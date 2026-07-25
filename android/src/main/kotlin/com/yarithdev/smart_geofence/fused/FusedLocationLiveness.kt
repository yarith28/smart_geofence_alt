package com.yarithdev.smart_geofence.fused

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeLongOrNull
import com.yarithdev.smart_geofence.core.safeString

data class FusedLocationLivenessSnapshot(
    val lastHealthyAtMillis: Long?,
    val lastHealthySource: String?,
    val recoveryStartedAtMillis: Long?,
    val lastRecoveryEndedAtMillis: Long?,
    val lastRecoveryReason: String?,
    val balancedRefreshCount: Long,
)

object FusedLocationLiveness {
    private const val HEALTH_PERSIST_INTERVAL_MILLIS = 5 * 60 * 1000L
    private const val KEY_LAST_HEALTHY_AT = "fused_liveness_last_healthy_at"
    private const val KEY_LAST_HEALTHY_LOCATION_TIME =
        "fused_liveness_last_healthy_location_time"
    private const val KEY_LAST_HEALTHY_SOURCE = "fused_liveness_last_healthy_source"
    private const val KEY_RECOVERY_STARTED_AT = "fused_liveness_recovery_started_at"
    private const val KEY_LAST_RECOVERY_ENDED_AT = "fused_liveness_last_recovery_ended_at"
    private const val KEY_LAST_RECOVERY_REASON = "fused_liveness_last_recovery_reason"
    private const val KEY_BALANCED_REFRESH_COUNT = "fused_liveness_balanced_refresh_count"

    @Volatile
    private var memoryLastHealthyAtMillis = 0L

    @Volatile
    private var memoryLastHealthyLocationTime = 0L

    @Volatile
    private var memoryLastHealthySource: String? = null

    fun isStale(
        context: Context,
        config: SmartGeofenceConfig,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = isStaleAt(
        lastHealthyAtMillis(context.applicationContext),
        nowMillis,
        config.activityFusedLocationStaleAfterMillis,
    )

    internal fun isStaleAt(
        lastHealthyAtMillis: Long?,
        nowMillis: Long,
        staleAfterMillis: Long,
    ): Boolean {
        val lastHealthy = lastHealthyAtMillis ?: return true
        val ageMillis = nowMillis - lastHealthy
        return ageMillis < 0L || ageMillis >= staleAfterMillis.coerceAtLeast(1L)
    }

    fun lastHealthyAtMillis(context: Context): Long? =
        maxOf(
            memoryLastHealthyAtMillis,
            prefs(context.applicationContext).safeLong(KEY_LAST_HEALTHY_AT, 0L),
        ).takeIf { it > 0L }

    fun healthyAgeMillis(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? = lastHealthyAtMillis(context)?.let { lastHealthy ->
        (nowMillis - lastHealthy).takeIf { it >= 0L }
    }

    @Synchronized
    fun recordHealthyFix(
        context: Context,
        location: Location,
        source: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val appContext = context.applicationContext
        val preferences = prefs(appContext)
        val locationTime = location.time
        if (locationTime > 0L &&
            (memoryLastHealthyLocationTime == locationTime ||
                preferences.safeLong(KEY_LAST_HEALTHY_LOCATION_TIME, 0L) == locationTime)
        ) {
            return
        }
        memoryLastHealthyAtMillis = nowMillis
        memoryLastHealthyLocationTime = locationTime
        memoryLastHealthySource = source
        val persistedAt = preferences.safeLong(KEY_LAST_HEALTHY_AT, 0L)
        if (persistedAt > 0L &&
            nowMillis >= persistedAt &&
            nowMillis - persistedAt < HEALTH_PERSIST_INTERVAL_MILLIS
        ) {
            return
        }
        preferences.edit()
            .putLong(KEY_LAST_HEALTHY_AT, nowMillis)
            .putLong(KEY_LAST_HEALTHY_LOCATION_TIME, locationTime)
            .putString(KEY_LAST_HEALTHY_SOURCE, source)
            .apply()
    }

    @Synchronized
    fun beginRecovery(
        context: Context,
        reason: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val preferences = prefs(context.applicationContext)
        if (preferences.safeLong(KEY_RECOVERY_STARTED_AT, 0L) > 0L) return
        preferences.edit()
            .putLong(KEY_RECOVERY_STARTED_AT, nowMillis)
            .putString(KEY_LAST_RECOVERY_REASON, reason)
            .apply()
    }

    @Synchronized
    fun endRecovery(
        context: Context,
        reason: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val preferences = prefs(context.applicationContext)
        if (preferences.safeLong(KEY_RECOVERY_STARTED_AT, 0L) <= 0L) return
        preferences.edit()
            .remove(KEY_RECOVERY_STARTED_AT)
            .putLong(KEY_LAST_RECOVERY_ENDED_AT, nowMillis)
            .putString(KEY_LAST_RECOVERY_REASON, reason)
            .apply()
    }

    @Synchronized
    fun recordBalancedRefresh(context: Context) {
        val preferences = prefs(context.applicationContext)
        val current = preferences.safeLong(KEY_BALANCED_REFRESH_COUNT, 0L)
        preferences.edit()
            .putLong(KEY_BALANCED_REFRESH_COUNT, current + 1L)
            .apply()
    }

    fun snapshot(context: Context): FusedLocationLivenessSnapshot {
        val preferences = prefs(context.applicationContext)
        return FusedLocationLivenessSnapshot(
            lastHealthyAtMillis = lastHealthyAtMillis(context),
            lastHealthySource = memoryLastHealthySource
                ?: preferences.safeString(KEY_LAST_HEALTHY_SOURCE),
            recoveryStartedAtMillis = preferences.safeLongOrNull(KEY_RECOVERY_STARTED_AT)
                ?.takeIf { it > 0L },
            lastRecoveryEndedAtMillis = preferences.safeLongOrNull(KEY_LAST_RECOVERY_ENDED_AT)
                ?.takeIf { it > 0L },
            lastRecoveryReason = preferences.safeString(KEY_LAST_RECOVERY_REASON),
            balancedRefreshCount = preferences.safeLong(KEY_BALANCED_REFRESH_COUNT, 0L),
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
