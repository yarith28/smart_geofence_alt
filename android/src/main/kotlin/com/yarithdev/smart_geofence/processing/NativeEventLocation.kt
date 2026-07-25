package com.yarithdev.smart_geofence.processing

@Deprecated("Use com.yarithdev.smart_geofence.confirm.NativeEventLocation")
data class NativeEventLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val isMock: Boolean,
)
