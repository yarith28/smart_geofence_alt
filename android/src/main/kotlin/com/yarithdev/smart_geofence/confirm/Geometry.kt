package com.yarithdev.smart_geofence.confirm

import com.yarithdev.smart_geofence.store.ObservedFenceState

enum class BoundaryPosition { INSIDE, OUTSIDE }

object BoundaryClassifier {
    fun classify(
        distanceMeters: Double,
        radiusMeters: Double,
    ): BoundaryPosition =
        if (distanceMeters <= radiusMeters) BoundaryPosition.INSIDE
        else BoundaryPosition.OUTSIDE
}

internal fun conservativeFusedBaselineState(
    distanceMeters: Double,
    accuracyMeters: Double,
    radiusMeters: Double,
): ObservedFenceState? {
    if (!distanceMeters.isFinite() || distanceMeters < 0.0) return null
    if (!accuracyMeters.isFinite() || accuracyMeters < 0.0) return null
    if (!radiusMeters.isFinite() || radiusMeters <= 0.0) return null
    val definitelyInside = distanceMeters + accuracyMeters <= radiusMeters
    val definitelyOutside = distanceMeters - accuracyMeters >= radiusMeters
    return when {
        definitelyInside && !definitelyOutside -> ObservedFenceState.INSIDE
        definitelyOutside && !definitelyInside -> ObservedFenceState.OUTSIDE
        else -> null
    }
}

object TeleportGuard {
    const val MAX_SPEED_MPS = 70.0

    fun isImplausible(
        distanceMeters: Double,
        dtSeconds: Double,
        maxSpeedMps: Double = MAX_SPEED_MPS,
    ): Boolean {
        if (dtSeconds <= 0.0) return false
        return distanceMeters / dtSeconds > maxSpeedMps
    }
}
