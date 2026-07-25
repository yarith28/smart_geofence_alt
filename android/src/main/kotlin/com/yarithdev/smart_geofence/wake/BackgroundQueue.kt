package com.yarithdev.smart_geofence.wake

import android.content.Context
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBoolean
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import org.json.JSONArray
import org.json.JSONObject

enum class BackgroundWorkKind {
    REFRESH_ACTIVITY_REQUEST;

    companion object {
        fun fromPersistedName(name: String): BackgroundWorkKind? =
            runCatching { valueOf(name) }.getOrNull()
    }
}

data class BackgroundWorkItem(
    val id: Long,
    val kind: BackgroundWorkKind,
    val source: String,
    val createdAtMillis: Long,
    val notBeforeMillis: Long,
    val dedupeKey: String,
) {
    fun isReadyAt(nowMillis: Long): Boolean = notBeforeMillis <= nowMillis
}

internal fun shouldExecuteBackgroundWork(
    kind: BackgroundWorkKind,
    activityMonitoringEligible: Boolean,
): Boolean = when (kind) {
    BackgroundWorkKind.REFRESH_ACTIVITY_REQUEST -> activityMonitoringEligible
}

object BackgroundQueue {
    private const val TAG = "BackgroundQueue"
    internal const val KEY_QUEUE = "background_work_queue"
    internal const val KEY_NEXT_ID = "background_work_next_id"
    private const val MAX_PENDING_WORK = 32

    @Synchronized
    fun enqueue(
        context: Context,
        kind: BackgroundWorkKind,
        source: String,
        notBeforeMillis: Long,
        dedupeKey: String,
    ): BackgroundWorkItem {
        require(dedupeKey.isNotBlank()) { "Background work dedupe key must not be blank." }

        val appContext = context.applicationContext
        val queue = read(appContext).toMutableList()
        val existing = queue.lastOrNull { it.dedupeKey == dedupeKey }
        val prefs = prefs(appContext)
        val nextId = prefs.safeLong(KEY_NEXT_ID, 0L) + 1L
        val nowMillis = System.currentTimeMillis()
        val item = BackgroundWorkItem(
            id = nextId,
            kind = kind,
            source = source,
            createdAtMillis = nowMillis,
            notBeforeMillis = maxOf(existing?.notBeforeMillis ?: notBeforeMillis, notBeforeMillis),
            dedupeKey = dedupeKey,
        )
        queue.removeAll { it.dedupeKey == dedupeKey }
        queue.add(item)
        val trimmed = queue.takeLast(MAX_PENDING_WORK)
        persist(appContext, trimmed, nextId)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Queued background work id=${item.id} kind=$kind source=$source " +
                "notBefore=$notBeforeMillis pending=${trimmed.size}."
        )
        return item
    }

    @Synchronized
    fun peekReady(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): BackgroundWorkItem? =
        read(context.applicationContext).firstOrNull { it.isReadyAt(nowMillis) }

    @Synchronized
    fun nextReadyAtMillis(context: Context): Long? =
        read(context.applicationContext).minOfOrNull { it.notBeforeMillis }

    @Synchronized
    fun removeIfUnchanged(context: Context, completedItem: BackgroundWorkItem) {
        removeExactIfUnchanged(context, completedItem)
    }

    @Synchronized
    internal fun removeExactIfUnchanged(
        context: Context,
        completedItem: BackgroundWorkItem,
    ): Boolean {
        val appContext = context.applicationContext
        val queue = read(appContext)
        val remaining = queue.filterNot {
            it.id == completedItem.id &&
                it.createdAtMillis == completedItem.createdAtMillis &&
                it.notBeforeMillis == completedItem.notBeforeMillis
        }
        if (remaining.size != queue.size) {
            persist(appContext, remaining)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Removed completed background work id=${completedItem.id} pending=${remaining.size}."
            )
            return true
        }
        return false
    }

    @Synchronized
    internal fun deferIfUnchanged(
        context: Context,
        item: BackgroundWorkItem,
        notBeforeMillis: Long,
    ): Boolean {
        val appContext = context.applicationContext
        val queue = read(appContext)
        val index = queue.indexOfFirst {
            it.id == item.id &&
                it.createdAtMillis == item.createdAtMillis &&
                it.notBeforeMillis == item.notBeforeMillis &&
                it.dedupeKey == item.dedupeKey
        }
        if (index < 0) return false
        val updated = queue.toMutableList()
        updated[index] = item.copy(notBeforeMillis = notBeforeMillis)
        persist(appContext, updated)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Deferred background work id=${item.id} until=$notBeforeMillis.",
        )
        return true
    }

    @Synchronized
    internal fun deferCurrentDedupeAtLeast(
        context: Context,
        dedupeKey: String,
        notBeforeMillis: Long,
    ): Boolean {
        val appContext = context.applicationContext
        val queue = read(appContext)
        val index = queue.indexOfLast { it.dedupeKey == dedupeKey }
        if (index < 0) return false
        val current = queue[index]
        val deferredAt = maxOf(current.notBeforeMillis, notBeforeMillis)
        if (deferredAt != current.notBeforeMillis) {
            val updated = queue.toMutableList()
            updated[index] = current.copy(notBeforeMillis = deferredAt)
            persist(appContext, updated)
        }
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Deferred current background work id=${current.id} dedupeKey=$dedupeKey " +
                "until=$deferredAt.",
        )
        return true
    }

    @Synchronized
    fun count(context: Context): Int = read(context.applicationContext).size

    @Synchronized
    fun countReady(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int = read(context.applicationContext).count { it.isReadyAt(nowMillis) }

    @Synchronized
    fun clear(context: Context) {
        val appContext = context.applicationContext
        val queue = read(appContext)
        persist(appContext, emptyList())
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Cleared ${queue.size} pending background repair task(s).",
        )
    }

    private fun read(context: Context): List<BackgroundWorkItem> {
        LegacyWakeQueueMigration.migrate(context)
        val raw = prefs(context).safeString(KEY_QUEUE) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching { fromJson(array.getJSONObject(index)) }
                    .onFailure {
                        SmartGeofenceLogger.w(
                            context,
                            TAG,
                            "Skipping malformed background work: ${it.message}",
                            it,
                        )
                    }
                    .getOrNull()
            }
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(context, TAG, "Failed to parse background queue: ${e.message}", e)
            emptyList()
        }
    }

    private fun persist(
        context: Context,
        items: List<BackgroundWorkItem>,
        nextId: Long? = null,
    ) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        val editor = prefs(context).edit().putString(KEY_QUEUE, array.toString())
        if (nextId != null) editor.putLong(KEY_NEXT_ID, nextId)
        editor.apply()
    }

    private fun toJson(item: BackgroundWorkItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("kind", item.kind.name)
        put("source", item.source)
        put("createdAt", item.createdAtMillis)
        put("notBefore", item.notBeforeMillis)
        put("dedupeKey", item.dedupeKey)
    }

    private fun fromJson(value: JSONObject): BackgroundWorkItem? {
        val kind = BackgroundWorkKind.fromPersistedName(value.optString("kind")) ?: return null
        val source = value.optString("source").takeIf { it.isNotBlank() } ?: return null
        val dedupeKey = value.optString("dedupeKey").takeIf { it.isNotBlank() } ?: return null
        return BackgroundWorkItem(
            id = value.getLong("id"),
            kind = kind,
            source = source,
            createdAtMillis = value.optLong("createdAt", 0L),
            notBeforeMillis = value.optLong("notBefore", 0L),
            dedupeKey = dedupeKey,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}

internal object LegacyWakeQueueMigration {
    private const val TAG = "WakeQueueMigration"
    private const val KEY_MIGRATED = "wake_work_queue_split_migrated"
    private const val LEGACY_KEY_QUEUE = "wake_work_queue"
    private const val LEGACY_KEY_NEXT_ID = "wake_work_next_id"

    @Synchronized
    fun migrate(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.safeBoolean(KEY_MIGRATED, false)) return

        val legacyRaw = prefs.safeString(LEGACY_KEY_QUEUE)
        if (legacyRaw == null) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }

        try {
            val foreground = readObjects(prefs.safeString(ForegroundQueue.KEY_QUEUE))
            val background = readObjects(prefs.safeString(BackgroundQueue.KEY_QUEUE))
            val legacy = readObjects(legacyRaw)
            legacy.forEach { value ->
                val lane = value.optString("lane", "FOREGROUND_REQUIRED")
                value.remove("lane")
                normalizeLegacyConfirm(value)
                if (lane == "BACKGROUND_SAFE") background.add(value) else foreground.add(value)
            }

            val legacyNextId = prefs.safeLong(LEGACY_KEY_NEXT_ID, 0L)
            val foregroundNextId = maxOf(
                prefs.safeLong(ForegroundQueue.KEY_NEXT_ID, 0L),
                legacyNextId,
                maxId(foreground),
            )
            val backgroundNextId = maxOf(
                prefs.safeLong(BackgroundQueue.KEY_NEXT_ID, 0L),
                legacyNextId,
                maxId(background),
            )
            prefs.edit()
                .putString(ForegroundQueue.KEY_QUEUE, toArray(coalesce(foreground)).toString())
                .putLong(ForegroundQueue.KEY_NEXT_ID, foregroundNextId)
                .putString(BackgroundQueue.KEY_QUEUE, toArray(coalesce(background)).toString())
                .putLong(BackgroundQueue.KEY_NEXT_ID, backgroundNextId)
                .remove(LEGACY_KEY_QUEUE)
                .remove(LEGACY_KEY_NEXT_ID)
                .putBoolean(KEY_MIGRATED, true)
                .apply()
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Split ${legacy.size} legacy wake work item(s) into foreground=${foreground.size} " +
                    "background=${background.size}."
            )
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Could not split the legacy wake queue: ${e.message}",
                e,
            )
        }
    }

    private fun readObjects(raw: String?): MutableList<JSONObject> {
        if (raw == null) return mutableListOf()
        val array = JSONArray(raw)
        return (0 until array.length()).map { JSONObject(array.getJSONObject(it).toString()) }.toMutableList()
    }

    private fun normalizeLegacyConfirm(value: JSONObject) {
        if (value.optString("kind") in setOf("CONFIRM_NEAREST", "CONFIRM_NEAR")) {
            value.put("kind", "CONFIRM_PROXIMITY")
        }
        when (value.optString("kind")) {
            "CONFIRM_PROXIMITY" -> value.put("dedupeKey", "confirm:proximity")
            "CONFIRM_OUTSIDE" -> value.put("dedupeKey", "confirm:outside")
            "CONFIRM_INSIDE" -> value.put("dedupeKey", "confirm:inside")
        }
    }

    private fun coalesce(items: List<JSONObject>): List<JSONObject> {
        val coalesced = linkedMapOf<String, JSONObject>()
        items.forEach { item ->
            val key = item.optString("dedupeKey").takeIf { it.isNotBlank() }
                ?: "${item.optString("kind")}:${item.optLong("id")}"
            val existing = coalesced[key]
            if (existing == null || item.optLong("createdAt") >= existing.optLong("createdAt")) {
                coalesced.remove(key)
                coalesced[key] = item
            }
        }
        return coalesced.values.toList()
    }

    private fun maxId(items: List<JSONObject>): Long = items.maxOfOrNull { it.optLong("id") } ?: 0L

    private fun toArray(items: List<JSONObject>): JSONArray = JSONArray().apply {
        items.forEach { put(it) }
    }
}
