package com.yarithdev.smart_geofence.confirm

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionController
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.foreground.CallbackForegroundService
import com.yarithdev.smart_geofence.foreground.ForegroundNotificationFactory
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator

internal fun shouldKeepLocationConfirmServiceAfterDrain(
    foregroundWorkRunning: Boolean,
    stickyEnabled: Boolean,
    hasFutureWorkOrPulse: Boolean,
    exactLaunchBridgeAvailable: Boolean,
    futureWakeArmed: Boolean,
): Boolean {
    if (foregroundWorkRunning) return true
    return stickyEnabled &&
        hasFutureWorkOrPulse &&
        (!exactLaunchBridgeAvailable || !futureWakeArmed)
}

class LocationConfirmService : Service() {
    private var activeLaunchToken: Long = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isForegroundReady = false
        SmartGeofenceLogger.d(applicationContext, TAG, "Location confirm service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val attempt = intent?.getIntExtra(LocationConfirmManager.EXTRA_START_ATTEMPT, 0) ?: 0
        val launchToken = intent
            ?.getLongExtra(LocationConfirmManager.EXTRA_LAUNCH_TOKEN, 0L)
            ?.takeIf { it > 0L }
            ?: LocationConfirmManager.ensureLaunchToken(applicationContext, "service start")
        val config = SmartGeofenceConfigStore.load(applicationContext)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            SmartGeofenceLogger.d(
                applicationContext,
                TAG,
                "Starting location confirm foreground service token=$launchToken " +
                    "attempt=$attempt type=$type."
            )
            ServiceCompat.startForeground(
                this,
                ForegroundNotificationFactory.notificationId(config),
                ForegroundNotificationFactory.build(this, config),
                type
            )
            if (!LocationConfirmManager.onForegroundReady(applicationContext, attempt, launchToken)) {
                SmartGeofenceLogger.w(
                    applicationContext,
                    TAG,
                    "Stopping location confirm service started with stale token=$launchToken."
                )
                val validActiveService = hasCurrentActiveLaunch()
                if (!validActiveService) {
                    isForegroundReady = false
                    stopSelf()
                }
                return if (validActiveService && config.foregroundServiceSticky) {
                    START_STICKY
                } else {
                    START_NOT_STICKY
                }
            }
            activeLaunchToken = launchToken
            isForegroundReady = true
            SmartGeofenceLogger.d(
                applicationContext,
                TAG,
                "Location confirm foreground service started token=$launchToken attempt=$attempt " +
                    "and ready to host foreground wake work."
            )
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(
                applicationContext,
                TAG,
                "Location confirm startForeground failed; stopping service: " +
                    "${e.javaClass.simpleName}: ${e.message}",
                e
            )
            val currentFailure = LocationConfirmManager.onForegroundStartFailed(
                applicationContext,
                attempt,
                launchToken,
                "startForeground ${e.javaClass.simpleName}"
            )
            if (currentFailure) {
                ForegroundServiceRearm.arm(
                    applicationContext,
                    attempt,
                    launchToken,
                    "startForeground ${e.javaClass.simpleName}"
                )
            }
            val validActiveService = hasCurrentActiveLaunch()
            if (!validActiveService) {
                isForegroundReady = false
                stopSelf()
            }
            return if (validActiveService && config.foregroundServiceSticky) {
                START_STICKY
            } else {
                START_NOT_STICKY
            }
        }

        WakeEventCoordinator.drainForegroundWork(applicationContext) {
            val currentConfig = SmartGeofenceConfigStore.load(applicationContext)
            val pendingWork = WakeEventCoordinator.foregroundWorkCount(applicationContext) > 0
            val deferredWorkWakeArmed = if (pendingWork) {
                runCatching {
                    LocationConfirmManager.scheduleNextReadyWork(
                        applicationContext,
                        "location confirm service idle",
                    )
                }.getOrElse { error ->
                    SmartGeofenceLogger.w(
                        applicationContext,
                        TAG,
                        "Could not arm deferred confirm wake while service became idle.",
                        error,
                    )
                    false
                }
            } else {
                true
            }
            val pulseWasActive =
                ProximityPulseController.isSchedulingActive(applicationContext)
            val pulseWakeArmed = if (pulseWasActive) {
                runCatching {
                    ProximityPulseController.reconcileScheduling(
                        applicationContext,
                        "location_confirm_service_idle",
                    )
                }.getOrElse { error ->
                    SmartGeofenceLogger.w(
                        applicationContext,
                        TAG,
                        "Could not reconcile Pulse wake while service became idle.",
                        error,
                    )
                    false
                }
            } else {
                true
            }
            val pulseActive =
                ProximityPulseController.isSchedulingActive(applicationContext)
            val futureWakeArmed = deferredWorkWakeArmed && pulseWakeArmed
            val exactLaunchBridgeAvailable = runCatching {
                ExactAlarmPermissionController.canScheduleExactAlarms(applicationContext)
            }.getOrElse { error ->
                SmartGeofenceLogger.w(
                    applicationContext,
                    TAG,
                    "Could not check exact-alarm launch bridge while service became idle.",
                    error,
                )
                false
            }
            val foregroundWorkRestarted = WakeEventCoordinator.isForegroundWorkRunning()
            val keepService = shouldKeepLocationConfirmServiceAfterDrain(
                foregroundWorkRunning = foregroundWorkRestarted,
                stickyEnabled = currentConfig.foregroundServiceSticky,
                hasFutureWorkOrPulse = pendingWork || pulseActive,
                exactLaunchBridgeAvailable = exactLaunchBridgeAvailable,
                futureWakeArmed = futureWakeArmed,
            )
            if (keepService) {
                SmartGeofenceLogger.d(
                    applicationContext,
                    TAG,
                    "Foreground wake drain retained service " +
                        "workRestarted=$foregroundWorkRestarted " +
                        "pendingWork=$pendingWork pulseActive=$pulseActive " +
                        "exactBridge=$exactLaunchBridgeAvailable " +
                        "deferredWakeArmed=$deferredWorkWakeArmed " +
                        "pulseWakeArmed=$pulseWakeArmed."
                )
            } else {
                SmartGeofenceLogger.d(
                    applicationContext,
                    TAG,
                    "Foreground wake work drained; requesting service stop " +
                        "startId=$startId " +
                        "workRestarted=$foregroundWorkRestarted " +
                        "pendingWork=$pendingWork pulseActive=$pulseActive " +
                        "exactBridge=$exactLaunchBridgeAvailable " +
                        "deferredWakeArmed=$deferredWorkWakeArmed " +
                        "pulseWakeArmed=$pulseWakeArmed."
                )
                if (!stopSelfResult(startId)) {
                    SmartGeofenceLogger.d(
                        applicationContext,
                        TAG,
                        "Idle service stop ignored because a newer start exists startId=$startId.",
                    )
                }
            }
        }
        return if (config.foregroundServiceSticky) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        val config = SmartGeofenceConfigStore.load(applicationContext)
        stopForegroundCompat(
            removeNotification = ForegroundNotificationFactory.shouldRemoveWhenServiceStops(
                config,
                otherForegroundServiceRunning = CallbackForegroundService.isRunning,
            )
        )
        isForegroundReady = false
        isRunning = false
        LocationConfirmManager.onServiceStopped(applicationContext, activeLaunchToken)
        SmartGeofenceLogger.d(applicationContext, TAG, "Location confirm service destroyed.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasCurrentActiveLaunch(): Boolean =
        isForegroundReady &&
            activeLaunchToken > 0L &&
            LocationConfirmManager.isCurrentLaunchToken(
                applicationContext,
                activeLaunchToken,
            )

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
        private const val TAG = "LocationConfirmService"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isForegroundReady: Boolean = false
            private set
    }
}
