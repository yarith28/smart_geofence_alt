package com.yarithdev.smart_geofence.processing

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.model.EventLocationEvidence
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.store.SmartGeofenceFence

internal object NativeEnterPayloadFilter {
    fun apply(
        context: Context,
        input: NativeEventInput,
        config: SmartGeofenceConfig,
    ): NativeEventInput {
        val payload = input.payloadLocation()
        if (payload == null) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Native ENTER payload sanity skipped; no location payload " +
                    "source=${input.source} ids=${input.fenceIds}.",
            )
            return input
        }
        val smartFences = FenceStore.getAll(context).associateBy { it.id }
        var rejectedCount = 0
        val filteredParams = input.callbackParams.mapNotNull { params ->
            val filteredGeofences = params.geofences.filter { geofence ->
                val smartFence = smartFences[geofence.id] ?: return@filter true
                val classification = classify(payload, smartFence)
                val rejected = nativeEnterPayloadTooFarOutside(
                    classification.edgeDistanceMeters,
                    payload.accuracyMeters,
                    config.nativeEnterPayloadDistanceSlackMeters,
                )
                val allowedOutsideDistance =
                    config.nativeEnterPayloadDistanceSlackMeters.coerceAtLeast(0.0) +
                        (payload.accuracyMeters?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0)
                recordDecision(
                    context,
                    classification,
                    input.source,
                    payload.accuracyMeters ?: Double.NaN,
                    payload.isMock,
                    if (rejected) "native_enter_rejected_distance" else "native_enter_payload_accepted",
                )
                if (rejected) {
                    rejectedCount += 1
                    SmartGeofenceLogger.w(
                        context,
                        TAG,
                        "native_enter_rejected_distance fence=${smartFence.id} " +
                            "source=${input.source} distance=${classification.distanceMeters.toInt()}m " +
                            "radius=${smartFence.radiusMeters.toInt()}m " +
                            "edge=${classification.edgeDistanceMeters.toInt()}m " +
                            "accuracy=${payload.accuracyMeters}m " +
                            "allowedOutside=${allowedOutsideDistance.toInt()}m.",
                    )
                    false
                } else {
                    true
                }
            }
            if (filteredGeofences.isEmpty()) null else params.copy(geofences = filteredGeofences)
        }
        if (rejectedCount == 0) return input
        val remainingFenceIds = filteredParams
            .flatMap { it.geofences }
            .map { it.id }
            .distinct()
        SmartGeofenceLogger.w(
            context,
            TAG,
            "Native ENTER payload sanity filtered rejected=$rejectedCount " +
                "remaining=${remainingFenceIds.size} source=${input.source}.",
        )
        return input.copy(
            fenceIds = remainingFenceIds,
            callbackParams = filteredParams,
        )
    }

    private fun classify(
        payload: EventLocationEvidence,
        fence: SmartGeofenceFence,
    ): ClassifiedPayload {
        val location = Location("smart_geofence_native_payload").apply {
            latitude = payload.latitude
            longitude = payload.longitude
            payload.accuracyMeters
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { accuracy = it.toFloat() }
        }
        val center = Location("smart_geofence").apply {
            latitude = fence.latitude
            longitude = fence.longitude
        }
        val distance = location.distanceTo(center).toDouble()
        return ClassifiedPayload(
            fence = fence,
            distanceMeters = distance,
            edgeDistanceMeters = distance - fence.radiusMeters,
        )
    }

    private fun recordDecision(
        context: Context,
        classification: ClassifiedPayload,
        source: String,
        accuracyMeters: Double,
        isMock: Boolean,
        decision: String,
    ) {
        SmartGeofenceDiagnostics.recordBoundaryDecision(
            context,
            classification.fence.id,
            source,
            decision,
            classification.distanceMeters,
            classification.fence.radiusMeters,
            classification.edgeDistanceMeters,
            accuracyMeters,
            isMock,
            null,
        )
    }

    private data class ClassifiedPayload(
        val fence: SmartGeofenceFence,
        val distanceMeters: Double,
        val edgeDistanceMeters: Double,
    )

    private const val TAG = "SmartGeofenceEventProcessor"
}
