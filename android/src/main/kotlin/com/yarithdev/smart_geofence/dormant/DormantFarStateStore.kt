package com.yarithdev.smart_geofence.dormant

import android.content.Context
import android.content.SharedPreferences
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBooleanOrNull
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeLongOrNull
import com.yarithdev.smart_geofence.core.safeString

data class DormantFarState(
    val enteredAtMillis: Long,
    val edgeDistanceMeters: Double,
    val nearestFenceId: String?,
    val lastAcceptedFixAtMillis: Long,
    val lastAcceptedFixSource: String,
    val nextProbeAtMillis: Long,
    val reason: String,
    val batteryMode: String,
    val generation: Long = 0L,
)

object DormantFarStateStore {
    private const val KEY_ACTIVE = "dormant_far_active"
    private const val KEY_ENTERED_AT = "dormant_far_entered_at"
    private const val KEY_EDGE_DISTANCE_BITS = "dormant_far_edge_distance_bits"
    private const val KEY_NEAREST_FENCE_ID = "dormant_far_nearest_fence_id"
    private const val KEY_LAST_ACCEPTED_FIX_AT = "dormant_far_last_accepted_fix_at"
    private const val KEY_LAST_ACCEPTED_FIX_SOURCE = "dormant_far_last_accepted_fix_source"
    private const val KEY_NEXT_PROBE_AT = "dormant_far_next_probe_at"
    private const val KEY_REASON = "dormant_far_reason"
    private const val KEY_BATTERY_MODE = "dormant_far_battery_mode"
    private const val KEY_GENERATION = "dormant_far_generation"
    private const val KEY_NEXT_GENERATION = "dormant_far_next_generation"
    private const val KEY_LAST_PROBE_AT = "dormant_far_last_probe_at"
    private const val KEY_LAST_PROBE_RESULT = "dormant_far_last_probe_result"

    fun load(context: Context): DormantFarState? {
        val prefs = prefs(context)
        if (prefs.safeBooleanOrNull(KEY_ACTIVE) != true) return null
        val edgeBits = prefs.safeLongOrNull(KEY_EDGE_DISTANCE_BITS) ?: return null
        val source = prefs.safeString(KEY_LAST_ACCEPTED_FIX_SOURCE) ?: return null
        val reason = prefs.safeString(KEY_REASON) ?: return null
        val batteryMode = prefs.safeString(KEY_BATTERY_MODE) ?: return null
        return DormantFarState(
            enteredAtMillis = prefs.safeLong(KEY_ENTERED_AT, 0L),
            edgeDistanceMeters = Double.fromBits(edgeBits),
            nearestFenceId = prefs.safeString(KEY_NEAREST_FENCE_ID),
            lastAcceptedFixAtMillis = prefs.safeLong(KEY_LAST_ACCEPTED_FIX_AT, 0L),
            lastAcceptedFixSource = source,
            nextProbeAtMillis = prefs.safeLong(KEY_NEXT_PROBE_AT, 0L),
            reason = reason,
            batteryMode = batteryMode,
            generation = prefs.safeLong(KEY_GENERATION, 0L),
        )
    }

    @Synchronized
    fun nextGeneration(context: Context): Long {
        val preferences = prefs(context)
        val current = preferences.safeLong(KEY_NEXT_GENERATION, 0L)
        val next = if (current == Long.MAX_VALUE) 1L else (current + 1L).coerceAtLeast(1L)
        preferences.edit().putLong(KEY_NEXT_GENERATION, next).apply()
        return next
    }

    @Synchronized
    fun save(context: Context, state: DormantFarState) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_ENTERED_AT, state.enteredAtMillis)
            .putLong(KEY_EDGE_DISTANCE_BITS, state.edgeDistanceMeters.toRawBits())
            .putNullableString(KEY_NEAREST_FENCE_ID, state.nearestFenceId)
            .putLong(KEY_LAST_ACCEPTED_FIX_AT, state.lastAcceptedFixAtMillis)
            .putString(KEY_LAST_ACCEPTED_FIX_SOURCE, state.lastAcceptedFixSource)
            .putLong(KEY_NEXT_PROBE_AT, state.nextProbeAtMillis)
            .putString(KEY_REASON, state.reason)
            .putString(KEY_BATTERY_MODE, state.batteryMode)
            .putLong(KEY_GENERATION, state.generation)
            .apply()
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_ENTERED_AT)
            .remove(KEY_EDGE_DISTANCE_BITS)
            .remove(KEY_NEAREST_FENCE_ID)
            .remove(KEY_LAST_ACCEPTED_FIX_AT)
            .remove(KEY_LAST_ACCEPTED_FIX_SOURCE)
            .remove(KEY_NEXT_PROBE_AT)
            .remove(KEY_REASON)
            .remove(KEY_BATTERY_MODE)
            .remove(KEY_GENERATION)
            .apply()
    }

    fun recordProbeResult(context: Context, result: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_PROBE_AT, System.currentTimeMillis())
            .putString(KEY_LAST_PROBE_RESULT, result)
            .apply()
    }

    fun diagnosticMap(context: Context): Map<String, Any?> {
        val prefs = prefs(context)
        val state = load(context)
        return linkedMapOf(
            "dormantFarActive" to (state != null),
            "dormantFarReason" to state?.reason,
            "dormantFarBatteryMode" to state?.batteryMode,
            "dormantFarLastEdgeDistanceMeters" to state?.edgeDistanceMeters,
            "dormantFarNearestFenceId" to state?.nearestFenceId,
            "dormantFarLastAcceptedFixSource" to state?.lastAcceptedFixSource,
            "dormantFarLastAcceptedFixAtMillis" to state?.lastAcceptedFixAtMillis,
            "dormantFarNextProbeAtMillis" to state?.nextProbeAtMillis,
            "dormantFarLastProbeAtMillis" to prefs.safeLongOrNull(KEY_LAST_PROBE_AT),
            "dormantFarLastProbeResult" to prefs.safeString(KEY_LAST_PROBE_RESULT),
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)
}
