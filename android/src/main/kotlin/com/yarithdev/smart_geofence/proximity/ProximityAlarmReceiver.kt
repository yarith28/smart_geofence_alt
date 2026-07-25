package com.yarithdev.smart_geofence.proximity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yarithdev.smart_geofence.activity.FusedLocationLivenessTrigger
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseStateStore

internal enum class ProximityAlarmDispatch {
    PULSE_TICK,
    LIVENESS,
    IGNORE,
}

internal fun consumeAndSelectProximityAlarmDispatch(
    alarmKind: ProximityAlarmKind?,
    pulseActive: Boolean,
    consume: (Boolean) -> Unit,
): ProximityAlarmDispatch {
    consume(pulseActive)
    return when {
        pulseActive -> ProximityAlarmDispatch.PULSE_TICK
        alarmKind == ProximityAlarmKind.LIVENESS -> ProximityAlarmDispatch.LIVENESS
        else -> ProximityAlarmDispatch.IGNORE
    }
}

class ProximityAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        try {
            SmartGeofenceLogger.d(appContext, TAG, "Shared proximity alarm fired.")
            val alarmKind = ProximityAlarmScheduler.scheduledKind(appContext)
            val schedule = com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
                .diagnosticStatus(appContext, ProximityAlarmScheduler.SCHEDULE_KEY_PROXIMITY)
            val triggerAt = schedule["triggerAtMillis"] as? Long
            SmartGeofenceDiagnostics.recordTrace(
                appContext,
                stage = "alarm_fired",
                reasonCode = alarmKind?.name?.lowercase() ?: "unknown",
                source = ProximityAlarmScheduler.SCHEDULE_KEY_PROXIMITY,
                extras = linkedMapOf(
                    "triggerAtMillis" to triggerAt,
                    "latenessMillis" to triggerAt?.let {
                        (System.currentTimeMillis() - it).coerceAtLeast(0L)
                    },
                ),
            )
            val pulseActive = ProximityPulseStateStore.load(appContext)?.schedulingActive == true
            when (consumeAndSelectProximityAlarmDispatch(alarmKind, pulseActive) {
                ProximityAlarmScheduler.consume(appContext, it)
            }) {
                ProximityAlarmDispatch.PULSE_TICK -> {
                    ProximityPulseController.onTick(appContext)
                    return
                }
                ProximityAlarmDispatch.IGNORE -> {
                    SmartGeofenceLogger.d(
                        appContext,
                        TAG,
                        "Inactive proximity alarm ignored kind=" +
                            "${alarmKind?.name?.lowercase() ?: "unknown"}.",
                    )
                    SmartGeofenceDiagnostics.recordTrace(
                        appContext,
                        stage = "alarm_result",
                        reasonCode = "inactive_ignored",
                        source = ProximityAlarmScheduler.SCHEDULE_KEY_PROXIMITY,
                    )
                    return
                }
                ProximityAlarmDispatch.LIVENESS -> runLivenessTrigger(appContext)
            }
        } catch (e: Throwable) {
            SmartGeofenceDiagnostics.recordTrace(
                appContext,
                stage = "alarm_result",
                reasonCode = "handler_failed",
                source = ProximityAlarmScheduler.SCHEDULE_KEY_PROXIMITY,
                extras = mapOf("errorType" to e.javaClass.name),
            )
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Shared proximity alarm handling failed: ${e.message}",
                e,
            )
        }
    }

    private fun runLivenessTrigger(context: Context) {
        val pending = goAsync()
        var callbackWillFinish = false
        try {
            callbackWillFinish = FusedLocationLivenessTrigger.run(context) {
                pending.finish()
            }
        } finally {
            if (!callbackWillFinish) {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ProximityAlarmReceiver"
    }
}
