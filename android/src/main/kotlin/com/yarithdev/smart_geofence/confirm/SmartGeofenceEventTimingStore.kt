package com.yarithdev.smart_geofence.confirm

import android.content.Context
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.model.EventTimingEvidence
import org.json.JSONObject

data class SmartGeofenceEventTiming(
    val wallClockEventAtMillis: Long,
    val eventMonotonicMillis: Long?,
    val androidBootCount: Long?,
    val timestampOrigin: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "wallClockEventAtMillis" to wallClockEventAtMillis,
        "eventMonotonicMillis" to eventMonotonicMillis,
        "androidBootCount" to androidBootCount,
        "timestampOrigin" to timestampOrigin,
    )
}

internal fun SmartGeofenceEventTiming.toEventTimingEvidence(): EventTimingEvidence =
    EventTimingEvidence(
        wallClockEventAtMillis = wallClockEventAtMillis,
        eventMonotonicMillis = eventMonotonicMillis,
        androidBootCount = androidBootCount,
        timestampOrigin = timestampOrigin,
    )

internal fun EventTimingEvidence.toSmartGeofenceEventTiming(): SmartGeofenceEventTiming =
    SmartGeofenceEventTiming(
        wallClockEventAtMillis = wallClockEventAtMillis,
        eventMonotonicMillis = eventMonotonicMillis,
        androidBootCount = androidBootCount,
        timestampOrigin = timestampOrigin,
    )

object SmartGeofenceEventTimingStore {
    private const val KEY_TIMINGS = "event_time_integrity_timings"
    private const val MAX_RECORDS = 100

    fun captureNow(
        context: Context,
        wallClockEventAtMillis: Long,
        timestampOrigin: String,
    ): SmartGeofenceEventTiming {
        val appContext = context.applicationContext
        val monotonicTime = captureAndroidMonotonicTime(appContext)
        return SmartGeofenceEventTiming(
            wallClockEventAtMillis = wallClockEventAtMillis,
            eventMonotonicMillis = monotonicTime.elapsedRealtimeMillis,
            androidBootCount = monotonicTime.bootCount,
            timestampOrigin = timestampOrigin,
        )
    }

    @Synchronized
    fun record(
        context: Context,
        fenceId: String,
        eventName: String,
        timing: SmartGeofenceEventTiming,
    ) {
        val appContext = context.applicationContext
        val records = read(appContext)
        records[key(fenceId, eventName, timing.wallClockEventAtMillis)] = timing
        while (records.size > MAX_RECORDS) {
            val first = records.keys.firstOrNull() ?: break
            records.remove(first)
        }
        persist(appContext, records)
    }

    @Synchronized
    fun lookup(
        context: Context,
        fenceId: String?,
        eventName: String?,
        eventAtMillis: Long?,
    ): SmartGeofenceEventTiming? {
        if (fenceId.isNullOrBlank() || eventName.isNullOrBlank() || eventAtMillis == null) {
            return null
        }
        return read(context.applicationContext)[key(fenceId, eventName, eventAtMillis)]
    }

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TIMINGS)
            .apply()
    }

    private fun key(fenceId: String, eventName: String, eventAtMillis: Long): String =
        "${fenceId}|${eventName.lowercase()}|$eventAtMillis"

    private fun read(context: Context): LinkedHashMap<String, SmartGeofenceEventTiming> {
        val raw = context
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TIMINGS, null) ?: return linkedMapOf()
        return runCatching {
            val json = JSONObject(raw)
            val result = linkedMapOf<String, SmartGeofenceEventTiming>()
            json.keys().forEach { key ->
                fromJson(json.optJSONObject(key))?.let { result[key] = it }
            }
            result
        }.getOrElse { linkedMapOf() }
    }

    private fun persist(
        context: Context,
        records: Map<String, SmartGeofenceEventTiming>,
    ) {
        val json = JSONObject()
        records.forEach { (key, value) -> json.put(key, toJson(value)) }
        context
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TIMINGS, json.toString())
            .apply()
    }

    private fun toJson(timing: SmartGeofenceEventTiming): JSONObject =
        JSONObject()
            .put("wallClockEventAtMillis", timing.wallClockEventAtMillis)
            .put("eventMonotonicMillis", timing.eventMonotonicMillis)
            .put("androidBootCount", timing.androidBootCount)
            .put("timestampOrigin", timing.timestampOrigin)

    private fun fromJson(value: JSONObject?): SmartGeofenceEventTiming? {
        value ?: return null
        val wallClockEventAtMillis = value.optLong("wallClockEventAtMillis", 0L)
            .takeIf { it > 0L }
            ?: return null
        return SmartGeofenceEventTiming(
            wallClockEventAtMillis = wallClockEventAtMillis,
            eventMonotonicMillis = value.optNullableLong("eventMonotonicMillis"),
            androidBootCount = value.optNullableLong("androidBootCount"),
            timestampOrigin = value.optString("timestampOrigin", "unknown"),
        )
    }
}

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
