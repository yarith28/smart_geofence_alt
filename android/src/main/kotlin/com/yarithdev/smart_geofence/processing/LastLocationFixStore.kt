package com.yarithdev.smart_geofence.processing

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLongOrNull

internal object LastLocationFixStore {
    private const val KEY_LATITUDE = "fused_location_confirm_last_lat"
    private const val KEY_LONGITUDE = "fused_location_confirm_last_lng"
    private const val KEY_TIME = "fused_location_confirm_last_time"

    fun load(context: Context): Location? {
        val preferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val latitudeBits = preferences.safeLongOrNull(KEY_LATITUDE) ?: return null
        val longitudeBits = preferences.safeLongOrNull(KEY_LONGITUDE) ?: return null
        val fixTime = preferences.safeLongOrNull(KEY_TIME) ?: return null
        return Location("smart_geofence_confirm").apply {
            latitude = Double.fromBits(latitudeBits)
            longitude = Double.fromBits(longitudeBits)
            time = fixTime
        }
    }

    fun save(context: Context, location: Location) {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LATITUDE, location.latitude.toRawBits())
            .putLong(KEY_LONGITUDE, location.longitude.toRawBits())
            .putLong(KEY_TIME, location.time)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LATITUDE)
            .remove(KEY_LONGITUDE)
            .remove(KEY_TIME)
            .apply()
    }
}
