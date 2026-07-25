package com.yarithdev.smart_geofence.monitoring

import android.content.Context
import android.content.SharedPreferences
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class MonitoringStopReason(val configValue: String) {
    FINE_LOCATION_PERMISSION_DENIED("fineLocationPermissionDenied"),
    BACKGROUND_LOCATION_PERMISSION_DENIED("backgroundLocationPermissionDenied"),
    LOCATION_SERVICES_DISABLED("locationServicesDisabled");

    companion object {
        fun fromConfigValue(value: String?): MonitoringStopReason? =
            entries.firstOrNull { it.configValue == value }
    }
}

enum class MonitoringStopPhase(val configValue: String) {
    STOPPING("stopping"),
    STOPPED("stopped");

    companion object {
        fun fromConfigValue(value: String?): MonitoringStopPhase? =
            entries.firstOrNull { it.configValue == value }
    }
}

data class MonitoringStoppedEvent(
    val eventId: String,
    val reason: MonitoringStopReason,
    val stoppedAtMillis: Long,
) {
    fun toMap(): Map<String, Any> = linkedMapOf(
        "eventId" to eventId,
        "reason" to reason.configValue,
        "stoppedAtMillis" to stoppedAtMillis,
    )
}

data class MonitoringStopSnapshot(
    val terminallyStopped: Boolean,
    val phase: MonitoringStopPhase?,
    val event: MonitoringStoppedEvent?,
    val callbackPending: Boolean,
    val pendingNativeCleanupFenceIds: Set<String>,
) {
    val nativeCleanupComplete: Boolean
        get() = pendingNativeCleanupFenceIds.isEmpty()

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "terminallyStopped" to terminallyStopped,
        "phase" to phase?.configValue,
        "reason" to event?.reason?.configValue,
        "stoppedAtMillis" to event?.stoppedAtMillis,
        "eventId" to event?.eventId,
        "callbackPending" to callbackPending,
        "nativeCleanupComplete" to nativeCleanupComplete,
        "pendingNativeCleanupFenceIds" to pendingNativeCleanupFenceIds.sorted(),
    )
}

internal fun monitoringStopPhaseAfterCleanup(
    pendingNativeCleanupFenceIds: Set<String>,
): MonitoringStopPhase =
    if (pendingNativeCleanupFenceIds.isEmpty()) {
        MonitoringStopPhase.STOPPED
    } else {
        MonitoringStopPhase.STOPPING
    }

internal fun shouldExposeMonitoringStoppedEvent(
    snapshot: MonitoringStopSnapshot,
): Boolean =
    snapshot.callbackPending &&
        snapshot.phase == MonitoringStopPhase.STOPPED &&
        snapshot.nativeCleanupComplete

internal fun enqueueMonitoringStoppedEvent(
    events: List<MonitoringStoppedEvent>,
    event: MonitoringStoppedEvent,
): List<MonitoringStoppedEvent> =
    if (events.any { it.eventId == event.eventId }) {
        events
    } else {
        events + event
    }

internal fun encodeMonitoringStoppedEventQueue(
    events: List<MonitoringStoppedEvent>,
): String = JSONArray().apply {
    events.forEach { event ->
        put(
            JSONObject()
                .put("eventId", event.eventId)
                .put("reason", event.reason.configValue)
                .put("stoppedAtMillis", event.stoppedAtMillis),
        )
    }
}.toString()

internal fun decodeMonitoringStoppedEventQueue(
    raw: String,
): List<MonitoringStoppedEvent> = runCatching {
    val array = JSONArray(raw)
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val eventId = item.optString("eventId").takeIf { it.isNotBlank() } ?: continue
            val reason = MonitoringStopReason.fromConfigValue(item.optString("reason")) ?: continue
            val stoppedAtMillis = item.optLong("stoppedAtMillis", 0L)
            if (stoppedAtMillis <= 0L) continue
            add(MonitoringStoppedEvent(eventId, reason, stoppedAtMillis))
        }
    }
}.getOrDefault(emptyList())

object MonitoringStopStateStore {
    private const val KEY_TERMINALLY_STOPPED = "monitoring_terminal_stop_active"
    private const val KEY_PHASE = "monitoring_terminal_stop_phase"
    private const val KEY_EVENT_ID = "monitoring_terminal_stop_event_id"
    private const val KEY_REASON = "monitoring_terminal_stop_reason"
    private const val KEY_STOPPED_AT = "monitoring_terminal_stop_at_millis"
    private const val KEY_CALLBACK_PENDING = "monitoring_terminal_stop_callback_pending"
    private const val KEY_PENDING_NATIVE_CLEANUP_IDS =
        "monitoring_terminal_stop_pending_native_cleanup_ids"
    private const val KEY_READY_EVENT_QUEUE = "monitoring_terminal_stop_ready_event_queue_v2"

    @Synchronized
    fun snapshot(context: Context): MonitoringStopSnapshot {
        val prefs = prefs(context.applicationContext)
        val eventId = prefs.safeString(KEY_EVENT_ID)
        val reason = MonitoringStopReason.fromConfigValue(prefs.safeString(KEY_REASON))
        val stoppedAtMillis = prefs.safeLong(KEY_STOPPED_AT, 0L)
        val event = if (
            !eventId.isNullOrBlank() &&
            reason != null &&
            stoppedAtMillis > 0L
        ) {
            MonitoringStoppedEvent(eventId, reason, stoppedAtMillis)
        } else {
            null
        }
        return MonitoringStopSnapshot(
            terminallyStopped = prefs.getBoolean(KEY_TERMINALLY_STOPPED, false),
            phase = MonitoringStopPhase.fromConfigValue(prefs.safeString(KEY_PHASE)),
            event = event,
            callbackPending = prefs.getBoolean(KEY_CALLBACK_PENDING, false) && event != null,
            pendingNativeCleanupFenceIds = prefs
                .getStringSet(KEY_PENDING_NATIVE_CLEANUP_IDS, emptySet())
                ?.filterTo(linkedSetOf()) { it.isNotBlank() }
                ?: emptySet(),
        )
    }

    @Synchronized
    fun begin(
        context: Context,
        reason: MonitoringStopReason,
        nativeCleanupFenceIds: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): MonitoringStopSnapshot {
        val appContext = context.applicationContext
        val existing = snapshot(appContext)
        if (existing.terminallyStopped) return existing
        val readyEvents = readyEventQueue(appContext, existing)
        val eventId = UUID.randomUUID().toString()
        val editor = prefs(appContext).edit()
            .putBoolean(KEY_TERMINALLY_STOPPED, true)
            .putString(KEY_PHASE, MonitoringStopPhase.STOPPING.configValue)
            .putString(KEY_EVENT_ID, eventId)
            .putString(KEY_REASON, reason.configValue)
            .putLong(KEY_STOPPED_AT, nowMillis)
            .putBoolean(KEY_CALLBACK_PENDING, true)
            .putStringSet(KEY_PENDING_NATIVE_CLEANUP_IDS, nativeCleanupFenceIds)
        writeReadyEventQueue(editor, readyEvents)
        val committed = editor.commit()
        check(committed) { "Failed to persist terminal monitoring stop." }
        return snapshot(appContext)
    }

    @Synchronized
    fun mergePendingNativeCleanupFenceIds(
        context: Context,
        fenceIds: Set<String>,
    ): MonitoringStopSnapshot {
        val appContext = context.applicationContext
        val current = snapshot(appContext)
        if (!current.terminallyStopped || fenceIds.isEmpty()) return current
        val pending = current.pendingNativeCleanupFenceIds + fenceIds.filter { it.isNotBlank() }
        if (pending == current.pendingNativeCleanupFenceIds) return current
        val committed = prefs(appContext).edit()
            .putString(KEY_PHASE, MonitoringStopPhase.STOPPING.configValue)
            .putStringSet(KEY_PENDING_NATIVE_CLEANUP_IDS, pending)
            .commit()
        check(committed) { "Failed to extend terminal monitoring cleanup scope." }
        return snapshot(appContext)
    }

    @Synchronized
    fun completeCleanupAttempt(
        context: Context,
        removedFenceIds: Set<String>,
    ): MonitoringStopSnapshot {
        val appContext = context.applicationContext
        val current = snapshot(appContext)
        if (!current.terminallyStopped) return current
        val pending = current.pendingNativeCleanupFenceIds - removedFenceIds
        val phase = monitoringStopPhaseAfterCleanup(pending)
        var readyEvents = readyEventQueue(appContext, current)
        if (phase == MonitoringStopPhase.STOPPED && current.callbackPending) {
            current.event?.let { readyEvents = enqueueMonitoringStoppedEvent(readyEvents, it) }
        }
        val editor = prefs(appContext).edit()
            .putString(KEY_PHASE, phase.configValue)
            .putStringSet(KEY_PENDING_NATIVE_CLEANUP_IDS, pending)
        writeReadyEventQueue(editor, readyEvents)
        val committed = editor.commit()
        check(committed) { "Failed to persist terminal monitoring cleanup result." }
        return snapshot(appContext)
    }

    @Synchronized
    fun pendingEvent(context: Context): MonitoringStoppedEvent? =
        context.applicationContext.let { appContext ->
            readyEventQueue(appContext, snapshot(appContext)).firstOrNull()
        }

    @Synchronized
    fun acknowledge(context: Context, eventId: String): Boolean {
        val appContext = context.applicationContext
        val current = snapshot(appContext)
        val readyEvents = readyEventQueue(appContext, current)
        if (readyEvents.none { it.eventId == eventId }) return false
        val editor = prefs(appContext).edit()
        writeReadyEventQueue(
            editor,
            readyEvents.filterNot { it.eventId == eventId },
        )
        if (current.event?.eventId == eventId) {
            editor.putBoolean(KEY_CALLBACK_PENDING, false)
        }
        if (!current.terminallyStopped && current.event?.eventId == eventId) {
            editor
                .remove(KEY_EVENT_ID)
                .remove(KEY_REASON)
                .remove(KEY_STOPPED_AT)
                .remove(KEY_CALLBACK_PENDING)
        }
        return editor.commit()
    }

    @Synchronized
    fun beginNewSession(context: Context): Boolean {
        val appContext = context.applicationContext
        val current = snapshot(appContext)
        if (!current.terminallyStopped) return true
        if (!current.nativeCleanupComplete) return false
        val readyEvents = readyEventQueue(appContext, current)
        val editor = prefs(appContext).edit()
            .putBoolean(KEY_TERMINALLY_STOPPED, false)
            .remove(KEY_PHASE)
            .remove(KEY_PENDING_NATIVE_CLEANUP_IDS)
        writeReadyEventQueue(editor, readyEvents)
        if (!current.callbackPending) {
            editor
                .remove(KEY_EVENT_ID)
                .remove(KEY_REASON)
                .remove(KEY_STOPPED_AT)
                .remove(KEY_CALLBACK_PENDING)
        }
        return editor.commit()
    }

    private fun readyEventQueue(
        context: Context,
        current: MonitoringStopSnapshot,
    ): List<MonitoringStoppedEvent> {
        val preferences = prefs(context)
        val stored = readReadyEventQueue(preferences)
        val legacyReady = current.event?.takeIf {
            current.callbackPending &&
                (shouldExposeMonitoringStoppedEvent(current) || !current.terminallyStopped)
        } ?: return stored
        val merged = enqueueMonitoringStoppedEvent(stored, legacyReady)
        if (merged !== stored) {
            val editor = preferences.edit()
            writeReadyEventQueue(editor, merged)
            check(editor.commit()) { "Failed to migrate the monitoring stopped event queue." }
        }
        return merged
    }

    private fun readReadyEventQueue(
        preferences: SharedPreferences,
    ): List<MonitoringStoppedEvent> =
        runCatching { preferences.getString(KEY_READY_EVENT_QUEUE, null) }
            .getOrNull()
            ?.let(::decodeMonitoringStoppedEventQueue)
            .orEmpty()

    private fun writeReadyEventQueue(
        editor: SharedPreferences.Editor,
        events: List<MonitoringStoppedEvent>,
    ) {
        if (events.isEmpty()) {
            editor.remove(KEY_READY_EVENT_QUEUE)
            return
        }
        editor.putString(KEY_READY_EVENT_QUEUE, encodeMonitoringStoppedEventQueue(events))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
