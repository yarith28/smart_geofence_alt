package com.yarithdev.smart_geofence.store

import android.content.Context
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.confirm.LocationConfirmManager
import com.yarithdev.smart_geofence.confirm.NativeEnterPendingStore
import com.yarithdev.smart_geofence.confirm.NativeExitPendingStore
import com.yarithdev.smart_geofence.delivery.EventDedupStore
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import org.json.JSONArray
import org.json.JSONObject

data class SmartGeofenceFence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val requestedRadiusMeters: Double = radiusMeters,
    val triggersEnter: Boolean,
    val triggersExit: Boolean,
    val triggersDwell: Boolean,
    val callbackHandle: Long,
    val dispatchCallbackHandle: Long,
    val armed: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("lat", latitude)
        put("lng", longitude)
        put("radius", radiusMeters)
        put("requestedRadius", requestedRadiusMeters)
        put("enter", triggersEnter)
        put("exit", triggersExit)
        put("dwell", triggersDwell)
        put("cb", callbackHandle)
        put("dispatchCb", dispatchCallbackHandle)
        put("armed", armed)
    }

    fun hasSameMeaningfulDefinition(other: SmartGeofenceFence): Boolean =
        id == other.id &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            radiusMeters == other.radiusMeters &&
            triggersEnter == other.triggersEnter &&
            triggersExit == other.triggersExit &&
            triggersDwell == other.triggersDwell

    fun toMap(): Map<String, Any?> {
        val triggers = mutableListOf<String>()
        if (triggersEnter) triggers.add("enter")
        if (triggersExit) triggers.add("exit")
        if (triggersDwell) triggers.add("dwell")
        return linkedMapOf(
            "id" to id,
            "latitude" to latitude,
            "longitude" to longitude,
            "radiusMeters" to radiusMeters,
            "requestedRadiusMeters" to requestedRadiusMeters,
            "effectiveRadiusMeters" to radiusMeters,
            "radiusNormalization" to radiusNormalizationMap(),
            "triggers" to triggers,
            "callbackHandle" to callbackHandle,
            "dispatchCallbackHandle" to dispatchCallbackHandle,
            "armed" to armed,
        )
    }

    fun radiusNormalizationMap(): Map<String, Any?> = linkedMapOf(
        "platform" to "android",
        "requestedRadiusMeters" to requestedRadiusMeters,
        "effectiveRadiusMeters" to radiusMeters,
        "wasAdjusted" to (requestedRadiusMeters != radiusMeters),
        "reason" to if (requestedRadiusMeters == radiusMeters) {
            "none"
        } else {
            "androidMinimum"
        },
    )

    companion object {
        fun fromJson(o: JSONObject): SmartGeofenceFence = SmartGeofenceFence(
            id = o.getString("id"),
            latitude = o.getDouble("lat"),
            longitude = o.getDouble("lng"),
            radiusMeters = o.getDouble("radius"),
            requestedRadiusMeters = if (o.has("requestedRadius")) {
                o.optDouble("requestedRadius", o.getDouble("radius"))
            } else {
                o.getDouble("radius")
            },
            triggersEnter = o.optBoolean("enter", false),
            triggersExit = o.optBoolean("exit", false),
            triggersDwell = o.optBoolean("dwell", false),
            callbackHandle = o.optLong("cb", 0L),
            dispatchCallbackHandle = o.optLong("dispatchCb", o.optLong("cb", 0L)),
            armed = if (o.has("armed")) o.optBoolean("armed", true) else true,
        )
    }
}

internal data class FenceReplacementStateChanges(
    val retainedIds: Set<String>,
    val changedIds: Set<String>,
    val removedIds: Set<String>,
)

object FenceStore {
    private const val TAG = "FenceStore"
    private const val KEY_FENCES = "pro_fences"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun upsert(context: Context, fence: SmartGeofenceFence): SmartGeofenceFence? {
        val all = getAll(context, includePending = true).associateBy { it.id }.toMutableMap()
        val previous = all[fence.id]
        all[fence.id] = fence
        persist(context, all.values)
        return previous
    }

    fun applyDefinitionChange(context: Context, id: String) {
        FenceObservationStore.remove(context, id)
        EventDedupStore.remove(context, id)
        cancelPendingNativeTransitions(context, setOf(id), "fence_changed")
    }

    fun remove(context: Context, id: String): Set<String> {
        val previous = getAll(context, includePending = true)
        val all = previous.filterNot { it.id == id }
        persist(context, all)
        return if (previous.any { it.id == id }) setOf(id) else emptySet()
    }

    fun removeAll(context: Context): Set<String> {
        val removedIds = getAll(context, includePending = true).map { it.id }.toSet() +
            pendingNativeFenceIds(context)
        persist(context, emptyList())
        return removedIds
    }

    fun applyRemovedState(context: Context, removedIds: Set<String>) {
        removedIds.forEach { id ->
            FenceObservationStore.remove(context, id)
            EventDedupStore.remove(context, id)
        }
        cancelPendingNativeTransitions(context, removedIds, "fence_removed")
    }

    fun applyRemoveAllState(context: Context, removedIds: Set<String>) {
        FenceObservationStore.clear(context)
        EventDedupStore.clear(context)
        cancelPendingNativeTransitions(context, removedIds, "fence_removed")
    }

    internal fun replaceAll(
        context: Context,
        fences: Collection<SmartGeofenceFence>,
    ): FenceReplacementStateChanges {
        val previous = getAll(context, includePending = true).associateBy { it.id }
        val replacement = fences.associateBy { it.id }
        val removedIds = (previous.keys + pendingNativeFenceIds(context)) - replacement.keys
        val changedIds = replacement.mapNotNull { (id, fence) ->
            val old = previous[id] ?: return@mapNotNull null
            id.takeUnless { old.hasSameMeaningfulDefinition(fence) }
        }.toSet()
        persist(context, fences)
        return FenceReplacementStateChanges(
            retainedIds = replacement.keys,
            changedIds = changedIds,
            removedIds = removedIds,
        )
    }

    internal fun applyReplacementState(
        context: Context,
        changes: FenceReplacementStateChanges,
    ) {
        FenceObservationStore.retainOnly(context, changes.retainedIds)
        EventDedupStore.retainOnly(context, changes.retainedIds)
        changes.changedIds.forEach { id ->
            FenceObservationStore.remove(context, id)
            EventDedupStore.remove(context, id)
        }
        cancelPendingNativeTransitions(context, changes.changedIds, "fence_changed")
        cancelPendingNativeTransitions(context, changes.removedIds, "fence_removed")
    }

    fun getAll(context: Context, includePending: Boolean = false): List<SmartGeofenceFence> {
        val raw = prefs(context).safeString(KEY_FENCES) ?: return emptyList()
        val fences = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { SmartGeofenceFence.fromJson(arr.getJSONObject(it)) }
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(context, TAG, "Failed to parse fence store: ${e.message}", e)
            emptyList()
        }
        return if (includePending) fences else fences.filter { it.armed }
    }

    fun get(
        context: Context,
        id: String,
        includePending: Boolean = false,
    ): SmartGeofenceFence? = getAll(context, includePending).firstOrNull { it.id == id }

    private fun persist(context: Context, fences: Collection<SmartGeofenceFence>) {
        val arr = JSONArray()
        fences.forEach { arr.put(it.toJson()) }
        if (!prefs(context).edit().putString(KEY_FENCES, arr.toString()).commit()) {
            SmartGeofenceLogger.w(context, TAG, "Failed to commit smart fence mirrors.")
            throw IllegalStateException("Failed to commit smart fence mirrors.")
        }
    }

    private fun cancelPendingNativeTransitions(
        context: Context,
        fenceIds: Set<String>,
        reason: String,
    ) {
        if (fenceIds.isEmpty()) return
        val appContext = context.applicationContext
        val exitCancelled = NativeExitPendingStore.cancel(appContext, fenceIds, reason)
        if (exitCancelled.isNotEmpty()) {
            LocationConfirmManager.cancelNativeExitConfirmIfNoPending(appContext, reason)
        }
        val enterCancelled = NativeEnterPendingStore.cancel(appContext, fenceIds, reason)
        if (enterCancelled.isNotEmpty()) {
            LocationConfirmManager.cancelNativeEnterConfirmIfNoPending(appContext, reason)
        }
        if (exitCancelled.isNotEmpty() || enterCancelled.isNotEmpty()) {
            LocationConfirmManager.cancelPendingValidationPulseIfNoPending(appContext, reason)
        }
    }

    private fun pendingNativeFenceIds(context: Context): Set<String> =
        NativeExitPendingStore.pendingFenceIds(context).toSet() +
            NativeEnterPendingStore.pendingFenceIds(context).toSet()
}
