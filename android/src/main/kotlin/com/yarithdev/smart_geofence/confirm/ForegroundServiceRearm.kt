package com.yarithdev.smart_geofence.confirm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.AndroidPackageManagerCompat
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.foreground.ForegroundLaunchState
import com.yarithdev.smart_geofence.foreground.ForegroundStartCoordinator
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator
import com.yarithdev.smart_geofence.wake.WakeAction
import com.yarithdev.smart_geofence.wake.WakeExemption
import com.yarithdev.smart_geofence.wake.WakeSource
import com.yarithdev.smart_geofence.wake.WakeTask

object ForegroundServiceRearm {
    private const val TAG = "ForegroundServiceRearm"
    private const val EVENT_FOREGROUND_SERVICE_REARM_ALARM = "foreground_service_rearm_alarm"
    internal const val SCHEDULE_KEY_REARM = "foreground_service_rearm"

    @Synchronized
    fun arm(
        context: Context,
        failedAttempt: Int,
        launchToken: Long,
        reason: String,
    ): Boolean {
        val appContext = context.applicationContext
        if (!ForegroundLaunchState.isCurrent(
                appContext,
                ForegroundLaunchState.SERVICE_LOCATION_CONFIRM,
                launchToken,
            )
        ) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Foreground rearm skipped; stale launch token=$launchToken reason=$reason."
            )
            return false
        }
        if (WakeEventCoordinator.foregroundWorkCount(appContext) == 0) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Foreground rearm skipped; queue is empty token=$launchToken reason=$reason."
            )
            return false
        }
        if (ForegroundLaunchState.snapshot(
                appContext,
                ForegroundLaunchState.SERVICE_LOCATION_CONFIRM
            ).rearmAttemptedAtMillis > 0L
        ) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Foreground rearm already used for token=$launchToken; refusing a second rearm. " +
                    "reason=$reason."
            )
            return false
        }
        if (ForegroundLaunchState.markRearmAttempted(
                appContext,
                ForegroundLaunchState.SERVICE_LOCATION_CONFIRM,
                launchToken,
            ) == null
        ) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Foreground rearm skipped; launch token became stale token=$launchToken."
            )
            return false
        }
        if (!hasReceiver(appContext, LocationConfirmAlarmReceiver::class.java)) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Foreground rearm skipped; LocationConfirmAlarmReceiver is not declared."
            )
            return false
        }
        val delayMillis = SmartGeofenceConfigStore.load(appContext)
            .foregroundServiceRearmDelayMillis
            .coerceAtLeast(0L)
        val nextAttempt = failedAttempt + 1
        val nowMillis = System.currentTimeMillis()
        val triggerAt = if (delayMillis > Long.MAX_VALUE - nowMillis) {
            Long.MAX_VALUE
        } else {
            nowMillis + delayMillis
        }
        val pending = pendingIntent(
            appContext,
            PendingIntent.FLAG_UPDATE_CURRENT,
            attempt = nextAttempt,
            launchToken = launchToken,
        ) ?: run {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Foreground rearm skipped; PendingIntent creation failed token=$launchToken reason=$reason."
            )
            return false
        }
        val result = AlarmPolicyScheduler.schedule(
            appContext,
            AlarmScheduleRequest(
                alarmType = AlarmManager.RTC_WAKEUP,
                triggerAtMillis = triggerAt,
                primary = pending,
                policy = AlarmSchedulePolicy.ExactOnly,
                scheduleKey = SCHEDULE_KEY_REARM,
                logTag = TAG,
                logEventPrefix = "foreground_rearm",
                detail = "delay=${delayMillis}ms token=$launchToken attempt=$nextAttempt reason=$reason",
            )
        )
        if (result.scheduled) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Foreground rearm ${result.eventSuffix} token=$launchToken attempt=$nextAttempt " +
                    "reason=$reason delay=${delayMillis}ms triggerAt=$triggerAt " +
                    "exactAllowed=${result.exactAllowed}."
            )
            return true
        }
        pending.cancel()
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Foreground rearm ${result.eventSuffix} token=$launchToken reason=$reason: " +
                (result.failureReason ?: result.eventSuffix)
        )
        return false
    }

    fun handleWatchdog(context: Context, attempt: Int, launchToken: Long) {
        val appContext = context.applicationContext
        if (!ForegroundLaunchState.isCurrent(
                appContext,
                ForegroundLaunchState.SERVICE_LOCATION_CONFIRM,
                launchToken,
            )
        ) {
            SmartGeofenceLogger.d(appContext, TAG, "Confirm watchdog ignored stale token=$launchToken.")
            return
        }
        ForegroundStartCoordinator.cancelWatchdog(appContext, LocationConfirmManager.LAUNCH_SPEC)
        if (LocationConfirmService.isForegroundReady) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Confirm watchdog satisfied; service is foreground-ready token=$launchToken."
            )
            return
        }
        val reason = "foreground readiness timeout"
        ForegroundStartCoordinator.markFailure(
            appContext,
            LocationConfirmManager.LAUNCH_SPEC,
            launchToken,
            reason
        )
        ForegroundStartCoordinator.markBatchServiceFinished(
            appContext,
            ForegroundLaunchState.SERVICE_LOCATION_CONFIRM,
            "watchdog_timeout",
            LocationConfirmManager.LAUNCH_SPEC.logService,
            launchToken,
            attempt,
        )
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Confirm service did not become foreground-ready token=$launchToken attempt=$attempt; " +
                "arming foreground rearm."
        )
        try {
            appContext.stopService(Intent(appContext, LocationConfirmService::class.java))
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(appContext, TAG, "Could not stop unready confirm service: ${e.message}", e)
        }
        arm(appContext, attempt, launchToken, reason)
    }

    fun handleAlarm(context: Context, attempt: Int, launchToken: Long) {
        val appContext = context.applicationContext
        existingPendingIntent(appContext)?.let {
            AlarmPolicyScheduler.cancel(appContext, SCHEDULE_KEY_REARM, it)
        }
        if (!ForegroundLaunchState.isCurrent(
                appContext,
                ForegroundLaunchState.SERVICE_LOCATION_CONFIRM,
                launchToken,
            )
        ) {
            SmartGeofenceLogger.d(appContext, TAG, "Foreground rearm alarm ignored stale token=$launchToken.")
            return
        }
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Foreground rearm alarm firing token=$launchToken attempt=$attempt; re-entering wake pipeline."
        )
        WakeEventCoordinator.submit(
            appContext,
            WakeTask(
                source = WakeSource.FOREGROUND_REARM,
                action = WakeAction.DRAIN_FOREGROUND_QUEUE,
                exemption = WakeExemption.EXACT_ALARM,
                reason = "foreground service rearm alarm",
                event = EVENT_FOREGROUND_SERVICE_REARM_ALARM,
                attempt = attempt,
                launchToken = launchToken,
            )
        )
    }

    @Synchronized
    fun cancel(context: Context, reason: String) {
        val appContext = context.applicationContext
        existingPendingIntent(appContext)?.let { pending ->
            AlarmPolicyScheduler.cancel(appContext, SCHEDULE_KEY_REARM, pending)
            SmartGeofenceLogger.d(appContext, TAG, "Cancelled foreground rearm alarm reason=$reason.")
        }
        ForegroundLaunchState.clearRearm(appContext, ForegroundLaunchState.SERVICE_LOCATION_CONFIRM)
    }

    fun pendingIntentExists(context: Context): Boolean =
        existingPendingIntent(context.applicationContext) != null

    private fun existingPendingIntent(context: Context): PendingIntent? =
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE)

    private fun pendingIntent(
        context: Context,
        baseFlags: Int,
        attempt: Int? = null,
        launchToken: Long? = null,
    ): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return runCatching {
            val intent = Intent(context, LocationConfirmAlarmReceiver::class.java).apply {
                putExtra(LocationConfirmManager.EXTRA_ALARM_PURPOSE, LocationConfirmManager.PURPOSE_REARM)
                if (attempt != null) putExtra(LocationConfirmManager.EXTRA_START_ATTEMPT, attempt)
                if (launchToken != null) putExtra(LocationConfirmManager.EXTRA_LAUNCH_TOKEN, launchToken)
            }
            PendingIntent.getBroadcast(
                context,
                Constants.PENDING_INTENT_REQUEST_CONFIRM_REARM,
                intent,
                flags
            )
        }.getOrNull()
    }

    private fun hasReceiver(context: Context, receiverClass: Class<*>): Boolean {
        return try {
            val component = ComponentName(context, receiverClass)
            AndroidPackageManagerCompat.getReceiverInfo(
                context.packageManager,
                component,
            )
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
