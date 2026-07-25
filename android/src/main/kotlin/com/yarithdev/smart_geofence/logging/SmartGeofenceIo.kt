package com.yarithdev.smart_geofence.logging

import android.util.Log
import java.util.concurrent.Executors

internal object SmartGeofenceIo {
    private const val TAG = "SmartGeofenceIo"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smart-geofence-io")
    }

    fun execute(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (e: Throwable) {
                Log.e(TAG, "Unhandled smart_geofence IO task failure.", e)
            }
        }
    }
}
