package com.yarithdev.smart_geofence.confirm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmScheduleMode
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.wake.WakeAction
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator
import com.yarithdev.smart_geofence.wake.WakeExemption
import com.yarithdev.smart_geofence.wake.WakeSource
import com.yarithdev.smart_geofence.wake.WakeTask

class LocationConfirmAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val attempt = intent.getIntExtra(LocationConfirmManager.EXTRA_START_ATTEMPT, 0)
        val token = intent.getLongExtra(LocationConfirmManager.EXTRA_LAUNCH_TOKEN, 0L)
        val purpose = intent.getStringExtra(LocationConfirmManager.EXTRA_ALARM_PURPOSE)
            ?: LocationConfirmManager.PURPOSE_START
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Confirm alarm fired purpose=$purpose token=$token attempt=$attempt " +
                "pendingForegroundWork=${WakeEventCoordinator.foregroundWorkCount(appContext)}."
        )
        val scheduleKey = when (purpose) {
            LocationConfirmManager.PURPOSE_WATCHDOG ->
                com.yarithdev.smart_geofence.foreground.ForegroundStartCoordinator
                    .watchdogScheduleKey(LocationConfirmManager.LAUNCH_SPEC)
            LocationConfirmManager.PURPOSE_REARM -> ForegroundServiceRearm.SCHEDULE_KEY_REARM
            LocationConfirmManager.PURPOSE_LOCATION_DISABLED_RECOVERY ->
                LocationDisabledRecoveryScheduler.SCHEDULE_KEY
            else -> com.yarithdev.smart_geofence.foreground.ForegroundStartCoordinator
                .startScheduleKey(LocationConfirmManager.LAUNCH_SPEC)
        }
        val alarmStatus = AlarmPolicyScheduler.diagnosticStatus(appContext, scheduleKey)
        val triggerAt = alarmStatus["triggerAtMillis"] as? Long
        val alarmMode = AlarmPolicyScheduler.diagnosticActualMode(appContext, scheduleKey)
        val alarmWakeExemption = if (alarmMode == AlarmScheduleMode.Exact) {
            WakeExemption.EXACT_ALARM
        } else {
            WakeExemption.NONE
        }
        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "alarm_fired",
            reasonCode = purpose,
            source = scheduleKey,
            extras = linkedMapOf(
                "launchToken" to token,
                "attempt" to attempt,
                "alarmMode" to alarmMode?.configValue,
                "wakeExemption" to alarmWakeExemption.name.lowercase(),
                "triggerAtMillis" to triggerAt,
                "latenessMillis" to triggerAt?.let {
                    (System.currentTimeMillis() - it).coerceAtLeast(0L)
                },
            ),
        )
        when (purpose) {
            LocationConfirmManager.PURPOSE_WATCHDOG ->
                ForegroundServiceRearm.handleWatchdog(appContext, attempt, token)
            LocationConfirmManager.PURPOSE_REARM ->
                ForegroundServiceRearm.handleAlarm(appContext, attempt, token)
            LocationConfirmManager.PURPOSE_LOCATION_DISABLED_RECOVERY ->
                LocationDisabledRecoveryScheduler.handleAlarm(appContext, attempt)
            else ->
                WakeEventCoordinator.submit(
                    appContext,
                    WakeTask(
                        source = WakeSource.LOCATION_CONFIRM_ALARM,
                        action = WakeAction.DRAIN_FOREGROUND_QUEUE,
                        exemption = alarmWakeExemption,
                        reason = if (attempt > 0) "alarm rearm" else "alarm start",
                        event = purpose,
                        attempt = attempt,
                        launchToken = token,
                    )
                )
        }
    }

    companion object {
        private const val TAG = "LocationConfirmAlarmReceiver"
    }
}
