package com.yarithdev.smart_geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yarithdev.smart_geofence.activity.ActivityMonitor
import com.yarithdev.smart_geofence.recovery.BootRecoveryCoordinator
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        ActivityMonitor.invalidateRegistrationConfidence(
            context.applicationContext,
            "boot_or_package_replaced:${action ?: "unknown"}",
        )
        SmartGeofenceLogger.d(context, TAG, "Boot/update ($action): full-stack recovery.")
        val pending = goAsync()
        val finishPending = BootRecoveryCoordinator.finishPendingResultOnDeadline(
            context,
            pending,
            TAG,
            "boot/update action=$action",
        )
        BootRecoveryCoordinator.runAsync(
            context,
            BootRecoveryCoordinator.SOURCE_BOOT_IMMEDIATE,
            action,
            scheduleFollowUp = true,
        ) {
            finishPending()
        }
    }

    companion object {
        private const val TAG = "SmartGeofenceBootReceiver"
    }
}
