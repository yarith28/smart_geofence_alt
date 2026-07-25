package com.yarithdev.smart_geofence.foreground

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.yarithdev.smart_geofence.confirm.LocationConfirmService
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import java.util.concurrent.TimeoutException

class CallbackForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val autoDemoteRunnable = Runnable {
        SmartGeofenceLogger.d(
            applicationContext,
            TAG,
            "Callback foreground service auto-demoting after timeout."
        )
        stopCallbackForeground(reason = "auto timeout")
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val config = SmartGeofenceConfigStore.load(applicationContext)
            acquireWakeLock(config)
            postForegroundNotification(config)
            isRunning = true
            startRequested = false
            finishPromotionRequests(null)
            scheduleAutoDemote(config)
            SmartGeofenceLogger.d(
                applicationContext,
                TAG,
                "Callback foreground service started."
            )
        } catch (e: Throwable) {
            SmartGeofenceLogger.e(
                applicationContext,
                TAG,
                "Callback startForeground failed; stopping service: " +
                    "${e.javaClass.simpleName}: ${e.message}",
                e
            )
            handler.removeCallbacks(autoDemoteRunnable)
            releaseWakeLock()
            startRequested = false
            finishPromotionRequests(e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN) {
            stopCallbackForeground(reason = "explicit demote")
            return START_NOT_STICKY
        }
        if (isRunning) {
            val config = SmartGeofenceConfigStore.load(applicationContext)
            postForegroundNotification(config)
            acquireWakeLock(config)
            scheduleAutoDemote(config)
            SmartGeofenceLogger.d(
                applicationContext,
                TAG,
                "Callback foreground service timeout refreshed."
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isRunning) {
            val config = SmartGeofenceConfigStore.load(applicationContext)
            stopForegroundCompat(
                removeNotification = ForegroundNotificationFactory.shouldRemoveWhenServiceStops(
                    config,
                    otherForegroundServiceRunning = LocationConfirmService.isRunning,
                )
            )
        }
        isRunning = false
        startRequested = false
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        SmartGeofenceLogger.w(
            applicationContext,
            TAG,
            "Callback foreground service reached the Android shortService timeout."
        )
        stopCallbackForeground(reason = "system shortService timeout")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleAutoDemote(config: SmartGeofenceConfig) {
        handler.removeCallbacks(autoDemoteRunnable)
        handler.postDelayed(autoDemoteRunnable, callbackTimeoutMillis(config))
    }

    private fun postForegroundNotification(config: SmartGeofenceConfig) {
        ServiceCompat.startForeground(
            this,
            ForegroundNotificationFactory.notificationId(config),
            ForegroundNotificationFactory.build(this, config),
            foregroundServiceType()
        )
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }

    private fun stopCallbackForeground(reason: String) {
        handler.removeCallbacks(autoDemoteRunnable)
        releaseWakeLock()
        if (isRunning) {
            val config = SmartGeofenceConfigStore.load(applicationContext)
            stopForegroundCompat(
                removeNotification = ForegroundNotificationFactory.shouldRemoveWhenServiceStops(
                    config,
                    otherForegroundServiceRunning = LocationConfirmService.isRunning,
                )
            )
        }
        isRunning = false
        startRequested = false
        stopSelf()
        SmartGeofenceLogger.d(
            applicationContext,
            TAG,
            "Callback foreground service stopped ($reason)."
        )
    }

    private fun acquireWakeLock(config: SmartGeofenceConfig) {
        releaseWakeLock()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            SmartGeofenceLogger.w(
                applicationContext,
                TAG,
                "PowerManager unavailable; callback work will continue without a wake lock."
            )
            return
        }
        try {
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire(callbackTimeoutMillis(config))
                }
        } catch (e: RuntimeException) {
            wakeLock = null
            SmartGeofenceLogger.w(
                applicationContext,
                TAG,
                "Could not acquire callback wake lock; callback work will continue: ${e.message}",
                e
            )
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        ServiceCompat.stopForeground(
            this,
            if (removeNotification) {
                ServiceCompat.STOP_FOREGROUND_REMOVE
            } else {
                ServiceCompat.STOP_FOREGROUND_DETACH
            },
        )
    }

    companion object {
        private const val TAG = "CallbackForegroundService"
        private const val ACTION_SHUTDOWN =
            "com.yarithdev.smart_geofence.action.CALLBACK_FOREGROUND_SHUTDOWN"
        private const val WAKE_LOCK_TAG = "smart_geofence:callback_foreground"
        private const val PROMOTION_READY_TIMEOUT_MILLIS = 10_000L
        private val promotionHandler = Handler(Looper.getMainLooper())
        private val promotionLock = Any()
        private val promotionCallbacks = mutableListOf<(Throwable?) -> Unit>()
        private val promotionTimeout = Runnable {
            startRequested = false
            finishPromotionRequests(
                TimeoutException(
                    "Callback foreground service did not report readiness within " +
                        "${PROMOTION_READY_TIMEOUT_MILLIS}ms.",
                ),
            )
        }

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var startRequested: Boolean = false

        private fun callbackTimeoutMillis(config: SmartGeofenceConfig): Long =
            config.foregroundServiceCallbackTimeoutMillis.coerceIn(
                Constants.MIN_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS,
                Constants.MAX_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS
            )

        fun promote(context: Context, onReady: (Throwable?) -> Unit) {
            synchronized(promotionLock) {
                val firstWaiter = promotionCallbacks.isEmpty()
                promotionCallbacks += onReady
                if (firstWaiter) {
                    promotionHandler.postDelayed(
                        promotionTimeout,
                        PROMOTION_READY_TIMEOUT_MILLIS,
                    )
                }
            }
            if (isRunning) {
                finishPromotionRequests(null)
                return
            }
            if (startRequested) return
            startRequested = true
            try {
                startForegroundServiceCompat(
                    context,
                    Intent(context, CallbackForegroundService::class.java)
                )
            } catch (e: Exception) {
                startRequested = false
                finishPromotionRequests(e)
            }
        }

        private fun finishPromotionRequests(error: Throwable?) {
            val callbacks = synchronized(promotionLock) {
                if (promotionCallbacks.isEmpty()) return
                promotionHandler.removeCallbacks(promotionTimeout)
                promotionCallbacks.toList().also { promotionCallbacks.clear() }
            }
            callbacks.forEach { callback -> callback(error) }
        }

        fun demote(context: Context) {
            finishPromotionRequests(
                IllegalStateException(
                    "Callback foreground promotion was cancelled by demotion.",
                ),
            )
            if (!isRunning && !startRequested) {
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Callback foreground service stop requested while not running or starting."
                )
                return
            }
            context.stopService(Intent(context, CallbackForegroundService::class.java))
            startRequested = false
        }

        private fun startForegroundServiceCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                SmartGeofenceLogger.e(
                    context,
                    TAG,
                    "Failed to start callback foreground service: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                    e
                )
                throw e
            }
        }
    }
}
