package com.yarithdev.smart_geofence.wake

import android.content.Context
import android.os.SystemClock
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceBridgeDecision
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceBridgeEvent
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceEventProcessor
import com.yarithdev.smart_geofence.bridge.NativeGeofenceEventHandler
import com.yarithdev.smart_geofence.bridge.NativeBridgeDeliveryOwnership
import com.yarithdev.smart_geofence.core.MainThreadRunner
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.monitoring.LocationAvailabilityStopController

internal typealias NativeBridgeMainThreadRunner = (Long, () -> Unit) -> Unit
internal typealias NativeBridgeProcessor =
    (Context, NativeGeofenceBridgeEvent, NativeBridgeDeliveryOwnership) ->
        NativeGeofenceBridgeDecision
internal typealias NativeBridgeClock = () -> Long

class NativeGeofenceBridge : NativeGeofenceEventProcessor {
    private val runOnMainThread: NativeBridgeMainThreadRunner
    private val processEvent: NativeBridgeProcessor
    private val elapsedRealtimeMillis: NativeBridgeClock

    constructor() {
        runOnMainThread = MainThreadRunner::runBlocking
        processEvent = { context, event, ownership ->
            if (LocationAvailabilityStopController.stopIfUnavailable(
                    context,
                    "native_geofence_bridge",
                )
            ) {
                NativeGeofenceBridgeDecision.Accept
            } else {
                NativeGeofenceEventHandler.process(
                    context,
                    event,
                    NativeGeofenceBridgeDependencies,
                    ownership,
                )
            }
        }
        elapsedRealtimeMillis = { SystemClock.elapsedRealtime() }
    }

    internal constructor(
        runOnMainThread: NativeBridgeMainThreadRunner,
        processEvent: NativeBridgeProcessor,
        elapsedRealtimeMillis: NativeBridgeClock = { SystemClock.elapsedRealtime() },
    ) {
        this.runOnMainThread = runOnMainThread
        this.processEvent = processEvent
        this.elapsedRealtimeMillis = elapsedRealtimeMillis
    }

    override fun processNativeGeofenceEvent(
        context: Context,
        event: NativeGeofenceBridgeEvent,
        completion: (Result<NativeGeofenceBridgeDecision>) -> Unit,
    ) {
        val startedAtElapsed = diagnosticElapsedRealtimeMillis()
        val ownership = NativeBridgeDeliveryOwnership.bounded(
            MAIN_THREAD_TIMEOUT_MILLIS,
            elapsedRealtimeMillis,
        )
        SmartGeofenceDiagnostics.recordTrace(
            context,
            stage = "native_bridge_entry",
            reasonCode = "entered",
            traceId = event.eventId,
            eventId = event.eventId,
            event = event.transition.name.lowercase(),
            source = "native_geofence",
            extras = mapOf("geofenceCount" to event.geofenceIds.size),
        )
        val result = try {
            val decision = java.util.concurrent.atomic.AtomicReference<NativeGeofenceBridgeDecision?>()
            runOnMainThread(MAIN_THREAD_TIMEOUT_MILLIS) {
                if (!ownership.canContinueSmart()) return@runOnMainThread
                decision.set(processEvent(context, event, ownership))
            }
            val completedDecision = checkNotNull(decision.get())
            if (completedDecision == NativeGeofenceBridgeDecision.Decline) {
                ownership.yieldToNativeFallback()
            }
            Result.success(completedDecision)
        } catch (error: Throwable) {
            ownership.yieldToNativeFallback()
            val committedDecision = ownership.committedSmartDecision()
            if (committedDecision != null) {
                Result.success(committedDecision)
            } else {
                Result.failure(error)
            }
        }
        val decision = result.getOrNull()
        SmartGeofenceDiagnostics.recordTrace(
            context,
            stage = "native_bridge_exit",
            reasonCode = when (decision) {
                NativeGeofenceBridgeDecision.Accept -> "accepted"
                NativeGeofenceBridgeDecision.Decline -> "declined"
                is NativeGeofenceBridgeDecision.Transform ->
                    "transformed"
                null -> "failed"
            },
            traceId = event.eventId,
            eventId = event.eventId,
            event = event.transition.name.lowercase(),
            source = "native_geofence",
            extras = linkedMapOf(
                "durationMillis" to startedAtElapsed?.let { startedAt ->
                    diagnosticElapsedRealtimeMillis()?.let { finishedAt ->
                        (finishedAt - startedAt).coerceAtLeast(0L)
                    }
                },
                "errorType" to result.exceptionOrNull()?.javaClass?.name,
                "ownershipState" to ownership.currentState().name.lowercase(),
            ),
        )
        completion(result)
    }

    private companion object {
        const val MAIN_THREAD_TIMEOUT_MILLIS = 2_000L

        fun diagnosticElapsedRealtimeMillis(): Long? =
            runCatching { SystemClock.elapsedRealtime() }.getOrNull()
    }
}
