package com.yarithdev.smart_geofence.model

internal data class EventLocationEvidence(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val isMock: Boolean,
    val fixTimeMillis: Long? = null,
    val elapsedRealtimeNanos: Long? = null,
)

internal data class EventTimingEvidence(
    val wallClockEventAtMillis: Long,
    val eventMonotonicMillis: Long?,
    val androidBootCount: Long?,
    val timestampOrigin: String,
)
