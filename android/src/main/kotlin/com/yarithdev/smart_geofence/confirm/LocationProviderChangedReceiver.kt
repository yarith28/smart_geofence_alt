package com.yarithdev.smart_geofence.confirm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.monitoring.LocationAvailabilityStopController

class LocationProviderChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocationManager.PROVIDERS_CHANGED_ACTION &&
            intent?.action != LocationManager.MODE_CHANGED_ACTION
        ) {
            return
        }
        val appContext = context.applicationContext
        val enabled = LocationServicesState.isLocationEnabled(appContext)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Location providers changed enabled=$enabled.",
        )
        if (enabled == false) {
            LocationAvailabilityStopController.stopIfUnavailable(
                appContext,
                "location_provider_changed",
            )
        } else if (enabled == true) {
            LocationConfirmManager.onLocationProvidersEnabled(appContext)
        }
    }

    companion object {
        private const val TAG = "LocationProviderChangedReceiver"
    }
}
