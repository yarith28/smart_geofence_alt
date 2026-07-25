package com.yarithdev.smart_geofence.processing

import android.location.Location
import com.chunkytofustudios.native_geofence.generated.GeofenceCallbackParamsWire
import com.chunkytofustudios.native_geofence.generated.LocationWire
import com.yarithdev.smart_geofence.model.EventLocationEvidence
import com.yarithdev.smart_geofence.model.EventTimingEvidence
import com.yarithdev.smart_geofence.bridge.NativeBridgeDeliveryOwnership
import com.yarithdev.smart_geofence.proximity.FusedBroadcastTailTracker
import com.yarithdev.smart_geofence.store.SmartGeofenceFence

internal enum class LocationEventMode {
    PROXIMITY,
    OUTSIDE,
    INSIDE,
}

internal data class LocationEventInput(
    val location: Location,
    val candidateFences: List<SmartGeofenceFence>,
    val mode: LocationEventMode,
    val source: String,
    val maxAgeMillis: Long,
    val tailTracker: FusedBroadcastTailTracker? = null,

    val nativeTransitionInstances: Map<String, String>? = null,
)

internal enum class LocationEventResult {
    PROCESSED,
    EVENT_FILTER_REJECTED,
    MOCK_REJECTED,
    NEEDS_FRESH_CONFIRM,
}

internal data class NativeEventInput(
    val fenceIds: Collection<String>,
    val eventName: String?,
    val location: EventLocationEvidence?,
    val callbackParams: List<GeofenceCallbackParamsWire>,
    val source: String,
    val allowCallbackOwnership: Boolean,
    val traceId: String? = null,
    val triggeredAtMillis: Long = System.currentTimeMillis(),
    val eventTiming: EventTimingEvidence? = null,
    val onFinished: (Boolean) -> Unit,
    val deliveryOwnership: NativeBridgeDeliveryOwnership =
        NativeBridgeDeliveryOwnership.unrestricted(),
)

internal enum class NativeEventDisposition {
    CALLBACK_OWNED,
    CONTINUE_NATIVE_CALLBACK,
}

internal fun NativeEventInput.payloadLocation(): EventLocationEvidence? =
    location ?: callbackParams.asSequence()
        .mapNotNull { it.location?.toEventLocationEvidence() }
        .firstOrNull()

private fun LocationWire.toEventLocationEvidence(): EventLocationEvidence = EventLocationEvidence(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    isMock = isMock,
    fixTimeMillis = fixTimeMillis,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
)

internal class FallbackEnqueueTracker(
    total: Int,
    private val onFinished: ((Boolean) -> Unit)?,
) {
    private var remaining = total
    private var allSucceeded = true

    init {
        if (total == 0) onFinished?.invoke(true)
    }

    @Synchronized
    fun complete(succeeded: Boolean) {
        if (remaining <= 0) return
        allSucceeded = allSucceeded && succeeded
        remaining -= 1
        if (remaining == 0) onFinished?.invoke(allSucceeded)
    }
}
