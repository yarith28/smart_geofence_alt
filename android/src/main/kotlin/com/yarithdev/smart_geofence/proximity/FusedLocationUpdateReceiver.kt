package com.yarithdev.smart_geofence.proximity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.LocationResult
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

internal fun runFusedLocationCallbackIfEligible(
    ineligibilityReason: () -> String?,
    onIgnored: (String) -> Unit,
    block: () -> Unit,
): Boolean {
    val reason = ineligibilityReason()
    if (reason != null) {
        onIgnored(reason)
        return false
    }
    block()
    return true
}

class FusedLocationUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val source = intent.getStringExtra(Constants.EXTRA_LOCATION_WAKE_SOURCE)
            ?: Constants.LOCATION_WAKE_SOURCE_PROXIMITY
        runFusedLocationCallbackIfEligible(
            ineligibilityReason = {
                FusedLocationManager.callbackIneligibilityReason(appContext, source)
            },
            onIgnored = { reason ->
                FusedLocationManager.recordIgnoredCallback(appContext, source, reason)
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Fused location callback ignored source=$source reason=$reason.",
                )
            },
        ) {
            val location = LocationResult.extractResult(intent)?.lastLocation ?: return@runFusedLocationCallbackIfEligible

            val pending = goAsync()
            val tailTracker = FusedBroadcastTailTracker(
                finish = { pending.finish() },
            )
            try {
                ProximityLocationEvaluator.evaluate(appContext, location, source, tailTracker)
            } catch (e: Throwable) {
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "Proximity evaluation failed: ${e.message}",
                    e,
                )
            } finally {
                tailTracker.markEvaluationComplete()
            }
        }
    }

    companion object {
        private const val TAG = "FusedLocationUpdateReceiver"
    }
}
