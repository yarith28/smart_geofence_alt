package com.yarithdev.smart_geofence.dormant

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.activity.ActivityMonitor
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

enum class DormantFarLocationAction {
    NONE,
    ENTERED,
    REFRESHED,
    EXITED,
}

object DormantFarController {
    private const val TAG = "DormantFarController"

    @Synchronized
    fun keepDormantOnStart(
        context: Context,
        config: SmartGeofenceConfig,
    ): Boolean {
        val appContext = context.applicationContext
        val loadedState = DormantFarStateStore.load(appContext) ?: return false
        val state = if (loadedState.generation > 0L) {
            loadedState
        } else {
            loadedState.copy(
                generation = DormantFarStateStore.nextGeneration(appContext),
            ).also { DormantFarStateStore.save(appContext, it) }
        }
        if (!DormantFarPolicy.supportsDormant(config)) {
            clear(appContext, "unsupported_mode")
            return false
        }
        if (DormantFarPolicy.isExpired(state)) {
            clear(appContext, "expired_on_start")
            SmartGeofenceLogger.i(
                appContext,
                TAG,
                "Dormant FAR expired on start; active proximity FLP will restart.",
            )
            return false
        }

        val delayMillis = nextDelayFromState(config, state)
        if (!DormantFarProbeScheduler.schedule(
                appContext,
                delayMillis,
                state.generation,
                "controller_start",
            )
        ) {
            clear(appContext, "probe_schedule_failed_on_start")
            return false
        }
        FusedLocationManager.stopBalancedUpdate(appContext)
        SmartGeofenceLogger.i(
            appContext,
            TAG,
            "Dormant FAR preserved on start edge=${state.edgeDistanceMeters.toInt()}m " +
                "fence=${state.nearestFenceId} delay=${delayMillis}ms reason=${state.reason}.",
        )
        return true
    }

    @Synchronized
    fun onAcceptedLocation(
        context: Context,
        config: SmartGeofenceConfig,
        location: Location,
        source: String,
        nearestFenceId: String?,
        edgeDistanceMeters: Double?,
    ): DormantFarLocationAction {
        val appContext = context.applicationContext
        val existing = DormantFarStateStore.load(appContext)
        if (!DormantFarPolicy.supportsDormant(config)) {
            if (existing != null) clear(appContext, "unsupported_mode_location")
            return DormantFarLocationAction.NONE
        }
        if (existing != null && DormantFarPolicy.isExpired(existing)) {
            exitAndRestart(appContext, "expired_location")
            return DormantFarLocationAction.EXITED
        }

        val likelyStationary = ActivityMonitor.isLikelyStationary(appContext)
        val decision = DormantFarPolicy.entryDecision(config, edgeDistanceMeters, likelyStationary)
        if (decision.shouldEnter) {
            val delayMillis = decision.probeDelayMillis ?: DormantFarPolicy.probeDelayMillis(
                config,
                edgeDistanceMeters ?: 0.0,
            )
            val nowMillis = System.currentTimeMillis()
            val enteredAt = existing?.enteredAtMillis ?: nowMillis
            val state = DormantFarState(
                enteredAtMillis = enteredAt,
                edgeDistanceMeters = edgeDistanceMeters ?: 0.0,
                nearestFenceId = nearestFenceId,
                lastAcceptedFixAtMillis = location.time.takeIf { it > 0L } ?: nowMillis,
                lastAcceptedFixSource = source,
                nextProbeAtMillis = nowMillis + delayMillis,
                reason = decision.reason ?: "far",
                batteryMode = config.batteryMode,
                generation = DormantFarStateStore.nextGeneration(appContext),
            )
            DormantFarStateStore.save(appContext, state)
            if (!DormantFarProbeScheduler.schedule(
                    appContext,
                    delayMillis,
                    state.generation,
                    "source=$source reason=${state.reason} edge=${state.edgeDistanceMeters.toInt()}m",
                )
            ) {
                DormantFarStateStore.recordProbeResult(appContext, "enter_schedule_failed")
                DormantFarStateStore.clear(appContext)
                restartBalancedAfterDormantProbeScheduleFailure(
                    wasAlreadyDormant = existing != null,
                ) {
                    FusedLocationManager.startBalancedUpdate(appContext)
                }
                return DormantFarLocationAction.NONE
            }
            FusedLocationManager.stopBalancedUpdate(appContext)
            val action = if (existing == null) {
                DormantFarLocationAction.ENTERED
            } else {
                DormantFarLocationAction.REFRESHED
            }
            SmartGeofenceLogger.i(
                appContext,
                TAG,
                "Dormant FAR ${action.name.lowercase()} source=$source " +
                    "edge=${state.edgeDistanceMeters.toInt()}m fence=$nearestFenceId " +
                    "threshold=${decision.thresholdMeters.toInt()}m delay=${delayMillis}ms " +
                    "reason=${state.reason}.",
            )
            return action
        }

        if (existing != null) {
            val reason = if (DormantFarPolicy.shouldExitForAcceptedFix(config, edgeDistanceMeters)) {
                "accepted_fix_near_or_ambiguous"
            } else {
                "accepted_fix_not_safely_far"
            }
            exitAndRestart(appContext, "$reason source=$source edge=${edgeDistanceMeters?.toInt()}")
            return DormantFarLocationAction.EXITED
        }
        return DormantFarLocationAction.NONE
    }

    @Synchronized
    fun exitForNativeEvent(
        context: Context,
        eventName: String?,
    ) {
        exitAndRestart(context.applicationContext, "native_event:${eventName ?: "unknown"}")
    }

    @Synchronized
    fun exitForActivityMoving(
        context: Context,
        eventSummary: String,
    ) {
        exitAndRestart(context.applicationContext, "activity_moving:$eventSummary")
    }

    @Synchronized
    fun exitAndRestart(
        context: Context,
        reason: String,
    ): Boolean {
        val appContext = context.applicationContext
        DormantFarStateStore.load(appContext) ?: return false
        clear(appContext, reason)
        SmartGeofenceLogger.i(
            appContext,
            TAG,
            "Dormant FAR exited; restarting active proximity FLP reason=$reason.",
        )
        val config = SmartGeofenceConfigStore.load(appContext)
        if (config.escalationEnabled) {
            FusedLocationManager.startBalancedUpdate(appContext)
        } else {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Dormant FAR restart skipped because escalation is disabled.",
            )
        }
        return true
    }

    @Synchronized
    fun clear(
        context: Context,
        reason: String,
    ) {
        val appContext = context.applicationContext
        val hadState = DormantFarStateStore.load(appContext) != null
        val hadProbe = DormantFarProbeScheduler.pendingIntentExists(appContext)
        DormantFarProbeScheduler.cancel(appContext)
        DormantFarStateStore.clear(appContext)
        if (hadState || hadProbe) {
            DormantFarStateStore.recordProbeResult(appContext, "cleared:$reason")
        }
    }

    @Synchronized
    internal fun runProbeCallbackIfCurrent(
        context: Context,
        expectedGeneration: Long,
        block: () -> Unit,
    ): Boolean = runProbeAlarmIfCurrent(context, expectedGeneration) { block() }

    @Synchronized
    internal fun runProbeAlarmIfCurrent(
        context: Context,
        expectedGeneration: Long?,
        block: (DormantFarState) -> Unit,
    ): Boolean {
        val current = DormantFarStateStore.load(context.applicationContext) ?: return false
        if (expectedGeneration == null) {
            if (current.generation != 0L) return false
        } else if (current.generation != expectedGeneration) {
            return false
        }
        block(current)
        return true
    }

    private fun nextDelayFromState(
        config: SmartGeofenceConfig,
        state: DormantFarState,
    ): Long {
        val nowMillis = System.currentTimeMillis()
        val storedDelay = state.nextProbeAtMillis - nowMillis
        if (storedDelay > 0L) return storedDelay
        return DormantFarPolicy.probeDelayMillis(config, state.edgeDistanceMeters)
    }
}

internal fun restartBalancedAfterDormantProbeScheduleFailure(
    wasAlreadyDormant: Boolean,
    restartBalanced: () -> Unit,
) {
    if (wasAlreadyDormant) restartBalanced()
}
