package com.yarithdev.smart_geofence.dormant

import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.core.Constants
import kotlin.math.max

data class DormantFarEntryDecision(
    val shouldEnter: Boolean,
    val thresholdMeters: Double,
    val reason: String?,
    val probeDelayMillis: Long?,
)

object DormantFarPolicy {
    const val EXPIRY_MILLIS = 24 * 60 * 60 * 1000L

    private const val PROBE_ASSUMED_SPEED_MPS = 35.0
    private const val PROBE_SAMPLE_FRACTION = 0.5
    private const val BALANCED_MIN_PROBE_MILLIS = 15 * 60 * 1000L
    private const val BALANCED_MAX_PROBE_MILLIS = 6 * 60 * 60 * 1000L
    private const val LOW_POWER_MIN_PROBE_MILLIS = 30 * 60 * 1000L
    private const val LOW_POWER_MAX_PROBE_MILLIS = 12 * 60 * 60 * 1000L

    fun supportsDormant(config: SmartGeofenceConfig): Boolean =
        config.escalationEnabled && config.batteryMode != Constants.BATTERY_MODE_HIGH_ACCURACY

    fun entryDecision(
        config: SmartGeofenceConfig,
        edgeDistanceMeters: Double?,
        likelyStationary: Boolean,
    ): DormantFarEntryDecision {
        val edgeDistance = edgeDistanceMeters?.takeIf { it.isFinite() }
        val fallbackThreshold = reactivationEdgeMeters(config)
        if (!supportsDormant(config) || edgeDistance == null) {
            return DormantFarEntryDecision(
                shouldEnter = false,
                thresholdMeters = fallbackThreshold,
                reason = null,
                probeDelayMillis = null,
            )
        }

        val threshold = if (likelyStationary) {
            fallbackThreshold
        } else {
            movingThresholdMeters(config)
        }
        val reason = if (likelyStationary) {
            "far_stationary"
        } else {
            "far_${config.batteryMode}"
        }
        val shouldEnter = edgeDistance > threshold
        return DormantFarEntryDecision(
            shouldEnter = shouldEnter,
            thresholdMeters = threshold,
            reason = if (shouldEnter) reason else null,
            probeDelayMillis = if (shouldEnter) probeDelayMillis(config, edgeDistance) else null,
        )
    }

    fun shouldExitForAcceptedFix(
        config: SmartGeofenceConfig,
        edgeDistanceMeters: Double?,
    ): Boolean {
        val edgeDistance = edgeDistanceMeters?.takeIf { it.isFinite() } ?: return true
        return edgeDistance <= reactivationEdgeMeters(config)
    }

    fun reactivationEdgeMeters(config: SmartGeofenceConfig): Double =
        config.proximityRadiusMeters + config.proximityAdaptiveHysteresisMeters

    fun movingThresholdMeters(config: SmartGeofenceConfig): Double =
        if (config.batteryMode == Constants.BATTERY_MODE_LOW_POWER) {
            max(2.0 * config.proximityRadiusMeters, 1500.0)
        } else {
            max(3.0 * config.proximityRadiusMeters, 3000.0)
        }

    fun probeDelayMillis(
        config: SmartGeofenceConfig,
        edgeDistanceMeters: Double,
    ): Long {
        val distanceToWake = edgeDistanceMeters - reactivationEdgeMeters(config)
        val rawDelayMillis = if (distanceToWake <= 0.0) {
            0L
        } else {
            (distanceToWake / PROBE_ASSUMED_SPEED_MPS * 1000.0 * PROBE_SAMPLE_FRACTION)
                .toLong()
        }
        val (minDelay, maxDelay) = if (config.batteryMode == Constants.BATTERY_MODE_LOW_POWER) {
            LOW_POWER_MIN_PROBE_MILLIS to LOW_POWER_MAX_PROBE_MILLIS
        } else {
            BALANCED_MIN_PROBE_MILLIS to BALANCED_MAX_PROBE_MILLIS
        }
        return rawDelayMillis.coerceIn(minDelay, maxDelay)
    }

    fun isExpired(
        state: DormantFarState,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val reference = state.lastAcceptedFixAtMillis.takeIf { it > 0L } ?: state.enteredAtMillis
        if (reference <= 0L) return true
        val ageMillis = nowMillis - reference
        return ageMillis < 0L || ageMillis > EXPIRY_MILLIS
    }
}
