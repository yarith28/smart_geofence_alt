package com.yarithdev.smart_geofence.transition

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.alarm.AlarmScheduleMode
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.time.captureAndroidMonotonicTime
import com.yarithdev.smart_geofence.time.monotonicDeadlineRemainingMillis
import com.yarithdev.smart_geofence.wake.WakeExemption

internal object NativeTransitionScheduler {
    fun reschedule(
        context: Context,
        direction: NativeTransitionDirection,
        pending: Collection<PendingNativeTransition>,
    ): Boolean {
        val appContext = context.applicationContext
        val monotonicNow = captureAndroidMonotonicTime(appContext)
        val wallClockNow = System.currentTimeMillis()
        val directionalPending = pending.filter {
            it.direction == direction && (!it.validationRequired || it.nativeCandidate)
        }
        val nextDelayMillis = directionalPending.minOfOrNull {
            monotonicDeadlineRemainingMillis(
                it.deadlineAtElapsedRealtimeMillis,
                it.deadlineBootCount,
                monotonicNow,
                it.deadlineStartedAtElapsedRealtimeMillis,
                it.deadlineStartedAtWallClockMillis,
                wallClockNow,
            )
        }
        val existing = existingPendingIntent(appContext, direction)
        if (nextDelayMillis == null) {
            if (existing != null) {
                AlarmPolicyScheduler.cancel(appContext, direction.scheduleKey, existing)
                SmartGeofenceLogger.d(
                    appContext,
                    direction.logTag,
                    "Cancelled native ${direction.name} fallback alarm; no pending ${direction.pluralName}.",
                )
            }
            return true
        }
        if (existing != null) {
            AlarmPolicyScheduler.cancel(appContext, direction.scheduleKey, existing)
        }
        val pendingIntent = pendingIntent(appContext, direction, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: run {
                SmartGeofenceLogger.w(
                    appContext,
                    direction.logTag,
                    "Could not create native ${direction.name} fallback PendingIntent.",
                )
                return false
            }
        val result = AlarmPolicyScheduler.schedule(
            appContext,
            AlarmScheduleRequest(
                alarmType = if (monotonicNow.elapsedRealtimeMillis != null) {
                    AlarmManager.ELAPSED_REALTIME_WAKEUP
                } else {
                    AlarmManager.RTC_WAKEUP
                },
                triggerAtMillis = monotonicNow.elapsedRealtimeMillis
                    ?.let { safeTransitionAdd(it, nextDelayMillis) }
                    ?: safeTransitionAdd(wallClockNow, nextDelayMillis),
                primary = pendingIntent,
                policy = AlarmSchedulePolicy.ExactWithInexactFallback,
                scheduleKey = direction.scheduleKey,
                logTag = direction.logTag,
                logEventPrefix = direction.scheduleKey,
                detail = "pending=${directionalPending.size}",
            ),
        )
        if (!result.scheduled) {
            pendingIntent.cancel()
            SmartGeofenceLogger.w(
                appContext,
                direction.logTag,
                "Failed to schedule native ${direction.name} fallback alarm: " +
                    "${result.failureReason ?: result.eventSuffix}.",
            )
        }
        return result.scheduled
    }

    fun pendingIntentExists(context: Context, direction: NativeTransitionDirection): Boolean =
        existingPendingIntent(context.applicationContext, direction) != null

    fun firedAlarmWakeExemption(
        context: Context,
        direction: NativeTransitionDirection,
    ): WakeExemption {
        val mode = AlarmPolicyScheduler.diagnosticActualMode(
            context.applicationContext,
            direction.scheduleKey,
        )
        return if (mode == AlarmScheduleMode.Exact) {
            WakeExemption.EXACT_ALARM
        } else {
            WakeExemption.NONE
        }
    }

    private fun existingPendingIntent(
        context: Context,
        direction: NativeTransitionDirection,
    ): PendingIntent? = pendingIntent(context, direction, PendingIntent.FLAG_NO_CREATE)

    private fun pendingIntent(
        context: Context,
        direction: NativeTransitionDirection,
        baseFlags: Int,
    ): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return runCatching {
            PendingIntent.getBroadcast(
                context,
                direction.pendingIntentRequestCode,
                Intent()
                    .setClassName(context.packageName, direction.receiverClassName)
                    .setAction(direction.action),
                flags,
            )
        }.getOrNull()
    }
}
