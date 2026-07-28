package com.yarithdev.smart_geofence.proximitypulse

import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import java.util.Calendar
import java.util.TimeZone

object AdaptivePulseRate {
    fun isActive(
        config: SmartGeofenceConfig,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR +
            calendar.get(Calendar.MINUTE)
        return isActiveMinute(
            minuteOfDay,
            config.proximityPulseActiveStartMinuteOfDay,
            config.proximityPulseActiveEndMinuteOfDay,
        )
    }

    fun intervalMillis(
        config: SmartGeofenceConfig,
        purpose: ProximityPulsePurpose,
    ): Long = intervalMillis(
        config,
        purpose,
        activeHoursNow = isActive(config),
    )

    internal fun intervalMillis(
        config: SmartGeofenceConfig,
        purpose: ProximityPulsePurpose,
        activeHoursNow: Boolean,
    ): Long {
        val requested = when (purpose) {
            ProximityPulsePurpose.TRANSITION_CONFIRMATION ->
                config.proximityPulseTransitionConfirmationIntervalMillis
            ProximityPulsePurpose.NEAR_FENCE ->
                config.proximityPulseNearFenceIntervalMillis
            ProximityPulsePurpose.PROXIMITY,
            ProximityPulsePurpose.INSIDE,
            ProximityPulsePurpose.FUSED_LIVENESS,
            -> config.proximityPulseIntervalMillis
        }.coerceAtLeast(0L)
        val activeInterval =
            requested.coerceAtLeast(config.proximityPulseMinIntervalMillis.coerceAtLeast(1L))
        if (activeHoursNow) return activeInterval
        val outsideMultiplier =
            config.proximityPulseOutsideActiveHoursIntervalMultiplier.coerceAtLeast(1).toLong()
        return if (activeInterval > Long.MAX_VALUE / outsideMultiplier) {
            Long.MAX_VALUE
        } else {
            activeInterval * outsideMultiplier
        }
    }

    internal fun isActiveMinute(
        minuteOfDay: Int,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
    ): Boolean {
        val minute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val start = startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) {
            minute in start until end
        } else {
            minute >= start || minute < end
        }
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
}
