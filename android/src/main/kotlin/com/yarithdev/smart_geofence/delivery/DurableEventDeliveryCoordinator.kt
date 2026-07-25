package com.yarithdev.smart_geofence.delivery

import android.content.Context
import com.chunkytofustudios.native_geofence.generated.GeofenceCallbackParamsWire
import com.chunkytofustudios.native_geofence.generated.GeofenceEvent
import com.yarithdev.smart_geofence.confirm.captureAndroidMonotonicTime
import java.util.UUID

internal data class DurableSmartDelivery(
    val params: GeofenceCallbackParamsWire,
    val record: EventDeliveryOutboxRecord,
)

internal sealed interface SmartDeliveryStageResult {
    data object FullyDeduped : SmartDeliveryStageResult
    data object PersistenceFailed : SmartDeliveryStageResult
    data class Staged(val deliveries: List<DurableSmartDelivery>) : SmartDeliveryStageResult
}

internal sealed interface ConfirmedDeliveryStageResult {
    data object Deduped : ConfirmedDeliveryStageResult
    data object SequenceFailed : ConfirmedDeliveryStageResult
    data object PersistenceFailed : ConfirmedDeliveryStageResult
    data class Ready(val record: EventDeliveryOutboxRecord) : ConfirmedDeliveryStageResult
    data class DurableClaimPending(val record: EventDeliveryOutboxRecord) : ConfirmedDeliveryStageResult
}

internal object DurableEventDeliveryCoordinator {
    fun reserveDeliverySequence(context: Context): Long? =
        EventDedupStore.reserveDeliverySequence(context)

    fun stage(context: Context, records: Collection<EventDeliveryOutboxRecord>): Boolean =
        EventDeliveryOutboxStore.addAll(context, records)

    fun stageConfirmedCallback(
        context: Context,
        fenceId: String,
        event: GeofenceEvent,
        source: String,
        createdAtMillis: Long,
        paramsFactory: (eventId: String) -> GeofenceCallbackParamsWire,
        onPrepared: () -> Unit = {},
    ): ConfirmedDeliveryStageResult {
        val eventName = event.name.lowercase()
        val monotonicNow = captureAndroidMonotonicTime(context)
        val previous = EventDedupStore.currentRecord(context, fenceId)
        if (previous?.eventName == eventName && previous.blocksSameEvent(monotonicNow)) {
            return ConfirmedDeliveryStageResult.Deduped
        }

        val pending = previous?.takeIf {
            it.eventName == eventName &&
                it.deliveryState == EventDedupDeliveryState.PENDING_ENQUEUE
        }
        val eventId = pending?.eventId ?: UUID.randomUUID().toString()
        val sequence = pending?.deliverySequence ?: reserveDeliverySequence(context)
            ?: return ConfirmedDeliveryStageResult.SequenceFailed
        val params = paramsFactory(eventId)
        val record = EventDeliveryOutboxRecord(
            id = eventId,
            eventId = eventId,
            source = source,
            deliverySequence = sequence,
            createdAtMillis = createdAtMillis,
            params = if (params.eventId == eventId) params else params.copy(eventId = eventId),
        )
        onPrepared()
        if (!stage(context, listOf(record))) {
            return ConfirmedDeliveryStageResult.PersistenceFailed
        }
        val claim = EventDedupStore.claimEvent(
            context,
            fenceId,
            event,
            source,
            createdAtMillis,
            requestedEventId = eventId,
            deliverySequence = sequence,
        )
        if (claim != null) return ConfirmedDeliveryStageResult.Ready(record)

        val current = EventDedupStore.currentRecord(context, fenceId)
        if (current?.eventName == eventName && current.blocksSameEvent(monotonicNow)) {
            discard(context, record.id)
            return ConfirmedDeliveryStageResult.Deduped
        }
        return ConfirmedDeliveryStageResult.DurableClaimPending(record)
    }

    fun stageSmartCallbacks(
        context: Context,
        callbackParams: Collection<GeofenceCallbackParamsWire>,
        source: String,
        createdAtMillis: Long,
    ): SmartDeliveryStageResult {
        val monotonicNow = captureAndroidMonotonicTime(context)
        val seenEventIds = mutableSetOf<String>()
        val identifiedParams = callbackParams.map { params ->
            val existing = params.eventId?.takeIf { it.isNotBlank() }
            val eventId = existing?.takeIf(seenEventIds::add)
                ?: UUID.randomUUID().toString().also(seenEventIds::add)
            params.copy(eventId = eventId)
        }
        val deliveries = identifiedParams.mapNotNull { params ->
            val eventId = checkNotNull(params.eventId)
            val sequence = reserveDeliverySequence(context)
                ?: throw IllegalStateException("Could not reserve native event delivery sequence.")
            val eventName = params.event.name.lowercase()
            val geofences = params.geofences.filter { fence ->
                if (!fence.triggers.contains(params.event)) return@filter false
                val current = EventDedupStore.currentRecord(context, fence.id)
                current?.eventName != eventName || !current.blocksSameEvent(monotonicNow)
            }
            if (geofences.isEmpty()) return@mapNotNull null

            val durableParams = params.copy(
                geofences = geofences,
                callbackContextsByGeofenceId = params.callbackContextsByGeofenceId
                    ?.filterKeys { id -> geofences.any { it.id == id } },
            )
            DurableSmartDelivery(
                params = durableParams,
                record = EventDeliveryOutboxRecord(
                    id = UUID.randomUUID().toString(),
                    eventId = eventId,
                    source = source,
                    deliverySequence = sequence,
                    createdAtMillis = createdAtMillis,
                    params = durableParams,
                ),
            )
        }
        if (deliveries.isEmpty()) return SmartDeliveryStageResult.FullyDeduped
        return if (stage(context, deliveries.map { it.record })) {
            SmartDeliveryStageResult.Staged(deliveries)
        } else {
            SmartDeliveryStageResult.PersistenceFailed
        }
    }

    fun claimSmartState(
        context: Context,
        delivery: DurableSmartDelivery,
        source: String,
        claimedAtMillis: Long,
    ): Boolean = delivery.params.geofences.map { fence ->
        EventDedupStore.claimEvent(
            context,
            fence.id,
            delivery.params.event,
            source,
            claimedAtMillis,
            requestedEventId = delivery.record.eventId,
            deliverySequence = delivery.record.deliverySequence,
        ) != null
    }.all { it }

    fun recoverableGeofences(record: EventDeliveryOutboxRecord, context: Context) =
        record.params.geofences.filter { geofence ->
            val current = EventDedupStore.currentRecord(context, geofence.id)
            current == null ||
                current.eventId == record.eventId ||
                current.eventName != record.params.event.name.lowercase() ||
                current.deliveryState != EventDedupDeliveryState.ENQUEUED
        }

    fun hasAcceptedFence(
        context: Context,
        record: EventDeliveryOutboxRecord,
        fenceIds: Collection<String>,
    ): Boolean = fenceIds.any { fenceId ->
        EventDedupStore.currentRecord(context, fenceId)?.let { current ->
            current.eventName == record.params.event.name.lowercase() &&
                current.eventId == record.eventId &&
                current.deliveryState == EventDedupDeliveryState.ENQUEUED
        } == true
    }

    fun stateOwnedFenceIds(
        context: Context,
        record: EventDeliveryOutboxRecord,
        fenceIds: Collection<String>,
    ): List<String> = fenceIds.filter { fenceId ->
        val current = EventDedupStore.currentRecord(context, fenceId)
        current?.eventId == record.eventId ||
            current?.deliverySequence == null ||
            current.deliverySequence <= record.deliverySequence
    }

    fun claimRecoveredSmartState(
        context: Context,
        record: EventDeliveryOutboxRecord,
        fenceIds: Collection<String>,
    ): Boolean = fenceIds.map { fenceId ->
        val current = EventDedupStore.currentRecord(context, fenceId)
        if (current?.eventName == record.params.event.name.lowercase() &&
            current.eventId == record.eventId
        ) {
            true
        } else {
            EventDedupStore.claimEvent(
                context,
                fenceId,
                record.params.event,
                record.source,
                record.createdAtMillis,
                requestedEventId = record.eventId,
                deliverySequence = record.deliverySequence,
            ) != null
        }
    }.all { it }

    fun next(
        context: Context,
        exclusions: Set<String> = emptySet(),
    ): EventDeliveryOutboxRecord? = EventDeliveryOutboxStore.snapshot(context)
        .firstOrNull { it.id !in exclusions }

    fun discard(context: Context, recordId: String): Boolean =
        EventDeliveryOutboxStore.remove(context, recordId)

    fun enqueue(
        context: Context,
        record: EventDeliveryOutboxRecord,
        fenceIds: Collection<String>,
        source: String = record.source,
        runInTransaction: ((() -> Unit) -> Unit) = { block -> block() },
        onFinished: (enqueued: Boolean, finalized: Boolean) -> Unit,
    ) {
        SmartGeofenceCallbackEnqueuer.enqueue(
            context,
            record.params.copy(eventId = record.eventId),
            source,
        ) { enqueued ->
            runInTransaction {
                val finalized = enqueued && completeAccepted(context, record, fenceIds)
                onFinished(enqueued, finalized)
            }
        }
    }

    fun completeAccepted(
        context: Context,
        record: EventDeliveryOutboxRecord,
        fenceIds: Collection<String>,
    ): Boolean {
        val eventName = record.params.event.name.lowercase()
        val finalized = attemptAllFenceFinalizations(fenceIds) { fenceId ->
            val current = EventDedupStore.currentRecord(context, fenceId)
            if (current?.eventId != record.eventId &&
                current?.deliverySequence != null &&
                current.deliverySequence > record.deliverySequence
            ) {
                true
            } else {
                EventDedupStore.markEnqueuedByEventId(
                    context,
                    fenceId,
                    eventName,
                    record.eventId,
                )
            }
        }
        return finalized && discard(context, record.id)
    }

    internal fun attemptAllFenceFinalizations(
        fenceIds: Collection<String>,
        finalize: (String) -> Boolean,
    ): Boolean = fenceIds.map(finalize).all { it }
}
