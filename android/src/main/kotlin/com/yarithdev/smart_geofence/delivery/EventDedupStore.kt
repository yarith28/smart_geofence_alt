package com.yarithdev.smart_geofence.delivery

import android.content.Context
import android.content.SharedPreferences
import com.chunkytofustudios.native_geofence.generated.GeofenceEvent
import com.yarithdev.smart_geofence.confirm.AndroidMonotonicTime
import com.yarithdev.smart_geofence.confirm.captureAndroidMonotonicTime
import com.yarithdev.smart_geofence.confirm.monotonicAgeMillis
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import org.json.JSONObject
import java.util.UUID

internal data class EventDedupClaim(
    val fenceId: String,
    val eventName: String,
    val claimedAtMillis: Long,
    val claimedAtElapsedRealtimeMillis: Long?,
    val claimedBootCount: Long?,
    val eventId: String,
    val deliverySequence: Long,
    internal val previous: EventDedupRecord?,
)

internal data class EventDedupRecord(
    val eventName: String,
    val source: String,
    val deliveredAtMillis: Long,
    val claimedAtElapsedRealtimeMillis: Long? = null,
    val claimedBootCount: Long? = null,
    val deliveryState: EventDedupDeliveryState = EventDedupDeliveryState.ENQUEUED,
    val eventId: String? = null,
    val deliverySequence: Long? = null,
)

internal enum class EventDedupDeliveryState {
    PENDING_ENQUEUE,
    ENQUEUED,
}

internal const val PENDING_ENQUEUE_STALE_AFTER_MILLIS = 2 * 60_000L

internal fun EventDedupRecord.hasFreshPendingEnqueue(
    now: AndroidMonotonicTime,
): Boolean {
    if (deliveryState != EventDedupDeliveryState.PENDING_ENQUEUE) return false
    val age = monotonicAgeMillis(
        claimedAtElapsedRealtimeMillis,
        claimedBootCount,
        now,
    ) ?: return false
    return age <= PENDING_ENQUEUE_STALE_AFTER_MILLIS
}

internal fun EventDedupRecord.blocksSameEvent(now: AndroidMonotonicTime): Boolean =
    deliveryState == EventDedupDeliveryState.ENQUEUED || hasFreshPendingEnqueue(now)

internal fun EventDedupRecord.isRecoverablePendingEnqueue(now: AndroidMonotonicTime): Boolean =
    deliveryState == EventDedupDeliveryState.PENDING_ENQUEUE && !hasFreshPendingEnqueue(now)

internal object EventDedupStore {
    private const val TAG = "EventDedupStore"
    private const val KEY_RECORDS = "post_process_dedup_records"
    private const val FIELD_EVENT = "event"
    private const val FIELD_SOURCE = "source"
    private const val FIELD_DELIVERED_AT = "deliveredAt"
    private const val FIELD_DELIVERY_STATE = "deliveryState"
    private const val FIELD_CLAIMED_ELAPSED_REALTIME = "claimedAtElapsedRealtime"
    private const val FIELD_CLAIMED_BOOT_COUNT = "claimedBootCount"
    private const val FIELD_EVENT_ID = "eventId"
    private const val FIELD_DELIVERY_SEQUENCE = "deliverySequence"
    private const val KEY_NEXT_DELIVERY_SEQUENCE = "event_delivery_next_sequence"

    @Synchronized
    fun reserveDeliverySequence(context: Context): Long? {
        val appContext = context.applicationContext
        val preferences = prefs(appContext)
        val current = preferences.safeLong(KEY_NEXT_DELIVERY_SEQUENCE, 0L).coerceAtLeast(0L)
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        return if (preferences.edit().putLong(KEY_NEXT_DELIVERY_SEQUENCE, next).commit()) {
            next
        } else {
            SmartGeofenceLogger.w(appContext, TAG, "Failed to reserve event delivery sequence.")
            null
        }
    }

    @Synchronized
    fun claimEvent(
        context: Context,
        fenceId: String,
        event: GeofenceEvent,
        source: String,
        claimedAtMillis: Long,
        monotonicTime: AndroidMonotonicTime = captureAndroidMonotonicTime(context),
        requestedEventId: String? = null,
        deliverySequence: Long? = null,
    ): EventDedupClaim? {
        val appContext = context.applicationContext
        val eventName = event.name.lowercase()
        val records = read(appContext)
        val previous = records[fenceId]
        if (previous?.eventName == eventName && previous.blocksSameEvent(monotonicTime)) {
            return null
        }
        val eventId = requestedEventId?.takeIf { it.isNotBlank() }
            ?: previous
            ?.takeIf {
                it.eventName == eventName &&
                    it.deliveryState == EventDedupDeliveryState.PENDING_ENQUEUE
            }
            ?.eventId
            ?: UUID.randomUUID().toString()
        val resolvedSequence = deliverySequence
            ?: previous?.takeIf {
                it.eventName == eventName &&
                    it.deliveryState == EventDedupDeliveryState.PENDING_ENQUEUE
            }?.deliverySequence
            ?: reserveDeliverySequence(appContext)
            ?: return null
        records[fenceId] = EventDedupRecord(
            eventName = eventName,
            source = source,
            deliveredAtMillis = claimedAtMillis,
            claimedAtElapsedRealtimeMillis = monotonicTime.elapsedRealtimeMillis,
            claimedBootCount = monotonicTime.bootCount,
            deliveryState = EventDedupDeliveryState.PENDING_ENQUEUE,
            eventId = eventId,
            deliverySequence = resolvedSequence,
        )
        if (!persist(appContext, records)) return null
        return EventDedupClaim(
            fenceId = fenceId,
            eventName = eventName,
            claimedAtMillis = claimedAtMillis,
            claimedAtElapsedRealtimeMillis = monotonicTime.elapsedRealtimeMillis,
            claimedBootCount = monotonicTime.bootCount,
            eventId = eventId,
            deliverySequence = resolvedSequence,
            previous = previous,
        )
    }

    @Synchronized
    fun restoreIfCurrent(context: Context, claim: EventDedupClaim) {
        val appContext = context.applicationContext
        val records = read(appContext)
        val current = records[claim.fenceId] ?: return
        if (current.eventName != claim.eventName ||
            current.deliveredAtMillis != claim.claimedAtMillis ||
            current.claimedAtElapsedRealtimeMillis != claim.claimedAtElapsedRealtimeMillis ||
            current.claimedBootCount != claim.claimedBootCount ||
            current.deliverySequence != claim.deliverySequence
        ) {
            return
        }
        if (claim.previous == null) {
            records.remove(claim.fenceId)
        } else {
            records[claim.fenceId] = claim.previous
        }
        persist(appContext, records)
    }

    @Synchronized
    fun markEnqueuedIfCurrent(context: Context, claim: EventDedupClaim): Boolean {
        val appContext = context.applicationContext
        val records = read(appContext)
        val current = records[claim.fenceId] ?: return false
        if (current.eventName != claim.eventName ||
            current.deliveredAtMillis != claim.claimedAtMillis ||
            current.claimedAtElapsedRealtimeMillis != claim.claimedAtElapsedRealtimeMillis ||
            current.claimedBootCount != claim.claimedBootCount ||
            current.deliverySequence != claim.deliverySequence ||
            current.deliveryState != EventDedupDeliveryState.PENDING_ENQUEUE
        ) {
            return false
        }
        records[claim.fenceId] = current.copy(
            deliveryState = EventDedupDeliveryState.ENQUEUED,
        )
        return persist(appContext, records)
    }

    @Synchronized
    fun markEnqueuedByEventId(
        context: Context,
        fenceId: String,
        eventName: String,
        eventId: String,
    ): Boolean {
        val appContext = context.applicationContext
        val records = read(appContext)
        val current = records[fenceId] ?: return false
        if (current.eventName != eventName || current.eventId != eventId) return false
        if (current.deliveryState == EventDedupDeliveryState.ENQUEUED) return true
        records[fenceId] = current.copy(deliveryState = EventDedupDeliveryState.ENQUEUED)
        return persist(appContext, records)
    }

    @Synchronized
    fun recordNativeEvent(
        context: Context,
        fenceIds: Collection<String>,
        eventName: String?,
        source: String,
    ) {
        val normalizedEvent = normalizeEvent(eventName) ?: return
        if (fenceIds.isEmpty()) return
        val appContext = context.applicationContext
        val records = read(appContext)
        val deliveredAtMillis = System.currentTimeMillis()
        val deliverySequence = reserveDeliverySequence(appContext)
        fenceIds.forEach { fenceId ->
            records[fenceId] = EventDedupRecord(
                eventName = normalizedEvent,
                source = source,
                deliveredAtMillis = deliveredAtMillis,
                deliveryState = EventDedupDeliveryState.ENQUEUED,
                deliverySequence = deliverySequence,
            )
        }
        persist(appContext, records)
    }

    @Synchronized
    fun snapshot(context: Context): Map<String, Map<String, Any?>> =
        read(context.applicationContext)
            .toSortedMap()
            .mapValues { (_, record) ->
                linkedMapOf(
                    "event" to record.eventName,
                    "source" to record.source,
                    "deliveredAtMillis" to record.deliveredAtMillis,
                    "claimedAtElapsedRealtimeMillis" to record.claimedAtElapsedRealtimeMillis,
                    "claimedBootCount" to record.claimedBootCount,
                    "deliveryState" to record.deliveryState.name.lowercase(),
                    "eventId" to record.eventId,
                    "deliverySequence" to record.deliverySequence,
                )
            }

    @Synchronized
    fun currentRecord(context: Context, fenceId: String): EventDedupRecord? =
        read(context.applicationContext)[fenceId]

    @Synchronized
    fun remove(context: Context, fenceId: String) {
        val appContext = context.applicationContext
        val records = read(appContext)
        if (records.remove(fenceId) != null) {
            persist(appContext, records)
        }
    }

    @Synchronized
    fun commitWithRemoval(
        context: Context,
        fenceId: String,
        editor: SharedPreferences.Editor,
    ): Boolean {
        val records = read(context.applicationContext)
        if (records.remove(fenceId) != null) {
            editor.putString(KEY_RECORDS, encode(records))
        }
        return editor.commit()
    }

    @Synchronized
    fun retainOnly(context: Context, fenceIds: Set<String>) {
        val appContext = context.applicationContext
        val records = read(appContext)
        val changed = records.keys.retainAll(fenceIds)
        if (changed) persist(appContext, records)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context.applicationContext).edit().remove(KEY_RECORDS).apply()
    }

    private fun normalizeEvent(eventName: String?): String? =
        when (eventName?.lowercase()) {
            "enter", "exit", "dwell" -> eventName.lowercase()
            else -> null
        }

    private fun read(context: Context): MutableMap<String, EventDedupRecord> {
        val raw = prefs(context).safeString(KEY_RECORDS) ?: return linkedMapOf()
        return try {
            val value = JSONObject(raw)
            val records = linkedMapOf<String, EventDedupRecord>()
            value.keys().forEach { fenceId ->
                val item = value.optJSONObject(fenceId) ?: return@forEach
                val eventName = normalizeEvent(item.optString(FIELD_EVENT)) ?: return@forEach
                records[fenceId] = EventDedupRecord(
                    eventName = eventName,
                    source = item.optString(FIELD_SOURCE),
                    deliveredAtMillis = item.optLong(FIELD_DELIVERED_AT, 0L),
                    claimedAtElapsedRealtimeMillis = item.optionalLong(
                        FIELD_CLAIMED_ELAPSED_REALTIME,
                    ),
                    claimedBootCount = item.optionalLong(FIELD_CLAIMED_BOOT_COUNT),
                    deliveryState = runCatching {
                        EventDedupDeliveryState.valueOf(
                            item.optString(FIELD_DELIVERY_STATE, "ENQUEUED").uppercase(),
                        )
                    }.getOrDefault(EventDedupDeliveryState.ENQUEUED),
                    eventId = item.optString(FIELD_EVENT_ID).takeIf { it.isNotBlank() },
                    deliverySequence = item.optionalLong(FIELD_DELIVERY_SEQUENCE),
                )
            }
            records
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Failed to parse event dedup records: ${e.message}",
                e,
            )
            linkedMapOf()
        }
    }

    private fun persist(context: Context, records: Map<String, EventDedupRecord>): Boolean {
        val committed = prefs(context).edit().putString(KEY_RECORDS, encode(records)).commit()
        if (!committed) {
            SmartGeofenceLogger.w(context, TAG, "Failed to commit event dedup records.")
        }
        return committed
    }

    private fun encode(records: Map<String, EventDedupRecord>): String {
        val value = JSONObject()
        records.forEach { (fenceId, record) ->
            value.put(
                fenceId,
                JSONObject().apply {
                    put(FIELD_EVENT, record.eventName)
                    put(FIELD_SOURCE, record.source)
                    put(FIELD_DELIVERED_AT, record.deliveredAtMillis)
                    put(FIELD_CLAIMED_ELAPSED_REALTIME, record.claimedAtElapsedRealtimeMillis)
                    put(FIELD_CLAIMED_BOOT_COUNT, record.claimedBootCount)
                    put(FIELD_DELIVERY_STATE, record.deliveryState.name)
                    put(FIELD_EVENT_ID, record.eventId)
                    put(FIELD_DELIVERY_SEQUENCE, record.deliverySequence)
                },
            )
        }
        return value.toString()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun JSONObject.optionalLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}
