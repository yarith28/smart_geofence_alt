package com.yarithdev.smart_geofence.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yarithdev.smart_geofence.core.SmartGeofenceController
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            intent?.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            return
        }

        val appContext = context.applicationContext
        if (ExactAlarmPermissionController.canScheduleExactAlarms(appContext)) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "exact_alarm_permission_granted; refreshing smart layers.",
            )
            SmartGeofenceController.refresh(appContext)
        } else {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "exact_alarm_permission_state_changed_but_unavailable.",
            )
            SmartGeofenceController.refresh(appContext)
        }
    }

    companion object {
        private const val TAG = "ExactAlarmPermissionReceiver"
    }
}
