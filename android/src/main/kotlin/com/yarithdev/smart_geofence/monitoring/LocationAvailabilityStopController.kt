package com.yarithdev.smart_geofence.monitoring

import android.content.Context
import com.yarithdev.smart_geofence.config.LocationUnavailablePolicy
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.confirm.LocationServicesState
import com.yarithdev.smart_geofence.fused.FusedLocationPermissions
import com.yarithdev.smart_geofence.registration.SmartGeofenceRegistrationTransactions
import com.yarithdev.smart_geofence.store.FenceStore

internal fun locationUnavailableStopReason(
    policy: LocationUnavailablePolicy,
    hasRegisteredFences: Boolean,
    fineLocationPermissionGranted: Boolean,
    backgroundLocationPermissionGranted: Boolean,
    locationServicesEnabled: Boolean?,
): MonitoringStopReason? {
    if (policy != LocationUnavailablePolicy.Stop || !hasRegisteredFences) return null
    return when {
        !fineLocationPermissionGranted ->
            MonitoringStopReason.FINE_LOCATION_PERMISSION_DENIED
        !backgroundLocationPermissionGranted ->
            MonitoringStopReason.BACKGROUND_LOCATION_PERMISSION_DENIED
        locationServicesEnabled == false ->
            MonitoringStopReason.LOCATION_SERVICES_DISABLED
        else -> null
    }
}

object LocationAvailabilityStopController {
    fun stopIfUnavailable(context: Context, source: String): Boolean {
        val appContext = context.applicationContext
        if (MonitoringStopStateStore.snapshot(appContext).terminallyStopped) {
            TerminalMonitoringStopController.enforce(appContext, source)
            return true
        }
        val config = SmartGeofenceConfigStore.load(appContext)
        val hasFences =
            FenceStore.getAll(appContext, includePending = true).isNotEmpty() ||
                SmartGeofenceRegistrationTransactions.coordinator
                    .activeCleanupFenceIds()
                    .isNotEmpty()
        val reason = locationUnavailableStopReason(
            policy = config.locationUnavailablePolicy,
            hasRegisteredFences = hasFences,
            fineLocationPermissionGranted =
                FusedLocationPermissions.hasFineLocationPermission(appContext),
            backgroundLocationPermissionGranted =
                FusedLocationPermissions.hasBackgroundLocationPermission(appContext),
            locationServicesEnabled = LocationServicesState.isLocationEnabled(appContext),
        ) ?: return false
        TerminalMonitoringStopController.trigger(appContext, reason, source)
        return true
    }
}
