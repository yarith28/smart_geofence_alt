package com.yarithdev.smart_geofence.proximity

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.confirm.FusedLocationConfirm
import com.yarithdev.smart_geofence.confirm.LocationConfirmManager
import com.yarithdev.smart_geofence.confirm.LocationQualityPolicy
import com.yarithdev.smart_geofence.confirm.SmartGeofenceEventProcessor
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.dormant.DormantFarController
import com.yarithdev.smart_geofence.dormant.DormantFarLocationAction
import com.yarithdev.smart_geofence.dormant.DormantFarStateStore
import com.yarithdev.smart_geofence.fused.FusedLocationLiveness
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.mock.MockLocationPolicyGate
import com.yarithdev.smart_geofence.processing.LocationEventInput
import com.yarithdev.smart_geofence.processing.LocationEventMode
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.store.FenceObservationStore
import com.yarithdev.smart_geofence.store.SmartGeofenceFence
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator

object ProximityLocationEvaluator {
    private const val TAG = "ProximityLocationEvaluator"

    fun evaluate(
        context: Context,
        location: Location,
        source: String,
        tailTracker: FusedBroadcastTailTracker? = null,
    ) {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        if (source == Constants.LOCATION_WAKE_SOURCE_PASSIVE) {
            val passiveRejection = LocationQualityPolicy.rejectionReason(
                location,
                Constants.PASSIVE_LOCATION_MAX_EVIDENCE_AGE_MILLIS,
                config.pulseLocationMaxAccuracyMeters,
            )
            if (passiveRejection != null) {
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Passive location rejected before downstream mutation reason=$passiveRejection.",
                )
                return
            }
        }
        val fences = FenceStore.getAll(appContext)
        if (fences.isEmpty()) {
            SmartGeofenceDiagnostics.recordLocationWake(
                appContext,
                source,
                location,
                nearestFenceId = null,
                edgeDistanceMeters = null,
                withinProximity = false,
            )
            DormantFarController.clear(appContext, "no_fences")
            FusedLocationManager.stopBackgroundUpdates(appContext, tailTracker)
            return
        }
        var nearest: SmartGeofenceFence? = null
        var nearestEdgeDistance = Double.MAX_VALUE
        for (fence in fences) {
            val center = Location("smart_geofence").apply {
                latitude = fence.latitude
                longitude = fence.longitude
            }
            val edgeDistance = location.distanceTo(center).toDouble() - fence.radiusMeters
            if (edgeDistance < nearestEdgeDistance) {
                nearestEdgeDistance = edgeDistance
                nearest = fence
            }
        }

        val accuracy = if (location.hasAccuracy()) "${location.accuracy.toInt()}m" else "unknown"
        val withinProximity = nearestEdgeDistance <= config.proximityRadiusMeters
        SmartGeofenceDiagnostics.recordLocationWake(
            appContext,
            source,
            location,
            nearest?.id,
            nearestEdgeDistance,
            withinProximity,
        )
        if (!config.escalationEnabled) {
            DormantFarController.clear(appContext, "escalation_disabled")
            FusedLocationManager.stopBackgroundUpdates(appContext, tailTracker)
            return
        }
        if (MockLocationPolicyGate.evaluateLocation(
                appContext,
                config,
                location,
                source,
                where = "proximity_evaluator",
            ).rejected
        ) {
            return
        }
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Location evaluation source=$source nearest fence=${nearest?.id} " +
                "edgeDist=${nearestEdgeDistance.toInt()}m " +
                "acc=$accuracy withinProximity=$withinProximity"
        )

        val pulseMaxAgeMillis = if (source == Constants.LOCATION_WAKE_SOURCE_PASSIVE) {
            Constants.PASSIVE_LOCATION_MAX_EVIDENCE_AGE_MILLIS
        } else if (source == Constants.LOCATION_WAKE_SOURCE_DORMANT_PROBE) {
            config.activityFusedLocationStaleAfterMillis
        } else {
            config.proximityMaxWaitMillis
        }.coerceAtLeast(1L)
        val pulseRejection = LocationQualityPolicy.rejectionReason(
            location,
            pulseMaxAgeMillis,
            config.pulseLocationMaxAccuracyMeters,
        )
        if (pulseRejection != null) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Fused location rejected by pulse-quality gate source=$source " +
                    "fence=${nearest?.id} withinProximity=$withinProximity reason=$pulseRejection."
            )
            if (source == Constants.LOCATION_WAKE_SOURCE_DORMANT_PROBE) {
                DormantFarStateStore.recordProbeResult(appContext, "cached_rejected:$pulseRejection")
                DormantFarController.exitAndRestart(
                    appContext,
                    "probe_cached_rejected:$pulseRejection",
                )
            }
            return
        }
        val livenessRejection = LocationQualityPolicy.rejectionReason(
            location,
            config.activityFusedLocationStaleAfterMillis,
            config.pulseLocationMaxAccuracyMeters,
        )
        if (livenessRejection == null) {
            FusedLocationLiveness.recordHealthyFix(appContext, location, source)
            ProximityPulseController.onHealthyFusedFix(appContext)
        }

        fences.forEach { fence ->
            if (FenceObservationStore.currentState(appContext, fence.id) ==
                com.yarithdev.smart_geofence.store.ObservedFenceState.OUTSIDE
            ) {
                ProximityPulseController.maybeStart(
                    appContext,
                    fence.id,
                    edgeDistance(location, fence),
                    source,
                )
            }
        }

        val knownSource = eventSource(source)
        val hasPendingValidation = NativeTransitionCoordinator.allPending(appContext)
            .any { it.validationRequired }
        if ((!withinProximity || nearest == null) && !hasPendingValidation) {
            SmartGeofenceEventProcessor.processLocation(
                appContext,
                LocationEventInput(
                    location = location,
                    candidateFences = emptyList(),
                    mode = LocationEventMode.PROXIMITY,
                    source = knownSource,
                    maxAgeMillis = pulseMaxAgeMillis,
                    tailTracker = tailTracker,
                ),
            )
            fences.forEach { fence ->
                if (FenceObservationStore.currentState(appContext, fence.id) ==
                    com.yarithdev.smart_geofence.store.ObservedFenceState.OUTSIDE
                ) {
                    ProximityPulseController.maybeStart(
                        appContext,
                        fence.id,
                        edgeDistance(location, fence),
                        source,
                    )
                }
            }
        }
        val dormantAction = DormantFarController.onAcceptedLocation(
            appContext,
            config,
            location,
            source,
            nearest?.id,
            nearestEdgeDistance,
        )
        if (dormantAction == DormantFarLocationAction.ENTERED ||
            dormantAction == DormantFarLocationAction.REFRESHED
        ) {
            if (nearest != null &&
                (FenceObservationStore.currentState(appContext, nearest.id) != null ||
                    hasPendingValidation)
            ) {
                FusedLocationConfirm.evaluateKnownLocation(
                    appContext,
                    location,
                    nearest,
                    knownSource,
                    tailTracker,
                )
            }
            if (source == Constants.LOCATION_WAKE_SOURCE_DORMANT_PROBE) {
                DormantFarStateStore.recordProbeResult(appContext, "valid_far")
            }
            return
        }
        if (dormantAction == DormantFarLocationAction.EXITED &&
            source == Constants.LOCATION_WAKE_SOURCE_DORMANT_PROBE
        ) {
            DormantFarStateStore.recordProbeResult(appContext, "valid_near_or_ambiguous")
        }

        FusedLocationManager.updateBalancedDisplacement(
            appContext,
            nearestEdgeDistance,
            tailTracker,
        )

        if ((!withinProximity && !hasPendingValidation) || nearest == null) return

        val decision = FusedLocationConfirm.evaluateKnownLocation(
            appContext,
            location,
            nearest,
            knownSource,
            tailTracker,
        )
        fences.forEach { fence ->
            if (FenceObservationStore.currentState(appContext, fence.id) ==
                com.yarithdev.smart_geofence.store.ObservedFenceState.OUTSIDE
            ) {
                ProximityPulseController.maybeStart(
                    appContext,
                    fence.id,
                    edgeDistance(location, fence),
                    source,
                )
            }
        }
        if (decision.canSkipFreshConfirm) {
            val coalescingRejection = LocationQualityPolicy.rejectionReason(
                location,
                FusedLocationConfirm.currentLocationTimeoutMillis(
                    config,
                    Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
                ),
                config.pulseLocationMaxAccuracyMeters,
            )
            val cancelledQueuedPulse = coalescingRejection == null &&
                LocationConfirmManager.cancelPulseBoundaryWorkIfQueued(
                    appContext,
                    reason = "recent_known_fix_resolved",
                )
            if (cancelledQueuedPulse) {
                SmartGeofenceDiagnostics.recordProximityConfirmCoalesced(
                    appContext,
                    source = knownSource,
                    fenceId = nearest.id,
                )
            }
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Known fix resolved nearby candidates for ${nearest.id}; " +
                    "no follow-up confirm needed queuedPulseCancelled=$cancelledQueuedPulse " +
                    "coalescingRejection=$coalescingRejection."
            )
            return
        }
        if (decision.eventFilterRejectedWithoutFreshConfirm) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Known fix failed the event filter for ${nearest.id}; no event work queued."
            )
        }
        if (source == Constants.LOCATION_WAKE_SOURCE_PASSIVE) {
            if (!config.passiveFollowUpEnabled) {
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Passive fix near ${nearest.id} needs follow-up; passive follow-up disabled."
                )
                return
            }
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Passive fix near ${nearest.id} needs follow-up; pulse is allowed."
            )
        }
    }

    private fun edgeDistance(location: Location, fence: SmartGeofenceFence): Double {
        val center = Location("smart_geofence_pulse_edge").apply {
            latitude = fence.latitude
            longitude = fence.longitude
        }
        return location.distanceTo(center).toDouble() - fence.radiusMeters
    }

    private fun eventSource(source: String): String = when (source) {
        Constants.LOCATION_WAKE_SOURCE_PASSIVE ->
            Constants.EVENT_SOURCE_SMART_GEOFENCE_PASSIVE
        Constants.LOCATION_WAKE_SOURCE_ACTIVITY ->
            Constants.EVENT_SOURCE_SMART_GEOFENCE_ACTIVITY
        else -> Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY
    }
}
