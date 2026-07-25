package com.yarithdev.smart_geofence.confirm

import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat

object LocationServicesState {
    fun isLocationEnabled(context: Context): Boolean? {
        try {
            val locationManager =
                context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return null
            return LocationManagerCompat.isLocationEnabled(locationManager)
        } catch (_: Throwable) {
            return null
        }
    }
}
