package com.yarithdev.smart_geofence.wake

enum class WakeSource {
    NATIVE_GEOFENCE,
    ACTIVITY,
    LOCATION_CONFIRM_ALARM,
    FOREGROUND_REARM,
}

enum class WakeAction {
    DRAIN_FOREGROUND_QUEUE,
}

enum class WakeExemption {
    GEOFENCE,
    ACTIVITY_TRANSITION,
    EXACT_ALARM,
    NONE,
}

internal fun WakeExemption.allowsForegroundServiceLaunch(): Boolean =
    this == WakeExemption.GEOFENCE ||
        this == WakeExemption.ACTIVITY_TRANSITION ||
        this == WakeExemption.EXACT_ALARM

data class WakeTask(
    val source: WakeSource,
    val action: WakeAction,
    val exemption: WakeExemption,
    val reason: String,
    val event: String? = null,
    val geofenceIds: List<String> = emptyList(),
    val attempt: Int = 0,
    val launchToken: Long = 0L,
)
