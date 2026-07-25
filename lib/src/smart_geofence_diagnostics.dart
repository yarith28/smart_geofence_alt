import 'package:native_geofence/native_geofence.dart';

import 'smart_geofence_status.dart';

class SmartGeofenceLogSnapshot {
  const SmartGeofenceLogSnapshot({
    required this.nativeGeofence,
    required this.smartGeofence,
    this.errors = const <String, String>{},
  });

  final String nativeGeofence;
  final String smartGeofence;
  final Map<String, String> errors;
}

class SmartGeofenceDiagnosticSnapshot {
  const SmartGeofenceDiagnosticSnapshot({
    required this.schema,
    required this.capturedAt,
    required this.includeSensitiveData,
    required this.registeredGeofences,
    required this.status,
    required this.nativeStatus,
    required this.rawSmartStatus,
    required this.sections,
    required this.collectionErrors,
  });

  final int schema;
  final DateTime capturedAt;

  final bool includeSensitiveData;

  final List<ActiveGeofence> registeredGeofences;

  final SmartGeofenceStatus? status;

  final NativeGeofenceStatus? nativeStatus;
  final Map<String, Object?> rawSmartStatus;
  final Map<String, Object?> sections;
  final List<Map<String, Object?>> collectionErrors;

  static SmartGeofenceDiagnosticSnapshot build({
    required DateTime capturedAt,
    required List<ActiveGeofence> registeredGeofences,
    required SmartGeofenceStatus? status,
    required NativeGeofenceStatus? nativeStatus,
    required Map<Object?, Object?> rawSmartStatus,
    required List<Map<String, Object?>> collectionErrors,
    bool includeSensitiveData = false,
  }) {
    final complete = _sanitizeMap(
      _map(rawSmartStatus),
      includeSensitiveData: includeSensitiveData,
    );
    final remaining = Map<String, Object?>.of(complete);
    final config = _map(remaining.remove('config'));
    final journal = _list(remaining.remove('diagnosticEventJournal'));
    final counters = _map(remaining.remove('diagnosticCounters'));
    final fenceStates = _map(remaining.remove('fenceStates'));
    final mirroredFenceIds = remaining.remove('mirroredFenceIds');

    final overviewFields = <String, Object?>{};
    for (final key in const [
      'smartLayerSupported',
      'smartLayerMode',
      'smartLayerModeReason',
      'androidSdkInt',
      'deviceManufacturer',
      'deviceModel',
    ]) {
      if (remaining.containsKey(key)) {
        overviewFields[key] = remaining.remove(key);
      }
    }

    final permissionsPower = _take(remaining, _permissionsPowerKeys);
    final alarmsRecovery = _take(remaining, _alarmsRecoveryKeys);
    final queuesServices = _take(remaining, _queuesServicesKeys);
    final locationMonitoring = _take(remaining, _locationMonitoringKeys);
    final eventDelivery = _take(remaining, _eventDeliveryKeys);
    final timeIntegrity = _take(remaining, _timeIntegrityKeys);

    final safeStatusMap = status == null
        ? null
        : <String, Object?>{
            ...complete,
            if (!complete.containsKey('mirroredFenceIds'))
              'mirroredFenceIds': const <String>[],
            if (!complete.containsKey('mirroredFenceCount'))
              'mirroredFenceCount': status.mirroredFenceIds.toSet().length,
          };
    final safeStatus = status == null
        ? null
        : SmartGeofenceStatus.fromMap(
            nativeStatus: status.nativeStatus,
            smartLayerSupported: status.smartLayerSupported,
            map: safeStatusMap!,
          );
    final warnings = safeStatus?.warnings ?? const <String>[];
    final safeNativeStatus = _sanitizeMap(
      _map(nativeStatus?.toJson()),
      includeSensitiveData: includeSensitiveData,
    );
    final safeErrors = collectionErrors
        .map(
          (error) => includeSensitiveData
              ? _sanitizeMap(error, includeSensitiveData: true)
              : <String, Object?>{
                  'component': _json(error['component']),
                  'error':
                      'Collection failed; details require sensitive export.',
                },
        )
        .toList(growable: false);
    final exportedGeofences = includeSensitiveData
        ? List<ActiveGeofence>.unmodifiable(registeredGeofences)
        : const <ActiveGeofence>[];
    return SmartGeofenceDiagnosticSnapshot(
      schema: 2,
      capturedAt: capturedAt,
      includeSensitiveData: includeSensitiveData,
      registeredGeofences: exportedGeofences,
      status: includeSensitiveData ? safeStatus : null,
      nativeStatus: includeSensitiveData ? nativeStatus : null,
      rawSmartStatus: complete,
      collectionErrors: safeErrors,
      sections: {
        'overview': {
          'schema': 2,
          'includeSensitiveData': includeSensitiveData,
          ...overviewFields,
          'registeredGeofenceCount': registeredGeofences.length,
          'warnings': warnings,
          'warningCount': warnings.length,
          'completeStatusFieldCount': complete.length,
          'uncategorizedFieldCount': remaining.length,
          'collectionErrorCount': safeErrors.length,
        },
        'config': config,
        'native_status': safeNativeStatus,
        'smart_status': complete,
        'permissions_power': permissionsPower,
        'fences': {
          'registeredGeofences': exportedGeofences
              .map((fence) => fence.toJson())
              .toList(growable: false),
          'mirroredFenceIds': _json(mirroredFenceIds),
          'fenceStates': fenceStates,
        },
        'alarms_recovery': alarmsRecovery,
        'queues_services': queuesServices,
        'location_monitoring': locationMonitoring,
        'event_delivery': eventDelivery,
        'time_integrity': timeIntegrity,
        'event_journal': {'count': journal.length, 'entries': journal},
        'counters': counters,
        'other': remaining,
      },
    );
  }

  Map<String, Object?> toJson() => {
    'schema': schema,
    'capturedAt': capturedAt.toIso8601String(),
    'includeSensitiveData': includeSensitiveData,
    'sections': sections,
    'collectionErrors': collectionErrors,
  };
}

Map<String, Object?> _take(Map<String, Object?> source, Set<String> keys) =>
    <String, Object?>{
      for (final key in keys)
        if (source.containsKey(key)) key: source.remove(key),
    };

const _permissionsPowerKeys = <String>{
  'locationPermissionGranted',
  'fineLocationGranted',
  'backgroundLocationPermissionGranted',
  'activityPermissionGranted',
  'foregroundServicePermissionGranted',
  'foregroundServiceLocationPermissionGranted',
  'notificationPermissionGranted',
  'locationServicesEnabled',
  'powerSaveMode',
  'deviceIdleMode',
  'batteryOptimizationsIgnored',
  'batteryLevelPercent',
  'batteryCharging',
};

const _alarmsRecoveryKeys = <String>{
  'exactAlarmPermissionMode',
  'exactAlarmPermissionDeclared',
  'exactAlarmPermissionRequired',
  'exactAlarmPermissionStatus',
  'exactAlarmPermissionGranted',
  'exactAlarmSettingsIntentAvailable',
  'exactAlarmAppSettingsFallbackAvailable',
  'exactAlarmSettingsCanOpen',
  'exactAlarmStrictStartupBlocked',
  'nativeConfirmDelayRequiresExactAlarm',
  'nativeConfirmDelayExactSchedulingAvailable',
  'nativeEnterConfirmImmediateTimingBypassPossible',
  'nativeExitConfirmImmediateTimingBypassPossible',
  'transitionValidationEnterBlocksEarlyConfirmationAcquisition',
  'transitionValidationExitBlocksEarlyConfirmationAcquisition',
  'transitionValidationEnterBlocksRawNativeFallback',
  'transitionValidationExitBlocksRawNativeFallback',
  'bootReceiverDeclared',
  'bootFollowUpReceiverDeclared',
  'bootFollowUpPendingIntentExists',
  'recoveryAlarmReceiverDeclared',
  'recoveryAlarmPendingIntentExists',
  'nativeExitFallbackReceiverDeclared',
  'nativeExitFallbackPendingCount',
  'nativeExitFallbackPendingFenceIds',
  'nativeExitFallbackAlarmPendingIntentExists',
  'nativeExitFallbackAlarmSchedule',
  'nativeExitFallbackPendingDetails',
  'nativeEnterFallbackReceiverDeclared',
  'nativeEnterFallbackPendingCount',
  'nativeEnterFallbackPendingFenceIds',
  'nativeEnterFallbackAlarmPendingIntentExists',
  'nativeEnterFallbackAlarmSchedule',
  'nativeEnterFallbackPendingDetails',
};

const _queuesServicesKeys = <String>{
  'foregroundStartCoordinatorWindowClosed',
  'foregroundStartQueuedServices',
  'foregroundStartBatchPendingIntentExists',
  'locationConfirmServiceDeclared',
  'locationConfirmServiceHasLocationType',
  'locationConfirmServiceRunning',
  'locationConfirmServiceForegroundReady',
  'locationConfirmCanRun',
  'locationConfirmQueueSize',
  'locationConfirmQueueItems',
  'foregroundQueueSize',
  'foregroundQueueItems',
  'backgroundQueueSize',
  'locationConfirmStartPendingIntentExists',
  'locationConfirmWatchdogPendingIntentExists',
  'foregroundServiceRearmPendingIntentExists',
};

const _locationMonitoringKeys = <String>{
  'monitoringTerminallyStopped',
  'monitoringStopPhase',
  'monitoringStopReason',
  'monitoringStoppedAtMillis',
  'monitoringStopCallbackPending',
  'monitoringNativeCleanupComplete',
  'monitoringNativeCleanupPendingCount',
  'fusedLocationUpdateReceiverDeclared',
  'activityReceiverDeclared',
  'proximityAlarmReceiverDeclared',
  'proximityAlarmPendingIntentExists',
  'proximityPulseStartedAtMillis',
  'proximityPulseIdleTicks',
  'proximityPulseRateMode',
  'proximityPulsePurpose',
  'proximityPulseSchedulingActive',
  'proximityPulseActiveHoursNow',
  'proximityPulseCanRun',
  'proximityAlarmKind',
  'proximityAlarmSchedule',
  'fusedLocationLastHealthyAtMillis',
  'fusedLocationHealthyAgeMillis',
  'fusedLocationLastHealthySource',
  'fusedLocationRecoveryStartedAtMillis',
  'fusedLocationLastRecoveryEndedAtMillis',
  'fusedLocationLastRecoveryReason',
  'fusedLocationBalancedRefreshCount',
  'proximityEligible',
  'passiveLocationEligible',
  'fusedBalancedDesired',
  'fusedBalancedDesiredEpoch',
  'fusedBalancedRequestInFlight',
  'fusedBalancedConfirmed',
  'fusedBalancedRemovalInFlight',
  'fusedBalancedRemovalConfirmed',
  'fusedBalancedDesiredPriority',
  'fusedBalancedConfirmedPriority',
  'fusedBalancedDesiredIntervalMillis',
  'fusedBalancedConfirmedIntervalMillis',
  'fusedBalancedDesiredDisplacementMeters',
  'fusedBalancedConfirmedDisplacementMeters',
  'fusedBalancedDesiredAdaptiveMode',
  'fusedBalancedConfirmedAdaptiveMode',
  'fusedBalancedLastSuccessAtMillis',
  'fusedBalancedLastFailureAtMillis',
  'fusedBalancedLastFailureReason',
  'fusedPassiveDesired',
  'fusedPassiveDesiredEpoch',
  'fusedPassiveRequestInFlight',
  'fusedPassiveConfirmed',
  'fusedPassiveRemovalInFlight',
  'fusedPassiveRemovalConfirmed',
  'fusedPassiveDesiredPriority',
  'fusedPassiveConfirmedPriority',
  'fusedPassiveDesiredIntervalMillis',
  'fusedPassiveConfirmedIntervalMillis',
  'fusedPassiveLastSuccessAtMillis',
  'fusedPassiveLastFailureAtMillis',
  'fusedPassiveLastFailureReason',
  'fusedRequestRemovalInFlightSinceMillis',
  'fusedRequestStaleCallbackCount',
  'fusedRequestLastStaleCallbackReason',
  'fusedRequestIgnoredCallbackCount',
  'fusedRequestLastIgnoredCallbackReason',
  'activityEligible',
  'recoveryEligible',
  'proximityPendingIntentExists',
  'passiveLocationPendingIntentExists',
  'activityPendingIntentExists',
  'activityTransitionPendingIntentExists',
  'activityPeriodicPendingIntentExists',
  'activityControllerDesired',
  'activityTransitionDesired',
  'activityPeriodicDesired',
  'activityPeriodicMode',
  'activityPeriodicBackstopEnabled',
  'activityOperationEpoch',
  'activityMonitoringSessionGeneration',
  'activityDesiredPeriodicIntervalMillis',
  'activityConfirmedPeriodicIntervalMillis',
  'activityConfirmedPeriodicOwner',
  'activityTransitionRequested',
  'activityTransitionRequestInFlight',
  'activityTransitionConfirmed',
  'activityTransitionRemovalInFlight',
  'activityTransitionRemovalConfirmed',
  'activityTransitionLastSuccessAtMillis',
  'activityTransitionLastFailureAtMillis',
  'activityTransitionLastFailureReason',
  'activityPeriodicRequested',
  'activityPeriodicRequestInFlight',
  'activityPeriodicConfirmed',
  'activityPeriodicRemovalInFlight',
  'activityPeriodicRemovalConfirmed',
  'activityPeriodicRemovalRequired',
  'activityBootstrapRequestedAtMillis',
  'activityBootstrapDeadlineMillis',
  'activityBootstrapResultReceived',
  'activityBootstrapCompleted',
  'activityBootstrapTimeoutPendingIntentExists',
  'activityPeriodicLastSuccessAtMillis',
  'activityPeriodicLastFailureAtMillis',
  'activityPeriodicLastFailureReason',
  'activityRemovalInFlightSinceMillis',
  'activityRemovalOverdue',
  'activityStaleOperationCallbackCount',
  'activityLastStaleOperationCallbackReason',
  'activityIgnoredCallbackCount',
  'activityLastIgnoredCallbackReason',
  'activityPeriodicReason',
  'activityPeriodicRequestedAtMillis',
  'activityPeriodicIntervalMillis',
  'lastActivityPeriodicResultAtMillis',
  'activityStationarySource',
  'dormantFarActive',
  'dormantFarProbeReceiverDeclared',
  'dormantFarReason',
  'dormantFarBatteryMode',
  'dormantFarLastEdgeDistanceMeters',
  'dormantFarNearestFenceId',
  'dormantFarLastAcceptedFixSource',
  'dormantFarLastAcceptedFixAtMillis',
  'dormantFarNextProbeAtMillis',
  'dormantFarLastProbeAtMillis',
  'dormantFarLastProbeResult',
  'dormantFarProbePendingIntentExists',
  'dormantFarProbeAlarmSchedule',
  'adaptiveProximityDisplacementMode',
  'adaptiveProximityDisplacementMeters',
  'adaptiveProximityLastEdgeDistanceMeters',
  'lastLocationWakeAtMillis',
  'lastLocationWakeSource',
  'lastLocationWakeProvider',
  'lastLocationWakeAccuracyMeters',
  'lastLocationWakeAgeMillis',
  'lastLocationWakeNearestFenceId',
  'lastLocationWakeEdgeDistanceMeters',
  'lastLocationWakeWithinProximity',
};

const _eventDeliveryKeys = <String>{
  'lastSmartCallbackAtMillis',
  'lastSmartCallbackFenceId',
  'lastSmartCallbackEvent',
  'lastSmartCallbackResult',
  'lastCallbackDispatchAtMillis',
  'lastCallbackDispatchFenceId',
  'lastCallbackDispatchEvent',
  'lastCallbackDispatchResult',
  'lastCallbackDispatchError',
  'lastBoundaryDecisionAtMillis',
  'lastBoundaryDecision',
  'lastBoundaryDecisionFenceId',
  'lastBoundaryDecisionSource',
  'lastBoundaryDistanceMeters',
  'lastBoundaryRadiusMeters',
  'lastBoundaryEdgeDistanceMeters',
  'lastBoundaryAccuracyMeters',
  'lastBoundaryMock',
  'lastBoundaryQueuedEvent',
  'lastMockLocationDecisionAtMillis',
  'lastMockLocationPolicy',
  'lastMockLocationAction',
  'lastMockLocationSuppressed',
};

const _timeIntegrityKeys = <String>{
  'lastCallbackDispatchEventAtMillis',
  'lastCallbackDispatchTimestampSource',
  'lastCallbackDispatchTimeReasonCode',
  'lastCallbackDispatchTimeTrusted',
  'lastCallbackDispatchTimeRejectionReason',
  'lastCallbackDispatchEvidenceQuality',
  'lastNativeExitConfirmTimingMode',
  'lastNativeExitConfirmTimingReason',
  'lastNativeExitConfirmTimingAtMillis',
  'lastNativeEnterConfirmTimingMode',
  'lastNativeEnterConfirmTimingReason',
  'lastNativeEnterConfirmTimingAtMillis',
};

Map<String, Object?> _map(Object? value) {
  final normalized = _json(value);
  return normalized is Map<String, Object?> ? normalized : {};
}

List<Object?> _list(Object? value) {
  final normalized = _json(value);
  return normalized is List<Object?> ? normalized : [];
}

Object? _json(Object? value) {
  if (value == null || value is String || value is num || value is bool) {
    return value;
  }
  if (value is DateTime) return value.toIso8601String();
  if (value is Enum) return value.name;
  if (value is Map) {
    return <String, Object?>{
      for (final entry in value.entries)
        entry.key.toString(): _json(entry.value),
    };
  }
  if (value is Iterable) return value.map(_json).toList(growable: false);
  return value.toString();
}

Map<String, Object?> _sanitizeMap(
  Map<String, Object?> source, {
  required bool includeSensitiveData,
}) => <String, Object?>{
  for (final entry in source.entries)
    if (!_alwaysPrivateKey(entry.key) &&
        (includeSensitiveData || !_defaultSensitiveKey(entry.key)))
      entry.key: _sanitizeValue(
        entry.value,
        includeSensitiveData: includeSensitiveData,
      ),
};

Object? _sanitizeValue(Object? value, {required bool includeSensitiveData}) {
  if (value is String && _containsPrivateRoutingLabel(value)) {
    return '[redacted callback routing metadata]';
  }
  if (value is Map) {
    return _sanitizeMap(
      _map(value),
      includeSensitiveData: includeSensitiveData,
    );
  }
  if (value is Iterable) {
    return value
        .map(
          (item) =>
              _sanitizeValue(item, includeSensitiveData: includeSensitiveData),
        )
        .toList(growable: false);
  }
  return _json(value);
}

bool _alwaysPrivateKey(String key) {
  final normalized = _normalizedKey(key);
  return _containsPrivateRoutingLabel(normalized) ||
      normalized == 'cb' ||
      normalized == 'dispatchcb';
}

bool _containsPrivateRoutingLabel(String value) {
  final normalized = _normalizedKey(value);
  return normalized.contains('callbackhandle') ||
      normalized.contains('callbackcontext');
}

bool _defaultSensitiveKey(String key) {
  final normalized = _normalizedKey(key);
  return normalized == 'id' ||
      normalized.endsWith('id') ||
      normalized.endsWith('ids') ||
      normalized == 'lat' ||
      normalized == 'lng' ||
      normalized == 'latitude' ||
      normalized == 'longitude' ||
      normalized == 'location' ||
      normalized.endsWith('provider') ||
      normalized.endsWith('accuracymeters') ||
      normalized.endsWith('agemillis') ||
      normalized.endsWith('mock') ||
      normalized == 'ismocklocation' ||
      normalized.contains('radius') ||
      normalized.contains('distance') ||
      normalized.contains('locationfix') ||
      normalized.contains('locationprovider') ||
      normalized.contains('locationaccuracy') ||
      normalized.contains('locationage') ||
      normalized.contains('callbacklocation') ||
      normalized.contains('fencestates') ||
      normalized.contains('timeevidence') ||
      normalized.contains('stacktrace');
}

String _normalizedKey(String key) =>
    key.toLowerCase().replaceAll(RegExp('[^a-z0-9]'), '');
