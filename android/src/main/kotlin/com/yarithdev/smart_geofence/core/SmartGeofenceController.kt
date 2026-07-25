package com.yarithdev.smart_geofence.core

import android.content.Context
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionController
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.confirm.LocationConfirmManager
import com.yarithdev.smart_geofence.confirm.SmartGeofenceEventProcessor
import com.yarithdev.smart_geofence.dormant.DormantFarController
import com.yarithdev.smart_geofence.proximitypulse.PulseStopReason
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.fused.FusedLocationPermissions
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.monitoring.LocationAvailabilityStopController
import com.yarithdev.smart_geofence.monitoring.MonitoringStopStateStore
import com.yarithdev.smart_geofence.monitoring.TerminalMonitoringStopController
import com.yarithdev.smart_geofence.activity.ActivityMonitor
import com.yarithdev.smart_geofence.activity.shouldMonitorActivity
import com.yarithdev.smart_geofence.foreground.IdleMonitoringNotification
import com.yarithdev.smart_geofence.recovery.RecoveryScheduler
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator

object SmartGeofenceController {
    private const val TAG = "SmartGeofenceController"

    fun start(
        context: Context,
        config: SmartGeofenceConfig,
        scheduleRecovery: Boolean = true,
    ) {
        val appContext = context.applicationContext
        if (MonitoringStopStateStore.snapshot(appContext).terminallyStopped) {
            TerminalMonitoringStopController.enforce(appContext, "controller_start")
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Smart layer start blocked by a terminal monitoring stop.",
            )
            return
        }
        if (LocationAvailabilityStopController.stopIfUnavailable(
                appContext,
                "controller_start",
            )
        ) {
            return
        }
        SmartGeofenceEventProcessor.recoverPendingEventOutbox(appContext)

        if (ExactAlarmPermissionController.isStrictBlocked(appContext, config)) {
            val status = ExactAlarmPermissionController.status(appContext).configValue
            stop(appContext)
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "exact_alarm_permission_denied where=controller_start " +
                    "mode=${config.exactAlarmPermissionMode.configValue} status=$status",
            )
            return
        }

        SmartGeofenceConfigStore.save(appContext, config)

        val hasFences = FenceStore.getAll(appContext).isNotEmpty()
        if (!hasFences) {
            SmartGeofenceLogger.d(appContext, TAG, "No fences registered; stopping smart layers.")
            stop(appContext)
            return
        }

        if (FusedLocationPermissions.hasLocationPermission(appContext) &&
            !FusedLocationPermissions.hasFineLocationPermission(appContext)
        ) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Only COARSE location is granted; the smart layer needs FINE " +
                    "(ACCESS_FINE_LOCATION). Coarse fixes are rejected by the accuracy " +
                    "filter, so no smart events will be delivered.",
            )
        }

        if (config.escalationEnabled) {
            if (config.passiveLocationEnabled) {
                FusedLocationManager.startPassiveUpdates(appContext)
            } else {
                FusedLocationManager.stopPassiveUpdates(appContext)
            }
            val pulseCanRun = config.proximityPulseEnabled &&
                ProximityPulseController.canRun(appContext)
            if (shouldMonitorActivity(config, hasFences, pulseCanRun)) {
                ActivityMonitor.start(appContext, "controller_start")
            } else {
                ActivityMonitor.stop(appContext, "controller_policy_ineligible")
            }
            if (!DormantFarController.keepDormantOnStart(appContext, config)) {
                FusedLocationManager.startBalancedUpdate(appContext)
            }
            if (pulseCanRun) {
                ProximityPulseController.reconcileScheduling(appContext)
            } else {
                if (config.proximityPulseEnabled) {
                    ProximityPulseController.stopScheduling(
                        appContext,
                        PulseStopReason.INELIGIBLE,
                    )
                } else {
                    ProximityPulseController.disable(appContext)
                }
            }
            LocationConfirmManager.reconcileQueuedWork(appContext, "controller_refresh")
        } else {
            ActivityMonitor.stop(appContext, "escalation_disabled")
            DormantFarController.clear(appContext, "escalation_disabled")
            LocationConfirmManager.stop(appContext)
            FusedLocationManager.stopBackgroundUpdates(appContext)
            ProximityPulseController.disable(appContext)
        }
        if (scheduleRecovery) {
            RecoveryScheduler.schedule(appContext, config)
        }
        IdleMonitoringNotification.show(appContext, config)
        SmartGeofenceLogger.d(appContext, TAG, "Smart layers started ($config).")
    }

    fun refresh(context: Context, scheduleRecovery: Boolean = true) {
        start(
            context,
            SmartGeofenceConfigStore.load(context.applicationContext),
            scheduleRecovery,
        )
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        ActivityMonitor.stop(appContext, "controller_stop")
        DormantFarController.clear(appContext, "controller_stop")
        FusedLocationManager.stopBackgroundUpdates(appContext)
        LocationConfirmManager.stop(appContext)
        NativeTransitionCoordinator.clear(appContext, "controller_stop")
        ProximityPulseController.disable(appContext, PulseStopReason.CONTROLLER_STOP)
        RecoveryScheduler.cancel(appContext)
        IdleMonitoringNotification.cancel(appContext)
        SmartGeofenceLogger.d(appContext, TAG, "Smart layers stopped.")
    }
}
