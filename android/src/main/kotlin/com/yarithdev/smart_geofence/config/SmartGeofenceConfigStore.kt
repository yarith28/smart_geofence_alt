package com.yarithdev.smart_geofence.config

import android.content.Context
import android.content.SharedPreferences
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionMode
import com.yarithdev.smart_geofence.core.Constants
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SmartGeofenceConfig(
    val batteryMode: String,
    val locationUnavailablePolicy: LocationUnavailablePolicy,
    val proximityRadiusMeters: Double,
    val escalationEnabled: Boolean,
    val proximityLocationPriority: String,
    val proximityIntervalMillis: Long,
    val proximityFastestIntervalMillis: Long,
    val proximityMaxWaitMillis: Long,
    val proximityMinDisplacementMeters: Double,
    val proximityAdaptiveDisplacementEnabled: Boolean,
    val proximityAdaptiveNearBoundaryDistanceMeters: Double,
    val proximityAdaptiveNearBoundaryDisplacementMeters: Double,
    val proximityAdaptiveStationaryDisplacementMeters: Double,
    val proximityAdaptiveHysteresisMeters: Double,
    val passiveLocationPriority: String,
    val passiveLocationIntervalMillis: Long,
    val passiveLocationFastestIntervalMillis: Long,
    val passiveLocationMaxWaitMillis: Long,
    val passiveFollowUpEnabled: Boolean,
    val locationConfirmTimeoutMillis: Long,
    val pulseLocationMaxAccuracyMeters: Double,
    // Retained wire name: this is the OUTSIDE event-accuracy limit.
    val eventLocationMaxAccuracyMeters: Double,
    val insideEventLocationMaxAccuracyMeters: Double,
    val nativeExitConfirmationEnabled: Boolean,
    val nativeEnterConfirmationEnabled: Boolean,
    val nativeConfirmDelayMillis: Long,
    val nativeConfirmMaxAttempts: Int,
    val transitionValidationEnabled: Boolean,
    val transitionValidationEnterEnabled: Boolean,
    val transitionValidationExitEnabled: Boolean,
    val transitionValidationMinimumDelayMillis: Long,
    val nativeEnterConfirmRadiusSlackMeters: Double,
    val nativeEnterPayloadSanityEnabled: Boolean,
    val nativeEnterPayloadDistanceSlackMeters: Double,
    val teleportGuardEnabled: Boolean,
    val teleportMaxSpeedMetersPerSecond: Double,
    val mockLocationPolicy: MockLocationPolicy,
    val proximityPulseEnabled: Boolean,
    val proximityPulseActivationDistanceMeters: Double,
    val proximityPulseIntervalMillis: Long,
    val proximityPulseNearFenceDistanceMeters: Double,
    val proximityPulseNearFenceIntervalMillis: Long,
    val proximityConfirmMaxAttempts: Int,
    val proximityPulseTransitionConfirmationIntervalMillis: Long,
    val proximityPulseTransitionConfirmationBurstDurationMillis: Long,
    val proximityPulseActiveStartMinuteOfDay: Int,
    val proximityPulseActiveEndMinuteOfDay: Int,
    val proximityPulseOutsideActiveHoursIntervalMultiplier: Int,
    val proximityPulseMinIntervalMillis: Long,
    val foregroundNotificationTitle: String,
    val foregroundNotificationChannelId: String,
    val foregroundNotificationChannelName: String,
    val foregroundNotificationId: Int,
    val foregroundNotificationSmallIconResourceName: String?,
    val foregroundNotificationSticky: Boolean,
    val foregroundNotificationTapAction: ForegroundNotificationTapAction,
    val foregroundNotificationShowWhileMonitoring: Boolean,
    val activityStationaryTtlMillis: Long,
    val activityPeriodicBackstopEnabled: Boolean,
    val activityUpdateIntervalMillis: Long,
    val activityMovingProximityCheckDelayMillis: Long,
    val activityFusedLocationStaleAfterMillis: Long,
    val recoveryTimesMinuteOfDay: List<Int>,
    val recoveryAlarmPolicy: AlarmSchedulePolicy,
    val recoveryInexactGuardDelayMillis: Long?,
    val exactAlarmPermissionMode: ExactAlarmPermissionMode,
    val logFileEnabled: Boolean,
    val logFileVerbose: Boolean,
    val maxLogFileBytes: Int,
    val retryOnCallbackFailure: Boolean,
    val passiveLocationEnabled: Boolean,
    val foregroundServiceLaunchTimeoutMillis: Long,
    val foregroundServiceStartDelayMillis: Long,
    val foregroundServiceRearmDelayMillis: Long,
    val foregroundServiceCallbackTimeoutMillis: Long,
    val foregroundServiceSticky: Boolean,
    val confirmQueueMaxAgeMillis: Long,
    val timeIntegrityEnabled: Boolean,
    val timeIntegrityConfigJson: String,
) {
    internal fun validateTransitionConfiguration(): SmartGeofenceConfig {
        require(
            eventLocationMaxAccuracyMeters > 0.0 &&
                eventLocationMaxAccuracyMeters.isFinite()
        ) {
            "eventLocationMaxAccuracyMeters must be finite and greater than zero."
        }
        require(
            insideEventLocationMaxAccuracyMeters > 0.0 &&
                insideEventLocationMaxAccuracyMeters.isFinite()
        ) {
            "insideEventLocationMaxAccuracyMeters must be finite and greater than zero."
        }
        require(nativeConfirmDelayMillis >= 0L) {
            "nativeConfirmDelayMillis must be non-negative."
        }
        require(transitionValidationMinimumDelayMillis >= 0L) {
            "transitionValidationMinimumDelayMillis must be non-negative."
        }
        require(proximityPulseActivationDistanceMeters >= 0.0 &&
            proximityPulseActivationDistanceMeters.isFinite()
        ) {
            "proximityPulseActivationDistanceMeters must be finite and non-negative."
        }
        require(proximityPulseIntervalMillis >= 0L) {
            "proximityPulseIntervalMillis must be non-negative."
        }
        require(
            proximityPulseNearFenceDistanceMeters >= 0.0 &&
                proximityPulseNearFenceDistanceMeters.isFinite()
        ) {
            "proximityPulseNearFenceDistanceMeters must be finite and non-negative."
        }
        require(proximityPulseNearFenceIntervalMillis >= 0L) {
            "proximityPulseNearFenceIntervalMillis must be non-negative."
        }
        require(proximityConfirmMaxAttempts > 0) {
            "proximityConfirmMaxAttempts must be greater than zero."
        }
        require(proximityPulseMinIntervalMillis >= 0L) {
            "proximityPulseMinIntervalMillis must be non-negative."
        }
        require(proximityPulseTransitionConfirmationIntervalMillis >= 0L) {
            "proximityPulseTransitionConfirmationIntervalMillis must be non-negative."
        }
        require(proximityPulseTransitionConfirmationBurstDurationMillis >= 0L) {
            "proximityPulseTransitionConfirmationBurstDurationMillis must be non-negative."
        }
        require(
            proximityPulseTransitionConfirmationIntervalMillis <=
                proximityPulseTransitionConfirmationBurstDurationMillis
        ) {
            "Transition-confirmation interval must not exceed its burst duration."
        }
        require(proximityPulseOutsideActiveHoursIntervalMultiplier > 0) {
            "proximityPulseOutsideActiveHoursIntervalMultiplier must be greater than zero."
        }
        require(
            !transitionValidationEnabled ||
                !transitionValidationEnterEnabled ||
                nativeEnterConfirmationEnabled
        ) {
            "transitionValidationEnterEnabled requires nativeEnterConfirmationEnabled."
        }
        require(
            !transitionValidationEnabled ||
                !transitionValidationExitEnabled ||
                nativeExitConfirmationEnabled
        ) {
            "transitionValidationExitEnabled requires nativeExitConfirmationEnabled."
        }
        return this
    }

    fun shouldRemoveForegroundNotificationWhenIdle(otherForegroundServiceRunning: Boolean): Boolean =
        !otherForegroundServiceRunning && !foregroundNotificationShowWhileMonitoring

    companion object {
        fun default(): SmartGeofenceConfig = SmartGeofenceConfig(
            batteryMode = Constants.BATTERY_MODE_BALANCED,
            locationUnavailablePolicy = LocationUnavailablePolicy.Default,
            proximityRadiusMeters = Constants.DEFAULT_PROXIMITY_RADIUS_METERS,
            escalationEnabled = true,
            proximityLocationPriority = Constants.DEFAULT_LOCATION_PRIORITY_BALANCED_POWER_ACCURACY,
            proximityIntervalMillis = Constants.DEFAULT_PROXIMITY_INTERVAL_MILLIS,
            proximityFastestIntervalMillis = Constants.DEFAULT_PROXIMITY_FASTEST_INTERVAL_MILLIS,
            proximityMaxWaitMillis = Constants.DEFAULT_PROXIMITY_MAX_WAIT_MILLIS,
            proximityMinDisplacementMeters = Constants.DEFAULT_PROXIMITY_MIN_DISPLACEMENT_METERS,
            proximityAdaptiveDisplacementEnabled =
                Constants.DEFAULT_PROXIMITY_ADAPTIVE_DISPLACEMENT_ENABLED,
            proximityAdaptiveNearBoundaryDistanceMeters =
                Constants.DEFAULT_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISTANCE_METERS,
            proximityAdaptiveNearBoundaryDisplacementMeters =
                Constants.DEFAULT_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISPLACEMENT_METERS,
            proximityAdaptiveStationaryDisplacementMeters =
                Constants.DEFAULT_PROXIMITY_ADAPTIVE_STATIONARY_DISPLACEMENT_METERS,
            proximityAdaptiveHysteresisMeters =
                Constants.DEFAULT_PROXIMITY_ADAPTIVE_HYSTERESIS_METERS,
            passiveLocationPriority = Constants.DEFAULT_LOCATION_PRIORITY_PASSIVE,
            passiveLocationIntervalMillis = Constants.DEFAULT_PASSIVE_LOCATION_INTERVAL_MILLIS,
            passiveLocationFastestIntervalMillis =
                Constants.DEFAULT_PASSIVE_LOCATION_FASTEST_INTERVAL_MILLIS,
            passiveLocationMaxWaitMillis = Constants.DEFAULT_PASSIVE_LOCATION_MAX_WAIT_MILLIS,
            passiveFollowUpEnabled = Constants.DEFAULT_PASSIVE_FOLLOW_UP_ENABLED,
            locationConfirmTimeoutMillis = Constants.DEFAULT_LOCATION_CONFIRM_TIMEOUT_MILLIS,
            pulseLocationMaxAccuracyMeters =
                Constants.DEFAULT_PULSE_LOCATION_MAX_ACCURACY_METERS,
            eventLocationMaxAccuracyMeters =
                Constants.DEFAULT_EVENT_LOCATION_MAX_ACCURACY_METERS,
            insideEventLocationMaxAccuracyMeters =
                Constants.DEFAULT_INSIDE_EVENT_LOCATION_MAX_ACCURACY_METERS,
            nativeExitConfirmationEnabled =
                Constants.DEFAULT_NATIVE_EXIT_CONFIRMATION_ENABLED,
            nativeEnterConfirmationEnabled =
                Constants.DEFAULT_NATIVE_ENTER_CONFIRMATION_ENABLED,
            nativeConfirmDelayMillis = Constants.DEFAULT_NATIVE_CONFIRM_DELAY_MILLIS,
            nativeConfirmMaxAttempts =
                Constants.DEFAULT_NATIVE_CONFIRM_MAX_ATTEMPTS,
            transitionValidationEnabled = Constants.DEFAULT_TRANSITION_VALIDATION_ENABLED,
            transitionValidationEnterEnabled =
                Constants.DEFAULT_TRANSITION_VALIDATION_ENTER_ENABLED,
            transitionValidationExitEnabled =
                Constants.DEFAULT_TRANSITION_VALIDATION_EXIT_ENABLED,
            transitionValidationMinimumDelayMillis =
                Constants.DEFAULT_TRANSITION_VALIDATION_MINIMUM_DELAY_MILLIS,
            nativeEnterConfirmRadiusSlackMeters =
                Constants.DEFAULT_NATIVE_ENTER_CONFIRM_RADIUS_SLACK_METERS,
            nativeEnterPayloadSanityEnabled =
                Constants.DEFAULT_NATIVE_ENTER_PAYLOAD_SANITY_ENABLED,
            nativeEnterPayloadDistanceSlackMeters =
                Constants.DEFAULT_NATIVE_ENTER_PAYLOAD_DISTANCE_SLACK_METERS,
            teleportGuardEnabled = Constants.DEFAULT_TELEPORT_GUARD_ENABLED,
            teleportMaxSpeedMetersPerSecond = Constants.DEFAULT_TELEPORT_MAX_SPEED_MPS,
            mockLocationPolicy = MockLocationPolicy.Default,
            proximityPulseEnabled = Constants.DEFAULT_PROXIMITY_PULSE_ENABLED,
            proximityPulseActivationDistanceMeters =
                Constants.DEFAULT_PROXIMITY_PULSE_ACTIVATION_DISTANCE_METERS,
            proximityPulseIntervalMillis =
                Constants.DEFAULT_PROXIMITY_PULSE_INTERVAL_SECONDS * 1_000L,
            proximityPulseNearFenceDistanceMeters =
                Constants.DEFAULT_PROXIMITY_PULSE_NEAR_FENCE_DISTANCE_METERS,
            proximityPulseNearFenceIntervalMillis =
                Constants.DEFAULT_PROXIMITY_PULSE_NEAR_FENCE_INTERVAL_MILLIS,
            proximityConfirmMaxAttempts =
                Constants.DEFAULT_PROXIMITY_CONFIRM_MAX_ATTEMPTS,
            proximityPulseTransitionConfirmationIntervalMillis =
                Constants.DEFAULT_PROXIMITY_PULSE_TRANSITION_CONFIRMATION_INTERVAL_MILLIS,
            proximityPulseTransitionConfirmationBurstDurationMillis =
                Constants.DEFAULT_PROXIMITY_PULSE_TRANSITION_CONFIRMATION_BURST_DURATION_MILLIS,
            proximityPulseActiveStartMinuteOfDay =
                Constants.DEFAULT_PROXIMITY_PULSE_ACTIVE_START_MINUTE_OF_DAY,
            proximityPulseActiveEndMinuteOfDay =
                Constants.DEFAULT_PROXIMITY_PULSE_ACTIVE_END_MINUTE_OF_DAY,
            proximityPulseOutsideActiveHoursIntervalMultiplier =
                Constants.DEFAULT_PROXIMITY_PULSE_OUTSIDE_ACTIVE_HOURS_INTERVAL_MULTIPLIER,
            proximityPulseMinIntervalMillis = Constants.DEFAULT_PROXIMITY_PULSE_MIN_INTERVAL_MILLIS,
            foregroundNotificationTitle = Constants.DEFAULT_FOREGROUND_NOTIFICATION_TITLE,
            foregroundNotificationChannelId = Constants.DEFAULT_FOREGROUND_NOTIFICATION_CHANNEL_ID,
            foregroundNotificationChannelName =
                Constants.DEFAULT_FOREGROUND_NOTIFICATION_CHANNEL_NAME,
            foregroundNotificationId = Constants.DEFAULT_FOREGROUND_NOTIFICATION_ID,
            foregroundNotificationSmallIconResourceName = null,
            foregroundNotificationSticky = false,
            foregroundNotificationTapAction = ForegroundNotificationTapAction.Default,
            foregroundNotificationShowWhileMonitoring =
                Constants.DEFAULT_FOREGROUND_NOTIFICATION_SHOW_WHILE_MONITORING,
            activityStationaryTtlMillis = Constants.DEFAULT_ACTIVITY_STATIONARY_TTL_MILLIS,
            activityPeriodicBackstopEnabled =
                Constants.DEFAULT_ACTIVITY_PERIODIC_BACKSTOP_ENABLED,
            activityUpdateIntervalMillis =
                Constants.DEFAULT_ACTIVITY_UPDATE_INTERVAL_MILLIS,
            activityMovingProximityCheckDelayMillis =
                Constants.DEFAULT_ACTIVITY_MOVING_PROXIMITY_CHECK_DELAY_MILLIS,
            activityFusedLocationStaleAfterMillis =
                Constants.DEFAULT_ACTIVITY_FUSED_LOCATION_STALE_AFTER_MILLIS,
            recoveryTimesMinuteOfDay = listOf(Constants.DEFAULT_RECOVERY_TIME_MINUTE_OF_DAY),
            recoveryAlarmPolicy = AlarmSchedulePolicy.ExactWithInexactFallback,
            recoveryInexactGuardDelayMillis = null,
            exactAlarmPermissionMode = ExactAlarmPermissionMode.Default,
            logFileEnabled = false,
            logFileVerbose = Constants.DEFAULT_LOG_FILE_VERBOSE,
            maxLogFileBytes = Constants.DEFAULT_LOG_FILE_MAX_BYTES,
            retryOnCallbackFailure = Constants.DEFAULT_RETRY_ON_CALLBACK_FAILURE,
            passiveLocationEnabled = Constants.DEFAULT_PASSIVE_LOCATION_ENABLED,
            foregroundServiceLaunchTimeoutMillis =
                Constants.DEFAULT_FOREGROUND_SERVICE_LAUNCH_TIMEOUT_MILLIS,
            foregroundServiceStartDelayMillis =
                Constants.DEFAULT_FOREGROUND_SERVICE_START_DELAY_MILLIS,
            foregroundServiceRearmDelayMillis =
                Constants.DEFAULT_FOREGROUND_SERVICE_REARM_DELAY_MILLIS,
            foregroundServiceCallbackTimeoutMillis =
                Constants.DEFAULT_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS,
            foregroundServiceSticky = Constants.DEFAULT_FOREGROUND_SERVICE_STICKY,
            confirmQueueMaxAgeMillis = Constants.DEFAULT_CONFIRM_QUEUE_MAX_AGE_MILLIS,
            timeIntegrityEnabled = Constants.DEFAULT_TIME_INTEGRITY_ENABLED,
            timeIntegrityConfigJson = Constants.DEFAULT_TIME_INTEGRITY_CONFIG_JSON,
        )
    }
}

object SmartGeofenceConfigStore {
    internal const val PERSISTED_CONFIG_DOCUMENT_KEY =
        "smart_geofence_config_document"
    internal const val CONFIG_APPLIED_AT_MILLIS_KEY =
        "smart_geofence_config_applied_at_millis"
    internal const val CONFIG_FINGERPRINT_KEY =
        "smart_geofence_config_fingerprint"

    fun normalizeBatteryMode(value: String?, defaultValue: String): String =
        when (value) {
            Constants.BATTERY_MODE_BALANCED,
            Constants.BATTERY_MODE_HIGH_ACCURACY,
            Constants.BATTERY_MODE_LOW_POWER -> value
            else -> defaultValue
        }

    fun save(context: Context, config: SmartGeofenceConfig) {
        config.validateTransitionConfiguration()
        val prefs = context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val document = SmartGeofenceConfigCodec.encode(config)
        val committed = prefs.edit()
            .putConfigDocument(config, document)
            .putLong(CONFIG_APPLIED_AT_MILLIS_KEY, System.currentTimeMillis())
            .putString(CONFIG_FINGERPRINT_KEY, fingerprint(document))
            .commit()
        check(committed) { "Failed to persist smart-geofence configuration." }
        LegacySmartGeofenceConfigMigrator.removeLegacyValues(prefs)
    }

    fun load(context: Context): SmartGeofenceConfig {
        val prefs = context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(PERSISTED_CONFIG_DOCUMENT_KEY)) {
            val document = runCatching {
                prefs.getString(PERSISTED_CONFIG_DOCUMENT_KEY, null)
            }.getOrNull()
            return document
                ?.let { runCatching { SmartGeofenceConfigCodec.decode(it) }.getOrNull() }
                ?: LegacySmartGeofenceConfigMigrator.read(prefs)
        }

        val migrated = LegacySmartGeofenceConfigMigrator.read(prefs)
        val migrationCommitted = runCatching {
            val document = SmartGeofenceConfigCodec.encode(migrated)
            prefs.edit()
                .putConfigDocument(migrated, document)
                .putLong(CONFIG_APPLIED_AT_MILLIS_KEY, System.currentTimeMillis())
                .putString(CONFIG_FINGERPRINT_KEY, fingerprint(document))
                .commit()
        }.getOrDefault(false)
        if (migrationCommitted) {
            LegacySmartGeofenceConfigMigrator.removeLegacyValues(prefs)
        }
        return migrated
    }

    fun appliedAtMillis(context: Context): Long? = context.applicationContext
        .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(CONFIG_APPLIED_AT_MILLIS_KEY, 0L)
        .takeIf { it > 0L }

    fun fingerprint(context: Context): String? = context.applicationContext
        .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(CONFIG_FINGERPRINT_KEY, null)

    fun fingerprint(config: SmartGeofenceConfig): String =
        fingerprint(SmartGeofenceConfigCodec.encode(config))

    internal fun fingerprint(document: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(document.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun SharedPreferences.Editor.putConfigDocument(
    config: SmartGeofenceConfig,
    document: String = SmartGeofenceConfigCodec.encode(config),
): SharedPreferences.Editor =
    putString(
        SmartGeofenceConfigStore.PERSISTED_CONFIG_DOCUMENT_KEY,
        document,
    )
        .putBoolean(Constants.CONFIG_LOG_FILE_ENABLED, config.logFileEnabled)
        .putBoolean(Constants.CONFIG_LOG_FILE_VERBOSE, config.logFileVerbose)
        .putInt(Constants.CONFIG_MAX_LOG_FILE_BYTES, config.maxLogFileBytes)
