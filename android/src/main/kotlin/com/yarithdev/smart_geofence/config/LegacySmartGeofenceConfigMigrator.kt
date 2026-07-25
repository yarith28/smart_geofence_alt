package com.yarithdev.smart_geofence.config

import android.content.SharedPreferences
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionMode
import com.yarithdev.smart_geofence.core.Constants
import java.util.concurrent.TimeUnit

internal object LegacySmartGeofenceConfigMigrator {
    private val operationalLoggerKeys = setOf(
        Constants.CONFIG_LOG_FILE_ENABLED,
        Constants.CONFIG_LOG_FILE_VERBOSE,
        Constants.CONFIG_MAX_LOG_FILE_BYTES,
    )

    fun read(source: SharedPreferences): SmartGeofenceConfig {
        val p = SnapshotSharedPreferences(source.all)
        val d = SmartGeofenceConfig.default()
        normalizeStoredConfig(p)
        val nativeExitConfirmationEnabled = p.getBoolean(
            Constants.CONFIG_NATIVE_EXIT_CONFIRMATION_ENABLED,
            d.nativeExitConfirmationEnabled,
        )
        val nativeEnterConfirmationEnabled = p.getBoolean(
            Constants.CONFIG_NATIVE_ENTER_CONFIRMATION_ENABLED,
            d.nativeEnterConfirmationEnabled,
        )
        return SmartGeofenceConfig(
            batteryMode = SmartGeofenceConfigStore.normalizeBatteryMode(
                p.getString(Constants.CONFIG_BATTERY_MODE, d.batteryMode),
                d.batteryMode,
            ),
            locationUnavailablePolicy = d.locationUnavailablePolicy,
            proximityRadiusMeters = p.getFloat(
                Constants.CONFIG_PROXIMITY_RADIUS_METERS, d.proximityRadiusMeters.toFloat()
            ).toDouble(),
            escalationEnabled = p.getBoolean(Constants.CONFIG_ESCALATION_ENABLED, d.escalationEnabled),
            proximityLocationPriority = p.getString(
                Constants.CONFIG_PROXIMITY_LOCATION_PRIORITY,
                d.proximityLocationPriority
            ) ?: d.proximityLocationPriority,
            proximityIntervalMillis = p.getLong(
                Constants.CONFIG_PROXIMITY_INTERVAL_MILLIS,
                d.proximityIntervalMillis
            ),
            proximityFastestIntervalMillis = p.getLong(
                Constants.CONFIG_PROXIMITY_FASTEST_INTERVAL_MILLIS,
                d.proximityFastestIntervalMillis
            ),
            proximityMaxWaitMillis = p.getLong(
                Constants.CONFIG_PROXIMITY_MAX_WAIT_MILLIS,
                d.proximityMaxWaitMillis
            ),
            proximityMinDisplacementMeters = p.getFloat(
                Constants.CONFIG_PROXIMITY_MIN_DISPLACEMENT_METERS,
                d.proximityMinDisplacementMeters.toFloat()
            ).toDouble(),
            proximityAdaptiveDisplacementEnabled = p.getBoolean(
                Constants.CONFIG_PROXIMITY_ADAPTIVE_DISPLACEMENT_ENABLED,
                d.proximityAdaptiveDisplacementEnabled,
            ),
            proximityAdaptiveNearBoundaryDistanceMeters = p.getFloat(
                Constants.CONFIG_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISTANCE_METERS,
                d.proximityAdaptiveNearBoundaryDistanceMeters.toFloat(),
            ).toDouble(),
            proximityAdaptiveNearBoundaryDisplacementMeters = p.getFloat(
                Constants.CONFIG_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISPLACEMENT_METERS,
                d.proximityAdaptiveNearBoundaryDisplacementMeters.toFloat(),
            ).toDouble(),
            proximityAdaptiveStationaryDisplacementMeters = p.getFloat(
                Constants.CONFIG_PROXIMITY_ADAPTIVE_STATIONARY_DISPLACEMENT_METERS,
                d.proximityAdaptiveStationaryDisplacementMeters.toFloat(),
            ).toDouble(),
            proximityAdaptiveHysteresisMeters = p.getFloat(
                Constants.CONFIG_PROXIMITY_ADAPTIVE_HYSTERESIS_METERS,
                d.proximityAdaptiveHysteresisMeters.toFloat(),
            ).toDouble(),
            passiveLocationPriority = p.getString(
                Constants.CONFIG_PASSIVE_LOCATION_PRIORITY,
                d.passiveLocationPriority
            ) ?: d.passiveLocationPriority,
            passiveLocationIntervalMillis = p.getLong(
                Constants.CONFIG_PASSIVE_LOCATION_INTERVAL_MILLIS,
                d.passiveLocationIntervalMillis
            ),
            passiveLocationFastestIntervalMillis = p.getLong(
                Constants.CONFIG_PASSIVE_LOCATION_FASTEST_INTERVAL_MILLIS,
                d.passiveLocationFastestIntervalMillis
            ),
            passiveLocationMaxWaitMillis = p.getLong(
                Constants.CONFIG_PASSIVE_LOCATION_MAX_WAIT_MILLIS,
                d.passiveLocationMaxWaitMillis
            ),
            passiveFollowUpEnabled = if (p.contains(Constants.CONFIG_PASSIVE_FOLLOW_UP_ENABLED)) {
                p.getBoolean(
                    Constants.CONFIG_PASSIVE_FOLLOW_UP_ENABLED,
                    d.passiveFollowUpEnabled
                )
            } else {
                p.getBoolean(
                    Constants.LEGACY_CONFIG_PASSIVE_AMBIGUOUS_CONFIRM_ENABLED,
                    d.passiveFollowUpEnabled
                )
            },
            locationConfirmTimeoutMillis = p.getLong(
                Constants.CONFIG_LOCATION_CONFIRM_TIMEOUT_MILLIS,
                d.locationConfirmTimeoutMillis
            ),
            pulseLocationMaxAccuracyMeters = p.getFloat(
                Constants.CONFIG_PULSE_LOCATION_MAX_ACCURACY_METERS,
                d.pulseLocationMaxAccuracyMeters.toFloat()
            ).toDouble(),
            eventLocationMaxAccuracyMeters = p.getFloat(
                Constants.CONFIG_EVENT_LOCATION_MAX_ACCURACY_METERS,
                d.eventLocationMaxAccuracyMeters.toFloat()
            ).toDouble(),
            nativeExitConfirmationEnabled = nativeExitConfirmationEnabled,
            nativeEnterConfirmationEnabled = nativeEnterConfirmationEnabled,
            nativeConfirmDelayMillis = d.nativeConfirmDelayMillis,
            nativeConfirmMaxAttempts = p.getInt(
                Constants.CONFIG_NATIVE_CONFIRM_MAX_ATTEMPTS,
                d.nativeConfirmMaxAttempts
            ),
            transitionValidationEnabled = d.transitionValidationEnabled,
            transitionValidationEnterEnabled = nativeEnterConfirmationEnabled,
            transitionValidationExitEnabled = nativeExitConfirmationEnabled,
            transitionValidationMinimumDelayMillis =
                d.transitionValidationMinimumDelayMillis,
            nativeEnterConfirmRadiusSlackMeters = p.getFloat(
                Constants.CONFIG_NATIVE_ENTER_CONFIRM_RADIUS_SLACK_METERS,
                d.nativeEnterConfirmRadiusSlackMeters.toFloat()
            ).toDouble(),
            nativeEnterPayloadSanityEnabled = p.getBoolean(
                Constants.CONFIG_NATIVE_ENTER_PAYLOAD_SANITY_ENABLED,
                d.nativeEnterPayloadSanityEnabled
            ),
            nativeEnterPayloadDistanceSlackMeters = p.getFloat(
                Constants.CONFIG_NATIVE_ENTER_PAYLOAD_DISTANCE_SLACK_METERS,
                d.nativeEnterPayloadDistanceSlackMeters.toFloat()
            ).toDouble(),
            teleportGuardEnabled = p.getBoolean(
                Constants.CONFIG_TELEPORT_GUARD_ENABLED,
                d.teleportGuardEnabled
            ),
            teleportMaxSpeedMetersPerSecond = p.getFloat(
                Constants.CONFIG_TELEPORT_MAX_SPEED_MPS,
                d.teleportMaxSpeedMetersPerSecond.toFloat()
            ).toDouble(),
            mockLocationPolicy = MockLocationPolicy.fromConfigValue(
                p.getString(
                    Constants.CONFIG_MOCK_LOCATION_POLICY,
                    d.mockLocationPolicy.configValue,
                )
            ) ?: d.mockLocationPolicy,
            proximityPulseEnabled = p.getBoolean(Constants.CONFIG_PROXIMITY_PULSE_ENABLED, d.proximityPulseEnabled),
            proximityPulseActivationDistanceMeters =
                d.proximityPulseActivationDistanceMeters,
            proximityPulseIntervalMillis = TimeUnit.SECONDS.toMillis(
                p.getLong(
                    Constants.CONFIG_PROXIMITY_PULSE_INTERVAL_SECONDS,
                    TimeUnit.MILLISECONDS.toSeconds(d.proximityPulseIntervalMillis),
                ),
            ),
            proximityConfirmMaxAttempts = d.proximityConfirmMaxAttempts,
            proximityPulseTransitionConfirmationIntervalMillis =
                d.proximityPulseTransitionConfirmationIntervalMillis,
            proximityPulseTransitionConfirmationBurstDurationMillis =
                d.proximityPulseTransitionConfirmationBurstDurationMillis,
            proximityPulseActiveStartMinuteOfDay = p.getInt(
                Constants.CONFIG_PROXIMITY_PULSE_ACTIVE_START_MINUTE_OF_DAY,
                d.proximityPulseActiveStartMinuteOfDay
            ),
            proximityPulseActiveEndMinuteOfDay = p.getInt(
                Constants.CONFIG_PROXIMITY_PULSE_ACTIVE_END_MINUTE_OF_DAY,
                d.proximityPulseActiveEndMinuteOfDay
            ),
            proximityPulseOutsideActiveHoursIntervalMultiplier =
                d.proximityPulseOutsideActiveHoursIntervalMultiplier,
            proximityPulseMinIntervalMillis = p.getLong(
                Constants.CONFIG_PROXIMITY_PULSE_MIN_INTERVAL_MILLIS,
                d.proximityPulseMinIntervalMillis
            ),
            foregroundNotificationTitle = p.getString(
                Constants.CONFIG_FOREGROUND_NOTIFICATION_TITLE,
                d.foregroundNotificationTitle
            ) ?: d.foregroundNotificationTitle,
            foregroundNotificationChannelId = p.getString(
                Constants.CONFIG_FOREGROUND_NOTIFICATION_CHANNEL_ID,
                d.foregroundNotificationChannelId
            ) ?: d.foregroundNotificationChannelId,
            foregroundNotificationChannelName = p.getString(
                Constants.CONFIG_FOREGROUND_NOTIFICATION_CHANNEL_NAME,
                d.foregroundNotificationChannelName
            ) ?: d.foregroundNotificationChannelName,
            foregroundNotificationId = p.getInt(
                Constants.CONFIG_FOREGROUND_NOTIFICATION_ID,
                d.foregroundNotificationId
            ),
            foregroundNotificationSmallIconResourceName = p.getString(
                Constants.CONFIG_FOREGROUND_NOTIFICATION_SMALL_ICON_RESOURCE_NAME,
                d.foregroundNotificationSmallIconResourceName
            ),
            foregroundNotificationSticky = p.getBoolean(
                Constants.CONFIG_FOREGROUND_NOTIFICATION_STICKY,
                legacyForegroundNotificationSticky(p, d.foregroundNotificationSticky)
            ),
            foregroundNotificationTapAction = ForegroundNotificationTapAction.fromConfigValue(
                p.getString(
                    Constants.CONFIG_FOREGROUND_NOTIFICATION_TAP_ACTION,
                    legacyForegroundNotificationTapAction(p, d.foregroundNotificationTapAction)
                        .configValue,
                )
            ) ?: d.foregroundNotificationTapAction,
            foregroundNotificationShowWhileMonitoring = p.getBoolean(
                Constants.CONFIG_FOREGROUND_NOTIFICATION_SHOW_WHILE_MONITORING,
                legacyForegroundNotificationShowWhileMonitoring(
                    p,
                    d.foregroundNotificationShowWhileMonitoring
                )
            ),
            activityStationaryTtlMillis = p.getLong(
                Constants.CONFIG_ACTIVITY_STATIONARY_TTL_MILLIS,
                d.activityStationaryTtlMillis
            ),
            activityPeriodicBackstopEnabled = p.getBoolean(
                Constants.CONFIG_ACTIVITY_PERIODIC_BACKSTOP_ENABLED,
                d.activityPeriodicBackstopEnabled,
            ),
            activityUpdateIntervalMillis = p.getLong(
                Constants.CONFIG_ACTIVITY_UPDATE_INTERVAL_MILLIS,
                d.activityUpdateIntervalMillis
            ),
            activityMovingProximityCheckDelayMillis = p.getLong(
                Constants.CONFIG_ACTIVITY_MOVING_PROXIMITY_CHECK_DELAY_MILLIS,
                d.activityMovingProximityCheckDelayMillis
            ),
            activityFusedLocationStaleAfterMillis = p.getLong(
                Constants.CONFIG_ACTIVITY_FUSED_LOCATION_STALE_AFTER_MILLIS,
                d.activityFusedLocationStaleAfterMillis
            ),
            recoveryTimesMinuteOfDay = p.getStringSet(
                Constants.CONFIG_RECOVERY_TIMES_MINUTE_OF_DAY,
                null,
            )
                ?.mapNotNull { it.toIntOrNull() }
                ?.filter { it in 0 until MINUTES_PER_DAY }
                ?.distinct()
                ?.sorted()
                ?.takeIf { it.isNotEmpty() }
                ?: d.recoveryTimesMinuteOfDay,
            recoveryAlarmPolicy = AlarmSchedulePolicy.fromConfigValue(
                p.getString(
                    Constants.CONFIG_RECOVERY_ALARM_POLICY,
                    d.recoveryAlarmPolicy.configValue,
                )
            ) ?: d.recoveryAlarmPolicy,
            recoveryInexactGuardDelayMillis = p.getNullableLong(
                Constants.CONFIG_RECOVERY_INEXACT_GUARD_DELAY_MILLIS,
            ) ?: d.recoveryInexactGuardDelayMillis,
            exactAlarmPermissionMode = ExactAlarmPermissionMode.fromConfigValue(
                p.getString(
                    Constants.CONFIG_EXACT_ALARM_PERMISSION_MODE,
                    d.exactAlarmPermissionMode.configValue,
                )
            ) ?: d.exactAlarmPermissionMode,
            logFileEnabled =
                p.getNullableBoolean(Constants.CONFIG_LOG_FILE_ENABLED) ?: d.logFileEnabled,
            logFileVerbose =
                p.getNullableBoolean(Constants.CONFIG_LOG_FILE_VERBOSE) ?: d.logFileVerbose,
            maxLogFileBytes = p.getInt(Constants.CONFIG_MAX_LOG_FILE_BYTES, d.maxLogFileBytes),
            retryOnCallbackFailure =
                p.getNullableBoolean(Constants.CONFIG_RETRY_ON_CALLBACK_FAILURE)
                    ?: d.retryOnCallbackFailure,
            passiveLocationEnabled = p.getBoolean(
                Constants.CONFIG_PASSIVE_LOCATION_ENABLED,
                d.passiveLocationEnabled
            ),
            foregroundServiceLaunchTimeoutMillis = p.getLong(
                Constants.CONFIG_FOREGROUND_SERVICE_LAUNCH_TIMEOUT_MILLIS,
                d.foregroundServiceLaunchTimeoutMillis
            ),
            foregroundServiceStartDelayMillis = p.getLong(
                Constants.CONFIG_FOREGROUND_SERVICE_START_DELAY_MILLIS,
                d.foregroundServiceStartDelayMillis
            ),
            foregroundServiceRearmDelayMillis = p.getLong(
                Constants.CONFIG_FOREGROUND_SERVICE_REARM_DELAY_MILLIS,
                d.foregroundServiceRearmDelayMillis
            ),
            foregroundServiceCallbackTimeoutMillis = p.getLong(
                Constants.CONFIG_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS,
                d.foregroundServiceCallbackTimeoutMillis
            ),
            foregroundServiceSticky = p.getBoolean(
                Constants.CONFIG_FOREGROUND_SERVICE_STICKY,
                d.foregroundServiceSticky
            ),
            confirmQueueMaxAgeMillis = p.getLong(
                Constants.CONFIG_CONFIRM_QUEUE_MAX_AGE_MILLIS,
                d.confirmQueueMaxAgeMillis
            ),
            timeIntegrityEnabled = p.getBoolean(
                Constants.CONFIG_TIME_INTEGRITY_ENABLED,
                d.timeIntegrityEnabled,
            ),
            timeIntegrityConfigJson = p.getString(
                Constants.CONFIG_TIME_INTEGRITY_CONFIG_JSON,
                d.timeIntegrityConfigJson,
            ) ?: d.timeIntegrityConfigJson,
        ).validateTransitionConfiguration()
    }

    fun removeLegacyValues(prefs: SharedPreferences) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("config_") && it !in operationalLoggerKeys }
            .forEach(editor::remove)
        runCatching { editor.commit() }
    }
}

private fun SharedPreferences.getNullableLong(key: String): Long? =
    if (contains(key)) {
        runCatching { getLong(key, 0L) }
            .getOrElse {
                edit().remove(key).apply()
                null
            }
    } else {
        null
    }

private fun SharedPreferences.getNullableBoolean(key: String): Boolean? =
    if (contains(key)) {
        runCatching { getBoolean(key, false) }
            .getOrElse {
                edit().remove(key).apply()
                null
            }
    } else {
        null
    }

private fun legacyForegroundNotificationSticky(
    prefs: SharedPreferences,
    fallback: Boolean,
): Boolean {
    prefs.getNullableBoolean(Constants.LEGACY_CONFIG_FOREGROUND_NOTIFICATION_ONGOING)?.let {
        return it
    }
    return when (prefs.getString(Constants.LEGACY_CONFIG_FOREGROUND_NOTIFICATION_BEHAVIOR, null)) {
        "sticky" -> true
        "dismissibleReasserting" -> false
        else -> fallback
    }
}

private fun legacyForegroundNotificationTapAction(
    prefs: SharedPreferences,
    fallback: ForegroundNotificationTapAction,
): ForegroundNotificationTapAction {
    return if (
        prefs.getNullableBoolean(Constants.LEGACY_CONFIG_FOREGROUND_NOTIFICATION_AUTO_CANCEL) == true
    ) {
        ForegroundNotificationTapAction.Dismiss
    } else {
        fallback
    }
}

private fun legacyForegroundNotificationShowWhileMonitoring(
    prefs: SharedPreferences,
    fallback: Boolean,
): Boolean {
    prefs.getNullableBoolean(Constants.LEGACY_CONFIG_FOREGROUND_NOTIFICATION_REMOVE_WHEN_IDLE)
        ?.let { removeWhenIdle ->
            return !removeWhenIdle
        }
    return fallback
}

private fun normalizeStoredConfig(prefs: SharedPreferences) {
    val values = prefs.all
    val editor = prefs.edit()
    var changed = false

    fun remove(key: String) {
        editor.remove(key)
        changed = true
    }

    fun normalizeString(key: String) {
        if (!values.containsKey(key)) return
        val value = values[key]
        if (value != null && value !is String) remove(key)
    }

    fun normalizeStringSet(key: String) {
        if (!values.containsKey(key)) return
        val value = values[key]
        if (value !is Set<*> || !value.all { it is String }) remove(key)
    }

    fun normalizeBoolean(key: String) {
        if (!values.containsKey(key)) return
        if (values[key] !is Boolean) remove(key)
    }

    fun normalizeLong(key: String) {
        if (!values.containsKey(key)) return
        when (val value = values[key]) {
            is Long -> Unit
            is Int -> {
                editor.putLong(key, value.toLong())
                changed = true
            }
            else -> remove(key)
        }
    }

    fun normalizeInt(key: String) {
        if (!values.containsKey(key)) return
        when (val value = values[key]) {
            is Int -> Unit
            is Long -> {
                if (value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                    editor.putInt(key, value.toInt())
                    changed = true
                } else {
                    remove(key)
                }
            }
            else -> remove(key)
        }
    }

    fun normalizeFloat(key: String) {
        if (!values.containsKey(key)) return
        when (val value = values[key]) {
            is Float -> Unit
            is Int -> {
                editor.putFloat(key, value.toFloat())
                changed = true
            }
            is Long -> {
                editor.putFloat(key, value.toFloat())
                changed = true
            }
            else -> remove(key)
        }
    }

    listOf(
        Constants.CONFIG_BATTERY_MODE,
        Constants.CONFIG_PROXIMITY_LOCATION_PRIORITY,
        Constants.CONFIG_PASSIVE_LOCATION_PRIORITY,
        Constants.CONFIG_MOCK_LOCATION_POLICY,
        Constants.CONFIG_FOREGROUND_NOTIFICATION_TITLE,
        Constants.CONFIG_FOREGROUND_NOTIFICATION_CHANNEL_ID,
        Constants.CONFIG_FOREGROUND_NOTIFICATION_CHANNEL_NAME,
        Constants.CONFIG_FOREGROUND_NOTIFICATION_SMALL_ICON_RESOURCE_NAME,
        Constants.CONFIG_FOREGROUND_NOTIFICATION_TAP_ACTION,
        Constants.LEGACY_CONFIG_FOREGROUND_NOTIFICATION_BEHAVIOR,
        Constants.CONFIG_RECOVERY_ALARM_POLICY,
        Constants.CONFIG_EXACT_ALARM_PERMISSION_MODE,
        Constants.CONFIG_TIME_INTEGRITY_CONFIG_JSON,
    ).forEach(::normalizeString)

    listOf(
        Constants.CONFIG_ESCALATION_ENABLED,
        Constants.CONFIG_PROXIMITY_ADAPTIVE_DISPLACEMENT_ENABLED,
        Constants.CONFIG_PASSIVE_FOLLOW_UP_ENABLED,
        Constants.LEGACY_CONFIG_PASSIVE_AMBIGUOUS_CONFIRM_ENABLED,
        Constants.CONFIG_NATIVE_EXIT_CONFIRMATION_ENABLED,
        Constants.CONFIG_NATIVE_ENTER_CONFIRMATION_ENABLED,
        Constants.CONFIG_NATIVE_ENTER_PAYLOAD_SANITY_ENABLED,
        Constants.CONFIG_TELEPORT_GUARD_ENABLED,
        Constants.CONFIG_PROXIMITY_PULSE_ENABLED,
        Constants.CONFIG_FOREGROUND_NOTIFICATION_STICKY,
        Constants.LEGACY_CONFIG_FOREGROUND_NOTIFICATION_ONGOING,
        Constants.LEGACY_CONFIG_FOREGROUND_NOTIFICATION_AUTO_CANCEL,
        Constants.LEGACY_CONFIG_FOREGROUND_NOTIFICATION_REMOVE_WHEN_IDLE,
        Constants.CONFIG_FOREGROUND_NOTIFICATION_SHOW_WHILE_MONITORING,
        Constants.CONFIG_ACTIVITY_PERIODIC_BACKSTOP_ENABLED,
        Constants.CONFIG_LOG_FILE_ENABLED,
        Constants.CONFIG_PASSIVE_LOCATION_ENABLED,
        Constants.CONFIG_FOREGROUND_SERVICE_STICKY,
        Constants.CONFIG_TIME_INTEGRITY_ENABLED,
    ).forEach(::normalizeBoolean)

    listOf(
        Constants.CONFIG_PROXIMITY_INTERVAL_MILLIS,
        Constants.CONFIG_PROXIMITY_FASTEST_INTERVAL_MILLIS,
        Constants.CONFIG_PROXIMITY_MAX_WAIT_MILLIS,
        Constants.CONFIG_PASSIVE_LOCATION_INTERVAL_MILLIS,
        Constants.CONFIG_PASSIVE_LOCATION_FASTEST_INTERVAL_MILLIS,
        Constants.CONFIG_PASSIVE_LOCATION_MAX_WAIT_MILLIS,
        Constants.CONFIG_LOCATION_CONFIRM_TIMEOUT_MILLIS,
        Constants.CONFIG_PROXIMITY_PULSE_DURATION_MINUTES,
        Constants.CONFIG_PROXIMITY_PULSE_INTERVAL_SECONDS,
        Constants.CONFIG_PROXIMITY_PULSE_HIGH_RATE_INTERVAL_MILLIS,
        Constants.CONFIG_PROXIMITY_PULSE_MIN_INTERVAL_MILLIS,
        Constants.CONFIG_PROXIMITY_PULSE_FIRST_TICK_DELAY_MILLIS,
        Constants.CONFIG_ACTIVITY_STATIONARY_TTL_MILLIS,
        Constants.CONFIG_ACTIVITY_UPDATE_INTERVAL_MILLIS,
        Constants.CONFIG_ACTIVITY_MOVING_PROXIMITY_CHECK_DELAY_MILLIS,
        Constants.CONFIG_ACTIVITY_FUSED_LOCATION_STALE_AFTER_MILLIS,
        Constants.CONFIG_RECOVERY_INEXACT_GUARD_DELAY_MILLIS,
        Constants.CONFIG_FOREGROUND_SERVICE_LAUNCH_TIMEOUT_MILLIS,
        Constants.CONFIG_FOREGROUND_SERVICE_START_DELAY_MILLIS,
        Constants.CONFIG_FOREGROUND_SERVICE_REARM_DELAY_MILLIS,
        Constants.CONFIG_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS,
        Constants.CONFIG_CONFIRM_QUEUE_MAX_AGE_MILLIS,
    ).forEach(::normalizeLong)

    listOf(
        Constants.CONFIG_PROXIMITY_PULSE_ACTIVE_START_MINUTE_OF_DAY,
        Constants.CONFIG_PROXIMITY_PULSE_ACTIVE_END_MINUTE_OF_DAY,
        Constants.CONFIG_PROXIMITY_PULSE_MAX_IDLE_TICKS,
        Constants.CONFIG_NATIVE_CONFIRM_MAX_ATTEMPTS,
        Constants.CONFIG_FOREGROUND_NOTIFICATION_ID,
        Constants.CONFIG_MAX_LOG_FILE_BYTES,
    ).forEach(::normalizeInt)

    listOf(
        Constants.CONFIG_PROXIMITY_RADIUS_METERS,
        Constants.CONFIG_PROXIMITY_MIN_DISPLACEMENT_METERS,
        Constants.CONFIG_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISTANCE_METERS,
        Constants.CONFIG_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISPLACEMENT_METERS,
        Constants.CONFIG_PROXIMITY_ADAPTIVE_STATIONARY_DISPLACEMENT_METERS,
        Constants.CONFIG_PROXIMITY_ADAPTIVE_HYSTERESIS_METERS,
        Constants.CONFIG_PULSE_LOCATION_MAX_ACCURACY_METERS,
        Constants.CONFIG_EVENT_LOCATION_MAX_ACCURACY_METERS,
        Constants.CONFIG_NATIVE_ENTER_CONFIRM_RADIUS_SLACK_METERS,
        Constants.CONFIG_NATIVE_ENTER_PAYLOAD_DISTANCE_SLACK_METERS,
        Constants.CONFIG_TELEPORT_MAX_SPEED_MPS,
        Constants.CONFIG_PROXIMITY_PULSE_HIGH_RATE_DISTANCE_METERS,
    ).forEach(::normalizeFloat)

    normalizeStringSet(Constants.CONFIG_RECOVERY_TIMES_MINUTE_OF_DAY)

    if (changed) editor.apply()
}

private const val MINUTES_PER_DAY = 24 * 60

private class SnapshotSharedPreferences(source: Map<String, *>) : SharedPreferences {
    private val values = source.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? =
        if (values.containsKey(key)) values[key] as String? else defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        if (values.containsKey(key)) (values[key] as Set<String>?)?.toMutableSet() else defValues

    override fun getInt(key: String, defValue: Int): Int =
        if (values.containsKey(key)) values[key] as Int else defValue
    override fun getLong(key: String, defValue: Long): Long =
        if (values.containsKey(key)) values[key] as Long else defValue
    override fun getFloat(key: String, defValue: Float): Float =
        if (values.containsKey(key)) values[key] as Float else defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        if (values.containsKey(key)) values[key] as Boolean else defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = SnapshotEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class SnapshotEditor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) =
            apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clearRequested = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearRequested) values.clear()
            removals.forEach(values::remove)
            values.putAll(pending)
        }
    }
}
