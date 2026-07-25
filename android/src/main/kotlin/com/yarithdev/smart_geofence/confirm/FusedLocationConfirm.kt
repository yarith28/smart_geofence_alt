package com.yarithdev.smart_geofence.confirm

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus
import com.yarithdev.smart_geofence.fused.FusedLocationLiveness
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.processing.LocationEventInput
import com.yarithdev.smart_geofence.processing.LocationEventMode
import com.yarithdev.smart_geofence.processing.LocationEventResult
import com.yarithdev.smart_geofence.mock.MockLocationPolicyGate
import com.yarithdev.smart_geofence.proximity.FusedBroadcastTailTracker
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseStateStore
import com.yarithdev.smart_geofence.store.FenceObservationStore
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.store.ObservedFenceState
import com.yarithdev.smart_geofence.store.SmartGeofenceFence
import com.yarithdev.smart_geofence.transition.NativeTransitionDirection
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator
import com.yarithdev.smart_geofence.transition.PendingNativeTransition

object FusedLocationConfirm {
    private const val TAG = "FusedLocationConfirm"
    private const val PROXIMITY_CONFIRM_PRIORITY =
        Constants.DEFAULT_LOCATION_PRIORITY_BALANCED_POWER_ACCURACY
    private const val OUTSIDE_CONFIRM_PRIORITY =
        Constants.DEFAULT_LOCATION_PRIORITY_HIGH_ACCURACY
    private const val INSIDE_CONFIRM_PRIORITY =
        Constants.DEFAULT_LOCATION_PRIORITY_BALANCED_POWER_ACCURACY

    private const val CONFIRM_DECISIVE_MARGIN_METERS = 25.0

    data class KnownLocationDecision(
        val hasProcessedObservation: Boolean,
        val needsFreshConfirm: Boolean,
        val hasEventFilterRejection: Boolean = false,
    ) {
        val canSkipFreshConfirm: Boolean
            get() = hasProcessedObservation && !needsFreshConfirm && !hasEventFilterRejection

        val eventFilterRejectedWithoutFreshConfirm: Boolean
            get() = hasEventFilterRejection && !needsFreshConfirm
    }

    data class LocationConfirmOutcome(
        val status: FusedCurrentLocationStatus,
        val reason: String,
        val failure: Throwable? = null,
    )

    private data class ConfirmFixDecision(
        val outcome: LocationConfirmOutcome? = null,
        val freshConfirmReason: String? = null,
    ) {
        companion object {
            val Accepted = ConfirmFixDecision()

            fun retryable(reason: String): ConfirmFixDecision =
                ConfirmFixDecision(
                    outcome = LocationConfirmOutcome(
                        status = FusedCurrentLocationStatus.FAILURE,
                        reason = reason,
                    ),
                )

            fun needsFreshConfirm(reason: String): ConfirmFixDecision =
                ConfirmFixDecision(freshConfirmReason = reason)
        }
    }

    fun confirmProximity(
        context: Context,
        source: String,
        traceId: String? = null,
        onComplete: (LocationConfirmOutcome) -> Unit,
    ) {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        val timeoutMillis = currentLocationTimeoutMillis(config, source)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Confirm proximity requested source=$source " +
                "priority=$PROXIMITY_CONFIRM_PRIORITY " +
                "timeout=${timeoutMillis}ms."
        )
        fetchConfirmLocation(
            appContext,
            source,
            PROXIMITY_CONFIRM_PRIORITY,
            config,
            successReason = "proximity_confirm_complete",
            traceId = traceId,
            freshOnly = isPulseSource(source),
            onComplete = onComplete,
        ) { location ->
            handleNearby(
                appContext,
                location,
                null,
                source,
                activeConfirmMaxAgeMillis(config),
                tailTracker = null,
            )
            ConfirmFixDecision.Accepted
        }
    }

    fun confirmOutside(
        context: Context,
        source: String,
        traceId: String? = null,
        nativeTransitionInstances: Map<String, String>? = null,
        onComplete: (LocationConfirmOutcome) -> Unit,
    ) {
        val appContext = context.applicationContext
        val nativeOwnership = nativeTransitionOwnershipForSource(
            source,
            NativeTransitionDirection.EXIT,
            nativeTransitionInstances,
        )
        val candidates = outsideCandidates(appContext, nativeOwnership)
        if (candidates.isEmpty()) {
            SmartGeofenceLogger.d(appContext, TAG, "No inside fences need outside confirmation (source=$source).")
            onComplete(
                LocationConfirmOutcome(
                    status = FusedCurrentLocationStatus.SUCCESS,
                    reason = "outside_confirm_skipped_no_candidates",
                )
            )
            return
        }
        val config = SmartGeofenceConfigStore.load(appContext)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Confirm outside requested source=$source candidates=${candidates.size} " +
                "ids=${candidates.joinToString(",") { it.id }} " +
                "priority=$OUTSIDE_CONFIRM_PRIORITY " +
                "timeout=${config.locationConfirmTimeoutMillis}ms."
        )
        fetchConfirmLocation(
            appContext,
            source,
            OUTSIDE_CONFIRM_PRIORITY,
            config,
            successReason = "outside_confirm_complete",
            traceId = traceId,
            onComplete = onComplete,
        ) { location ->
            handleOutside(
                appContext,
                location,
                source,
                activeConfirmMaxAgeMillis(config),
                nativeOwnership,
            )
        }
    }

    fun confirmInside(
        context: Context,
        source: String,
        traceId: String? = null,
        nativeTransitionInstances: Map<String, String>? = null,
        onComplete: (LocationConfirmOutcome) -> Unit,
    ) {
        val appContext = context.applicationContext
        val nativeOwnership = nativeTransitionOwnershipForSource(
            source,
            NativeTransitionDirection.ENTER,
            nativeTransitionInstances,
        )
        val candidates = insideCandidates(appContext, nativeOwnership)
        if (candidates.isEmpty()) {
            SmartGeofenceLogger.d(appContext, TAG, "No pending fences need inside confirmation (source=$source).")
            onComplete(
                LocationConfirmOutcome(
                    status = FusedCurrentLocationStatus.SUCCESS,
                    reason = "inside_confirm_skipped_no_candidates",
                )
            )
            return
        }
        val config = SmartGeofenceConfigStore.load(appContext)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Confirm inside requested source=$source candidates=${candidates.size} " +
                "ids=${candidates.joinToString(",") { it.id }} " +
                "priority=$INSIDE_CONFIRM_PRIORITY " +
                "timeout=${config.locationConfirmTimeoutMillis}ms " +
                "radiusSlack=${config.nativeEnterConfirmRadiusSlackMeters}m."
        )
        fetchConfirmLocation(
            appContext,
            source,
            INSIDE_CONFIRM_PRIORITY,
            config,
            successReason = "inside_confirm_complete",
            traceId = traceId,
            onComplete = onComplete,
        ) { location ->
            handleInside(
                appContext,
                location,
                source,
                activeConfirmMaxAgeMillis(config),
                nativeOwnership,
            )
        }
    }

    fun hasOutsideCandidates(context: Context): Boolean =
        outsideCandidates(context.applicationContext).isNotEmpty()

    fun hasInsideCandidates(context: Context): Boolean =
        insideCandidates(context.applicationContext).isNotEmpty()

    fun evaluateKnownLocation(
        context: Context,
        location: Location,
        seedFence: SmartGeofenceFence?,
        source: String,
        tailTracker: FusedBroadcastTailTracker? = null,
    ): KnownLocationDecision {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        val maxAgeMillis = if (source.contains("passive", ignoreCase = true)) {
            Constants.PASSIVE_LOCATION_MAX_EVIDENCE_AGE_MILLIS
        } else {
            config.proximityMaxWaitMillis
        }.coerceAtLeast(1L)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Evaluating known fix source=$source seedFence=${seedFence?.id} " +
                "${describeFix(location)}."
        )
        return handleNearby(appContext, location, seedFence, source, maxAgeMillis, tailTracker)
    }

    private fun handleNearby(
        context: Context,
        location: Location,
        seedFence: SmartGeofenceFence?,
        source: String,
        maxAgeMillis: Long,
        tailTracker: FusedBroadcastTailTracker?,
    ): KnownLocationDecision {
        val fences = FenceStore.getAll(context)
        if (fences.isEmpty()) {
            SmartGeofenceLogger.d(context, TAG, "No registered fences to confirm (source=$source).")
            return KnownLocationDecision(
                hasProcessedObservation = false,
                needsFreshConfirm = false,
            )
        }

        val config = SmartGeofenceConfigStore.load(context)
        val proximityRadius = config.proximityRadiusMeters
        val candidates = linkedMapOf<String, SmartGeofenceFence>()
        val allPending = NativeTransitionCoordinator.allPending(context)
        val pendingIds = proximityEvaluationPendingFenceIds(allPending)
        val nativePendingIds = allPending.asSequence()
            .filter { it.nativeCandidate }
            .mapTo(linkedSetOf()) { it.fenceId }
        val pulseTargetIds = ProximityPulseStateStore.load(context)?.let {
            it.proximityFenceIds + it.insideFenceIds
        }.orEmpty()

        if (seedFence != null && seedFence.id !in nativePendingIds) {
            fences.firstOrNull { it.id == seedFence.id }?.let { candidates[it.id] = it }
        }
        for (fence in fences) {
            if (fence.id !in nativePendingIds &&
                (fence.id in pendingIds || fence.id in pulseTargetIds ||
                    edgeDistance(location, fence) <= proximityRadius)
            ) {
                candidates[fence.id] = fence
            }
        }
        if (candidates.isEmpty()) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "No nearby fences to confirm; checking accepted fix for unknown baselines " +
                    "(source=$source).",
            )
        }
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Confirm candidates source=$source seedFence=${seedFence?.id} " +
                "count=${candidates.size} ids=${candidates.keys.joinToString(",")}."
        )
        return when (SmartGeofenceEventProcessor.processLocation(
            context,
            LocationEventInput(
                location = location,
                candidateFences = candidates.values.toList(),
                mode = LocationEventMode.PROXIMITY,
                source = source,
                maxAgeMillis = maxAgeMillis,
                tailTracker = tailTracker,
            ),
        )) {
            LocationEventResult.PROCESSED -> KnownLocationDecision(
                hasProcessedObservation = candidates.isNotEmpty(),
                needsFreshConfirm = false,
            )
            LocationEventResult.EVENT_FILTER_REJECTED -> KnownLocationDecision(
                hasProcessedObservation = false,
                needsFreshConfirm = false,
                hasEventFilterRejection = true,
            )
            LocationEventResult.MOCK_REJECTED -> KnownLocationDecision(
                hasProcessedObservation = false,
                needsFreshConfirm = false,
                hasEventFilterRejection = true,
            )
            LocationEventResult.NEEDS_FRESH_CONFIRM -> KnownLocationDecision(
                hasProcessedObservation = false,
                needsFreshConfirm = true,
            )
        }
    }

    private fun handleOutside(
        context: Context,
        location: Location,
        source: String,
        maxAgeMillis: Long,
        nativeTransitionInstances: Map<String, String>? = null,
    ): ConfirmFixDecision {
        val candidates = outsideCandidates(context, nativeTransitionInstances)
        if (candidates.isEmpty()) {
            SmartGeofenceLogger.d(context, TAG, "Outside confirm became unnecessary (source=$source).")
            return ConfirmFixDecision.Accepted
        }
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Outside confirm candidates source=$source count=${candidates.size} " +
                "ids=${candidates.joinToString(",") { it.id }}."
        )
        return nativeConfirmDecision(
            result = SmartGeofenceEventProcessor.processLocation(
                context,
                LocationEventInput(
                    location = location,
                    candidateFences = candidates,
                    mode = LocationEventMode.OUTSIDE,
                    source = source,
                    maxAgeMillis = maxAgeMillis,
                    nativeTransitionInstances = nativeTransitionInstances,
                ),
            ),
            prefix = "outside_confirm",
        )
    }

    private fun handleInside(
        context: Context,
        location: Location,
        source: String,
        maxAgeMillis: Long,
        nativeTransitionInstances: Map<String, String>? = null,
    ): ConfirmFixDecision {
        val candidates = insideCandidates(context, nativeTransitionInstances)
        if (candidates.isEmpty()) {
            SmartGeofenceLogger.d(context, TAG, "Inside confirm became unnecessary (source=$source).")
            return ConfirmFixDecision.Accepted
        }
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Inside confirm candidates source=$source count=${candidates.size} " +
                "ids=${candidates.joinToString(",") { it.id }}."
        )
        return nativeConfirmDecision(
            result = SmartGeofenceEventProcessor.processLocation(
                context,
                LocationEventInput(
                    location = location,
                    candidateFences = candidates,
                    mode = LocationEventMode.INSIDE,
                    source = source,
                    maxAgeMillis = maxAgeMillis,
                    nativeTransitionInstances = nativeTransitionInstances,
                ),
            ),
            prefix = "inside_confirm",
        )
    }

    private fun nativeConfirmDecision(
        result: LocationEventResult,
        prefix: String,
    ): ConfirmFixDecision =
        when (result) {
            LocationEventResult.PROCESSED -> ConfirmFixDecision.Accepted
            LocationEventResult.EVENT_FILTER_REJECTED ->
                ConfirmFixDecision.retryable("${prefix}_fix_rejected")
            LocationEventResult.MOCK_REJECTED ->
                ConfirmFixDecision.retryable("${prefix}_mock_rejected")
            LocationEventResult.NEEDS_FRESH_CONFIRM ->
                ConfirmFixDecision.needsFreshConfirm("${prefix}_needs_fresh_fix")
        }

    private fun outsideCandidates(
        context: Context,
        nativeTransitionInstances: Map<String, String>? = null,
    ): List<SmartGeofenceFence> {
        val pendingIds = NativeExitPendingStore.pendingFenceIds(context).toSet()
        return FenceStore.getAll(context).filter { fence ->
            (FenceObservationStore.currentState(context, fence.id) ==
                ObservedFenceState.INSIDE || fence.id in pendingIds) &&
                (nativeTransitionInstances == null ||
                    ownsNativeTransitionInstance(
                        nativeTransitionInstances,
                        fence.id,
                        NativeExitPendingStore.pendingFor(context, fence.id)?.instanceId,
                    ))
        }
    }

    private fun insideCandidates(
        context: Context,
        nativeTransitionInstances: Map<String, String>? = null,
    ): List<SmartGeofenceFence> {
        val pendingIds = NativeEnterPendingStore.pendingFenceIds(context).toSet()
        if (pendingIds.isEmpty()) return emptyList()
        return FenceStore.getAll(context).filter { fence ->
            pendingIds.contains(fence.id) &&
                (nativeTransitionInstances == null ||
                    ownsNativeTransitionInstance(
                        nativeTransitionInstances,
                        fence.id,
                        NativeEnterPendingStore.pendingFor(context, fence.id)?.instanceId,
                    ))
        }
    }

    private fun edgeDistance(location: Location, fence: SmartGeofenceFence): Double {
        val center = Location("smart_geofence").apply {
            latitude = fence.latitude
            longitude = fence.longitude
        }
        return location.distanceTo(center).toDouble() - fence.radiusMeters
    }

    private fun fetchConfirmLocation(
        context: Context,
        source: String,
        priority: String,
        config: SmartGeofenceConfig,
        successReason: String,
        traceId: String?,
        freshOnly: Boolean = false,
        onComplete: (LocationConfirmOutcome) -> Unit,
        onFix: (Location) -> ConfirmFixDecision,
    ) {
        if (freshOnly) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Bypassing lastLocation for pulse source=$source; maximumUpdateAge=0ms.",
            )
            fetchCurrentLocation(
                context,
                source,
                priority,
                config,
                successReason,
                traceId,
                onComplete,
                onFix,
                freshOnly = true,
            )
            return
        }
        val cachedTimeoutMillis = config.locationConfirmTimeoutMillis.coerceAtLeast(1L)
        FusedLocationManager.requestLastLocation(context, cachedTimeoutMillis) { cached ->
            val location = cached.location
            if (location != null &&
                cached.status == FusedCurrentLocationStatus.SUCCESS &&
                MockLocationPolicyGate.evaluateLocation(
                    context,
                    config,
                    location,
                    source,
                    where = "cached_confirm",
                ).rejected
            ) {
                SmartGeofenceDiagnostics.recordConfirmResult(
                    context,
                    source,
                    result = "cached_mock_rejected",
                    elapsedMillis = cached.elapsedMillis,
                    location = location,
                    traceId = traceId,
                )
                fetchCurrentLocation(
                    context,
                    source,
                    priority,
                    config,
                    successReason,
                    traceId,
                    onComplete,
                    onFix,
                )
                return@requestLastLocation
            }
            if (location != null &&
                cached.status == FusedCurrentLocationStatus.SUCCESS &&
                cachedFixDecides(context, location, config)
            ) {
                SmartGeofenceDiagnostics.recordConfirmResult(
                    context,
                    source,
                    result = "cached_decisive",
                    elapsedMillis = cached.elapsedMillis,
                    location = location,
                    traceId = traceId,
                )
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Cached fix decided confirm source=$source ${describeFix(location)}; " +
                        "skipped fresh request.",
                )
                try {
                    recordHealthyConfirmFix(context, location, source, config)
                    val decision = onFix(location)
                    if (decision.freshConfirmReason != null) {
                        SmartGeofenceLogger.d(
                            context,
                            TAG,
                            "Cached confirm fix requested fresh confirm source=$source " +
                                "reason=${decision.freshConfirmReason}.",
                        )
                        fetchCurrentLocation(
                            context,
                            source,
                            priority,
                            config,
                            successReason,
                            traceId,
                            onComplete,
                            onFix,
                        )
                        return@requestLastLocation
                    }
                    if (decision.outcome != null) {
                        onComplete(decision.outcome)
                        return@requestLastLocation
                    }
                    onComplete(
                        LocationConfirmOutcome(
                            status = FusedCurrentLocationStatus.SUCCESS,
                            reason = successReason,
                        )
                    )
                } catch (e: Throwable) {
                    SmartGeofenceLogger.w(
                        context,
                        TAG,
                        "Cached confirm processing failed source=$source: ${e.message}",
                        e,
                    )
                    onComplete(
                        LocationConfirmOutcome(
                            status = FusedCurrentLocationStatus.FAILURE,
                            reason = "processing_exception",
                            failure = e,
                        )
                    )
                }
                return@requestLastLocation
            }
            when (cached.status) {
                FusedCurrentLocationStatus.NULL_LOCATION ->
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "cached_null",
                        elapsedMillis = cached.elapsedMillis,
                        traceId = traceId,
                    )
                FusedCurrentLocationStatus.FAILURE ->
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "cached_failure",
                        elapsedMillis = cached.elapsedMillis,
                        failureMessage = cached.failure?.message,
                        traceId = traceId,
                    )
                FusedCurrentLocationStatus.TIMEOUT ->
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "cached_timeout",
                        elapsedMillis = cached.elapsedMillis,
                        traceId = traceId,
                    )
                FusedCurrentLocationStatus.PERMISSION_MISSING ->
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "cached_permission_missing",
                        elapsedMillis = cached.elapsedMillis,
                        traceId = traceId,
                    )
                FusedCurrentLocationStatus.SECURITY_EXCEPTION ->
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "cached_security_exception",
                        elapsedMillis = cached.elapsedMillis,
                        failureMessage = cached.failure?.message,
                        traceId = traceId,
                    )
                FusedCurrentLocationStatus.SUCCESS -> Unit
            }
            fetchCurrentLocation(
                context,
                source,
                priority,
                config,
                successReason,
                traceId,
                onComplete,
                onFix,
            )
        }
    }

    private fun cachedFixDecides(
        context: Context,
        location: Location,
        config: SmartGeofenceConfig,
    ): Boolean {
        val pendingValidation = NativeTransitionCoordinator.allPending(context)
            .filter { it.validationRequired }
        if (pendingValidation.any {
                !NativeTransitionCoordinator.isEligible(context, it) ||
                    !isDistinctPostEligibilityFix(context, location, it)
            }
        ) {
            return false
        }
        if (!location.hasAccuracy()) return false
        if (LocationQualityPolicy.rejectionReason(
                location,
                activeConfirmMaxAgeMillis(config),
                config.eventLocationMaxAccuracyMeters,
            ) != null
        ) {
            return false
        }
        val edgeDistances = FenceStore.getAll(context).map { edgeDistance(location, it) }
        return isFixDecisiveForBoundaries(
            edgeDistances,
            location.accuracy.toDouble(),
            CONFIRM_DECISIVE_MARGIN_METERS,
        )
    }

    private fun fetchCurrentLocation(
        context: Context,
        source: String,
        priority: String,
        config: SmartGeofenceConfig,
        successReason: String,
        traceId: String?,
        onComplete: (LocationConfirmOutcome) -> Unit,
        onFix: (Location) -> ConfirmFixDecision,
        freshOnly: Boolean = false,
    ) {
        val timeoutMillis = currentLocationTimeoutMillis(config, source)
        val requestedAtMillis = System.currentTimeMillis()
        SmartGeofenceDiagnostics.recordConfirmRequest(
            context,
            source,
            priority,
            timeoutMillis,
            requestedAtMillis,
            traceId = traceId,
        )
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Requesting current location source=$source " +
                "priority=$priority timeout=${timeoutMillis}ms."
        )
        FusedLocationManager.requestCurrentLocation(
            context,
            priority,
            timeoutMillis,
            maximumUpdateAgeMillis = 0L.takeIf { freshOnly },
        ) { result ->
            when (result.status) {
                FusedCurrentLocationStatus.SUCCESS -> {
                    val location = result.location
                    if (location != null) {
                        if (MockLocationPolicyGate.evaluateLocation(
                                context,
                                config,
                                location,
                                source,
                                where = "fresh_confirm",
                            ).rejected
                        ) {
                            SmartGeofenceDiagnostics.recordConfirmResult(
                                context,
                                source,
                                result = "mock_rejected",
                                elapsedMillis = result.elapsedMillis,
                                location = location,
                                traceId = traceId,
                            )
                            onComplete(
                                LocationConfirmOutcome(
                                    status = FusedCurrentLocationStatus.FAILURE,
                                    reason = "mock_location_rejected",
                                )
                            )
                            return@requestCurrentLocation
                        }
                        SmartGeofenceDiagnostics.recordConfirmResult(
                            context,
                            source,
                            result = "success",
                            elapsedMillis = result.elapsedMillis,
                            location = location,
                            traceId = traceId,
                        )
                        SmartGeofenceLogger.d(
                            context,
                            TAG,
                            "Confirm fix received source=$source ${describeFix(location)}."
                        )
                        try {
                            recordHealthyConfirmFix(context, location, source, config)
                            val decision = onFix(location)
                            if (decision.freshConfirmReason != null) {
                                onComplete(
                                    LocationConfirmOutcome(
                                        status = FusedCurrentLocationStatus.FAILURE,
                                        reason = decision.freshConfirmReason,
                                    )
                                )
                                return@requestCurrentLocation
                            }
                            if (decision.outcome != null) {
                                onComplete(decision.outcome)
                                return@requestCurrentLocation
                            }
                            onComplete(
                                LocationConfirmOutcome(
                                    status = FusedCurrentLocationStatus.SUCCESS,
                                    reason = successReason,
                                )
                            )
                        } catch (e: Throwable) {
                            SmartGeofenceLogger.w(
                                context,
                                TAG,
                                "Confirm processing failed source=$source: ${e.message}",
                                e,
                            )
                            onComplete(
                                LocationConfirmOutcome(
                                    status = FusedCurrentLocationStatus.FAILURE,
                                    reason = "processing_exception",
                                    failure = e,
                                )
                            )
                        }
                    } else {
                        onComplete(
                            LocationConfirmOutcome(
                                status = FusedCurrentLocationStatus.NULL_LOCATION,
                                reason = "null_location",
                            )
                        )
                    }
                }
                FusedCurrentLocationStatus.NULL_LOCATION -> {
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "null",
                        elapsedMillis = result.elapsedMillis,
                        traceId = traceId,
                    )
                    SmartGeofenceLogger.d(context, TAG, "Confirm returned null location (source=$source).")
                    onComplete(
                        LocationConfirmOutcome(
                            status = FusedCurrentLocationStatus.NULL_LOCATION,
                            reason = "null_location",
                        )
                    )
                }
                FusedCurrentLocationStatus.FAILURE -> {
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "failure",
                        elapsedMillis = result.elapsedMillis,
                        failureMessage = result.failure?.message,
                        traceId = traceId,
                    )
                    SmartGeofenceLogger.w(
                        context,
                        TAG,
                        "Confirm failed (source=$source): ${result.failure?.message}",
                        result.failure
                    )
                    onComplete(
                        LocationConfirmOutcome(
                            status = FusedCurrentLocationStatus.FAILURE,
                            reason = "location_failure",
                            failure = result.failure,
                        )
                    )
                }
                FusedCurrentLocationStatus.TIMEOUT -> {
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "timeout",
                        elapsedMillis = result.elapsedMillis,
                        traceId = traceId,
                    )
                    SmartGeofenceLogger.d(context, TAG, "Confirm timed out after ${timeoutMillis}ms (source=$source).")
                    onComplete(
                        LocationConfirmOutcome(
                            status = FusedCurrentLocationStatus.TIMEOUT,
                            reason = "timeout",
                        )
                    )
                }
                FusedCurrentLocationStatus.PERMISSION_MISSING -> {
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "permission_missing",
                        elapsedMillis = result.elapsedMillis,
                        traceId = traceId,
                    )
                    SmartGeofenceLogger.w(context, TAG, "Skipping confirm: location permission missing (source=$source).")
                    onComplete(
                        LocationConfirmOutcome(
                            status = FusedCurrentLocationStatus.PERMISSION_MISSING,
                            reason = "permission_missing",
                        )
                    )
                }
                FusedCurrentLocationStatus.SECURITY_EXCEPTION -> {
                    SmartGeofenceDiagnostics.recordConfirmResult(
                        context,
                        source,
                        result = "security_exception",
                        elapsedMillis = result.elapsedMillis,
                        failureMessage = result.failure?.message,
                        traceId = traceId,
                    )
                    SmartGeofenceLogger.w(
                        context,
                        TAG,
                        "Confirm SecurityException (source=$source): ${result.failure?.message}",
                        result.failure
                    )
                    onComplete(
                        LocationConfirmOutcome(
                            status = FusedCurrentLocationStatus.SECURITY_EXCEPTION,
                            reason = "security_exception",
                            failure = result.failure,
                        )
                    )
                }
            }
        }
    }

    private fun describeFix(location: Location): String {
        val provider = location.provider ?: "unknown"
        val accuracy = if (location.hasAccuracy()) "${location.accuracy.toInt()}m" else "unknown"
        val age = if (location.time > 0L) {
            "${(System.currentTimeMillis() - location.time).coerceAtLeast(0L)}ms"
        } else {
            "unknown"
        }
        return "provider=$provider acc=$accuracy age=$age " +
            "mock=${MockLocationPolicyGate.isMockLocation(location)}"
    }

    private fun activeConfirmMaxAgeMillis(config: SmartGeofenceConfig): Long =
        maxOf(
            30_000L,
            config.locationConfirmTimeoutMillis.coerceAtMost(Long.MAX_VALUE / 2L) * 2L,
        )

    internal fun isPulseSource(source: String): Boolean =
        source == Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM ||
            source == Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS

    internal fun currentLocationTimeoutMillis(
        config: SmartGeofenceConfig,
        source: String,
    ): Long = if (isPulseSource(source)) {
        Constants.PROXIMITY_CONFIRM_TIMEOUT_MILLIS
    } else {
        config.locationConfirmTimeoutMillis.coerceAtLeast(1L)
    }

    internal fun proximityEvaluationPendingFenceIds(
        pending: Collection<PendingNativeTransition>,
    ): Set<String> = pending.asSequence()
        .filter { it.validationRequired && !it.nativeCandidate }
        .mapTo(linkedSetOf()) { it.fenceId }

    private fun recordHealthyConfirmFix(
        context: Context,
        location: Location,
        source: String,
        config: SmartGeofenceConfig,
    ) {
        if (LocationQualityPolicy.rejectionReason(
                location,
                activeConfirmMaxAgeMillis(config),
                config.pulseLocationMaxAccuracyMeters,
            ) != null
        ) {
            return
        }
        if (MockLocationPolicyGate.evaluateLocation(
                context,
                config,
                location,
                source,
                where = "healthy_confirm",
            ).rejected
        ) {
            return
        }
        FusedLocationLiveness.recordHealthyFix(context, location, source)
        ProximityPulseController.onHealthyFusedFix(context)
    }
}

internal fun isFixDecisiveForBoundaries(
    edgeDistancesMeters: List<Double>,
    accuracyMeters: Double,
    marginMeters: Double,
): Boolean {
    if (accuracyMeters.isNaN() || accuracyMeters < 0.0) return false
    if (edgeDistancesMeters.isEmpty()) return false
    val threshold = accuracyMeters + marginMeters.coerceAtLeast(0.0)
    return edgeDistancesMeters.all { kotlin.math.abs(it) > threshold }
}
