package com.yarithdev.smart_geofence.dormant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yarithdev.smart_geofence.confirm.LocationQualityPolicy
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus
import com.yarithdev.smart_geofence.fused.FusedCurrentLocationResult
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.proximity.ProximityLocationEvaluator

class DormantFarProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.action != Constants.ACTION_DORMANT_FAR_PROBE) return
        val expectedGeneration = if (
            intent.hasExtra(DormantFarProbeScheduler.EXTRA_STATE_GENERATION)
        ) {
            intent.getLongExtra(DormantFarProbeScheduler.EXTRA_STATE_GENERATION, 0L)
        } else {
            null
        }
        val claimed = DormantFarController.runProbeAlarmIfCurrent(
            appContext,
            expectedGeneration,
        ) { state ->
            DormantFarProbeScheduler.cancel(appContext)
            startCurrentProbe(appContext, state)
        }
        if (!claimed) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Ignored stale Dormant FAR probe alarm generation=$expectedGeneration.",
            )
            return
        }
    }

    private fun startCurrentProbe(
        appContext: Context,
        state: DormantFarState,
    ) {
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Dormant FAR probe alarm fired generation=${state.generation}.",
        )
        val config = SmartGeofenceConfigStore.load(appContext)
        if (!DormantFarPolicy.supportsDormant(config)) {
            DormantFarStateStore.recordProbeResult(appContext, "unsupported_mode")
            if (config.escalationEnabled) {
                DormantFarController.exitAndRestart(appContext, "probe_unsupported_mode")
            } else {
                DormantFarController.clear(appContext, "probe_unsupported_mode")
            }
            return
        }
        if (DormantFarPolicy.isExpired(state)) {
            DormantFarStateStore.recordProbeResult(appContext, "expired")
            DormantFarController.exitAndRestart(appContext, "probe_expired")
            return
        }

        val pending = goAsync()
        try {
            FusedLocationManager.requestLastLocation(
                appContext,
                config.locationConfirmTimeoutMillis,
            ) { result ->
                try {
                    val handled = DormantFarController.runProbeCallbackIfCurrent(
                        appContext,
                        state.generation,
                    ) {
                        handleCurrentProbeResult(appContext, result)
                    }
                    if (!handled) {
                        SmartGeofenceLogger.d(
                            appContext,
                            TAG,
                            "Ignored stale Dormant FAR probe callback generation=${state.generation}.",
                        )
                    }
                } catch (e: Throwable) {
                    val handled = DormantFarController.runProbeCallbackIfCurrent(
                        appContext,
                        state.generation,
                    ) {
                        recordProbeFailure(appContext, e)
                    }
                    if (!handled) {
                        SmartGeofenceLogger.d(
                            appContext,
                            TAG,
                            "Ignored stale Dormant FAR probe failure generation=${state.generation}.",
                        )
                    }
                } finally {
                    pending.finish()
                }
            }
        } catch (e: Throwable) {
            try {
                DormantFarController.runProbeCallbackIfCurrent(
                    appContext,
                    state.generation,
                ) {
                    recordProbeFailure(appContext, e)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleCurrentProbeResult(
        context: Context,
        result: FusedCurrentLocationResult,
    ) {
        if (result.status != FusedCurrentLocationStatus.SUCCESS || result.location == null) {
            val status = result.status.name.lowercase()
            DormantFarStateStore.recordProbeResult(context, "no_cached_location:$status")
            SmartGeofenceLogger.i(
                context,
                TAG,
                "Dormant FAR probe has no usable cached location status=$status; " +
                    "restarting active proximity FLP.",
            )
            DormantFarController.exitAndRestart(context, "probe_$status")
            return
        }
        val config = SmartGeofenceConfigStore.load(context)
        val rejection = LocationQualityPolicy.rejectionReason(
            result.location,
            config.activityFusedLocationStaleAfterMillis,
            config.pulseLocationMaxAccuracyMeters,
        )
        if (rejection != null) {
            DormantFarStateStore.recordProbeResult(context, "cached_rejected:$rejection")
            SmartGeofenceLogger.i(
                context,
                TAG,
                "Dormant FAR probe cached fix rejected reason=$rejection; " +
                    "restarting active proximity FLP.",
            )
            DormantFarController.exitAndRestart(
                context,
                "probe_cached_rejected:$rejection",
            )
            return
        }

        DormantFarStateStore.recordProbeResult(context, "cached_location_evaluating")
        ProximityLocationEvaluator.evaluate(
            context,
            result.location,
            Constants.LOCATION_WAKE_SOURCE_DORMANT_PROBE,
        )
    }

    private fun recordProbeFailure(context: Context, error: Throwable) {
        val reason = "probe_exception:${error.javaClass.simpleName}"
        runCatching { DormantFarStateStore.recordProbeResult(context, reason) }
        SmartGeofenceLogger.w(
            context,
            TAG,
            "Dormant FAR probe failed; restarting active proximity FLP: ${error.message}",
            error,
        )
        runCatching { DormantFarController.exitAndRestart(context, reason) }
            .onFailure { restartError ->
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Dormant FAR probe recovery restart failed: ${restartError.message}",
                    restartError,
                )
            }
    }

    companion object {
        private const val TAG = "DormantFarProbeReceiver"
    }
}
