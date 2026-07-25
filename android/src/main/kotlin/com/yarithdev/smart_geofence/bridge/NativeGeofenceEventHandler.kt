package com.yarithdev.smart_geofence.bridge

import android.content.Context
import android.os.SystemClock
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceBridgeDecision
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceBridgeEvent
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceBridgeTransformation
import com.chunkytofustudios.native_geofence.generated.GeofenceCallbackParamsWire
import com.yarithdev.smart_geofence.model.EventLocationEvidence
import com.yarithdev.smart_geofence.model.EventTimingEvidence
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.processing.NativeEventDisposition
import com.yarithdev.smart_geofence.processing.NativeEventInput
import com.yarithdev.smart_geofence.store.SmartGeofenceFence

internal interface NativeGeofenceEventHandlerDependencies {
    fun mirroredFences(context: Context): List<SmartGeofenceFence>
    fun callbackParams(
        context: Context,
        event: NativeGeofenceBridgeEvent,
        fences: List<SmartGeofenceFence>,
    ): List<GeofenceCallbackParamsWire>
    fun submitWake(context: Context, event: NativeGeofenceBridgeEvent, observedFenceIds: List<String>)
    fun captureTiming(context: Context, triggeredAtMillis: Long): EventTimingEvidence
    fun processSmart(context: Context, input: NativeEventInput): NativeEventDisposition
    fun debug(context: Context, message: String)
    fun warning(context: Context, message: String, error: Throwable? = null)
}

internal object NativeGeofenceEventHandler {
    @Volatile
    internal var dependencies: NativeGeofenceEventHandlerDependencies? = null

    internal fun resetDependenciesForTests() {
        dependencies = null
    }

    fun process(
        context: Context,
        event: NativeGeofenceBridgeEvent,
    ): NativeGeofenceBridgeDecision = process(
        context,
        event,
        checkNotNull(dependencies) { "NativeGeofenceEventHandler dependencies were not provided" },
    )

    fun process(
        context: Context,
        event: NativeGeofenceBridgeEvent,
        dependencies: NativeGeofenceEventHandlerDependencies,
        ownership: NativeBridgeDeliveryOwnership = NativeBridgeDeliveryOwnership.unrestricted(),
    ): NativeGeofenceBridgeDecision {
        val appContext = context.applicationContext
        val startedAtElapsed = diagnosticElapsedRealtimeMillis()
        fun decision(
            value: NativeGeofenceBridgeDecision,
            reason: String,
            errorType: String? = null,
        ): NativeGeofenceBridgeDecision {
            SmartGeofenceDiagnostics.recordTrace(
                context = appContext,
                stage = "native_bridge_decision",
                reasonCode = reason,
                traceId = event.eventId,
                eventId = event.eventId,
                event = event.transition.name.lowercase(),
                source = NATIVE_EVENT_SOURCE,
                extras = linkedMapOf(
                    "geofenceCount" to event.geofenceIds.size,
                    "durationMillis" to startedAtElapsed?.let { startedAt ->
                        diagnosticElapsedRealtimeMillis()?.let { finishedAt ->
                            (finishedAt - startedAt).coerceAtLeast(0L)
                        }
                    },
                    "decision" to value.javaClass.simpleName,
                    "errorType" to errorType,
                ),
            )
            return value
        }
        SmartGeofenceDiagnostics.recordTrace(
            context = appContext,
            stage = "native_bridge_received",
            reasonCode = "received",
            traceId = event.eventId,
            eventId = event.eventId,
            event = event.transition.name.lowercase(),
            source = NATIVE_EVENT_SOURCE,
            extras = linkedMapOf(
                "geofenceCount" to event.geofenceIds.size,
                "hasLocation" to (event.location != null),
                "locationFixTimeMillis" to event.location?.fixTimeMillis,
            ),
        )
        val mirrorsById = dependencies.mirroredFences(appContext).associateBy { it.id }
        if (mirrorsById.isEmpty()) {
            dependencies.debug(appContext, "Ignoring native geofence event; no smart fences are armed.")
            return decision(NativeGeofenceBridgeDecision.Decline, "no_smart_fences")
        }

        val mirroredFences = event.geofenceIds.distinct().mapNotNull(mirrorsById::get)
        if (mirroredFences.isEmpty()) {
            dependencies.debug(
                appContext,
                "Declining native geofence event; none of its IDs are owned by smart_geofence.",
            )
            return decision(NativeGeofenceBridgeDecision.Decline, "no_owned_ids")
        }
        val mirroredIds = mirroredFences.map { it.id }
        val callbackParams = dependencies.callbackParams(appContext, event, mirroredFences)
        if (callbackParams.isEmpty()) {
            dependencies.warning(
                appContext,
                "Could not reconstruct smart callback routing; continuing native delivery.",
            )
            return decision(NativeGeofenceBridgeDecision.Decline, "routing_reconstruction_failed")
        }

        val nativeOnlyIds = event.geofenceIds.distinct().filterNot(mirrorsById::containsKey)
        val smartDecision = if (nativeOnlyIds.isEmpty()) {
            NativeGeofenceBridgeDecision.Accept
        } else {
            NativeGeofenceBridgeDecision.Transform(
                NativeGeofenceBridgeTransformation(
                    geofenceIds = nativeOnlyIds,
                    transition = event.transition,
                    location = event.location,
                ),
            )
        }
        ownership.prepareSmartDecision(smartDecision)
        if (!ownership.canContinueSmart()) {
            return decision(NativeGeofenceBridgeDecision.Decline, "ownership_expired_before_wake")
        }

        dependencies.submitWake(appContext, event, mirroredIds)
        if (!ownership.canContinueSmart()) {
            return decision(NativeGeofenceBridgeDecision.Decline, "ownership_expired_before_processor")
        }
        val triggeredAtMillis = event.eventAtMillis?.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val disposition = try {
            dependencies.processSmart(
                appContext,
                NativeEventInput(
                    fenceIds = mirroredIds,
                    eventName = event.transition.name.lowercase(),
                    location = event.location?.let {
                        EventLocationEvidence(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            accuracyMeters = it.accuracyMeters,
                            isMock = it.isMock,
                            fixTimeMillis = it.fixTimeMillis,
                            elapsedRealtimeNanos = it.elapsedRealtimeNanos,
                        )
                    },
                    callbackParams = callbackParams,
                    source = NATIVE_EVENT_SOURCE,
                    allowCallbackOwnership = true,
                    traceId = event.eventId,
                    triggeredAtMillis = triggeredAtMillis,
                    eventTiming = dependencies.captureTiming(appContext, triggeredAtMillis),
                    onFinished = {},
                    deliveryOwnership = ownership,
                ),
            )
        } catch (error: Throwable) {
            dependencies.warning(
                appContext,
                "Smart native processing failed before ownership; continuing native delivery.",
                error,
            )
            SmartGeofenceDiagnostics.recordTrace(
                context = appContext,
                stage = "native_bridge_processing",
                reasonCode = "processor_threw",
                traceId = event.eventId,
                eventId = event.eventId,
                event = event.transition.name.lowercase(),
                source = NATIVE_EVENT_SOURCE,
                extras = mapOf("errorType" to error.javaClass.name),
            )
            NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
        }

        if (disposition == NativeEventDisposition.CONTINUE_NATIVE_CALLBACK) {
            ownership.committedSmartDecision()?.let { committedDecision ->
                return decision(committedDecision, "smart_committed_before_processor_finished")
            }
            ownership.yieldToNativeFallback()
            return decision(NativeGeofenceBridgeDecision.Decline, "smart_declined")
        }
        if (!ownership.tryCommitSmart()) {
            return decision(NativeGeofenceBridgeDecision.Decline, "ownership_expired_before_commit")
        }
        return decision(
            smartDecision,
            if (nativeOnlyIds.isEmpty()) {
                "smart_accepted"
            } else {
                "smart_accepted_partial_transform"
            },
        )
    }

    private const val NATIVE_EVENT_SOURCE = "native_geofence"

    private fun diagnosticElapsedRealtimeMillis(): Long? =
        runCatching { SystemClock.elapsedRealtime() }.getOrNull()
}
