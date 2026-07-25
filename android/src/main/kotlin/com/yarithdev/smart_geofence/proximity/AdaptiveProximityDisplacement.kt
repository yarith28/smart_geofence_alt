package com.yarithdev.smart_geofence.proximity

import android.content.Context
import android.content.SharedPreferences
import com.yarithdev.smart_geofence.activity.ActivityMonitor
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeLongOrNull
import com.yarithdev.smart_geofence.core.safeString
import kotlin.math.abs

enum class AdaptiveDisplacementMode {
    NEAR_BOUNDARY,
    NORMAL,
    STATIONARY,
    FAR,
}

data class AdaptiveSamplingDecision(
    val mode: AdaptiveDisplacementMode,
    val priorityName: String,
    val intervalMillis: Long,
    val fastestIntervalMillis: Long,
    val maxWaitMillis: Long,
    val displacementMeters: Double,
)

object AdaptiveProximityDisplacement {
    private const val KEY_MODE = "adaptive_proximity_displacement_mode"
    private const val KEY_APPLIED_DISPLACEMENT_BITS =
        "adaptive_proximity_displacement_applied_bits"
    private const val KEY_APPLIED_PRIORITY = "adaptive_proximity_displacement_applied_priority"
    private const val KEY_APPLIED_INTERVAL = "adaptive_proximity_displacement_applied_interval"
    private const val KEY_APPLIED_FASTEST = "adaptive_proximity_displacement_applied_fastest"
    private const val KEY_APPLIED_MAX_WAIT = "adaptive_proximity_displacement_applied_max_wait"
    private const val KEY_LAST_EDGE_DISTANCE_BITS =
        "adaptive_proximity_displacement_last_edge_bits"
    private const val KEY_LAST_EDGE_RECORDED_AT =
        "adaptive_proximity_displacement_last_edge_recorded_at"
    private const val MIN_EDGE_FRESHNESS_MILLIS = 10 * 60 * 1000L

    private const val FAR_SAMPLE_FRACTION = 0.5
    private const val FAR_MAX_INTERVAL_MILLIS = 15 * 60 * 1000L
    private const val FAR_STILL_ASSUMED_SPEED_MPS = 1.5
    private const val FAR_INTERVAL_QUANTIZE_MILLIS = 60 * 1000L

    fun decide(
        config: SmartGeofenceConfig,
        currentMode: AdaptiveDisplacementMode?,
        edgeDistanceMeters: Double?,
        likelyStationary: Boolean,
    ): AdaptiveSamplingDecision {
        fun band(mode: AdaptiveDisplacementMode, displacementMeters: Double) =
            AdaptiveSamplingDecision(
                mode = mode,
                priorityName = config.proximityLocationPriority,
                intervalMillis = config.proximityIntervalMillis,
                fastestIntervalMillis = config.proximityFastestIntervalMillis,
                maxWaitMillis = config.proximityMaxWaitMillis,
                displacementMeters = displacementMeters,
            )

        val normal = band(AdaptiveDisplacementMode.NORMAL, config.proximityMinDisplacementMeters)
        if (!config.proximityAdaptiveDisplacementEnabled) return normal

        val edgeDistance = edgeDistanceMeters?.takeIf { it.isFinite() }
        if (edgeDistance != null) {
            val nearLimit = config.proximityAdaptiveNearBoundaryDistanceMeters +
                if (currentMode == AdaptiveDisplacementMode.NEAR_BOUNDARY) {
                    config.proximityAdaptiveHysteresisMeters
                } else {
                    0.0
                }
            if (abs(edgeDistance) <= nearLimit) {
                return band(
                    AdaptiveDisplacementMode.NEAR_BOUNDARY,
                    config.proximityAdaptiveNearBoundaryDisplacementMeters,
                )
            }

            val farThreshold = config.proximityRadiusMeters +
                if (currentMode == AdaptiveDisplacementMode.FAR) {
                    -config.proximityAdaptiveHysteresisMeters
                } else {
                    config.proximityAdaptiveHysteresisMeters
                }
            if (edgeDistance > farThreshold) {
                return farBand(config, edgeDistance, likelyStationary)
            }
        }

        return if (likelyStationary) {
            band(
                AdaptiveDisplacementMode.STATIONARY,
                config.proximityAdaptiveStationaryDisplacementMeters,
            )
        } else {
            normal
        }
    }

    private fun farBand(
        config: SmartGeofenceConfig,
        edgeDistanceMeters: Double,
        likelyStationary: Boolean,
    ): AdaptiveSamplingDecision {
        val assumedSpeed = (
            if (likelyStationary) FAR_STILL_ASSUMED_SPEED_MPS
            else config.teleportMaxSpeedMetersPerSecond
            ).coerceAtLeast(FAR_STILL_ASSUMED_SPEED_MPS)

        val minTimeToFenceMillis = edgeDistanceMeters / assumedSpeed * 1000.0
        val ceilingMillis = maxOf(FAR_MAX_INTERVAL_MILLIS, config.proximityIntervalMillis)
        val interval = quantize(
            (minTimeToFenceMillis * FAR_SAMPLE_FRACTION)
                .toLong()
                .coerceIn(config.proximityIntervalMillis, ceilingMillis)
        )
        return AdaptiveSamplingDecision(
            mode = AdaptiveDisplacementMode.FAR,
            priorityName = Constants.DEFAULT_LOCATION_PRIORITY_LOW_POWER,
            intervalMillis = interval,
            fastestIntervalMillis = config.proximityFastestIntervalMillis.coerceAtMost(interval),
            maxWaitMillis = interval * 2,
            displacementMeters = config.proximityAdaptiveStationaryDisplacementMeters,
        )
    }

    private fun quantize(intervalMillis: Long): Long =
        (intervalMillis / FAR_INTERVAL_QUANTIZE_MILLIS).coerceAtLeast(1L) *
            FAR_INTERVAL_QUANTIZE_MILLIS

    @Synchronized
    fun select(
        context: Context,
        config: SmartGeofenceConfig,
        edgeDistanceMeters: Double? = null,
    ): AdaptiveSamplingDecision {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val usableEdge = edgeDistanceMeters?.takeIf { it.isFinite() }?.also { edge ->
            prefs.edit()
                .putLong(KEY_LAST_EDGE_DISTANCE_BITS, edge.toRawBits())
                .putLong(KEY_LAST_EDGE_RECORDED_AT, System.currentTimeMillis())
                .apply()
        } ?: loadFreshEdgeDistance(config, prefs)
        return decide(
            config = config,
            currentMode = currentMode(prefs),
            edgeDistanceMeters = usableEdge,
            likelyStationary = ActivityMonitor.isLikelyStationary(appContext),
        )
    }

    fun sameRequest(a: AdaptiveSamplingDecision, b: AdaptiveSamplingDecision): Boolean =
        a.priorityName == b.priorityName &&
            a.intervalMillis == b.intervalMillis &&
            a.fastestIntervalMillis == b.fastestIntervalMillis &&
            a.maxWaitMillis == b.maxWaitMillis &&
            abs(a.displacementMeters - b.displacementMeters) < 0.01

    @Synchronized
    fun markApplied(context: Context, decision: AdaptiveSamplingDecision) {
        prefs(context.applicationContext)
            .edit()
            .putString(KEY_MODE, decision.mode.name)
            .putString(KEY_APPLIED_PRIORITY, decision.priorityName)
            .putLong(KEY_APPLIED_INTERVAL, decision.intervalMillis)
            .putLong(KEY_APPLIED_FASTEST, decision.fastestIntervalMillis)
            .putLong(KEY_APPLIED_MAX_WAIT, decision.maxWaitMillis)
            .putLong(KEY_APPLIED_DISPLACEMENT_BITS, decision.displacementMeters.toRawBits())
            .apply()
    }

    fun loadApplied(context: Context): AdaptiveSamplingDecision? {
        val prefs = prefs(context.applicationContext)
        val mode = currentMode(prefs) ?: return null
        val priority = prefs.safeString(KEY_APPLIED_PRIORITY) ?: return null
        val displacementBits =
            prefs.safeLongOrNull(KEY_APPLIED_DISPLACEMENT_BITS) ?: return null
        return AdaptiveSamplingDecision(
            mode = mode,
            priorityName = priority,
            intervalMillis = prefs.safeLong(KEY_APPLIED_INTERVAL, 0L),
            fastestIntervalMillis = prefs.safeLong(KEY_APPLIED_FASTEST, 0L),
            maxWaitMillis = prefs.safeLong(KEY_APPLIED_MAX_WAIT, 0L),
            displacementMeters = Double.fromBits(displacementBits),
        )
    }

    fun appliedDisplacementMeters(context: Context): Double? {
        val prefs = prefs(context.applicationContext)
        return prefs.safeLongOrNull(KEY_APPLIED_DISPLACEMENT_BITS)
            ?.let { Double.fromBits(it) }
    }

    fun currentMode(context: Context): AdaptiveDisplacementMode? =
        currentMode(prefs(context.applicationContext))

    fun lastEdgeDistanceMeters(context: Context): Double? {
        val prefs = prefs(context.applicationContext)
        return prefs.safeLongOrNull(KEY_LAST_EDGE_DISTANCE_BITS)
            ?.let { Double.fromBits(it) }
    }

    @Synchronized
    fun clearApplied(context: Context) {
        prefs(context.applicationContext)
            .edit()
            .remove(KEY_MODE)
            .remove(KEY_APPLIED_PRIORITY)
            .remove(KEY_APPLIED_INTERVAL)
            .remove(KEY_APPLIED_FASTEST)
            .remove(KEY_APPLIED_MAX_WAIT)
            .remove(KEY_APPLIED_DISPLACEMENT_BITS)
            .apply()
    }

    @Synchronized
    fun reset(context: Context) {
        prefs(context.applicationContext)
            .edit()
            .remove(KEY_MODE)
            .remove(KEY_APPLIED_PRIORITY)
            .remove(KEY_APPLIED_INTERVAL)
            .remove(KEY_APPLIED_FASTEST)
            .remove(KEY_APPLIED_MAX_WAIT)
            .remove(KEY_APPLIED_DISPLACEMENT_BITS)
            .remove(KEY_LAST_EDGE_DISTANCE_BITS)
            .remove(KEY_LAST_EDGE_RECORDED_AT)
            .apply()
    }

    private fun loadFreshEdgeDistance(
        config: SmartGeofenceConfig,
        prefs: SharedPreferences,
    ): Double? {
        val edgeDistanceBits =
            prefs.safeLongOrNull(KEY_LAST_EDGE_DISTANCE_BITS) ?: return null
        val recordedAt = prefs.safeLong(KEY_LAST_EDGE_RECORDED_AT, 0L)
        val freshnessMillis = edgeFreshnessMillis(config)
        val ageMillis = System.currentTimeMillis() - recordedAt
        if (recordedAt <= 0L || ageMillis < 0L || ageMillis > freshnessMillis) {
            return null
        }
        return Double.fromBits(edgeDistanceBits)
    }

    internal fun edgeFreshnessMillis(config: SmartGeofenceConfig): Long = maxOf(
        MIN_EDGE_FRESHNESS_MILLIS,
        config.proximityMaxWaitMillis
            .coerceIn(0L, Long.MAX_VALUE / 2L) * 2L,
        config.activityFusedLocationStaleAfterMillis.coerceAtLeast(1L),
    )

    private fun currentMode(prefs: SharedPreferences): AdaptiveDisplacementMode? =
        prefs.safeString(KEY_MODE)
            ?.let { runCatching { AdaptiveDisplacementMode.valueOf(it) }.getOrNull() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
