part of '../smart_geofence_manager.dart';

class _SmartGeofenceRegistrationSynchronizer {
  _SmartGeofenceRegistrationSynchronizer({
    required MethodChannel channel,
    required bool Function() supportsSmartLayer,
  }) : _channel = channel,
       _supportsSmartLayer = supportsSmartLayer;

  final MethodChannel _channel;
  final bool Function() _supportsSmartLayer;
  _RegistrationTransaction? _activeRegistrationTransaction;

  static const Duration _transactionAcquireTimeout = Duration(seconds: 30);
  static const Duration _transactionRetryDelay = Duration(milliseconds: 20);

  bool get _smartLayerSupported => _supportsSmartLayer();

  Future<void> createGeofence(
    ng.Geofence geofence,
    SmartGeofenceCallback callback,
  ) async {
    final userCallbackHandle = PluginUtilities.getCallbackHandle(callback);
    if (userCallbackHandle == null) {
      throw ng.NativeGeofenceException.invalidArgument(
        message: 'Callback is invalid.',
      );
    }
    final dispatchCallbackHandle = PluginUtilities.getCallbackHandle(
      smartGeofenceCallbackDispatcher,
    );
    if (dispatchCallbackHandle == null) {
      throw ng.NativeGeofenceException.invalidArgument(
        message: 'Smart callback dispatcher is invalid.',
      );
    }
    final userCallbackContext = userCallbackHandle.toRawHandle();

    if (!_smartLayerSupported) {
      await ng.NativeGeofenceManager.instance.createGeofence(
        geofence,
        smartGeofenceCallbackDispatcher,
        callbackContext: userCallbackContext,
      );
      return;
    }
    await _withRegistrationTransaction(
      'createGeofence:${geofence.id}',
      () async {
        await _createSmartGeofence(
          geofence,
          userCallbackContext,
          dispatchCallbackHandle.toRawHandle(),
        );
      },
      cleanupFenceIds: <String>{geofence.id},
    );
  }

  Future<void> _createSmartGeofence(
    ng.Geofence geofence,
    int userCallbackContext,
    int dispatchCallbackHandle,
  ) async {
    final radiusNormalization = _androidRadiusNormalization(geofence);
    if (radiusNormalization.wasAdjusted) {
      debugPrint(
        'smart_geofence: radius normalization '
        '${radiusNormalization.toJson()}.',
      );
      geofence = _withRadius(
        geofence,
        radiusNormalization.effectiveRadiusMeters,
      );
    }
    final previousNative = await _registeredNativeGeofenceById(geofence.id);
    if (previousNative != null) {
      final previousMirror = await _fenceMirrorById(geofence.id);
      _requireRestorableNativeCallbacks(
        [previousNative],
        [?previousMirror],
        {geofence.id},
        operation: 'smart_geofence createGeofence',
      );
    }
    final previousMirror = await _registerFenceMirror(
      geofence,
      userCallbackContext,
      dispatchCallbackHandle: dispatchCallbackHandle,
      radiusNormalization: radiusNormalization,
      armed: false,
      refresh: false,
      resetState: false,
    );
    var nativeRegistrationSucceeded = false;
    try {
      await ng.NativeGeofenceManager.instance.createGeofence(
        _nativeSubscriptionGeofence(geofence),
        smartGeofenceCallbackDispatcher,
        callbackContext: userCallbackContext,
      );
      nativeRegistrationSucceeded = true;
      await _registerFenceMirror(
        geofence,
        userCallbackContext,
        dispatchCallbackHandle: dispatchCallbackHandle,
        radiusNormalization: radiusNormalization,
        armed: true,
        refresh: true,
        resetState: _meaningfulMirrorDefinitionChanged(
          previousMirror,
          _fenceMirrorMap(
            geofence,
            userCallbackContext,
            dispatchCallbackHandle: dispatchCallbackHandle,
            radiusNormalization: radiusNormalization,
          ),
        ),
      );
    } catch (error, stackTrace) {
      final rollbackFailures = <String>[];
      if (nativeRegistrationSucceeded) {
        final failure = await _restoreNativeRegistration(
          geofence.id,
          previousNative,
          previousMirror,
        );
        if (failure != null) rollbackFailures.add(failure);
      }
      final mirrorFailure = await _restoreFenceMirror(
        geofence.id,
        previousMirror,
      );
      if (mirrorFailure != null) rollbackFailures.add(mirrorFailure);
      await _reportGeofenceSync(
        result: rollbackFailures.isEmpty
            ? 'failed_rollback_succeeded'
            : 'failed_rollback_incomplete',
        desiredCount: 1,
        previousCount: previousNative == null ? 0 : 1,
        error: error,
        rollbackFailures: rollbackFailures,
      );
      Error.throwWithStackTrace(
        _rollbackAwareFailure(
          'smart_geofence createGeofence',
          error,
          rollbackFailures,
        ),
        stackTrace,
      );
    }
  }

  Future<SGLegacyAdoptionResult> adoptLegacyRegistrations(
    List<SGLegacyGeofenceRegistration> registrations,
  ) async {
    if (registrations.isEmpty) {
      return const SGLegacyAdoptionResult(
        complete: true,
        changed: false,
        items: <SGLegacyAdoptionItemResult>[],
      );
    }
    if (!_smartLayerSupported) {
      return SGLegacyAdoptionResult(
        complete: false,
        changed: false,
        items: registrations
            .map(
              (registration) => SGLegacyAdoptionItemResult(
                id: registration.geofence.id,
                status: SGLegacyAdoptionStatus.unsupportedPlatform,
                requiresRemoveAndReregister: false,
                message:
                    'Legacy smart-layer adoption is only required on Android.',
              ),
            )
            .toList(growable: false),
      );
    }
    return _withRegistrationTransaction(
      'adoptLegacyRegistrations',
      () => _adoptLegacyRegistrations(registrations),
      advanceRevisionWhen: (result) => result.changed,
      cleanupFenceIds: registrations
          .map((registration) => registration.geofence.id)
          .toSet(),
    );
  }

  Future<SGLegacyAdoptionResult> _adoptLegacyRegistrations(
    List<SGLegacyGeofenceRegistration> registrations,
  ) async {
    final desired = _prepareSmartRegistrations(
      registrations
          .map(
            (registration) => SmartGeofenceRegistration(
              geofence: registration.geofence,
              callback: registration.callback,
            ),
          )
          .toList(growable: false),
    );
    final inputsById = <String, SGLegacyGeofenceRegistration>{
      for (final registration in registrations)
        registration.geofence.id: registration,
    };
    final nativeById = <String, ng.ActiveGeofence>{
      for (final geofence
          in await ng.NativeGeofenceManager.instance.getRegisteredGeofences())
        geofence.id: geofence,
    };
    final mirrorsById = <String, Map<Object?, Object?>>{
      for (final mirror in await _fenceMirrors())
        if (mirror['id'] is String) mirror['id'] as String: mirror,
    };
    final resultsById = <String, SGLegacyAdoptionItemResult>{};
    final candidates = <_LegacyAdoptionCandidate>[];

    for (final prepared in desired.registrations) {
      final id = prepared.geofence.id;
      final input = inputsById[id]!;
      final native = nativeById[id];
      if (native == null) {
        resultsById[id] = SGLegacyAdoptionItemResult(
          id: id,
          status: SGLegacyAdoptionStatus.nativeRegistrationMissing,
          requiresRemoveAndReregister: true,
          message:
              'No native registration exists for $id; register it through '
              'SmartGeofenceManager instead.',
        );
        continue;
      }
      final mirror = mirrorsById[id];
      if (mirror != null) {
        final expectedMirror = _fenceMirrorMap(
          prepared.geofence,
          prepared.callbackHandle,
          dispatchCallbackHandle: desired.dispatcherHandle,
          radiusNormalization: prepared.radiusNormalization,
        );
        final matches =
            _mirrorSetsEqual([mirror], [expectedMirror]) &&
            _activeGeofenceMatchesDesired(native, prepared.nativeGeofence);
        final hasRollbackMetadata =
            _nativeCallbackFromMirror(mirror) != null &&
            _intFromObject(mirror['callbackHandle']) != null;
        resultsById[id] = SGLegacyAdoptionItemResult(
          id: id,
          status: matches
              ? SGLegacyAdoptionStatus.alreadyAdopted
              : hasRollbackMetadata
              ? SGLegacyAdoptionStatus.smartRegistrationConflict
              : SGLegacyAdoptionStatus.rollbackMetadataUnavailable,
          requiresRemoveAndReregister: !matches && !hasRollbackMetadata,
          message: matches
              ? 'The registration is already owned by smart_geofence.'
              : hasRollbackMetadata
              ? 'The registration already has different restorable smart '
                    'metadata; use ensureSynchronized() instead of legacy '
                    'adoption.'
              : 'The existing smart metadata cannot restore its native '
                    'callback; remove and re-register this ID explicitly.',
        );
        continue;
      }
      final legacyCallback = input.legacyCallback;
      final legacyContext = input.legacyCallbackContext;
      final legacyHandle = legacyCallback == null
          ? null
          : PluginUtilities.getCallbackHandle(legacyCallback);
      final legacyContextValue = legacyContext?.value;
      final legacyContextFitsTransport =
          legacyContextValue == null ||
          (legacyContextValue >= -9223372036854775808 &&
              legacyContextValue <= 9223372036854775807);
      if (legacyCallback == null ||
          legacyContext == null ||
          legacyHandle == null ||
          !legacyContextFitsTransport) {
        resultsById[id] = SGLegacyAdoptionItemResult(
          id: id,
          status: SGLegacyAdoptionStatus.rollbackMetadataUnavailable,
          requiresRemoveAndReregister: true,
          message:
              'Exact live legacy callback/context metadata is unavailable for '
              '$id; remove it and register it through SmartGeofenceManager.',
        );
        continue;
      }
      candidates.add(
        _LegacyAdoptionCandidate(
          input: input,
          prepared: prepared,
          previousNative: native,
        ),
      );
    }

    final adopted = <_LegacyAdoptionCandidate>[];
    for (var index = 0; index < candidates.length; index++) {
      final candidate = candidates[index];
      final id = candidate.prepared.geofence.id;
      var nativeAttempted = false;
      try {
        await _registerFenceMirror(
          candidate.prepared.geofence,
          candidate.prepared.callbackHandle,
          dispatchCallbackHandle: desired.dispatcherHandle,
          radiusNormalization: candidate.prepared.radiusNormalization,
          armed: false,
          refresh: false,
          resetState: false,
        );
        nativeAttempted = true;
        await ng.NativeGeofenceManager.instance.createGeofence(
          candidate.prepared.nativeGeofence,
          smartGeofenceCallbackDispatcher,
          callbackContext: candidate.prepared.callbackHandle,
        );
        await _registerFenceMirror(
          candidate.prepared.geofence,
          candidate.prepared.callbackHandle,
          dispatchCallbackHandle: desired.dispatcherHandle,
          radiusNormalization: candidate.prepared.radiusNormalization,
          armed: true,
          refresh: true,
          resetState: false,
        );
        adopted.add(candidate);
        resultsById[id] = SGLegacyAdoptionItemResult(
          id: id,
          status: SGLegacyAdoptionStatus.adopted,
          requiresRemoveAndReregister: false,
          message:
              'The native registration now uses the smart dispatcher and '
              'current callback context.',
        );
      } catch (error) {
        final rollbackTargets = <(_LegacyAdoptionCandidate, bool)>[
          (candidate, nativeAttempted),
          for (final previous in adopted.reversed) (previous, true),
        ];
        final rollbackFailures = <String, List<String>>{};
        for (final (target, restoreNative) in rollbackTargets) {
          final failures = await _rollbackLegacyAdoption(
            target,
            restoreNative: restoreNative,
          );
          if (failures.isNotEmpty) {
            rollbackFailures[target.prepared.geofence.id] = failures;
          }
        }
        for (final (target, _) in rollbackTargets) {
          final targetId = target.prepared.geofence.id;
          final failures = rollbackFailures[targetId] ?? const <String>[];
          resultsById[targetId] = SGLegacyAdoptionItemResult(
            id: targetId,
            status: failures.isEmpty
                ? SGLegacyAdoptionStatus.rolledBackAfterFailure
                : SGLegacyAdoptionStatus.rollbackIncomplete,
            requiresRemoveAndReregister: failures.isNotEmpty,
            message: failures.isEmpty
                ? 'Adoption failed and the exact legacy registration was '
                      'restored.'
                : 'Adoption failed and rollback was incomplete: '
                      '${failures.join(' ')}',
          );
        }
        for (final remaining in candidates.skip(index + 1)) {
          final remainingId = remaining.prepared.geofence.id;
          resultsById[remainingId] = SGLegacyAdoptionItemResult(
            id: remainingId,
            status: SGLegacyAdoptionStatus.notAttempted,
            requiresRemoveAndReregister: false,
            message: 'Not attempted because an earlier adoption failed.',
          );
        }
        final allRollbackFailures = rollbackFailures.values
            .expand((failures) => failures)
            .toList(growable: false);
        await _reportGeofenceSync(
          result: allRollbackFailures.isEmpty
              ? 'legacy_adoption_failed_rollback_succeeded'
              : 'legacy_adoption_failed_rollback_incomplete',
          desiredCount: registrations.length,
          previousCount: nativeById.length,
          error: error,
          rollbackFailures: allRollbackFailures,
        );
        return _legacyAdoptionResult(
          registrations,
          resultsById,
          changed: allRollbackFailures.isNotEmpty,
        );
      }
    }
    return _legacyAdoptionResult(
      registrations,
      resultsById,
      changed: adopted.isNotEmpty,
    );
  }

  SGLegacyAdoptionResult _legacyAdoptionResult(
    List<SGLegacyGeofenceRegistration> registrations,
    Map<String, SGLegacyAdoptionItemResult> resultsById, {
    required bool changed,
  }) {
    final items = registrations
        .map((registration) => resultsById[registration.geofence.id]!)
        .toList(growable: false);
    return SGLegacyAdoptionResult(
      complete: items.every(
        (item) =>
            item.status == SGLegacyAdoptionStatus.adopted ||
            item.status == SGLegacyAdoptionStatus.alreadyAdopted,
      ),
      changed: changed,
      items: items,
    );
  }

  Future<List<String>> _rollbackLegacyAdoption(
    _LegacyAdoptionCandidate candidate, {
    required bool restoreNative,
  }) async {
    final id = candidate.prepared.geofence.id;
    final failures = <String>[];
    if (restoreNative) {
      try {
        await ng.NativeGeofenceManager.instance.restoreGeofence(
          _geofenceFromActive(candidate.previousNative),
          candidate.input.legacyCallback!,
          callbackContext: candidate.input.legacyCallbackContext!.value,
          expirationDeadline: candidate.previousNative.expirationDeadline,
        );
      } catch (error) {
        failures.add('legacy native rollback failed for $id: $error');
      }
    }
    final mirrorFailure = await _restoreFenceMirror(id, null);
    if (mirrorFailure != null) failures.add(mirrorFailure);
    return failures;
  }

  bool _activeGeofenceMatchesDesired(
    ng.ActiveGeofence active,
    ng.Geofence desired,
  ) =>
      active.id == desired.id &&
      active.location.latitude == desired.location.latitude &&
      active.location.longitude == desired.location.longitude &&
      active.radiusMeters == desired.radiusMeters &&
      active.triggers.length == desired.triggers.length &&
      active.triggers.containsAll(desired.triggers) &&
      active.androidSettings?.toJson().toString() ==
          desired.androidSettings.toJson().toString();

  Future<SGSynchronizationReport> ensureSynchronized(
    List<SmartGeofenceRegistration> registrations, {
    bool removeUnlisted = true,
  }) => _smartLayerSupported
      ? _withRegistrationTransaction(
          'ensureSynchronized',
          () => _ensureSynchronized(
            registrations,
            removeUnlisted: removeUnlisted,
          ),
          cleanupFenceIds: registrations
              .map((registration) => registration.geofence.id)
              .toSet(),
        )
      : _ensureSynchronized(registrations, removeUnlisted: removeUnlisted);

  Future<SGMonitoringInspection> getMonitoringState(
    List<SmartGeofenceRegistration> registrations, {
    bool removeUnlisted = true,
  }) => _smartLayerSupported
      ? _withRegistrationTransaction(
          'getMonitoringState',
          () => _getMonitoringState(
            registrations,
            removeUnlisted: removeUnlisted,
          ),
          advanceRevision: false,
        )
      : _getMonitoringState(registrations, removeUnlisted: removeUnlisted);

  Future<SGMonitoringInspection> _getMonitoringState(
    List<SmartGeofenceRegistration> registrations, {
    required bool removeUnlisted,
  }) async {
    final desired = _prepareSmartRegistrations(registrations);
    final nativeInspection = await ng.NativeGeofenceManager.instance
        .inspectSynchronization(
          desired.nativeRegistrations,
          removeUnlisted: removeUnlisted,
        );
    final desiredIds =
        desired.registrations
            .map((registration) => registration.geofence.id)
            .toList()
          ..sort();
    final reasons = _smartSynchronizationReasons(nativeInspection.reasons);
    var mirroredIds = const <String>[];
    var mirrorMatches = true;
    final missingIds = nativeInspection.missingIds.toSet();
    final unlistedIds = nativeInspection.unlistedIds.toSet();
    var relevantRegistrationExists = nativeInspection.currentIds.any(
      desiredIds.contains,
    );

    if (_smartLayerSupported) {
      final currentMirrors = await _fenceMirrors();
      final desiredMirrors = desired.registrations
          .map(
            (registration) => _fenceMirrorMap(
              registration.geofence,
              registration.callbackHandle,
              dispatchCallbackHandle: desired.dispatcherHandle,
              radiusNormalization: registration.radiusNormalization,
            ),
          )
          .toList(growable: false);
      mirroredIds =
          currentMirrors
              .map((mirror) => mirror['id'])
              .whereType<String>()
              .toSet()
              .toList()
            ..sort();
      mirrorMatches = _mirrorsMatch(
        currentMirrors,
        desiredMirrors,
        removeUnlisted: removeUnlisted,
      );
      final mirroredIdSet = mirroredIds.toSet();
      missingIds.addAll(desiredIds.where((id) => !mirroredIdSet.contains(id)));
      if (removeUnlisted) {
        unlistedIds.addAll(mirroredIds.where((id) => !desiredIds.contains(id)));
      }
      relevantRegistrationExists =
          relevantRegistrationExists || mirroredIds.any(desiredIds.contains);
      if (!mirrorMatches) {
        reasons.add(SGSynchronizationReason.smartMirrorDrift);
      }
    }

    final matchesDesired = nativeInspection.matchesDesired && mirrorMatches;
    final state = desiredIds.isEmpty
        ? SGMonitoringState.inactive
        : matchesDesired
        ? SGMonitoringState.synchronized
        : relevantRegistrationExists
        ? SGMonitoringState.drifted
        : SGMonitoringState.inactive;
    return SGMonitoringInspection(
      state: state,
      matchesDesired: matchesDesired,
      desiredIds: desiredIds,
      nativeIds: nativeInspection.currentIds,
      mirroredIds: mirroredIds,
      missingIds: missingIds.toList()..sort(),
      unlistedIds: unlistedIds.toList()..sort(),
      reasons: reasons,
    );
  }

  Future<bool> isMonitoring(
    List<SmartGeofenceRegistration> registrations, {
    bool removeUnlisted = true,
  }) async {
    if (registrations.isEmpty) return false;
    final inspection = await getMonitoringState(
      registrations,
      removeUnlisted: removeUnlisted,
    );
    return inspection.state == SGMonitoringState.synchronized;
  }

  Future<SGSynchronizationReport> _ensureSynchronized(
    List<SmartGeofenceRegistration> registrations, {
    required bool removeUnlisted,
  }) async {
    final desired = _prepareSmartRegistrations(registrations);
    final resolved = desired.registrations;
    final nativeRegistrations = desired.nativeRegistrations;
    final dispatcherHandle = desired.dispatcherHandle;

    if (!_smartLayerSupported) {
      final report = await ng.NativeGeofenceManager.instance.ensureSynchronized(
        nativeRegistrations,
        removeUnlisted: removeUnlisted,
      );
      return _smartSynchronizationReport(report);
    }

    final previousNative = await ng.NativeGeofenceManager.instance
        .getRegisteredGeofences();
    final previousMirrors = await _fenceMirrors();
    final desiredNativeIds = <String>{
      for (final registration in nativeRegistrations) registration.geofence.id,
    };
    final desiredMirrors = <String, Map<String, Object?>>{};
    if (!removeUnlisted) {
      for (final mirror in previousMirrors) {
        final id = mirror['id'];
        if (id is String) {
          desiredMirrors[id] = mirror.map<String, Object?>(
            (key, value) => MapEntry(key.toString(), value),
          );
        }
      }
    }
    for (final registration in resolved) {
      desiredMirrors[registration.geofence.id] = _fenceMirrorMap(
        registration.geofence,
        registration.callbackHandle,
        dispatchCallbackHandle: dispatcherHandle,
        radiusNormalization: registration.radiusNormalization,
      );
    }
    final mirrorDrift = !_mirrorSetsEqual(
      previousMirrors,
      desiredMirrors.values.toList(),
    );
    if (mirrorDrift) {
      final rollbackScopeIds = removeUnlisted
          ? <String>{for (final geofence in previousNative) geofence.id}
          : desiredNativeIds;
      _requireRestorableNativeCallbacks(
        previousNative,
        previousMirrors,
        rollbackScopeIds,
        operation: 'smart_geofence ensureSynchronized',
      );
    }

    var nativeSynchronizationCompleted = false;
    var nativeMutationOccurred = false;
    try {
      final nativeReport = await ng.NativeGeofenceManager.instance
          .ensureSynchronized(
            nativeRegistrations,
            removeUnlisted: removeUnlisted,
          );
      nativeSynchronizationCompleted = true;
      nativeMutationOccurred = nativeReport.didSynchronize;
      if (mirrorDrift) {
        await _replaceFenceMirrors(desiredMirrors.values.toList());
      }
      final report = _smartSynchronizationReport(nativeReport);
      return SGSynchronizationReport(
        synchronized: report.synchronized || mirrorDrift,
        reasons: {
          ...report.reasons,
          if (mirrorDrift) SGSynchronizationReason.smartMirrorDrift,
        },
        desiredCount: report.desiredCount,
        previousCount: report.previousCount,
      );
    } catch (error, stackTrace) {
      final rollbackFailures = <String>[
        if (nativeSynchronizationCompleted) ...[
          if (nativeMutationOccurred)
            ...await _restoreExactNativeRegistrations(
              previousNative,
              previousMirrors,
              touchedIds: desiredNativeIds,
              removeUnlisted: removeUnlisted,
            ),
          ...await _restoreFenceMirrors(previousMirrors),
        ],
      ];
      await _reportGeofenceSync(
        result: rollbackFailures.isEmpty
            ? 'failed_rollback_succeeded'
            : 'failed_rollback_incomplete',
        desiredCount: registrations.length,
        previousCount: previousNative.length,
        error: error,
        rollbackFailures: rollbackFailures,
      );
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  _PreparedSmartRegistrations _prepareSmartRegistrations(
    List<SmartGeofenceRegistration> registrations,
  ) {
    final ids = registrations.map((registration) => registration.geofence.id);
    if (ids.toSet().length != registrations.length) {
      throw ng.NativeGeofenceException.invalidArgument(
        message: 'Registrations contain duplicate geofence IDs.',
      );
    }

    final dispatcherCallbackHandle = PluginUtilities.getCallbackHandle(
      smartGeofenceCallbackDispatcher,
    );
    if (dispatcherCallbackHandle == null) {
      throw ng.NativeGeofenceException.invalidArgument(
        message: 'Smart callback dispatcher is invalid.',
      );
    }

    final resolved = <_PreparedSmartRegistration>[];
    for (final registration in registrations) {
      final callbackHandle = PluginUtilities.getCallbackHandle(
        registration.callback,
      );
      if (callbackHandle == null) {
        throw ng.NativeGeofenceException.invalidArgument(
          message:
              'Callback is invalid for geofence ${registration.geofence.id}.',
        );
      }
      var geofence = registration.geofence;
      final radiusNormalization = _smartLayerSupported
          ? _androidRadiusNormalization(geofence)
          : SGRadiusNormalization(
              platform: defaultTargetPlatform.name,
              requestedRadiusMeters: geofence.radiusMeters,
              effectiveRadiusMeters: geofence.radiusMeters,
              reason: SGRadiusNormalizationReason.none,
            );
      if (radiusNormalization.wasAdjusted) {
        geofence = _withRadius(
          geofence,
          radiusNormalization.effectiveRadiusMeters,
        );
      }
      resolved.add(
        _PreparedSmartRegistration(
          geofence: geofence,
          nativeGeofence: _smartLayerSupported
              ? _nativeSubscriptionGeofence(geofence)
              : geofence,
          callbackHandle: callbackHandle.toRawHandle(),
          radiusNormalization: radiusNormalization,
        ),
      );
    }
    return _PreparedSmartRegistrations(
      registrations: resolved,
      dispatcherHandle: dispatcherCallbackHandle.toRawHandle(),
    );
  }

  SGSynchronizationReport _smartSynchronizationReport(
    ng.NativeGeofenceSynchronizationReport report,
  ) {
    return SGSynchronizationReport(
      synchronized: report.didSynchronize,
      reasons: _smartSynchronizationReasons(report.reasons),
      desiredCount: report.desiredCount,
      previousCount: report.previousCount,
    );
  }

  bool _mirrorSetsEqual(
    List<Map<Object?, Object?>> current,
    List<Map<String, Object?>> desired,
  ) {
    Object? normalize(Object? value) {
      if (value is Map) {
        return <String, Object?>{
          for (final entry in value.entries)
            entry.key.toString(): normalize(entry.value),
        };
      }
      if (value is Iterable) {
        return value.map(normalize).toList(growable: false);
      }
      return value;
    }

    String key(Map map) => (normalize(map) as Map<String, Object?>).toString();
    final currentKeys = current.map(key).toList()..sort();
    final desiredKeys = desired.map(key).toList()..sort();
    if (currentKeys.length != desiredKeys.length) return false;
    for (var index = 0; index < currentKeys.length; index++) {
      if (currentKeys[index] != desiredKeys[index]) return false;
    }
    return true;
  }

  bool _mirrorsMatch(
    List<Map<Object?, Object?>> current,
    List<Map<String, Object?>> desired, {
    required bool removeUnlisted,
  }) {
    if (removeUnlisted) return _mirrorSetsEqual(current, desired);
    final currentById = <String, Map<Object?, Object?>>{
      for (final mirror in current)
        if (mirror['id'] is String) mirror['id'] as String: mirror,
    };
    for (final wanted in desired) {
      final id = wanted['id'];
      final existing = currentById[id];
      if (existing == null || !_mirrorSetsEqual([existing], [wanted])) {
        return false;
      }
    }
    return true;
  }

  Future<ng.ActiveGeofence?> _registeredNativeGeofenceById(String id) async {
    final geofences = await ng.NativeGeofenceManager.instance
        .getRegisteredGeofences();
    for (final geofence in geofences) {
      if (geofence.id == id) return geofence;
    }
    return null;
  }

  Future<String?> _restoreNativeRegistration(
    String id,
    ng.ActiveGeofence? previousNative,
    Map<Object?, Object?>? previousMirror,
  ) async {
    try {
      if (previousNative == null) {
        await ng.NativeGeofenceManager.instance.removeGeofenceById(id);
        return null;
      }
      final callback = _nativeCallbackFromMirror(previousMirror);
      if (callback == null) {
        final message =
            'unable to restore previous callback for geofence $id; '
            'native registration was not restored';
        debugPrint('smart_geofence: $message.');
        return message;
      }
      await ng.NativeGeofenceManager.instance.restoreGeofence(
        _geofenceFromActive(previousNative),
        callback,
        callbackContext: _intFromObject(previousMirror?['callbackHandle']),
        expirationDeadline: previousNative.expirationDeadline,
      );
      return null;
    } on ng.NativeGeofenceException catch (error) {
      if (previousNative == null &&
          error.code == ng.NativeGeofenceErrorCode.geofenceNotFound) {
        return null;
      }
      final message = 'native rollback failed for geofence $id: $error';
      debugPrint('smart_geofence: $message');
      return message;
    } catch (error) {
      final message = 'native rollback failed for geofence $id: $error';
      debugPrint('smart_geofence: $message');
      return message;
    }
  }

  ng.GeofenceCallback? _nativeCallbackFromMirror(
    Map<Object?, Object?>? mirror,
  ) {
    if (mirror == null) return null;
    final rawHandle = _intFromObject(mirror['dispatchCallbackHandle']);
    if (rawHandle != null && rawHandle != 0) {
      return smartGeofenceCallbackDispatcher;
    }
    return null;
  }

  void _requireRestorableNativeCallbacks(
    List<ng.ActiveGeofence> geofences,
    List<Map<Object?, Object?>> mirrors,
    Set<String> scopeIds, {
    required String operation,
  }) {
    final mirrorsById = <String, Map<Object?, Object?>>{
      for (final mirror in mirrors)
        if (mirror['id'] is String) mirror['id'] as String: mirror,
    };
    final missingIds =
        geofences
            .where((geofence) => scopeIds.contains(geofence.id))
            .where(
              (geofence) =>
                  _nativeCallbackFromMirror(mirrorsById[geofence.id]) == null,
            )
            .map((geofence) => geofence.id)
            .toList()
          ..sort();
    if (missingIds.isEmpty) return;
    throw StateError(
      '$operation cannot start because exact rollback callback metadata is '
      'unavailable for: ${missingIds.join(', ')}.',
    );
  }

  int? _intFromObject(Object? value) => switch (value) {
    int() => value,
    double() => value.toInt(),
    _ => null,
  };

  ng.Geofence _withRadius(ng.Geofence geofence, double radiusMeters) =>
      ng.Geofence(
        id: geofence.id,
        location: geofence.location,
        radiusMeters: radiusMeters,
        triggers: geofence.triggers,
        iosSettings: geofence.iosSettings,
        androidSettings: geofence.androidSettings,
      );

  ng.Geofence _nativeSubscriptionGeofence(ng.Geofence geofence) => ng.Geofence(
    id: geofence.id,
    location: geofence.location,
    radiusMeters: geofence.radiusMeters,
    triggers: <ng.GeofenceEvent>{
      ...geofence.triggers,
      ng.GeofenceEvent.enter,
      ng.GeofenceEvent.exit,
    },
    iosSettings: geofence.iosSettings,
    androidSettings: _androidSettingsWithSmartDefaults(
      geofence.androidSettings,
    ),
  );

  ng.AndroidGeofenceSettings _androidSettingsWithSmartDefaults(
    ng.AndroidGeofenceSettings settings,
  ) => ng.AndroidGeofenceSettings(
    initialTriggers: settings.initialTriggers,
    expiration: settings.expiration,
    loiteringDelay: settings.loiteringDelay,
    notificationResponsiveness:
        settings.notificationResponsiveness ?? Duration.zero,
  );

  SGRadiusNormalization _androidRadiusNormalization(ng.Geofence geofence) {
    final requestedRadiusMeters = geofence.radiusMeters;
    final effectiveRadiusMeters =
        requestedRadiusMeters < smartGeofenceAndroidMinimumRadiusMeters
        ? smartGeofenceAndroidMinimumRadiusMeters
        : requestedRadiusMeters;
    return SGRadiusNormalization(
      platform: 'android',
      requestedRadiusMeters: requestedRadiusMeters,
      effectiveRadiusMeters: effectiveRadiusMeters,
      reason: requestedRadiusMeters == effectiveRadiusMeters
          ? SGRadiusNormalizationReason.none
          : SGRadiusNormalizationReason.androidMinimum,
    );
  }

  ng.Geofence _geofenceFromActive(ng.ActiveGeofence geofence) => ng.Geofence(
    id: geofence.id,
    location: geofence.location,
    radiusMeters: geofence.radiusMeters,
    triggers: geofence.triggers,
    iosSettings: const ng.IosGeofenceSettings(),
    androidSettings: _androidSettingsWithoutInitialTriggers(
      geofence.androidSettings,
    ),
  );

  ng.AndroidGeofenceSettings _androidSettingsWithoutInitialTriggers(
    ng.AndroidGeofenceSettings? settings,
  ) => ng.AndroidGeofenceSettings(
    initialTriggers: const <ng.GeofenceEvent>{},
    expiration: settings?.expiration,
    loiteringDelay: settings?.loiteringDelay ?? Duration.zero,
    notificationResponsiveness:
        settings?.notificationResponsiveness ?? Duration.zero,
  );

  Future<Map<Object?, Object?>?> _registerFenceMirror(
    ng.Geofence geofence,
    int callbackHandle, {
    int? dispatchCallbackHandle,
    required SGRadiusNormalization radiusNormalization,
    bool armed = true,
    bool refresh = true,
    bool resetState = false,
  }) async {
    final raw = await _channel.invokeMethod<Object?>(
      'registerFence',
      _registrationTransactionArguments({
        ..._fenceMirrorMap(
          geofence,
          callbackHandle,
          dispatchCallbackHandle: dispatchCallbackHandle,
          radiusNormalization: radiusNormalization,
          armed: armed,
        ),
        'refresh': refresh,
        'resetState': resetState,
      }),
    );
    return raw is Map ? raw.cast<Object?, Object?>() : null;
  }

  Map<String, Object?> _fenceMirrorMap(
    ng.Geofence geofence,
    int callbackHandle, {
    int? dispatchCallbackHandle,
    required SGRadiusNormalization radiusNormalization,
    bool armed = true,
  }) {
    return <String, Object?>{
      'id': geofence.id,
      'latitude': geofence.location.latitude,
      'longitude': geofence.location.longitude,
      'radiusMeters': geofence.radiusMeters,
      'requestedRadiusMeters': radiusNormalization.requestedRadiusMeters,
      'effectiveRadiusMeters': radiusNormalization.effectiveRadiusMeters,
      'radiusNormalization': radiusNormalization.toJson(),
      'triggers': geofence.triggers.map((event) => event.name).toList()..sort(),
      'callbackHandle': callbackHandle,
      'dispatchCallbackHandle': dispatchCallbackHandle ?? callbackHandle,
      'armed': armed,
    };
  }

  bool _meaningfulMirrorDefinitionChanged(
    Map<Object?, Object?>? previous,
    Map<String, Object?> desired,
  ) {
    if (previous == null) return false;
    const meaningfulKeys = <String>{
      'id',
      'latitude',
      'longitude',
      'radiusMeters',
      'triggers',
    };
    Object? normalized(Object? value) {
      if (value is Iterable) {
        final values = value.map((item) => item.toString()).toList()..sort();
        return values.join('\u0000');
      }
      return value;
    }

    for (final key in meaningfulKeys) {
      if (normalized(previous[key]) != normalized(desired[key])) return true;
    }
    return false;
  }

  Future<void> _replaceFenceMirrors(
    List<Map<Object?, Object?>> mirrors, {
    bool refresh = true,
    bool applyStateChanges = true,
  }) async {
    final normalizedMirrors = mirrors
        .map(
          (mirror) => mirror.map<String, Object?>(
            (key, value) => MapEntry(key.toString(), value),
          ),
        )
        .toList(growable: false);
    await _channel.invokeMethod(
      'replaceFenceMirrors',
      _registrationTransactionArguments({
        'fences': normalizedMirrors,
        'refresh': refresh,
        'applyStateChanges': applyStateChanges,
      }),
    );
  }

  Future<String?> _restoreFenceMirror(
    String id,
    Map<Object?, Object?>? previousMirror, {
    bool refresh = true,
  }) async {
    try {
      if (previousMirror == null) {
        await _channel.invokeMethod(
          'removeFence',
          _registrationTransactionArguments({'id': id}),
        );
        return null;
      }
      final restoredMirror = previousMirror.map<String, Object?>(
        (key, value) => MapEntry(key.toString(), value),
      );
      restoredMirror['refresh'] = refresh;
      restoredMirror['resetState'] = false;
      await _channel.invokeMethod(
        'registerFence',
        _registrationTransactionArguments(restoredMirror),
      );
      return null;
    } catch (error) {
      final message = 'smart mirror rollback failed for geofence $id: $error';
      debugPrint('smart_geofence: $message');
      return message;
    }
  }

  Future<void> removeGeofence(ng.Geofence geofence) =>
      removeGeofenceById(geofence.id);

  Future<void> removeGeofenceById(String id) async {
    if (!_smartLayerSupported) {
      await ng.NativeGeofenceManager.instance.removeGeofenceById(id);
      return;
    }
    await _withRegistrationTransaction(
      'removeGeofenceById:$id',
      () => _removeSmartGeofenceById(id),
    );
  }

  Future<void> _removeSmartGeofenceById(String id) async {
    final previousNative = await _registeredNativeGeofenceById(id);
    final previousMirror = await _fenceMirrorById(id);
    try {
      await ng.NativeGeofenceManager.instance.removeGeofenceById(id);
    } on ng.NativeGeofenceException catch (error) {
      if (error.code != ng.NativeGeofenceErrorCode.geofenceNotFound) rethrow;
    }
    try {
      await _channel.invokeMethod(
        'removeFence',
        _registrationTransactionArguments({'id': id}),
      );
    } catch (error, stackTrace) {
      final rollbackFailures = <String>[];
      final nativeFailure = await _restoreNativeRegistration(
        id,
        previousNative,
        previousMirror,
      );
      if (nativeFailure != null) rollbackFailures.add(nativeFailure);
      final mirrorFailure = await _restoreFenceMirror(id, previousMirror);
      if (mirrorFailure != null) rollbackFailures.add(mirrorFailure);
      await _reportGeofenceSync(
        result: rollbackFailures.isEmpty
            ? 'failed_rollback_succeeded'
            : 'failed_rollback_incomplete',
        desiredCount: 0,
        previousCount: previousNative == null ? 0 : 1,
        error: error,
        rollbackFailures: rollbackFailures,
      );
      Error.throwWithStackTrace(
        _rollbackAwareFailure(
          'smart_geofence removeGeofenceById',
          error,
          rollbackFailures,
        ),
        stackTrace,
      );
    }
  }

  Future<void> removeAllGeofences() async {
    if (!_smartLayerSupported) {
      await ng.NativeGeofenceManager.instance.removeAllGeofences();
      return;
    }
    await _withRegistrationTransaction(
      'removeAllGeofences',
      _removeAllSmartGeofences,
    );
  }

  Future<void> _removeAllSmartGeofences() async {
    final previousNative = await ng.NativeGeofenceManager.instance
        .getRegisteredGeofences();
    final previousMirrors = await _fenceMirrors();
    _requireRestorableNativeCallbacks(
      previousNative,
      previousMirrors,
      previousNative.map((geofence) => geofence.id).toSet(),
      operation: 'smart_geofence removeAllGeofences',
    );
    await ng.NativeGeofenceManager.instance.removeAllGeofences();
    try {
      await _channel.invokeMethod(
        'removeAllFences',
        _registrationTransactionArguments(const <String, Object?>{}),
      );
    } catch (error, stackTrace) {
      final rollbackFailures = <String>[
        ...await _restoreNativeRegistrations(previousNative, previousMirrors),
        ...await _restoreFenceMirrors(previousMirrors),
      ];
      await _reportGeofenceSync(
        result: rollbackFailures.isEmpty
            ? 'failed_rollback_succeeded'
            : 'failed_rollback_incomplete',
        desiredCount: 0,
        previousCount: previousNative.length,
        error: error,
        rollbackFailures: rollbackFailures,
      );
      Error.throwWithStackTrace(
        _rollbackAwareFailure(
          'smart_geofence removeAllGeofences',
          error,
          rollbackFailures,
        ),
        stackTrace,
      );
    }
  }

  Future<Map<Object?, Object?>?> _fenceMirrorById(String id) async {
    final raw = await _channel.invokeMethod<Object?>('getFenceMirror', {
      'id': id,
    });
    return raw is Map ? raw.cast<Object?, Object?>() : null;
  }

  Future<List<Map<Object?, Object?>>> _fenceMirrors() async {
    final raw = await _channel.invokeMethod<Object?>('getFenceMirrors');
    if (raw is! List) return const <Map<Object?, Object?>>[];
    return raw
        .whereType<Map>()
        .map((value) => value.cast<Object?, Object?>())
        .toList(growable: false);
  }

  Future<List<String>> _restoreNativeRegistrations(
    List<ng.ActiveGeofence> geofences,
    List<Map<Object?, Object?>> mirrors,
  ) async {
    final mirrorsById = <String, Map<Object?, Object?>>{};
    for (final mirror in mirrors) {
      final id = mirror['id'];
      if (id is String) mirrorsById[id] = mirror;
    }
    final failures = <String>[];
    for (final geofence in geofences) {
      final failure = await _restoreNativeRegistration(
        geofence.id,
        geofence,
        mirrorsById[geofence.id],
      );
      if (failure != null) failures.add(failure);
    }
    return failures;
  }

  Future<List<String>> _restoreExactNativeRegistrations(
    List<ng.ActiveGeofence> geofences,
    List<Map<Object?, Object?>> mirrors, {
    required Set<String> touchedIds,
    required bool removeUnlisted,
  }) async {
    try {
      final mirrorsById = <String, Map<Object?, Object?>>{
        for (final mirror in mirrors)
          if (mirror['id'] is String) mirror['id'] as String: mirror,
      };
      if (removeUnlisted) {
        await ng.NativeGeofenceManager.instance.removeAllGeofences();
      } else {
        for (final id in touchedIds) {
          try {
            await ng.NativeGeofenceManager.instance.removeGeofenceById(id);
          } on ng.NativeGeofenceException catch (error) {
            if (error.code != ng.NativeGeofenceErrorCode.geofenceNotFound) {
              rethrow;
            }
          }
        }
      }
      final registrationsToRestore = removeUnlisted
          ? geofences
          : geofences
                .where((geofence) => touchedIds.contains(geofence.id))
                .toList(growable: false);
      for (final geofence in registrationsToRestore) {
        final mirror = mirrorsById[geofence.id];
        final callback = _nativeCallbackFromMirror(mirror);
        if (callback == null) {
          throw StateError(
            'Unable to restore callback metadata for geofence ${geofence.id}.',
          );
        }
        await ng.NativeGeofenceManager.instance.restoreGeofence(
          _geofenceFromActive(geofence),
          callback,
          callbackContext: _intFromObject(mirror?['callbackHandle']),
          expirationDeadline: geofence.expirationDeadline,
        );
      }
      return const <String>[];
    } catch (error) {
      final message = 'exact native synchronization rollback failed: $error';
      debugPrint('smart_geofence: $message');
      return <String>[message];
    }
  }

  Future<List<String>> _restoreFenceMirrors(
    List<Map<Object?, Object?>> mirrors,
  ) async {
    final failures = <String>[];
    try {
      await _replaceFenceMirrors(
        mirrors,
        refresh: false,
        applyStateChanges: false,
      );
    } catch (error) {
      final message = 'batch smart mirror rollback failed: $error';
      debugPrint('smart_geofence: $message');
      failures.add(message);
      for (final mirror in mirrors) {
        final id = mirror['id'];
        if (id is String) {
          final failure = await _restoreFenceMirror(id, mirror, refresh: false);
          if (failure != null) failures.add(failure);
        }
      }
    }
    try {
      await _channel.invokeMethod('start');
    } catch (error) {
      final message = 'smart layer rollback restart failed: $error';
      debugPrint('smart_geofence: $message');
      failures.add(message);
    }
    return failures;
  }

  Future<void> recoverNativeAndSmartLayers() async {
    if (!_smartLayerSupported) {
      await ng.NativeGeofenceManager.instance.reCreateAfterReboot();
      return;
    }
    await _withRegistrationTransaction('recoverNativeAndSmartLayers', () async {
      await ng.NativeGeofenceManager.instance.reCreateAfterReboot();
      await _channel.invokeMethod('start');
    });
  }

  Future<T> _withRegistrationTransaction<T>(
    String operation,
    Future<T> Function() action, {
    bool advanceRevision = true,
    bool Function(T value)? advanceRevisionWhen,
    Set<String> cleanupFenceIds = const <String>{},
  }) async {
    final transaction = await _acquireRegistrationTransaction(
      operation,
      cleanupFenceIds,
    );
    _activeRegistrationTransaction = transaction;
    var committed = false;
    try {
      final value = await action();
      await _channel.invokeMethod<Object?>('commitRegistrationTransaction', {
        'token': transaction.token,
        'revision': transaction.revision,
        'advanceRevision': advanceRevisionWhen?.call(value) ?? advanceRevision,
      });
      committed = true;
      return value;
    } finally {
      if (!committed) {
        try {
          await _channel.invokeMethod<Object?>('abortRegistrationTransaction', {
            'token': transaction.token,
          });
        } catch (error) {
          debugPrint(
            'smart_geofence: failed to abort registration transaction '
            '${transaction.token}: $error',
          );
        }
      }
      if (identical(_activeRegistrationTransaction, transaction)) {
        _activeRegistrationTransaction = null;
      }
    }
  }

  Future<_RegistrationTransaction> _acquireRegistrationTransaction(
    String operation,
    Set<String> cleanupFenceIds,
  ) async {
    final stopwatch = Stopwatch()..start();
    String? activeOperation;
    while (stopwatch.elapsed < _transactionAcquireTimeout) {
      final raw = await _channel.invokeMethod<Object?>(
        'beginRegistrationTransaction',
        {
          'operation': operation,
          'cleanupFenceIds': cleanupFenceIds.toList()..sort(),
        },
      );
      if (raw is! Map) {
        throw StateError(
          'Android returned a malformed registration transaction grant.',
        );
      }
      final grant = raw.cast<Object?, Object?>();
      if (grant['acquired'] == true) {
        final token = grant['token'];
        final revision = grant['revision'];
        if (token is! String || token.isEmpty || revision is! num) {
          throw StateError(
            'Android returned an incomplete registration transaction grant.',
          );
        }
        return _RegistrationTransaction(token, revision.toInt());
      }
      activeOperation = grant['activeOperation'] as String?;
      await Future<void>.delayed(_transactionRetryDelay);
    }
    throw StateError(
      'Timed out waiting to $operation because another registration mutation '
      'is still active${activeOperation == null ? '.' : ': $activeOperation.'}',
    );
  }

  Map<String, Object?> _registrationTransactionArguments(
    Map<Object?, Object?> arguments,
  ) {
    final transaction = _activeRegistrationTransaction;
    if (transaction == null) {
      throw StateError('Registration mirror mutation escaped its transaction.');
    }
    return <String, Object?>{
      for (final entry in arguments.entries) entry.key.toString(): entry.value,
      'registrationTransactionToken': transaction.token,
      'registrationTransactionRevision': transaction.revision,
    };
  }

  Future<List<String>> getRegisteredGeofenceIds() =>
      ng.NativeGeofenceManager.instance.getRegisteredGeofenceIds();

  Future<List<ng.ActiveGeofence>> getRegisteredGeofences() =>
      ng.NativeGeofenceManager.instance.getRegisteredGeofences();
}

class _RegistrationTransaction {
  const _RegistrationTransaction(this.token, this.revision);

  final String token;
  final int revision;
}
