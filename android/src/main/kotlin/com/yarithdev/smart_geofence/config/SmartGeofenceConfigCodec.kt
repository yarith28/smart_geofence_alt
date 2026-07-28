package com.yarithdev.smart_geofence.config

import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionMode
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal object SmartGeofenceConfigCodec {
    const val CURRENT_SCHEMA_VERSION = 7
    const val LEGACY_SCHEMA_VERSION = 1

    private const val FIELD_SCHEMA_VERSION = "schemaVersion"
    private const val FIELD_CONFIG = "config"
    private const val MINUTES_PER_DAY = 24 * 60

    fun encode(config: SmartGeofenceConfig): String {
        config.validateTransitionConfiguration()
        return JSONObject()
            .put(FIELD_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .put(FIELD_CONFIG, config.toJsonObject())
            .toString()
    }

    fun decode(documentJson: String): SmartGeofenceConfig {
        val document = try {
            JSONObject(documentJson)
        } catch (error: Throwable) {
            throw IllegalArgumentException("Malformed configuration document.", error)
        }
        val schemaVersion = document.number(FIELD_SCHEMA_VERSION)?.toInt()
            ?: throw IllegalArgumentException("Missing configuration schemaVersion.")
        if (schemaVersion !in LEGACY_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException(
                "Unsupported configuration schemaVersion=$schemaVersion."
            )
        }
        val config = document.opt(FIELD_CONFIG) as? JSONObject
            ?: throw IllegalArgumentException("Missing configuration config object.")
        return decodeConfigObject(config, schemaVersion)
    }

    fun decodeLegacyMap(arguments: Map<*, *>): SmartGeofenceConfig =
        decodeConfigObject(arguments.toJsonObject(), LEGACY_SCHEMA_VERSION)
            .validateLegacyTimeIntegrity()

    fun decodeLegacyEnvelope(configJson: String): SmartGeofenceConfig {
        val config = try {
            JSONObject(configJson)
        } catch (error: Throwable) {
            throw IllegalArgumentException("Malformed configuration configJson.", error)
        }
        return decodeConfigObject(config, LEGACY_SCHEMA_VERSION)
            .validateLegacyTimeIntegrity()
    }

    private fun SmartGeofenceConfig.validateLegacyTimeIntegrity(): SmartGeofenceConfig {
        if (timeIntegrityEnabled) {
            try {
                JSONObject(timeIntegrityConfigJson)
            } catch (error: Throwable) {
                throw IllegalArgumentException("Malformed time-integrity configuration JSON.", error)
            }
        }
        return this
    }

    private fun decodeConfigObject(
        source: JSONObject,
        schemaVersion: Int,
    ): SmartGeofenceConfig {
        val d = SmartGeofenceConfig.default()
        val timeIntegrity = source.timeIntegrity(d)
        val nativeExitConfirmationEnabled = source.boolean(
            "nativeExitConfirmationEnabled",
            d.nativeExitConfirmationEnabled,
        )
        val nativeEnterConfirmationEnabled = source.boolean(
            "nativeEnterConfirmationEnabled",
            d.nativeEnterConfirmationEnabled,
        )
        return SmartGeofenceConfig(
            batteryMode = SmartGeofenceConfigStore.normalizeBatteryMode(
                source.string("batteryMode", d.batteryMode),
                d.batteryMode,
            ),
            locationUnavailablePolicy = LocationUnavailablePolicy.fromConfigValue(
                source.string(
                    "locationUnavailablePolicy",
                    d.locationUnavailablePolicy.configValue,
                )
            ) ?: d.locationUnavailablePolicy,
            proximityRadiusMeters = source.double(
                "proximityRadiusMeters",
                d.proximityRadiusMeters,
            ),
            escalationEnabled = source.boolean("escalationEnabled", d.escalationEnabled),
            proximityLocationPriority = source.string(
                "proximityLocationPriority",
                d.proximityLocationPriority,
            ),
            proximityIntervalMillis = source.long(
                "proximityIntervalMillis",
                d.proximityIntervalMillis,
            ),
            proximityFastestIntervalMillis = source.long(
                "proximityFastestIntervalMillis",
                d.proximityFastestIntervalMillis,
            ),
            proximityMaxWaitMillis = source.long(
                "proximityMaxWaitMillis",
                d.proximityMaxWaitMillis,
            ),
            proximityMinDisplacementMeters = source.double(
                "proximityMinDisplacementMeters",
                d.proximityMinDisplacementMeters,
            ),
            proximityAdaptiveDisplacementEnabled = source.boolean(
                "proximityAdaptiveDisplacementEnabled",
                d.proximityAdaptiveDisplacementEnabled,
            ),
            proximityAdaptiveNearBoundaryDistanceMeters = source.double(
                "proximityAdaptiveNearBoundaryDistanceMeters",
                d.proximityAdaptiveNearBoundaryDistanceMeters,
            ),
            proximityAdaptiveNearBoundaryDisplacementMeters = source.double(
                "proximityAdaptiveNearBoundaryDisplacementMeters",
                d.proximityAdaptiveNearBoundaryDisplacementMeters,
            ),
            proximityAdaptiveStationaryDisplacementMeters = source.double(
                "proximityAdaptiveStationaryDisplacementMeters",
                d.proximityAdaptiveStationaryDisplacementMeters,
            ),
            proximityAdaptiveHysteresisMeters = source.double(
                "proximityAdaptiveHysteresisMeters",
                d.proximityAdaptiveHysteresisMeters,
            ),
            passiveLocationPriority = source.string(
                "passiveLocationPriority",
                d.passiveLocationPriority,
            ),
            passiveLocationIntervalMillis = source.long(
                "passiveLocationIntervalMillis",
                d.passiveLocationIntervalMillis,
            ),
            passiveLocationFastestIntervalMillis = source.long(
                "passiveLocationFastestIntervalMillis",
                d.passiveLocationFastestIntervalMillis,
            ),
            passiveLocationMaxWaitMillis = source.long(
                "passiveLocationMaxWaitMillis",
                d.passiveLocationMaxWaitMillis,
            ),
            passiveFollowUpEnabled = source.nullableBoolean("passiveFollowUpEnabled")
                ?: source.nullableBoolean("passiveAmbiguousConfirmEnabled")
                ?: d.passiveFollowUpEnabled,
            locationConfirmTimeoutMillis = source.long(
                "locationConfirmTimeoutMillis",
                d.locationConfirmTimeoutMillis,
            ),
            pulseLocationMaxAccuracyMeters = source.double(
                "pulseLocationMaxAccuracyMeters",
                d.pulseLocationMaxAccuracyMeters,
            ),
            eventLocationMaxAccuracyMeters = source.double(
                "eventLocationMaxAccuracyMeters",
                d.eventLocationMaxAccuracyMeters,
            ),
            insideEventLocationMaxAccuracyMeters = if (schemaVersion >= 7) {
                source.double(
                    "insideEventLocationMaxAccuracyMeters",
                    d.insideEventLocationMaxAccuracyMeters,
                )
            } else {
                d.insideEventLocationMaxAccuracyMeters
            },
            nativeExitConfirmationEnabled = nativeExitConfirmationEnabled,
            nativeEnterConfirmationEnabled = nativeEnterConfirmationEnabled,
            nativeConfirmDelayMillis = source.long(
                "nativeConfirmDelayMillis",
                d.nativeConfirmDelayMillis,
            ),
            nativeConfirmMaxAttempts = source.int(
                "nativeConfirmMaxAttempts",
                d.nativeConfirmMaxAttempts,
            ),
            transitionValidationEnabled = source.boolean(
                "transitionValidationEnabled",
                d.transitionValidationEnabled,
            ),
            transitionValidationEnterEnabled = source.boolean(
                "transitionValidationEnterEnabled",
                nativeEnterConfirmationEnabled,
            ),
            transitionValidationExitEnabled = source.boolean(
                "transitionValidationExitEnabled",
                nativeExitConfirmationEnabled,
            ),
            transitionValidationMinimumDelayMillis = source.long(
                "transitionValidationMinimumDelayMillis",
                d.transitionValidationMinimumDelayMillis,
            ),
            nativeEnterConfirmRadiusSlackMeters = source.double(
                "nativeEnterConfirmRadiusSlackMeters",
                d.nativeEnterConfirmRadiusSlackMeters,
            ),
            nativeEnterPayloadSanityEnabled = source.boolean(
                "nativeEnterPayloadSanityEnabled",
                d.nativeEnterPayloadSanityEnabled,
            ),
            nativeEnterPayloadDistanceSlackMeters = source.double(
                "nativeEnterPayloadDistanceSlackMeters",
                d.nativeEnterPayloadDistanceSlackMeters,
            ),
            teleportGuardEnabled = source.boolean(
                "teleportGuardEnabled",
                d.teleportGuardEnabled,
            ),
            teleportMaxSpeedMetersPerSecond = source.double(
                "teleportMaxSpeedMetersPerSecond",
                d.teleportMaxSpeedMetersPerSecond,
            ),
            mockLocationPolicy = MockLocationPolicy.fromConfigValue(
                source.string("mockLocationPolicy", d.mockLocationPolicy.configValue)
            ) ?: d.mockLocationPolicy,
            proximityPulseEnabled = source.boolean(
                "proximityPulseEnabled",
                d.proximityPulseEnabled,
            ),
            proximityPulseActivationDistanceMeters = if (schemaVersion >= 4) {
                source.double(
                    "proximityPulseActivationDistanceMeters",
                    d.proximityPulseActivationDistanceMeters,
                )
            } else {
                d.proximityPulseActivationDistanceMeters
            },
            proximityPulseIntervalMillis = if (
                schemaVersion == LEGACY_SCHEMA_VERSION
            ) {
                TimeUnit.SECONDS.toMillis(
                    source.long(
                        "proximityPulseIntervalSeconds",
                        TimeUnit.MILLISECONDS.toSeconds(d.proximityPulseIntervalMillis),
                    ),
                )
            } else {
                source.long(
                    "proximityPulseIntervalMillis",
                    d.proximityPulseIntervalMillis,
                )
            },
            proximityPulseNearFenceDistanceMeters = if (schemaVersion >= 7) {
                source.double(
                    "proximityPulseNearFenceDistanceMeters",
                    d.proximityPulseNearFenceDistanceMeters,
                )
            } else {
                d.proximityPulseNearFenceDistanceMeters
            },
            proximityPulseNearFenceIntervalMillis = if (schemaVersion >= 7) {
                source.long(
                    "proximityPulseNearFenceIntervalMillis",
                    d.proximityPulseNearFenceIntervalMillis,
                )
            } else {
                d.proximityPulseNearFenceIntervalMillis
            },
            proximityConfirmMaxAttempts = if (schemaVersion >= 6) {
                source.int(
                    "proximityConfirmMaxAttempts",
                    d.proximityConfirmMaxAttempts,
                )
            } else {
                d.proximityConfirmMaxAttempts
            },
            proximityPulseTransitionConfirmationIntervalMillis = if (schemaVersion >= 4) {
                source.long(
                    "proximityPulseTransitionConfirmationIntervalMillis",
                    d.proximityPulseTransitionConfirmationIntervalMillis,
                )
            } else {
                d.proximityPulseTransitionConfirmationIntervalMillis
            },
            proximityPulseTransitionConfirmationBurstDurationMillis = if (schemaVersion >= 4) {
                source.long(
                    "proximityPulseTransitionConfirmationBurstDurationMillis",
                    d.proximityPulseTransitionConfirmationBurstDurationMillis,
                )
            } else {
                d.proximityPulseTransitionConfirmationBurstDurationMillis
            },
            proximityPulseActiveStartMinuteOfDay = source.int(
                "proximityPulseActiveStartMinuteOfDay",
                d.proximityPulseActiveStartMinuteOfDay,
            ),
            proximityPulseActiveEndMinuteOfDay = source.int(
                "proximityPulseActiveEndMinuteOfDay",
                d.proximityPulseActiveEndMinuteOfDay,
            ),
            proximityPulseOutsideActiveHoursIntervalMultiplier = if (schemaVersion >= 6) {
                source.int(
                    "proximityPulseOutsideActiveHoursIntervalMultiplier",
                    d.proximityPulseOutsideActiveHoursIntervalMultiplier,
                )
            } else {
                d.proximityPulseOutsideActiveHoursIntervalMultiplier
            },
            proximityPulseMinIntervalMillis = source.long(
                "proximityPulseMinIntervalMillis",
                d.proximityPulseMinIntervalMillis,
            ),
            foregroundNotificationTitle = source.string(
                "foregroundNotificationTitle",
                d.foregroundNotificationTitle,
            ),
            foregroundNotificationChannelId = source.string(
                "foregroundNotificationChannelId",
                d.foregroundNotificationChannelId,
            ),
            foregroundNotificationChannelName = source.string(
                "foregroundNotificationChannelName",
                d.foregroundNotificationChannelName,
            ),
            foregroundNotificationId = source.int(
                "foregroundNotificationId",
                d.foregroundNotificationId,
            ),
            foregroundNotificationSmallIconResourceName = source.nullableString(
                "foregroundNotificationSmallIconResourceName",
            ),
            foregroundNotificationSticky = source.boolean(
                "foregroundNotificationSticky",
                d.foregroundNotificationSticky,
            ),
            foregroundNotificationTapAction = ForegroundNotificationTapAction.fromConfigValue(
                source.string(
                    "foregroundNotificationTapAction",
                    d.foregroundNotificationTapAction.configValue,
                )
            ) ?: d.foregroundNotificationTapAction,
            foregroundNotificationShowWhileMonitoring =
                source.nullableBoolean("foregroundNotificationShowWhileMonitoring")
                    ?: source.nullableBoolean("foregroundNotificationRemoveWhenIdle")?.not()
                    ?: d.foregroundNotificationShowWhileMonitoring,
            activityStationaryTtlMillis = source.long(
                "activityStationaryTtlMillis",
                d.activityStationaryTtlMillis,
            ),
            activityPeriodicBackstopEnabled = source.boolean(
                "activityPeriodicBackstopEnabled",
                d.activityPeriodicBackstopEnabled,
            ),
            activityUpdateIntervalMillis = source.long(
                "activityUpdateIntervalMillis",
                d.activityUpdateIntervalMillis,
            ),
            activityMovingProximityCheckDelayMillis = source.long(
                "activityMovingProximityCheckDelayMillis",
                d.activityMovingProximityCheckDelayMillis,
            ),
            activityFusedLocationStaleAfterMillis = source.long(
                "activityFusedLocationStaleAfterMillis",
                d.activityFusedLocationStaleAfterMillis,
            ),
            recoveryTimesMinuteOfDay = source.intList("recoveryTimesMinuteOfDay")
                .filter { it in 0 until MINUTES_PER_DAY }
                .distinct()
                .sorted()
                .ifEmpty { d.recoveryTimesMinuteOfDay },
            recoveryAlarmPolicy = AlarmSchedulePolicy.fromConfigValue(
                source.string("recoveryAlarmPolicy", d.recoveryAlarmPolicy.configValue)
            ) ?: d.recoveryAlarmPolicy,
            recoveryInexactGuardDelayMillis = source.nullableLong(
                "recoveryInexactGuardDelayMillis",
            ) ?: d.recoveryInexactGuardDelayMillis,
            exactAlarmPermissionMode = ExactAlarmPermissionMode.fromConfigValue(
                source.string(
                    "exactAlarmPermissionMode",
                    d.exactAlarmPermissionMode.configValue,
                )
            ) ?: d.exactAlarmPermissionMode,
            logFileEnabled = source.boolean("logFileEnabled", d.logFileEnabled),
            logFileVerbose = source.boolean("logFileVerbose", d.logFileVerbose),
            maxLogFileBytes = source.int("maxLogFileBytes", d.maxLogFileBytes),
            retryOnCallbackFailure = source.boolean(
                "retryOnCallbackFailure",
                d.retryOnCallbackFailure,
            ),
            passiveLocationEnabled = source.boolean(
                "passiveLocationEnabled",
                d.passiveLocationEnabled,
            ),
            foregroundServiceLaunchTimeoutMillis = source.long(
                "foregroundServiceLaunchTimeoutMillis",
                d.foregroundServiceLaunchTimeoutMillis,
            ),
            foregroundServiceStartDelayMillis = source.long(
                "foregroundServiceStartDelayMillis",
                d.foregroundServiceStartDelayMillis,
            ),
            foregroundServiceRearmDelayMillis = source.long(
                "foregroundServiceRearmDelayMillis",
                d.foregroundServiceRearmDelayMillis,
            ),
            foregroundServiceCallbackTimeoutMillis = source.long(
                "foregroundServiceCallbackTimeoutMillis",
                d.foregroundServiceCallbackTimeoutMillis,
            ),
            foregroundServiceSticky = source.boolean(
                "foregroundServiceSticky",
                d.foregroundServiceSticky,
            ),
            confirmQueueMaxAgeMillis = source.long(
                "confirmQueueMaxAgeMillis",
                d.confirmQueueMaxAgeMillis,
            ),
            timeIntegrityEnabled = timeIntegrity.enabled,
            timeIntegrityConfigJson = timeIntegrity.configJson,
        ).validateTransitionConfiguration()
    }

    private fun SmartGeofenceConfig.toJsonObject(): JSONObject = JSONObject()
        .put("batteryMode", batteryMode)
        .put("locationUnavailablePolicy", locationUnavailablePolicy.configValue)
        .put("proximityRadiusMeters", proximityRadiusMeters)
        .put("escalationEnabled", escalationEnabled)
        .put("proximityLocationPriority", proximityLocationPriority)
        .put("proximityIntervalMillis", proximityIntervalMillis)
        .put("proximityFastestIntervalMillis", proximityFastestIntervalMillis)
        .put("proximityMaxWaitMillis", proximityMaxWaitMillis)
        .put("proximityMinDisplacementMeters", proximityMinDisplacementMeters)
        .put("proximityAdaptiveDisplacementEnabled", proximityAdaptiveDisplacementEnabled)
        .put(
            "proximityAdaptiveNearBoundaryDistanceMeters",
            proximityAdaptiveNearBoundaryDistanceMeters,
        )
        .put(
            "proximityAdaptiveNearBoundaryDisplacementMeters",
            proximityAdaptiveNearBoundaryDisplacementMeters,
        )
        .put(
            "proximityAdaptiveStationaryDisplacementMeters",
            proximityAdaptiveStationaryDisplacementMeters,
        )
        .put("proximityAdaptiveHysteresisMeters", proximityAdaptiveHysteresisMeters)
        .put("passiveLocationPriority", passiveLocationPriority)
        .put("passiveLocationIntervalMillis", passiveLocationIntervalMillis)
        .put("passiveLocationFastestIntervalMillis", passiveLocationFastestIntervalMillis)
        .put("passiveLocationMaxWaitMillis", passiveLocationMaxWaitMillis)
        .put("passiveFollowUpEnabled", passiveFollowUpEnabled)
        .put("locationConfirmTimeoutMillis", locationConfirmTimeoutMillis)
        .put("pulseLocationMaxAccuracyMeters", pulseLocationMaxAccuracyMeters)
        .put("eventLocationMaxAccuracyMeters", eventLocationMaxAccuracyMeters)
        .put("insideEventLocationMaxAccuracyMeters", insideEventLocationMaxAccuracyMeters)
        .put("nativeExitConfirmationEnabled", nativeExitConfirmationEnabled)
        .put("nativeEnterConfirmationEnabled", nativeEnterConfirmationEnabled)
        .put("nativeConfirmDelayMillis", nativeConfirmDelayMillis)
        .put("nativeConfirmMaxAttempts", nativeConfirmMaxAttempts)
        .put("transitionValidationEnabled", transitionValidationEnabled)
        .put("transitionValidationEnterEnabled", transitionValidationEnterEnabled)
        .put("transitionValidationExitEnabled", transitionValidationExitEnabled)
        .put("transitionValidationMinimumDelayMillis", transitionValidationMinimumDelayMillis)
        .put("nativeEnterConfirmRadiusSlackMeters", nativeEnterConfirmRadiusSlackMeters)
        .put("nativeEnterPayloadSanityEnabled", nativeEnterPayloadSanityEnabled)
        .put("nativeEnterPayloadDistanceSlackMeters", nativeEnterPayloadDistanceSlackMeters)
        .put("teleportGuardEnabled", teleportGuardEnabled)
        .put("teleportMaxSpeedMetersPerSecond", teleportMaxSpeedMetersPerSecond)
        .put("mockLocationPolicy", mockLocationPolicy.configValue)
        .put("proximityPulseEnabled", proximityPulseEnabled)
        .put("proximityPulseActivationDistanceMeters", proximityPulseActivationDistanceMeters)
        .put("proximityPulseIntervalMillis", proximityPulseIntervalMillis)
        .put("proximityPulseNearFenceDistanceMeters", proximityPulseNearFenceDistanceMeters)
        .put("proximityPulseNearFenceIntervalMillis", proximityPulseNearFenceIntervalMillis)
        .put("proximityConfirmMaxAttempts", proximityConfirmMaxAttempts)
        .put(
            "proximityPulseTransitionConfirmationIntervalMillis",
            proximityPulseTransitionConfirmationIntervalMillis,
        )
        .put(
            "proximityPulseTransitionConfirmationBurstDurationMillis",
            proximityPulseTransitionConfirmationBurstDurationMillis,
        )
        .put("proximityPulseActiveStartMinuteOfDay", proximityPulseActiveStartMinuteOfDay)
        .put("proximityPulseActiveEndMinuteOfDay", proximityPulseActiveEndMinuteOfDay)
        .put(
            "proximityPulseOutsideActiveHoursIntervalMultiplier",
            proximityPulseOutsideActiveHoursIntervalMultiplier,
        )
        .put("proximityPulseMinIntervalMillis", proximityPulseMinIntervalMillis)
        .put("foregroundNotificationTitle", foregroundNotificationTitle)
        .put("foregroundNotificationChannelId", foregroundNotificationChannelId)
        .put("foregroundNotificationChannelName", foregroundNotificationChannelName)
        .put("foregroundNotificationId", foregroundNotificationId)
        .put(
            "foregroundNotificationSmallIconResourceName",
            foregroundNotificationSmallIconResourceName ?: JSONObject.NULL,
        )
        .put("foregroundNotificationSticky", foregroundNotificationSticky)
        .put("foregroundNotificationTapAction", foregroundNotificationTapAction.configValue)
        .put(
            "foregroundNotificationShowWhileMonitoring",
            foregroundNotificationShowWhileMonitoring,
        )
        .put("activityStationaryTtlMillis", activityStationaryTtlMillis)
        .put("activityPeriodicBackstopEnabled", activityPeriodicBackstopEnabled)
        .put("activityUpdateIntervalMillis", activityUpdateIntervalMillis)
        .put("activityMovingProximityCheckDelayMillis", activityMovingProximityCheckDelayMillis)
        .put("activityFusedLocationStaleAfterMillis", activityFusedLocationStaleAfterMillis)
        .put("recoveryTimesMinuteOfDay", JSONArray(recoveryTimesMinuteOfDay))
        .put("recoveryAlarmPolicy", recoveryAlarmPolicy.configValue)
        .put(
            "recoveryInexactGuardDelayMillis",
            recoveryInexactGuardDelayMillis ?: JSONObject.NULL,
        )
        .put("exactAlarmPermissionMode", exactAlarmPermissionMode.configValue)
        .put("logFileEnabled", logFileEnabled)
        .put("logFileVerbose", logFileVerbose)
        .put("maxLogFileBytes", maxLogFileBytes)
        .put("retryOnCallbackFailure", retryOnCallbackFailure)
        .put("passiveLocationEnabled", passiveLocationEnabled)
        .put("foregroundServiceLaunchTimeoutMillis", foregroundServiceLaunchTimeoutMillis)
        .put("foregroundServiceStartDelayMillis", foregroundServiceStartDelayMillis)
        .put("foregroundServiceRearmDelayMillis", foregroundServiceRearmDelayMillis)
        .put("foregroundServiceCallbackTimeoutMillis", foregroundServiceCallbackTimeoutMillis)
        .put("foregroundServiceSticky", foregroundServiceSticky)
        .put("confirmQueueMaxAgeMillis", confirmQueueMaxAgeMillis)
        .put("timeIntegrity", timeIntegrityJsonValue())

    private fun SmartGeofenceConfig.timeIntegrityJsonValue(): Any =
        if (!timeIntegrityEnabled) {
            JSONObject.NULL
        } else {
            try {
                JSONObject(timeIntegrityConfigJson)
            } catch (error: Throwable) {
                throw IllegalArgumentException("Malformed time-integrity configuration JSON.", error)
            }
        }

    private data class TimeIntegrityValue(
        val enabled: Boolean,
        val configJson: String,
    )

    private fun JSONObject.timeIntegrity(default: SmartGeofenceConfig): TimeIntegrityValue {
        if (has("timeIntegrity")) {
            if (isNull("timeIntegrity")) {
                return TimeIntegrityValue(false, default.timeIntegrityConfigJson)
            }
            val config = opt("timeIntegrity") as? JSONObject
                ?: return TimeIntegrityValue(
                    default.timeIntegrityEnabled,
                    default.timeIntegrityConfigJson,
                )
            return TimeIntegrityValue(true, config.toString())
        }

        return TimeIntegrityValue(
            boolean("timeIntegrityEnabled", default.timeIntegrityEnabled),
            string("timeIntegrityConfigJson", default.timeIntegrityConfigJson),
        )
    }

    private fun Map<*, *>.toJsonObject(): JSONObject = JSONObject().also { result ->
        forEach { (key, value) ->
            if (key is String) result.put(key, value.toJsonValue())
        }
    }

    private fun Any?.toJsonValue(): Any? = when (this) {
        null -> JSONObject.NULL
        is Map<*, *> -> toJsonObject()
        is Iterable<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
        is Array<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
        else -> this
    }

    private fun JSONObject.number(key: String): Number? =
        opt(key).takeUnless { it === JSONObject.NULL } as? Number

    private fun JSONObject.boolean(key: String, fallback: Boolean): Boolean =
        nullableBoolean(key) ?: fallback

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        opt(key).takeUnless { it === JSONObject.NULL } as? Boolean

    private fun JSONObject.string(key: String, fallback: String): String =
        nullableString(key) ?: fallback

    private fun JSONObject.nullableString(key: String): String? =
        opt(key).takeUnless { it === JSONObject.NULL } as? String

    private fun JSONObject.int(key: String, fallback: Int): Int =
        number(key)?.toInt() ?: fallback

    private fun JSONObject.long(key: String, fallback: Long): Long =
        number(key)?.toLong() ?: fallback

    private fun JSONObject.nullableLong(key: String): Long? = number(key)?.toLong()

    private fun JSONObject.double(key: String, fallback: Double): Double =
        number(key)?.toDouble() ?: fallback

    private fun JSONObject.intList(key: String): List<Int> {
        val values = opt(key) as? JSONArray ?: return emptyList()
        return (0 until values.length()).mapNotNull { index ->
            (values.opt(index) as? Number)?.toInt()
        }
    }
}
