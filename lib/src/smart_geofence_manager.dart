import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:native_geofence/native_geofence.dart' as ng;
import 'package:time_integrity/time_integrity.dart';

import 'smart_geofence_callback_params.dart';
import 'config/smart_geofence_config_transport.dart';
import 'smart_geofence_config.dart';
import 'smart_geofence_diagnostics.dart';
import 'smart_geofence_monitoring_stopped.dart';
import 'smart_geofence_readiness.dart';
import 'smart_geofence_status.dart';
import 'smart_geofence_time_integrity_logger.dart';
import 'smart_geofence_time_resolver.dart';

part 'registration/smart_geofence_registration_synchronizer.dart';

typedef SmartGeofenceCallback =
    Future<void> Function(SmartGeofenceCallbackParams params);

const double smartGeofenceAndroidMinimumRadiusMeters = 100;

class SmartGeofenceRegistration {
  const SmartGeofenceRegistration({
    required this.geofence,
    required this.callback,
  });

  final ng.Geofence geofence;
  final SmartGeofenceCallback callback;
}

class SGLegacyCallbackContext {
  const SGLegacyCallbackContext.none() : value = null;

  const SGLegacyCallbackContext.value(this.value);

  final int? value;
}

class SGLegacyGeofenceRegistration {
  const SGLegacyGeofenceRegistration({
    required this.geofence,
    required this.callback,
    this.legacyCallback,
    this.legacyCallbackContext,
  });

  final ng.Geofence geofence;
  final SmartGeofenceCallback callback;
  final ng.GeofenceCallback? legacyCallback;
  final SGLegacyCallbackContext? legacyCallbackContext;
}

enum SGLegacyAdoptionStatus {
  adopted,
  alreadyAdopted,
  rollbackMetadataUnavailable,
  nativeRegistrationMissing,
  smartRegistrationConflict,
  rolledBackAfterFailure,
  rollbackIncomplete,
  notAttempted,
  unsupportedPlatform,
}

class SGLegacyAdoptionItemResult {
  const SGLegacyAdoptionItemResult({
    required this.id,
    required this.status,
    required this.requiresRemoveAndReregister,
    required this.message,
  });

  final String id;
  final SGLegacyAdoptionStatus status;
  final bool requiresRemoveAndReregister;
  final String message;

  Map<String, Object?> toJson() => {
    'id': id,
    'status': status.name,
    'requiresRemoveAndReregister': requiresRemoveAndReregister,
    'message': message,
  };
}

class SGLegacyAdoptionResult {
  const SGLegacyAdoptionResult({
    required this.complete,
    required this.changed,
    required this.items,
  });

  final bool complete;
  final bool changed;
  final List<SGLegacyAdoptionItemResult> items;

  Map<String, Object?> toJson() => {
    'complete': complete,
    'changed': changed,
    'items': items.map((item) => item.toJson()).toList(growable: false),
  };
}

enum SGSynchronizationReason {
  firstRun,
  callbackFingerprintChanged,
  registrationDrift,
  smartMirrorDrift,
}

class SGSynchronizationReport {
  const SGSynchronizationReport({
    required this.synchronized,
    required this.reasons,
    required this.desiredCount,
    required this.previousCount,
  });

  final bool synchronized;
  final Set<SGSynchronizationReason> reasons;
  final int desiredCount;
  final int previousCount;

  Map<String, Object?> toJson() => {
    'synchronized': synchronized,
    'reasons': reasons.map((reason) => reason.name).toList()..sort(),
    'desiredCount': desiredCount,
    'previousCount': previousCount,
  };
}

enum SGMonitoringState { inactive, synchronized, drifted }

class SGMonitoringInspection {
  const SGMonitoringInspection({
    required this.state,
    required this.matchesDesired,
    required this.desiredIds,
    required this.nativeIds,
    required this.mirroredIds,
    required this.missingIds,
    required this.unlistedIds,
    required this.reasons,
  });

  final SGMonitoringState state;
  final bool matchesDesired;
  final List<String> desiredIds;
  final List<String> nativeIds;
  final List<String> mirroredIds;
  final List<String> missingIds;
  final List<String> unlistedIds;
  final Set<SGSynchronizationReason> reasons;

  Map<String, Object?> toJson() => {
    'state': state.name,
    'matchesDesired': matchesDesired,
    'desiredIds': [...desiredIds]..sort(),
    'nativeIds': [...nativeIds]..sort(),
    'mirroredIds': [...mirroredIds]..sort(),
    'missingIds': [...missingIds]..sort(),
    'unlistedIds': [...unlistedIds]..sort(),
    'reasons': reasons.map((reason) => reason.name).toList()..sort(),
  };
}

class _PreparedSmartRegistration {
  const _PreparedSmartRegistration({
    required this.geofence,
    required this.nativeGeofence,
    required this.callbackHandle,
    required this.radiusNormalization,
  });

  final ng.Geofence geofence;

  final ng.Geofence nativeGeofence;
  final int callbackHandle;
  final SGRadiusNormalization radiusNormalization;
}

class _PreparedSmartRegistrations {
  const _PreparedSmartRegistrations({
    required this.registrations,
    required this.dispatcherHandle,
  });

  final List<_PreparedSmartRegistration> registrations;
  final int dispatcherHandle;

  List<ng.GeofenceRegistration> get nativeRegistrations => registrations
      .map(
        (registration) => ng.GeofenceRegistration(
          geofence: registration.nativeGeofence,
          callback: smartGeofenceCallbackDispatcher,
          callbackContext: registration.callbackHandle,
        ),
      )
      .toList(growable: false);
}

class _LegacyAdoptionCandidate {
  const _LegacyAdoptionCandidate({
    required this.input,
    required this.prepared,
    required this.previousNative,
  });

  final SGLegacyGeofenceRegistration input;
  final _PreparedSmartRegistration prepared;
  final ng.ActiveGeofence previousNative;
}

Set<SGSynchronizationReason> _smartSynchronizationReasons(
  Iterable<ng.NativeGeofenceSynchronizationReason> reasons,
) => reasons.map((reason) {
  return switch (reason) {
    ng.NativeGeofenceSynchronizationReason.firstRun =>
      SGSynchronizationReason.firstRun,
    ng.NativeGeofenceSynchronizationReason.callbackFingerprintChanged =>
      SGSynchronizationReason.callbackFingerprintChanged,
    ng.NativeGeofenceSynchronizationReason.registrationDrift =>
      SGSynchronizationReason.registrationDrift,
  };
}).toSet();

const MethodChannel _smartGeofenceChannel = MethodChannel('smart_geofence');

bool get _smartLayerSupportedPlatform =>
    !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

@pragma('vm:entry-point')
Future<void> smartGeofenceCallbackDispatcher(
  ng.GeofenceCallbackParams params,
) async {
  if (_smartLayerSupportedPlatform) {
    await _dispatchWithSmartLayer(params);
    return;
  }
  await _dispatchPassThrough(params);
}

Future<void> _dispatchPassThrough(ng.GeofenceCallbackParams params) async {
  final routing = _routeByCallbackContext(params);
  final resolved = <int, SmartGeofenceCallback>{};
  final errors = <String>[...routing.errors];
  final callbackFailures = <_DispatchFailure>[];

  for (final context in routing.geofencesByCallbackContext.keys) {
    final callback = _resolveSmartGeofenceCallback(context);
    if (callback == null) {
      errors.add(_unresolvedCallbackMessage(context));
      continue;
    }
    resolved[context] = callback;
  }

  for (final entry in resolved.entries) {
    try {
      await entry.value(
        _disabledSmartParams(
          nativeParams: params,
          geofences: routing.geofencesByCallbackContext[entry.key]!,
        ),
      );
    } catch (error, stackTrace) {
      callbackFailures.add(_DispatchFailure(error, stackTrace));
    }
  }

  if (errors.isNotEmpty) {
    callbackFailures.insert(
      0,
      _DispatchFailure(
        StateError(
          'smart_geofence could not route ${errors.length} geofence callback(s) '
          'for event ${params.event.name}: ${errors.join(' ')}',
        ),
        StackTrace.current,
      ),
    );
  }
  _throwFirstDispatchFailure(callbackFailures);
}

Future<void> _dispatchWithSmartLayer(ng.GeofenceCallbackParams params) async {
  if (await _isTerminallyStopped()) return;
  final retryOnFailure = await _retryOnCallbackFailure(params);
  final geofencesByCallbackContext = <int, List<ng.ActiveGeofence>>{};
  final infrastructureFailures = <_DispatchFailure>[];
  final retryableApplicationFailures = <_DispatchFailure>[];

  for (final geofence in params.geofences) {
    final context = _callbackContextFor(params, geofence.id);
    if (context != null) {
      geofencesByCallbackContext
          .putIfAbsent(context, () => <ng.ActiveGeofence>[])
          .add(geofence);
      continue;
    }
    final lookup = await _fenceMirrorById(geofence.id);
    if (lookup.failed) {
      await _reportCallbackDispatch(
        fenceId: geofence.id,
        event: params.event.name,
        eventId: params.eventId,
        traceId: params.traceId,
        result: 'mirror_lookup_error',
        retryOnFailure: retryOnFailure,
        eventAt: params.eventAt,
        error: lookup.error,
      );
      infrastructureFailures.add(
        _DispatchFailure(
          StateError(
            'smart_geofence callback mirror lookup failed for geofence ${geofence.id}: '
            '${lookup.error}',
          ),
          StackTrace.current,
        ),
      );
      continue;
    }
    final mirror = lookup.mirror;
    final callbackHandle = _intFromObject(mirror?['callbackHandle']);
    if (callbackHandle == null || callbackHandle == 0) {
      await _reportCallbackDispatch(
        fenceId: geofence.id,
        event: params.event.name,
        eventId: params.eventId,
        traceId: params.traceId,
        result: 'mirror_missing',
        retryOnFailure: retryOnFailure,
        eventAt: params.eventAt,
      );
      infrastructureFailures.add(
        _DispatchFailure(
          StateError(
            'smart_geofence callback mirror missing for geofence ${geofence.id}.',
          ),
          StackTrace.current,
        ),
      );
      continue;
    }
    geofencesByCallbackContext
        .putIfAbsent(callbackHandle, () => <ng.ActiveGeofence>[])
        .add(geofence);
  }

  for (final entry in geofencesByCallbackContext.entries) {
    final callback = _resolveSmartGeofenceCallback(entry.key);
    if (callback == null) {
      for (final geofence in entry.value) {
        await _reportCallbackDispatch(
          fenceId: geofence.id,
          event: params.event.name,
          eventId: params.eventId,
          traceId: params.traceId,
          result: 'handle_unresolved',
          retryOnFailure: retryOnFailure,
          eventAt: params.eventAt,
        );
      }
      infrastructureFailures.add(
        _DispatchFailure(
          StateError(_unresolvedCallbackMessage(entry.key)),
          StackTrace.current,
        ),
      );
      continue;
    }

    SmartGeofenceCallbackParams smartParams;
    try {
      smartParams = await resolveSmartGeofenceCallbackParams(
        nativeParams: params,
        geofences: entry.value,
      );
    } catch (error, stackTrace) {
      for (final geofence in entry.value) {
        await _reportCallbackDispatch(
          fenceId: geofence.id,
          event: params.event.name,
          eventId: params.eventId,
          traceId: params.traceId,
          result: 'time_resolution_error',
          retryOnFailure: true,
          eventAt: params.eventAt,
          error: error,
        );
      }
      infrastructureFailures.add(_DispatchFailure(error, stackTrace));
      continue;
    }

    final resolution = smartParams.timeResolution;

    try {
      await callback(smartParams);
      for (final geofence in entry.value) {
        await _reportCallbackDispatch(
          fenceId: geofence.id,
          event: params.event.name,
          eventId: params.eventId,
          traceId: params.traceId,
          result: 'dispatched_ok',
          retryOnFailure: retryOnFailure,
          eventAt: smartParams.eventAt,
          timestampSource: resolution.source.name,
          timeReasonCode: resolution.reasonCode,
          timeRejectionReason: resolution.rejectionReason,
          evidenceQuality: resolution.evidence?.quality.name,
          timeTrusted: resolution.isTrusted,
        );
      }
    } catch (error, stackTrace) {
      for (final geofence in entry.value) {
        await _reportCallbackDispatch(
          fenceId: geofence.id,
          event: params.event.name,
          eventId: params.eventId,
          traceId: params.traceId,
          result: 'app_callback_error',
          retryOnFailure: retryOnFailure,
          eventAt: smartParams.eventAt,
          timestampSource: resolution.source.name,
          timeReasonCode: resolution.reasonCode,
          timeRejectionReason: resolution.rejectionReason,
          evidenceQuality: resolution.evidence?.quality.name,
          timeTrusted: resolution.isTrusted,
          error: error,
        );
      }
      if (retryOnFailure) {
        retryableApplicationFailures.add(_DispatchFailure(error, stackTrace));
      }
    }
  }

  _throwFirstDispatchFailure(infrastructureFailures);
  _throwFirstDispatchFailure(retryableApplicationFailures);
}

Future<bool> _isTerminallyStopped() async {
  try {
    return await _smartGeofenceChannel.invokeMethod<bool>(
          'isTerminallyStopped',
        ) ??
        false;
  } catch (_) {
    return false;
  }
}

int? _callbackContextFor(ng.GeofenceCallbackParams params, String fenceId) {
  final context = params.callbackContextsByGeofenceId[fenceId];
  if (context == null || context == 0) return null;
  return context;
}

_CallbackRouting _routeByCallbackContext(ng.GeofenceCallbackParams params) {
  final geofencesByCallbackContext = <int, List<ng.ActiveGeofence>>{};
  final errors = <String>[];
  for (final geofence in params.geofences) {
    final context = _callbackContextFor(params, geofence.id);
    if (context == null) {
      errors.add(
        'Geofence ${geofence.id} has no callback context; it was registered by '
        'an older smart_geofence version or outside SmartGeofenceManager. '
        'Re-register it through SmartGeofenceManager.createGeofence.',
      );
      continue;
    }
    geofencesByCallbackContext
        .putIfAbsent(context, () => <ng.ActiveGeofence>[])
        .add(geofence);
  }
  return _CallbackRouting(geofencesByCallbackContext, errors);
}

SmartGeofenceCallbackParams _disabledSmartParams({
  required ng.GeofenceCallbackParams nativeParams,
  required List<ng.ActiveGeofence> geofences,
}) => SmartGeofenceCallbackParams(
  geofences: geofences,
  event: nativeParams.event,
  location: nativeParams.location,
  eventAt: nativeParams.eventAt,
  eventId: nativeParams.eventId,
  traceId: nativeParams.traceId,
  callbackContextsByGeofenceId: {
    for (final geofence in geofences)
      geofence.id: ?nativeParams.callbackContextsByGeofenceId[geofence.id],
  },
  timeResolution: SGEventTimeResolution(
    source: SGEventTimeSource.deviceWallClock,
    wallClockEventAt: nativeParams.eventAt,
  ),
);

SmartGeofenceCallback? _resolveSmartGeofenceCallback(int callbackContext) {
  final callback = PluginUtilities.getCallbackFromHandle(
    CallbackHandle.fromRawHandle(callbackContext),
  );
  return callback is SmartGeofenceCallback ? callback : null;
}

String _unresolvedCallbackMessage(int _) =>
    'smart_geofence callback could not be resolved. '
    'The handle is stored when the geofence is created and goes stale after an '
    'app update, an obfuscated rebuild, or when the callback is moved, renamed, '
    'or is no longer a SmartGeofenceCallback. Re-register your geofences on '
    'launch from live top-level functions.';

class _CallbackRouting {
  const _CallbackRouting(this.geofencesByCallbackContext, this.errors);

  final Map<int, List<ng.ActiveGeofence>> geofencesByCallbackContext;
  final List<String> errors;
}

Future<bool> _retryOnCallbackFailure(ng.GeofenceCallbackParams params) async {
  try {
    final value = await _smartGeofenceChannel.invokeMethod<bool>(
      'getRetryOnCallbackFailure',
    );
    if (value == null) {
      throw StateError('Callback retry policy returned no value.');
    }
    return value;
  } catch (error) {
    const fallbackRetryOnFailure = true;
    await _reportCallbackDispatch(
      event: params.event.name,
      eventId: params.eventId,
      traceId: params.traceId,
      result: 'retry_config_unavailable',
      retryOnFailure: fallbackRetryOnFailure,
      eventAt: params.eventAt,
      error: error,
    );
    return fallbackRetryOnFailure;
  }
}

Future<_FenceMirrorLookup> _fenceMirrorById(String id) async {
  try {
    final raw = await _smartGeofenceChannel.invokeMethod<Object?>(
      'getFenceMirror',
      {'id': id},
    );
    return _FenceMirrorLookup(raw is Map ? raw.cast<Object?, Object?>() : null);
  } catch (error) {
    debugPrint('smart_geofence: unable to read callback mirror $id: $error');
    return _FenceMirrorLookup(null, error: error.toString(), failed: true);
  }
}

Future<void> _reportCallbackDispatch({
  String? fenceId,
  String? event,
  String? eventId,
  String? traceId,
  required String result,
  bool? retryOnFailure,
  DateTime? eventAt,
  String? timestampSource,
  String? timeReasonCode,
  String? timeRejectionReason,
  String? evidenceQuality,
  bool? timeTrusted,
  Object? error,
}) async {
  try {
    await _smartGeofenceChannel
        .invokeMethod<void>('reportCallbackDispatch', <String, Object?>{
          'fenceId': ?fenceId,
          'event': ?event,
          'eventId': ?eventId,
          'traceId': ?traceId,
          'result': result,
          'retryOnFailure': ?retryOnFailure,
          if (eventAt != null) 'eventAtMillis': eventAt.millisecondsSinceEpoch,
          'timestampSource': ?timestampSource,
          'timeReasonCode': ?timeReasonCode,
          'timeRejectionReason': ?timeRejectionReason,
          'evidenceQuality': ?evidenceQuality,
          'timeTrusted': ?timeTrusted,
          if (error != null) 'error': error.toString(),
        });
  } catch (error) {
    debugPrint('smart_geofence: unable to report callback dispatch: $error');
  }
}

Future<void> _reportGeofenceSync({
  required String result,
  required int desiredCount,
  required int previousCount,
  Object? error,
  List<String> rollbackFailures = const <String>[],
}) async {
  try {
    await _smartGeofenceChannel
        .invokeMethod<void>('reportGeofenceSync', <String, Object?>{
          'result': result,
          'desiredCount': desiredCount,
          'previousCount': previousCount,
          if (error != null) 'error': error.toString(),
          'rollbackFailures': rollbackFailures,
        });
  } catch (reportError) {
    debugPrint(
      'smart_geofence: unable to report geofence synchronization: '
      '$reportError',
    );
  }
}

class _DispatchFailure {
  const _DispatchFailure(this.error, this.stackTrace);

  final Object error;
  final StackTrace stackTrace;
}

Object _rollbackAwareFailure(
  String operation,
  Object original,
  List<String> rollbackFailures,
) {
  if (rollbackFailures.isEmpty) return original;
  return StateError(
    '$operation failed and rollback was incomplete: '
    '${rollbackFailures.join(' ')} Original failure: $original',
  );
}

void _throwFirstDispatchFailure(List<_DispatchFailure> failures) {
  if (failures.isEmpty) return;
  final first = failures.first;
  Error.throwWithStackTrace(first.error, first.stackTrace);
}

int? _intFromObject(Object? value) => switch (value) {
  int() => value,
  double() => value.toInt(),
  _ => null,
};

class _FenceMirrorLookup {
  const _FenceMirrorLookup(this.mirror, {this.error, this.failed = false});

  final Map<Object?, Object?>? mirror;
  final String? error;
  final bool failed;
}

class SmartGeofenceManager {
  SmartGeofenceManager._();

  static final SmartGeofenceManager instance = SmartGeofenceManager._();

  static const MethodChannel _channel = _smartGeofenceChannel;
  late final _registration = _SmartGeofenceRegistrationSynchronizer(
    channel: _channel,
    supportsSmartLayer: () => _smartLayerSupported,
  );
  SmartGeofenceMonitoringStoppedCallback? _monitoringStoppedCallback;
  final Set<String> _deliveredMonitoringStopEventIds = <String>{};
  final Map<String, Future<void>> _monitoringStopDeliveries =
      <String, Future<void>>{};

  bool get _smartLayerSupported => _smartLayerSupportedPlatform;

  Future<void> initialize({
    SGConfig config = const SGConfig(),
    SmartGeofenceMonitoringStoppedCallback? onMonitoringStopped,
  }) async {
    _monitoringStoppedCallback = onMonitoringStopped;
    if (_smartLayerSupported) {
      _channel.setMethodCallHandler(_handleNativeMethodCall);
    }
    await ng.NativeGeofenceManager.instance.initialize();
    await configure(config);
    if (_smartLayerSupported && onMonitoringStopped != null) {
      await _deliverPendingMonitoringStoppedEvent();
    }
  }

  Future<Object?> _handleNativeMethodCall(MethodCall call) async {
    if (call.method != 'monitoringStopped') {
      throw MissingPluginException(
        'Unknown smart_geofence call ${call.method}.',
      );
    }
    final raw = call.arguments;
    if (raw is! Map) {
      throw PlatformException(
        code: 'invalid_monitoring_stopped_event',
        message: 'Android returned a malformed monitoring stopped event.',
      );
    }
    await _deliverMonitoringStoppedEvent(
      SGMonitoringStoppedEvent.fromMap(raw.cast<Object?, Object?>()),
    );
    return null;
  }

  Future<void> _deliverPendingMonitoringStoppedEvent() async {
    String? unacknowledgedEventId;
    while (true) {
      final raw = await _channel.invokeMethod<Object?>(
        'getPendingMonitoringStoppedEvent',
      );
      if (raw is! Map) return;
      final event = SGMonitoringStoppedEvent.fromMap(
        raw.cast<Object?, Object?>(),
      );
      if (event.eventId == unacknowledgedEventId) {
        throw StateError(
          'Android could not acknowledge monitoring stopped event '
          '${event.eventId}.',
        );
      }
      await _deliverMonitoringStoppedEvent(event);
      final acknowledged = await _channel.invokeMethod<bool>(
        'ackMonitoringStoppedEvent',
        {'eventId': event.eventId},
      );
      unacknowledgedEventId = acknowledged == true ? null : event.eventId;
    }
  }

  Future<void> _deliverMonitoringStoppedEvent(
    SGMonitoringStoppedEvent event,
  ) async {
    if (_deliveredMonitoringStopEventIds.contains(event.eventId)) return;
    final existing = _monitoringStopDeliveries[event.eventId];
    if (existing != null) return existing;

    final callback = _monitoringStoppedCallback;
    if (callback == null) {
      throw PlatformException(
        code: 'monitoring_stopped_callback_unavailable',
        message: 'No monitoring stopped callback is registered.',
      );
    }
    final delivery = () async {
      await callback(event);
      _deliveredMonitoringStopEventIds.add(event.eventId);
    }();
    _monitoringStopDeliveries[event.eventId] = delivery;
    try {
      await delivery;
    } finally {
      if (identical(_monitoringStopDeliveries[event.eventId], delivery)) {
        _monitoringStopDeliveries.remove(event.eventId);
      }
    }
  }

  Future<void> configure(SGConfig config) async {
    config.validate();
    await ng.NativeGeofenceManager.instance.configureLogFile(
      config: ng.NativeGeofenceLogFileConfig(
        enabled: config.logging.fileEnabled,
        verbose: config.logging.verbose,
        maxBytes: config.logging.maxFileBytes,
      ),
    );
    if (!_smartLayerSupported) return;
    await _channel.invokeMethod(
      'configure',
      encodeSmartGeofenceConfigTransport(config),
    );
  }

  Future<SyncResult> syncTimeIntegrity({
    required Future<int> Function() trustedUtcMillis,
    TimeIntegrityConfig? config,
  }) async {
    final resolvedConfig = config ?? const TimeIntegrityConfig();
    await logSmartGeofenceTimeIntegrity(
      level: SGTimeIntegrityLogLevel.info,
      stage: 'sync.start',
      message: 'syncTimeIntegrity start',
      extras: const <String, Object?>{'source': 'callback'},
    );
    final integrity = DefaultTimeIntegrity(
      timeSource: CallbackTimeSource.utcMs(trustedUtcMillis),
      config: resolvedConfig,
    );
    try {
      final result = await integrity.sync();
      await logSmartGeofenceTimeIntegrity(
        level: result.success
            ? SGTimeIntegrityLogLevel.info
            : SGTimeIntegrityLogLevel.warning,
        stage: result.success ? 'sync.success' : 'sync.failure',
        message: result.success
            ? 'syncTimeIntegrity success'
            : 'syncTimeIntegrity failed',
        extras: _syncResultLogExtras(result),
      );
      return result;
    } catch (error, stackTrace) {
      await logSmartGeofenceTimeIntegrity(
        level: SGTimeIntegrityLogLevel.warning,
        stage: 'sync.exception',
        message: 'syncTimeIntegrity threw',
        extras: <String, Object?>{'error': error.toString()},
      );
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  Future<ClockHealth> checkTimeIntegrityHealth({
    TimeIntegrityConfig? config,
  }) async {
    final resolvedConfig = config ?? const TimeIntegrityConfig();
    final integrity = DefaultTimeIntegrity(
      timeSource: CallbackTimeSource.utcMs(_unusedHealthTrustedUtcMillis),
      config: resolvedConfig,
    );
    try {
      final health = await integrity.checkHealth();
      await logSmartGeofenceTimeIntegrity(
        level: health.isHealthy
            ? SGTimeIntegrityLogLevel.debug
            : SGTimeIntegrityLogLevel.warning,
        stage: health.isHealthy ? 'health.healthy' : 'health.degraded',
        message: health.isHealthy
            ? 'checkHealth healthy'
            : 'checkHealth degraded',
        extras: _clockHealthLogExtras(health),
      );
      return health;
    } catch (error, stackTrace) {
      await logSmartGeofenceTimeIntegrity(
        level: SGTimeIntegrityLogLevel.warning,
        stage: 'health.exception',
        message: 'checkHealth threw',
        extras: <String, Object?>{'error': error.toString()},
      );
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  Future<SmartGeofenceLogSnapshot> readLogs() async {
    var nativeLogs = '';
    var smartLogs = '';
    final errors = <String, String>{};
    try {
      nativeLogs = await ng.NativeGeofenceManager.instance.readLogFile();
    } catch (error) {
      errors['native_geofence'] = error.toString();
    }
    if (_smartLayerSupported) {
      try {
        smartLogs = await _channel.invokeMethod<String>('readLogs') ?? '';
      } catch (error) {
        errors['smart_geofence'] = error.toString();
      }
    }
    return SmartGeofenceLogSnapshot(
      nativeGeofence: nativeLogs,
      smartGeofence: smartLogs,
      errors: errors,
    );
  }

  Future<void> clearLogs() async {
    Object? firstError;
    StackTrace? firstStackTrace;
    try {
      await ng.NativeGeofenceManager.instance.clearLogFile();
    } catch (error, stackTrace) {
      firstError = error;
      firstStackTrace = stackTrace;
    }
    if (_smartLayerSupported) {
      try {
        await _channel.invokeMethod('clearLogs');
      } catch (error, stackTrace) {
        firstError ??= error;
        firstStackTrace ??= stackTrace;
      }
    }
    if (firstError != null) {
      Error.throwWithStackTrace(firstError, firstStackTrace!);
    }
  }

  Future<T> runPromoted<T>(Future<T> Function() action) async {
    var promoted = false;
    if (_smartLayerSupported) {
      try {
        await _channel.invokeMethod<void>('promoteCallbackToForeground');
        promoted = true;
      } catch (error, stackTrace) {
        await _recordForegroundLifecycleFailure(
          stage: 'promotion',
          error: error,
          stackTrace: stackTrace,
        );
      }
    }
    try {
      return await action();
    } finally {
      if (promoted) {
        try {
          await _channel.invokeMethod<void>('demoteCallbackToBackground');
        } catch (error, stackTrace) {
          await _recordForegroundLifecycleFailure(
            stage: 'demotion',
            error: error,
            stackTrace: stackTrace,
          );
        }
      }
    }
  }

  Future<void> _recordForegroundLifecycleFailure({
    required String stage,
    required Object error,
    required StackTrace stackTrace,
  }) async {
    debugPrint(
      'smart_geofence: callback foreground $stage failed: $error\n$stackTrace',
    );
    try {
      await _channel.invokeMethod<void>(
        'reportCallbackForegroundLifecycleFailure',
        {'stage': stage, 'error': error.toString()},
      );
    } catch (_) {}
  }

  Future<SmartGeofenceStatus> getStatus() async {
    final nativeStatus = await ng.NativeGeofenceManager.instance.getStatus();
    if (!_smartLayerSupported) {
      return SmartGeofenceStatus(
        nativeStatus: nativeStatus,
        smartLayerSupported: false,
      );
    }
    Object? raw;
    try {
      raw = await _channel.invokeMethod<Object?>('getStatus');
    } catch (error) {
      return SmartGeofenceStatus.unavailable(
        nativeStatus: nativeStatus,
        reason: 'Android smart status lookup failed: $error',
      );
    }
    if (raw is! Map) {
      return SmartGeofenceStatus.unavailable(
        nativeStatus: nativeStatus,
        reason: 'Android smart status returned ${raw.runtimeType}, not a map.',
      );
    }
    return SmartGeofenceStatus.fromMap(
      nativeStatus: nativeStatus,
      smartLayerSupported: true,
      map: raw.cast<Object?, Object?>(),
    );
  }

  Future<SGMonitoringReadiness> inspectReadiness() async =>
      SGMonitoringReadiness.fromStatus(await getStatus());

  Future<SmartGeofenceDiagnosticSnapshot> captureDiagnostics({
    bool includeSensitiveData = false,
  }) async {
    final errors = <Map<String, Object?>>[];
    var geofences = <ng.ActiveGeofence>[];
    ng.NativeGeofenceStatus? nativeStatus;
    var rawSmartStatus = const <Object?, Object?>{};
    SmartGeofenceStatus? status;
    try {
      geofences = await getRegisteredGeofences();
    } catch (error, stackTrace) {
      errors.add({
        'component': 'registered_geofences',
        'error': error.toString(),
        'stackTrace': stackTrace.toString(),
      });
    }
    try {
      nativeStatus = await ng.NativeGeofenceManager.instance.getStatus();
    } catch (error, stackTrace) {
      errors.add({
        'component': 'native_status',
        'error': error.toString(),
        'stackTrace': stackTrace.toString(),
      });
    }
    if (_smartLayerSupported) {
      try {
        final raw = await _channel.invokeMethod<Object?>('getStatus');
        rawSmartStatus = raw is Map
            ? raw.cast<Object?, Object?>()
            : const <Object?, Object?>{};
      } catch (error, stackTrace) {
        errors.add({
          'component': 'smart_status',
          'error': error.toString(),
          'stackTrace': stackTrace.toString(),
        });
      }
    }
    if (nativeStatus != null) {
      status = SmartGeofenceStatus.fromMap(
        nativeStatus: nativeStatus,
        smartLayerSupported: _smartLayerSupported,
        map: rawSmartStatus,
      );
    }
    return SmartGeofenceDiagnosticSnapshot.build(
      capturedAt: DateTime.now(),
      registeredGeofences: geofences,
      status: status,
      nativeStatus: nativeStatus,
      rawSmartStatus: rawSmartStatus,
      collectionErrors: errors,
      includeSensitiveData: includeSensitiveData,
    );
  }

  Future<SGExactAlarmPermissionStatus> getExactAlarmPermissionStatus() async {
    if (!_smartLayerSupported) return SGExactAlarmPermissionStatus.notRequired;
    final raw = await _channel.invokeMethod<String>(
      'getExactAlarmPermissionStatus',
    );
    return _exactAlarmPermissionStatus(raw);
  }

  Future<bool> canScheduleExactAlarms() async {
    if (!_smartLayerSupported) return true;
    return await _channel.invokeMethod<bool>('canScheduleExactAlarms') ?? false;
  }

  Future<bool> openExactAlarmPermissionSettings() async {
    if (!_smartLayerSupported) return false;
    return await _channel.invokeMethod<bool>(
          'openExactAlarmPermissionSettings',
        ) ??
        false;
  }

  Future<SGActivityRecognitionPermissionStatus>
  getActivityRecognitionPermissionStatus() async {
    if (!_smartLayerSupported) {
      return SGActivityRecognitionPermissionStatus.notRequired;
    }
    final raw = await _channel.invokeMethod<String>(
      'getActivityRecognitionPermissionStatus',
    );
    return _activityRecognitionPermissionStatus(raw);
  }

  Future<SGActivityRecognitionPermissionStatus>
  requestActivityRecognitionPermission() async {
    if (!_smartLayerSupported) {
      return SGActivityRecognitionPermissionStatus.notRequired;
    }
    final raw = await _channel.invokeMethod<String>(
      'requestActivityRecognitionPermission',
    );
    return _activityRecognitionPermissionStatus(raw);
  }

  Future<bool> openActivityRecognitionPermissionSettings() async {
    if (!_smartLayerSupported) return false;
    return await _channel.invokeMethod<bool>(
          'openActivityRecognitionPermissionSettings',
        ) ??
        false;
  }

  Future<void> createGeofence(
    ng.Geofence geofence,
    SmartGeofenceCallback callback,
  ) => _registration.createGeofence(geofence, callback);

  Future<SGLegacyAdoptionResult> adoptLegacyRegistrations(
    List<SGLegacyGeofenceRegistration> registrations,
  ) => _registration.adoptLegacyRegistrations(registrations);

  Future<SGSynchronizationReport> ensureSynchronized(
    List<SmartGeofenceRegistration> registrations, {
    bool removeUnlisted = true,
  }) => _registration.ensureSynchronized(
    registrations,
    removeUnlisted: removeUnlisted,
  );

  Future<SGMonitoringInspection> getMonitoringState(
    List<SmartGeofenceRegistration> registrations, {
    bool removeUnlisted = true,
  }) => _registration.getMonitoringState(
    registrations,
    removeUnlisted: removeUnlisted,
  );

  Future<bool> isMonitoring(
    List<SmartGeofenceRegistration> registrations, {
    bool removeUnlisted = true,
  }) =>
      _registration.isMonitoring(registrations, removeUnlisted: removeUnlisted);

  Future<void> removeGeofence(ng.Geofence geofence) =>
      _registration.removeGeofence(geofence);

  Future<void> removeGeofenceById(String id) =>
      _registration.removeGeofenceById(id);

  Future<void> removeAllGeofences() => _registration.removeAllGeofences();

  Future<List<String>> getRegisteredGeofenceIds() =>
      _registration.getRegisteredGeofenceIds();

  Future<List<ng.ActiveGeofence>> getRegisteredGeofences() =>
      _registration.getRegisteredGeofences();

  Future<void> reCreateAfterReboot() => _recoverNativeAndSmartLayers();

  Future<void> recoverNow() => _recoverNativeAndSmartLayers();

  Future<void> _recoverNativeAndSmartLayers() =>
      _registration.recoverNativeAndSmartLayers();

  SGExactAlarmPermissionStatus _exactAlarmPermissionStatus(String? value) {
    for (final status in SGExactAlarmPermissionStatus.values) {
      if (status.name == value) return status;
    }
    return SGExactAlarmPermissionStatus.settingsUnavailable;
  }

  SGActivityRecognitionPermissionStatus _activityRecognitionPermissionStatus(
    String? value,
  ) {
    for (final status in SGActivityRecognitionPermissionStatus.values) {
      if (status.name == value) return status;
    }
    return SGActivityRecognitionPermissionStatus.settingsUnavailable;
  }
}

Future<int> _unusedHealthTrustedUtcMillis() async {
  throw StateError('checkTimeIntegrityHealth does not sync trusted time.');
}

Map<String, Object?> _syncResultLogExtras(SyncResult result) =>
    <String, Object?>{
      'success': result.success,
      'reasonCode': result.reasonCode,
      'source': result.source,
      'utcMs': result.utcMs,
      'uncertaintyMs': result.uncertaintyMs,
      if (result.error != null) 'error': result.error.toString(),
      'snapshotBeforeMonotonicMs': result.snapshotBefore?.monotonicMs,
      'snapshotAfterMonotonicMs': result.snapshotAfter?.monotonicMs,
    };

Map<String, Object?> _clockHealthLogExtras(ClockHealth health) =>
    <String, Object?>{
      'healthy': health.isHealthy,
      'reasonCode': health.reasonCode,
      'rebootDetected': health.rebootDetected,
      'nowUtcMs': health.now?.toUtc().millisecondsSinceEpoch,
      'wallClockDifferenceMs': health.wallClockDifference?.inMilliseconds,
      'anchorAgeMs': health.anchorAge?.inMilliseconds,
      'uncertaintyMs': health.uncertainty?.inMilliseconds,
      'failedGates': health.failedGates.map((gate) => gate.name).toList(),
      'evaluatedAtWallClockMs': health.evaluatedAtWallClockMs,
    };
