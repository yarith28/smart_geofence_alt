package com.yarithdev.smart_geofence.wake

import android.content.Context
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceBridgeEvent
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceBridgeTransition
import com.chunkytofustudios.native_geofence.generated.ActiveGeofenceWire
import com.chunkytofustudios.native_geofence.generated.GeofenceCallbackParamsWire
import com.chunkytofustudios.native_geofence.generated.GeofenceEvent
import com.chunkytofustudios.native_geofence.generated.LocationWire
import com.chunkytofustudios.native_geofence.util.ActiveGeofenceWires
import com.chunkytofustudios.native_geofence.util.NativeGeofencePersistence
import com.yarithdev.smart_geofence.bridge.NativeGeofenceEventHandlerDependencies
import com.yarithdev.smart_geofence.confirm.SmartGeofenceEventProcessor
import com.yarithdev.smart_geofence.confirm.SmartGeofenceEventTimingStore
import com.yarithdev.smart_geofence.confirm.toEventTimingEvidence
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.model.EventTimingEvidence
import com.yarithdev.smart_geofence.processing.NativeEventDisposition
import com.yarithdev.smart_geofence.processing.NativeEventInput
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.store.SmartGeofenceFence
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object NativeGeofenceBridgeDependencies : NativeGeofenceEventHandlerDependencies {
    private const val TAG = "NativeGeofenceBridge"

    override fun mirroredFences(context: Context): List<SmartGeofenceFence> =
        FenceStore.getAll(context)

    override fun callbackParams(
        context: Context,
        event: NativeGeofenceBridgeEvent,
        fences: List<SmartGeofenceFence>,
    ): List<GeofenceCallbackParamsWire> {
        val groups = fences.groupBy(SmartGeofenceFence::dispatchCallbackHandle)
        return groups.entries.map { (dispatchHandle, group) ->
            GeofenceCallbackParamsWire(
                geofences = group.map { activeGeofenceWire(context, it) },
                event = event.transition.toWire(),
                location = event.location?.let {
                    LocationWire(
                        latitude = it.latitude,
                        longitude = it.longitude,
                        accuracyMeters = it.accuracyMeters,
                        isMock = it.isMock,
                        fixTimeMillis = it.fixTimeMillis,
                        elapsedRealtimeNanos = it.elapsedRealtimeNanos,
                    )
                },
                eventAtMillis = event.eventAtMillis,
                callbackHandle = dispatchHandle,
                eventId = if (groups.size == 1) {
                    event.eventId
                } else {
                    UUID.nameUUIDFromBytes(
                        "${event.eventId}:$dispatchHandle".toByteArray(StandardCharsets.UTF_8),
                    ).toString()
                },
                callbackContextsByGeofenceId = group.associate { it.id to it.callbackHandle },
                traceId = event.eventId,
            )
        }
    }

    override fun submitWake(
        context: Context,
        event: NativeGeofenceBridgeEvent,
        observedFenceIds: List<String>,
    ) {
        WakeEventCoordinator.submit(
            context,
            WakeTask(
                source = WakeSource.NATIVE_GEOFENCE,
                action = WakeAction.DRAIN_FOREGROUND_QUEUE,
                exemption = WakeExemption.GEOFENCE,
                reason = "native_geofence_wake",
                event = event.transition.name.lowercase(),
                geofenceIds = observedFenceIds,
            ),
        )
    }

    override fun captureTiming(
        context: Context,
        triggeredAtMillis: Long,
    ): EventTimingEvidence = SmartGeofenceEventTimingStore.captureNow(
        context,
        triggeredAtMillis,
        "native_geofence",
    ).toEventTimingEvidence()

    override fun processSmart(
        context: Context,
        input: NativeEventInput,
    ): NativeEventDisposition = SmartGeofenceEventProcessor.processNative(context, input)

    override fun debug(context: Context, message: String) =
        SmartGeofenceLogger.d(context, TAG, message)

    override fun warning(context: Context, message: String, error: Throwable?) =
        SmartGeofenceLogger.w(context, TAG, message, error)

    private fun NativeGeofenceBridgeTransition.toWire(): GeofenceEvent = when (this) {
        NativeGeofenceBridgeTransition.ENTER -> GeofenceEvent.ENTER
        NativeGeofenceBridgeTransition.EXIT -> GeofenceEvent.EXIT
        NativeGeofenceBridgeTransition.DWELL -> GeofenceEvent.DWELL
    }

    private fun activeGeofenceWire(
        context: Context,
        fence: SmartGeofenceFence,
    ): ActiveGeofenceWire {
        val callbackTriggers = buildList {
            if (fence.triggersEnter) add(GeofenceEvent.ENTER)
            if (fence.triggersExit) add(GeofenceEvent.EXIT)
            if (fence.triggersDwell) add(GeofenceEvent.DWELL)
        }
        val nativeFence = NativeGeofencePersistence.getGeofence(context, fence.id)
        if (nativeFence != null) {
            return ActiveGeofenceWires.fromGeofenceWire(nativeFence).copy(
                triggers = callbackTriggers,
            )
        }
        return ActiveGeofenceWire(
            id = fence.id,
            location = LocationWire(
                latitude = fence.latitude,
                longitude = fence.longitude,
                accuracyMeters = null,
                isMock = false,
            ),
            radiusMeters = fence.radiusMeters,
            triggers = callbackTriggers,
            androidSettings = null,
        )
    }
}
