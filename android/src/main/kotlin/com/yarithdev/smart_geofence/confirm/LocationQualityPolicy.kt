package com.yarithdev.smart_geofence.confirm

import android.location.Location

object LocationQualityPolicy {
    private const val FUTURE_TOLERANCE_MILLIS = 2 * 60 * 1000L

    fun rejectionReason(
        location: Location,
        maxAgeMillis: Long,
        maxAccuracyMeters: Double,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? = rejectionReason(
        hasAccuracy = location.hasAccuracy(),
        accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        timestampMillis = location.time,
        maxAgeMillis = maxAgeMillis,
        maxAccuracyMeters = maxAccuracyMeters,
        nowMillis = nowMillis,
    )

    internal fun rejectionReason(
        hasAccuracy: Boolean,
        accuracyMeters: Double?,
        timestampMillis: Long,
        maxAgeMillis: Long,
        maxAccuracyMeters: Double,
        nowMillis: Long,
    ): String? {
        if (!hasAccuracy || accuracyMeters == null) return "missing_accuracy"
        if (!accuracyMeters.isFinite() || accuracyMeters < 0.0) return "invalid_accuracy"
        if (accuracyMeters > maxAccuracyMeters) {
            return "accuracy_exceeds_limit:$accuracyMeters"
        }
        if (timestampMillis <= 0L) return "missing_timestamp"
        val ageMillis = nowMillis - timestampMillis
        if (ageMillis < -FUTURE_TOLERANCE_MILLIS) return "future_timestamp"
        if (ageMillis > maxAgeMillis.coerceAtLeast(1L)) return "stale_location:$ageMillis"
        return null
    }
}
