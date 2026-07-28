package com.yarithdev.smart_geofence.proximitypulse

import android.content.Context
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBoolean
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString
import org.json.JSONArray

enum class ProximityPulsePurpose {
    PROXIMITY,
    NEAR_FENCE,
    INSIDE,
    TRANSITION_CONFIRMATION,
    FUSED_LIVENESS,
}

data class ProximityPulseState(
    val startedAtMillis: Long,
    val purpose: ProximityPulsePurpose,
    val schedulingActive: Boolean,
    val proximityFenceIds: Set<String> = emptySet(),
    val nearFenceIds: Set<String> = emptySet(),
    val insideFenceIds: Set<String> = emptySet(),
    val livenessStartedAtMillis: Long? = null,
)

object ProximityPulseStateStore {
    private const val KEY_STARTED_AT = "proximity_pulse_started_at_millis"
    private const val KEY_PURPOSE = "proximity_pulse_purpose"
    private const val KEY_SCHEDULING_ACTIVE = "proximity_pulse_scheduling_active"
    private const val KEY_PROXIMITY_FENCE_IDS = "proximity_pulse_proximity_fence_ids"
    private const val KEY_NEAR_FENCE_IDS = "proximity_pulse_near_fence_ids"
    private const val KEY_INSIDE_FENCE_IDS = "proximity_pulse_inside_fence_ids"
    private const val KEY_LIVENESS_STARTED_AT = "proximity_pulse_liveness_started_at_millis"

    @Synchronized
    fun load(context: Context): ProximityPulseState? {
        val prefs = prefs(context)
        val startedAt = prefs.safeLong(KEY_STARTED_AT, 0L)
        val rawPurpose = prefs.safeString(KEY_PURPOSE) ?: return null
        if (rawPurpose == "BOUNDARY_FOLLOW_UP") return null
        val purpose = runCatching { ProximityPulsePurpose.valueOf(rawPurpose) }.getOrNull()
            ?: return null
        if (startedAt <= 0L) return null
        return ProximityPulseState(
            startedAtMillis = startedAt,
            purpose = purpose,
            schedulingActive = prefs.safeBoolean(KEY_SCHEDULING_ACTIVE, true),
            proximityFenceIds = decodeIds(prefs.safeString(KEY_PROXIMITY_FENCE_IDS)),
            nearFenceIds = decodeIds(prefs.safeString(KEY_NEAR_FENCE_IDS)),
            insideFenceIds = decodeIds(prefs.safeString(KEY_INSIDE_FENCE_IDS)),
            livenessStartedAtMillis = prefs.safeLong(KEY_LIVENESS_STARTED_AT, 0L)
                .takeIf { it > 0L }
                ?: startedAt.takeIf { purpose == ProximityPulsePurpose.FUSED_LIVENESS },
        )
    }

    @Synchronized
    fun hasLegacyBoundaryState(context: Context): Boolean =
        prefs(context).safeString(KEY_PURPOSE) == "BOUNDARY_FOLLOW_UP"

    @Synchronized
    fun save(context: Context, state: ProximityPulseState) {
        val editor = prefs(context).edit()
            .putLong(KEY_STARTED_AT, state.startedAtMillis)
            .putString(KEY_PURPOSE, state.purpose.name)
            .putBoolean(KEY_SCHEDULING_ACTIVE, state.schedulingActive)
            .putString(KEY_PROXIMITY_FENCE_IDS, encodeIds(state.proximityFenceIds))
            .putString(KEY_NEAR_FENCE_IDS, encodeIds(state.nearFenceIds))
            .putString(KEY_INSIDE_FENCE_IDS, encodeIds(state.insideFenceIds))
        if (state.livenessStartedAtMillis == null) {
            editor.remove(KEY_LIVENESS_STARTED_AT)
        } else {
            editor.putLong(KEY_LIVENESS_STARTED_AT, state.livenessStartedAtMillis)
        }
        if (!editor.commit()) error("Failed to persist pulse state.")
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_STARTED_AT)
            .remove(KEY_PURPOSE)
            .remove(KEY_SCHEDULING_ACTIVE)
            .remove(KEY_PROXIMITY_FENCE_IDS)
            .remove(KEY_NEAR_FENCE_IDS)
            .remove(KEY_INSIDE_FENCE_IDS)
            .remove(KEY_LIVENESS_STARTED_AT)
            .remove("proximity_pulse_idle_ticks")
            .remove("proximity_pulse_rate_mode")
            .apply()
    }

    internal fun isPersistedStateCorrupt(context: Context): Boolean {
        val prefs = prefs(context)
        val rawPurpose = prefs.safeString(KEY_PURPOSE) ?: return false
        if (rawPurpose == "BOUNDARY_FOLLOW_UP") return false
        return prefs.safeLong(KEY_STARTED_AT, 0L) <= 0L ||
            runCatching { ProximityPulsePurpose.valueOf(rawPurpose) }.getOrNull() == null
    }

    private fun decodeIds(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun encodeIds(ids: Set<String>): String = JSONArray(ids.sorted()).toString()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
