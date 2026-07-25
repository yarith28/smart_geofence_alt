package com.yarithdev.smart_geofence.delivery

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.store.FenceObservationStore
import com.yarithdev.smart_geofence.store.FenceStore

internal class EventOutboxRecoveryCoordinator {
    private var inFlightRecordId: String? = null

    val isBusy: Boolean
        get() = inFlightRecordId != null

    fun claim(recordId: String): Boolean {
        if (inFlightRecordId != null) return false
        inFlightRecordId = recordId
        return true
    }

    fun release(recordId: String) {
        if (inFlightRecordId == recordId) inFlightRecordId = null
    }

    fun recover(
        context: Context,
        exclusions: Set<String> = emptySet(),
        runInTransaction: (() -> Unit) -> Unit,
    ): Boolean {
        if (isBusy) return true
        val record = DurableEventDeliveryCoordinator.next(context, exclusions) ?: return false
        if (record.params.geofences.isEmpty()) {
            if (!DurableEventDeliveryCoordinator.discard(context, record.id)) return false
            return recover(context, exclusions, runInTransaction)
        }

        val eventName = record.params.event.name.lowercase()
        val geofences = DurableEventDeliveryCoordinator.recoverableGeofences(record, context)
        if (geofences.isEmpty()) {
            if (!DurableEventDeliveryCoordinator.discard(context, record.id)) return false
            return recover(context, exclusions, runInTransaction)
        }

        val alreadyAccepted = DurableEventDeliveryCoordinator.hasAcceptedFence(
            context,
            record,
            geofences.map { it.id },
        )
        val stateFenceIds = DurableEventDeliveryCoordinator.stateOwnedFenceIds(
            context,
            record,
            geofences.map { it.id },
        )
        if (!DurableEventDeliveryCoordinator.claimRecoveredSmartState(
                context,
                record,
                stateFenceIds,
            )
        ) return false

        try {
            if (stateFenceIds.isNotEmpty()) {
                FenceObservationStore.recordNativeEvent(
                    context,
                    stateFenceIds,
                    eventName,
                    recoveryEdgeDistances(context, stateFenceIds, record.params.location),
                )
            }
        } catch (error: Throwable) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Outbox recovery could not commit observation state " +
                    "eventId=${record.eventId}: ${error.message}",
                error,
            )
            return false
        }

        if (alreadyAccepted) {
            if (!DurableEventDeliveryCoordinator.completeAccepted(
                    context,
                    record,
                    geofences.map { it.id },
                )
            ) return false
            recover(context, exclusions, runInTransaction)
            return true
        }
        val recoveredParams = record.params.copy(
            geofences = geofences,
            eventId = record.eventId,
            callbackContextsByGeofenceId = record.params.callbackContextsByGeofenceId
                ?.filterKeys { id -> geofences.any { it.id == id } },
        )
        return enqueueRecovered(
            context,
            record.copy(params = recoveredParams),
            geofences.map { it.id },
            exclusions,
            runInTransaction,
        )
    }

    private fun enqueueRecovered(
        context: Context,
        record: EventDeliveryOutboxRecord,
        fenceIds: List<String>,
        exclusions: Set<String>,
        runInTransaction: (() -> Unit) -> Unit,
    ): Boolean {
        check(claim(record.id))
        return try {
            DurableEventDeliveryCoordinator.enqueue(
                context,
                record,
                fenceIds,
                "${record.source}:outbox_recovery",
                runInTransaction,
            ) { _, finalized ->
                release(record.id)
                if (finalized) {
                    recover(context, exclusions, runInTransaction)
                }
            }
            true
        } catch (error: Throwable) {
            release(record.id)
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Outbox recovery enqueue failed eventId=${record.eventId}: ${error.message}",
                error,
            )
            false
        }
    }

    private companion object {
        private const val TAG = "SmartGeofenceEventProcessor"
    }
}

private fun recoveryEdgeDistances(
    context: Context,
    fenceIds: Collection<String>,
    location: com.chunkytofustudios.native_geofence.generated.LocationWire?,
): Map<String, Double> {
    location ?: return emptyMap()
    val ids = fenceIds.toSet()
    if (ids.isEmpty()) return emptyMap()
    val fix = Location("smart_geofence_outbox_recovery").apply {
        latitude = location.latitude
        longitude = location.longitude
    }
    return FenceStore.getAll(context).mapNotNull { fence ->
        if (fence.id !in ids) return@mapNotNull null
        val center = Location("smart_geofence_outbox_recovery_fence").apply {
            latitude = fence.latitude
            longitude = fence.longitude
        }
        fence.id to (fix.distanceTo(center).toDouble() - fence.radiusMeters)
    }.toMap()
}
