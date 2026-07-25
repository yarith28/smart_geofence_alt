package com.yarithdev.smart_geofence.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import java.util.Calendar
import java.util.TimeZone

object RecoveryScheduler {
    private const val TAG = "RecoveryScheduler"
    internal const val SCHEDULE_KEY = "recovery"
    const val ACTION_RECOVERY_ALARM =
        "com.yarithdev.smart_geofence.action.RECOVERY_ALARM"

    fun schedule(context: Context, config: SmartGeofenceConfig): Boolean {
        val appContext = context.applicationContext
        cancel(appContext)
        val triggerAtMillis = nextTriggerAtMillis(config.recoveryTimesMinuteOfDay)
        if (triggerAtMillis == null) {
            SmartGeofenceLogger.w(appContext, TAG, "Recovery alarm not scheduled; no times configured.")
            return false
        }
        if (config.recoveryAlarmPolicy == AlarmSchedulePolicy.InexactWithExactGuard &&
            config.recoveryInexactGuardDelayMillis?.takeIf { it > 0L } == null
        ) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Recovery alarm not scheduled; inexact guard policy requires recoveryInexactGuardDelayMillis."
            )
            return false
        }

        val guarded = config.recoveryAlarmPolicy == AlarmSchedulePolicy.InexactWithExactGuard
        val scheduleToken = if (guarded) AlarmPolicyScheduler.newScheduleToken() else 0L
        val primary = pendingIntent(
            appContext,
            PendingIntent.FLAG_UPDATE_CURRENT,
            requestCode = Constants.PENDING_INTENT_REQUEST_RECOVERY_ALARM,
            scheduleToken = scheduleToken,
            isGuard = false,
            guarded = guarded,
        ) ?: run {
            SmartGeofenceLogger.w(appContext, TAG, "Recovery alarm not scheduled; PendingIntent creation failed.")
            return false
        }
        val guard = if (guarded) {
            pendingIntent(
                appContext,
                PendingIntent.FLAG_UPDATE_CURRENT,
                requestCode = Constants.PENDING_INTENT_REQUEST_RECOVERY_ALARM_GUARD,
                scheduleToken = scheduleToken,
                isGuard = true,
                guarded = true,
            ) ?: run {
                primary.cancel()
                SmartGeofenceLogger.w(appContext, TAG, "Recovery alarm not scheduled; guard PendingIntent creation failed.")
                return false
            }
        } else {
            null
        }
        val result = AlarmPolicyScheduler.schedule(
            appContext,
            AlarmScheduleRequest(
                alarmType = AlarmManager.RTC_WAKEUP,
                triggerAtMillis = triggerAtMillis,
                primary = primary,
                policy = config.recoveryAlarmPolicy,
                guard = guard,
                guardDelayMillis = config.recoveryInexactGuardDelayMillis,
                scheduleKey = SCHEDULE_KEY,
                scheduleToken = scheduleToken,
                logTag = TAG,
                logEventPrefix = "recovery_alarm",
                detail = "times=${config.recoveryTimesMinuteOfDay}",
            )
        )
        return result.scheduled
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        AlarmPolicyScheduler.cancel(
            appContext,
            SCHEDULE_KEY,
            existingPendingIntent(appContext, Constants.PENDING_INTENT_REQUEST_RECOVERY_ALARM),
            existingPendingIntent(appContext, Constants.PENDING_INTENT_REQUEST_RECOVERY_ALARM_GUARD),
        )
    }

    fun pendingIntentExists(context: Context): Boolean {
        val appContext = context.applicationContext
        return existingPendingIntent(appContext, Constants.PENDING_INTENT_REQUEST_RECOVERY_ALARM) != null ||
            existingPendingIntent(appContext, Constants.PENDING_INTENT_REQUEST_RECOVERY_ALARM_GUARD) != null
    }

    internal fun nextTriggerAtMillis(
        timesMinuteOfDay: List<Int>,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long? = timesMinuteOfDay
        .asSequence()
        .filter { it in 0 until MINUTES_PER_DAY }
        .distinct()
        .map { minuteOfDay ->
            Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, minuteOfDay / MINUTES_PER_HOUR)
                set(Calendar.MINUTE, minuteOfDay % MINUTES_PER_HOUR)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }
        .minOrNull()

    private fun existingPendingIntent(context: Context, requestCode: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            alarmIntent(context),
            immutableFlags(PendingIntent.FLAG_NO_CREATE),
        )

    private fun pendingIntent(
        context: Context,
        baseFlags: Int,
        requestCode: Int,
        scheduleToken: Long,
        isGuard: Boolean,
        guarded: Boolean,
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        requestCode,
        alarmIntent(context, scheduleToken, isGuard, guarded),
        immutableFlags(baseFlags),
    )

    private fun alarmIntent(
        context: Context,
        scheduleToken: Long = 0L,
        isGuard: Boolean = false,
        guarded: Boolean = false,
    ): Intent = Intent(context, RecoveryAlarmReceiver::class.java)
        .setAction(ACTION_RECOVERY_ALARM)
        .putExtra(AlarmPolicyScheduler.EXTRA_SCHEDULE_TOKEN, scheduleToken)
        .putExtra(AlarmPolicyScheduler.EXTRA_IS_GUARD, isGuard)
        .putExtra(AlarmPolicyScheduler.EXTRA_IS_GUARDED, guarded)

    private fun immutableFlags(baseFlags: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            baseFlags or PendingIntent.FLAG_IMMUTABLE
        } else {
            baseFlags
        }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
}
