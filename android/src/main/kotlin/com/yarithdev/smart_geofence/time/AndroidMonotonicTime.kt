package com.yarithdev.smart_geofence.time

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

internal data class AndroidMonotonicTime(
    val elapsedRealtimeMillis: Long?,
    val bootCount: Long?,
)

private const val BOOT_CLOCK_ORIGIN_TOLERANCE_MILLIS = 5_000L

internal fun elapsedWallClockOriginsConsistent(
    firstElapsedRealtimeMillis: Long,
    firstWallClockMillis: Long,
    secondElapsedRealtimeMillis: Long,
    secondWallClockMillis: Long,
): Boolean {
    if (firstElapsedRealtimeMillis < 0L || secondElapsedRealtimeMillis < 0L ||
        firstWallClockMillis < firstElapsedRealtimeMillis ||
        secondWallClockMillis < secondElapsedRealtimeMillis
    ) {
        return false
    }
    val firstOrigin = firstWallClockMillis - firstElapsedRealtimeMillis
    val secondOrigin = secondWallClockMillis - secondElapsedRealtimeMillis
    val originDrift = if (firstOrigin >= secondOrigin) {
        firstOrigin - secondOrigin
    } else {
        secondOrigin - firstOrigin
    }
    return originDrift <= BOOT_CLOCK_ORIGIN_TOLERANCE_MILLIS
}

internal fun captureAndroidMonotonicTime(context: Context): AndroidMonotonicTime {
    val appContext = context.applicationContext
    return AndroidMonotonicTime(
        elapsedRealtimeMillis = runCatching { SystemClock.elapsedRealtime() }.getOrNull(),
        bootCount = runCatching {
            Settings.Global
                .getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
                .toLong()
        }.getOrNull(),
    )
}

internal fun monotonicAgeMillis(
    startedAtElapsedRealtimeMillis: Long?,
    startedBootCount: Long?,
    now: AndroidMonotonicTime,
): Long? {
    val started = startedAtElapsedRealtimeMillis ?: return null
    val current = now.elapsedRealtimeMillis ?: return null
    if (startedBootCount != null && now.bootCount != null && startedBootCount != now.bootCount) {
        return null
    }
    if (current < started) return null
    return current - started
}

internal fun monotonicDeadlineRemainingMillis(
    deadlineAtElapsedRealtimeMillis: Long?,
    deadlineBootCount: Long?,
    now: AndroidMonotonicTime,
    deadlineStartedAtElapsedRealtimeMillis: Long? = null,
    deadlineStartedAtWallClockMillis: Long? = null,
    nowWallClockMillis: Long? = null,
): Long {
    val deadline = deadlineAtElapsedRealtimeMillis ?: return 0L
    val current = now.elapsedRealtimeMillis ?: return 0L
    if (deadlineBootCount != null && now.bootCount != null && deadlineBootCount != now.bootCount) {
        return 0L
    }
    if (deadlineBootCount == null || now.bootCount == null) {
        val started = deadlineStartedAtElapsedRealtimeMillis ?: return 0L
        val startedWall = deadlineStartedAtWallClockMillis ?: return 0L
        val currentWall = nowWallClockMillis ?: return 0L
        if (started < 0L || current < started || deadline < started) return 0L
        if (startedWall < 0L || currentWall < startedWall) return 0L

        if (!elapsedWallClockOriginsConsistent(
                started,
                startedWall,
                current,
                currentWall,
            )
        ) {
            return 0L
        }
    } else if (
        deadlineStartedAtElapsedRealtimeMillis != null &&
        current < deadlineStartedAtElapsedRealtimeMillis
    ) {
        return 0L
    }
    return (deadline - current).coerceAtLeast(0L)
}
