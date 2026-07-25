package com.yarithdev.smart_geofence.foreground

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.confirm.LocationConfirmService
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeInt
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

object IdleMonitoringNotification {
    private const val TAG = "IdleMonitoringNotification"
    private const val KEY_LAST_ID = "idle_foreground_notification_last_id"
    private const val LEGACY_KEY_LAST_ID = "monitoring_notification_last_id"
    private const val STALE_SEPARATE_MONITORING_NOTIFICATION_ID = 9103

    fun show(context: Context, config: SmartGeofenceConfig): Boolean {
        val appContext = context.applicationContext
        if (!shouldPostIdleNotification(config)) {
            cancelIdle(appContext)
            return false
        }
        if (!canPostNotifications(appContext)) {
            cancelIdle(appContext)
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Idle foreground notification skipped; POST_NOTIFICATIONS is not granted."
            )
            return false
        }

        val manager = appContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Idle foreground notification skipped; NotificationManager unavailable."
            )
            return false
        }

        val currentId = notificationId(config)
        cancelPreviousIds(appContext, manager, currentId)
        val notification = ForegroundNotificationFactory.build(appContext, config)

        return try {
            manager.notify(currentId, notification)
            recordLastId(appContext, currentId)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Idle foreground notification posted id=$currentId."
            )
            true
        } catch (e: SecurityException) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Idle foreground notification failed: ${e.message}",
                e
            )
            false
        } catch (e: RuntimeException) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Idle foreground notification failed: ${e.message}",
                e
            )
            false
        }
    }

    fun cancel(context: Context) {
        cancel(context, preserveActiveForegroundServiceNotification = false)
    }

    fun notificationId(config: SmartGeofenceConfig): Int =
        ForegroundNotificationFactory.notificationId(config)

    fun shouldPostIdleNotification(config: SmartGeofenceConfig): Boolean =
        config.foregroundNotificationShowWhileMonitoring

    private fun cancelIdle(context: Context) {
        cancel(context, preserveActiveForegroundServiceNotification = true)
    }

    private fun cancel(
        context: Context,
        preserveActiveForegroundServiceNotification: Boolean,
    ) {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        val manager = appContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val currentId = notificationId(config)
        val preserveCurrent = preserveActiveForegroundServiceNotification &&
            foregroundServiceNotificationMayBeActive()

        if (!preserveCurrent) manager?.cancel(currentId)
        cancelPreviousIds(appContext, manager, currentId)
        recordLastId(appContext, currentId)
    }

    private fun cancelPreviousIds(
        context: Context,
        manager: NotificationManager?,
        currentId: Int,
    ) {
        val preferences = prefs(context)
        val lastId = preferences.safeInt(
            KEY_LAST_ID,
            preferences.safeInt(LEGACY_KEY_LAST_ID, currentId),
        )
        if (lastId != currentId) manager?.cancel(lastId)
        if (
            STALE_SEPARATE_MONITORING_NOTIFICATION_ID != currentId &&
            STALE_SEPARATE_MONITORING_NOTIFICATION_ID != lastId
        ) {
            manager?.cancel(STALE_SEPARATE_MONITORING_NOTIFICATION_ID)
        }
    }

    private fun foregroundServiceNotificationMayBeActive(): Boolean =
        LocationConfirmService.isRunning || CallbackForegroundService.isRunning

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun recordLastId(context: Context, id: Int) {
        prefs(context).edit()
            .putInt(KEY_LAST_ID, id)
            .remove(LEGACY_KEY_LAST_ID)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            Constants.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
}
