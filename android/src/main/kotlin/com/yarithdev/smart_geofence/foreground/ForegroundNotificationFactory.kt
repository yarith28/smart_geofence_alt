package com.yarithdev.smart_geofence.foreground

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yarithdev.smart_geofence.config.ForegroundNotificationTapAction
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

object ForegroundNotificationFactory {
    private const val TAG = "ForegroundNotificationFactory"

    fun build(context: Context, config: SmartGeofenceConfig): Notification {
        val channelId = config.foregroundNotificationChannelId.ifBlank {
            Constants.DEFAULT_FOREGROUND_NOTIFICATION_CHANNEL_ID
        }
        val channelName = config.foregroundNotificationChannelName.ifBlank {
            Constants.DEFAULT_FOREGROUND_NOTIFICATION_CHANNEL_NAME
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm == null) {
                SmartGeofenceLogger.w(
                    TAG,
                    "NotificationManager unavailable; foreground channel was not created.",
                )
            } else if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        channelName,
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(
                config.foregroundNotificationTitle.ifBlank {
                    Constants.DEFAULT_FOREGROUND_NOTIFICATION_TITLE
                }
            )
            .setSmallIcon(
                resolveSmallIcon(context, config.foregroundNotificationSmallIconResourceName)
            )
            .setOngoing(config.foregroundNotificationSticky)
            .setAutoCancel(config.foregroundNotificationTapAction == ForegroundNotificationTapAction.Dismiss)

        contentIntent(context, config)?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    fun notificationId(config: SmartGeofenceConfig): Int =
        config.foregroundNotificationId.takeIf { it > 0 }
            ?: Constants.DEFAULT_FOREGROUND_NOTIFICATION_ID

    fun shouldRemoveWhenServiceStops(
        config: SmartGeofenceConfig,
        otherForegroundServiceRunning: Boolean,
    ): Boolean = config.shouldRemoveForegroundNotificationWhenIdle(otherForegroundServiceRunning)

    private fun resolveSmallIcon(context: Context, resourceName: String?): Int {
        val name = resourceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: return android.R.drawable.ic_menu_mylocation
        val resources = context.resources
        val packageName = context.packageName
        val id = when {
            ":" in name -> resources.getIdentifier(name, null, null)
            "/" in name -> {
                val parts = name.split("/", limit = 2)
                resources.getIdentifier(parts[1], parts[0], packageName)
            }
            else -> resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
                ?: resources.getIdentifier(name, "mipmap", packageName)
        }
        return id.takeIf { it != 0 } ?: android.R.drawable.ic_menu_mylocation
    }

    private fun contentIntent(
        context: Context,
        config: SmartGeofenceConfig,
    ): PendingIntent? {
        val appContext = context.applicationContext
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return when (config.foregroundNotificationTapAction) {
            ForegroundNotificationTapAction.OpenApp -> {
                val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                    ?: return null
                PendingIntent.getActivity(appContext, 0, intent, flags)
            }
            ForegroundNotificationTapAction.Dismiss -> {
                val intent = Intent(appContext, ForegroundNotificationTapReceiver::class.java)
                    .setAction(Constants.ACTION_FOREGROUND_NOTIFICATION_DISMISS)
                PendingIntent.getBroadcast(
                    appContext,
                    Constants.PENDING_INTENT_REQUEST_NOTIFICATION_DISMISS,
                    intent,
                    flags
                )
            }
            ForegroundNotificationTapAction.None -> null
        }
    }
}
