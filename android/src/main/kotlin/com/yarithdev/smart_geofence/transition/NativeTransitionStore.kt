package com.yarithdev.smart_geofence.transition

import android.content.Context
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBoolean
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import org.json.JSONArray
import org.json.JSONObject

internal object NativeTransitionStore {
    private const val TAG = "NativeTransitionStore"
    private const val KEY_PENDING = "native_transition_pending_fallbacks_v2"
    private const val KEY_MIGRATED = "native_transition_pending_fallbacks_migrated_v2"
    private const val LEGACY_ENTER_KEY = "native_enter_pending_fallbacks"
    private const val LEGACY_EXIT_KEY = "native_exit_pending_fallbacks"

    fun read(context: Context): MutableMap<NativeTransitionKey, PendingNativeTransition> {
        val preferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val unified = parse(
            context,
            preferences.safeString(KEY_PENDING),
            direction = null,
            sourceKey = KEY_PENDING,
        )
        if (preferences.safeBoolean(KEY_MIGRATED, false)) return unified

        parse(
            context,
            preferences.safeString(LEGACY_ENTER_KEY),
            NativeTransitionDirection.ENTER,
            LEGACY_ENTER_KEY,
        ).forEach { (key, value) -> unified.putIfAbsent(key, value) }
        parse(
            context,
            preferences.safeString(LEGACY_EXIT_KEY),
            NativeTransitionDirection.EXIT,
            LEGACY_EXIT_KEY,
        ).forEach { (key, value) -> unified.putIfAbsent(key, value) }

        if (persist(context, unified.values)) {
            preferences.edit()
                .remove(LEGACY_ENTER_KEY)
                .remove(LEGACY_EXIT_KEY)
                .apply()
        }
        return unified
    }

    fun persist(context: Context, pending: Collection<PendingNativeTransition>): Boolean {
        val array = JSONArray()
        pending.sortedWith(compareBy({ it.direction.name }, { it.fenceId }))
            .forEach { array.put(toJson(it)) }
        val committed = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING, array.toString())
            .putBoolean(KEY_MIGRATED, true)
            .commit()
        if (!committed) {
            SmartGeofenceLogger.w(context, TAG, "Failed to commit pending native transitions.")
        }
        return committed
    }

    private fun parse(
        context: Context,
        raw: String?,
        direction: NativeTransitionDirection?,
        sourceKey: String,
    ): MutableMap<NativeTransitionKey, PendingNativeTransition> {
        if (raw == null) return linkedMapOf()
        return try {
            val array = JSONArray(raw)
            val result = linkedMapOf<NativeTransitionKey, PendingNativeTransition>()
            for (index in 0 until array.length()) {
                val item = fromJson(array.optJSONObject(index) ?: continue, direction) ?: continue
                result[item.key] = item
            }
            result
        } catch (error: Throwable) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Failed to parse pending native transitions from $sourceKey: ${error.message}",
                error,
            )
            linkedMapOf()
        }
    }

    private fun toJson(item: PendingNativeTransition): JSONObject =
        JSONObject()
            .put("direction", item.direction.name.lowercase())
            .put("fenceId", item.fenceId)
            .put("source", item.source)
            .put("createdAtMillis", item.createdAtMillis)
            .put("triggeredAtMillis", item.triggeredAtMillis)
            .put("deadlineAtMillis", item.deadlineAtMillis)
            .put("deadlineAtElapsedRealtimeMillis", item.deadlineAtElapsedRealtimeMillis)
            .put("deadlineBootCount", item.deadlineBootCount)
            .put(
                "deadlineStartedAtElapsedRealtimeMillis",
                item.deadlineStartedAtElapsedRealtimeMillis,
            )
            .put("deadlineStartedAtWallClockMillis", item.deadlineStartedAtWallClockMillis)
            .put("isMock", item.isMock)
            .put("eventMonotonicMillis", item.eventMonotonicMillis)
            .put("androidBootCount", item.androidBootCount)
            .put("timestampOrigin", item.timestampOrigin)
            .put("traceId", item.traceId)
            .put("instanceId", item.instanceId)
            .put("validationRequired", item.validationRequired)
            .put("candidateLocationTimeMillis", item.candidateLocationTimeMillis)
            .put(
                "candidateLocationElapsedRealtimeNanos",
                item.candidateLocationElapsedRealtimeNanos,
            )
            .put("fenceRadiusMeters", item.fenceRadiusMeters)
            .put("confirmationBoundaryMeters", item.confirmationBoundaryMeters)
            .put("minimumDelayMillis", item.minimumDelayMillis)
            .put("validationConfigFingerprint", item.validationConfigFingerprint)
            .put("nativeCandidate", item.nativeCandidate)
            .put("eligibleAtMillis", item.eligibleAtMillis)
            .put("eligibleAtElapsedRealtimeMillis", item.eligibleAtElapsedRealtimeMillis)
            .put("eligibilityBootCount", item.eligibilityBootCount)
            .put(
                "eligibilityStartedAtElapsedRealtimeMillis",
                item.eligibilityStartedAtElapsedRealtimeMillis,
            )
            .put("eligibilityStartedAtWallClockMillis", item.eligibilityStartedAtWallClockMillis)
            .put("confirmationNotBeforeMillis", item.confirmationNotBeforeMillis)
            .also { value ->
                item.latitude?.let { value.put("latitude", it) }
                item.longitude?.let { value.put("longitude", it) }
                item.accuracyMeters?.let { value.put("accuracyMeters", it) }
            }

    private fun fromJson(
        value: JSONObject,
        legacyDirection: NativeTransitionDirection?,
    ): PendingNativeTransition? {
        val direction = legacyDirection ?: runCatching {
            NativeTransitionDirection.valueOf(value.optString("direction").uppercase())
        }.getOrNull() ?: return null
        val fenceId = value.optString("fenceId").takeIf { it.isNotBlank() } ?: return null
        return PendingNativeTransition(
            direction = direction,
            fenceId = fenceId,
            source = value.optString("source"),
            createdAtMillis = value.optLong("createdAtMillis", 0L),
            triggeredAtMillis = value.optLong(
                "triggeredAtMillis",
                value.optLong("createdAtMillis", 0L),
            ),
            deadlineAtMillis = value.optLong("deadlineAtMillis", 0L),
            latitude = optionalDouble(value, "latitude"),
            longitude = optionalDouble(value, "longitude"),
            accuracyMeters = optionalDouble(value, "accuracyMeters"),
            isMock = value.optBoolean("isMock", false),
            eventMonotonicMillis = optionalLong(value, "eventMonotonicMillis"),
            androidBootCount = optionalLong(value, "androidBootCount"),
            timestampOrigin = value.optString("timestampOrigin", "unknown"),
            deadlineAtElapsedRealtimeMillis = optionalLong(
                value,
                "deadlineAtElapsedRealtimeMillis",
            ),
            deadlineBootCount = optionalLong(value, "deadlineBootCount"),
            deadlineStartedAtElapsedRealtimeMillis = optionalLong(
                value,
                "deadlineStartedAtElapsedRealtimeMillis",
            ),
            deadlineStartedAtWallClockMillis = optionalLong(
                value,
                "deadlineStartedAtWallClockMillis",
            ),
            traceId = value.optString("traceId").takeIf { it.isNotBlank() },
            instanceId = value.optString("instanceId"),
            validationRequired = value.optBoolean("validationRequired", false),
            candidateLocationTimeMillis = optionalLong(value, "candidateLocationTimeMillis"),
            candidateLocationElapsedRealtimeNanos = optionalLong(
                value,
                "candidateLocationElapsedRealtimeNanos",
            ),
            fenceRadiusMeters = optionalDouble(value, "fenceRadiusMeters"),
            confirmationBoundaryMeters = optionalDouble(
                value,
                "confirmationBoundaryMeters",
            ),
            minimumDelayMillis = optionalLong(value, "minimumDelayMillis"),
            validationConfigFingerprint = value.optString("validationConfigFingerprint")
                .takeIf { it.isNotBlank() },
            nativeCandidate = value.optBoolean("nativeCandidate", false),
            eligibleAtMillis = optionalLong(value, "eligibleAtMillis")
                ?: optionalLong(value, "deadlineAtMillis"),
            eligibleAtElapsedRealtimeMillis = optionalLong(
                value,
                "eligibleAtElapsedRealtimeMillis",
            ) ?: optionalLong(value, "deadlineAtElapsedRealtimeMillis"),
            eligibilityBootCount = optionalLong(value, "eligibilityBootCount")
                ?: optionalLong(value, "deadlineBootCount"),
            eligibilityStartedAtElapsedRealtimeMillis = optionalLong(
                value,
                "eligibilityStartedAtElapsedRealtimeMillis",
            ) ?: optionalLong(value, "deadlineStartedAtElapsedRealtimeMillis"),
            eligibilityStartedAtWallClockMillis = optionalLong(
                value,
                "eligibilityStartedAtWallClockMillis",
            ) ?: optionalLong(value, "deadlineStartedAtWallClockMillis"),
            confirmationNotBeforeMillis = optionalLong(value, "confirmationNotBeforeMillis"),
        )
    }

    private fun optionalDouble(value: JSONObject, key: String): Double? =
        if (value.has(key) && !value.isNull(key)) value.optDouble(key) else null

    private fun optionalLong(value: JSONObject, key: String): Long? =
        if (value.has(key) && !value.isNull(key)) value.optLong(key) else null
}
