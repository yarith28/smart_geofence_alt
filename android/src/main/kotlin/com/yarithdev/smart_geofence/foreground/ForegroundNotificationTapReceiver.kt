package com.yarithdev.smart_geofence.foreground

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

class ForegroundNotificationTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        if (intent?.action != Constants.ACTION_FOREGROUND_NOTIFICATION_DISMISS) return

        val config = SmartGeofenceConfigStore.load(appContext)
        val manager = appContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(ForegroundNotificationFactory.notificationId(config))
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Foreground notification dismissed from tap action."
        )
    }

    private companion object {
        const val TAG = "ForegroundNotificationTapReceiver"
    }
}
