package com.yarithdev.smart_geofence.confirm

import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.PowerManager
import com.chunkytofustudios.native_geofence.generated.ActiveGeofenceWire
import com.chunkytofustudios.native_geofence.generated.GeofenceCallbackParamsWire
import com.chunkytofustudios.native_geofence.generated.GeofenceEvent
import com.chunkytofustudios.native_geofence.generated.LocationWire
import com.chunkytofustudios.native_geofence.util.ActiveGeofenceWires
import com.chunkytofustudios.native_geofence.util.NativeGeofencePersistence
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.delivery.DurableEventDeliveryCoordinator
import com.yarithdev.smart_geofence.delivery.ConfirmedDeliveryStageResult
import com.yarithdev.smart_geofence.delivery.EventDedupRecord
import com.yarithdev.smart_geofence.delivery.EventDedupStore
import com.yarithdev.smart_geofence.delivery.EventOutboxRecoveryCoordinator
import com.yarithdev.smart_geofence.delivery.SmartDeliveryStageResult
import com.yarithdev.smart_geofence.delivery.blocksSameEvent
import com.yarithdev.smart_geofence.delivery.hasFreshPendingEnqueue
import com.yarithdev.smart_geofence.delivery.isRecoverablePendingEnqueue
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.dormant.DormantFarController
import com.yarithdev.smart_geofence.fused.FusedLocationLiveness
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.mock.MockLocationPolicyGate
import com.yarithdev.smart_geofence.model.EventLocationEvidence
import com.yarithdev.smart_geofence.proximity.FusedBroadcastTailTracker
import com.yarithdev.smart_geofence.processing.LocationEventInput
import com.yarithdev.smart_geofence.processing.LocationEventMode
import com.yarithdev.smart_geofence.processing.LocationEventResult
import com.yarithdev.smart_geofence.processing.LastLocationFixStore
import com.yarithdev.smart_geofence.processing.NativeEventDisposition
import com.yarithdev.smart_geofence.processing.NativeEnterPayloadFilter
import com.yarithdev.smart_geofence.processing.NativeEventInput
import com.yarithdev.smart_geofence.processing.smartCallbackDeliveryPath
import com.yarithdev.smart_geofence.processing.smartCallbackTimestampSource
import com.yarithdev.smart_geofence.processing.triggerToDeliveryLatencyMillis
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.store.FenceObservation
import com.yarithdev.smart_geofence.store.FenceObservationDecision
import com.yarithdev.smart_geofence.store.FenceObservationStore
import com.yarithdev.smart_geofence.store.ObservedFenceState
import com.yarithdev.smart_geofence.store.SmartGeofenceFence
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.store.observationDecision
import com.yarithdev.smart_geofence.transition.NativeTransitionDirection
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator
import com.yarithdev.smart_geofence.transition.PendingNativeTransition
import com.yarithdev.smart_geofence.wake.WakeExemption

internal object SmartGeofenceEventProcessor {
    private const val TAG = "SmartGeofenceEventProcessor"
    private val transitionTransactionLock = Any()
    private val eventOutboxRecovery = EventOutboxRecoveryCoordinator()
    private val nativeTransitionWorkflow = NativeTransitionWorkflow(::emitNativeFallback)

    fun processLocation(
        context: Context,
        input: LocationEventInput,
    ): LocationEventResult {
        val appContext = context.applicationContext
        assertMainThread(appContext, "processLocation")
        return synchronized(transitionTransactionLock) {
            processLocationLocked(appContext, input)
        }
    }

    private fun processLocationLocked(
        appContext: Context,
        input: LocationEventInput,
    ): LocationEventResult {
        val config = SmartGeofenceConfigStore.load(appContext)
        val location = input.location
        val isMock = MockLocationPolicyGate.isMockLocation(location)
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN
        val classifications = input.candidateFences.map { fence ->
            val effectiveRadiusMeters = if (input.mode == LocationEventMode.INSIDE) {
                fence.radiusMeters + config.nativeEnterConfirmRadiusSlackMeters.coerceAtLeast(0.0)
            } else {
                fence.radiusMeters
            }
            val center = Location("smart_geofence").apply {
                latitude = fence.latitude
                longitude = fence.longitude
            }
            val distance = location.distanceTo(center).toDouble()
            ClassifiedFence(
                fence = fence,
                distanceMeters = distance,
                edgeDistanceMeters = distance - fence.radiusMeters,
                boundaryPosition = BoundaryClassifier.classify(distance, effectiveRadiusMeters),
            )
        }

        val mockDecision = MockLocationPolicyGate.evaluateLocation(
            appContext,
            config,
            location,
            input.source,
            where = "event_processor",
        )
        if (mockDecision.rejected) {
            classifications.forEach { classification ->
                recordBoundaryDecision(
                    appContext,
                    classification,
                    input.source,
                    accuracy,
                    isMock,
                    decision = "mock_location_rejected",
                )
            }
            return recordLocationResult(
                appContext,
                input,
                LocationEventResult.MOCK_REJECTED,
            )
        }

        val eventRejection = LocationQualityPolicy.rejectionReason(
            location,
            input.maxAgeMillis,
            config.eventLocationMaxAccuracyMeters,
        )
        if (eventRejection != null) {
            classifications.forEach { classification ->
                recordBoundaryDecision(
                    appContext,
                    classification,
                    input.source,
                    accuracy,
                    isMock,
                    decision =
                        "${classification.boundaryPosition.name.lowercase()}-event-filter-rejected",
                )
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Event location rejected after " +
                        "${classification.boundaryPosition.name.lowercase()} classification " +
                        "fence=${classification.fence.id} source=${input.source} " +
                        "reason=$eventRejection.",
                )
            }
            return recordLocationResult(
                appContext,
                input,
                LocationEventResult.EVENT_FILTER_REJECTED,
            )
        }

        val last = LastLocationFixStore.load(appContext)
        if (config.teleportGuardEnabled && !isMock && last != null) {
            val dtSeconds = (location.time - last.time) / 1000.0
            val distanceFromLast = location.distanceTo(last).toDouble()
            if (TeleportGuard.isImplausible(
                    distanceFromLast,
                    dtSeconds,
                    config.teleportMaxSpeedMetersPerSecond,
                )
            ) {
                classifications.forEach { classification ->
                    recordBoundaryDecision(
                        appContext,
                        classification,
                        input.source,
                        accuracy,
                        isMock,
                        decision = "teleport-rejected",
                    )
                    SmartGeofenceLogger.d(
                        appContext,
                        TAG,
                        "Rejecting teleport fix near ${classification.fence.id} " +
                            "source=${input.source} " +
                            "(${distanceFromLast.toInt()}m / ${dtSeconds}s).",
                    )
                }
                return recordLocationResult(
                    appContext,
                    input,
                    LocationEventResult.NEEDS_FRESH_CONFIRM,
                )
            }
        }

        if (!isMock) {
            try {
                seedUnknownFromAcceptedFusedFix(appContext, location, accuracy)
            } catch (error: Throwable) {
                runCatching {
                    SmartGeofenceLogger.w(
                        appContext,
                        TAG,
                        "Accepted fused fix could not seed unknown baselines; continuing normal processing " +
                            "source=${input.source}.",
                        error,
                    )
                }
            }
        }

        if (classifications.isEmpty()) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Accepted fix had no confirmation candidates; baseline-only processing complete " +
                    "source=${input.source}.",
            )
            ProximityPulseController.onConfidentLocationProcessed(appContext)
            return recordLocationResult(appContext, input, LocationEventResult.PROCESSED)
        }

        classifications.forEach { classification ->
            val observedState = when (classification.boundaryPosition) {
                BoundaryPosition.INSIDE -> ObservedFenceState.INSIDE
                BoundaryPosition.OUTSIDE -> ObservedFenceState.OUTSIDE
            }
            val eventName = transitionEventName(
                input.mode,
                classification.boundaryPosition,
            )
            val queued = processAcceptedLocationObservation(
                appContext,
                config,
                classification.fence,
                classification.distanceMeters,
                observedState,
                eventName,
                location,
                isMock,
                input.source,
                input.tailTracker,
                nativeTransitionInstances = input.nativeTransitionInstances,
            )
            if (input.mode == LocationEventMode.OUTSIDE &&
                classification.boundaryPosition == BoundaryPosition.INSIDE
            ) {
                val rejectedDecision = if (
                    input.source.startsWith(
                        Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM,
                    )
                ) {
                    "native_exit_confirm_rejected_inside"
                } else {
                    "outside_confirm_rejected_inside"
                }
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "$rejectedDecision fence=${classification.fence.id} " +
                        "source=${input.source} distance=${classification.distanceMeters.toInt()}m " +
                        "radius=${classification.fence.radiusMeters.toInt()}m " +
                        "edge=${classification.edgeDistanceMeters.toInt()}m.",
                )
            }
            if (input.mode == LocationEventMode.OUTSIDE &&
                classification.boundaryPosition == BoundaryPosition.OUTSIDE &&
                input.source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM)
            ) {
                val result = if (queued) {
                    "native_exit_confirmed_emitted"
                } else {
                    "native_exit_confirmed_no_callback"
                }
                SmartGeofenceLogger.i(
                    appContext,
                    TAG,
                    "$result fence=${classification.fence.id} " +
                        "source=${input.source} distance=${classification.distanceMeters.toInt()}m " +
                        "radius=${classification.fence.radiusMeters.toInt()}m " +
                        "edge=${classification.edgeDistanceMeters.toInt()}m.",
                )
            }
            if (input.mode == LocationEventMode.INSIDE &&
                classification.boundaryPosition == BoundaryPosition.OUTSIDE &&
                input.source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)
            ) {
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "native_enter_confirm_rejected_outside fence=${classification.fence.id} " +
                        "source=${input.source} distance=${classification.distanceMeters.toInt()}m " +
                        "radius=${classification.fence.radiusMeters.toInt()}m " +
                        "edge=${classification.edgeDistanceMeters.toInt()}m " +
                        "slack=${config.nativeEnterConfirmRadiusSlackMeters.toInt()}m.",
                )
            }
            if (input.mode == LocationEventMode.INSIDE &&
                classification.boundaryPosition == BoundaryPosition.INSIDE &&
                input.source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)
            ) {
                val result = if (queued) {
                    "native_enter_confirmed_emitted"
                } else {
                    "native_enter_confirmed_no_callback"
                }
                SmartGeofenceLogger.i(
                    appContext,
                    TAG,
                    "$result fence=${classification.fence.id} " +
                        "source=${input.source} distance=${classification.distanceMeters.toInt()}m " +
                        "radius=${classification.fence.radiusMeters.toInt()}m " +
                        "edge=${classification.edgeDistanceMeters.toInt()}m " +
                        "slack=${config.nativeEnterConfirmRadiusSlackMeters.toInt()}m.",
                )
            }
            recordBoundaryDecision(
                appContext,
                classification,
                input.source,
                accuracy,
                isMock,
                decision = if (
                    input.mode == LocationEventMode.OUTSIDE &&
                    classification.boundaryPosition == BoundaryPosition.INSIDE &&
                    input.source.startsWith(
                        Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM,
                    )
                ) {
                    "native_exit_confirm_rejected_inside"
                } else if (
                    input.mode == LocationEventMode.INSIDE &&
                    classification.boundaryPosition == BoundaryPosition.OUTSIDE &&
                    input.source.startsWith(
                        Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM,
                    )
                ) {
                    "native_enter_confirm_rejected_outside"
                } else {
                    classification.boundaryPosition.name.lowercase()
                },
                queuedEvent = if (queued) eventName else null,
            )
        }
        if (!isMock) LastLocationFixStore.save(appContext, location)
        FusedLocationLiveness.recordHealthyFix(appContext, location, input.source)
        ProximityPulseController.onConfidentLocationProcessed(appContext)
        return recordLocationResult(appContext, input, LocationEventResult.PROCESSED)
    }

    private fun processAcceptedLocationObservation(
        context: Context,
        config: SmartGeofenceConfig,
        fence: SmartGeofenceFence,
        distanceMeters: Double,
        observedState: ObservedFenceState,
        transitionEventName: String?,
        location: Location,
        isMock: Boolean,
        source: String,
        tailTracker: FusedBroadcastTailTracker?,
        nativeTransitionInstances: Map<String, String>? = null,
    ): Boolean {
        val direction = when (observedState) {
            ObservedFenceState.INSIDE -> NativeTransitionDirection.ENTER
            ObservedFenceState.OUTSIDE -> NativeTransitionDirection.EXIT
        }
        val opposite = when (direction) {
            NativeTransitionDirection.ENTER -> NativeTransitionDirection.EXIT
            NativeTransitionDirection.EXIT -> NativeTransitionDirection.ENTER
        }
        val oppositePending = NativeTransitionCoordinator.pendingFor(context, opposite, fence.id)
        if (oppositePending != null && !locationSourceOwnsPendingTransition(
                oppositePending,
                source,
                nativeTransitionInstances,
            )
        ) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Ignoring location evidence for ${fence.id}; pending ${opposite.name} " +
                    "is owned by its native confirmation lane source=$source.",
            )
            return false
        }
        val cancelledOpposite = oppositePending?.takeIf {
            NativeTransitionCoordinator.resolveIfCurrent(
                context,
                it,
                "opposite_location_evidence:$source",
            )
        }?.let(::listOf).orEmpty()
        if (cancelledOpposite.isNotEmpty()) {
            cancelNativeConfirmationIfNoPending(context, opposite, "opposite_location_evidence")
            LocationConfirmManager.cancelPendingValidationPulseIfNoPending(
                context,
                "opposite_location_evidence",
            )
            ProximityPulseController.onPendingTransitionChanged(
                context,
                "opposite_location_evidence",
            )
            SmartGeofenceLogger.i(
                context,
                TAG,
                "Cancelled pending ${opposite.name} for ${fence.id}; " +
                    "${direction.name} location evidence arrived source=$source. " +
                    "The restorative transition is suppressed.",
            )
            return false
        }

        if (transitionEventName == null) return false
        val currentPending = NativeTransitionCoordinator.pendingFor(context, direction, fence.id)
        if (currentPending != null && !locationSourceOwnsPendingTransition(
                currentPending,
                source,
                nativeTransitionInstances,
            )
        ) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Ignoring location evidence for ${fence.id}; pending ${direction.name} " +
                    "is owned by its native confirmation lane source=$source.",
            )
            return false
        }
        val validationEnabled = config.transitionValidationEnabled && when (direction) {
            NativeTransitionDirection.ENTER -> config.transitionValidationEnterEnabled
            NativeTransitionDirection.EXIT -> config.transitionValidationExitEnabled
        }
        if (!validationEnabled) {
            return finalizeConfirmedObservation(
                context,
                fence,
                observedState,
                transitionEventName,
                location,
                isMock,
                source,
                tailTracker,
                nativeTransitionInstances,
            )
        }

        var pending = currentPending
        if (pending != null && !pending.validationRequired) {
            NativeTransitionCoordinator.resolveIfCurrent(
                context,
                pending,
                "legacy_unvalidated_pending_replaced:$source",
            )
            pending = null
        }
        val deliveredState = FenceObservationStore.currentState(context, fence.id)
        if (pending == null && deliveredState == null) {
            FenceObservationStore.observe(context, fence.id, observedState)
            if (observedState == ObservedFenceState.OUTSIDE &&
                isNonNativeLocationEvidenceSource(source)
            ) {
                ProximityPulseController.maybeStart(
                    context,
                    fence.id,
                    distanceMeters - fence.radiusMeters,
                    source,
                )
            }
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Established ${observedState.name} baseline for ${fence.id}; " +
                    "no initial validated event queued.",
            )
            return false
        }
        if (pending == null && deliveredState == observedState) {
            if (observedState == ObservedFenceState.OUTSIDE &&
                isNonNativeLocationEvidenceSource(source)
            ) {
                ProximityPulseController.maybeStart(
                    context,
                    fence.id,
                    distanceMeters - fence.radiusMeters,
                    source,
                )
            }
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Observed unchanged ${observedState.name} for ${fence.id} source=$source.",
            )
            return false
        }

        val fingerprint = sharedTransitionValidationFingerprint(
            direction,
            config,
            fence.radiusMeters,
        )
        if (pending != null && pending.validationConfigFingerprint != fingerprint) {
            NativeTransitionCoordinator.resolveIfCurrent(
                context,
                pending,
                "validation_config_changed:$source",
            )
            pending = null
        }
        if (pending != null && isPendingTransitionValidationExpired(
                pending,
                config.confirmQueueMaxAgeMillis,
                System.currentTimeMillis(),
            )
        ) {
            val expired = pending
            if (!NativeTransitionCoordinator.resolveIfCurrent(
                    context,
                    expired,
                    "validated_transition_expired:$source",
                )
            ) {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Could not discard expired pending ${direction.name} for ${fence.id}; " +
                        "confirmation is suppressed source=$source.",
                )
                return false
            }
            cancelNativeConfirmationIfNoPending(
                context,
                direction,
                "validated_transition_expired",
            )
            LocationConfirmManager.cancelPendingValidationPulseIfNoPending(
                context,
                "validated_transition_expired",
            )
            ProximityPulseController.onPendingTransitionChanged(
                context,
                "validated_transition_expired",
            )
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Discarded expired pending ${direction.name} for ${fence.id} source=$source.",
            )
            pending = null
        }
        if (pending != null && isPrimaryFusedTransitionCandidateSource(source)) {
            val eventAtMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            val elapsedRealtimeNanos = location.elapsedRealtimeNanos.takeIf { it > 0L }
            val monotonic = captureAndroidMonotonicTime(context)
            val joined = NativeTransitionCoordinator.joinEarlierValidatedCandidateIfCurrent(
                context = context,
                pendingTransition = pending,
                source = source,
                location = EventLocationEvidence(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = if (location.hasAccuracy()) {
                        location.accuracy.toDouble()
                    } else {
                        null
                    },
                    isMock = isMock,
                ),
                triggeredAtMillis = eventAtMillis,
                eventTiming = com.yarithdev.smart_geofence.model.EventTimingEvidence(
                    wallClockEventAtMillis = eventAtMillis,
                    eventMonotonicMillis = elapsedRealtimeNanos?.div(1_000_000L),
                    androidBootCount = monotonic.bootCount,
                    timestampOrigin = "fused_location:$source",
                ),
                candidateLocationTimeMillis = location.time.takeIf { it > 0L },
                candidateLocationElapsedRealtimeNanos = elapsedRealtimeNanos,
            )
            if (joined == null) {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Could not join current pending ${direction.name} for ${fence.id}; " +
                        "confirmation is suppressed source=$source.",
                )
                return false
            }
            pending = joined
        }
        val confirmationBoundaryMeters = pending?.confirmationBoundaryMeters
            ?: fence.radiusMeters
        if (pending == null) {
            if (source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM) ||
                source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)
            ) {
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Ignoring stale native confirmation evidence for ${fence.id}; " +
                        "no matching validated transition is pending source=$source.",
                )
                return false
            }
            val eventAtMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            val elapsedRealtimeNanos = location.elapsedRealtimeNanos.takeIf { it > 0L }
            val monotonic = captureAndroidMonotonicTime(context)
            val armed = NativeTransitionCoordinator.arm(
                context = context,
                direction = direction,
                fenceIds = listOf(fence.id),
                source = source,
                location = EventLocationEvidence(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = if (location.hasAccuracy()) {
                        location.accuracy.toDouble()
                    } else {
                        null
                    },
                    isMock = isMock,
                ),
                triggeredAtMillis = eventAtMillis,
                delayMillis = config.transitionValidationMinimumDelayMillis,
                eventTiming = com.yarithdev.smart_geofence.model.EventTimingEvidence(
                    wallClockEventAtMillis = eventAtMillis,
                    eventMonotonicMillis = elapsedRealtimeNanos?.div(1_000_000L),
                    androidBootCount = monotonic.bootCount,
                    timestampOrigin = "fused_location:$source",
                ),
                validationRequired = true,
                candidateLocationTimeMillis = location.time.takeIf { it > 0L },
                candidateLocationElapsedRealtimeNanos = elapsedRealtimeNanos,
                fenceRadiusMeters = fence.radiusMeters,
                confirmationBoundaryMeters = confirmationBoundaryMeters,
                validationConfigFingerprint = fingerprint,
            )
            SmartGeofenceLogger.i(
                context,
                TAG,
                "Armed ${direction.name}_PENDING for ${fence.id} source=$source " +
                    "eventAt=$eventAtMillis eligibilityDelay=" +
                    "${config.transitionValidationMinimumDelayMillis}ms " +
                    "confirmationBoundary=${confirmationBoundaryMeters.toInt()}m " +
                    "persisted=${armed.isNotEmpty()}.",
            )
            if (armed.isNotEmpty()) {
                ProximityPulseController.onPendingTransitionChanged(
                    context,
                    "candidate_armed",
                    newInstance = true,
                )
            }
            return false
        }

        if (!NativeTransitionCoordinator.isEligible(context, pending)) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Pending ${direction.name} for ${fence.id} is not eligible yet source=$source.",
            )
            return false
        }
        if (!isDistinctPostEligibilityFix(context, location, pending)) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Rejected duplicate or pre-eligibility confirmation fix for " +
                    "${fence.id} direction=${direction.name} source=$source.",
            )
            return false
        }
        val geometryConfirmed = when (direction) {
            NativeTransitionDirection.EXIT ->
                distanceMeters > (pending.confirmationBoundaryMeters ?: Double.MAX_VALUE)
            NativeTransitionDirection.ENTER ->
                distanceMeters <= (pending.confirmationBoundaryMeters ?: fence.radiusMeters)
        }
        if (!geometryConfirmed) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Pending ${direction.name} remains unconfirmed for ${fence.id} " +
                    "distance=${distanceMeters.toInt()}m boundary=" +
                    "${pending.confirmationBoundaryMeters?.toInt()}m source=$source.",
            )
            return false
        }
        return finalizeConfirmedObservation(
            context,
            fence,
            observedState,
            transitionEventName,
            location,
            isMock,
            source,
            tailTracker,
            nativeTransitionInstances,
            validatedTransition = pending,
        )
    }

    private fun isPrimaryFusedTransitionCandidateSource(source: String): Boolean =
        source == Constants.EVENT_SOURCE_SMART_GEOFENCE_PASSIVE ||
            source == Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY ||
            source == Constants.EVENT_SOURCE_SMART_GEOFENCE_ACTIVITY

    private fun isNonNativeLocationEvidenceSource(source: String): Boolean =
        !source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM) &&
            !source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)

    internal fun locationSourceOwnsPendingTransition(
        pending: PendingNativeTransition,
        source: String,
        nativeTransitionInstances: Map<String, String>?,
    ): Boolean {
        val expectedNativeSource = when (pending.direction) {
            NativeTransitionDirection.ENTER ->
                Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM
            NativeTransitionDirection.EXIT ->
                Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM
        }
        if (!pending.nativeCandidate) return isNonNativeLocationEvidenceSource(source)
        if (!source.startsWith(expectedNativeSource)) return false
        val ownership = nativeTransitionOwnershipForSource(
            source,
            pending.direction,
            nativeTransitionInstances,
        )
        return ownsNativeTransitionInstance(
            ownership,
            pending.fenceId,
            pending.instanceId,
        )
    }

    private fun cancelNativeConfirmationIfNoPending(
        context: Context,
        direction: NativeTransitionDirection,
        reason: String,
    ) {
        when (direction) {
            NativeTransitionDirection.ENTER ->
                LocationConfirmManager.cancelNativeEnterConfirmIfNoPending(context, reason)
            NativeTransitionDirection.EXIT ->
                LocationConfirmManager.cancelNativeExitConfirmIfNoPending(context, reason)
        }
    }

    private fun finalizeConfirmedObservation(
        context: Context,
        fence: SmartGeofenceFence,
        observedState: ObservedFenceState,
        transitionEventName: String?,
        location: Location?,
        isMock: Boolean,
        source: String,
        tailTracker: FusedBroadcastTailTracker?,
        nativeTransitionInstances: Map<String, String>? = null,
        nativeExitFallback: PendingNativeExit? = null,
        nativeEnterFallback: PendingNativeEnter? = null,
        validatedTransition: PendingNativeTransition? = null,
        onEnqueueFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val appContext = context.applicationContext
        recoverPendingEventOutboxLocked(appContext)
        val nativeExitConfirmSource =
            source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM)
        val nativeEnterConfirmSource =
            source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)
        val exitOwnership = nativeTransitionOwnershipForSource(
            source,
            NativeTransitionDirection.EXIT,
            nativeTransitionInstances,
        )
        val enterOwnership = nativeTransitionOwnershipForSource(
            source,
            NativeTransitionDirection.ENTER,
            nativeTransitionInstances,
        )
        val pendingNativeExit = NativeExitPendingStore.pendingFor(appContext, fence.id)
            ?.takeIf {
                ownsNativeTransitionInstance(exitOwnership, fence.id, it.instanceId)
            }
        val matchingNativeExitFallback = nativeExitFallback?.takeIf { it.fenceId == fence.id }
        val pendingNativeExitProof = matchingNativeExitFallback ?: pendingNativeExit
        val hasPendingNativeExit = pendingNativeExitProof != null
        val pendingNativeEnter = NativeEnterPendingStore.pendingFor(appContext, fence.id)
            ?.takeIf {
                ownsNativeTransitionInstance(enterOwnership, fence.id, it.instanceId)
            }
        val matchingNativeEnterFallback = nativeEnterFallback?.takeIf { it.fenceId == fence.id }
        val pendingNativeEnterProof = matchingNativeEnterFallback ?: pendingNativeEnter
        val hasPendingNativeEnter = pendingNativeEnterProof != null
        if (nativeExitConfirmSource && !hasPendingNativeExit) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Ignoring stale native EXIT confirm for ${fence.id}; pending EXIT was resolved or replaced " +
                    "source=$source observed=${observedState.name}.",
            )
            return false
        }
        if (nativeEnterConfirmSource && !hasPendingNativeEnter) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Ignoring stale native ENTER confirm for ${fence.id}; pending ENTER was resolved or replaced " +
                    "source=$source observed=${observedState.name}.",
            )
            return false
        }
        val hasMatchingPendingNativeExit =
            hasPendingNativeExit && observedState == ObservedFenceState.OUTSIDE
        val hasMatchingPendingNativeEnter =
            hasPendingNativeEnter && observedState == ObservedFenceState.INSIDE
        val rootTraceId = when {
            hasMatchingPendingNativeExit -> pendingNativeExitProof?.traceId
            hasMatchingPendingNativeEnter -> pendingNativeEnterProof?.traceId
            else -> pendingNativeExitProof?.traceId ?: pendingNativeEnterProof?.traceId
        }
        if (hasPendingNativeExit && observedState == ObservedFenceState.INSIDE) {
            pendingNativeExitProof?.let {
                NativeExitPendingStore.resolveIfCurrent(
                    appContext,
                    it,
                    "inside_evidence:$source",
                )
            }
            LocationConfirmManager.cancelNativeExitConfirmIfNoPending(
                appContext,
                "inside_evidence",
            )
            SmartGeofenceLogger.i(
                appContext,
                TAG,
                "Cancelled pending native EXIT for ${fence.id}; inside evidence arrived source=$source.",
            )
            if (!hasMatchingPendingNativeEnter) {
                FenceObservationStore.observe(
                    appContext,
                    fence.id,
                    ObservedFenceState.INSIDE,
                )
                return false
            }
        }
        if (hasPendingNativeEnter && observedState == ObservedFenceState.OUTSIDE) {
            pendingNativeEnterProof?.let {
                NativeEnterPendingStore.resolveIfCurrent(
                    appContext,
                    it,
                    "outside_evidence:$source",
                )
            }
            LocationConfirmManager.cancelNativeEnterConfirmIfNoPending(
                appContext,
                "outside_evidence",
            )
            SmartGeofenceLogger.i(
                appContext,
                TAG,
                "Cancelled pending native ENTER for ${fence.id}; outside evidence arrived source=$source.",
            )
            if (!hasMatchingPendingNativeExit) {
                FenceObservationStore.observe(
                    appContext,
                    fence.id,
                    ObservedFenceState.OUTSIDE,
                )
                return false
            }
        }
        val nativeTransitionResolution = pendingNativeTransitionResolution(
            observedState = observedState,
            transitionEventName = transitionEventName,
            pendingNativeExit = pendingNativeExitProof,
            pendingNativeEnter = pendingNativeEnterProof,
        )
        val eventNameToEmit = nativeTransitionResolution.eventName
        val recoveringStalePendingEnqueue = eventNameToEmit != null &&
            EventDedupStore.currentRecord(appContext, fence.id)?.let { record ->
                record.eventName == eventNameToEmit &&
                    record.isRecoverablePendingEnqueue(captureAndroidMonotonicTime(appContext))
            } == true
        val previousObservation = FenceObservationStore.currentState(appContext, fence.id)
        val observation = FenceObservation(
            previous = previousObservation,
            current = observedState,
            decision = observationDecision(previousObservation, observedState),
        )
        when (observation.decision) {
            FenceObservationDecision.BASELINE -> {
                if (nativeTransitionResolution.forcedByPending) {
                    SmartGeofenceLogger.i(
                        appContext,
                        TAG,
                        "Treating ${observedState.name} baseline for ${fence.id} as " +
                            "${nativeTransitionResolution.eventName} because a pending native transition exists " +
                            "source=$source.",
                    )
                } else {
                    FenceObservationStore.observe(appContext, fence.id, observedState)
                    SmartGeofenceLogger.d(
                        appContext,
                        TAG,
                        "Established ${observedState.name} baseline for ${fence.id}; " +
                            "no initial smart event queued.",
                    )
                    return false
                }
            }
            FenceObservationDecision.UNCHANGED -> {
                if (nativeTransitionResolution.forcedByPending || recoveringStalePendingEnqueue) {
                    SmartGeofenceLogger.i(
                        appContext,
                        TAG,
                        "Treating unchanged ${observedState.name} for ${fence.id} as " +
                            "$eventNameToEmit because a recoverable pending transition exists " +
                            "source=$source.",
                    )
                } else {
                    SmartGeofenceLogger.d(
                        appContext,
                        TAG,
                        "Observed ${observedState.name} for ${fence.id} source=$source; " +
                            "state is unchanged.",
                    )
                    return false
                }
            }
            FenceObservationDecision.TRANSITION -> Unit
        }

        if (eventNameToEmit == null) {
            FenceObservationStore.observe(appContext, fence.id, observedState)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Recorded ${observedState.name} transition for ${fence.id} source=$source; " +
                    "this confirmation type does not emit it.",
            )
            return false
        }

        val confirmedValidatedTransition = validatedTransition?.takeIf { transition ->
            transition.validationRequired && when (observedState) {
                ObservedFenceState.OUTSIDE ->
                    pendingNativeExitProof?.instanceId == transition.instanceId
                ObservedFenceState.INSIDE ->
                    pendingNativeEnterProof?.instanceId == transition.instanceId
            }
        }
        confirmedValidatedTransition?.let { transition ->
            SmartGeofenceDiagnostics.recordTransitionConfirmationEvidence(
                context = appContext,
                pending = transition,
                confirmationSource = source,
                confirmationLocation = location,
                confirmationIsMock = isMock,
                confirmedEventName = eventNameToEmit,
                traceId = rootTraceId,
            )
        }

        val edgeDistance = location?.let {
            val center = Location("smart_geofence_pulse_edge").apply {
                latitude = fence.latitude
                longitude = fence.longitude
            }
            it.distanceTo(center).toDouble() - fence.radiusMeters
        }
        var transitionCommitted = false
        val callbackFilteredByTriggers = eventFromName(eventNameToEmit)?.let { event ->
            !fence.hasCallbackTrigger(event)
        } == true
        val emitted = emitConfirmed(
            appContext,
            fence,
            eventNameToEmit,
            location,
            isMock,
            source,
            tailTracker,
            eventAtMillis = nativeTransitionResolution.eventAtMillis,
            eventTiming = nativeTransitionResolution.eventTiming,
            traceId = rootTraceId,
            transitionInstanceId = confirmedValidatedTransition?.instanceId,
            beforeEnqueue = {
                FenceObservationStore.emitTransition(
                    appContext,
                    fence.id,
                    observedState,
                    edgeDistanceMeters = edgeDistance,
                    invalidateCallbackDedup = callbackFilteredByTriggers,
                    forceEmission = nativeTransitionResolution.forcedByPending ||
                        recoveringStalePendingEnqueue,
                )
                transitionCommitted = true
            },
        ) { finalized ->
            if (!finalized) {
                FenceObservationStore.restoreIfCurrent(
                    appContext,
                    fence.id,
                    observation.current,
                    observation.previous,
                )
                matchingNativeExitFallback?.let {
                    NativeExitPendingStore.restore(
                        appContext,
                        it,
                        "fallback_enqueue_failed:$source",
                    )
                }
                matchingNativeEnterFallback?.let {
                    NativeEnterPendingStore.restore(
                        appContext,
                        it,
                        "fallback_enqueue_failed:$source",
                    )
                }
            } else if (observedState == ObservedFenceState.OUTSIDE &&
                pendingNativeExitProof != null
            ) {
                NativeExitPendingStore.resolveIfCurrent(
                    appContext,
                    pendingNativeExitProof,
                    "outside_evidence:$source",
                )
                LocationConfirmManager.cancelNativeExitConfirmIfNoPending(
                    appContext,
                    "outside_evidence",
                )
                LocationConfirmManager.cancelPendingValidationPulseIfNoPending(
                    appContext,
                    "outside_evidence",
                )
                ProximityPulseController.onPendingTransitionChanged(
                    appContext,
                    "outside_evidence",
                )
            } else if (observedState == ObservedFenceState.INSIDE &&
                pendingNativeEnterProof != null
            ) {
                NativeEnterPendingStore.resolveIfCurrent(
                    appContext,
                    pendingNativeEnterProof,
                    "inside_evidence:$source",
                )
                LocationConfirmManager.cancelNativeEnterConfirmIfNoPending(
                    appContext,
                    "inside_evidence",
                )
                LocationConfirmManager.cancelPendingValidationPulseIfNoPending(
                    appContext,
                    "inside_evidence",
                )
                ProximityPulseController.onPendingTransitionChanged(
                    appContext,
                    "inside_evidence",
                )
            }
            onEnqueueFinished?.invoke(finalized)
        }
        if (transitionCommitted) {
            runCatching {
                ProximityPulseController.onInternalTransitionCommitted(
                    appContext,
                    fence.id,
                    observedState,
                    edgeDistance,
                    source,
                )
            }.onFailure { error ->
                runCatching {
                    SmartGeofenceLogger.w(
                        appContext,
                        TAG,
                        "Internal transition committed but Pulse reconciliation failed " +
                            "fence=${fence.id} source=$source.",
                        error,
                    )
                }
            }
        }
        return emitted
    }

    private fun emitConfirmed(
        context: Context,
        fence: SmartGeofenceFence,
        eventName: String,
        location: Location?,
        isMock: Boolean,
        source: String,
        tailTracker: FusedBroadcastTailTracker? = null,
        eventAtMillis: Long? = null,
        eventTiming: SmartGeofenceEventTiming? = null,
        traceId: String? = null,
        transitionInstanceId: String? = null,
        beforeEnqueue: (() -> Unit)? = null,
        onEnqueueFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val appContext = context.applicationContext
        val deliveryPath = smartCallbackDeliveryPath(source)
        val deviceIdleModeAtDelivery = isDeviceIdleMode(appContext)
        val event = eventFromName(eventName)
        if (event == null) {
            SmartGeofenceDiagnostics.recordSmartCallback(
                appContext,
                fence.id,
                eventName,
                source,
                result = "invalid_event",
                deliveryPath = deliveryPath,
                deviceIdleModeAtDelivery = deviceIdleModeAtDelivery,
                traceId = traceId,
                transitionInstanceId = transitionInstanceId,
            )
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Ignoring confirmed event with invalid event=$eventName fence=${fence.id} source=$source."
            )
            onEnqueueFinished?.invoke(false)
            return false
        }
        fun commitInternalTransition(): Boolean = try {
            beforeEnqueue?.invoke()
            true
        } catch (error: Throwable) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Could not commit confirmed internal transition; validation remains pending " +
                    "fence=${fence.id} event=$event source=$source.",
                error,
            )
            false
        }
        if (!fence.hasCallbackTrigger(event)) {
            SmartGeofenceDiagnostics.recordSmartCallback(
                appContext,
                fence.id,
                eventName,
                source,
                result = "callback_filtered",
                deliveryPath = deliveryPath,
                deviceIdleModeAtDelivery = deviceIdleModeAtDelivery,
                traceId = traceId,
                transitionInstanceId = transitionInstanceId,
            )
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Skipping callback for confirmed $eventName on ${fence.id}; event is filtered by triggers."
            )
            val committed = commitInternalTransition()
            onEnqueueFinished?.invoke(committed)
            return false
        }

        val enqueuedAt = System.currentTimeMillis()
        val callbackEventAtMillis = eventAtMillis
            ?.takeIf { it > 0L }
            ?: enqueuedAt
        val timestampSource = smartCallbackTimestampSource(eventAtMillis)
        val timing = eventTiming?.takeIf {
            it.wallClockEventAtMillis == callbackEventAtMillis
        } ?: SmartGeofenceEventTimingStore.captureNow(
            appContext,
            callbackEventAtMillis,
            timestampSource,
        )
        val deliveryLatencyMillis = triggerToDeliveryLatencyMillis(
            enqueuedAt,
            eventAtMillis,
        )
        val stagedDelivery = DurableEventDeliveryCoordinator.stageConfirmedCallback(
            appContext,
            fence.id,
            event,
            source,
            enqueuedAt,
            paramsFactory = { eventId ->
                GeofenceCallbackParamsWire(
                    geofences = listOf(activeGeofenceWire(appContext, fence)),
                    event = event,
                    location = location?.toLocationWire(isMock),
                    eventAtMillis = callbackEventAtMillis,
                    callbackHandle = fence.dispatchCallbackHandle,
                    eventId = eventId,
                    callbackContextsByGeofenceId = mapOf(fence.id to fence.callbackHandle),
                    traceId = traceId,
                )
            },
            onPrepared = {
                SmartGeofenceEventTimingStore.record(
                    appContext,
                    fence.id,
                    eventName,
                    timing,
                )
            },
        )
        val stagedEventId = when (stagedDelivery) {
            is ConfirmedDeliveryStageResult.Ready -> stagedDelivery.record.eventId
            is ConfirmedDeliveryStageResult.DurableClaimPending -> stagedDelivery.record.eventId
            else -> null
        }
        if (transitionInstanceId != null && stagedEventId != null) {
            SmartGeofenceDiagnostics.recordTrace(
                context = appContext,
                stage = "transition_validation_emission",
                reasonCode = "outbox_persisted",
                traceId = traceId,
                eventId = stagedEventId,
                fenceId = fence.id,
                event = eventName,
                source = source,
                extras = linkedMapOf(
                    "transitionInstanceId" to transitionInstanceId,
                    "emittedEventName" to eventName,
                    "eventAtMillis" to callbackEventAtMillis,
                ),
            )
        }
        val claimPending = stagedDelivery is ConfirmedDeliveryStageResult.DurableClaimPending
        val outboxRecord = when (stagedDelivery) {
            ConfirmedDeliveryStageResult.Deduped -> {
                SmartGeofenceDiagnostics.recordSmartCallback(
                    appContext,
                    fence.id,
                    eventName,
                    source,
                    result = "deduped",
                    enqueuedAtMillis = enqueuedAt,
                    eventAtMillis = callbackEventAtMillis,
                    timestampSource = timestampSource,
                    deliveryPath = deliveryPath,
                    triggerToDeliveryLatencyMillis = deliveryLatencyMillis,
                    deviceIdleModeAtDelivery = deviceIdleModeAtDelivery,
                    traceId = traceId,
                    transitionInstanceId = transitionInstanceId,
                )
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Confirmed event de-duped fence=${fence.id} event=$event source=$source.",
                )
                val committed = commitInternalTransition()
                onEnqueueFinished?.invoke(committed)
                return false
            }
            ConfirmedDeliveryStageResult.SequenceFailed -> {
                onEnqueueFinished?.invoke(false)
                return false
            }
            ConfirmedDeliveryStageResult.PersistenceFailed -> {
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "Could not persist confirmed event outbox " +
                        "fence=${fence.id} event=$event source=$source.",
                )
                onEnqueueFinished?.invoke(false)
                return false
            }
            is ConfirmedDeliveryStageResult.DurableClaimPending -> stagedDelivery.record
            is ConfirmedDeliveryStageResult.Ready -> stagedDelivery.record
        }
        val params = outboxRecord.params
        if (!commitInternalTransition()) {
            onEnqueueFinished?.invoke(false)
            return false
        }
        if (claimPending) {
            onEnqueueFinished?.invoke(true)
            return true
        }
        SmartGeofenceDiagnostics.recordSmartCallback(
            appContext,
            fence.id,
            eventName,
            source,
            result = "enqueue_requested",
            enqueuedAtMillis = enqueuedAt,
            eventAtMillis = callbackEventAtMillis,
            timestampSource = timestampSource,
            deliveryPath = deliveryPath,
            triggerToDeliveryLatencyMillis = deliveryLatencyMillis,
            deviceIdleModeAtDelivery = deviceIdleModeAtDelivery,
            traceId = traceId,
            eventId = outboxRecord.eventId,
            transitionInstanceId = transitionInstanceId,
        )
        if (eventOutboxRecovery.isBusy) {
            onEnqueueFinished?.invoke(true)
            return true
        }
        check(eventOutboxRecovery.claim(outboxRecord.id))
        val tail = tailTracker?.registerTail()
        try {
            DurableEventDeliveryCoordinator.enqueue(
                appContext,
                outboxRecord,
                listOf(fence.id),
                source,
                ::runInTransitionTransaction,
            ) { enqueued, finalized ->
                try {
                    eventOutboxRecovery.release(outboxRecord.id)
                    SmartGeofenceDiagnostics.recordSmartCallback(
                        appContext,
                        fence.id,
                        eventName,
                        source,
                        result = if (enqueued) "enqueue_succeeded" else "enqueue_failed",
                        enqueuedAtMillis = enqueuedAt,
                        eventAtMillis = callbackEventAtMillis,
                        timestampSource = timestampSource,
                        deliveryPath = deliveryPath,
                        triggerToDeliveryLatencyMillis = deliveryLatencyMillis,
                        deviceIdleModeAtDelivery = deviceIdleModeAtDelivery,
                        traceId = traceId,
                        eventId = outboxRecord.eventId,
                        transitionInstanceId = transitionInstanceId,
                    )
                    if (finalized) {
                        recoverPendingEventOutboxLocked(appContext)
                    }
                    onEnqueueFinished?.invoke(true)
                } finally {
                    tail?.complete()
                }
            }
        } catch (e: Throwable) {
            eventOutboxRecovery.release(outboxRecord.id)
            tail?.complete()
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Immediate callback enqueue threw after outbox persistence; " +
                    "recovery will retry fence=${fence.id} event=$event.",
                e,
            )
            onEnqueueFinished?.invoke(true)
            return true
        }
        SmartGeofenceLogger.i(
            appContext,
            TAG,
            "Queued confirmed event fence=${fence.id} event=$event source=$source."
        )
        return true
    }

    private fun isDeviceIdleMode(context: Context): Boolean? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return powerManager.isDeviceIdleMode
    }

    fun recoverPendingEventOutbox(context: Context): Boolean {
        val appContext = context.applicationContext
        assertMainThread(appContext, "recoverPendingEventOutbox")
        return synchronized(transitionTransactionLock) {
            recoverPendingEventOutboxLocked(appContext)
        }
    }

    private fun recoverPendingEventOutboxLocked(context: Context): Boolean =
        recoverPendingEventOutboxLocked(context, emptySet())

    private fun recoverPendingEventOutboxLocked(
        context: Context,
        exclusions: Set<String>,
    ): Boolean = eventOutboxRecovery.recover(
        context,
        exclusions,
        ::runInTransitionTransaction,
    )

    private fun runInTransitionTransaction(block: () -> Unit) {
        synchronized(transitionTransactionLock) { block() }
    }

    fun processNative(
        context: Context,
        input: NativeEventInput,
        recoveryExclusions: Set<String> = emptySet(),
    ): NativeEventDisposition {
        val appContext = context.applicationContext
        assertMainThread(appContext, "processNative")
        if (!input.deliveryOwnership.canContinueSmart()) {
            return recordNativeDisposition(
                appContext,
                input,
                NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
            )
        }
        return synchronized(transitionTransactionLock) {
            if (!input.deliveryOwnership.canContinueSmart()) {
                return@synchronized recordNativeDisposition(
                    appContext,
                    input,
                    NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                )
            }
            recoverPendingEventOutboxLocked(appContext, recoveryExclusions)
            if (!input.deliveryOwnership.canContinueSmart()) {
                return@synchronized recordNativeDisposition(
                    appContext,
                    input,
                    NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                )
            }
            processNativeLocked(appContext, input)
        }
    }

    private fun processNativeLocked(
        appContext: Context,
        input: NativeEventInput,
    ): NativeEventDisposition {
        val config = SmartGeofenceConfigStore.load(appContext)
        val event = eventFromName(input.eventName ?: "")
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "native_event_received event=${input.eventName} source=${input.source} " +
                "ids=${input.fenceIds} allowCallbackOwnership=${input.allowCallbackOwnership} " +
                "hasLocation=${input.location != null} callbackGroups=${input.callbackParams.size}."
        )
        if (!input.allowCallbackOwnership) {
            when (event) {
                GeofenceEvent.ENTER -> nativeTransitionWorkflow.cancelOpposite(
                    appContext,
                    NativeTransitionDirection.ENTER,
                    input,
                )
                GeofenceEvent.EXIT -> nativeTransitionWorkflow.cancelOpposite(
                    appContext,
                    NativeTransitionDirection.EXIT,
                    input,
                )
                else -> Unit
            }
            input.location?.let { payload ->
                MockLocationPolicyGate.evaluateNativePayload(
                    appContext,
                    config,
                    isMock = payload.isMock,
                    source = input.source,
                    where = "native_event_unowned",
                    accuracyMeters = payload.accuracyMeters,
                    rejectable = false,
                )
            }
            FenceObservationStore.recordNativeEvent(
                appContext,
                input.fenceIds,
                input.eventName,
            )
            EventDedupStore.recordNativeEvent(
                appContext,
                input.fenceIds,
                input.eventName,
                input.source,
            )
            return recordNativeDisposition(
                appContext,
                input,
                NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
            )
        }
        if (!input.deliveryOwnership.canContinueSmart()) {
            return recordNativeDisposition(
                appContext,
                input,
                NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
            )
        }
        DormantFarController.exitForNativeEvent(appContext, input.eventName)
        input.location?.let { payload ->
            val mockDecision = MockLocationPolicyGate.evaluateNativePayload(
                appContext,
                config,
                isMock = payload.isMock,
                source = input.source,
                where = "native_event",
                accuracyMeters = payload.accuracyMeters,
                rejectable = true,
            )
            if (mockDecision.rejected) {
                if (!input.deliveryOwnership.tryCommitSmart()) {
                    return recordNativeDisposition(
                        appContext,
                        input,
                        NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                    )
                }
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "Native event suppressed by mock-location policy " +
                        "event=${input.eventName} source=${input.source} ids=${input.fenceIds}."
                )
                input.onFinished(true)
                return recordNativeDisposition(
                    appContext,
                    input,
                    NativeEventDisposition.CALLBACK_OWNED,
                )
            }
        }
        val inputAfterOppositePendingCancel = when (event) {
            GeofenceEvent.ENTER -> {
                if (!input.deliveryOwnership.canContinueSmart()) {
                    return recordNativeDisposition(
                        appContext,
                        input,
                        NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                    )
                }
                val cancellation = nativeTransitionWorkflow.cancelOpposite(
                    appContext,
                    NativeTransitionDirection.ENTER,
                    input,
                )
                if (cancellation.cancelledCount > 0 && cancellation.input.callbackParams.isEmpty()) {
                    if (!input.deliveryOwnership.tryCommitSmart()) {
                        return recordNativeDisposition(
                            appContext,
                            input,
                            NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                        )
                    }
                    input.onFinished(true)
                    return recordNativeDisposition(
                        appContext,
                        input,
                        NativeEventDisposition.CALLBACK_OWNED,
                    )
                }
                cancellation.input
            }
            GeofenceEvent.EXIT -> {
                if (!input.deliveryOwnership.canContinueSmart()) {
                    return recordNativeDisposition(
                        appContext,
                        input,
                        NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                    )
                }
                val cancellation = nativeTransitionWorkflow.cancelOpposite(
                    appContext,
                    NativeTransitionDirection.EXIT,
                    input,
                )
                if (cancellation.cancelledCount > 0 && cancellation.input.callbackParams.isEmpty()) {
                    if (!input.deliveryOwnership.tryCommitSmart()) {
                        return recordNativeDisposition(
                            appContext,
                            input,
                            NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                        )
                    }
                    input.onFinished(true)
                    return recordNativeDisposition(
                        appContext,
                        input,
                        NativeEventDisposition.CALLBACK_OWNED,
                    )
                }
                cancellation.input
            }
            else -> input
        }
        if (inputAfterOppositePendingCancel.callbackParams.isEmpty()) {
            return recordNativeDisposition(
                appContext,
                inputAfterOppositePendingCancel,
                NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
            )
        }
        if (event == GeofenceEvent.EXIT && config.nativeExitConfirmationEnabled) {
            val disposition = nativeTransitionWorkflow.handleCandidate(
                appContext,
                NativeTransitionDirection.EXIT,
                inputAfterOppositePendingCancel,
                config,
            )
            return recordNativeDisposition(appContext, inputAfterOppositePendingCancel, disposition)
        }
        if (event == GeofenceEvent.ENTER && config.nativeEnterConfirmationEnabled) {
            val disposition = nativeTransitionWorkflow.handleCandidate(
                appContext,
                NativeTransitionDirection.ENTER,
                inputAfterOppositePendingCancel,
                config,
            )
            return recordNativeDisposition(appContext, inputAfterOppositePendingCancel, disposition)
        }
        val inputForDirectDelivery = if (event == GeofenceEvent.DWELL) {
            val seededFenceIds = try {
                FenceObservationStore.prepareNativeDwellBaseline(
                    appContext,
                    inputAfterOppositePendingCancel.fenceIds,
                )
            } catch (error: Throwable) {
                runCatching {
                    SmartGeofenceLogger.w(
                        appContext,
                        TAG,
                        "Native DWELL baseline commit failed; continuing native callback path " +
                            "source=${input.source} ids=${input.fenceIds}.",
                        error,
                    )
                }
                return recordNativeDisposition(
                    appContext,
                    inputAfterOppositePendingCancel,
                    NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                )
            }
            withoutFenceIds(inputAfterOppositePendingCancel, seededFenceIds)
        } else {
            inputAfterOppositePendingCancel
        }
        if (inputForDirectDelivery.callbackParams.isEmpty()) {
            if (event == GeofenceEvent.DWELL &&
                inputForDirectDelivery.fenceIds.isEmpty()
            ) {
                if (!input.deliveryOwnership.tryCommitSmart()) {
                    return recordNativeDisposition(
                        appContext,
                        input,
                        NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                    )
                }
                input.onFinished(true)
                return recordNativeDisposition(
                    appContext,
                    input,
                    NativeEventDisposition.CALLBACK_OWNED,
                )
            }
            return recordNativeDisposition(
                appContext,
                inputForDirectDelivery,
                NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
            )
        }
        val filteredInput = if (event == GeofenceEvent.ENTER &&
            config.nativeEnterPayloadSanityEnabled
        ) {
            NativeEnterPayloadFilter.apply(appContext, inputForDirectDelivery, config)
        } else {
            inputForDirectDelivery
        }
        if (filteredInput.callbackParams.isEmpty()) {
            if (!input.deliveryOwnership.tryCommitSmart()) {
                return recordNativeDisposition(
                    appContext,
                    input,
                    NativeEventDisposition.CONTINUE_NATIVE_CALLBACK,
                )
            }
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Native event suppressed after payload sanity filtering " +
                    "event=${input.eventName} source=${input.source} ids=${input.fenceIds}."
            )
            input.onFinished(true)
            return recordNativeDisposition(
                appContext,
                input,
                NativeEventDisposition.CALLBACK_OWNED,
            )
        }
        val emitted = emitOwnedNative(appContext, filteredInput)
        val disposition = if (emitted) {
            NativeEventDisposition.CALLBACK_OWNED
        } else {
            NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
        }
        return recordNativeDisposition(appContext, input, disposition)
    }

    private fun seedUnknownFromAcceptedFusedFix(
        context: Context,
        location: Location,
        accuracyMeters: Double,
    ) {
        val baselines = FenceStore.getAll(context).mapNotNull { fence ->
            val center = Location("smart_geofence_baseline").apply {
                latitude = fence.latitude
                longitude = fence.longitude
            }
            conservativeFusedBaselineState(
                distanceMeters = location.distanceTo(center).toDouble(),
                accuracyMeters = accuracyMeters,
                radiusMeters = fence.radiusMeters,
            )?.let { fence.id to it }
        }.toMap(linkedMapOf())
        FenceObservationStore.seedUnknownFromAcceptedFusedFix(context, baselines)
    }

    private fun withoutFenceIds(
        input: NativeEventInput,
        excludedFenceIds: Set<String>,
    ): NativeEventInput {
        if (excludedFenceIds.isEmpty()) return input
        val callbackParams = input.callbackParams.mapNotNull { params ->
            val geofences = params.geofences.filterNot { it.id in excludedFenceIds }
            if (geofences.isEmpty()) {
                null
            } else {
                params.copy(
                    geofences = geofences,
                    callbackContextsByGeofenceId = params.callbackContextsByGeofenceId
                        ?.filterKeys { it !in excludedFenceIds },
                )
            }
        }
        return input.copy(
            fenceIds = input.fenceIds.filterNot { it in excludedFenceIds },
            callbackParams = callbackParams,
        )
    }

    fun emitDueNativeExitFallbacks(
        context: Context,
        launchExemption: WakeExemption = WakeExemption.NONE,
        onFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val appContext = context.applicationContext
        assertMainThread(appContext, "emitDueNativeExitFallbacks")
        return synchronized(transitionTransactionLock) {
            nativeTransitionWorkflow.emitDue(
                appContext,
                NativeTransitionDirection.EXIT,
                launchExemption,
                onFinished,
            )
        }
    }

    fun emitPendingNativeExitFallbacks(
        context: Context,
        fenceInstances: Map<String, String>,
        reason: String,
    ): Boolean {
        val appContext = context.applicationContext
        assertMainThread(appContext, "emitPendingNativeExitFallbacks")
        return synchronized(transitionTransactionLock) {
            nativeTransitionWorkflow.emitPending(
                appContext,
                NativeTransitionDirection.EXIT,
                fenceInstances,
                reason,
            )
        }
    }

    fun emitDueNativeEnterFallbacks(
        context: Context,
        launchExemption: WakeExemption = WakeExemption.NONE,
        onFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val appContext = context.applicationContext
        assertMainThread(appContext, "emitDueNativeEnterFallbacks")
        return synchronized(transitionTransactionLock) {
            nativeTransitionWorkflow.emitDue(
                appContext,
                NativeTransitionDirection.ENTER,
                launchExemption,
                onFinished,
            )
        }
    }

    fun emitPendingNativeEnterFallbacks(
        context: Context,
        fenceInstances: Map<String, String>,
        reason: String,
    ): Boolean {
        val appContext = context.applicationContext
        assertMainThread(appContext, "emitPendingNativeEnterFallbacks")
        return synchronized(transitionTransactionLock) {
            nativeTransitionWorkflow.emitPending(
                appContext,
                NativeTransitionDirection.ENTER,
                fenceInstances,
                reason,
            )
        }
    }

    private fun emitNativeFallback(
        context: Context,
        emission: NativeFallbackEmission,
    ): Boolean = when (emission.pending.direction) {
        NativeTransitionDirection.ENTER -> finalizeConfirmedObservation(
            context = context,
            fence = emission.fence,
            observedState = ObservedFenceState.INSIDE,
            transitionEventName = "enter",
            location = emission.location,
            isMock = emission.pending.isMock,
            source = emission.source,
            tailTracker = null,
            nativeEnterFallback = emission.pending.toPendingNativeEnter(),
            onEnqueueFinished = emission.onEnqueueFinished,
        )
        NativeTransitionDirection.EXIT -> finalizeConfirmedObservation(
            context = context,
            fence = emission.fence,
            observedState = ObservedFenceState.OUTSIDE,
            transitionEventName = "exit",
            location = emission.location,
            isMock = emission.pending.isMock,
            source = emission.source,
            tailTracker = null,
            nativeExitFallback = emission.pending.toPendingNativeExit(),
            onEnqueueFinished = emission.onEnqueueFinished,
        )
    }

    private fun PendingNativeTransition.toPendingNativeEnter(): PendingNativeEnter =
        PendingNativeEnter(
            fenceId = fenceId,
            source = source,
            createdAtMillis = createdAtMillis,
            triggeredAtMillis = triggeredAtMillis,
            deadlineAtMillis = deadlineAtMillis,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            isMock = isMock,
            eventMonotonicMillis = eventMonotonicMillis,
            androidBootCount = androidBootCount,
            timestampOrigin = timestampOrigin,
            deadlineAtElapsedRealtimeMillis = deadlineAtElapsedRealtimeMillis,
            deadlineBootCount = deadlineBootCount,
            deadlineStartedAtElapsedRealtimeMillis = deadlineStartedAtElapsedRealtimeMillis,
            deadlineStartedAtWallClockMillis = deadlineStartedAtWallClockMillis,
            traceId = traceId,
            instanceId = instanceId,
            validationRequired = validationRequired,
            candidateLocationTimeMillis = candidateLocationTimeMillis,
            candidateLocationElapsedRealtimeNanos = candidateLocationElapsedRealtimeNanos,
            fenceRadiusMeters = fenceRadiusMeters,
            confirmationBoundaryMeters = confirmationBoundaryMeters,
            minimumDelayMillis = minimumDelayMillis,
            validationConfigFingerprint = validationConfigFingerprint,
            nativeCandidate = nativeCandidate,
            eligibleAtMillis = eligibleAtMillis,
            eligibleAtElapsedRealtimeMillis = eligibleAtElapsedRealtimeMillis,
            eligibilityBootCount = eligibilityBootCount,
            eligibilityStartedAtElapsedRealtimeMillis =
                eligibilityStartedAtElapsedRealtimeMillis,
            eligibilityStartedAtWallClockMillis = eligibilityStartedAtWallClockMillis,
            confirmationNotBeforeMillis = confirmationNotBeforeMillis,
        )

    private fun PendingNativeTransition.toPendingNativeExit(): PendingNativeExit =
        PendingNativeExit(
            fenceId = fenceId,
            source = source,
            createdAtMillis = createdAtMillis,
            triggeredAtMillis = triggeredAtMillis,
            deadlineAtMillis = deadlineAtMillis,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            isMock = isMock,
            eventMonotonicMillis = eventMonotonicMillis,
            androidBootCount = androidBootCount,
            timestampOrigin = timestampOrigin,
            deadlineAtElapsedRealtimeMillis = deadlineAtElapsedRealtimeMillis,
            deadlineBootCount = deadlineBootCount,
            deadlineStartedAtElapsedRealtimeMillis = deadlineStartedAtElapsedRealtimeMillis,
            deadlineStartedAtWallClockMillis = deadlineStartedAtWallClockMillis,
            traceId = traceId,
            instanceId = instanceId,
            validationRequired = validationRequired,
            candidateLocationTimeMillis = candidateLocationTimeMillis,
            candidateLocationElapsedRealtimeNanos = candidateLocationElapsedRealtimeNanos,
            fenceRadiusMeters = fenceRadiusMeters,
            confirmationBoundaryMeters = confirmationBoundaryMeters,
            minimumDelayMillis = minimumDelayMillis,
            validationConfigFingerprint = validationConfigFingerprint,
            nativeCandidate = nativeCandidate,
            eligibleAtMillis = eligibleAtMillis,
            eligibleAtElapsedRealtimeMillis = eligibleAtElapsedRealtimeMillis,
            eligibilityBootCount = eligibilityBootCount,
            eligibilityStartedAtElapsedRealtimeMillis =
                eligibilityStartedAtElapsedRealtimeMillis,
            eligibilityStartedAtWallClockMillis = eligibilityStartedAtWallClockMillis,
            confirmationNotBeforeMillis = confirmationNotBeforeMillis,
        )
    private fun emitOwnedNative(
        context: Context,
        input: NativeEventInput,
    ): Boolean {
        if (!input.deliveryOwnership.canContinueSmart()) {
            input.onFinished(false)
            return false
        }
        val claimedAtMillis = System.currentTimeMillis()
        val emissions = when (
            val staged = DurableEventDeliveryCoordinator.stageSmartCallbacks(
                context,
                input.callbackParams,
                input.source,
                claimedAtMillis,
            )
        ) {
            SmartDeliveryStageResult.FullyDeduped -> {
                if (!input.deliveryOwnership.tryCommitSmart()) {
                    input.onFinished(false)
                    return false
                }
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Native event fully de-duped source=${input.source}.",
                )
                input.onFinished(true)
                return true
            }
            SmartDeliveryStageResult.PersistenceFailed -> {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Native callback outbox commit failed; continuing native callback path " +
                        "source=${input.source}.",
                )
                input.onFinished(false)
                return false
            }
            is SmartDeliveryStageResult.Staged -> staged.deliveries
        }

        if (!input.deliveryOwnership.tryCommitSmart()) {
            emissions.forEach { emission ->
                DurableEventDeliveryCoordinator.discard(context, emission.record.id)
            }
            input.onFinished(false)
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Discarded provisional native event outbox after bridge ownership expired " +
                    "source=${input.source} groups=${emissions.size}.",
            )
            return false
        }

        val lock = Object()
        var remaining = emissions.size
        var immediateDeliveryStarted = false
        fun finishOne() {
            synchronized(lock) {
                remaining -= 1
                if (remaining == 0) input.onFinished(true)
            }
        }
        emissions.forEach { emission ->
            try {
                check(input.deliveryOwnership.canContinueSmart()) {
                    "Native bridge ownership lost before dedup claim."
                }
                if (!DurableEventDeliveryCoordinator.claimSmartState(
                        context,
                        emission,
                        input.source,
                        claimedAtMillis,
                    )
                ) {
                    SmartGeofenceLogger.w(
                        context,
                        TAG,
                        "Native event outbox persisted but not every dedup claim committed; " +
                            "recovery will retry eventId=${emission.record.eventId}.",
                    )
                    finishOne()
                    return@forEach
                }
                check(input.deliveryOwnership.canContinueSmart()) {
                    "Native bridge ownership lost before observation commit."
                }
                FenceObservationStore.recordNativeEvent(
                    context,
                    emission.params.geofences.map { it.id },
                    emission.params.event.name,
                )
                val eventAtMillis = emission.params.eventAtMillis
                    ?.takeIf { it > 0L }
                    ?: claimedAtMillis
                val eventName = emission.params.event.name.lowercase()
                val timestampSource = smartCallbackTimestampSource(emission.params.eventAtMillis)
                emission.params.geofences.forEach { fence ->
                    val timing = input.eventTiming?.toSmartGeofenceEventTiming()?.takeIf {
                        it.wallClockEventAtMillis == eventAtMillis
                    } ?: SmartGeofenceEventTimingStore.captureNow(
                        context,
                        eventAtMillis,
                        timestampSource,
                    )
                    check(input.deliveryOwnership.canContinueSmart()) {
                        "Native bridge ownership lost before timing commit."
                    }
                    SmartGeofenceEventTimingStore.record(
                        context,
                        fence.id,
                        eventName,
                        timing,
                    )
                }
                recordNativeRawEmission(
                    context,
                    emission.params,
                    input.source,
                    result = "enqueue_requested",
                    enqueuedAtMillis = claimedAtMillis,
                )
                if (immediateDeliveryStarted || eventOutboxRecovery.isBusy) {
                    finishOne()
                    return@forEach
                }
                immediateDeliveryStarted = true
                check(input.deliveryOwnership.canContinueSmart()) {
                    "Native bridge ownership lost before outbox claim."
                }
                check(eventOutboxRecovery.claim(emission.record.id))
                check(input.deliveryOwnership.canContinueSmart()) {
                    "Native bridge ownership lost before callback enqueue."
                }
                DurableEventDeliveryCoordinator.enqueue(
                    context,
                    emission.record,
                    emission.params.geofences.map { it.id },
                    input.source,
                    ::runInTransitionTransaction,
                ) { enqueued, finalized ->
                    eventOutboxRecovery.release(emission.record.id)
                    recordNativeRawEmission(
                        context,
                        emission.params,
                        input.source,
                        result = if (enqueued) "enqueue_succeeded" else "enqueue_failed",
                        enqueuedAtMillis = claimedAtMillis,
                    )
                    if (finalized) {
                        recoverPendingEventOutboxLocked(context)
                    }
                    finishOne()
                }
            } catch (e: Throwable) {
                eventOutboxRecovery.release(emission.record.id)
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Native event remains durable in outbox after immediate enqueue failure " +
                        "eventId=${emission.record.eventId}: ${e.message}",
                    e,
                )
                finishOne()
            }
        }
        SmartGeofenceLogger.i(
            context,
            TAG,
            "Queued native event groups=${emissions.size} source=${input.source}."
        )
        return true
    }

    private fun recordNativeRawEmission(
        context: Context,
        params: GeofenceCallbackParamsWire,
        source: String,
        result: String,
        enqueuedAtMillis: Long,
    ) {
        val eventName = params.event.name.lowercase()
        val eventAtMillis = params.eventAtMillis?.takeIf { it > 0L }
        val callbackEventAtMillis = eventAtMillis ?: enqueuedAtMillis
        val timestampSource = smartCallbackTimestampSource(eventAtMillis)
        val latencyMillis = triggerToDeliveryLatencyMillis(enqueuedAtMillis, eventAtMillis)
        val deviceIdle = isDeviceIdleMode(context)
        params.geofences.forEach { fence ->
            SmartGeofenceDiagnostics.recordSmartCallback(
                context,
                fence.id,
                eventName,
                source,
                result = result,
                enqueuedAtMillis = enqueuedAtMillis,
                eventAtMillis = callbackEventAtMillis,
                timestampSource = timestampSource,
                deliveryPath = "native_raw",
                triggerToDeliveryLatencyMillis = latencyMillis,
                deviceIdleModeAtDelivery = deviceIdle,
                traceId = params.traceId,
                eventId = params.eventId,
            )
        }
    }

    private fun recordBoundaryDecision(
        context: Context,
        classification: ClassifiedFence,
        source: String,
        accuracyMeters: Double,
        isMock: Boolean,
        decision: String,
        queuedEvent: String? = null,
    ) {
        SmartGeofenceDiagnostics.recordBoundaryDecision(
            context,
            classification.fence.id,
            source,
            decision,
            classification.distanceMeters,
            classification.fence.radiusMeters,
            classification.edgeDistanceMeters,
            accuracyMeters,
            isMock,
            queuedEvent,
        )
    }

    private fun recordLocationResult(
        context: Context,
        input: LocationEventInput,
        result: LocationEventResult,
    ): LocationEventResult = result.also {
        SmartGeofenceDiagnostics.recordEventProcessor(
            context,
            inputType = "location",
            source = input.source,
            result = result.name.lowercase(),
            candidateCount = input.candidateFences.size,
        )
    }

    private fun recordNativeDisposition(
        context: Context,
        input: NativeEventInput,
        disposition: NativeEventDisposition,
    ): NativeEventDisposition = disposition.also {
        SmartGeofenceDiagnostics.recordEventProcessor(
            context,
            inputType = "native",
            source = input.source,
            result = disposition.name.lowercase(),
            candidateCount = input.fenceIds.size,
            traceId = input.traceId,
            eventId = input.callbackParams.firstOrNull()?.eventId,
        )
    }

    private fun assertMainThread(context: Context, where: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        SmartGeofenceLogger.e(
            context,
            TAG,
            "$where ran off the main thread (${Thread.currentThread().name}); the " +
                "dedup/observation transition needs main-thread serialization (see B3).",
        )
    }

    private fun eventFromName(eventName: String): GeofenceEvent? =
        when (eventName.lowercase()) {
            "enter" -> GeofenceEvent.ENTER
            "exit" -> GeofenceEvent.EXIT
            "dwell" -> GeofenceEvent.DWELL
            else -> null
        }

    private fun SmartGeofenceFence.hasCallbackTrigger(event: GeofenceEvent): Boolean =
        when (event) {
            GeofenceEvent.ENTER -> triggersEnter
            GeofenceEvent.EXIT -> triggersExit
            GeofenceEvent.DWELL -> triggersDwell
        }

    private fun activeGeofenceWire(
        context: Context,
        fence: SmartGeofenceFence,
    ): ActiveGeofenceWire {
        val callbackTriggers = buildList {
            if (fence.triggersEnter) add(GeofenceEvent.ENTER)
            if (fence.triggersExit) add(GeofenceEvent.EXIT)
            if (fence.triggersDwell) add(GeofenceEvent.DWELL)
        }
        val nativeFence = NativeGeofencePersistence.getGeofence(context, fence.id)
        if (nativeFence != null) {
            return ActiveGeofenceWires.fromGeofenceWire(nativeFence).copy(
                triggers = callbackTriggers,
            )
        }
        return ActiveGeofenceWire(
            id = fence.id,
            location = LocationWire(
                latitude = fence.latitude,
                longitude = fence.longitude,
                accuracyMeters = null,
                isMock = false,
            ),
            radiusMeters = fence.radiusMeters,
            triggers = callbackTriggers,
            androidSettings = null,
        )
    }

    private fun Location.toLocationWire(isMock: Boolean): LocationWire =
        LocationWire(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
            isMock = isMock,
        )

    private data class ClassifiedFence(
        val fence: SmartGeofenceFence,
        val distanceMeters: Double,
        val edgeDistanceMeters: Double,
        val boundaryPosition: BoundaryPosition,
    )
}
