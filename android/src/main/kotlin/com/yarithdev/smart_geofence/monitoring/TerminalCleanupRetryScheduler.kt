package com.yarithdev.smart_geofence.monitoring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

internal fun terminalCleanupRetryDelayMillis(attempt: Int): Long {
    val exponent = attempt.coerceIn(0, 4)
    return (TerminalCleanupRetryScheduler.INITIAL_DELAY_MILLIS * (1L shl exponent))
        .coerceAtMost(TerminalCleanupRetryScheduler.MAX_DELAY_MILLIS)
}

object TerminalCleanupRetryScheduler {
    internal const val SCHEDULE_KEY = "terminal_monitoring_cleanup"
    internal const val INITIAL_DELAY_MILLIS = 60_000L
    internal const val MAX_DELAY_MILLIS = 15 * 60_000L
    internal const val EXTRA_ATTEMPT = "terminal_cleanup_attempt"

    private const val TAG = "TerminalCleanupRetry"

    @Synchronized
    fun ensureScheduled(
        context: Context,
        reason: String,
        attempt: Int = 0,
    ): Boolean {
        val appContext = context.applicationContext
        if (!MonitoringStopStateStore.snapshot(appContext).terminallyStopped) {
            cancel(appContext, "terminal_stop_inactive_$reason")
            return false
        }
        val nowMillis = System.currentTimeMillis()
        val existing = existingPendingIntent(appContext)
        val existingTriggerAtMillis = if (existing != null) {
            (AlarmPolicyScheduler.diagnosticStatus(appContext, SCHEDULE_KEY)["triggerAtMillis"]
                as? Number)?.toLong()
        } else {
            null
        }
        if (existing != null &&
            existingTriggerAtMillis != null &&
            existingTriggerAtMillis > nowMillis
        ) {
            return true
        }
        if (existing != null) {
            AlarmPolicyScheduler.cancel(appContext, SCHEDULE_KEY, existing)
            existing.cancel()
        }
        val pending = pendingIntent(
            appContext,
            PendingIntent.FLAG_UPDATE_CURRENT,
            attempt.coerceAtLeast(0),
        ) ?: return false
        val triggerAtMillis = safeAdd(
            nowMillis,
            terminalCleanupRetryDelayMillis(attempt),
        )
        val result = AlarmPolicyScheduler.schedule(
            appContext,
            AlarmScheduleRequest(
                alarmType = AlarmManager.RTC_WAKEUP,
                triggerAtMillis = triggerAtMillis,
                primary = pending,
                policy = AlarmSchedulePolicy.ExactWithInexactFallback,
                scheduleKey = SCHEDULE_KEY,
                logTag = TAG,
                logEventPrefix = "terminal_cleanup_retry",
                detail = "attempt=$attempt reason=$reason",
            ),
        )
        if (!result.scheduled) pending.cancel()
        return result.scheduled
    }

    fun handleAlarm(context: Context, attempt: Int): Boolean {
        val appContext = context.applicationContext
        cancel(appContext, "alarm_fired")
        if (!MonitoringStopStateStore.snapshot(appContext).terminallyStopped) return true
        val nextAttempt = if (attempt == Int.MAX_VALUE) Int.MAX_VALUE else attempt + 1
        ensureScheduled(appContext, "alarm_prearm", nextAttempt)
        TerminalMonitoringStopController.enforce(appContext, "terminal_cleanup_alarm")
        return MonitoringStopStateStore.snapshot(appContext).nativeCleanupComplete
    }

    @Synchronized
    fun cancel(context: Context, reason: String) {
        val appContext = context.applicationContext
        val pending = existingPendingIntent(appContext)
        AlarmPolicyScheduler.cancel(appContext, SCHEDULE_KEY, pending)
        pending?.cancel()
        if (pending != null) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Cancelled terminal cleanup retry reason=$reason.",
            )
        }
    }

    fun pendingIntentExists(context: Context): Boolean =
        existingPendingIntent(context.applicationContext) != null

    private fun existingPendingIntent(context: Context): PendingIntent? =
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE, attempt = 0)

    private fun pendingIntent(
        context: Context,
        baseFlags: Int,
        attempt: Int,
    ): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return runCatching {
            PendingIntent.getBroadcast(
                context,
                Constants.PENDING_INTENT_REQUEST_TERMINAL_CLEANUP_RETRY,
                Intent(context, TerminalCleanupRetryReceiver::class.java)
                    .putExtra(EXTRA_ATTEMPT, attempt),
                flags,
            )
        }.getOrNull()
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
