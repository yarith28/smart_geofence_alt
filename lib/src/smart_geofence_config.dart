import 'dart:convert';

import 'package:time_integrity/time_integrity.dart';

enum SGLocationPriority {
  highAccuracy,
  balancedPowerAccuracy,
  lowPower,
  passive,
}

enum SGBatteryMode { balanced, highAccuracy, lowPower }

enum SGLocationUnavailablePolicy { recover, stop }

enum SGMockLocationPolicy { allow, logOnly, reject }

enum SGAlarmSchedulePolicy {
  exactOnly,
  exactWithInexactFallback,
  inexactWithExactGuard,
  inexactOnly,
}

enum SGExactAlarmPermissionMode { bestEffort, strict }

enum SGExactAlarmPermissionStatus {
  notRequired,
  granted,
  denied,
  settingsUnavailable,
}

enum SGActivityRecognitionPermissionStatus {
  notRequired,
  granted,
  denied,
  permanentlyDenied,
  requestUnavailable,
  settingsUnavailable,
}

enum SGForegroundNotificationTapAction { openApp, dismiss, none }

class SGConfig {
  final SGBatteryMode batteryMode;

  final SGLocationUnavailablePolicy locationUnavailablePolicy;

  final SGMockLocationPolicy mockLocationPolicy;

  final SGNativeEventFilterConfig nativeEvents;

  final SGTransitionValidationConfig transitionValidation;

  final SGProximityPulseConfig proximityPulse;

  final SGAdvancedConfig advanced;

  final SGRecoveryConfig recovery;

  final SGExactAlarmConfig exactAlarm;

  final SGLogConfig logging;

  final bool retryOnCallbackFailure;

  final SGForegroundNotificationConfig foregroundNotification;

  final TimeIntegrityConfig? timeIntegrity;

  const SGConfig({
    this.batteryMode = SGBatteryMode.balanced,
    this.locationUnavailablePolicy = SGLocationUnavailablePolicy.recover,
    this.mockLocationPolicy = SGMockLocationPolicy.logOnly,
    this.nativeEvents = const SGNativeEventFilterConfig(),
    this.transitionValidation = const SGTransitionValidationConfig(),
    this.proximityPulse = const SGProximityPulseConfig(),
    SGAdvancedConfig? advanced,
    SGRecoveryConfig? recovery,
    this.exactAlarm = const SGExactAlarmConfig(),
    this.logging = const SGLogConfig(),
    this.retryOnCallbackFailure = false,
    this.foregroundNotification = const SGForegroundNotificationConfig(),
    this.timeIntegrity,
  }) : recovery = recovery ?? const SGRecoveryConfig._defaults(),
       advanced =
           advanced ??
           (batteryMode == SGBatteryMode.highAccuracy
               ? const SGAdvancedConfig.highAccuracy()
               : batteryMode == SGBatteryMode.lowPower
               ? const SGAdvancedConfig.lowPower()
               : const SGAdvancedConfig());

  SGConfig copyWith({
    SGBatteryMode? batteryMode,
    SGLocationUnavailablePolicy? locationUnavailablePolicy,
    SGMockLocationPolicy? mockLocationPolicy,
    SGNativeEventFilterConfig? nativeEvents,
    SGTransitionValidationConfig? transitionValidation,
    SGProximityPulseConfig? proximityPulse,
    SGAdvancedConfig? advanced,
    SGRecoveryConfig? recovery,
    SGExactAlarmConfig? exactAlarm,
    SGLogConfig? logging,
    bool? retryOnCallbackFailure,
    SGForegroundNotificationConfig? foregroundNotification,
    TimeIntegrityConfig? timeIntegrity,
    bool clearTimeIntegrity = false,
  }) {
    if (clearTimeIntegrity && timeIntegrity != null) {
      throw ArgumentError(
        'Provide either timeIntegrity or clearTimeIntegrity, not both.',
      );
    }
    final effectiveBatteryMode = batteryMode ?? this.batteryMode;
    final batteryModeChanged =
        batteryMode != null && batteryMode != this.batteryMode;
    return SGConfig(
      batteryMode: effectiveBatteryMode,
      locationUnavailablePolicy:
          locationUnavailablePolicy ?? this.locationUnavailablePolicy,
      mockLocationPolicy: mockLocationPolicy ?? this.mockLocationPolicy,
      nativeEvents: nativeEvents ?? this.nativeEvents,
      transitionValidation: transitionValidation ?? this.transitionValidation,
      proximityPulse: proximityPulse ?? this.proximityPulse,
      advanced:
          advanced ??
          (batteryModeChanged
              ? _advancedPresetFor(effectiveBatteryMode)
              : this.advanced),
      recovery: recovery ?? this.recovery,
      exactAlarm: exactAlarm ?? this.exactAlarm,
      logging: logging ?? this.logging,
      retryOnCallbackFailure:
          retryOnCallbackFailure ?? this.retryOnCallbackFailure,
      foregroundNotification:
          foregroundNotification ?? this.foregroundNotification,
      timeIntegrity: clearTimeIntegrity
          ? null
          : timeIntegrity ?? this.timeIntegrity,
    );
  }

  factory SGConfig.fromMap(Map<Object?, Object?> map) {
    final batteryMode = _batteryMode(
      map,
      'batteryMode',
      SGBatteryMode.balanced,
    );
    final defaults = SGConfig(batteryMode: batteryMode);
    final nativeEvents = SGNativeEventFilterConfig(
      confirmExits: _bool(
        map,
        'nativeExitConfirmationEnabled',
        defaults.nativeEvents.confirmExits,
      ),
      confirmEnters: _bool(
        map,
        'nativeEnterConfirmationEnabled',
        defaults.nativeEvents.confirmEnters,
      ),
      confirmDelay: Duration(
        milliseconds: _int(
          map,
          'nativeConfirmDelayMillis',
          defaults.nativeEvents.confirmDelay.inMilliseconds,
        ),
      ),
      confirmMaxAttempts: _int(
        map,
        'nativeConfirmMaxAttempts',
        defaults.nativeEvents.confirmMaxAttempts,
      ),
      enterConfirmRadiusSlackMeters: _double(
        map,
        'nativeEnterConfirmRadiusSlackMeters',
        defaults.nativeEvents.enterConfirmRadiusSlackMeters,
      ),
      rejectDistantEnterPayloads: _bool(
        map,
        'nativeEnterPayloadSanityEnabled',
        defaults.nativeEvents.rejectDistantEnterPayloads,
      ),
      enterPayloadDistanceSlackMeters: _double(
        map,
        'nativeEnterPayloadDistanceSlackMeters',
        defaults.nativeEvents.enterPayloadDistanceSlackMeters,
      ),
    );
    final mockLocationPolicy = _mockLocationPolicy(
      map,
      'mockLocationPolicy',
      defaults.mockLocationPolicy,
    );
    final transitionValidation = SGTransitionValidationConfig(
      enabled: _bool(
        map,
        'transitionValidationEnabled',
        defaults.transitionValidation.enabled,
      ),
      enterEnabled: _bool(
        map,
        'transitionValidationEnterEnabled',
        nativeEvents.confirmEnters,
      ),
      exitEnabled: _bool(
        map,
        'transitionValidationExitEnabled',
        nativeEvents.confirmExits,
      ),
      minimumDelay: Duration(
        milliseconds: _int(
          map,
          'transitionValidationMinimumDelayMillis',
          defaults.transitionValidation.minimumDelay.inMilliseconds,
        ),
      ),
    );
    final escalation = SGEscalationConfig(
      enabled: _bool(map, 'escalationEnabled', defaults.escalation.enabled),
      proximity: SGProximityConfig(
        radiusMeters: _double(
          map,
          'proximityRadiusMeters',
          defaults.escalation.proximity.radiusMeters,
        ),
        priority: _priority(
          map,
          'proximityLocationPriority',
          defaults.escalation.proximity.priority,
        ),
        interval: Duration(
          milliseconds: _int(
            map,
            'proximityIntervalMillis',
            defaults.escalation.proximity.interval.inMilliseconds,
          ),
        ),
        fastestInterval: Duration(
          milliseconds: _int(
            map,
            'proximityFastestIntervalMillis',
            defaults.escalation.proximity.fastestInterval.inMilliseconds,
          ),
        ),
        maxWait: Duration(
          milliseconds: _int(
            map,
            'proximityMaxWaitMillis',
            defaults.escalation.proximity.maxWait.inMilliseconds,
          ),
        ),
        minDisplacementMeters: _double(
          map,
          'proximityMinDisplacementMeters',
          defaults.escalation.proximity.minDisplacementMeters,
        ),
        adaptiveDisplacement: SGAdaptiveDisplacementConfig(
          enabled: _bool(
            map,
            'proximityAdaptiveDisplacementEnabled',
            defaults.escalation.proximity.adaptiveDisplacement.enabled,
          ),
          nearBoundaryDistanceMeters: _double(
            map,
            'proximityAdaptiveNearBoundaryDistanceMeters',
            defaults
                .escalation
                .proximity
                .adaptiveDisplacement
                .nearBoundaryDistanceMeters,
          ),
          nearBoundaryDisplacementMeters: _double(
            map,
            'proximityAdaptiveNearBoundaryDisplacementMeters',
            defaults
                .escalation
                .proximity
                .adaptiveDisplacement
                .nearBoundaryDisplacementMeters,
          ),
          stationaryDisplacementMeters: _double(
            map,
            'proximityAdaptiveStationaryDisplacementMeters',
            defaults
                .escalation
                .proximity
                .adaptiveDisplacement
                .stationaryDisplacementMeters,
          ),
          hysteresisMeters: _double(
            map,
            'proximityAdaptiveHysteresisMeters',
            defaults.escalation.proximity.adaptiveDisplacement.hysteresisMeters,
          ),
        ),
      ),
      passive: SGPassiveLocationConfig(
        enabled: _bool(
          map,
          'passiveLocationEnabled',
          defaults.escalation.passive.enabled,
        ),
        priority: _priority(
          map,
          'passiveLocationPriority',
          defaults.escalation.passive.priority,
        ),
        interval: Duration(
          milliseconds: _int(
            map,
            'passiveLocationIntervalMillis',
            defaults.escalation.passive.interval.inMilliseconds,
          ),
        ),
        fastestInterval: Duration(
          milliseconds: _int(
            map,
            'passiveLocationFastestIntervalMillis',
            defaults.escalation.passive.fastestInterval.inMilliseconds,
          ),
        ),
        maxWait: Duration(
          milliseconds: _int(
            map,
            'passiveLocationMaxWaitMillis',
            defaults.escalation.passive.maxWait.inMilliseconds,
          ),
        ),
        followUpEnabled: _bool(
          map,
          'passiveFollowUpEnabled',
          _bool(
            map,
            'passiveAmbiguousConfirmEnabled',
            defaults.escalation.passive.followUpEnabled,
          ),
        ),
      ),
      locationConfirm: SGLocationConfirmConfig(
        timeout: Duration(
          milliseconds: _int(
            map,
            'locationConfirmTimeoutMillis',
            defaults.escalation.locationConfirm.timeout.inMilliseconds,
          ),
        ),
      ),
      locationFilter: SGLocationFilterConfig(
        pulseMaxAccuracyMeters: _double(
          map,
          'pulseLocationMaxAccuracyMeters',
          defaults.escalation.locationFilter.pulseMaxAccuracyMeters,
        ),
        eventMaxAccuracyMeters: _double(
          map,
          'eventLocationMaxAccuracyMeters',
          defaults.escalation.locationFilter.eventMaxAccuracyMeters,
        ),
      ),
      nativeEvents: nativeEvents,
      teleportGuard: SGTeleportGuardConfig(
        enabled: _bool(
          map,
          'teleportGuardEnabled',
          defaults.escalation.teleportGuard.enabled,
        ),
        maxSpeedMetersPerSecond: _double(
          map,
          'teleportMaxSpeedMetersPerSecond',
          defaults.escalation.teleportGuard.maxSpeedMetersPerSecond,
        ),
      ),
      mockLocationPolicy: mockLocationPolicy,
    );

    return SGConfig(
      batteryMode: batteryMode,
      locationUnavailablePolicy: _locationUnavailablePolicy(
        map,
        'locationUnavailablePolicy',
        defaults.locationUnavailablePolicy,
      ),
      mockLocationPolicy: mockLocationPolicy,
      nativeEvents: nativeEvents,
      transitionValidation: transitionValidation,
      proximityPulse: SGProximityPulseConfig(
        enabled: _bool(
          map,
          'proximityPulseEnabled',
          defaults.proximityPulse.enabled,
        ),
        activationDistanceMeters: _double(
          map,
          'proximityPulseActivationDistanceMeters',
          defaults.proximityPulse.activationDistanceMeters,
        ),
        interval: Duration(
          milliseconds: _durationMillis(
            map: map,
            millisecondsKey: 'proximityPulseIntervalMillis',
            legacyKey: 'proximityPulseIntervalSeconds',
            legacyUnitMillis: Duration.millisecondsPerSecond,
            defaultValue: defaults.proximityPulse.interval,
          ),
        ),
        maxLocationAttempts: _int(
          map,
          'proximityConfirmMaxAttempts',
          defaults.proximityPulse.maxLocationAttempts,
        ),
        transitionConfirmation: SGTransitionConfirmationPulseConfig(
          interval: Duration(
            milliseconds: _int(
              map,
              'proximityPulseTransitionConfirmationIntervalMillis',
              defaults
                  .proximityPulse
                  .transitionConfirmation
                  .interval
                  .inMilliseconds,
            ),
          ),
          burstDuration: Duration(
            milliseconds: _int(
              map,
              'proximityPulseTransitionConfirmationBurstDurationMillis',
              defaults
                  .proximityPulse
                  .transitionConfirmation
                  .burstDuration
                  .inMilliseconds,
            ),
          ),
        ),
        activeHours: SGActiveHoursConfig(
          start: Duration(
            minutes: _int(
              map,
              'proximityPulseActiveStartMinuteOfDay',
              defaults.proximityPulse.activeHours.start.inMinutes,
            ),
          ),
          end: Duration(
            minutes: _int(
              map,
              'proximityPulseActiveEndMinuteOfDay',
              defaults.proximityPulse.activeHours.end.inMinutes,
            ),
          ),
          outsideIntervalMultiplier: _int(
            map,
            'proximityPulseOutsideActiveHoursIntervalMultiplier',
            defaults.proximityPulse.activeHours.outsideIntervalMultiplier,
          ),
        ),
      ),
      advanced: SGAdvancedConfig(
        escalation: escalation,
        activity: SGActivityConfig(
          stationaryTtl: Duration(
            milliseconds: _int(
              map,
              'activityStationaryTtlMillis',
              defaults.activity.stationaryTtl.inMilliseconds,
            ),
          ),
          periodicBackstopEnabled: _bool(
            map,
            'activityPeriodicBackstopEnabled',
            defaults.activity.periodicBackstopEnabled,
          ),
          activityUpdateInterval: Duration(
            milliseconds: _int(
              map,
              'activityUpdateIntervalMillis',
              defaults.activity.activityUpdateInterval.inMilliseconds,
            ),
          ),
          movingProximityCheckDelay: Duration(
            milliseconds: _int(
              map,
              'activityMovingProximityCheckDelayMillis',
              defaults.activity.movingProximityCheckDelay.inMilliseconds,
            ),
          ),
          fusedLocationStaleAfter: Duration(
            milliseconds: _int(
              map,
              'activityFusedLocationStaleAfterMillis',
              defaults.activity.fusedLocationStaleAfter.inMilliseconds,
            ),
          ),
        ),
        foregroundService: SGForegroundServiceConfig(
          launchTimeout: Duration(
            milliseconds: _int(
              map,
              'foregroundServiceLaunchTimeoutMillis',
              defaults.foregroundServiceLaunchTimeout.inMilliseconds,
            ),
          ),
          startDelay: Duration(
            milliseconds: _int(
              map,
              'foregroundServiceStartDelayMillis',
              defaults.foregroundServiceStartDelay.inMilliseconds,
            ),
          ),
          rearmDelay: Duration(
            milliseconds: _int(
              map,
              'foregroundServiceRearmDelayMillis',
              defaults.foregroundServiceRearmDelay.inMilliseconds,
            ),
          ),
          callbackTimeout: Duration(
            milliseconds: _int(
              map,
              'foregroundServiceCallbackTimeoutMillis',
              defaults.foregroundServiceCallbackTimeout.inMilliseconds,
            ),
          ),
          sticky: _bool(
            map,
            'foregroundServiceSticky',
            defaults.foregroundServiceSticky,
          ),
        ),
        confirmQueue: SGConfirmQueueConfig(
          maxAge: Duration(
            milliseconds: _int(
              map,
              'confirmQueueMaxAgeMillis',
              defaults.confirmQueueMaxAge.inMilliseconds,
            ),
          ),
        ),
        proximityPulse: SGProximityPulseAdvancedConfig(
          minInterval: Duration(
            milliseconds: _int(
              map,
              'proximityPulseMinIntervalMillis',
              defaults.advanced.proximityPulse.minInterval.inMilliseconds,
            ),
          ),
        ),
      ),
      recovery: SGRecoveryConfig(
        timesOfDay: _minuteOfDayList(
          map,
          'recoveryTimesMinuteOfDay',
          defaults.recovery.timesOfDay,
        ),
        alarmPolicy: _alarmPolicy(
          map,
          'recoveryAlarmPolicy',
          defaults.recovery.alarmPolicy,
        ),
        inexactGuardDelay:
            _optionalDurationMillis(map, 'recoveryInexactGuardDelayMillis') ??
            defaults.recovery.inexactGuardDelay,
      ),
      exactAlarm: SGExactAlarmConfig(
        permissionMode: _exactAlarmPermissionMode(
          map,
          'exactAlarmPermissionMode',
          defaults.exactAlarm.permissionMode,
        ),
      ),
      logging: SGLogConfig(
        fileEnabled: _bool(map, 'logFileEnabled', defaults.logging.fileEnabled),
        verbose: _bool(map, 'logFileVerbose', defaults.logging.verbose),
        maxFileBytes: _int(
          map,
          'maxLogFileBytes',
          defaults.logging.maxFileBytes,
        ),
      ),
      retryOnCallbackFailure: _bool(
        map,
        'retryOnCallbackFailure',
        defaults.retryOnCallbackFailure,
      ),
      foregroundNotification: SGForegroundNotificationConfig(
        title: _string(
          map,
          'foregroundNotificationTitle',
          defaults.foregroundNotification.title,
        ),
        channelId: _string(
          map,
          'foregroundNotificationChannelId',
          defaults.foregroundNotification.channelId,
        ),
        channelName: _string(
          map,
          'foregroundNotificationChannelName',
          defaults.foregroundNotification.channelName,
        ),
        notificationId: _int(
          map,
          'foregroundNotificationId',
          defaults.foregroundNotification.notificationId,
        ),
        smallIconResourceName: _stringOrNull(
          map,
          'foregroundNotificationSmallIconResourceName',
        ),
        sticky: _foregroundNotificationSticky(
          map,
          defaults.foregroundNotification.sticky,
        ),
        tapAction: _foregroundNotificationTapAction(
          map,
          'foregroundNotificationTapAction',
          defaults.foregroundNotification.tapAction,
        ),
        showWhileMonitoring: _foregroundNotificationShowWhileMonitoring(
          map,
          defaults.foregroundNotification.showWhileMonitoring,
        ),
      ),
      timeIntegrity: _timeIntegrityFromMap(map),
    );
  }

  void validate() {
    _requireFiniteNonNegative(
      'escalation.proximity.radiusMeters',
      escalation.proximity.radiusMeters,
    );
    _requireFiniteNonNegative(
      'escalation.proximity.minDisplacementMeters',
      escalation.proximity.minDisplacementMeters,
    );
    _requireFiniteNonNegative(
      'escalation.proximity.adaptiveDisplacement.nearBoundaryDistanceMeters',
      escalation.proximity.adaptiveDisplacement.nearBoundaryDistanceMeters,
    );
    _requireFiniteNonNegative(
      'escalation.proximity.adaptiveDisplacement.nearBoundaryDisplacementMeters',
      escalation.proximity.adaptiveDisplacement.nearBoundaryDisplacementMeters,
    );
    _requireFiniteNonNegative(
      'escalation.proximity.adaptiveDisplacement.stationaryDisplacementMeters',
      escalation.proximity.adaptiveDisplacement.stationaryDisplacementMeters,
    );
    _requireFiniteNonNegative(
      'escalation.proximity.adaptiveDisplacement.hysteresisMeters',
      escalation.proximity.adaptiveDisplacement.hysteresisMeters,
    );
    if (escalation.proximity.adaptiveDisplacement.enabled) {
      _requireDoubleNotGreater(
        'escalation.proximity.adaptiveDisplacement.nearBoundaryDisplacementMeters',
        escalation
            .proximity
            .adaptiveDisplacement
            .nearBoundaryDisplacementMeters,
        'escalation.proximity.minDisplacementMeters',
        escalation.proximity.minDisplacementMeters,
      );
      _requireDoubleNotGreater(
        'escalation.proximity.minDisplacementMeters',
        escalation.proximity.minDisplacementMeters,
        'escalation.proximity.adaptiveDisplacement.stationaryDisplacementMeters',
        escalation.proximity.adaptiveDisplacement.stationaryDisplacementMeters,
      );
    }
    _requirePositiveDuration(
      'escalation.proximity.interval',
      escalation.proximity.interval,
    );
    _requirePositiveDuration(
      'escalation.proximity.fastestInterval',
      escalation.proximity.fastestInterval,
    );
    _requirePositiveDuration(
      'escalation.proximity.maxWait',
      escalation.proximity.maxWait,
    );
    _requireNotGreater(
      'escalation.proximity.fastestInterval',
      escalation.proximity.fastestInterval,
      'escalation.proximity.interval',
      escalation.proximity.interval,
    );
    _requireNotGreater(
      'escalation.proximity.interval',
      escalation.proximity.interval,
      'escalation.proximity.maxWait',
      escalation.proximity.maxWait,
    );
    _requirePositiveDuration(
      'escalation.passive.interval',
      escalation.passive.interval,
    );
    _requirePositiveDuration(
      'escalation.passive.fastestInterval',
      escalation.passive.fastestInterval,
    );
    _requirePositiveDuration(
      'escalation.passive.maxWait',
      escalation.passive.maxWait,
    );
    _requireNotGreater(
      'escalation.passive.fastestInterval',
      escalation.passive.fastestInterval,
      'escalation.passive.interval',
      escalation.passive.interval,
    );
    _requireNotGreater(
      'escalation.passive.interval',
      escalation.passive.interval,
      'escalation.passive.maxWait',
      escalation.passive.maxWait,
    );
    if (escalation.passive.priority != SGLocationPriority.passive) {
      throw ArgumentError.value(
        escalation.passive.priority,
        'escalation.passive.priority',
        'Must be SGLocationPriority.passive. Passive location must remain no-power.',
      );
    }
    _requirePositiveDuration(
      'escalation.locationConfirm.timeout',
      escalation.locationConfirm.timeout,
    );
    _requireFinitePositive(
      'escalation.locationFilter.pulseMaxAccuracyMeters',
      escalation.locationFilter.pulseMaxAccuracyMeters,
    );
    _requireFinitePositive(
      'escalation.locationFilter.eventMaxAccuracyMeters',
      escalation.locationFilter.eventMaxAccuracyMeters,
    );
    _requireFiniteNonNegative(
      'nativeEvents.enterConfirmRadiusSlackMeters',
      nativeEvents.enterConfirmRadiusSlackMeters,
    );
    _requireNonNegativeDuration(
      'nativeEvents.confirmDelay',
      nativeEvents.confirmDelay,
    );
    if (nativeEvents.confirmMaxAttempts <= 0) {
      throw ArgumentError.value(
        nativeEvents.confirmMaxAttempts,
        'nativeEvents.confirmMaxAttempts',
        'Must be greater than zero.',
      );
    }
    _requireFiniteNonNegative(
      'nativeEvents.enterPayloadDistanceSlackMeters',
      nativeEvents.enterPayloadDistanceSlackMeters,
    );
    _requireNonNegativeDuration(
      'transitionValidation.minimumDelay',
      transitionValidation.minimumDelay,
    );
    if (transitionValidation.enabled &&
        transitionValidation.enterEnabled &&
        !nativeEvents.confirmEnters) {
      throw ArgumentError.value(
        nativeEvents.confirmEnters,
        'nativeEvents.confirmEnters',
        'Must be true when transitionValidation.enterEnabled is true.',
      );
    }
    if (transitionValidation.enabled &&
        transitionValidation.exitEnabled &&
        !nativeEvents.confirmExits) {
      throw ArgumentError.value(
        nativeEvents.confirmExits,
        'nativeEvents.confirmExits',
        'Must be true when transitionValidation.exitEnabled is true.',
      );
    }
    _requireFinitePositive(
      'escalation.teleportGuard.maxSpeedMetersPerSecond',
      escalation.teleportGuard.maxSpeedMetersPerSecond,
    );

    _requireNonNegativeDuration(
      'proximityPulse.interval',
      proximityPulse.interval,
    );
    _requireFiniteNonNegative(
      'proximityPulse.activationDistanceMeters',
      proximityPulse.activationDistanceMeters,
    );
    if (proximityPulse.maxLocationAttempts <= 0) {
      throw ArgumentError.value(
        proximityPulse.maxLocationAttempts,
        'proximityPulse.maxLocationAttempts',
        'Must be greater than zero.',
      );
    }
    _requireNonNegativeDuration(
      'proximityPulse.transitionConfirmation.interval',
      proximityPulse.transitionConfirmation.interval,
    );
    _requireNonNegativeDuration(
      'proximityPulse.transitionConfirmation.burstDuration',
      proximityPulse.transitionConfirmation.burstDuration,
    );
    _requirePositiveDuration(
      'advanced.proximityPulse.minInterval',
      advanced.proximityPulse.minInterval,
    );
    _requireNotGreater(
      'advanced.proximityPulse.minInterval',
      advanced.proximityPulse.minInterval,
      'proximityPulse.interval',
      proximityPulse.interval,
    );
    _requireNotGreater(
      'advanced.proximityPulse.minInterval',
      advanced.proximityPulse.minInterval,
      'proximityPulse.transitionConfirmation.interval',
      proximityPulse.transitionConfirmation.interval,
    );
    _requireNotGreater(
      'proximityPulse.transitionConfirmation.interval',
      proximityPulse.transitionConfirmation.interval,
      'proximityPulse.transitionConfirmation.burstDuration',
      proximityPulse.transitionConfirmation.burstDuration,
    );
    _requireMinuteOfDay(
      'proximityPulse.activeHours.start',
      proximityPulse.activeHours.start,
    );
    _requireMinuteOfDay(
      'proximityPulse.activeHours.end',
      proximityPulse.activeHours.end,
    );
    if (proximityPulse.activeHours.outsideIntervalMultiplier <= 0) {
      throw ArgumentError.value(
        proximityPulse.activeHours.outsideIntervalMultiplier,
        'proximityPulse.activeHours.outsideIntervalMultiplier',
        'Must be greater than zero.',
      );
    }
    _requireNonNegativeDuration(
      'advanced.activity.stationaryTtl',
      activity.stationaryTtl,
    );
    _requireNonNegativeDuration(
      'advanced.activity.activityUpdateInterval',
      activity.activityUpdateInterval,
    );
    _requireNonNegativeDuration(
      'advanced.activity.movingProximityCheckDelay',
      activity.movingProximityCheckDelay,
    );
    _requirePositiveDuration(
      'advanced.activity.fusedLocationStaleAfter',
      activity.fusedLocationStaleAfter,
    );
    _requireNonEmpty(
      'foregroundNotification.title',
      foregroundNotification.title,
    );
    _requireNonEmpty(
      'foregroundNotification.channelId',
      foregroundNotification.channelId,
    );
    _requireNonEmpty(
      'foregroundNotification.channelName',
      foregroundNotification.channelName,
    );
    if (foregroundNotification.notificationId <= 0) {
      throw ArgumentError.value(
        foregroundNotification.notificationId,
        'foregroundNotification.notificationId',
        'Must be greater than zero.',
      );
    }
    if (foregroundNotification.sticky &&
        foregroundNotification.tapAction ==
            SGForegroundNotificationTapAction.dismiss) {
      throw ArgumentError.value(
        foregroundNotification.tapAction,
        'foregroundNotification.tapAction',
        'Cannot be dismiss when foregroundNotification.sticky is true.',
      );
    }
    if (recovery.timesOfDay.isEmpty) {
      throw ArgumentError.value(
        recovery.timesOfDay,
        'recovery.timesOfDay',
        'Must contain at least one local time.',
      );
    }
    if (recovery.alarmPolicy == SGAlarmSchedulePolicy.inexactWithExactGuard) {
      final guardDelay = recovery.inexactGuardDelay;
      if (guardDelay == null) {
        throw ArgumentError.value(
          guardDelay,
          'recovery.inexactGuardDelay',
          'Must be set when recovery.alarmPolicy is inexactWithExactGuard.',
        );
      }
      _requirePositiveDuration('recovery.inexactGuardDelay', guardDelay);
    } else if (recovery.inexactGuardDelay != null) {
      _requirePositiveDuration(
        'recovery.inexactGuardDelay',
        recovery.inexactGuardDelay!,
      );
    }
    final recoveryMinutes = <int>{};
    for (var index = 0; index < recovery.timesOfDay.length; index++) {
      final time = recovery.timesOfDay[index];
      _requireMinuteOfDay('recovery.timesOfDay[$index]', time);
      if (!recoveryMinutes.add(time.inMinutes)) {
        throw ArgumentError.value(
          time,
          'recovery.timesOfDay[$index]',
          'Duplicate recovery time.',
        );
      }
    }
    _requirePositiveDuration(
      'advanced.foregroundService.launchTimeout',
      foregroundServiceLaunchTimeout,
    );
    _requireNonNegativeDuration(
      'advanced.foregroundService.startDelay',
      foregroundServiceStartDelay,
    );
    _requireNonNegativeDuration(
      'advanced.foregroundService.rearmDelay',
      foregroundServiceRearmDelay,
    );
    _requireDurationRange(
      'advanced.foregroundService.callbackTimeout',
      foregroundServiceCallbackTimeout,
      const Duration(seconds: 30),
      const Duration(minutes: 2),
    );
    _requirePositiveDuration(
      'advanced.confirmQueue.maxAge',
      confirmQueueMaxAge,
    );
    if (logging.maxFileBytes < 16 * 1024 ||
        logging.maxFileBytes > 50 * 1024 * 1024) {
      throw ArgumentError.value(
        logging.maxFileBytes,
        'logging.maxFileBytes',
        'Must be between 16384 and 52428800 bytes.',
      );
    }
    final timeConfig = timeIntegrity;
    if (timeConfig != null) {
      _requireNonNegativeDuration(
        'timeIntegrity.normalThreshold',
        timeConfig.normalThreshold,
      );
      _requireNonNegativeDuration(
        'timeIntegrity.warningThreshold',
        timeConfig.warningThreshold,
      );
      _requireNonNegativeDuration(
        'timeIntegrity.blockOfflineThreshold',
        timeConfig.blockOfflineThreshold,
      );
      _requireNonNegativeDuration(
        'timeIntegrity.hardTamperThreshold',
        timeConfig.hardTamperThreshold,
      );
      _requirePositiveDuration(
        'timeIntegrity.maxOfflineAnchorAge',
        timeConfig.maxOfflineAnchorAge,
      );
      _requireNonNegativeDuration(
        'timeIntegrity.bootEstimateDriftThreshold',
        timeConfig.bootEstimateDriftThreshold,
      );
      _requireNonNegativeDuration(
        'timeIntegrity.maxSyncUncertainty',
        timeConfig.maxSyncUncertainty,
      );
      _requirePositiveDuration(
        'timeIntegrity.timeSourceTimeout',
        timeConfig.timeSourceTimeout,
      );
      _requireNonNegativeDuration(
        'timeIntegrity.syncPlausibilityThreshold',
        timeConfig.syncPlausibilityThreshold,
      );
      _requireNonNegativeDuration(
        'timeIntegrity.allowedWallClockDrift',
        timeConfig.allowedWallClockDrift,
      );
      if (!timeConfig.isOrdered) {
        throw ArgumentError.value(
          timeConfig,
          'timeIntegrity',
          'Thresholds must be non-decreasing.',
        );
      }
    }
  }

  Map<String, dynamic> toMap() => {
    'batteryMode': batteryMode.name,
    'locationUnavailablePolicy': locationUnavailablePolicy.name,
    'proximityRadiusMeters': escalation.proximity.radiusMeters,
    'escalationEnabled': escalation.enabled,
    'proximityLocationPriority': escalation.proximity.priority.name,
    'proximityIntervalMillis': escalation.proximity.interval.inMilliseconds,
    'proximityFastestIntervalMillis':
        escalation.proximity.fastestInterval.inMilliseconds,
    'proximityMaxWaitMillis': escalation.proximity.maxWait.inMilliseconds,
    'proximityMinDisplacementMeters':
        escalation.proximity.minDisplacementMeters,
    'proximityAdaptiveDisplacementEnabled':
        escalation.proximity.adaptiveDisplacement.enabled,
    'proximityAdaptiveNearBoundaryDistanceMeters':
        escalation.proximity.adaptiveDisplacement.nearBoundaryDistanceMeters,
    'proximityAdaptiveNearBoundaryDisplacementMeters': escalation
        .proximity
        .adaptiveDisplacement
        .nearBoundaryDisplacementMeters,
    'proximityAdaptiveStationaryDisplacementMeters':
        escalation.proximity.adaptiveDisplacement.stationaryDisplacementMeters,
    'proximityAdaptiveHysteresisMeters':
        escalation.proximity.adaptiveDisplacement.hysteresisMeters,
    'passiveLocationPriority': escalation.passive.priority.name,
    'passiveLocationIntervalMillis': escalation.passive.interval.inMilliseconds,
    'passiveLocationFastestIntervalMillis':
        escalation.passive.fastestInterval.inMilliseconds,
    'passiveLocationMaxWaitMillis': escalation.passive.maxWait.inMilliseconds,
    'passiveFollowUpEnabled': escalation.passive.followUpEnabled,
    'locationConfirmTimeoutMillis':
        escalation.locationConfirm.timeout.inMilliseconds,
    'pulseLocationMaxAccuracyMeters':
        escalation.locationFilter.pulseMaxAccuracyMeters,
    'eventLocationMaxAccuracyMeters':
        escalation.locationFilter.eventMaxAccuracyMeters,
    'nativeExitConfirmationEnabled': nativeEvents.confirmExits,
    'nativeEnterConfirmationEnabled': nativeEvents.confirmEnters,
    'nativeConfirmDelayMillis': nativeEvents.confirmDelay.inMilliseconds,
    'nativeConfirmMaxAttempts': nativeEvents.confirmMaxAttempts,
    'transitionValidationEnabled': transitionValidation.enabled,
    'transitionValidationEnterEnabled': transitionValidation.enterEnabled,
    'transitionValidationExitEnabled': transitionValidation.exitEnabled,
    'transitionValidationMinimumDelayMillis':
        transitionValidation.minimumDelay.inMilliseconds,
    'nativeEnterConfirmRadiusSlackMeters':
        nativeEvents.enterConfirmRadiusSlackMeters,
    'nativeEnterPayloadSanityEnabled': nativeEvents.rejectDistantEnterPayloads,
    'nativeEnterPayloadDistanceSlackMeters':
        nativeEvents.enterPayloadDistanceSlackMeters,
    'teleportGuardEnabled': escalation.teleportGuard.enabled,
    'teleportMaxSpeedMetersPerSecond':
        escalation.teleportGuard.maxSpeedMetersPerSecond,
    'mockLocationPolicy': mockLocationPolicy.name,
    'proximityPulseEnabled': proximityPulse.enabled,
    'proximityPulseActivationDistanceMeters':
        proximityPulse.activationDistanceMeters,
    'proximityPulseIntervalMillis': proximityPulse.interval.inMilliseconds,
    'proximityConfirmMaxAttempts': proximityPulse.maxLocationAttempts,
    'proximityPulseTransitionConfirmationIntervalMillis':
        proximityPulse.transitionConfirmation.interval.inMilliseconds,
    'proximityPulseTransitionConfirmationBurstDurationMillis':
        proximityPulse.transitionConfirmation.burstDuration.inMilliseconds,
    'proximityPulseActiveStartMinuteOfDay':
        proximityPulse.activeHours.start.inMinutes,
    'proximityPulseActiveEndMinuteOfDay':
        proximityPulse.activeHours.end.inMinutes,
    'proximityPulseOutsideActiveHoursIntervalMultiplier':
        proximityPulse.activeHours.outsideIntervalMultiplier,
    'proximityPulseMinIntervalMillis':
        advanced.proximityPulse.minInterval.inMilliseconds,
    'foregroundNotificationTitle': foregroundNotification.title,
    'foregroundNotificationChannelId': foregroundNotification.channelId,
    'foregroundNotificationChannelName': foregroundNotification.channelName,
    'foregroundNotificationId': foregroundNotification.notificationId,
    'foregroundNotificationSmallIconResourceName':
        foregroundNotification.smallIconResourceName,
    'foregroundNotificationSticky': foregroundNotification.sticky,
    'foregroundNotificationTapAction': foregroundNotification.tapAction.name,
    'foregroundNotificationShowWhileMonitoring':
        foregroundNotification.showWhileMonitoring,
    'activityStationaryTtlMillis': activity.stationaryTtl.inMilliseconds,
    'activityPeriodicBackstopEnabled': activity.periodicBackstopEnabled,
    'activityUpdateIntervalMillis':
        activity.activityUpdateInterval.inMilliseconds,
    'activityMovingProximityCheckDelayMillis':
        activity.movingProximityCheckDelay.inMilliseconds,
    'activityFusedLocationStaleAfterMillis':
        activity.fusedLocationStaleAfter.inMilliseconds,
    'recoveryTimesMinuteOfDay': recovery.timesOfDay
        .map((time) => time.inMinutes)
        .toList(growable: false),
    'recoveryAlarmPolicy': recovery.alarmPolicy.name,
    'recoveryInexactGuardDelayMillis':
        recovery.inexactGuardDelay?.inMilliseconds,
    'exactAlarmPermissionMode': exactAlarm.permissionMode.name,
    'passiveLocationEnabled': escalation.passive.enabled,
    'foregroundServiceLaunchTimeoutMillis':
        foregroundServiceLaunchTimeout.inMilliseconds,
    'foregroundServiceStartDelayMillis':
        foregroundServiceStartDelay.inMilliseconds,
    'foregroundServiceRearmDelayMillis':
        foregroundServiceRearmDelay.inMilliseconds,
    'foregroundServiceCallbackTimeoutMillis':
        foregroundServiceCallbackTimeout.inMilliseconds,
    'foregroundServiceSticky': foregroundServiceSticky,
    'confirmQueueMaxAgeMillis': confirmQueueMaxAge.inMilliseconds,
    'logFileEnabled': logging.fileEnabled,
    'logFileVerbose': logging.verbose,
    'maxLogFileBytes': logging.maxFileBytes,
    'retryOnCallbackFailure': retryOnCallbackFailure,
    'timeIntegrityEnabled': timeIntegrity != null,
    'timeIntegrityConfigJson': jsonEncode(
      (timeIntegrity ?? const TimeIntegrityConfig()).toJson(),
    ),
  };

  SGEscalationConfig get escalation => advanced.escalation.copyWith(
    nativeEvents: nativeEvents,
    mockLocationPolicy: mockLocationPolicy,
  );

  SGActivityConfig get activity => advanced.activity;

  SGForegroundServiceConfig get foregroundService => advanced.foregroundService;

  SGConfirmQueueConfig get confirmQueue => advanced.confirmQueue;

  Duration get foregroundServiceLaunchTimeout =>
      foregroundService.launchTimeout;

  Duration get foregroundServiceStartDelay => foregroundService.startDelay;

  Duration get foregroundServiceRearmDelay => foregroundService.rearmDelay;

  Duration get foregroundServiceCallbackTimeout =>
      foregroundService.callbackTimeout;

  bool get foregroundServiceSticky => foregroundService.sticky;

  Duration get confirmQueueMaxAge => confirmQueue.maxAge;

  double get proximityRadiusMeters => escalation.proximity.radiusMeters;

  bool get escalationEnabled => escalation.enabled;

  SGLocationPriority get proximityLocationPriority =>
      escalation.proximity.priority;

  Duration get proximityInterval => escalation.proximity.interval;

  Duration get proximityFastestInterval => escalation.proximity.fastestInterval;

  Duration get proximityMaxWait => escalation.proximity.maxWait;

  double get proximityMinDisplacementMeters =>
      escalation.proximity.minDisplacementMeters;

  SGLocationPriority get passiveLocationPriority => escalation.passive.priority;

  Duration get passiveLocationInterval => escalation.passive.interval;

  Duration get passiveLocationFastestInterval =>
      escalation.passive.fastestInterval;

  Duration get passiveLocationMaxWait => escalation.passive.maxWait;

  bool get passiveFollowUpEnabled => escalation.passive.followUpEnabled;

  Duration get locationConfirmTimeout => escalation.locationConfirm.timeout;

  bool get teleportGuardEnabled => escalation.teleportGuard.enabled;

  double get teleportMaxSpeedMetersPerSecond =>
      escalation.teleportGuard.maxSpeedMetersPerSecond;

  bool get nativeExitConfirmationEnabled => nativeEvents.confirmExits;

  bool get nativeEnterConfirmationEnabled => nativeEvents.confirmEnters;

  int get nativeConfirmMaxAttempts => nativeEvents.confirmMaxAttempts;

  Duration get nativeConfirmDelay => nativeEvents.confirmDelay;

  double get nativeEnterConfirmRadiusSlackMeters =>
      nativeEvents.enterConfirmRadiusSlackMeters;

  bool get nativeEnterPayloadSanityEnabled =>
      nativeEvents.rejectDistantEnterPayloads;

  double get nativeEnterPayloadDistanceSlackMeters =>
      nativeEvents.enterPayloadDistanceSlackMeters;

  bool get proximityPulseEnabled => proximityPulse.enabled;

  Duration get proximityPulseInterval => proximityPulse.interval;

  Duration get proximityPulseMinInterval => advanced.proximityPulse.minInterval;

  String get foregroundNotificationTitle => foregroundNotification.title;

  String get foregroundNotificationChannelId =>
      foregroundNotification.channelId;

  String get foregroundNotificationChannelName =>
      foregroundNotification.channelName;

  int get foregroundNotificationId => foregroundNotification.notificationId;

  String? get foregroundNotificationSmallIconResourceName =>
      foregroundNotification.smallIconResourceName;

  bool get foregroundNotificationSticky => foregroundNotification.sticky;

  SGForegroundNotificationTapAction get foregroundNotificationTapAction =>
      foregroundNotification.tapAction;

  bool get foregroundNotificationShowWhileMonitoring =>
      foregroundNotification.showWhileMonitoring;

  bool get passiveLocationEnabled => escalation.passive.enabled;

  bool get logFileEnabled => logging.fileEnabled;

  bool get logFileVerbose => logging.verbose;

  int get maxLogFileBytes => logging.maxFileBytes;

  @override
  String toString() =>
      'SGConfig(batteryMode: $batteryMode, '
      'locationUnavailablePolicy: $locationUnavailablePolicy, '
      'mockLocationPolicy: $mockLocationPolicy, nativeEvents: $nativeEvents, '
      'transitionValidation: $transitionValidation, '
      'proximityPulse: $proximityPulse, '
      'recovery: $recovery, exactAlarm: $exactAlarm, logging: $logging, '
      'retryOnCallbackFailure: $retryOnCallbackFailure, '
      'foregroundNotification: $foregroundNotification, '
      'timeIntegrity: $timeIntegrity, '
      'advanced: $advanced)';
}

SGAdvancedConfig _advancedPresetFor(SGBatteryMode batteryMode) =>
    switch (batteryMode) {
      SGBatteryMode.balanced => const SGAdvancedConfig(),
      SGBatteryMode.highAccuracy => const SGAdvancedConfig.highAccuracy(),
      SGBatteryMode.lowPower => const SGAdvancedConfig.lowPower(),
    };

class SGAdvancedConfig {
  final SGEscalationConfig escalation;

  final SGActivityConfig activity;

  final SGForegroundServiceConfig foregroundService;

  final SGConfirmQueueConfig confirmQueue;

  final SGProximityPulseAdvancedConfig proximityPulse;

  const SGAdvancedConfig({
    this.escalation = const SGEscalationConfig.balanced(),
    this.activity = const SGActivityConfig(),
    this.foregroundService = const SGForegroundServiceConfig(),
    this.confirmQueue = const SGConfirmQueueConfig(),
    this.proximityPulse = const SGProximityPulseAdvancedConfig(),
  });

  const SGAdvancedConfig.highAccuracy()
    : this(escalation: const SGEscalationConfig.highAccuracy());

  const SGAdvancedConfig.lowPower()
    : this(escalation: const SGEscalationConfig.lowPower());

  SGAdvancedConfig copyWith({
    SGEscalationConfig? escalation,
    SGActivityConfig? activity,
    SGForegroundServiceConfig? foregroundService,
    SGConfirmQueueConfig? confirmQueue,
    SGProximityPulseAdvancedConfig? proximityPulse,
  }) => SGAdvancedConfig(
    escalation: escalation ?? this.escalation,
    activity: activity ?? this.activity,
    foregroundService: foregroundService ?? this.foregroundService,
    confirmQueue: confirmQueue ?? this.confirmQueue,
    proximityPulse: proximityPulse ?? this.proximityPulse,
  );

  @override
  String toString() =>
      'SGAdvancedConfig(escalation: $escalation, activity: $activity, '
      'foregroundService: $foregroundService, confirmQueue: $confirmQueue, '
      'proximityPulse: $proximityPulse)';
}

class SGForegroundServiceConfig {
  final Duration launchTimeout;

  final Duration startDelay;

  final Duration rearmDelay;

  final Duration callbackTimeout;

  final bool sticky;

  const SGForegroundServiceConfig({
    this.launchTimeout = const Duration(seconds: 10),
    this.startDelay = const Duration(seconds: 1),
    this.rearmDelay = const Duration(milliseconds: 250),
    this.callbackTimeout = const Duration(minutes: 2),
    this.sticky = true,
  });

  SGForegroundServiceConfig copyWith({
    Duration? launchTimeout,
    Duration? startDelay,
    Duration? rearmDelay,
    Duration? callbackTimeout,
    bool? sticky,
  }) => SGForegroundServiceConfig(
    launchTimeout: launchTimeout ?? this.launchTimeout,
    startDelay: startDelay ?? this.startDelay,
    rearmDelay: rearmDelay ?? this.rearmDelay,
    callbackTimeout: callbackTimeout ?? this.callbackTimeout,
    sticky: sticky ?? this.sticky,
  );

  @override
  String toString() =>
      'SGForegroundServiceConfig(launchTimeout: $launchTimeout, '
      'startDelay: $startDelay, rearmDelay: $rearmDelay, '
      'callbackTimeout: $callbackTimeout, '
      'sticky: $sticky)';
}

class SGConfirmQueueConfig {
  final Duration maxAge;

  const SGConfirmQueueConfig({this.maxAge = const Duration(minutes: 7)});

  SGConfirmQueueConfig copyWith({Duration? maxAge}) =>
      SGConfirmQueueConfig(maxAge: maxAge ?? this.maxAge);

  @override
  String toString() => 'SGConfirmQueueConfig(maxAge: $maxAge)';
}

class SGProximityPulseAdvancedConfig {
  final Duration minInterval;

  const SGProximityPulseAdvancedConfig({
    this.minInterval = const Duration(seconds: 30),
  });

  SGProximityPulseAdvancedConfig copyWith({Duration? minInterval}) =>
      SGProximityPulseAdvancedConfig(
        minInterval: minInterval ?? this.minInterval,
      );

  @override
  String toString() =>
      'SGProximityPulseAdvancedConfig(minInterval: $minInterval)';
}

class SGEscalationConfig {
  final bool enabled;

  final SGProximityConfig proximity;

  final SGPassiveLocationConfig passive;

  final SGLocationConfirmConfig locationConfirm;

  final SGLocationFilterConfig locationFilter;

  final SGNativeEventFilterConfig nativeEvents;

  final SGTeleportGuardConfig teleportGuard;

  final SGMockLocationPolicy mockLocationPolicy;

  const SGEscalationConfig({
    this.enabled = true,
    this.proximity = const SGProximityConfig(),
    this.passive = const SGPassiveLocationConfig(),
    this.locationConfirm = const SGLocationConfirmConfig(),
    this.locationFilter = const SGLocationFilterConfig(),
    this.nativeEvents = const SGNativeEventFilterConfig(),
    this.teleportGuard = const SGTeleportGuardConfig(),
    this.mockLocationPolicy = SGMockLocationPolicy.logOnly,
  });

  const SGEscalationConfig.balanced() : this();

  const SGEscalationConfig.highAccuracy()
    : this(
        proximity: const SGProximityConfig(
          radiusMeters: 1500.0,
          priority: SGLocationPriority.highAccuracy,
          interval: Duration(seconds: 60),
          fastestInterval: Duration(seconds: 30),
          maxWait: Duration(seconds: 60),
          minDisplacementMeters: 75.0,
          adaptiveDisplacement: SGAdaptiveDisplacementConfig(
            nearBoundaryDistanceMeters: 400.0,
            nearBoundaryDisplacementMeters: 25.0,
            stationaryDisplacementMeters: 200.0,
            hysteresisMeters: 100.0,
          ),
        ),
        passive: const SGPassiveLocationConfig(),
        locationConfirm: const SGLocationConfirmConfig(
          timeout: Duration(seconds: 10),
        ),
      );

  const SGEscalationConfig.lowPower()
    : this(
        proximity: const SGProximityConfig(
          radiusMeters: 750.0,
          priority: SGLocationPriority.lowPower,
          interval: Duration(minutes: 5),
          fastestInterval: Duration(minutes: 2),
          maxWait: Duration(minutes: 10),
          minDisplacementMeters: 300.0,
          adaptiveDisplacement: SGAdaptiveDisplacementConfig(
            nearBoundaryDistanceMeters: 250.0,
            nearBoundaryDisplacementMeters: 150.0,
            stationaryDisplacementMeters: 750.0,
            hysteresisMeters: 100.0,
          ),
        ),
        passive: const SGPassiveLocationConfig(
          interval: Duration(minutes: 30),
          fastestInterval: Duration(minutes: 15),
          maxWait: Duration(minutes: 60),
          followUpEnabled: false,
        ),
        locationConfirm: const SGLocationConfirmConfig(
          timeout: Duration(seconds: 6),
        ),
      );

  SGEscalationConfig copyWith({
    bool? enabled,
    SGProximityConfig? proximity,
    SGPassiveLocationConfig? passive,
    SGLocationConfirmConfig? locationConfirm,
    SGLocationFilterConfig? locationFilter,
    SGNativeEventFilterConfig? nativeEvents,
    SGTeleportGuardConfig? teleportGuard,
    SGMockLocationPolicy? mockLocationPolicy,
  }) => SGEscalationConfig(
    enabled: enabled ?? this.enabled,
    proximity: proximity ?? this.proximity,
    passive: passive ?? this.passive,
    locationConfirm: locationConfirm ?? this.locationConfirm,
    locationFilter: locationFilter ?? this.locationFilter,
    nativeEvents: nativeEvents ?? this.nativeEvents,
    teleportGuard: teleportGuard ?? this.teleportGuard,
    mockLocationPolicy: mockLocationPolicy ?? this.mockLocationPolicy,
  );

  @override
  String toString() =>
      'SGEscalationConfig(enabled: $enabled, '
      'proximity: $proximity, passive: $passive, '
      'locationConfirm: $locationConfirm, locationFilter: $locationFilter, '
      'nativeEvents: $nativeEvents, teleportGuard: $teleportGuard, '
      'mockLocationPolicy: $mockLocationPolicy)';
}

class SGProximityConfig {
  final double radiusMeters;

  final SGLocationPriority priority;

  final Duration interval;

  final Duration fastestInterval;

  final Duration maxWait;

  final double minDisplacementMeters;

  final SGAdaptiveDisplacementConfig adaptiveDisplacement;

  const SGProximityConfig({
    this.radiusMeters = 1000.0,
    this.priority = SGLocationPriority.balancedPowerAccuracy,
    this.interval = const Duration(minutes: 2),
    this.fastestInterval = const Duration(minutes: 1),
    this.maxWait = const Duration(minutes: 5),
    this.minDisplacementMeters = 200.0,
    this.adaptiveDisplacement = const SGAdaptiveDisplacementConfig(),
  });

  SGProximityConfig copyWith({
    double? radiusMeters,
    SGLocationPriority? priority,
    Duration? interval,
    Duration? fastestInterval,
    Duration? maxWait,
    double? minDisplacementMeters,
    SGAdaptiveDisplacementConfig? adaptiveDisplacement,
  }) => SGProximityConfig(
    radiusMeters: radiusMeters ?? this.radiusMeters,
    priority: priority ?? this.priority,
    interval: interval ?? this.interval,
    fastestInterval: fastestInterval ?? this.fastestInterval,
    maxWait: maxWait ?? this.maxWait,
    minDisplacementMeters: minDisplacementMeters ?? this.minDisplacementMeters,
    adaptiveDisplacement: adaptiveDisplacement ?? this.adaptiveDisplacement,
  );

  @override
  String toString() =>
      'SGProximityConfig(radiusMeters: $radiusMeters, '
      'priority: $priority, interval: $interval, '
      'fastestInterval: $fastestInterval, maxWait: $maxWait, '
      'minDisplacementMeters: $minDisplacementMeters, '
      'adaptiveDisplacement: $adaptiveDisplacement)';
}

class SGAdaptiveDisplacementConfig {
  final bool enabled;

  final double nearBoundaryDistanceMeters;

  final double nearBoundaryDisplacementMeters;

  final double stationaryDisplacementMeters;

  final double hysteresisMeters;

  const SGAdaptiveDisplacementConfig({
    this.enabled = true,
    this.nearBoundaryDistanceMeters = 300.0,
    this.nearBoundaryDisplacementMeters = 75.0,
    this.stationaryDisplacementMeters = 500.0,
    this.hysteresisMeters = 100.0,
  });

  SGAdaptiveDisplacementConfig copyWith({
    bool? enabled,
    double? nearBoundaryDistanceMeters,
    double? nearBoundaryDisplacementMeters,
    double? stationaryDisplacementMeters,
    double? hysteresisMeters,
  }) => SGAdaptiveDisplacementConfig(
    enabled: enabled ?? this.enabled,
    nearBoundaryDistanceMeters:
        nearBoundaryDistanceMeters ?? this.nearBoundaryDistanceMeters,
    nearBoundaryDisplacementMeters:
        nearBoundaryDisplacementMeters ?? this.nearBoundaryDisplacementMeters,
    stationaryDisplacementMeters:
        stationaryDisplacementMeters ?? this.stationaryDisplacementMeters,
    hysteresisMeters: hysteresisMeters ?? this.hysteresisMeters,
  );

  @override
  String toString() =>
      'SGAdaptiveDisplacementConfig(enabled: $enabled, '
      'nearBoundaryDistanceMeters: $nearBoundaryDistanceMeters, '
      'nearBoundaryDisplacementMeters: $nearBoundaryDisplacementMeters, '
      'stationaryDisplacementMeters: $stationaryDisplacementMeters, '
      'hysteresisMeters: $hysteresisMeters)';
}

class SGPassiveLocationConfig {
  final bool enabled;

  final SGLocationPriority priority;

  final Duration interval;

  final Duration fastestInterval;

  final Duration maxWait;

  final bool followUpEnabled;

  const SGPassiveLocationConfig({
    this.enabled = true,
    this.priority = SGLocationPriority.passive,
    this.interval = const Duration(minutes: 20),
    this.fastestInterval = const Duration(minutes: 5),
    this.maxWait = const Duration(minutes: 40),
    this.followUpEnabled = true,
  });

  SGPassiveLocationConfig copyWith({
    bool? enabled,
    SGLocationPriority? priority,
    Duration? interval,
    Duration? fastestInterval,
    Duration? maxWait,
    bool? followUpEnabled,
  }) => SGPassiveLocationConfig(
    enabled: enabled ?? this.enabled,
    priority: priority ?? this.priority,
    interval: interval ?? this.interval,
    fastestInterval: fastestInterval ?? this.fastestInterval,
    maxWait: maxWait ?? this.maxWait,
    followUpEnabled: followUpEnabled ?? this.followUpEnabled,
  );

  @override
  String toString() =>
      'SGPassiveLocationConfig(enabled: $enabled, '
      'priority: $priority, interval: $interval, '
      'fastestInterval: $fastestInterval, maxWait: $maxWait, '
      'followUpEnabled: $followUpEnabled)';
}

class SGLocationConfirmConfig {
  final Duration timeout;

  const SGLocationConfirmConfig({this.timeout = const Duration(seconds: 8)});

  SGLocationConfirmConfig copyWith({Duration? timeout}) =>
      SGLocationConfirmConfig(timeout: timeout ?? this.timeout);

  @override
  String toString() => 'SGLocationConfirmConfig(timeout: $timeout)';
}

class SGLocationFilterConfig {
  final double pulseMaxAccuracyMeters;

  final double eventMaxAccuracyMeters;

  const SGLocationFilterConfig({
    this.pulseMaxAccuracyMeters = 300.0,
    this.eventMaxAccuracyMeters = 150.0,
  });

  SGLocationFilterConfig copyWith({
    double? pulseMaxAccuracyMeters,
    double? eventMaxAccuracyMeters,
  }) => SGLocationFilterConfig(
    pulseMaxAccuracyMeters:
        pulseMaxAccuracyMeters ?? this.pulseMaxAccuracyMeters,
    eventMaxAccuracyMeters:
        eventMaxAccuracyMeters ?? this.eventMaxAccuracyMeters,
  );

  @override
  String toString() =>
      'SGLocationFilterConfig(pulseMaxAccuracyMeters: $pulseMaxAccuracyMeters, '
      'eventMaxAccuracyMeters: $eventMaxAccuracyMeters)';
}

class SGTransitionValidationConfig {
  final bool enabled;

  final bool enterEnabled;

  final bool exitEnabled;

  final Duration minimumDelay;

  const SGTransitionValidationConfig({
    this.enabled = true,
    this.enterEnabled = true,
    this.exitEnabled = true,
    this.minimumDelay = const Duration(minutes: 2),
  });

  SGTransitionValidationConfig copyWith({
    bool? enabled,
    bool? enterEnabled,
    bool? exitEnabled,
    Duration? minimumDelay,
  }) => SGTransitionValidationConfig(
    enabled: enabled ?? this.enabled,
    enterEnabled: enterEnabled ?? this.enterEnabled,
    exitEnabled: exitEnabled ?? this.exitEnabled,
    minimumDelay: minimumDelay ?? this.minimumDelay,
  );

  @override
  String toString() =>
      'SGTransitionValidationConfig(enabled: $enabled, '
      'enterEnabled: $enterEnabled, exitEnabled: $exitEnabled, '
      'minimumDelay: $minimumDelay)';
}

class SGNativeEventFilterConfig {
  final bool confirmExits;

  final bool confirmEnters;

  final Duration confirmDelay;

  final int confirmMaxAttempts;

  final double enterConfirmRadiusSlackMeters;

  final bool rejectDistantEnterPayloads;

  final double enterPayloadDistanceSlackMeters;

  const SGNativeEventFilterConfig({
    this.confirmExits = true,
    this.confirmEnters = true,
    this.confirmDelay = const Duration(minutes: 2),
    this.confirmMaxAttempts = 3,
    this.enterConfirmRadiusSlackMeters = 300.0,
    this.rejectDistantEnterPayloads = true,
    this.enterPayloadDistanceSlackMeters = 1000.0,
  });

  SGNativeEventFilterConfig copyWith({
    bool? confirmExits,
    bool? confirmEnters,
    Duration? confirmDelay,
    int? confirmMaxAttempts,
    double? enterConfirmRadiusSlackMeters,
    bool? rejectDistantEnterPayloads,
    double? enterPayloadDistanceSlackMeters,
  }) => SGNativeEventFilterConfig(
    confirmExits: confirmExits ?? this.confirmExits,
    confirmEnters: confirmEnters ?? this.confirmEnters,
    confirmDelay: confirmDelay ?? this.confirmDelay,
    confirmMaxAttempts: confirmMaxAttempts ?? this.confirmMaxAttempts,
    enterConfirmRadiusSlackMeters:
        enterConfirmRadiusSlackMeters ?? this.enterConfirmRadiusSlackMeters,
    rejectDistantEnterPayloads:
        rejectDistantEnterPayloads ?? this.rejectDistantEnterPayloads,
    enterPayloadDistanceSlackMeters:
        enterPayloadDistanceSlackMeters ?? this.enterPayloadDistanceSlackMeters,
  );

  @override
  String toString() =>
      'SGNativeEventFilterConfig(confirmExits: $confirmExits, '
      'confirmEnters: $confirmEnters, '
      'confirmDelay: $confirmDelay, '
      'confirmMaxAttempts: $confirmMaxAttempts, '
      'enterConfirmRadiusSlackMeters: $enterConfirmRadiusSlackMeters, '
      'rejectDistantEnterPayloads: $rejectDistantEnterPayloads, '
      'enterPayloadDistanceSlackMeters: $enterPayloadDistanceSlackMeters)';
}

class SGTeleportGuardConfig {
  final bool enabled;

  final double maxSpeedMetersPerSecond;

  const SGTeleportGuardConfig({
    this.enabled = true,
    this.maxSpeedMetersPerSecond = 70.0,
  });

  SGTeleportGuardConfig copyWith({
    bool? enabled,
    double? maxSpeedMetersPerSecond,
  }) => SGTeleportGuardConfig(
    enabled: enabled ?? this.enabled,
    maxSpeedMetersPerSecond:
        maxSpeedMetersPerSecond ?? this.maxSpeedMetersPerSecond,
  );

  @override
  String toString() =>
      'SGTeleportGuardConfig(enabled: $enabled, '
      'maxSpeedMetersPerSecond: $maxSpeedMetersPerSecond)';
}

class SGProximityPulseConfig {
  final bool enabled;

  final double activationDistanceMeters;

  final Duration interval;

  final int maxLocationAttempts;

  final SGTransitionConfirmationPulseConfig transitionConfirmation;

  final SGActiveHoursConfig activeHours;

  const SGProximityPulseConfig({
    this.enabled = true,
    this.activationDistanceMeters = 1500.0,
    this.interval = const Duration(minutes: 6),
    this.maxLocationAttempts = 10,
    this.transitionConfirmation = const SGTransitionConfirmationPulseConfig(),
    this.activeHours = const SGActiveHoursConfig(),
  });

  const SGProximityPulseConfig.disabled() : this(enabled: false);

  SGProximityPulseConfig copyWith({
    bool? enabled,
    double? activationDistanceMeters,
    Duration? interval,
    int? maxLocationAttempts,
    SGTransitionConfirmationPulseConfig? transitionConfirmation,
    SGActiveHoursConfig? activeHours,
  }) => SGProximityPulseConfig(
    enabled: enabled ?? this.enabled,
    activationDistanceMeters:
        activationDistanceMeters ?? this.activationDistanceMeters,
    interval: interval ?? this.interval,
    maxLocationAttempts: maxLocationAttempts ?? this.maxLocationAttempts,
    transitionConfirmation:
        transitionConfirmation ?? this.transitionConfirmation,
    activeHours: activeHours ?? this.activeHours,
  );

  @override
  String toString() =>
      'SGProximityPulseConfig(enabled: $enabled, '
      'activationDistanceMeters: $activationDistanceMeters, interval: $interval, '
      'maxLocationAttempts: $maxLocationAttempts, '
      'transitionConfirmation: $transitionConfirmation, activeHours: $activeHours)';
}

class SGTransitionConfirmationPulseConfig {
  final Duration interval;

  final Duration burstDuration;

  const SGTransitionConfirmationPulseConfig({
    this.interval = const Duration(seconds: 90),
    this.burstDuration = const Duration(minutes: 5),
  });

  SGTransitionConfirmationPulseConfig copyWith({
    Duration? interval,
    Duration? burstDuration,
  }) => SGTransitionConfirmationPulseConfig(
    interval: interval ?? this.interval,
    burstDuration: burstDuration ?? this.burstDuration,
  );

  @override
  String toString() =>
      'SGTransitionConfirmationPulseConfig(interval: $interval, '
      'burstDuration: $burstDuration)';
}

class SGActiveHoursConfig {
  final Duration start;

  final Duration end;

  final int outsideIntervalMultiplier;

  const SGActiveHoursConfig({
    this.start = Duration.zero,
    this.end = Duration.zero,
    this.outsideIntervalMultiplier = 2,
  });

  SGActiveHoursConfig copyWith({
    Duration? start,
    Duration? end,
    int? outsideIntervalMultiplier,
  }) => SGActiveHoursConfig(
    start: start ?? this.start,
    end: end ?? this.end,
    outsideIntervalMultiplier:
        outsideIntervalMultiplier ?? this.outsideIntervalMultiplier,
  );

  @override
  String toString() =>
      'SGActiveHoursConfig(start: $start, end: $end, '
      'outsideIntervalMultiplier: $outsideIntervalMultiplier)';
}

class SGForegroundNotificationConfig {
  final String title;

  final String channelId;

  final String channelName;

  final int notificationId;

  final String? smallIconResourceName;

  final bool sticky;

  final SGForegroundNotificationTapAction tapAction;

  final bool showWhileMonitoring;

  const SGForegroundNotificationConfig({
    this.title = 'Checking nearby geofence',
    this.channelId = 'smart_geofence_foreground',
    this.channelName = 'Geofence monitoring',
    this.notificationId = 393939,
    this.smallIconResourceName,
    this.sticky = false,
    this.tapAction = SGForegroundNotificationTapAction.openApp,
    this.showWhileMonitoring = false,
  });

  SGForegroundNotificationConfig copyWith({
    String? title,
    String? channelId,
    String? channelName,
    int? notificationId,
    String? smallIconResourceName,
    bool clearSmallIconResourceName = false,
    bool? sticky,
    SGForegroundNotificationTapAction? tapAction,
    bool? showWhileMonitoring,
  }) {
    if (clearSmallIconResourceName && smallIconResourceName != null) {
      throw ArgumentError(
        'Provide either smallIconResourceName or '
        'clearSmallIconResourceName, not both.',
      );
    }
    return SGForegroundNotificationConfig(
      title: title ?? this.title,
      channelId: channelId ?? this.channelId,
      channelName: channelName ?? this.channelName,
      notificationId: notificationId ?? this.notificationId,
      smallIconResourceName: clearSmallIconResourceName
          ? null
          : smallIconResourceName ?? this.smallIconResourceName,
      sticky: sticky ?? this.sticky,
      tapAction: tapAction ?? this.tapAction,
      showWhileMonitoring: showWhileMonitoring ?? this.showWhileMonitoring,
    );
  }

  @override
  String toString() =>
      'SGForegroundNotificationConfig(title: $title, '
      'channelId: $channelId, channelName: $channelName, '
      'notificationId: $notificationId, '
      'smallIconResourceName: $smallIconResourceName, '
      'sticky: $sticky, tapAction: $tapAction, '
      'showWhileMonitoring: $showWhileMonitoring)';
}

class SGActivityConfig {
  final Duration stationaryTtl;

  final bool periodicBackstopEnabled;

  final Duration activityUpdateInterval;

  final Duration movingProximityCheckDelay;

  final Duration fusedLocationStaleAfter;

  const SGActivityConfig({
    this.stationaryTtl = const Duration(minutes: 15),
    this.periodicBackstopEnabled = false,
    this.activityUpdateInterval = const Duration(minutes: 5),
    this.movingProximityCheckDelay = const Duration(seconds: 60),
    this.fusedLocationStaleAfter = const Duration(minutes: 10),
  });

  SGActivityConfig copyWith({
    Duration? stationaryTtl,
    bool? periodicBackstopEnabled,
    Duration? activityUpdateInterval,
    Duration? movingProximityCheckDelay,
    Duration? fusedLocationStaleAfter,
  }) => SGActivityConfig(
    stationaryTtl: stationaryTtl ?? this.stationaryTtl,
    periodicBackstopEnabled:
        periodicBackstopEnabled ?? this.periodicBackstopEnabled,
    activityUpdateInterval:
        activityUpdateInterval ?? this.activityUpdateInterval,
    movingProximityCheckDelay:
        movingProximityCheckDelay ?? this.movingProximityCheckDelay,
    fusedLocationStaleAfter:
        fusedLocationStaleAfter ?? this.fusedLocationStaleAfter,
  );

  @override
  String toString() =>
      'SGActivityConfig(stationaryTtl: $stationaryTtl, '
      'periodicBackstopEnabled: $periodicBackstopEnabled, '
      'activityUpdateInterval: $activityUpdateInterval, '
      'movingProximityCheckDelay: $movingProximityCheckDelay, '
      'fusedLocationStaleAfter: $fusedLocationStaleAfter)';
}

class SGRecoveryConfig {
  final List<Duration> timesOfDay;

  final SGAlarmSchedulePolicy alarmPolicy;

  final Duration? inexactGuardDelay;

  factory SGRecoveryConfig({
    List<Duration> timesOfDay = const <Duration>[Duration(hours: 2)],
    SGAlarmSchedulePolicy alarmPolicy =
        SGAlarmSchedulePolicy.exactWithInexactFallback,
    Duration? inexactGuardDelay,
  }) => SGRecoveryConfig._(
    timesOfDay: List<Duration>.unmodifiable(timesOfDay),
    alarmPolicy: alarmPolicy,
    inexactGuardDelay: inexactGuardDelay,
  );

  const SGRecoveryConfig._({
    required this.timesOfDay,
    required this.alarmPolicy,
    required this.inexactGuardDelay,
  });

  const SGRecoveryConfig._defaults()
    : this._(
        timesOfDay: const <Duration>[Duration(hours: 2)],
        alarmPolicy: SGAlarmSchedulePolicy.exactWithInexactFallback,
        inexactGuardDelay: null,
      );

  SGRecoveryConfig copyWith({
    List<Duration>? timesOfDay,
    SGAlarmSchedulePolicy? alarmPolicy,
    Duration? inexactGuardDelay,
    bool clearInexactGuardDelay = false,
  }) {
    if (clearInexactGuardDelay && inexactGuardDelay != null) {
      throw ArgumentError(
        'Provide either inexactGuardDelay or clearInexactGuardDelay, not both.',
      );
    }
    return SGRecoveryConfig(
      timesOfDay: timesOfDay ?? this.timesOfDay,
      alarmPolicy: alarmPolicy ?? this.alarmPolicy,
      inexactGuardDelay: clearInexactGuardDelay
          ? null
          : inexactGuardDelay ?? this.inexactGuardDelay,
    );
  }

  @override
  String toString() =>
      'SGRecoveryConfig(timesOfDay: $timesOfDay, '
      'alarmPolicy: $alarmPolicy, '
      'inexactGuardDelay: $inexactGuardDelay)';
}

class SGExactAlarmConfig {
  final SGExactAlarmPermissionMode permissionMode;

  const SGExactAlarmConfig({
    this.permissionMode = SGExactAlarmPermissionMode.bestEffort,
  });

  SGExactAlarmConfig copyWith({SGExactAlarmPermissionMode? permissionMode}) =>
      SGExactAlarmConfig(permissionMode: permissionMode ?? this.permissionMode);

  @override
  String toString() => 'SGExactAlarmConfig(permissionMode: $permissionMode)';
}

class SGLogConfig {
  final bool fileEnabled;

  final bool verbose;

  final int maxFileBytes;

  const SGLogConfig({
    this.fileEnabled = false,
    this.verbose = false,
    this.maxFileBytes = 5 * 1024 * 1024,
  });

  SGLogConfig copyWith({bool? fileEnabled, bool? verbose, int? maxFileBytes}) =>
      SGLogConfig(
        fileEnabled: fileEnabled ?? this.fileEnabled,
        verbose: verbose ?? this.verbose,
        maxFileBytes: maxFileBytes ?? this.maxFileBytes,
      );

  @override
  String toString() =>
      'SGLogConfig(fileEnabled: $fileEnabled, '
      'verbose: $verbose, '
      'maxFileBytes: $maxFileBytes)';
}

void _requireFiniteNonNegative(String name, double value) {
  if (!value.isFinite || value < 0) {
    throw ArgumentError.value(value, name, 'Must be finite and non-negative.');
  }
}

void _requireFinitePositive(String name, double value) {
  if (!value.isFinite || value <= 0) {
    throw ArgumentError.value(
      value,
      name,
      'Must be finite and greater than zero.',
    );
  }
}

void _requirePositiveDuration(String name, Duration value) {
  if (value <= Duration.zero) {
    throw ArgumentError.value(value, name, 'Must be greater than zero.');
  }
}

void _requireNonNegativeDuration(String name, Duration value) {
  if (value < Duration.zero) {
    throw ArgumentError.value(value, name, 'Must be non-negative.');
  }
}

void _requireDurationRange(
  String name,
  Duration value,
  Duration minimum,
  Duration maximum,
) {
  if (value < minimum || value > maximum) {
    throw ArgumentError.value(
      value,
      name,
      'Must be between $minimum and $maximum.',
    );
  }
}

void _requireMinuteOfDay(String name, Duration value) {
  const day = Duration(days: 1);
  if (value < Duration.zero ||
      value >= day ||
      value.inMicroseconds % Duration.microsecondsPerMinute != 0) {
    throw ArgumentError.value(
      value,
      name,
      'Must be a whole-minute offset from 00:00 up to 23:59.',
    );
  }
}

void _requireNotGreater(
  String lowerName,
  Duration lower,
  String upperName,
  Duration upper,
) {
  if (lower > upper) {
    throw ArgumentError.value(lower, lowerName, 'Must not exceed $upperName.');
  }
}

void _requireDoubleNotGreater(
  String lowerName,
  double lower,
  String upperName,
  double upper,
) {
  if (lower > upper) {
    throw ArgumentError.value(lower, lowerName, 'Must not exceed $upperName.');
  }
}

void _requireNonEmpty(String name, String value) {
  if (value.trim().isEmpty) {
    throw ArgumentError.value(value, name, 'Must not be empty.');
  }
}

bool _bool(Map<Object?, Object?> map, String key, bool fallback) {
  final value = map[key];
  return value is bool ? value : fallback;
}

bool _foregroundNotificationShowWhileMonitoring(
  Map<Object?, Object?> map,
  bool fallback,
) {
  final value = map['foregroundNotificationShowWhileMonitoring'];
  if (value is bool) return value;

  final legacyRemoveWhenIdle = map['foregroundNotificationRemoveWhenIdle'];
  if (legacyRemoveWhenIdle is bool) return !legacyRemoveWhenIdle;

  return fallback;
}

int _int(Map<Object?, Object?> map, String key, int fallback) {
  final value = map[key];
  return value is num ? value.toInt() : fallback;
}

int _durationMillis({
  required Map<Object?, Object?> map,
  required String millisecondsKey,
  required String legacyKey,
  required int legacyUnitMillis,
  required Duration defaultValue,
}) {
  final milliseconds = map[millisecondsKey];
  if (milliseconds is num) return milliseconds.toInt();
  final legacyValue = map[legacyKey];
  if (legacyValue is num) return legacyValue.toInt() * legacyUnitMillis;
  return defaultValue.inMilliseconds;
}

double _double(Map<Object?, Object?> map, String key, double fallback) {
  final value = map[key];
  return value is num ? value.toDouble() : fallback;
}

String? _stringOrNull(Map<Object?, Object?> map, String key) {
  final value = map[key];
  return value is String ? value : null;
}

String _string(Map<Object?, Object?> map, String key, String fallback) =>
    _stringOrNull(map, key) ?? fallback;

SGLocationPriority _priority(
  Map<Object?, Object?> map,
  String key,
  SGLocationPriority fallback,
) {
  final name = _stringOrNull(map, key);
  if (name == null) return fallback;
  for (final value in SGLocationPriority.values) {
    if (value.name == name) return value;
  }
  return fallback;
}

SGBatteryMode _batteryMode(
  Map<Object?, Object?> map,
  String key,
  SGBatteryMode fallback,
) {
  final name = _stringOrNull(map, key);
  if (name == null) return fallback;
  for (final value in SGBatteryMode.values) {
    if (value.name == name) return value;
  }
  return fallback;
}

SGLocationUnavailablePolicy _locationUnavailablePolicy(
  Map<Object?, Object?> map,
  String key,
  SGLocationUnavailablePolicy fallback,
) {
  final name = _stringOrNull(map, key);
  if (name == null) return fallback;
  for (final value in SGLocationUnavailablePolicy.values) {
    if (value.name == name) return value;
  }
  return fallback;
}

Duration? _optionalDurationMillis(Map<Object?, Object?> map, String key) {
  final value = map[key];
  return value is num ? Duration(milliseconds: value.toInt()) : null;
}

SGAlarmSchedulePolicy _alarmPolicy(
  Map<Object?, Object?> map,
  String key,
  SGAlarmSchedulePolicy fallback,
) {
  final name = _stringOrNull(map, key);
  if (name == null) return fallback;
  for (final value in SGAlarmSchedulePolicy.values) {
    if (value.name == name) return value;
  }
  return fallback;
}

SGExactAlarmPermissionMode _exactAlarmPermissionMode(
  Map<Object?, Object?> map,
  String key,
  SGExactAlarmPermissionMode fallback,
) {
  final name = _stringOrNull(map, key);
  if (name == null) return fallback;
  for (final value in SGExactAlarmPermissionMode.values) {
    if (value.name == name) return value;
  }
  return fallback;
}

TimeIntegrityConfig? _timeIntegrityFromMap(Map<Object?, Object?> map) {
  if (!_bool(map, 'timeIntegrityEnabled', false)) return null;
  final configJson = _stringOrNull(map, 'timeIntegrityConfigJson');
  if (configJson != null && configJson.isNotEmpty) {
    try {
      final decoded = jsonDecode(configJson);
      if (decoded is Map) {
        return TimeIntegrityConfig.fromJson(decoded.cast<String, dynamic>());
      }
    } catch (_) {}
  }
  return const TimeIntegrityConfig();
}

SGMockLocationPolicy _mockLocationPolicy(
  Map<Object?, Object?> map,
  String key,
  SGMockLocationPolicy fallback,
) {
  final name = _stringOrNull(map, key);
  if (name == null) return fallback;
  for (final value in SGMockLocationPolicy.values) {
    if (value.name == name) return value;
  }
  return fallback;
}

bool _foregroundNotificationSticky(Map<Object?, Object?> map, bool fallback) {
  final sticky = map['foregroundNotificationSticky'];
  if (sticky is bool) return sticky;

  final legacyOngoing = map['foregroundNotificationOngoing'];
  if (legacyOngoing is bool) return legacyOngoing;

  final legacyBehavior = _stringOrNull(map, 'foregroundNotificationBehavior');
  if (legacyBehavior == 'sticky') return true;
  if (legacyBehavior == 'dismissibleReasserting') return false;

  return fallback;
}

SGForegroundNotificationTapAction _foregroundNotificationTapAction(
  Map<Object?, Object?> map,
  String key,
  SGForegroundNotificationTapAction fallback,
) {
  final name = _stringOrNull(map, key);
  if (name != null) {
    for (final value in SGForegroundNotificationTapAction.values) {
      if (value.name == name) return value;
    }
  }

  final legacyAutoCancel = map['foregroundNotificationAutoCancel'];
  if (legacyAutoCancel == true) {
    return SGForegroundNotificationTapAction.dismiss;
  }

  return fallback;
}

List<Duration> _minuteOfDayList(
  Map<Object?, Object?> map,
  String key,
  List<Duration> fallback,
) {
  final value = map[key];
  if (value is! List) return fallback;
  final minutes = <int>{};
  for (final item in value) {
    if (item is num && item >= 0 && item < Duration.minutesPerDay) {
      minutes.add(item.toInt());
    }
  }
  if (minutes.isEmpty) return fallback;
  final sorted = minutes.toList()..sort();
  return List<Duration>.unmodifiable(
    sorted.map((minute) => Duration(minutes: minute)),
  );
}
