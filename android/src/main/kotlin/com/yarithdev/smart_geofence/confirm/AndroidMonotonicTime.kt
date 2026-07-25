package com.yarithdev.smart_geofence.confirm

import android.content.Context
import com.yarithdev.smart_geofence.time.captureAndroidMonotonicTime as captureMonotonicTime
import com.yarithdev.smart_geofence.time.monotonicAgeMillis as monotonicAge
import com.yarithdev.smart_geofence.time.monotonicDeadlineRemainingMillis as deadlineRemaining

internal typealias AndroidMonotonicTime = com.yarithdev.smart_geofence.time.AndroidMonotonicTime

internal fun captureAndroidMonotonicTime(context: Context): AndroidMonotonicTime =
    captureMonotonicTime(context)

internal fun monotonicAgeMillis(
    startedAtElapsedRealtimeMillis: Long?,
    startedBootCount: Long?,
    now: AndroidMonotonicTime,
): Long? = monotonicAge(startedAtElapsedRealtimeMillis, startedBootCount, now)

internal fun monotonicDeadlineRemainingMillis(
    deadlineAtElapsedRealtimeMillis: Long?,
    deadlineBootCount: Long?,
    now: AndroidMonotonicTime,
    deadlineStartedAtElapsedRealtimeMillis: Long? = null,
    deadlineStartedAtWallClockMillis: Long? = null,
    nowWallClockMillis: Long? = null,
): Long = deadlineRemaining(
    deadlineAtElapsedRealtimeMillis,
    deadlineBootCount,
    now,
    deadlineStartedAtElapsedRealtimeMillis,
    deadlineStartedAtWallClockMillis,
    nowWallClockMillis,
)
