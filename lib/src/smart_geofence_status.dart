import 'package:native_geofence/native_geofence.dart' as ng;

import 'smart_geofence_config.dart';

const int smartGeofenceStatusSchemaVersion = 1;

enum SGSmartStatusState {
  unsupported,
  unavailable,
  unsupportedSchema,
  malformed,
  complete,
}

enum SGRadiusNormalizationReason { none, androidMinimum }

class SGRadiusNormalization {
  const SGRadiusNormalization({
    required this.platform,
    required this.requestedRadiusMeters,
    required this.effectiveRadiusMeters,
    required this.reason,
  });

  final String platform;
  final double requestedRadiusMeters;
  final double effectiveRadiusMeters;
  final SGRadiusNormalizationReason reason;

  bool get wasAdjusted => requestedRadiusMeters != effectiveRadiusMeters;

  Map<String, Object?> toJson() => {
    'platform': platform,
    'requestedRadiusMeters': requestedRadiusMeters,
    'effectiveRadiusMeters': effectiveRadiusMeters,
    'wasAdjusted': wasAdjusted,
    'reason': reason.name,
  };

  @override
  String toString() => 'SGRadiusNormalization(${toJson()})';

  static SGRadiusNormalization? tryFromMap(Map<Object?, Object?> map) {
    final platform = map['platform'];
    final requested = map['requestedRadiusMeters'];
    final effective = map['effectiveRadiusMeters'];
    final rawReason = map['reason'];
    if (platform is! String || requested is! num || effective is! num) {
      return null;
    }
    final requestedRadiusMeters = requested.toDouble();
    final effectiveRadiusMeters = effective.toDouble();
    if (!requestedRadiusMeters.isFinite || !effectiveRadiusMeters.isFinite) {
      return null;
    }
    SGRadiusNormalizationReason? reason;
    for (final candidate in SGRadiusNormalizationReason.values) {
      if (candidate.name == rawReason) {
        reason = candidate;
        break;
      }
    }
    if (reason == null) return null;
    return SGRadiusNormalization(
      platform: platform,
      requestedRadiusMeters: requestedRadiusMeters,
      effectiveRadiusMeters: effectiveRadiusMeters,
      reason: reason,
    );
  }
}

class _SmartStatusValidation {
  const _SmartStatusValidation({
    required this.state,
    required this.schemaVersion,
    required this.issues,
  });

  final SGSmartStatusState state;
  final int? schemaVersion;
  final List<String> issues;
}

_SmartStatusValidation _validateSmartStatus({
  required bool smartLayerSupported,
  required Map<Object?, Object?> map,
}) {
  if (!smartLayerSupported) {
    return const _SmartStatusValidation(
      state: SGSmartStatusState.unsupported,
      schemaVersion: null,
      issues: <String>[],
    );
  }

  final rawSchemaVersion = map['smartStatusSchemaVersion'];
  final schemaVersion = rawSchemaVersion is int ? rawSchemaVersion : null;
  if (schemaVersion == null) {
    return const _SmartStatusValidation(
      state: SGSmartStatusState.malformed,
      schemaVersion: null,
      issues: <String>['Missing or invalid smartStatusSchemaVersion.'],
    );
  }
  if (schemaVersion != smartGeofenceStatusSchemaVersion) {
    return _SmartStatusValidation(
      state: SGSmartStatusState.unsupportedSchema,
      schemaVersion: schemaVersion,
      issues: <String>[
        'Unsupported Android smart status schema $schemaVersion.',
      ],
    );
  }

  final issues = <String>[];
  if (map['config'] is! Map) issues.add('config must be a map.');
  if (map['smartLayerMode'] is! String) {
    issues.add('smartLayerMode must be a string.');
  }
  final mirroredFenceIds = map['mirroredFenceIds'];
  if (mirroredFenceIds is! List ||
      mirroredFenceIds.any((id) => id is! String)) {
    issues.add('mirroredFenceIds must be a list of strings.');
  }
  for (final field in _mandatorySmartStatusBooleanFields) {
    if (map[field] is! bool) issues.add('$field must be a boolean.');
  }
  if (map['exactAlarmPermissionStatus'] is! String) {
    issues.add('exactAlarmPermissionStatus must be a string.');
  }
  return _SmartStatusValidation(
    state: issues.isEmpty
        ? SGSmartStatusState.complete
        : SGSmartStatusState.malformed,
    schemaVersion: schemaVersion,
    issues: List<String>.unmodifiable(issues),
  );
}

const List<String> _mandatorySmartStatusBooleanFields = <String>[
  'locationPermissionGranted',
  'fineLocationGranted',
  'backgroundLocationPermissionGranted',
  'locationServicesEnabled',
  'notificationPermissionGranted',
  'activityPermissionGranted',
  'exactAlarmPermissionGranted',
  'fusedLocationUpdateReceiverDeclared',
  'bootReceiverDeclared',
  'exactAlarmPermissionStateReceiverDeclared',
  'bootFollowUpReceiverDeclared',
  'recoveryAlarmReceiverDeclared',
  'locationConfirmReceiverDeclared',
  'nativeExitFallbackReceiverDeclared',
  'nativeEnterFallbackReceiverDeclared',
  'foregroundServiceLaunchReceiverDeclared',
  'locationConfirmServiceDeclared',
  'locationConfirmServiceHasLocationType',
  'proximityAlarmReceiverDeclared',
  'foregroundServicePermissionGranted',
  'foregroundServiceLocationPermissionGranted',
  'locationConfirmCanRun',
  'locationProviderChangedReceiverDeclared',
  'locationDisabledRecoveryPendingIntentExists',
];

class SmartGeofenceStatus {
  final ng.NativeGeofenceStatus nativeStatus;
  final bool smartLayerSupported;
  final SGSmartStatusState smartStatusState;
  final int? smartStatusSchemaVersion;
  final List<String> smartStatusIssues;
  final Map<Object?, Object?> rawStatus;
  final String? smartLayerMode;
  final String? smartLayerModeReason;
  final SGConfig config;
  final List<Map<Object?, Object?>> diagnosticEventJournal;
  final int? diagnosticEventJournalCapacity;
  final int? diagnosticEventJournalCount;
  final int? diagnosticEventJournalSequence;
  final int? diagnosticEventJournalDroppedCount;
  final int? diagnosticEventJournalOldestAtMillis;
  final int? diagnosticEventJournalNewestAtMillis;
  final int? configAppliedAtMillis;
  final String? configFingerprint;
  final String? packageVersion;
  final String? buildRevision;
  final Map<String, int> diagnosticCounters;
  final Map<Object?, Object?> fenceStates;
  final List<String> mirroredFenceIds;
  final int? mirroredFenceCount;

  final Map<String, SGRadiusNormalization> radiusNormalizations;
  final int? androidSdkInt;
  final String? deviceManufacturer;
  final String? deviceModel;
  final bool? locationPermissionGranted;
  final bool? fineLocationGranted;
  final bool? backgroundLocationPermissionGranted;
  final bool? activityPermissionGranted;
  final bool? foregroundServicePermissionGranted;
  final bool? foregroundServiceLocationPermissionGranted;
  final bool? notificationPermissionGranted;
  final bool? foregroundNotificationSticky;
  final SGForegroundNotificationTapAction? foregroundNotificationTapAction;
  final bool? foregroundNotificationShowWhileMonitoring;
  final bool? powerSaveMode;
  final bool? deviceIdleMode;
  final bool? batteryOptimizationsIgnored;
  final int? batteryLevelPercent;
  final bool? batteryCharging;
  final SGExactAlarmPermissionMode? exactAlarmPermissionMode;
  final bool? exactAlarmPermissionDeclared;
  final bool? exactAlarmPermissionRequired;
  final SGExactAlarmPermissionStatus? exactAlarmPermissionStatus;
  final bool? exactAlarmPermissionGranted;
  final bool? exactAlarmSettingsIntentAvailable;
  final bool? exactAlarmAppSettingsFallbackAvailable;
  final bool? exactAlarmSettingsCanOpen;
  final bool? exactAlarmStrictStartupBlocked;

  final bool? nativeConfirmDelayRequiresExactAlarm;

  final bool? nativeConfirmDelayExactSchedulingAvailable;

  final bool? nativeEnterConfirmImmediateTimingBypassPossible;

  final bool? nativeExitConfirmImmediateTimingBypassPossible;

  final bool? transitionValidationEnterBlocksEarlyConfirmationAcquisition;
  final bool? transitionValidationExitBlocksEarlyConfirmationAcquisition;
  final bool? transitionValidationEnterBlocksRawNativeFallback;
  final bool? transitionValidationExitBlocksRawNativeFallback;
  final int? nativeConfirmDelayMillis;
  final int? nativeConfirmMaxAttempts;
  final bool? transitionValidationEnabled;
  final bool? transitionValidationEnterEnabled;
  final bool? transitionValidationExitEnabled;
  final int? transitionValidationMinimumDelayMillis;
  final bool? nativeExitFallbackReceiverDeclared;
  final int? nativeExitFallbackPendingCount;
  final List<String> nativeExitFallbackPendingFenceIds;
  final bool? nativeExitFallbackAlarmPendingIntentExists;
  final Map<Object?, Object?> nativeExitFallbackAlarmSchedule;
  final List<Map<Object?, Object?>> nativeExitFallbackPendingDetails;
  final bool? nativeEnterFallbackReceiverDeclared;
  final int? nativeEnterFallbackPendingCount;
  final List<String> nativeEnterFallbackPendingFenceIds;
  final bool? nativeEnterFallbackAlarmPendingIntentExists;
  final Map<Object?, Object?> nativeEnterFallbackAlarmSchedule;
  final List<Map<Object?, Object?>> nativeEnterFallbackPendingDetails;
  final int? lastExactAlarmSettingsOpenedAtMillis;
  final String? lastExactAlarmSettingsOpenResult;
  final String? lastExactAlarmSettingsOpenRoute;
  final String? lastExactAlarmSettingsOpenFailureReason;
  final bool? locationServicesEnabled;
  final bool? monitoringTerminallyStopped;
  final String? monitoringStopPhase;
  final String? monitoringStopReason;
  final int? monitoringStoppedAtMillis;
  final String? monitoringStopEventId;
  final bool? monitoringStopCallbackPending;
  final bool? monitoringNativeCleanupComplete;
  final int? monitoringNativeCleanupPendingCount;
  final bool? fusedLocationUpdateReceiverDeclared;
  final bool? activityReceiverDeclared;
  final bool? bootReceiverDeclared;
  final bool? exactAlarmPermissionStateReceiverDeclared;
  final bool? bootFollowUpReceiverDeclared;
  final bool? recoveryAlarmReceiverDeclared;
  final bool? locationConfirmReceiverDeclared;
  final bool? foregroundServiceLaunchReceiverDeclared;
  final bool? foregroundStartCoordinatorWindowClosed;
  final List<String> foregroundStartQueuedServices;
  final bool? foregroundStartBatchPendingIntentExists;
  final bool? locationConfirmServiceDeclared;
  final bool? locationConfirmServiceHasLocationType;
  final bool? locationConfirmServiceRunning;
  final bool? locationConfirmServiceForegroundReady;
  final int? locationConfirmLaunchToken;
  final int? locationConfirmLaunchRequestedAtMillis;
  final int? locationConfirmForegroundReadyAtMillis;
  final int? locationConfirmLastLaunchFailureAtMillis;
  final String? locationConfirmLastLaunchFailureReason;
  final bool? locationConfirmCanRun;
  final int? locationConfirmQueueSize;
  final int? locationConfirmQueueTotalSize;
  final int? locationConfirmQueueParkedSize;
  final List<Map<Object?, Object?>> locationConfirmQueueItems;
  final bool? locationDisabledRecoveryPendingIntentExists;
  final Map<Object?, Object?> locationDisabledRecoveryAlarmSchedule;
  final bool? proximityAlarmReceiverDeclared;
  final bool? dormantFarProbeReceiverDeclared;
  final bool? dormantFarActive;
  final String? dormantFarReason;
  final String? dormantFarBatteryMode;
  final double? dormantFarLastEdgeDistanceMeters;
  final String? dormantFarNearestFenceId;
  final String? dormantFarLastAcceptedFixSource;
  final int? dormantFarLastAcceptedFixAtMillis;
  final int? dormantFarNextProbeAtMillis;
  final int? dormantFarLastProbeAtMillis;
  final String? dormantFarLastProbeResult;
  final String? adaptiveProximityDisplacementMode;
  final double? adaptiveProximityDisplacementMeters;
  final double? adaptiveProximityLastEdgeDistanceMeters;
  final int? proximityPulseStartedAtMillis;
  final int? proximityPulseIdleTicks;
  final String? proximityPulseRateMode;
  final String? proximityPulsePurpose;
  final bool? proximityPulseSchedulingActive;
  final bool? proximityPulseActiveHoursNow;
  final bool? proximityPulseCanRun;
  final int? fusedLocationLastHealthyAtMillis;
  final int? fusedLocationHealthyAgeMillis;
  final String? fusedLocationLastHealthySource;
  final int? fusedLocationRecoveryStartedAtMillis;
  final int? fusedLocationLastRecoveryEndedAtMillis;
  final String? fusedLocationLastRecoveryReason;
  final int? fusedLocationBalancedRefreshCount;
  final bool? proximityEligible;
  final bool? passiveLocationEligible;
  final bool? fusedBalancedDesired;
  final int? fusedBalancedDesiredEpoch;
  final bool? fusedBalancedRequestInFlight;
  final bool? fusedBalancedConfirmed;
  final bool? fusedBalancedRemovalInFlight;
  final bool? fusedBalancedRemovalConfirmed;
  final String? fusedBalancedDesiredPriority;
  final String? fusedBalancedConfirmedPriority;
  final int? fusedBalancedDesiredIntervalMillis;
  final int? fusedBalancedConfirmedIntervalMillis;
  final double? fusedBalancedDesiredDisplacementMeters;
  final double? fusedBalancedConfirmedDisplacementMeters;
  final String? fusedBalancedDesiredAdaptiveMode;
  final String? fusedBalancedConfirmedAdaptiveMode;
  final int? fusedBalancedLastSuccessAtMillis;
  final int? fusedBalancedLastFailureAtMillis;
  final String? fusedBalancedLastFailureReason;
  final bool? fusedPassiveDesired;
  final int? fusedPassiveDesiredEpoch;
  final bool? fusedPassiveRequestInFlight;
  final bool? fusedPassiveConfirmed;
  final bool? fusedPassiveRemovalInFlight;
  final bool? fusedPassiveRemovalConfirmed;
  final String? fusedPassiveDesiredPriority;
  final String? fusedPassiveConfirmedPriority;
  final int? fusedPassiveDesiredIntervalMillis;
  final int? fusedPassiveConfirmedIntervalMillis;
  final int? fusedPassiveLastSuccessAtMillis;
  final int? fusedPassiveLastFailureAtMillis;
  final String? fusedPassiveLastFailureReason;
  final int? fusedRequestRemovalInFlightSinceMillis;
  final int? fusedRequestStaleCallbackCount;
  final String? fusedRequestLastStaleCallbackReason;
  final int? fusedRequestIgnoredCallbackCount;
  final String? fusedRequestLastIgnoredCallbackReason;
  final bool? activityEligible;
  final bool? recoveryEligible;
  final bool? proximityPendingIntentExists;
  final bool? passiveLocationPendingIntentExists;
  final bool? activityPendingIntentExists;
  final bool? activityTransitionPendingIntentExists;
  final bool? activityPeriodicPendingIntentExists;
  final bool? activityControllerDesired;
  final bool? activityTransitionDesired;
  final bool? activityPeriodicDesired;
  final String? activityPeriodicMode;
  final bool? activityPeriodicBackstopEnabled;
  final int? activityOperationEpoch;
  final int? activityMonitoringSessionGeneration;
  final int? activityDesiredPeriodicIntervalMillis;
  final int? activityConfirmedPeriodicIntervalMillis;
  final String? activityConfirmedPeriodicOwner;
  final bool? activityTransitionRequested;
  final bool? activityTransitionRequestInFlight;
  final bool? activityTransitionConfirmed;
  final bool? activityTransitionRemovalInFlight;
  final bool? activityTransitionRemovalConfirmed;
  final int? activityTransitionLastSuccessAtMillis;
  final int? activityTransitionLastFailureAtMillis;
  final String? activityTransitionLastFailureReason;
  final bool? activityPeriodicRequested;
  final bool? activityPeriodicRequestInFlight;
  final bool? activityPeriodicConfirmed;
  final bool? activityPeriodicRemovalInFlight;
  final bool? activityPeriodicRemovalConfirmed;
  final bool? activityPeriodicRemovalRequired;
  final int? activityBootstrapRequestedAtMillis;
  final int? activityBootstrapDeadlineMillis;
  final bool? activityBootstrapResultReceived;
  final bool? activityBootstrapCompleted;
  final bool? activityBootstrapTimeoutPendingIntentExists;
  final int? activityPeriodicLastSuccessAtMillis;
  final int? activityPeriodicLastFailureAtMillis;
  final String? activityPeriodicLastFailureReason;
  final int? activityRemovalInFlightSinceMillis;
  final bool? activityRemovalOverdue;
  final int? activityStaleOperationCallbackCount;
  final String? activityLastStaleOperationCallbackReason;
  final int? activityIgnoredCallbackCount;
  final String? activityLastIgnoredCallbackReason;
  final String? activityPeriodicReason;
  final int? activityPeriodicRequestedAtMillis;
  final int? activityPeriodicIntervalMillis;
  final int? lastActivityPeriodicResultAtMillis;
  final String? activityStationarySource;
  final bool? bootFollowUpPendingIntentExists;
  final bool? recoveryAlarmPendingIntentExists;
  final bool? locationConfirmStartPendingIntentExists;
  final bool? locationConfirmWatchdogPendingIntentExists;
  final bool? foregroundServiceRearmPendingIntentExists;
  final int? foregroundQueueSize;
  final List<Map<Object?, Object?>> foregroundQueueItems;
  final int? backgroundQueueSize;
  final String? proximityAlarmKind;
  final bool? proximityAlarmPendingIntentExists;
  final Map<Object?, Object?> proximityAlarmSchedule;
  final bool? dormantFarProbePendingIntentExists;
  final Map<Object?, Object?> dormantFarProbeAlarmSchedule;
  final int? lastLocationWakeAtMillis;
  final String? lastLocationWakeSource;
  final String? lastLocationWakeProvider;
  final double? lastLocationWakeAccuracyMeters;
  final int? lastLocationWakeAgeMillis;
  final String? lastLocationWakeNearestFenceId;
  final double? lastLocationWakeEdgeDistanceMeters;
  final bool? lastLocationWakeWithinProximity;
  final int? lastConfirmQueueAtMillis;
  final int? lastConfirmQueueRequestId;
  final String? lastConfirmQueueFenceId;
  final bool? lastConfirmQueueIsProximity;
  final String? lastConfirmQueueSource;
  final int? lastConfirmQueueAgeMillis;
  final int? lastConfirmRequestAtMillis;
  final String? lastConfirmSource;
  final String? lastConfirmPriority;
  final int? lastConfirmTimeoutMillis;
  final String? lastConfirmResult;
  final int? lastConfirmElapsedMillis;
  final String? lastConfirmLocationProvider;
  final double? lastConfirmLocationAccuracyMeters;
  final int? lastConfirmLocationAgeMillis;
  final String? lastConfirmFailureMessage;
  final String? lastNativeExitConfirmTimingMode;
  final String? lastNativeExitConfirmTimingReason;
  final int? lastNativeExitConfirmTimingAtMillis;
  final String? lastNativeEnterConfirmTimingMode;
  final String? lastNativeEnterConfirmTimingReason;
  final int? lastNativeEnterConfirmTimingAtMillis;
  final int? lastBoundaryDecisionAtMillis;
  final String? lastBoundaryDecision;
  final String? lastBoundaryDecisionFenceId;
  final String? lastBoundaryDecisionSource;
  final double? lastBoundaryDistanceMeters;
  final double? lastBoundaryRadiusMeters;
  final double? lastBoundaryEdgeDistanceMeters;
  final double? lastBoundaryAccuracyMeters;
  final bool? lastBoundaryMock;
  final String? lastBoundaryQueuedEvent;
  final int? lastMockLocationDecisionAtMillis;
  final SGMockLocationPolicy? lastMockLocationPolicy;
  final String? lastMockLocationAction;
  final String? lastMockLocationSource;
  final String? lastMockLocationProvider;
  final double? lastMockLocationAccuracyMeters;
  final int? lastMockLocationAgeMillis;
  final bool? lastMockLocationSuppressed;
  final int? lastSmartCallbackAtMillis;
  final String? lastSmartCallbackEvent;
  final String? lastSmartCallbackFenceId;
  final String? lastSmartCallbackSource;
  final String? lastSmartCallbackResult;
  final int? lastSmartCallbackEnqueuedAtMillis;
  final int? lastSmartCallbackEventAtMillis;
  final String? lastSmartCallbackTimestampSource;
  final String? lastSmartCallbackDeliveryPath;
  final int? lastSmartCallbackTriggerToDeliveryLatencyMillis;
  final bool? lastSmartCallbackDeviceIdleModeAtDelivery;
  final int? lastCallbackDispatchAtMillis;
  final String? lastCallbackDispatchEvent;
  final String? lastCallbackDispatchFenceId;
  final String? lastCallbackDispatchResult;
  final int? lastCallbackDispatchCallbackHandle;
  final bool? lastCallbackDispatchRetryOnFailure;
  final int? lastCallbackDispatchEventAtMillis;
  final String? lastCallbackDispatchTimestampSource;
  final String? lastCallbackDispatchTimeReasonCode;
  final bool? lastCallbackDispatchTimeTrusted;
  final String? lastCallbackDispatchError;
  final String? lastBootRecoverySource;
  final String? lastBootRecoveryAction;
  final int? lastBootRecoveryStartedAtMillis;
  final int? lastBootRecoveryFinishedAtMillis;
  final String? lastBootRecoveryResult;
  final int? lastBootRecoveryNativeFenceCount;
  final int? lastBootRecoverySmartFenceCount;
  final int? lastBootRecoveryElapsedMillis;
  final String? lastBootRecoveryFailureMessage;
  final int? lastBootFollowUpScheduledAtMillis;
  final int? lastBootFollowUpTriggerAtMillis;
  final String? lastBootFollowUpAction;
  final String? lastBootFollowUpScheduleResult;
  final String? lastBootFollowUpFailureMessage;
  final int? lastBroadcastDeadlineFinishedAtMillis;
  final String? lastBroadcastDeadlineReason;
  final String? lastBroadcastDeadlineTag;
  final int? lastRecoveryMainThreadTimeoutAtMillis;
  final String? lastRecoveryMainThreadTimeoutSource;
  final String? lastRecoveryMainThreadTimeoutReason;

  const SmartGeofenceStatus({
    required this.nativeStatus,
    required this.smartLayerSupported,
    this.smartStatusState = SGSmartStatusState.unsupported,
    this.smartStatusSchemaVersion,
    this.smartStatusIssues = const <String>[],
    this.rawStatus = const <Object?, Object?>{},
    this.smartLayerMode,
    this.smartLayerModeReason,
    this.config = const SGConfig(),
    this.diagnosticEventJournal = const <Map<Object?, Object?>>[],
    this.diagnosticEventJournalCapacity,
    this.diagnosticEventJournalCount,
    this.diagnosticEventJournalSequence,
    this.diagnosticEventJournalDroppedCount,
    this.diagnosticEventJournalOldestAtMillis,
    this.diagnosticEventJournalNewestAtMillis,
    this.configAppliedAtMillis,
    this.configFingerprint,
    this.packageVersion,
    this.buildRevision,
    this.diagnosticCounters = const <String, int>{},
    this.fenceStates = const <Object?, Object?>{},
    this.mirroredFenceIds = const <String>[],
    this.mirroredFenceCount,
    this.radiusNormalizations = const <String, SGRadiusNormalization>{},
    this.androidSdkInt,
    this.deviceManufacturer,
    this.deviceModel,
    this.locationPermissionGranted,
    this.fineLocationGranted,
    this.backgroundLocationPermissionGranted,
    this.activityPermissionGranted,
    this.foregroundServicePermissionGranted,
    this.foregroundServiceLocationPermissionGranted,
    this.notificationPermissionGranted,
    this.foregroundNotificationSticky,
    this.foregroundNotificationTapAction,
    this.foregroundNotificationShowWhileMonitoring,
    this.powerSaveMode,
    this.deviceIdleMode,
    this.batteryOptimizationsIgnored,
    this.batteryLevelPercent,
    this.batteryCharging,
    this.exactAlarmPermissionMode,
    this.exactAlarmPermissionDeclared,
    this.exactAlarmPermissionRequired,
    this.exactAlarmPermissionStatus,
    this.exactAlarmPermissionGranted,
    this.exactAlarmSettingsIntentAvailable,
    this.exactAlarmAppSettingsFallbackAvailable,
    this.exactAlarmSettingsCanOpen,
    this.exactAlarmStrictStartupBlocked,
    this.nativeConfirmDelayRequiresExactAlarm,
    this.nativeConfirmDelayExactSchedulingAvailable,
    this.nativeEnterConfirmImmediateTimingBypassPossible,
    this.nativeExitConfirmImmediateTimingBypassPossible,
    this.transitionValidationEnterBlocksEarlyConfirmationAcquisition,
    this.transitionValidationExitBlocksEarlyConfirmationAcquisition,
    this.transitionValidationEnterBlocksRawNativeFallback,
    this.transitionValidationExitBlocksRawNativeFallback,
    this.nativeConfirmDelayMillis,
    this.nativeConfirmMaxAttempts,
    this.transitionValidationEnabled,
    this.transitionValidationEnterEnabled,
    this.transitionValidationExitEnabled,
    this.transitionValidationMinimumDelayMillis,
    this.nativeExitFallbackReceiverDeclared,
    this.nativeExitFallbackPendingCount,
    this.nativeExitFallbackPendingFenceIds = const <String>[],
    this.nativeExitFallbackAlarmPendingIntentExists,
    this.nativeExitFallbackAlarmSchedule = const <Object?, Object?>{},
    this.nativeExitFallbackPendingDetails = const <Map<Object?, Object?>>[],
    this.nativeEnterFallbackReceiverDeclared,
    this.nativeEnterFallbackPendingCount,
    this.nativeEnterFallbackPendingFenceIds = const <String>[],
    this.nativeEnterFallbackAlarmPendingIntentExists,
    this.nativeEnterFallbackAlarmSchedule = const <Object?, Object?>{},
    this.nativeEnterFallbackPendingDetails = const <Map<Object?, Object?>>[],
    this.lastExactAlarmSettingsOpenedAtMillis,
    this.lastExactAlarmSettingsOpenResult,
    this.lastExactAlarmSettingsOpenRoute,
    this.lastExactAlarmSettingsOpenFailureReason,
    this.locationServicesEnabled,
    this.monitoringTerminallyStopped,
    this.monitoringStopPhase,
    this.monitoringStopReason,
    this.monitoringStoppedAtMillis,
    this.monitoringStopEventId,
    this.monitoringStopCallbackPending,
    this.monitoringNativeCleanupComplete,
    this.monitoringNativeCleanupPendingCount,
    this.fusedLocationUpdateReceiverDeclared,
    this.activityReceiverDeclared,
    this.bootReceiverDeclared,
    this.exactAlarmPermissionStateReceiverDeclared,
    this.bootFollowUpReceiverDeclared,
    this.recoveryAlarmReceiverDeclared,
    this.locationConfirmReceiverDeclared,
    this.foregroundServiceLaunchReceiverDeclared,
    this.foregroundStartCoordinatorWindowClosed,
    this.foregroundStartQueuedServices = const <String>[],
    this.foregroundStartBatchPendingIntentExists,
    this.locationConfirmServiceDeclared,
    this.locationConfirmServiceHasLocationType,
    this.locationConfirmServiceRunning,
    this.locationConfirmServiceForegroundReady,
    this.locationConfirmLaunchToken,
    this.locationConfirmLaunchRequestedAtMillis,
    this.locationConfirmForegroundReadyAtMillis,
    this.locationConfirmLastLaunchFailureAtMillis,
    this.locationConfirmLastLaunchFailureReason,
    this.locationConfirmCanRun,
    this.locationConfirmQueueSize,
    this.locationConfirmQueueTotalSize,
    this.locationConfirmQueueParkedSize,
    this.locationConfirmQueueItems = const <Map<Object?, Object?>>[],
    this.locationDisabledRecoveryPendingIntentExists,
    this.locationDisabledRecoveryAlarmSchedule = const <Object?, Object?>{},
    this.proximityAlarmReceiverDeclared,
    this.dormantFarProbeReceiverDeclared,
    this.dormantFarActive,
    this.dormantFarReason,
    this.dormantFarBatteryMode,
    this.dormantFarLastEdgeDistanceMeters,
    this.dormantFarNearestFenceId,
    this.dormantFarLastAcceptedFixSource,
    this.dormantFarLastAcceptedFixAtMillis,
    this.dormantFarNextProbeAtMillis,
    this.dormantFarLastProbeAtMillis,
    this.dormantFarLastProbeResult,
    this.adaptiveProximityDisplacementMode,
    this.adaptiveProximityDisplacementMeters,
    this.adaptiveProximityLastEdgeDistanceMeters,
    this.proximityPulseStartedAtMillis,
    this.proximityPulseIdleTicks,
    this.proximityPulseRateMode,
    this.proximityPulsePurpose,
    this.proximityPulseSchedulingActive,
    this.proximityPulseActiveHoursNow,
    this.proximityPulseCanRun,
    this.fusedLocationLastHealthyAtMillis,
    this.fusedLocationHealthyAgeMillis,
    this.fusedLocationLastHealthySource,
    this.fusedLocationRecoveryStartedAtMillis,
    this.fusedLocationLastRecoveryEndedAtMillis,
    this.fusedLocationLastRecoveryReason,
    this.fusedLocationBalancedRefreshCount,
    this.proximityEligible,
    this.passiveLocationEligible,
    this.fusedBalancedDesired,
    this.fusedBalancedDesiredEpoch,
    this.fusedBalancedRequestInFlight,
    this.fusedBalancedConfirmed,
    this.fusedBalancedRemovalInFlight,
    this.fusedBalancedRemovalConfirmed,
    this.fusedBalancedDesiredPriority,
    this.fusedBalancedConfirmedPriority,
    this.fusedBalancedDesiredIntervalMillis,
    this.fusedBalancedConfirmedIntervalMillis,
    this.fusedBalancedDesiredDisplacementMeters,
    this.fusedBalancedConfirmedDisplacementMeters,
    this.fusedBalancedDesiredAdaptiveMode,
    this.fusedBalancedConfirmedAdaptiveMode,
    this.fusedBalancedLastSuccessAtMillis,
    this.fusedBalancedLastFailureAtMillis,
    this.fusedBalancedLastFailureReason,
    this.fusedPassiveDesired,
    this.fusedPassiveDesiredEpoch,
    this.fusedPassiveRequestInFlight,
    this.fusedPassiveConfirmed,
    this.fusedPassiveRemovalInFlight,
    this.fusedPassiveRemovalConfirmed,
    this.fusedPassiveDesiredPriority,
    this.fusedPassiveConfirmedPriority,
    this.fusedPassiveDesiredIntervalMillis,
    this.fusedPassiveConfirmedIntervalMillis,
    this.fusedPassiveLastSuccessAtMillis,
    this.fusedPassiveLastFailureAtMillis,
    this.fusedPassiveLastFailureReason,
    this.fusedRequestRemovalInFlightSinceMillis,
    this.fusedRequestStaleCallbackCount,
    this.fusedRequestLastStaleCallbackReason,
    this.fusedRequestIgnoredCallbackCount,
    this.fusedRequestLastIgnoredCallbackReason,
    this.activityEligible,
    this.recoveryEligible,
    this.proximityPendingIntentExists,
    this.passiveLocationPendingIntentExists,
    this.activityPendingIntentExists,
    this.activityTransitionPendingIntentExists,
    this.activityPeriodicPendingIntentExists,
    this.activityControllerDesired,
    this.activityTransitionDesired,
    this.activityPeriodicDesired,
    this.activityPeriodicMode,
    this.activityPeriodicBackstopEnabled,
    this.activityOperationEpoch,
    this.activityMonitoringSessionGeneration,
    this.activityDesiredPeriodicIntervalMillis,
    this.activityConfirmedPeriodicIntervalMillis,
    this.activityConfirmedPeriodicOwner,
    this.activityTransitionRequested,
    this.activityTransitionRequestInFlight,
    this.activityTransitionConfirmed,
    this.activityTransitionRemovalInFlight,
    this.activityTransitionRemovalConfirmed,
    this.activityTransitionLastSuccessAtMillis,
    this.activityTransitionLastFailureAtMillis,
    this.activityTransitionLastFailureReason,
    this.activityPeriodicRequested,
    this.activityPeriodicRequestInFlight,
    this.activityPeriodicConfirmed,
    this.activityPeriodicRemovalInFlight,
    this.activityPeriodicRemovalConfirmed,
    this.activityPeriodicRemovalRequired,
    this.activityBootstrapRequestedAtMillis,
    this.activityBootstrapDeadlineMillis,
    this.activityBootstrapResultReceived,
    this.activityBootstrapCompleted,
    this.activityBootstrapTimeoutPendingIntentExists,
    this.activityPeriodicLastSuccessAtMillis,
    this.activityPeriodicLastFailureAtMillis,
    this.activityPeriodicLastFailureReason,
    this.activityRemovalInFlightSinceMillis,
    this.activityRemovalOverdue,
    this.activityStaleOperationCallbackCount,
    this.activityLastStaleOperationCallbackReason,
    this.activityIgnoredCallbackCount,
    this.activityLastIgnoredCallbackReason,
    this.activityPeriodicReason,
    this.activityPeriodicRequestedAtMillis,
    this.activityPeriodicIntervalMillis,
    this.lastActivityPeriodicResultAtMillis,
    this.activityStationarySource,
    this.bootFollowUpPendingIntentExists,
    this.recoveryAlarmPendingIntentExists,
    this.locationConfirmStartPendingIntentExists,
    this.locationConfirmWatchdogPendingIntentExists,
    this.foregroundServiceRearmPendingIntentExists,
    this.foregroundQueueSize,
    this.foregroundQueueItems = const <Map<Object?, Object?>>[],
    this.backgroundQueueSize,
    this.proximityAlarmKind,
    this.proximityAlarmPendingIntentExists,
    this.proximityAlarmSchedule = const <Object?, Object?>{},
    this.dormantFarProbePendingIntentExists,
    this.dormantFarProbeAlarmSchedule = const <Object?, Object?>{},
    this.lastLocationWakeAtMillis,
    this.lastLocationWakeSource,
    this.lastLocationWakeProvider,
    this.lastLocationWakeAccuracyMeters,
    this.lastLocationWakeAgeMillis,
    this.lastLocationWakeNearestFenceId,
    this.lastLocationWakeEdgeDistanceMeters,
    this.lastLocationWakeWithinProximity,
    this.lastConfirmQueueAtMillis,
    this.lastConfirmQueueRequestId,
    this.lastConfirmQueueFenceId,
    this.lastConfirmQueueIsProximity,
    this.lastConfirmQueueSource,
    this.lastConfirmQueueAgeMillis,
    this.lastConfirmRequestAtMillis,
    this.lastConfirmSource,
    this.lastConfirmPriority,
    this.lastConfirmTimeoutMillis,
    this.lastConfirmResult,
    this.lastConfirmElapsedMillis,
    this.lastConfirmLocationProvider,
    this.lastConfirmLocationAccuracyMeters,
    this.lastConfirmLocationAgeMillis,
    this.lastConfirmFailureMessage,
    this.lastNativeExitConfirmTimingMode,
    this.lastNativeExitConfirmTimingReason,
    this.lastNativeExitConfirmTimingAtMillis,
    this.lastNativeEnterConfirmTimingMode,
    this.lastNativeEnterConfirmTimingReason,
    this.lastNativeEnterConfirmTimingAtMillis,
    this.lastBoundaryDecisionAtMillis,
    this.lastBoundaryDecision,
    this.lastBoundaryDecisionFenceId,
    this.lastBoundaryDecisionSource,
    this.lastBoundaryDistanceMeters,
    this.lastBoundaryRadiusMeters,
    this.lastBoundaryEdgeDistanceMeters,
    this.lastBoundaryAccuracyMeters,
    this.lastBoundaryMock,
    this.lastBoundaryQueuedEvent,
    this.lastMockLocationDecisionAtMillis,
    this.lastMockLocationPolicy,
    this.lastMockLocationAction,
    this.lastMockLocationSource,
    this.lastMockLocationProvider,
    this.lastMockLocationAccuracyMeters,
    this.lastMockLocationAgeMillis,
    this.lastMockLocationSuppressed,
    this.lastSmartCallbackAtMillis,
    this.lastSmartCallbackEvent,
    this.lastSmartCallbackFenceId,
    this.lastSmartCallbackSource,
    this.lastSmartCallbackResult,
    this.lastSmartCallbackEnqueuedAtMillis,
    this.lastSmartCallbackEventAtMillis,
    this.lastSmartCallbackTimestampSource,
    this.lastSmartCallbackDeliveryPath,
    this.lastSmartCallbackTriggerToDeliveryLatencyMillis,
    this.lastSmartCallbackDeviceIdleModeAtDelivery,
    this.lastCallbackDispatchAtMillis,
    this.lastCallbackDispatchEvent,
    this.lastCallbackDispatchFenceId,
    this.lastCallbackDispatchResult,
    this.lastCallbackDispatchCallbackHandle,
    this.lastCallbackDispatchRetryOnFailure,
    this.lastCallbackDispatchEventAtMillis,
    this.lastCallbackDispatchTimestampSource,
    this.lastCallbackDispatchTimeReasonCode,
    this.lastCallbackDispatchTimeTrusted,
    this.lastCallbackDispatchError,
    this.lastBootRecoverySource,
    this.lastBootRecoveryAction,
    this.lastBootRecoveryStartedAtMillis,
    this.lastBootRecoveryFinishedAtMillis,
    this.lastBootRecoveryResult,
    this.lastBootRecoveryNativeFenceCount,
    this.lastBootRecoverySmartFenceCount,
    this.lastBootRecoveryElapsedMillis,
    this.lastBootRecoveryFailureMessage,
    this.lastBootFollowUpScheduledAtMillis,
    this.lastBootFollowUpTriggerAtMillis,
    this.lastBootFollowUpAction,
    this.lastBootFollowUpScheduleResult,
    this.lastBootFollowUpFailureMessage,
    this.lastBroadcastDeadlineFinishedAtMillis,
    this.lastBroadcastDeadlineReason,
    this.lastBroadcastDeadlineTag,
    this.lastRecoveryMainThreadTimeoutAtMillis,
    this.lastRecoveryMainThreadTimeoutSource,
    this.lastRecoveryMainThreadTimeoutReason,
  });

  factory SmartGeofenceStatus.fromMap({
    required ng.NativeGeofenceStatus nativeStatus,
    required bool smartLayerSupported,
    required Map<Object?, Object?> map,
  }) {
    final validation = _validateSmartStatus(
      smartLayerSupported: smartLayerSupported,
      map: map,
    );
    final configMap = _map(map, 'config');
    return SmartGeofenceStatus(
      nativeStatus: nativeStatus,
      smartLayerSupported: smartLayerSupported,
      smartStatusState: validation.state,
      smartStatusSchemaVersion: validation.schemaVersion,
      smartStatusIssues: validation.issues,
      rawStatus: Map<Object?, Object?>.of(map),
      config: _configFromMap(configMap),
      smartLayerMode: _stringOrNull(map, 'smartLayerMode'),
      smartLayerModeReason: _stringOrNull(map, 'smartLayerModeReason'),
      diagnosticEventJournal: _mapList(map, 'diagnosticEventJournal'),
      diagnosticEventJournalCapacity: _intOrNull(
        map,
        'diagnosticEventJournalCapacity',
      ),
      diagnosticEventJournalCount: _intOrNull(
        map,
        'diagnosticEventJournalCount',
      ),
      diagnosticEventJournalSequence: _intOrNull(
        map,
        'diagnosticEventJournalSequence',
      ),
      diagnosticEventJournalDroppedCount: _intOrNull(
        map,
        'diagnosticEventJournalDroppedCount',
      ),
      diagnosticEventJournalOldestAtMillis: _intOrNull(
        map,
        'diagnosticEventJournalOldestAtMillis',
      ),
      diagnosticEventJournalNewestAtMillis: _intOrNull(
        map,
        'diagnosticEventJournalNewestAtMillis',
      ),
      configAppliedAtMillis: _intOrNull(map, 'configAppliedAtMillis'),
      configFingerprint: _stringOrNull(map, 'configFingerprint'),
      packageVersion: _stringOrNull(map, 'packageVersion'),
      buildRevision: _stringOrNull(map, 'buildRevision'),
      diagnosticCounters: _stringIntMap(map, 'diagnosticCounters'),
      fenceStates: _map(map, 'fenceStates'),
      mirroredFenceIds: _stringList(map, 'mirroredFenceIds'),
      mirroredFenceCount: _intOrNull(map, 'mirroredFenceCount'),
      radiusNormalizations: _radiusNormalizationMap(
        map,
        'radiusNormalizations',
      ),
      androidSdkInt: _intOrNull(map, 'androidSdkInt'),
      deviceManufacturer: _stringOrNull(map, 'deviceManufacturer'),
      deviceModel: _stringOrNull(map, 'deviceModel'),
      locationPermissionGranted: _boolOrNull(map, 'locationPermissionGranted'),
      fineLocationGranted: _boolOrNull(map, 'fineLocationGranted'),
      backgroundLocationPermissionGranted: _boolOrNull(
        map,
        'backgroundLocationPermissionGranted',
      ),
      activityPermissionGranted: _boolOrNull(map, 'activityPermissionGranted'),
      foregroundServicePermissionGranted: _boolOrNull(
        map,
        'foregroundServicePermissionGranted',
      ),
      foregroundServiceLocationPermissionGranted: _boolOrNull(
        map,
        'foregroundServiceLocationPermissionGranted',
      ),
      notificationPermissionGranted: _boolOrNull(
        map,
        'notificationPermissionGranted',
      ),
      foregroundNotificationSticky: _boolOrNull(
        map,
        'foregroundNotificationSticky',
      ),
      foregroundNotificationTapAction: _foregroundNotificationTapAction(
        map,
        'foregroundNotificationTapAction',
      ),
      foregroundNotificationShowWhileMonitoring:
          _foregroundNotificationShowWhileMonitoring(map),
      powerSaveMode: _boolOrNull(map, 'powerSaveMode'),
      deviceIdleMode: _boolOrNull(map, 'deviceIdleMode'),
      batteryOptimizationsIgnored: _boolOrNull(
        map,
        'batteryOptimizationsIgnored',
      ),
      batteryLevelPercent: _intOrNull(map, 'batteryLevelPercent'),
      batteryCharging: _boolOrNull(map, 'batteryCharging'),
      exactAlarmPermissionMode: _exactAlarmPermissionMode(
        map,
        'exactAlarmPermissionMode',
      ),
      exactAlarmPermissionDeclared: _boolOrNull(
        map,
        'exactAlarmPermissionDeclared',
      ),
      exactAlarmPermissionRequired: _boolOrNull(
        map,
        'exactAlarmPermissionRequired',
      ),
      exactAlarmPermissionStatus: _exactAlarmPermissionStatus(
        map,
        'exactAlarmPermissionStatus',
      ),
      exactAlarmPermissionGranted: _boolOrNull(
        map,
        'exactAlarmPermissionGranted',
      ),
      exactAlarmSettingsIntentAvailable: _boolOrNull(
        map,
        'exactAlarmSettingsIntentAvailable',
      ),
      exactAlarmAppSettingsFallbackAvailable: _boolOrNull(
        map,
        'exactAlarmAppSettingsFallbackAvailable',
      ),
      exactAlarmSettingsCanOpen: _boolOrNull(map, 'exactAlarmSettingsCanOpen'),
      exactAlarmStrictStartupBlocked: _boolOrNull(
        map,
        'exactAlarmStrictStartupBlocked',
      ),
      nativeConfirmDelayRequiresExactAlarm: _boolOrNull(
        map,
        'nativeConfirmDelayRequiresExactAlarm',
      ),
      nativeConfirmDelayExactSchedulingAvailable: _boolOrNull(
        map,
        'nativeConfirmDelayExactSchedulingAvailable',
      ),
      nativeEnterConfirmImmediateTimingBypassPossible: _boolOrNull(
        map,
        'nativeEnterConfirmImmediateTimingBypassPossible',
      ),
      nativeExitConfirmImmediateTimingBypassPossible: _boolOrNull(
        map,
        'nativeExitConfirmImmediateTimingBypassPossible',
      ),
      transitionValidationEnterBlocksEarlyConfirmationAcquisition: _boolOrNull(
        map,
        'transitionValidationEnterBlocksEarlyConfirmationAcquisition',
      ),
      transitionValidationExitBlocksEarlyConfirmationAcquisition: _boolOrNull(
        map,
        'transitionValidationExitBlocksEarlyConfirmationAcquisition',
      ),
      transitionValidationEnterBlocksRawNativeFallback: _boolOrNull(
        map,
        'transitionValidationEnterBlocksRawNativeFallback',
      ),
      transitionValidationExitBlocksRawNativeFallback: _boolOrNull(
        map,
        'transitionValidationExitBlocksRawNativeFallback',
      ),
      nativeConfirmDelayMillis: _intOrNull(map, 'nativeConfirmDelayMillis'),
      nativeConfirmMaxAttempts: _intOrNull(map, 'nativeConfirmMaxAttempts'),
      transitionValidationEnabled: _boolOrNull(
        map,
        'transitionValidationEnabled',
      ),
      transitionValidationEnterEnabled: _boolOrNull(
        map,
        'transitionValidationEnterEnabled',
      ),
      transitionValidationExitEnabled: _boolOrNull(
        map,
        'transitionValidationExitEnabled',
      ),
      transitionValidationMinimumDelayMillis: _intOrNull(
        map,
        'transitionValidationMinimumDelayMillis',
      ),
      nativeExitFallbackReceiverDeclared: _boolOrNull(
        map,
        'nativeExitFallbackReceiverDeclared',
      ),
      nativeExitFallbackPendingCount: _intOrNull(
        map,
        'nativeExitFallbackPendingCount',
      ),
      nativeExitFallbackPendingFenceIds: _stringList(
        map,
        'nativeExitFallbackPendingFenceIds',
      ),
      nativeExitFallbackAlarmPendingIntentExists: _boolOrNull(
        map,
        'nativeExitFallbackAlarmPendingIntentExists',
      ),
      nativeExitFallbackAlarmSchedule: _map(
        map,
        'nativeExitFallbackAlarmSchedule',
      ),
      nativeExitFallbackPendingDetails: _mapList(
        map,
        'nativeExitFallbackPendingDetails',
      ),
      nativeEnterFallbackReceiverDeclared: _boolOrNull(
        map,
        'nativeEnterFallbackReceiverDeclared',
      ),
      nativeEnterFallbackPendingCount: _intOrNull(
        map,
        'nativeEnterFallbackPendingCount',
      ),
      nativeEnterFallbackPendingFenceIds: _stringList(
        map,
        'nativeEnterFallbackPendingFenceIds',
      ),
      nativeEnterFallbackAlarmPendingIntentExists: _boolOrNull(
        map,
        'nativeEnterFallbackAlarmPendingIntentExists',
      ),
      nativeEnterFallbackAlarmSchedule: _map(
        map,
        'nativeEnterFallbackAlarmSchedule',
      ),
      nativeEnterFallbackPendingDetails: _mapList(
        map,
        'nativeEnterFallbackPendingDetails',
      ),
      lastExactAlarmSettingsOpenedAtMillis: _intOrNull(
        map,
        'lastExactAlarmSettingsOpenedAtMillis',
      ),
      lastExactAlarmSettingsOpenResult: _stringOrNull(
        map,
        'lastExactAlarmSettingsOpenResult',
      ),
      lastExactAlarmSettingsOpenRoute: _stringOrNull(
        map,
        'lastExactAlarmSettingsOpenRoute',
      ),
      lastExactAlarmSettingsOpenFailureReason: _stringOrNull(
        map,
        'lastExactAlarmSettingsOpenFailureReason',
      ),
      locationServicesEnabled: _boolOrNull(map, 'locationServicesEnabled'),
      monitoringTerminallyStopped: _boolOrNull(
        map,
        'monitoringTerminallyStopped',
      ),
      monitoringStopPhase: _stringOrNull(map, 'monitoringStopPhase'),
      monitoringStopReason: _stringOrNull(map, 'monitoringStopReason'),
      monitoringStoppedAtMillis: _intOrNull(map, 'monitoringStoppedAtMillis'),
      monitoringStopEventId: _stringOrNull(map, 'monitoringStopEventId'),
      monitoringStopCallbackPending: _boolOrNull(
        map,
        'monitoringStopCallbackPending',
      ),
      monitoringNativeCleanupComplete: _boolOrNull(
        map,
        'monitoringNativeCleanupComplete',
      ),
      monitoringNativeCleanupPendingCount: _intOrNull(
        map,
        'monitoringNativeCleanupPendingCount',
      ),
      fusedLocationUpdateReceiverDeclared: _boolOrNull(
        map,
        'fusedLocationUpdateReceiverDeclared',
      ),
      activityReceiverDeclared: _boolOrNull(map, 'activityReceiverDeclared'),
      bootReceiverDeclared: _boolOrNull(map, 'bootReceiverDeclared'),
      exactAlarmPermissionStateReceiverDeclared: _boolOrNull(
        map,
        'exactAlarmPermissionStateReceiverDeclared',
      ),
      bootFollowUpReceiverDeclared: _boolOrNull(
        map,
        'bootFollowUpReceiverDeclared',
      ),
      recoveryAlarmReceiverDeclared: _boolOrNull(
        map,
        'recoveryAlarmReceiverDeclared',
      ),
      locationConfirmReceiverDeclared: _boolOrNull(
        map,
        'locationConfirmReceiverDeclared',
      ),
      foregroundServiceLaunchReceiverDeclared: _boolOrNull(
        map,
        'foregroundServiceLaunchReceiverDeclared',
      ),
      foregroundStartCoordinatorWindowClosed: _boolOrNull(
        map,
        'foregroundStartCoordinatorWindowClosed',
      ),
      foregroundStartQueuedServices: _stringList(
        map,
        'foregroundStartQueuedServices',
      ),
      foregroundStartBatchPendingIntentExists: _boolOrNull(
        map,
        'foregroundStartBatchPendingIntentExists',
      ),
      locationConfirmServiceDeclared: _boolOrNull(
        map,
        'locationConfirmServiceDeclared',
      ),
      locationConfirmServiceHasLocationType: _boolOrNull(
        map,
        'locationConfirmServiceHasLocationType',
      ),
      locationConfirmServiceRunning: _boolOrNull(
        map,
        'locationConfirmServiceRunning',
      ),
      locationConfirmServiceForegroundReady: _boolOrNull(
        map,
        'locationConfirmServiceForegroundReady',
      ),
      locationConfirmLaunchToken: _intOrNull(map, 'locationConfirmLaunchToken'),
      locationConfirmLaunchRequestedAtMillis: _intOrNull(
        map,
        'locationConfirmLaunchRequestedAtMillis',
      ),
      locationConfirmForegroundReadyAtMillis: _intOrNull(
        map,
        'locationConfirmForegroundReadyAtMillis',
      ),
      locationConfirmLastLaunchFailureAtMillis: _intOrNull(
        map,
        'locationConfirmLastLaunchFailureAtMillis',
      ),
      locationConfirmLastLaunchFailureReason: _stringOrNull(
        map,
        'locationConfirmLastLaunchFailureReason',
      ),
      locationConfirmCanRun: _boolOrNull(map, 'locationConfirmCanRun'),
      locationConfirmQueueSize: _intOrNull(map, 'locationConfirmQueueSize'),
      locationConfirmQueueTotalSize: _intOrNull(
        map,
        'locationConfirmQueueTotalSize',
      ),
      locationConfirmQueueParkedSize: _intOrNull(
        map,
        'locationConfirmQueueParkedSize',
      ),
      locationConfirmQueueItems: _mapList(map, 'locationConfirmQueueItems'),
      locationDisabledRecoveryPendingIntentExists: _boolOrNull(
        map,
        'locationDisabledRecoveryPendingIntentExists',
      ),
      locationDisabledRecoveryAlarmSchedule: _map(
        map,
        'locationDisabledRecoveryAlarmSchedule',
      ),
      proximityAlarmReceiverDeclared: _boolOrNull(
        map,
        'proximityAlarmReceiverDeclared',
      ),
      dormantFarProbeReceiverDeclared: _boolOrNull(
        map,
        'dormantFarProbeReceiverDeclared',
      ),
      dormantFarActive: _boolOrNull(map, 'dormantFarActive'),
      dormantFarReason: _stringOrNull(map, 'dormantFarReason'),
      dormantFarBatteryMode: _stringOrNull(map, 'dormantFarBatteryMode'),
      dormantFarLastEdgeDistanceMeters: _doubleOrNull(
        map,
        'dormantFarLastEdgeDistanceMeters',
      ),
      dormantFarNearestFenceId: _stringOrNull(map, 'dormantFarNearestFenceId'),
      dormantFarLastAcceptedFixSource: _stringOrNull(
        map,
        'dormantFarLastAcceptedFixSource',
      ),
      dormantFarLastAcceptedFixAtMillis: _intOrNull(
        map,
        'dormantFarLastAcceptedFixAtMillis',
      ),
      dormantFarNextProbeAtMillis: _intOrNull(
        map,
        'dormantFarNextProbeAtMillis',
      ),
      dormantFarLastProbeAtMillis: _intOrNull(
        map,
        'dormantFarLastProbeAtMillis',
      ),
      dormantFarLastProbeResult: _stringOrNull(
        map,
        'dormantFarLastProbeResult',
      ),
      adaptiveProximityDisplacementMode: _stringOrNull(
        map,
        'adaptiveProximityDisplacementMode',
      ),
      adaptiveProximityDisplacementMeters: _doubleOrNull(
        map,
        'adaptiveProximityDisplacementMeters',
      ),
      adaptiveProximityLastEdgeDistanceMeters: _doubleOrNull(
        map,
        'adaptiveProximityLastEdgeDistanceMeters',
      ),
      proximityPulseStartedAtMillis: _intOrNull(
        map,
        'proximityPulseStartedAtMillis',
      ),
      proximityPulseIdleTicks: _intOrNull(map, 'proximityPulseIdleTicks'),
      proximityPulseRateMode: _stringOrNull(map, 'proximityPulseRateMode'),
      proximityPulsePurpose: _stringOrNull(map, 'proximityPulsePurpose'),
      proximityPulseSchedulingActive: _boolOrNull(
        map,
        'proximityPulseSchedulingActive',
      ),
      proximityPulseActiveHoursNow: _boolOrNull(
        map,
        'proximityPulseActiveHoursNow',
      ),
      proximityPulseCanRun: _boolOrNull(map, 'proximityPulseCanRun'),
      fusedLocationLastHealthyAtMillis: _intOrNull(
        map,
        'fusedLocationLastHealthyAtMillis',
      ),
      fusedLocationHealthyAgeMillis: _intOrNull(
        map,
        'fusedLocationHealthyAgeMillis',
      ),
      fusedLocationLastHealthySource: _stringOrNull(
        map,
        'fusedLocationLastHealthySource',
      ),
      fusedLocationRecoveryStartedAtMillis: _intOrNull(
        map,
        'fusedLocationRecoveryStartedAtMillis',
      ),
      fusedLocationLastRecoveryEndedAtMillis: _intOrNull(
        map,
        'fusedLocationLastRecoveryEndedAtMillis',
      ),
      fusedLocationLastRecoveryReason: _stringOrNull(
        map,
        'fusedLocationLastRecoveryReason',
      ),
      fusedLocationBalancedRefreshCount: _intOrNull(
        map,
        'fusedLocationBalancedRefreshCount',
      ),
      proximityEligible: _boolOrNull(map, 'proximityEligible'),
      passiveLocationEligible: _boolOrNull(map, 'passiveLocationEligible'),
      fusedBalancedDesired: _boolOrNull(map, 'fusedBalancedDesired'),
      fusedBalancedDesiredEpoch: _intOrNull(map, 'fusedBalancedDesiredEpoch'),
      fusedBalancedRequestInFlight: _boolOrNull(
        map,
        'fusedBalancedRequestInFlight',
      ),
      fusedBalancedConfirmed: _boolOrNull(map, 'fusedBalancedConfirmed'),
      fusedBalancedRemovalInFlight: _boolOrNull(
        map,
        'fusedBalancedRemovalInFlight',
      ),
      fusedBalancedRemovalConfirmed: _boolOrNull(
        map,
        'fusedBalancedRemovalConfirmed',
      ),
      fusedBalancedDesiredPriority: _stringOrNull(
        map,
        'fusedBalancedDesiredPriority',
      ),
      fusedBalancedConfirmedPriority: _stringOrNull(
        map,
        'fusedBalancedConfirmedPriority',
      ),
      fusedBalancedDesiredIntervalMillis: _intOrNull(
        map,
        'fusedBalancedDesiredIntervalMillis',
      ),
      fusedBalancedConfirmedIntervalMillis: _intOrNull(
        map,
        'fusedBalancedConfirmedIntervalMillis',
      ),
      fusedBalancedDesiredDisplacementMeters: _doubleOrNull(
        map,
        'fusedBalancedDesiredDisplacementMeters',
      ),
      fusedBalancedConfirmedDisplacementMeters: _doubleOrNull(
        map,
        'fusedBalancedConfirmedDisplacementMeters',
      ),
      fusedBalancedDesiredAdaptiveMode: _stringOrNull(
        map,
        'fusedBalancedDesiredAdaptiveMode',
      ),
      fusedBalancedConfirmedAdaptiveMode: _stringOrNull(
        map,
        'fusedBalancedConfirmedAdaptiveMode',
      ),
      fusedBalancedLastSuccessAtMillis: _intOrNull(
        map,
        'fusedBalancedLastSuccessAtMillis',
      ),
      fusedBalancedLastFailureAtMillis: _intOrNull(
        map,
        'fusedBalancedLastFailureAtMillis',
      ),
      fusedBalancedLastFailureReason: _stringOrNull(
        map,
        'fusedBalancedLastFailureReason',
      ),
      fusedPassiveDesired: _boolOrNull(map, 'fusedPassiveDesired'),
      fusedPassiveDesiredEpoch: _intOrNull(map, 'fusedPassiveDesiredEpoch'),
      fusedPassiveRequestInFlight: _boolOrNull(
        map,
        'fusedPassiveRequestInFlight',
      ),
      fusedPassiveConfirmed: _boolOrNull(map, 'fusedPassiveConfirmed'),
      fusedPassiveRemovalInFlight: _boolOrNull(
        map,
        'fusedPassiveRemovalInFlight',
      ),
      fusedPassiveRemovalConfirmed: _boolOrNull(
        map,
        'fusedPassiveRemovalConfirmed',
      ),
      fusedPassiveDesiredPriority: _stringOrNull(
        map,
        'fusedPassiveDesiredPriority',
      ),
      fusedPassiveConfirmedPriority: _stringOrNull(
        map,
        'fusedPassiveConfirmedPriority',
      ),
      fusedPassiveDesiredIntervalMillis: _intOrNull(
        map,
        'fusedPassiveDesiredIntervalMillis',
      ),
      fusedPassiveConfirmedIntervalMillis: _intOrNull(
        map,
        'fusedPassiveConfirmedIntervalMillis',
      ),
      fusedPassiveLastSuccessAtMillis: _intOrNull(
        map,
        'fusedPassiveLastSuccessAtMillis',
      ),
      fusedPassiveLastFailureAtMillis: _intOrNull(
        map,
        'fusedPassiveLastFailureAtMillis',
      ),
      fusedPassiveLastFailureReason: _stringOrNull(
        map,
        'fusedPassiveLastFailureReason',
      ),
      fusedRequestRemovalInFlightSinceMillis: _intOrNull(
        map,
        'fusedRequestRemovalInFlightSinceMillis',
      ),
      fusedRequestStaleCallbackCount: _intOrNull(
        map,
        'fusedRequestStaleCallbackCount',
      ),
      fusedRequestLastStaleCallbackReason: _stringOrNull(
        map,
        'fusedRequestLastStaleCallbackReason',
      ),
      fusedRequestIgnoredCallbackCount: _intOrNull(
        map,
        'fusedRequestIgnoredCallbackCount',
      ),
      fusedRequestLastIgnoredCallbackReason: _stringOrNull(
        map,
        'fusedRequestLastIgnoredCallbackReason',
      ),
      activityEligible: _boolOrNull(map, 'activityEligible'),
      recoveryEligible: _boolOrNull(map, 'recoveryEligible'),
      proximityPendingIntentExists: _boolOrNull(
        map,
        'proximityPendingIntentExists',
      ),
      passiveLocationPendingIntentExists: _boolOrNull(
        map,
        'passiveLocationPendingIntentExists',
      ),
      activityPendingIntentExists: _boolOrNull(
        map,
        'activityPendingIntentExists',
      ),
      activityTransitionPendingIntentExists: _boolOrNull(
        map,
        'activityTransitionPendingIntentExists',
      ),
      activityPeriodicPendingIntentExists: _boolOrNull(
        map,
        'activityPeriodicPendingIntentExists',
      ),
      activityControllerDesired: _boolOrNull(map, 'activityControllerDesired'),
      activityTransitionDesired: _boolOrNull(map, 'activityTransitionDesired'),
      activityPeriodicDesired: _boolOrNull(map, 'activityPeriodicDesired'),
      activityPeriodicMode: _stringOrNull(map, 'activityPeriodicMode'),
      activityPeriodicBackstopEnabled: _boolOrNull(
        map,
        'activityPeriodicBackstopEnabled',
      ),
      activityOperationEpoch: _intOrNull(map, 'activityOperationEpoch'),
      activityMonitoringSessionGeneration: _intOrNull(
        map,
        'activityMonitoringSessionGeneration',
      ),
      activityDesiredPeriodicIntervalMillis: _intOrNull(
        map,
        'activityDesiredPeriodicIntervalMillis',
      ),
      activityConfirmedPeriodicIntervalMillis: _intOrNull(
        map,
        'activityConfirmedPeriodicIntervalMillis',
      ),
      activityConfirmedPeriodicOwner: _stringOrNull(
        map,
        'activityConfirmedPeriodicOwner',
      ),
      activityTransitionRequested: _boolOrNull(
        map,
        'activityTransitionRequested',
      ),
      activityTransitionRequestInFlight: _boolOrNull(
        map,
        'activityTransitionRequestInFlight',
      ),
      activityTransitionConfirmed: _boolOrNull(
        map,
        'activityTransitionConfirmed',
      ),
      activityTransitionRemovalInFlight: _boolOrNull(
        map,
        'activityTransitionRemovalInFlight',
      ),
      activityTransitionRemovalConfirmed: _boolOrNull(
        map,
        'activityTransitionRemovalConfirmed',
      ),
      activityTransitionLastSuccessAtMillis: _intOrNull(
        map,
        'activityTransitionLastSuccessAtMillis',
      ),
      activityTransitionLastFailureAtMillis: _intOrNull(
        map,
        'activityTransitionLastFailureAtMillis',
      ),
      activityTransitionLastFailureReason: _stringOrNull(
        map,
        'activityTransitionLastFailureReason',
      ),
      activityPeriodicRequested: _boolOrNull(map, 'activityPeriodicRequested'),
      activityPeriodicRequestInFlight: _boolOrNull(
        map,
        'activityPeriodicRequestInFlight',
      ),
      activityPeriodicConfirmed: _boolOrNull(map, 'activityPeriodicConfirmed'),
      activityPeriodicRemovalInFlight: _boolOrNull(
        map,
        'activityPeriodicRemovalInFlight',
      ),
      activityPeriodicRemovalConfirmed: _boolOrNull(
        map,
        'activityPeriodicRemovalConfirmed',
      ),
      activityPeriodicRemovalRequired: _boolOrNull(
        map,
        'activityPeriodicRemovalRequired',
      ),
      activityBootstrapRequestedAtMillis: _intOrNull(
        map,
        'activityBootstrapRequestedAtMillis',
      ),
      activityBootstrapDeadlineMillis: _intOrNull(
        map,
        'activityBootstrapDeadlineMillis',
      ),
      activityBootstrapResultReceived: _boolOrNull(
        map,
        'activityBootstrapResultReceived',
      ),
      activityBootstrapCompleted: _boolOrNull(
        map,
        'activityBootstrapCompleted',
      ),
      activityBootstrapTimeoutPendingIntentExists: _boolOrNull(
        map,
        'activityBootstrapTimeoutPendingIntentExists',
      ),
      activityPeriodicLastSuccessAtMillis: _intOrNull(
        map,
        'activityPeriodicLastSuccessAtMillis',
      ),
      activityPeriodicLastFailureAtMillis: _intOrNull(
        map,
        'activityPeriodicLastFailureAtMillis',
      ),
      activityPeriodicLastFailureReason: _stringOrNull(
        map,
        'activityPeriodicLastFailureReason',
      ),
      activityRemovalInFlightSinceMillis: _intOrNull(
        map,
        'activityRemovalInFlightSinceMillis',
      ),
      activityRemovalOverdue: _boolOrNull(map, 'activityRemovalOverdue'),
      activityStaleOperationCallbackCount: _intOrNull(
        map,
        'activityStaleOperationCallbackCount',
      ),
      activityLastStaleOperationCallbackReason: _stringOrNull(
        map,
        'activityLastStaleOperationCallbackReason',
      ),
      activityIgnoredCallbackCount: _intOrNull(
        map,
        'activityIgnoredCallbackCount',
      ),
      activityLastIgnoredCallbackReason: _stringOrNull(
        map,
        'activityLastIgnoredCallbackReason',
      ),
      activityPeriodicReason: _stringOrNull(map, 'activityPeriodicReason'),
      activityPeriodicRequestedAtMillis: _intOrNull(
        map,
        'activityPeriodicRequestedAtMillis',
      ),
      activityPeriodicIntervalMillis: _intOrNull(
        map,
        'activityPeriodicIntervalMillis',
      ),
      lastActivityPeriodicResultAtMillis: _intOrNull(
        map,
        'lastActivityPeriodicResultAtMillis',
      ),
      activityStationarySource: _stringOrNull(map, 'activityStationarySource'),
      bootFollowUpPendingIntentExists: _boolOrNull(
        map,
        'bootFollowUpPendingIntentExists',
      ),
      recoveryAlarmPendingIntentExists: _boolOrNull(
        map,
        'recoveryAlarmPendingIntentExists',
      ),
      locationConfirmStartPendingIntentExists: _boolOrNull(
        map,
        'locationConfirmStartPendingIntentExists',
      ),
      locationConfirmWatchdogPendingIntentExists: _boolOrNull(
        map,
        'locationConfirmWatchdogPendingIntentExists',
      ),
      foregroundServiceRearmPendingIntentExists:
          _boolOrNull(map, 'foregroundServiceRearmPendingIntentExists') ??
          _boolOrNull(map, 'locationConfirmRearmPendingIntentExists'),
      foregroundQueueSize: _intOrNull(map, 'foregroundQueueSize'),
      foregroundQueueItems: _mapList(map, 'foregroundQueueItems'),
      backgroundQueueSize: _intOrNull(map, 'backgroundQueueSize'),
      proximityAlarmKind: _stringOrNull(map, 'proximityAlarmKind'),
      proximityAlarmPendingIntentExists: _boolOrNull(
        map,
        'proximityAlarmPendingIntentExists',
      ),
      proximityAlarmSchedule: _map(map, 'proximityAlarmSchedule'),
      dormantFarProbePendingIntentExists: _boolOrNull(
        map,
        'dormantFarProbePendingIntentExists',
      ),
      dormantFarProbeAlarmSchedule: _map(map, 'dormantFarProbeAlarmSchedule'),
      lastLocationWakeAtMillis: _intOrNull(map, 'lastLocationWakeAtMillis'),
      lastLocationWakeSource: _stringOrNull(map, 'lastLocationWakeSource'),
      lastLocationWakeProvider: _stringOrNull(map, 'lastLocationWakeProvider'),
      lastLocationWakeAccuracyMeters: _doubleOrNull(
        map,
        'lastLocationWakeAccuracyMeters',
      ),
      lastLocationWakeAgeMillis: _intOrNull(map, 'lastLocationWakeAgeMillis'),
      lastLocationWakeNearestFenceId: _stringOrNull(
        map,
        'lastLocationWakeNearestFenceId',
      ),
      lastLocationWakeEdgeDistanceMeters: _doubleOrNull(
        map,
        'lastLocationWakeEdgeDistanceMeters',
      ),
      lastLocationWakeWithinProximity: _boolOrNull(
        map,
        'lastLocationWakeWithinProximity',
      ),
      lastConfirmQueueAtMillis: _intOrNull(map, 'lastConfirmQueueAtMillis'),
      lastConfirmQueueRequestId: _intOrNull(map, 'lastConfirmQueueRequestId'),
      lastConfirmQueueFenceId: _stringOrNull(map, 'lastConfirmQueueFenceId'),
      lastConfirmQueueIsProximity: _boolOrNull(
        map,
        'lastConfirmQueueIsProximity',
      ),
      lastConfirmQueueSource: _stringOrNull(map, 'lastConfirmQueueSource'),
      lastConfirmQueueAgeMillis: _intOrNull(map, 'lastConfirmQueueAgeMillis'),
      lastConfirmRequestAtMillis: _intOrNull(map, 'lastConfirmRequestAtMillis'),
      lastConfirmSource: _stringOrNull(map, 'lastConfirmSource'),
      lastConfirmPriority: _stringOrNull(map, 'lastConfirmPriority'),
      lastConfirmTimeoutMillis: _intOrNull(map, 'lastConfirmTimeoutMillis'),
      lastConfirmResult: _stringOrNull(map, 'lastConfirmResult'),
      lastConfirmElapsedMillis: _intOrNull(map, 'lastConfirmElapsedMillis'),
      lastConfirmLocationProvider: _stringOrNull(
        map,
        'lastConfirmLocationProvider',
      ),
      lastConfirmLocationAccuracyMeters: _doubleOrNull(
        map,
        'lastConfirmLocationAccuracyMeters',
      ),
      lastConfirmLocationAgeMillis: _intOrNull(
        map,
        'lastConfirmLocationAgeMillis',
      ),
      lastConfirmFailureMessage: _stringOrNull(
        map,
        'lastConfirmFailureMessage',
      ),
      lastNativeExitConfirmTimingMode: _stringOrNull(
        map,
        'lastNativeExitConfirmTimingMode',
      ),
      lastNativeExitConfirmTimingReason: _stringOrNull(
        map,
        'lastNativeExitConfirmTimingReason',
      ),
      lastNativeExitConfirmTimingAtMillis: _intOrNull(
        map,
        'lastNativeExitConfirmTimingAtMillis',
      ),
      lastNativeEnterConfirmTimingMode: _stringOrNull(
        map,
        'lastNativeEnterConfirmTimingMode',
      ),
      lastNativeEnterConfirmTimingReason: _stringOrNull(
        map,
        'lastNativeEnterConfirmTimingReason',
      ),
      lastNativeEnterConfirmTimingAtMillis: _intOrNull(
        map,
        'lastNativeEnterConfirmTimingAtMillis',
      ),
      lastBoundaryDecisionAtMillis: _intOrNull(
        map,
        'lastBoundaryDecisionAtMillis',
      ),
      lastBoundaryDecision: _stringOrNull(map, 'lastBoundaryDecision'),
      lastBoundaryDecisionFenceId: _stringOrNull(
        map,
        'lastBoundaryDecisionFenceId',
      ),
      lastBoundaryDecisionSource: _stringOrNull(
        map,
        'lastBoundaryDecisionSource',
      ),
      lastBoundaryDistanceMeters: _doubleOrNull(
        map,
        'lastBoundaryDistanceMeters',
      ),
      lastBoundaryRadiusMeters: _doubleOrNull(map, 'lastBoundaryRadiusMeters'),
      lastBoundaryEdgeDistanceMeters: _doubleOrNull(
        map,
        'lastBoundaryEdgeDistanceMeters',
      ),
      lastBoundaryAccuracyMeters: _doubleOrNull(
        map,
        'lastBoundaryAccuracyMeters',
      ),
      lastBoundaryMock: _boolOrNull(map, 'lastBoundaryMock'),
      lastBoundaryQueuedEvent: _stringOrNull(map, 'lastBoundaryQueuedEvent'),
      lastMockLocationDecisionAtMillis: _intOrNull(
        map,
        'lastMockLocationDecisionAtMillis',
      ),
      lastMockLocationPolicy: _mockLocationPolicy(
        map,
        'lastMockLocationPolicy',
      ),
      lastMockLocationAction: _stringOrNull(map, 'lastMockLocationAction'),
      lastMockLocationSource: _stringOrNull(map, 'lastMockLocationSource'),
      lastMockLocationProvider: _stringOrNull(map, 'lastMockLocationProvider'),
      lastMockLocationAccuracyMeters: _doubleOrNull(
        map,
        'lastMockLocationAccuracyMeters',
      ),
      lastMockLocationAgeMillis: _intOrNull(map, 'lastMockLocationAgeMillis'),
      lastMockLocationSuppressed: _boolOrNull(
        map,
        'lastMockLocationSuppressed',
      ),
      lastSmartCallbackAtMillis: _intOrNull(map, 'lastSmartCallbackAtMillis'),
      lastSmartCallbackEvent: _stringOrNull(map, 'lastSmartCallbackEvent'),
      lastSmartCallbackFenceId: _stringOrNull(map, 'lastSmartCallbackFenceId'),
      lastSmartCallbackSource: _stringOrNull(map, 'lastSmartCallbackSource'),
      lastSmartCallbackResult: _stringOrNull(map, 'lastSmartCallbackResult'),
      lastSmartCallbackEnqueuedAtMillis: _intOrNull(
        map,
        'lastSmartCallbackEnqueuedAtMillis',
      ),
      lastSmartCallbackEventAtMillis: _intOrNull(
        map,
        'lastSmartCallbackEventAtMillis',
      ),
      lastSmartCallbackTimestampSource: _stringOrNull(
        map,
        'lastSmartCallbackTimestampSource',
      ),
      lastSmartCallbackDeliveryPath: _stringOrNull(
        map,
        'lastSmartCallbackDeliveryPath',
      ),
      lastSmartCallbackTriggerToDeliveryLatencyMillis: _intOrNull(
        map,
        'lastSmartCallbackTriggerToDeliveryLatencyMillis',
      ),
      lastSmartCallbackDeviceIdleModeAtDelivery: _boolOrNull(
        map,
        'lastSmartCallbackDeviceIdleModeAtDelivery',
      ),
      lastCallbackDispatchAtMillis: _intOrNull(
        map,
        'lastCallbackDispatchAtMillis',
      ),
      lastCallbackDispatchEvent: _stringOrNull(
        map,
        'lastCallbackDispatchEvent',
      ),
      lastCallbackDispatchFenceId: _stringOrNull(
        map,
        'lastCallbackDispatchFenceId',
      ),
      lastCallbackDispatchResult: _stringOrNull(
        map,
        'lastCallbackDispatchResult',
      ),
      lastCallbackDispatchCallbackHandle: _intOrNull(
        map,
        'lastCallbackDispatchCallbackHandle',
      ),
      lastCallbackDispatchRetryOnFailure: _boolOrNull(
        map,
        'lastCallbackDispatchRetryOnFailure',
      ),
      lastCallbackDispatchEventAtMillis: _intOrNull(
        map,
        'lastCallbackDispatchEventAtMillis',
      ),
      lastCallbackDispatchTimestampSource: _stringOrNull(
        map,
        'lastCallbackDispatchTimestampSource',
      ),
      lastCallbackDispatchTimeReasonCode: _stringOrNull(
        map,
        'lastCallbackDispatchTimeReasonCode',
      ),
      lastCallbackDispatchTimeTrusted: _boolOrNull(
        map,
        'lastCallbackDispatchTimeTrusted',
      ),
      lastCallbackDispatchError: _stringOrNull(
        map,
        'lastCallbackDispatchError',
      ),
      lastBootRecoverySource: _stringOrNull(map, 'lastBootRecoverySource'),
      lastBootRecoveryAction: _stringOrNull(map, 'lastBootRecoveryAction'),
      lastBootRecoveryStartedAtMillis: _intOrNull(
        map,
        'lastBootRecoveryStartedAtMillis',
      ),
      lastBootRecoveryFinishedAtMillis: _intOrNull(
        map,
        'lastBootRecoveryFinishedAtMillis',
      ),
      lastBootRecoveryResult: _stringOrNull(map, 'lastBootRecoveryResult'),
      lastBootRecoveryNativeFenceCount: _intOrNull(
        map,
        'lastBootRecoveryNativeFenceCount',
      ),
      lastBootRecoverySmartFenceCount: _intOrNull(
        map,
        'lastBootRecoverySmartFenceCount',
      ),
      lastBootRecoveryElapsedMillis: _intOrNull(
        map,
        'lastBootRecoveryElapsedMillis',
      ),
      lastBootRecoveryFailureMessage: _stringOrNull(
        map,
        'lastBootRecoveryFailureMessage',
      ),
      lastBootFollowUpScheduledAtMillis: _intOrNull(
        map,
        'lastBootFollowUpScheduledAtMillis',
      ),
      lastBootFollowUpTriggerAtMillis: _intOrNull(
        map,
        'lastBootFollowUpTriggerAtMillis',
      ),
      lastBootFollowUpAction: _stringOrNull(map, 'lastBootFollowUpAction'),
      lastBootFollowUpScheduleResult: _stringOrNull(
        map,
        'lastBootFollowUpScheduleResult',
      ),
      lastBootFollowUpFailureMessage: _stringOrNull(
        map,
        'lastBootFollowUpFailureMessage',
      ),
      lastBroadcastDeadlineFinishedAtMillis: _intOrNull(
        map,
        'lastBroadcastDeadlineFinishedAtMillis',
      ),
      lastBroadcastDeadlineReason: _stringOrNull(
        map,
        'lastBroadcastDeadlineReason',
      ),
      lastBroadcastDeadlineTag: _stringOrNull(map, 'lastBroadcastDeadlineTag'),
      lastRecoveryMainThreadTimeoutAtMillis: _intOrNull(
        map,
        'lastRecoveryMainThreadTimeoutAtMillis',
      ),
      lastRecoveryMainThreadTimeoutSource: _stringOrNull(
        map,
        'lastRecoveryMainThreadTimeoutSource',
      ),
      lastRecoveryMainThreadTimeoutReason: _stringOrNull(
        map,
        'lastRecoveryMainThreadTimeoutReason',
      ),
    );
  }

  factory SmartGeofenceStatus.unavailable({
    required ng.NativeGeofenceStatus nativeStatus,
    String? reason,
  }) => SmartGeofenceStatus(
    nativeStatus: nativeStatus,
    smartLayerSupported: true,
    smartStatusState: SGSmartStatusState.unavailable,
    smartStatusIssues: <String>[
      reason ?? 'Android smart status was unavailable.',
    ],
  );

  Map<String, Object?> toJson() => {
    'native': nativeStatus.toJson(),
    'smart': _jsonMap(rawStatus),
    'smartStatusState': smartStatusState.name,
    'smartStatusSchemaVersion': smartStatusSchemaVersion,
    'smartStatusIssues': smartStatusIssues,
    'config': config.toMap(),
    'radiusNormalizations': radiusNormalizations.map(
      (id, normalization) => MapEntry(id, normalization.toJson()),
    ),
  };

  List<String> get nativeWarnings {
    final items = <String>[];
    if ((locationServicesEnabled ?? nativeStatus.locationServicesEnabled) ==
        false) {
      items.add('Device location services are disabled.');
    }
    if ((locationPermissionGranted ?? nativeStatus.locationPermissionGranted) ==
        false) {
      items.add('Location permission is missing.');
    }
    if ((backgroundLocationPermissionGranted ??
            nativeStatus.backgroundLocationPermissionGranted) ==
        false) {
      items.add(
        smartLayerSupported
            ? 'Smart layer is native-only because background location permission is missing.'
            : 'Background location permission is missing.',
      );
    }
    if (nativeStatus.preciseLocationPermissionGranted == false) {
      items.add('Precise location is unavailable.');
    }
    if (nativeStatus.monitoringAvailable == false) {
      items.add('Native region monitoring is unavailable.');
    }
    switch (nativeStatus.backgroundRefreshStatus) {
      case ng.NativeGeofenceBackgroundRefreshStatus.denied:
        items.add(
          'Background app refresh is denied; native background delivery may be unavailable.',
        );
      case ng.NativeGeofenceBackgroundRefreshStatus.restricted:
        items.add(
          'Background app refresh is restricted; native background delivery may be unavailable.',
        );
      case ng.NativeGeofenceBackgroundRefreshStatus.available:
      case ng.NativeGeofenceBackgroundRefreshStatus.unknown:
      case null:
        break;
    }
    switch (nativeStatus.registrationHealth) {
      case ng.NativeGeofenceRegistrationHealth.degraded:
        items.add('native_geofence reports degraded registration health.');
      case ng.NativeGeofenceRegistrationHealth.unavailable:
        items.add('native_geofence registration health is unavailable.');
      case ng.NativeGeofenceRegistrationHealth.unknown:
        if (nativeStatus.persistedGeofenceCount > 0) {
          items.add('native_geofence registration health is unknown.');
        }
      case ng.NativeGeofenceRegistrationHealth.noRegistrations:
      case ng.NativeGeofenceRegistrationHealth.healthy:
        break;
    }
    switch (nativeStatus.callbackRefreshState) {
      case ng.NativeGeofenceCallbackRefreshState.refreshRequired:
        items.add(
          'Stored native geofence callback handles need refresh. Run ensureSynchronized with live callbacks.',
        );
      case ng.NativeGeofenceCallbackRefreshState.unknown:
        if (nativeStatus.persistedGeofenceCount > 0) {
          items.add('Native geofence callback freshness is unknown.');
        }
      case ng.NativeGeofenceCallbackRefreshState.current:
      case ng.NativeGeofenceCallbackRefreshState.notApplicable:
        break;
    }
    return List<String>.unmodifiable(items);
  }

  List<String> get smartLayerWarnings {
    if (!smartLayerSupported) return const <String>[];
    final items = <String>[];

    if (monitoringTerminallyStopped == true) {
      items.add(
        'The Android monitoring session is permanently stopped'
        '${monitoringStopReason == null ? '.' : ' ($monitoringStopReason).'} '
        'Register or synchronize geofences to begin a new session.',
      );
    }
    final nativeCount = nativeStatus.persistedGeofenceCount;
    final smartCount = mirroredFenceCount ?? mirroredFenceIds.toSet().length;
    if (nativeCount != smartCount) {
      items.add(
        'native_geofence and smart_geofence registration counts differ '
        '(native=$nativeCount, smart=$smartCount). Run getMonitoringState for '
        'an exact app-owned ID comparison.',
      );
    }
    if (locationPermissionGranted == true &&
        backgroundLocationPermissionGranted != false &&
        fineLocationGranted == false) {
      items.add(
        'Only coarse location is granted; smart fused fixes are rejected by the event accuracy filter.',
      );
    }
    if (smartLayerMode == 'limited') {
      items.add(
        'Smart layer is limited because foreground confirm is unavailable.',
      );
    }
    if (config.foregroundNotification.showWhileMonitoring &&
        notificationPermissionGranted == false) {
      items.add(
        'Idle foreground notification is enabled, but notification permission is missing.',
      );
    }
    if (fusedLocationUpdateReceiverDeclared == false) {
      items.add(
        'FusedLocationUpdateReceiver is not declared in the merged manifest.',
      );
    }
    if (activityReceiverDeclared == false) {
      items.add('ActivityReceiver is not declared in the merged manifest.');
    }
    if (bootReceiverDeclared == false) {
      items.add(
        'BootReceiver is not declared; smart layers will not re-arm after reboot.',
      );
    }
    if (exactAlarmPermissionStateReceiverDeclared == false) {
      items.add(
        'ExactAlarmPermissionReceiver is not declared; smart layers will not re-arm immediately after exact-alarm access is granted.',
      );
    }
    if (exactAlarmStrictStartupBlocked == true) {
      items.add(
        'Strict exact-alarm mode is enabled, but exact-alarm special access is not granted.',
      );
    }
    if (exactAlarmPermissionRequired == true &&
        exactAlarmPermissionDeclared == false) {
      items.add(
        'SCHEDULE_EXACT_ALARM is not declared in the merged manifest; exact-only smart alarms and the Alarms & reminders prompt are unavailable.',
      );
    }
    if (bootFollowUpReceiverDeclared == false) {
      items.add(
        'BootRecoveryReceiver is not declared; delayed boot recovery will not run.',
      );
    }
    if (_hasFailure(lastBootRecoveryFailureMessage)) {
      items.add('Last boot recovery failed: $lastBootRecoveryFailureMessage.');
    }
    if (_hasFailure(lastBootFollowUpFailureMessage)) {
      items.add(
        'Last boot follow-up scheduling failed: $lastBootFollowUpFailureMessage.',
      );
    }
    if (lastCallbackDispatchResult != null &&
        lastCallbackDispatchResult != 'dispatched_ok') {
      final fence = lastCallbackDispatchFenceId == null
          ? ''
          : ' for $lastCallbackDispatchFenceId';
      items.add(
        'Last smart_geofence Dart callback dispatch$fence ended with '
        '$lastCallbackDispatchResult.',
      );
    }
    if (config.escalation.enabled) {
      if (foregroundServicePermissionGranted == false) {
        items.add(
          'One-shot Fused Location confirm requires FOREGROUND_SERVICE in the merged manifest.',
        );
      }
      if (foregroundServiceLocationPermissionGranted == false) {
        items.add(
          'One-shot Fused Location confirm requires FOREGROUND_SERVICE_LOCATION on Android 14+.',
        );
      }
      if (locationConfirmReceiverDeclared == false) {
        items.add('LocationConfirmAlarmReceiver is not declared.');
      }
      if (nativeExitFallbackReceiverDeclared == false) {
        items.add('NativeExitFallbackReceiver is not declared.');
      }
      if (nativeEnterFallbackReceiverDeclared == false) {
        items.add('NativeEnterFallbackReceiver is not declared.');
      }
      if ((nativeExitFallbackPendingCount ?? 0) > 0 &&
          nativeExitFallbackAlarmPendingIntentExists == false) {
        items.add(
          'Native EXIT fallback is pending, but no fallback alarm is armed.',
        );
      }
      if ((nativeEnterFallbackPendingCount ?? 0) > 0 &&
          nativeEnterFallbackAlarmPendingIntentExists == false) {
        items.add(
          'Native ENTER fallback is pending, but no fallback alarm is armed.',
        );
      }
      if (foregroundServiceLaunchReceiverDeclared == false) {
        items.add('ForegroundServiceLaunchReceiver is not declared.');
      }
      if (exactAlarmPermissionGranted == false) {
        items.add(
          'Exact alarms are unavailable; foreground-service launch failures cannot be rearmed.',
        );
      }
      if (locationConfirmServiceDeclared == false) {
        items.add('LocationConfirmService is not declared.');
      }
      if (locationConfirmServiceHasLocationType == false) {
        items.add(
          'LocationConfirmService is missing foregroundServiceType="location".',
        );
      }
      if ((locationConfirmQueueSize ?? 0) > 0 &&
          locationConfirmCanRun == false) {
        items.add(
          'Foreground Fused Location confirm requests are queued but the foreground confirm service cannot run.',
        );
      }
      if ((locationConfirmQueueParkedSize ?? 0) > 0 &&
          locationDisabledRecoveryPendingIntentExists == false) {
        items.add(
          'Location-disabled confirm work is parked, but no durable recovery alarm is armed.',
        );
      }
      if (foregroundStartQueuedServices.isNotEmpty &&
          foregroundStartCoordinatorWindowClosed == true &&
          foregroundStartBatchPendingIntentExists == false &&
          locationConfirmServiceForegroundReady != true) {
        items.add(
          'Foreground service starts are queued, but no batch alarm is armed.',
        );
      }
      if (locationConfirmServiceRunning == true &&
          locationConfirmServiceForegroundReady == false) {
        items.add(
          'LocationConfirmService is running but has not reached foreground-ready state.',
        );
      }
      if (_hasFailure(locationConfirmLastLaunchFailureReason)) {
        items.add(
          'Last LocationConfirmService launch failure: '
          '$locationConfirmLastLaunchFailureReason.',
        );
      }
    }
    if (recoveryAlarmReceiverDeclared == false) {
      items.add('RecoveryAlarmReceiver is not declared.');
    }
    if (exactAlarmPermissionGranted == false &&
        config.recovery.alarmPolicy == SGAlarmSchedulePolicy.exactOnly) {
      items.add(
        'Scheduled recovery cannot run because exact alarms are not permitted.',
      );
    } else if (recoveryEligible == true &&
        recoveryAlarmPendingIntentExists == false) {
      items.add(
        'Registered fences exist, but no scheduled recovery alarm is armed.',
      );
    }
    if (config.escalation.passive.enabled && passiveLocationEligible == false) {
      items.add('Passive location is enabled but is not currently eligible.');
    }
    if (fusedBalancedDesired == true) {
      if (fusedBalancedConfirmed != true ||
          proximityPendingIntentExists != true) {
        items.add(
          'Balanced fused monitoring is desired, but its request is missing or unconfirmed.',
        );
      }
      if (fusedBalancedConfirmed == true &&
          (fusedBalancedDesiredPriority != fusedBalancedConfirmedPriority ||
              fusedBalancedDesiredIntervalMillis !=
                  fusedBalancedConfirmedIntervalMillis ||
              fusedBalancedDesiredDisplacementMeters !=
                  fusedBalancedConfirmedDisplacementMeters ||
              fusedBalancedDesiredAdaptiveMode !=
                  fusedBalancedConfirmedAdaptiveMode)) {
        items.add(
          'Balanced fused monitoring has not confirmed the currently desired request shape.',
        );
      }
    } else if (fusedBalancedDesired == false &&
        (fusedBalancedConfirmed == true ||
            fusedBalancedRequestInFlight == true ||
            proximityPendingIntentExists == true) &&
        !(dormantFarActive == true &&
            (fusedBalancedRequestInFlight == true ||
                fusedBalancedRemovalInFlight == true))) {
      items.add(
        'Balanced fused monitoring is not desired, but request state remains active.',
      );
    }
    if (fusedPassiveDesired == true &&
        (fusedPassiveConfirmed != true ||
            passiveLocationPendingIntentExists != true)) {
      items.add(
        'Passive fused monitoring is desired, but its request is missing or unconfirmed.',
      );
    } else if (fusedPassiveDesired == false &&
        (fusedPassiveConfirmed == true ||
            fusedPassiveRequestInFlight == true ||
            passiveLocationPendingIntentExists == true)) {
      items.add(
        'Passive fused monitoring is not desired, but request state remains active.',
      );
    }
    if (config.proximityPulse.enabled &&
        proximityAlarmReceiverDeclared == false) {
      items.add('ProximityAlarmReceiver is not declared.');
    }
    if (proximityPulseSchedulingActive == true &&
        proximityAlarmPendingIntentExists == false) {
      items.add(
        'ProximityPulse scheduling is active, but no proximity alarm is armed.',
      );
    }
    if (dormantFarActive == true &&
        dormantFarProbePendingIntentExists == false) {
      items.add(
        'Dormant-far mode is active, but no standby probe alarm is armed.',
      );
    }
    if (dormantFarActive == true && dormantFarProbeReceiverDeclared == false) {
      items.add('DormantFarProbeReceiver is not declared.');
    }
    if (config.proximityPulse.enabled ||
        config.escalation.proximity.adaptiveDisplacement.enabled) {
      if (activityPermissionGranted == false) {
        items.add('Activity permission is missing; Activity is disabled.');
      }
    }
    if (activityEligible == true && activityControllerDesired == true) {
      if (activityPeriodicBackstopEnabled != null &&
          activityPeriodicBackstopEnabled !=
              config.activity.periodicBackstopEnabled) {
        items.add(
          'Activity periodic-backstop lifecycle state does not match the applied configuration.',
        );
      }
      if (activityTransitionConfirmed != true ||
          activityTransitionPendingIntentExists != true) {
        items.add(
          'Activity monitoring is eligible, but the transition registration is missing or unconfirmed.',
        );
      }
      if (config.activity.periodicBackstopEnabled) {
        if (activityPeriodicMode != 'persistent_backstop') {
          items.add(
            'The persistent Activity backstop is enabled, but periodic mode is $activityPeriodicMode.',
          );
        }
        if (activityPeriodicConfirmed != true ||
            activityPeriodicPendingIntentExists != true) {
          items.add(
            'The persistent Activity backstop is enabled, but its periodic registration is missing or unconfirmed.',
          );
        }
        if (activityPeriodicConfirmed == true &&
            activityConfirmedPeriodicOwner != 'monitoring_baseline') {
          items.add(
            'Periodic Activity registration is owned by $activityConfirmedPeriodicOwner instead of monitoring_baseline.',
          );
        }
        if (activityDesiredPeriodicIntervalMillis != null &&
            activityDesiredPeriodicIntervalMillis !=
                activityConfirmedPeriodicIntervalMillis) {
          items.add(
            'Periodic Activity interval is not confirmed at the desired value '
            '(desired=${activityDesiredPeriodicIntervalMillis}ms, '
            'confirmed=${activityConfirmedPeriodicIntervalMillis}ms).',
          );
        }
      } else if (activityBootstrapCompleted != true) {
        items.add(
          'Activity transition monitoring is registered, but the bounded startup bootstrap has not completed.',
        );
      } else if (activityPeriodicMode != 'none' ||
          activityPeriodicDesired == true ||
          activityPeriodicConfirmed == true ||
          activityPeriodicRequestInFlight == true ||
          activityPeriodicRemovalInFlight == true ||
          activityPeriodicRemovalRequired == true ||
          activityPeriodicPendingIntentExists == true ||
          activityBootstrapTimeoutPendingIntentExists == true ||
          activityPeriodicRemovalConfirmed != true) {
        items.add(
          'Activity bootstrap completed, but periodic Activity work has not reached transition-only steady state.',
        );
      }
    }
    if (activityControllerDesired == false &&
        (activityTransitionConfirmed == true ||
            activityTransitionRequestInFlight == true ||
            activityTransitionRemovalInFlight == true ||
            activityTransitionPendingIntentExists == true ||
            activityPeriodicConfirmed == true ||
            activityPeriodicRequestInFlight == true ||
            activityPeriodicRemovalInFlight == true ||
            activityPeriodicPendingIntentExists == true ||
            ((activityOperationEpoch ?? 0) > 0 &&
                (activityTransitionRemovalConfirmed == false ||
                    activityPeriodicRemovalConfirmed == false)))) {
      items.add(
        'Activity monitoring is not desired, but registration or request state is still active.',
      );
    }
    if (activityControllerDesired == true && activityRemovalOverdue == true) {
      items.add(
        'Activity monitoring is desired, but an old registration removal has remained unresolved for too long.',
      );
    }
    return List<String>.unmodifiable(items);
  }

  List<String> get warnings => List<String>.unmodifiable(<String>{
    ...nativeWarnings,
    ...smartLayerWarnings,
  });

  @override
  String toString() {
    return 'SmartGeofenceStatus('
        'smartLayerSupported: $smartLayerSupported, '
        'smartStatusState: $smartStatusState, '
        'smartStatusSchemaVersion: $smartStatusSchemaVersion, '
        'smartStatusIssues: $smartStatusIssues, '
        'smartLayerMode: $smartLayerMode, '
        'smartLayerModeReason: $smartLayerModeReason, '
        'config: $config, '
        'diagnosticEventJournalSize: ${diagnosticEventJournal.length}, '
        'diagnosticCounters: $diagnosticCounters, '
        'mirroredFenceIds: [${mirroredFenceIds.join(',')}], '
        'mirroredFenceCount: $mirroredFenceCount, '
        'radiusNormalizations: $radiusNormalizations, '
        'locationPermissionGranted: $locationPermissionGranted, '
        'fineLocationGranted: $fineLocationGranted, '
        'backgroundLocationPermissionGranted: $backgroundLocationPermissionGranted, '
        'foregroundNotificationSticky: $foregroundNotificationSticky, '
        'foregroundNotificationTapAction: $foregroundNotificationTapAction, '
        'foregroundNotificationShowWhileMonitoring: $foregroundNotificationShowWhileMonitoring, '
        'powerSaveMode: $powerSaveMode, '
        'deviceIdleMode: $deviceIdleMode, '
        'batteryOptimizationsIgnored: $batteryOptimizationsIgnored, '
        'batteryLevelPercent: $batteryLevelPercent, '
        'batteryCharging: $batteryCharging, '
        'notificationPermissionGranted: $notificationPermissionGranted, '
        'exactAlarmPermissionMode: $exactAlarmPermissionMode, '
        'exactAlarmPermissionDeclared: $exactAlarmPermissionDeclared, '
        'exactAlarmPermissionStatus: $exactAlarmPermissionStatus, '
        'exactAlarmPermissionGranted: $exactAlarmPermissionGranted, '
        'exactAlarmStrictStartupBlocked: $exactAlarmStrictStartupBlocked, '
        'nativeConfirmDelayRequiresExactAlarm: $nativeConfirmDelayRequiresExactAlarm, '
        'nativeConfirmDelayExactSchedulingAvailable: $nativeConfirmDelayExactSchedulingAvailable, '
        'nativeEnterConfirmImmediateTimingBypassPossible: $nativeEnterConfirmImmediateTimingBypassPossible, '
        'nativeExitConfirmImmediateTimingBypassPossible: $nativeExitConfirmImmediateTimingBypassPossible, '
        'transitionValidationEnterBlocksEarlyConfirmationAcquisition: $transitionValidationEnterBlocksEarlyConfirmationAcquisition, '
        'transitionValidationExitBlocksEarlyConfirmationAcquisition: $transitionValidationExitBlocksEarlyConfirmationAcquisition, '
        'transitionValidationEnterBlocksRawNativeFallback: $transitionValidationEnterBlocksRawNativeFallback, '
        'transitionValidationExitBlocksRawNativeFallback: $transitionValidationExitBlocksRawNativeFallback, '
        'nativeConfirmDelayMillis: $nativeConfirmDelayMillis, '
        'nativeConfirmMaxAttempts: $nativeConfirmMaxAttempts, '
        'transitionValidationEnabled: $transitionValidationEnabled, '
        'transitionValidationEnterEnabled: $transitionValidationEnterEnabled, '
        'transitionValidationExitEnabled: $transitionValidationExitEnabled, '
        'transitionValidationMinimumDelayMillis: $transitionValidationMinimumDelayMillis, '
        'nativeExitFallbackReceiverDeclared: $nativeExitFallbackReceiverDeclared, '
        'nativeExitFallbackPendingCount: $nativeExitFallbackPendingCount, '
        'nativeExitFallbackPendingFenceIds: $nativeExitFallbackPendingFenceIds, '
        'nativeExitFallbackAlarmPendingIntentExists: $nativeExitFallbackAlarmPendingIntentExists, '
        'nativeExitFallbackPendingDetails: $nativeExitFallbackPendingDetails, '
        'nativeEnterFallbackReceiverDeclared: $nativeEnterFallbackReceiverDeclared, '
        'nativeEnterFallbackPendingCount: $nativeEnterFallbackPendingCount, '
        'nativeEnterFallbackPendingFenceIds: $nativeEnterFallbackPendingFenceIds, '
        'nativeEnterFallbackAlarmPendingIntentExists: $nativeEnterFallbackAlarmPendingIntentExists, '
        'nativeEnterFallbackPendingDetails: $nativeEnterFallbackPendingDetails, '
        'fenceStates: $fenceStates, '
        'locationServicesEnabled: $locationServicesEnabled, '
        'monitoringTerminallyStopped: $monitoringTerminallyStopped, '
        'monitoringStopPhase: $monitoringStopPhase, '
        'monitoringStopReason: $monitoringStopReason, '
        'monitoringStoppedAtMillis: $monitoringStoppedAtMillis, '
        'monitoringStopCallbackPending: $monitoringStopCallbackPending, '
        'monitoringNativeCleanupComplete: $monitoringNativeCleanupComplete, '
        'monitoringNativeCleanupPendingCount: $monitoringNativeCleanupPendingCount, '
        'proximityEligible: $proximityEligible, '
        'passiveLocationEligible: $passiveLocationEligible, '
        'fusedBalancedDesired: $fusedBalancedDesired, '
        'fusedBalancedRequestInFlight: $fusedBalancedRequestInFlight, '
        'fusedBalancedConfirmed: $fusedBalancedConfirmed, '
        'fusedBalancedRemovalInFlight: $fusedBalancedRemovalInFlight, '
        'fusedBalancedDesiredAdaptiveMode: $fusedBalancedDesiredAdaptiveMode, '
        'fusedBalancedConfirmedAdaptiveMode: $fusedBalancedConfirmedAdaptiveMode, '
        'fusedPassiveDesired: $fusedPassiveDesired, '
        'fusedPassiveRequestInFlight: $fusedPassiveRequestInFlight, '
        'fusedPassiveConfirmed: $fusedPassiveConfirmed, '
        'fusedPassiveRemovalInFlight: $fusedPassiveRemovalInFlight, '
        'fusedRequestStaleCallbackCount: $fusedRequestStaleCallbackCount, '
        'fusedRequestLastStaleCallbackReason: $fusedRequestLastStaleCallbackReason, '
        'fusedRequestIgnoredCallbackCount: $fusedRequestIgnoredCallbackCount, '
        'fusedRequestLastIgnoredCallbackReason: $fusedRequestLastIgnoredCallbackReason, '
        'locationConfirmCanRun: $locationConfirmCanRun, '
        'foregroundStartQueuedServices: $foregroundStartQueuedServices, '
        'foregroundStartBatchPendingIntentExists: $foregroundStartBatchPendingIntentExists, '
        'locationConfirmServiceForegroundReady: $locationConfirmServiceForegroundReady, '
        'locationConfirmLastLaunchFailureReason: $locationConfirmLastLaunchFailureReason, '
        'locationConfirmQueueSize: $locationConfirmQueueSize, '
        'locationConfirmQueueTotalSize: $locationConfirmQueueTotalSize, '
        'locationConfirmQueueParkedSize: $locationConfirmQueueParkedSize, '
        'locationConfirmQueueItems: $locationConfirmQueueItems, '
        'locationDisabledRecoveryPendingIntentExists: $locationDisabledRecoveryPendingIntentExists, '
        'locationDisabledRecoveryAlarmSchedule: $locationDisabledRecoveryAlarmSchedule, '
        'foregroundQueueSize: $foregroundQueueSize, '
        'foregroundQueueItems: $foregroundQueueItems, '
        'backgroundQueueSize: $backgroundQueueSize, '
        'foregroundServiceRearmPendingIntentExists: $foregroundServiceRearmPendingIntentExists, '
        'adaptiveProximityDisplacementMode: $adaptiveProximityDisplacementMode, '
        'adaptiveProximityDisplacementMeters: $adaptiveProximityDisplacementMeters, '
        'adaptiveProximityLastEdgeDistanceMeters: $adaptiveProximityLastEdgeDistanceMeters, '
        'dormantFarActive: $dormantFarActive, '
        'dormantFarReason: $dormantFarReason, '
        'dormantFarLastEdgeDistanceMeters: $dormantFarLastEdgeDistanceMeters, '
        'dormantFarNearestFenceId: $dormantFarNearestFenceId, '
        'dormantFarLastAcceptedFixSource: $dormantFarLastAcceptedFixSource, '
        'dormantFarNextProbeAtMillis: $dormantFarNextProbeAtMillis, '
        'dormantFarLastProbeResult: $dormantFarLastProbeResult, '
        'proximityPulseCanRun: $proximityPulseCanRun, '
        'proximityPulseRateMode: $proximityPulseRateMode, '
        'proximityPulsePurpose: $proximityPulsePurpose, '
        'proximityPulseSchedulingActive: $proximityPulseSchedulingActive, '
        'proximityPulseActiveHoursNow: $proximityPulseActiveHoursNow, '
        'fusedLocationLastHealthyAtMillis: $fusedLocationLastHealthyAtMillis, '
        'fusedLocationHealthyAgeMillis: $fusedLocationHealthyAgeMillis, '
        'fusedLocationLastHealthySource: $fusedLocationLastHealthySource, '
        'fusedLocationRecoveryStartedAtMillis: $fusedLocationRecoveryStartedAtMillis, '
        'fusedLocationLastRecoveryEndedAtMillis: $fusedLocationLastRecoveryEndedAtMillis, '
        'fusedLocationLastRecoveryReason: $fusedLocationLastRecoveryReason, '
        'fusedLocationBalancedRefreshCount: $fusedLocationBalancedRefreshCount, '
        'proximityAlarmKind: $proximityAlarmKind, '
        'proximityAlarmPendingIntentExists: $proximityAlarmPendingIntentExists, '
        'proximityAlarmSchedule: $proximityAlarmSchedule, '
        'activityTransitionPendingIntentExists: $activityTransitionPendingIntentExists, '
        'activityPeriodicPendingIntentExists: $activityPeriodicPendingIntentExists, '
        'activityControllerDesired: $activityControllerDesired, '
        'activityTransitionDesired: $activityTransitionDesired, '
        'activityPeriodicDesired: $activityPeriodicDesired, '
        'activityPeriodicMode: $activityPeriodicMode, '
        'activityPeriodicBackstopEnabled: $activityPeriodicBackstopEnabled, '
        'activityOperationEpoch: $activityOperationEpoch, '
        'activityMonitoringSessionGeneration: $activityMonitoringSessionGeneration, '
        'activityTransitionRequestInFlight: $activityTransitionRequestInFlight, '
        'activityTransitionConfirmed: $activityTransitionConfirmed, '
        'activityTransitionRemovalInFlight: $activityTransitionRemovalInFlight, '
        'activityTransitionRemovalConfirmed: $activityTransitionRemovalConfirmed, '
        'activityPeriodicRequestInFlight: $activityPeriodicRequestInFlight, '
        'activityPeriodicConfirmed: $activityPeriodicConfirmed, '
        'activityPeriodicRemovalInFlight: $activityPeriodicRemovalInFlight, '
        'activityPeriodicRemovalConfirmed: $activityPeriodicRemovalConfirmed, '
        'activityPeriodicRemovalRequired: $activityPeriodicRemovalRequired, '
        'activityBootstrapRequestedAtMillis: $activityBootstrapRequestedAtMillis, '
        'activityBootstrapDeadlineMillis: $activityBootstrapDeadlineMillis, '
        'activityBootstrapResultReceived: $activityBootstrapResultReceived, '
        'activityBootstrapCompleted: $activityBootstrapCompleted, '
        'activityBootstrapTimeoutPendingIntentExists: '
        '$activityBootstrapTimeoutPendingIntentExists, '
        'activityDesiredPeriodicIntervalMillis: $activityDesiredPeriodicIntervalMillis, '
        'activityConfirmedPeriodicIntervalMillis: $activityConfirmedPeriodicIntervalMillis, '
        'activityConfirmedPeriodicOwner: $activityConfirmedPeriodicOwner, '
        'activityStaleOperationCallbackCount: $activityStaleOperationCallbackCount, '
        'activityLastStaleOperationCallbackReason: $activityLastStaleOperationCallbackReason, '
        'activityIgnoredCallbackCount: $activityIgnoredCallbackCount, '
        'activityLastIgnoredCallbackReason: $activityLastIgnoredCallbackReason, '
        'activityPeriodicReason: $activityPeriodicReason, '
        'activityPeriodicRequestedAtMillis: $activityPeriodicRequestedAtMillis, '
        'activityPeriodicIntervalMillis: $activityPeriodicIntervalMillis, '
        'lastActivityPeriodicResultAtMillis: $lastActivityPeriodicResultAtMillis, '
        'activityStationarySource: $activityStationarySource, '
        'dormantFarProbePendingIntentExists: $dormantFarProbePendingIntentExists, '
        'proximityPulseIdleTicks: $proximityPulseIdleTicks, '
        'lastLocationWakeAtMillis: $lastLocationWakeAtMillis, '
        'lastLocationWakeSource: $lastLocationWakeSource, '
        'lastLocationWakeProvider: $lastLocationWakeProvider, '
        'lastLocationWakeNearestFenceId: $lastLocationWakeNearestFenceId, '
        'lastLocationWakeEdgeDistanceMeters: $lastLocationWakeEdgeDistanceMeters, '
        'lastLocationWakeAccuracyMeters: $lastLocationWakeAccuracyMeters, '
        'lastLocationWakeAgeMillis: $lastLocationWakeAgeMillis, '
        'lastLocationWakeWithinProximity: $lastLocationWakeWithinProximity, '
        'lastConfirmQueueAtMillis: $lastConfirmQueueAtMillis, '
        'lastConfirmQueueRequestId: $lastConfirmQueueRequestId, '
        'lastConfirmQueueFenceId: $lastConfirmQueueFenceId, '
        'lastConfirmQueueIsProximity: $lastConfirmQueueIsProximity, '
        'lastConfirmQueueSource: $lastConfirmQueueSource, '
        'lastConfirmRequestAtMillis: $lastConfirmRequestAtMillis, '
        'lastConfirmSource: $lastConfirmSource, '
        'lastConfirmPriority: $lastConfirmPriority, '
        'lastConfirmTimeoutMillis: $lastConfirmTimeoutMillis, '
        'lastConfirmResult: $lastConfirmResult, '
        'lastConfirmElapsedMillis: $lastConfirmElapsedMillis, '
        'lastConfirmLocationProvider: $lastConfirmLocationProvider, '
        'lastConfirmLocationAccuracyMeters: $lastConfirmLocationAccuracyMeters, '
        'lastConfirmLocationAgeMillis: $lastConfirmLocationAgeMillis, '
        'lastConfirmFailureMessage: $lastConfirmFailureMessage, '
        'lastNativeExitConfirmTimingMode: $lastNativeExitConfirmTimingMode, '
        'lastNativeExitConfirmTimingReason: $lastNativeExitConfirmTimingReason, '
        'lastNativeExitConfirmTimingAtMillis: $lastNativeExitConfirmTimingAtMillis, '
        'lastNativeEnterConfirmTimingMode: $lastNativeEnterConfirmTimingMode, '
        'lastNativeEnterConfirmTimingReason: $lastNativeEnterConfirmTimingReason, '
        'lastNativeEnterConfirmTimingAtMillis: $lastNativeEnterConfirmTimingAtMillis, '
        'lastConfirmQueueAgeMillis: $lastConfirmQueueAgeMillis, '
        'lastBoundaryDecisionAtMillis: $lastBoundaryDecisionAtMillis, '
        'lastBoundaryDecision: $lastBoundaryDecision, '
        'lastBoundaryDecisionFenceId: $lastBoundaryDecisionFenceId, '
        'lastBoundaryDecisionSource: $lastBoundaryDecisionSource, '
        'lastBoundaryDistanceMeters: $lastBoundaryDistanceMeters, '
        'lastBoundaryRadiusMeters: $lastBoundaryRadiusMeters, '
        'lastBoundaryEdgeDistanceMeters: $lastBoundaryEdgeDistanceMeters, '
        'lastBoundaryAccuracyMeters: $lastBoundaryAccuracyMeters, '
        'lastBoundaryMock: $lastBoundaryMock, '
        'lastBoundaryQueuedEvent: $lastBoundaryQueuedEvent, '
        'lastMockLocationDecisionAtMillis: $lastMockLocationDecisionAtMillis, '
        'lastMockLocationPolicy: $lastMockLocationPolicy, '
        'lastMockLocationAction: $lastMockLocationAction, '
        'lastMockLocationSource: $lastMockLocationSource, '
        'lastMockLocationProvider: $lastMockLocationProvider, '
        'lastMockLocationAccuracyMeters: $lastMockLocationAccuracyMeters, '
        'lastMockLocationAgeMillis: $lastMockLocationAgeMillis, '
        'lastMockLocationSuppressed: $lastMockLocationSuppressed, '
        'lastSmartCallbackAtMillis: $lastSmartCallbackAtMillis, '
        'lastSmartCallbackEvent: $lastSmartCallbackEvent, '
        'lastSmartCallbackFenceId: $lastSmartCallbackFenceId, '
        'lastSmartCallbackSource: $lastSmartCallbackSource, '
        'lastSmartCallbackResult: $lastSmartCallbackResult, '
        'lastSmartCallbackEnqueuedAtMillis: $lastSmartCallbackEnqueuedAtMillis, '
        'lastSmartCallbackEventAtMillis: $lastSmartCallbackEventAtMillis, '
        'lastSmartCallbackTimestampSource: $lastSmartCallbackTimestampSource, '
        'lastSmartCallbackDeliveryPath: $lastSmartCallbackDeliveryPath, '
        'lastSmartCallbackTriggerToDeliveryLatencyMillis: $lastSmartCallbackTriggerToDeliveryLatencyMillis, '
        'lastSmartCallbackDeviceIdleModeAtDelivery: $lastSmartCallbackDeviceIdleModeAtDelivery, '
        'lastCallbackDispatchResult: $lastCallbackDispatchResult, '
        'lastCallbackDispatchFenceId: $lastCallbackDispatchFenceId, '
        'lastCallbackDispatchTimestampSource: $lastCallbackDispatchTimestampSource, '
        'lastCallbackDispatchTimeReasonCode: $lastCallbackDispatchTimeReasonCode, '
        'lastCallbackDispatchTimeTrusted: $lastCallbackDispatchTimeTrusted, '
        'lastCallbackDispatchError: $lastCallbackDispatchError, '
        'bootFollowUpReceiverDeclared: $bootFollowUpReceiverDeclared, '
        'bootFollowUpPendingIntentExists: $bootFollowUpPendingIntentExists, '
        'lastBootRecoverySource: $lastBootRecoverySource, '
        'lastBootRecoveryAction: $lastBootRecoveryAction, '
        'lastBootRecoveryStartedAtMillis: $lastBootRecoveryStartedAtMillis, '
        'lastBootRecoveryFinishedAtMillis: $lastBootRecoveryFinishedAtMillis, '
        'lastBootRecoveryResult: $lastBootRecoveryResult, '
        'lastBootRecoveryNativeFenceCount: $lastBootRecoveryNativeFenceCount, '
        'lastBootRecoverySmartFenceCount: $lastBootRecoverySmartFenceCount, '
        'lastBootRecoveryElapsedMillis: $lastBootRecoveryElapsedMillis, '
        'lastBootRecoveryFailureMessage: $lastBootRecoveryFailureMessage, '
        'lastBootFollowUpScheduledAtMillis: $lastBootFollowUpScheduledAtMillis, '
        'lastBootFollowUpTriggerAtMillis: $lastBootFollowUpTriggerAtMillis, '
        'lastBootFollowUpAction: $lastBootFollowUpAction, '
        'lastBootFollowUpScheduleResult: $lastBootFollowUpScheduleResult, '
        'lastBootFollowUpFailureMessage: $lastBootFollowUpFailureMessage, '
        'lastBroadcastDeadlineReason: $lastBroadcastDeadlineReason, '
        'lastRecoveryMainThreadTimeoutReason: $lastRecoveryMainThreadTimeoutReason, '
        'warnings: $warnings, '
        'nativeStatus: $nativeStatus)';
  }

  static SGConfig _configFromMap(Map<Object?, Object?> map) {
    return SGConfig.fromMap(map);
  }

  static Map<Object?, Object?> _map(Map<Object?, Object?> map, String key) {
    final value = map[key];
    if (value is Map<Object?, Object?>) return value;
    if (value is Map) return value.cast<Object?, Object?>();
    return const <Object?, Object?>{};
  }

  static List<String> _stringList(Map<Object?, Object?> map, String key) {
    final value = map[key];
    if (value is List) {
      return value.whereType<String>().toList(growable: false);
    }
    return const <String>[];
  }

  static List<Map<Object?, Object?>> _mapList(
    Map<Object?, Object?> map,
    String key,
  ) {
    final value = map[key];
    if (value is! List) return const <Map<Object?, Object?>>[];
    return value
        .whereType<Map>()
        .map((item) => item.cast<Object?, Object?>())
        .toList(growable: false);
  }

  static Map<String, int> _stringIntMap(Map<Object?, Object?> map, String key) {
    final value = map[key];
    if (value is! Map) return const <String, int>{};
    return value.map((key, value) {
      final parsedKey = key?.toString() ?? '';
      final parsedValue = value is num ? value.toInt() : 0;
      return MapEntry(parsedKey, parsedValue);
    });
  }

  static Map<String, SGRadiusNormalization> _radiusNormalizationMap(
    Map<Object?, Object?> map,
    String key,
  ) {
    final value = map[key];
    if (value is! Map) return const <String, SGRadiusNormalization>{};
    final parsed = <String, SGRadiusNormalization>{};
    for (final entry in value.entries) {
      final id = entry.key;
      final rawNormalization = entry.value;
      if (id is! String || rawNormalization is! Map) continue;
      final normalization = SGRadiusNormalization.tryFromMap(
        rawNormalization.cast<Object?, Object?>(),
      );
      if (normalization != null) parsed[id] = normalization;
    }
    return Map<String, SGRadiusNormalization>.unmodifiable(parsed);
  }

  static bool? _boolOrNull(Map<Object?, Object?> map, String key) {
    final value = map[key];
    return value is bool ? value : null;
  }

  static bool? _foregroundNotificationShowWhileMonitoring(
    Map<Object?, Object?> map,
  ) {
    final value = map['foregroundNotificationShowWhileMonitoring'];
    if (value is bool) return value;

    final legacyRemoveWhenIdle = map['foregroundNotificationRemoveWhenIdle'];
    if (legacyRemoveWhenIdle is bool) return !legacyRemoveWhenIdle;

    return null;
  }

  static int? _intOrNull(Map<Object?, Object?> map, String key) {
    final value = map[key];
    return value is num ? value.toInt() : null;
  }

  static double? _doubleOrNull(Map<Object?, Object?> map, String key) {
    final value = map[key];
    return value is num ? value.toDouble() : null;
  }

  static String? _stringOrNull(Map<Object?, Object?> map, String key) {
    final value = map[key];
    return value is String ? value : null;
  }

  static SGExactAlarmPermissionStatus? _exactAlarmPermissionStatus(
    Map<Object?, Object?> map,
    String key,
  ) {
    final value = _stringOrNull(map, key);
    if (value == null) return null;
    for (final status in SGExactAlarmPermissionStatus.values) {
      if (status.name == value) return status;
    }
    return SGExactAlarmPermissionStatus.settingsUnavailable;
  }

  static SGExactAlarmPermissionMode? _exactAlarmPermissionMode(
    Map<Object?, Object?> map,
    String key,
  ) {
    final value = _stringOrNull(map, key);
    if (value == null) return null;
    for (final mode in SGExactAlarmPermissionMode.values) {
      if (mode.name == value) return mode;
    }
    return null;
  }

  static SGMockLocationPolicy? _mockLocationPolicy(
    Map<Object?, Object?> map,
    String key,
  ) {
    final value = _stringOrNull(map, key);
    if (value == null) return null;
    for (final policy in SGMockLocationPolicy.values) {
      if (policy.name == value) return policy;
    }
    return null;
  }

  static SGForegroundNotificationTapAction? _foregroundNotificationTapAction(
    Map<Object?, Object?> map,
    String key,
  ) {
    final value = _stringOrNull(map, key);
    if (value == null) return null;
    for (final action in SGForegroundNotificationTapAction.values) {
      if (action.name == value) return action;
    }
    return null;
  }

  static bool _hasFailure(String? reason) =>
      reason != null && reason.trim().isNotEmpty;
}

Map<String, Object?> _jsonMap(Map<Object?, Object?> value) => {
  for (final entry in value.entries)
    entry.key.toString(): _jsonValue(entry.value),
};

Object? _jsonValue(Object? value) {
  if (value == null || value is String || value is num || value is bool) {
    return value;
  }
  if (value is DateTime) return value.toIso8601String();
  if (value is Enum) return value.name;
  if (value is Map<Object?, Object?>) return _jsonMap(value);
  if (value is Map) {
    return {
      for (final entry in value.entries)
        entry.key.toString(): _jsonValue(entry.value),
    };
  }
  if (value is Iterable) return value.map(_jsonValue).toList(growable: false);
  return value.toString();
}
