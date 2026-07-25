package com.yarithdev.smart_geofence.confirm

import com.yarithdev.smart_geofence.model.EventLocationEvidence

data class NativeEventLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val isMock: Boolean,
)

internal fun NativeEventLocation.toEventLocationEvidence(): EventLocationEvidence =
    EventLocationEvidence(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        isMock = isMock,
    )

internal fun EventLocationEvidence.toNativeEventLocation(): NativeEventLocation =
    NativeEventLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        isMock = isMock,
    )
