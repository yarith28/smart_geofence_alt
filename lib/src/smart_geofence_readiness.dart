import 'package:native_geofence/native_geofence.dart' as ng;

import 'smart_geofence_config.dart';
import 'smart_geofence_status.dart';

enum SGReadinessLevel { ready, degraded, blocked }

enum SGReadinessIssueSeverity { warning, blocking }

enum SGReadinessIssueCode {
  smartStatusUnavailable,
  locationServicesDisabled,
  locationPermissionMissing,
  backgroundLocationPermissionMissing,
  notificationPermissionMissing,
  activityRecognitionPermissionMissing,
  exactAlarmPermissionMissing,
  requiredManifestComponentMissing,
  callbackHandlesNeedRefresh,
  foregroundConfirmationUnavailable,
}

class SGReadinessIssue {
  const SGReadinessIssue({
    required this.code,
    required this.severity,
    required this.message,
    this.recommendedAction,
  });

  final SGReadinessIssueCode code;
  final SGReadinessIssueSeverity severity;
  final String message;
  final String? recommendedAction;

  Map<String, Object?> toJson() => {
    'code': code.name,
    'severity': severity.name,
    'message': message,
    'recommendedAction': recommendedAction,
  };
}

class SGMonitoringReadiness {
  const SGMonitoringReadiness({
    required this.level,
    required this.issues,
    required this.status,
  });

  factory SGMonitoringReadiness.fromStatus(SmartGeofenceStatus status) {
    final issuesByCode = <SGReadinessIssueCode, SGReadinessIssue>{};

    void add(
      SGReadinessIssueCode code,
      SGReadinessIssueSeverity severity,
      String message,
      String recommendedAction,
    ) {
      final existing = issuesByCode[code];
      if (existing != null &&
          existing.severity == SGReadinessIssueSeverity.blocking) {
        return;
      }
      issuesByCode[code] = SGReadinessIssue(
        code: code,
        severity: severity,
        message: message,
        recommendedAction: recommendedAction,
      );
    }

    final native = status.nativeStatus;
    if (status.smartLayerSupported &&
        status.smartStatusState != SGSmartStatusState.complete) {
      add(
        SGReadinessIssueCode.smartStatusUnavailable,
        SGReadinessIssueSeverity.blocking,
        switch (status.smartStatusState) {
          SGSmartStatusState.unavailable =>
            'Android smart-layer status is unavailable.',
          SGSmartStatusState.unsupportedSchema =>
            'Android smart-layer status uses an unsupported schema.',
          SGSmartStatusState.malformed =>
            'Android smart-layer status is incomplete or malformed.',
          SGSmartStatusState.unsupported =>
            'Android smart-layer status is unsupported.',
          SGSmartStatusState.complete =>
            'Android smart-layer status is complete.',
        },
        'Update the plugin if needed, then retry status inspection before starting monitoring.',
      );
    }
    final locationServicesEnabled =
        status.locationServicesEnabled ?? native.locationServicesEnabled;
    final locationPermissionGranted =
        status.locationPermissionGranted ?? native.locationPermissionGranted;
    final backgroundLocationPermissionGranted =
        status.backgroundLocationPermissionGranted ??
        native.backgroundLocationPermissionGranted;

    if (locationServicesEnabled == false) {
      add(
        SGReadinessIssueCode.locationServicesDisabled,
        SGReadinessIssueSeverity.blocking,
        'Device location services are disabled.',
        'Enable device location services before starting monitoring.',
      );
    }
    if (locationPermissionGranted == false) {
      add(
        SGReadinessIssueCode.locationPermissionMissing,
        SGReadinessIssueSeverity.blocking,
        'Location permission is not granted.',
        'Grant location permission before starting monitoring.',
      );
    } else if (status.smartLayerSupported &&
        status.fineLocationGranted == false) {
      add(
        SGReadinessIssueCode.locationPermissionMissing,
        SGReadinessIssueSeverity.warning,
        'Precise location is unavailable; smart confirmation is degraded.',
        'Grant precise location for full smart confirmation capability.',
      );
    }
    if (backgroundLocationPermissionGranted == false) {
      add(
        SGReadinessIssueCode.backgroundLocationPermissionMissing,
        SGReadinessIssueSeverity.blocking,
        'Background location permission is not granted.',
        'Grant background location permission before starting monitoring.',
      );
    }
    if (native.callbackRefreshState ==
        ng.NativeGeofenceCallbackRefreshState.refreshRequired) {
      add(
        SGReadinessIssueCode.callbackHandlesNeedRefresh,
        SGReadinessIssueSeverity.blocking,
        'Stored geofence callback handles need an application-build refresh.',
        'Run ensureSynchronized with live callback functions.',
      );
    }

    if (status.smartLayerSupported) {
      final config = status.config;
      if (config.foregroundNotification.showWhileMonitoring &&
          status.notificationPermissionGranted == false) {
        add(
          SGReadinessIssueCode.notificationPermissionMissing,
          SGReadinessIssueSeverity.warning,
          'Monitoring notifications are enabled but notification permission is missing.',
          'Grant notification permission for visible monitoring state.',
        );
      }

      final activityCapabilityEnabled =
          config.proximityPulse.enabled ||
          config.escalation.proximity.adaptiveDisplacement.enabled;
      if (activityCapabilityEnabled &&
          status.activityPermissionGranted == false) {
        add(
          SGReadinessIssueCode.activityRecognitionPermissionMissing,
          SGReadinessIssueSeverity.warning,
          'Activity-aware geofence optimization is enabled but permission is missing.',
          'Grant Activity Recognition permission for full optimization.',
        );
      }

      final exactAlarmUnavailable =
          status.exactAlarmPermissionGranted == false ||
          status.exactAlarmPermissionStatus ==
              SGExactAlarmPermissionStatus.denied ||
          status.exactAlarmPermissionStatus ==
              SGExactAlarmPermissionStatus.settingsUnavailable;
      if (exactAlarmUnavailable) {
        final strict =
            config.exactAlarm.permissionMode ==
                SGExactAlarmPermissionMode.strict ||
            status.exactAlarmStrictStartupBlocked == true;
        add(
          SGReadinessIssueCode.exactAlarmPermissionMissing,
          strict
              ? SGReadinessIssueSeverity.blocking
              : SGReadinessIssueSeverity.warning,
          strict
              ? 'Strict exact-alarm mode requires special access before monitoring can start.'
              : 'Exact alarms are unavailable; smart recovery timing is degraded.',
          'Grant Alarms & reminders access when prompted by the application.',
        );
      }

      final missingManifestComponents = <String>[
        if (status.fusedLocationUpdateReceiverDeclared == false)
          'FusedLocationUpdateReceiver',
        if (status.bootReceiverDeclared == false) 'BootReceiver',
        if (status.exactAlarmPermissionStateReceiverDeclared == false)
          'ExactAlarmPermissionReceiver',
        if (status.bootFollowUpReceiverDeclared == false)
          'BootRecoveryReceiver',
        if (status.recoveryAlarmReceiverDeclared == false)
          'RecoveryAlarmReceiver',
        if (config.escalation.enabled &&
            status.locationConfirmReceiverDeclared == false)
          'LocationConfirmAlarmReceiver',
        if (config.escalation.enabled &&
            status.nativeExitFallbackReceiverDeclared == false)
          'NativeExitFallbackReceiver',
        if (config.escalation.enabled &&
            status.nativeEnterFallbackReceiverDeclared == false)
          'NativeEnterFallbackReceiver',
        if (config.escalation.enabled &&
            status.foregroundServiceLaunchReceiverDeclared == false)
          'ForegroundServiceLaunchReceiver',
        if (config.escalation.enabled &&
            status.locationConfirmServiceDeclared == false)
          'LocationConfirmService',
        if (config.proximityPulse.enabled &&
            status.proximityAlarmReceiverDeclared == false)
          'ProximityAlarmReceiver',
      ];
      if (missingManifestComponents.isNotEmpty) {
        missingManifestComponents.sort();
        add(
          SGReadinessIssueCode.requiredManifestComponentMissing,
          SGReadinessIssueSeverity.warning,
          'Optional smart capabilities are unavailable because merged-manifest components are missing: ${missingManifestComponents.join(', ')}.',
          'Declare the listed smart_geofence components in the merged manifest.',
        );
      }

      final foregroundConfirmationUnavailable =
          config.escalation.enabled &&
          (status.foregroundServicePermissionGranted == false ||
              status.foregroundServiceLocationPermissionGranted == false ||
              status.locationConfirmServiceDeclared == false ||
              status.locationConfirmServiceHasLocationType == false ||
              status.locationConfirmCanRun == false);
      if (foregroundConfirmationUnavailable) {
        add(
          SGReadinessIssueCode.foregroundConfirmationUnavailable,
          SGReadinessIssueSeverity.warning,
          'Foreground fused-location confirmation is unavailable.',
          'Verify foreground-service permissions and location service declarations.',
        );
      }
    }

    final issues = SGReadinessIssueCode.values
        .map((code) => issuesByCode[code])
        .whereType<SGReadinessIssue>()
        .toList(growable: false);
    final level =
        issues.any(
          (issue) => issue.severity == SGReadinessIssueSeverity.blocking,
        )
        ? SGReadinessLevel.blocked
        : issues.isNotEmpty
        ? SGReadinessLevel.degraded
        : SGReadinessLevel.ready;
    return SGMonitoringReadiness(level: level, issues: issues, status: status);
  }

  final SGReadinessLevel level;
  final List<SGReadinessIssue> issues;
  final SmartGeofenceStatus status;

  bool get canMonitor => level != SGReadinessLevel.blocked;
  bool get fullyOperational => level == SGReadinessLevel.ready;

  List<SGReadinessIssue> get blockingIssues => issues
      .where((issue) => issue.severity == SGReadinessIssueSeverity.blocking)
      .toList(growable: false);

  List<SGReadinessIssue> get degradedIssues => issues
      .where((issue) => issue.severity == SGReadinessIssueSeverity.warning)
      .toList(growable: false);

  Map<String, Object?> toJson() => {
    'level': level.name,
    'canMonitor': canMonitor,
    'fullyOperational': fullyOperational,
    'issues': issues.map((issue) => issue.toJson()).toList(growable: false),
    'status': status.toJson(),
  };
}
