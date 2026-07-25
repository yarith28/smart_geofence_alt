package com.yarithdev.smart_geofence.delivery

import android.content.Context
import com.chunkytofustudios.native_geofence.generated.ActiveGeofenceWire
import com.chunkytofustudios.native_geofence.generated.AndroidGeofenceSettingsWire
import com.chunkytofustudios.native_geofence.generated.GeofenceCallbackParamsWire
import com.chunkytofustudios.native_geofence.generated.GeofenceEvent
import com.chunkytofustudios.native_geofence.generated.LocationWire
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import org.json.JSONArray
import org.json.JSONObject

internal data class EventDeliveryOutboxRecord(
    val id: String,
    val eventId: String,
    val source: String,
    val deliverySequence: Long,
    val createdAtMillis: Long,
    val params: GeofenceCallbackParamsWire,
)

internal object EventDeliveryOutboxStore {
    private const val TAG = "EventDeliveryOutboxStore"
    private const val KEY_RECORDS = "event_delivery_outbox_v1"

    @Synchronized
    fun addAll(context: Context, records: Collection<EventDeliveryOutboxRecord>): Boolean {
        if (records.isEmpty()) return true
        val appContext = context.applicationContext
        val current = read(appContext)
        records.forEach { current[it.id] = it }
        return persist(appContext, current)
    }

    @Synchronized
    fun snapshot(context: Context): List<EventDeliveryOutboxRecord> =
        read(context.applicationContext)
            .values
            .sortedWith(compareBy(EventDeliveryOutboxRecord::deliverySequence, EventDeliveryOutboxRecord::id))

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val appContext = context.applicationContext
        val current = read(appContext)
        if (current.remove(id) == null) return true
        return persist(appContext, current)
    }

    @Synchronized
    fun clear(context: Context): Boolean =
        prefs(context.applicationContext).edit().remove(KEY_RECORDS).commit()

    private fun read(context: Context): LinkedHashMap<String, EventDeliveryOutboxRecord> {
        val raw = prefs(context).safeString(KEY_RECORDS) ?: return linkedMapOf()
        return try {
            val root = JSONObject(raw)
            val result = linkedMapOf<String, EventDeliveryOutboxRecord>()
            root.keys().forEach { id ->
                recordFromJson(id, root.optJSONObject(id))?.let { result[id] = it }
            }
            result
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(context, TAG, "Failed to parse event outbox: ${e.message}", e)
            linkedMapOf()
        }
    }

    private fun persist(
        context: Context,
        records: Map<String, EventDeliveryOutboxRecord>,
    ): Boolean {
        val root = JSONObject()
        records.forEach { (id, record) -> root.put(id, recordToJson(record)) }
        val committed = prefs(context).edit().putString(KEY_RECORDS, root.toString()).commit()
        if (!committed) {
            SmartGeofenceLogger.w(context, TAG, "Failed to commit event delivery outbox.")
        }
        return committed
    }

    private fun recordToJson(record: EventDeliveryOutboxRecord): JSONObject =
        JSONObject()
            .put("eventId", record.eventId)
            .put("source", record.source)
            .put("deliverySequence", record.deliverySequence)
            .put("createdAtMillis", record.createdAtMillis)
            .put("params", paramsToJson(record.params))

    private fun recordFromJson(
        id: String,
        value: JSONObject?,
    ): EventDeliveryOutboxRecord? {
        value ?: return null
        val eventId = value.optString("eventId").takeIf { it.isNotBlank() } ?: return null
        val params = paramsFromJson(value.optJSONObject("params")) ?: return null
        return EventDeliveryOutboxRecord(
            id = id,
            eventId = eventId,
            source = value.optString("source", "outbox_recovery"),
            deliverySequence = value.optLong("deliverySequence", 0L),
            createdAtMillis = value.optLong("createdAtMillis", 0L),
            params = if (params.eventId == eventId) params else params.copy(eventId = eventId),
        )
    }

    private fun paramsToJson(params: GeofenceCallbackParamsWire): JSONObject =
        JSONObject()
            .put("geofences", JSONArray().apply {
                params.geofences.forEach { put(activeGeofenceToJson(it)) }
            })
            .put("event", params.event.name)
            .put("location", params.location?.let(::locationToJson))
            .put("eventAtMillis", params.eventAtMillis)
            .put("callbackHandle", params.callbackHandle)
            .put("eventId", params.eventId)
            .put("traceId", params.traceId)
            .put("callbackContexts", JSONObject().apply {
                params.callbackContextsByGeofenceId.orEmpty().forEach { (id, handle) ->
                    put(id, handle)
                }
            })

    private fun paramsFromJson(value: JSONObject?): GeofenceCallbackParamsWire? {
        value ?: return null
        val event = enumValue<GeofenceEvent>(value.optString("event")) ?: return null
        val geofencesJson = value.optJSONArray("geofences") ?: return null
        val geofences = buildList {
            for (index in 0 until geofencesJson.length()) {
                activeGeofenceFromJson(geofencesJson.optJSONObject(index))?.let(::add)
            }
        }
        if (geofences.isEmpty()) return null
        val contextsJson = value.optJSONObject("callbackContexts")
        val contexts = linkedMapOf<String, Long>()
        contextsJson?.keys()?.forEach { id -> contexts[id] = contextsJson.optLong(id) }
        return GeofenceCallbackParamsWire(
            geofences = geofences,
            event = event,
            location = locationFromJson(value.optJSONObject("location")),
            eventAtMillis = value.optionalLong("eventAtMillis"),
            callbackHandle = value.optLong("callbackHandle"),
            eventId = value.optString("eventId").takeIf { it.isNotBlank() },
            callbackContextsByGeofenceId = contexts.takeIf { it.isNotEmpty() },
            traceId = value.optString("traceId").takeIf { it.isNotBlank() },
        )
    }

    private fun activeGeofenceToJson(value: ActiveGeofenceWire): JSONObject =
        JSONObject()
            .put("id", value.id)
            .put("location", locationToJson(value.location))
            .put("radiusMeters", value.radiusMeters)
            .put("triggers", JSONArray().apply { value.triggers.forEach { put(it.name) } })
            .put("androidSettings", value.androidSettings?.let(::androidSettingsToJson))

    private fun activeGeofenceFromJson(value: JSONObject?): ActiveGeofenceWire? {
        value ?: return null
        val id = value.optString("id").takeIf { it.isNotBlank() } ?: return null
        val location = locationFromJson(value.optJSONObject("location")) ?: return null
        val triggersJson = value.optJSONArray("triggers") ?: return null
        val triggers = buildList {
            for (index in 0 until triggersJson.length()) {
                enumValue<GeofenceEvent>(triggersJson.optString(index))?.let(::add)
            }
        }
        return ActiveGeofenceWire(
            id = id,
            location = location,
            radiusMeters = value.optDouble("radiusMeters"),
            triggers = triggers,
            androidSettings = androidSettingsFromJson(value.optJSONObject("androidSettings")),
        )
    }

    private fun locationToJson(value: LocationWire): JSONObject =
        JSONObject()
            .put("latitude", value.latitude)
            .put("longitude", value.longitude)
            .put("accuracyMeters", value.accuracyMeters)
            .put("isMock", value.isMock)
            .put("fixTimeMillis", value.fixTimeMillis)
            .put("elapsedRealtimeNanos", value.elapsedRealtimeNanos)

    private fun locationFromJson(value: JSONObject?): LocationWire? {
        value ?: return null
        return LocationWire(
            latitude = value.optDouble("latitude"),
            longitude = value.optDouble("longitude"),
            accuracyMeters = value.optionalDouble("accuracyMeters"),
            isMock = value.optBoolean("isMock", false),
            fixTimeMillis = value.optionalLong("fixTimeMillis"),
            elapsedRealtimeNanos = value.optionalLong("elapsedRealtimeNanos"),
        )
    }

    private fun androidSettingsToJson(value: AndroidGeofenceSettingsWire): JSONObject =
        JSONObject()
            .put("initialTriggers", JSONArray().apply {
                value.initialTriggers.forEach { put(it.name) }
            })
            .put("expirationDurationMillis", value.expirationDurationMillis)
            .put("loiteringDelayMillis", value.loiteringDelayMillis)
            .put("notificationResponsivenessMillis", value.notificationResponsivenessMillis)

    private fun androidSettingsFromJson(value: JSONObject?): AndroidGeofenceSettingsWire? {
        value ?: return null
        val initialJson = value.optJSONArray("initialTriggers") ?: JSONArray()
        val initialTriggers = buildList {
            for (index in 0 until initialJson.length()) {
                enumValue<GeofenceEvent>(initialJson.optString(index))?.let(::add)
            }
        }
        return AndroidGeofenceSettingsWire(
            initialTriggers = initialTriggers,
            expirationDurationMillis = value.optionalLong("expirationDurationMillis"),
            loiteringDelayMillis = value.optLong("loiteringDelayMillis"),
            notificationResponsivenessMillis = value.optionalLong(
                "notificationResponsivenessMillis",
            ),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()

    private fun JSONObject.optionalLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optionalDouble(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
