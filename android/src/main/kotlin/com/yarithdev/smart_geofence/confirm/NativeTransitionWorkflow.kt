package com.yarithdev.smart_geofence.confirm

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.activity.ActivityMonitor
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.delivery.EventDedupStore
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.processing.FallbackEnqueueTracker
import com.yarithdev.smart_geofence.processing.NativeEventDisposition
import com.yarithdev.smart_geofence.processing.NativeEventInput
import com.yarithdev.smart_geofence.processing.nativeFallbackBlockedByPendingOppositeEvent
import com.yarithdev.smart_geofence.processing.nativeFallbackSupersedeDiagnostic
import com.yarithdev.smart_geofence.processing.nativeFallbackSupersededByOppositeEvent
import com.yarithdev.smart_geofence.processing.payloadLocation
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.store.FenceObservationClaim
import com.yarithdev.smart_geofence.store.FenceObservationStore
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.store.ObservedFenceState
import com.yarithdev.smart_geofence.store.SmartGeofenceFence
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator
import com.yarithdev.smart_geofence.transition.NativeTransitionDirection
import com.yarithdev.smart_geofence.transition.PendingNativeTransition
import com.yarithdev.smart_geofence.wake.WakeExemption

internal class NativeTransitionWorkflow(
    private val emitFallback: (Context, NativeFallbackEmission) -> Boolean,
) {
    fun emitDue(
        context: Context,
        direction: NativeTransitionDirection,
        launchExemption: WakeExemption = WakeExemption.NONE,
        onFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val due = NativeTransitionCoordinator.leaseDue(
            context,
            direction,
            captureAndroidMonotonicTime(context),
        )
        if (due.isEmpty()) {
            onFinished?.invoke(true)
            return false
        }
        val config = SmartGeofenceConfigStore.load(context)
        val validated = due.filter { it.validationRequired }
        val validationMaxAgeMillis = config.confirmQueueMaxAgeMillis
        val validationNowMillis = System.currentTimeMillis()
        val expiredValidation = validated.filter {
            isPendingTransitionValidationExpired(
                it,
                validationMaxAgeMillis,
                validationNowMillis,
            )
        }
        expiredValidation.forEach {
            resolve(context, it, "validated_transition_expired")
        }
        if (expiredValidation.isNotEmpty()) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Discarded ${expiredValidation.size} expired validated ${direction.name} " +
                    "transition(s); maxAge=${validationMaxAgeMillis}ms.",
            )
        }
        val activeValidation = validated - expiredValidation.toSet()
        val unsafeLegacy = if (sharedValidationEnabled(config, direction)) {
            due.filterNot { it.validationRequired }
        } else {
            emptyList()
        }
        unsafeLegacy.forEach {
            resolve(context, it, "legacy_pending_discarded_shared_validation_enabled")
        }
        val queued = enqueueDueValidation(
            context,
            direction,
            activeValidation,
            launchExemption,
        )
        val legacyFallbacks = due - validated.toSet() - unsafeLegacy.toSet()
        if (legacyFallbacks.isEmpty()) {
            onFinished?.invoke(true)
            return queued
        }
        return emitFallbacks(context, direction, legacyFallbacks, "fallback_due", onFinished) ||
            queued
    }

    fun emitPending(
        context: Context,
        direction: NativeTransitionDirection,
        fenceInstances: Map<String, String>,
        reason: String,
    ): Boolean {
        val pending = NativeTransitionCoordinator.leaseFenceInstances(
            context,
            direction,
            fenceInstances,
            reason,
        )
        if (pending.isEmpty()) return false
        val config = SmartGeofenceConfigStore.load(context)
        val mustDiscard = pending.filter {
            it.validationRequired || sharedValidationEnabled(config, direction)
        }
        mustDiscard.forEach { transition ->
            resolve(context, transition, "unvalidated_transition_expired:$reason")
        }
        val legacyFallbacks = pending - mustDiscard.toSet()
        return legacyFallbacks.isNotEmpty() &&
            emitFallbacks(context, direction, legacyFallbacks, reason)
    }

    fun handleCandidate(
        context: Context,
        direction: NativeTransitionDirection,
        input: NativeEventInput,
        config: SmartGeofenceConfig,
    ): NativeEventDisposition {
        val sharedValidationEnabled = sharedValidationEnabled(config, direction)
        var preparedClaims = emptyList<FenceObservationClaim>()
        var preparedFenceIds = emptyList<String>()
        try {
            if (!input.deliveryOwnership.canContinueSmart()) {
                return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
            }
            preparedClaims = prepareClaims(
                context,
                direction,
                input.fenceIds,
                commitBaseline = !sharedValidationEnabled,
            )
            preparedFenceIds = preparedClaims.map { it.fenceId }
            if (!input.deliveryOwnership.canContinueSmart()) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
            }

            val unavailableBeforeArm = direction.candidateCheckOrder ==
                NativeCandidateCheckOrder.BEFORE_ARM && !hasCandidates(context, direction)
            if (preparedClaims.isEmpty() || unavailableBeforeArm) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                if (!input.deliveryOwnership.tryCommitSmart()) {
                    return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
                }
                recordCandidateSuppressed(
                    context,
                    direction,
                    input,
                    if (preparedClaims.isEmpty()) "no_confirmation_claims" else direction.noCandidatesResult,
                    preparedClaims.size,
                )
                input.onFinished(true)
                return NativeEventDisposition.CALLBACK_OWNED
            }

            val fallbackDelayMillis = if (sharedValidationEnabled) {
                config.transitionValidationMinimumDelayMillis.coerceAtLeast(0L)
            } else {
                fallbackDelayMillis(config, direction)
            }
            val eventTiming = input.eventTiming ?: SmartGeofenceEventTimingStore.captureNow(
                context,
                input.triggeredAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
                direction.timestampOrigin,
            ).toEventTimingEvidence()
            val payloadLocation = input.payloadLocation()
            val candidateLocationTimeMillis = payloadLocation?.fixTimeMillis?.takeIf { it > 0L }
            val candidateLocationElapsedRealtimeNanos = payloadLocation?.elapsedRealtimeNanos
                ?.takeIf { it > 0L }
            val confirmationNotBeforeMillis = if (sharedValidationEnabled) {
                safeAddLong(
                    eventTiming.wallClockEventAtMillis.takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                    maxOf(
                        config.nativeConfirmDelayMillis.coerceAtLeast(0L),
                        config.transitionValidationMinimumDelayMillis.coerceAtLeast(0L),
                    ),
                )
            } else {
                null
            }
            if (!input.deliveryOwnership.canContinueSmart()) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
            }
            val fences = FenceStore.getAll(context).associateBy { it.id }
            val pulseOwnedBeforeArm = preparedFenceIds.filterTo(linkedSetOf()) { fenceId ->
                NativeTransitionCoordinator.pendingFor(context, direction, fenceId)?.let {
                    it.validationRequired && !it.nativeCandidate
                } == true
            }
            val pending = if (sharedValidationEnabled) {
                preparedFenceIds.mapNotNull { fenceId ->
                    val fence = fences[fenceId] ?: return@mapNotNull null
                    val confirmationBoundaryMeters = when (direction) {
                        NativeTransitionDirection.ENTER ->
                            fence.radiusMeters +
                                config.nativeEnterConfirmRadiusSlackMeters.coerceAtLeast(0.0)
                        NativeTransitionDirection.EXIT -> fence.radiusMeters
                    }
                    NativeTransitionCoordinator.arm(
                        context = context,
                        direction = direction,
                        fenceIds = listOf(fenceId),
                        source = input.source,
                        location = payloadLocation,
                        triggeredAtMillis = input.triggeredAtMillis,
                        delayMillis = fallbackDelayMillis,
                        eventTiming = eventTiming,
                        traceId = input.traceId,
                        validationRequired = true,
                        candidateLocationTimeMillis = candidateLocationTimeMillis,
                        candidateLocationElapsedRealtimeNanos =
                            candidateLocationElapsedRealtimeNanos,
                        fenceRadiusMeters = fence.radiusMeters,
                        confirmationBoundaryMeters = confirmationBoundaryMeters,
                        validationConfigFingerprint = sharedTransitionValidationFingerprint(
                            direction,
                            config,
                            fence.radiusMeters,
                        ),
                        nativeCandidate = true,
                        confirmationNotBeforeMillis = confirmationNotBeforeMillis,
                    ).singleOrNull()
                }
            } else {
                NativeTransitionCoordinator.arm(
                    context = context,
                    direction = direction,
                    fenceIds = preparedFenceIds,
                    source = input.source,
                    location = payloadLocation,
                    triggeredAtMillis = input.triggeredAtMillis,
                    delayMillis = fallbackDelayMillis,
                    eventTiming = eventTiming,
                    traceId = input.traceId,
                    nativeCandidate = true,
                )
            }
            val promotedFromPulse = pending.filter {
                it.fenceId in pulseOwnedBeforeArm && it.validationRequired && it.nativeCandidate
            }
            if (promotedFromPulse.isNotEmpty()) {
                val reason = "native_candidate_promoted"
                LocationConfirmManager.cancelPendingValidationPulseIfNoPending(context, reason)
                ProximityPulseController.onPendingTransitionChanged(context, reason)
                SmartGeofenceLogger.i(
                    context,
                    TAG,
                    "Native ${direction.name} candidate released Pulse ownership ids=" +
                        promotedFromPulse.joinToString(",") { it.fenceId } + ".",
                )
            }
            if (pending.size != preparedFenceIds.size) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                cancel(
                    context,
                    direction,
                    preparedFenceIds,
                    "incomplete_pending_persistence:${input.source}",
                )
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Native ${direction.name} fallback could not be persisted; continuing native callback " +
                        "source=${input.source} ids=${input.fenceIds}.",
                )
                if (sharedValidationEnabled && input.deliveryOwnership.tryCommitSmart()) {
                    input.onFinished(true)
                    return NativeEventDisposition.CALLBACK_OWNED
                }
                return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
            }
            if (!input.deliveryOwnership.canContinueSmart()) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                cancel(context, direction, preparedFenceIds, "bridge_ownership_expired:${input.source}")
                cancelConfirmationIfNoPending(context, direction, "bridge_ownership_expired")
                return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
            }

            val unavailableAfterArm = direction.candidateCheckOrder ==
                NativeCandidateCheckOrder.AFTER_ARM && !hasCandidates(context, direction)
            if (unavailableAfterArm) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                cancel(context, direction, preparedFenceIds, "${direction.noCandidatesResult}:${input.source}")
                if (!input.deliveryOwnership.tryCommitSmart()) {
                    return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
                }
                recordCandidateSuppressed(
                    context,
                    direction,
                    input,
                    direction.noCandidatesResult,
                    preparedClaims.size,
                )
                input.onFinished(true)
                return NativeEventDisposition.CALLBACK_OWNED
            }

            if (!input.deliveryOwnership.canContinueSmart()) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                cancel(context, direction, preparedFenceIds, "bridge_ownership_expired:${input.source}")
                cancelConfirmationIfNoPending(context, direction, "bridge_ownership_expired")
                return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
            }
            val queued = enqueueConfirmation(
                context,
                direction,
                input.source,
                pending,
                input.traceId,
            )
            if (!queued) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                if (!sharedValidationEnabled) {
                    cancel(context, direction, preparedFenceIds, "queue_failed:${input.source}")
                }
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Native ${direction.name} confirmation could not be queued; continuing native callback " +
                        "source=${input.source} ids=${input.fenceIds}.",
                )
                if (sharedValidationEnabled && input.deliveryOwnership.tryCommitSmart()) {
                    input.onFinished(true)
                    return NativeEventDisposition.CALLBACK_OWNED
                }
                return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
            }
            if (!input.deliveryOwnership.tryCommitSmart()) {
                FenceObservationStore.restoreIfCurrent(context, preparedClaims)
                cancel(context, direction, preparedFenceIds, "bridge_ownership_expired:${input.source}")
                cancelConfirmationIfNoPending(context, direction, "bridge_ownership_expired")
                return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
            }

            SmartGeofenceLogger.i(
                context,
                TAG,
                "native_${direction.eventName}_confirm_queued source=${input.source} " +
                    "ids=${input.fenceIds} prepared=${preparedClaims.size} " +
                    "pending=${pending.size} validationDelay=${fallbackDelayMillis}ms " +
                    "sharedValidation=$sharedValidationEnabled.",
            )
            SmartGeofenceDiagnostics.recordTrace(
                context = context,
                stage = "native_confirm_enqueued",
                reasonCode = "queued",
                traceId = input.traceId,
                eventId = input.callbackParams.firstOrNull()?.eventId,
                event = direction.eventName,
                source = input.source,
                extras = linkedMapOf(
                    "candidateCount" to preparedClaims.size,
                    "pendingFallbackCount" to pending.size,
                    "fallbackDelayMillis" to fallbackDelayMillis,
                ),
            )
            input.onFinished(true)
            return NativeEventDisposition.CALLBACK_OWNED
        } catch (error: Throwable) {
            if (input.deliveryOwnership.committedSmartDecision() != null) {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Native ${direction.name} confirmation failed after durable smart ownership; " +
                        "retaining the persisted recovery route source=${input.source}: ${error.message}",
                    error,
                )
                return NativeEventDisposition.CALLBACK_OWNED
            }
            FenceObservationStore.restoreIfCurrent(context, preparedClaims)
            cancel(
                context,
                direction,
                preparedFenceIds,
                "candidate_exception:${error.javaClass.simpleName}:${input.source}",
            )
            cancelConfirmationIfNoPending(context, direction, "candidate_exception")
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Native ${direction.name} confirmation failed before ownership; continuing native callback " +
                    "source=${input.source} ids=${input.fenceIds}: ${error.message}",
                error,
            )
            return NativeEventDisposition.CONTINUE_NATIVE_CALLBACK
        }
    }

    fun cancelOpposite(
        context: Context,
        direction: NativeTransitionDirection,
        input: NativeEventInput,
    ): NativeOppositeCancellation {
        val opposite = direction.opposite
        val cancelled = cancel(
            context,
            opposite,
            input.fenceIds,
            "native_${direction.eventName}:${input.source}",
        )
        if (cancelled.isEmpty()) return NativeOppositeCancellation(input, 0)
        if (!input.deliveryOwnership.canContinueSmart()) {
            cancelled.forEach { pending ->
                NativeTransitionCoordinator.restore(
                    context,
                    pending,
                    "bridge_ownership_expired",
                )
            }
            return NativeOppositeCancellation(input, 0)
        }

        val cancelledIds = cancelled.map { it.fenceId }.toSet()
        val legacyCancelledIds = cancelled
            .filterNot { it.validationRequired }
            .map { it.fenceId }
            .toSet()
        if (legacyCancelledIds.isNotEmpty()) {
            FenceObservationStore.recordNativeEvent(
                context,
                legacyCancelledIds,
                direction.eventName,
            )
        }
        cancelConfirmationIfNoPending(context, opposite, "native_${direction.eventName}")
        LocationConfirmManager.cancelPendingValidationPulseIfNoPending(
            context,
            "native_${direction.eventName}",
        )
        if (cancelled.any { it.validationRequired && !it.nativeCandidate }) {
            ProximityPulseController.onPendingTransitionChanged(
                context,
                "native_${direction.eventName}",
            )
        }
        val callbackParams = input.callbackParams.mapNotNull { params ->
            val geofences = params.geofences.filterNot { it.id in cancelledIds }
            if (geofences.isEmpty()) null else params.copy(geofences = geofences)
        }
        SmartGeofenceLogger.i(
            context,
            TAG,
            "Native ${direction.name} cancelled pending native ${opposite.name} fallback " +
                "ids=${cancelledIds.joinToString(",")} source=${input.source}.",
        )
        return NativeOppositeCancellation(
            input.copy(
                fenceIds = input.fenceIds.filterNot { it in cancelledIds },
                callbackParams = callbackParams,
            ),
            cancelled.size,
        )
    }

    private fun emitFallbacks(
        context: Context,
        direction: NativeTransitionDirection,
        pendingTransitions: List<PendingNativeTransition>,
        reason: String,
        onFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val fences = FenceStore.getAll(context).associateBy { it.id }
        val completion = FallbackEnqueueTracker(pendingTransitions.size, onFinished)
        val monotonicNow = captureAndroidMonotonicTime(context)
        var emittedAny = false
        pendingTransitions.forEach { pending ->
            check(pending.direction == direction) {
                "Leased ${pending.direction.name} fallback in ${direction.name} workflow"
            }
            if (pending.validationRequired ||
                sharedValidationEnabled(SmartGeofenceConfigStore.load(context), direction)
            ) {
                resolve(context, pending, "raw_fallback_blocked:$reason")
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Discarded unvalidated ${direction.name}; raw native fallback is blocked " +
                        "fence=${pending.fenceId} reason=$reason.",
                )
                completion.complete(true)
                return@forEach
            }
            val supersedingRecord = EventDedupStore.currentRecord(context, pending.fenceId)
            if (nativeFallbackBlockedByPendingOppositeEvent(
                    supersedingRecord,
                    direction.opposite.eventName,
                    pending.triggeredAtMillis,
                    monotonicNow,
                )
            ) {
                NativeTransitionCoordinator.restore(
                    context,
                    pending,
                    "opposite_${direction.opposite.eventName}_enqueue_pending",
                )
                SmartGeofenceLogger.i(
                    context,
                    TAG,
                    "Native ${direction.name} fallback deferred; newer ${direction.opposite.name} enqueue " +
                        "is not durable yet fence=${pending.fenceId} source=${pending.source}.",
                )
                completion.complete(true)
                return@forEach
            }
            if (nativeFallbackSupersededByOppositeEvent(
                    supersedingRecord,
                    direction.opposite.eventName,
                    pending.triggeredAtMillis,
                )
            ) {
                resolve(context, pending, "superseded_by_durable_${direction.opposite.eventName}")
                val supersedeDiagnostic = nativeFallbackSupersedeDiagnostic(
                    pendingEventName = direction.eventName,
                    pendingTriggeredAtMillis = pending.triggeredAtMillis,
                    oppositeEventName = direction.opposite.eventName,
                    oppositeDeliveredAtMillis = supersedingRecord?.deliveredAtMillis,
                    source = pending.source,
                    reason = reason,
                )
                SmartGeofenceDiagnostics.recordEventProcessor(
                    context,
                    inputType = "native_${direction.eventName}_fallback",
                    source = pending.source,
                    result = "fallback_superseded_by_${direction.opposite.eventName}",
                    candidateCount = 1,
                    traceId = pending.traceId,
                )
                SmartGeofenceLogger.i(
                    context,
                    TAG,
                    "Native ${direction.name} fallback dropped; newer ${direction.opposite.name} already " +
                        "delivered fence=${pending.fenceId} $supersedeDiagnostic.",
                )
                completion.complete(true)
                return@forEach
            }
            val fence = fences[pending.fenceId]
            if (fence == null) {
                resolve(context, pending, "fence_missing")
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Native ${direction.name} fallback skipped; fence missing id=${pending.fenceId} " +
                        "source=${pending.source}.",
                )
                completion.complete(true)
                return@forEach
            }

            val fallbackSource = "${direction.fallbackSource}:${pending.source}"
            var completionInvoked = false
            val emitted = try {
                emitFallback(
                    context,
                    NativeFallbackEmission(
                        pending = pending,
                        fence = fence,
                        source = fallbackSource,
                        location = pending.toLocation(),
                        onEnqueueFinished = { succeeded ->
                            completionInvoked = true
                            completion.complete(succeeded)
                        },
                    ),
                )
            } catch (error: Throwable) {
                NativeTransitionCoordinator.restore(
                    context,
                    pending,
                    "fallback_emit_failed:${error.javaClass.simpleName}",
                )
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Native ${direction.name} fallback failed before enqueue; restored pending " +
                        "${direction.eventName} fence=${pending.fenceId} source=$fallbackSource: ${error.message}",
                    error,
                )
                completion.complete(false)
                false
            }
            if (!emitted && !completionInvoked) completion.complete(true)
            if (emitted) {
                emittedAny = true
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "native_${direction.eventName}_fallback_emitted fence=${pending.fenceId} " +
                        "source=$fallbackSource deadline=${pending.deadlineAtMillis}.",
                )
            } else {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Native ${direction.name} fallback did not enqueue fence=${pending.fenceId} " +
                        "source=$fallbackSource.",
                )
            }
        }
        cancelConfirmationIfNoPending(context, direction, reason)
        return emittedAny
    }

    private fun recordCandidateSuppressed(
        context: Context,
        direction: NativeTransitionDirection,
        input: NativeEventInput,
        result: String,
        preparedCount: Int,
    ) {
        SmartGeofenceDiagnostics.recordEventProcessor(
            context,
            inputType = "native_${direction.eventName}_suppression",
            source = input.source,
            result = result,
            candidateCount = when {
                result == "no_confirmation_claims" -> input.fenceIds.size
                direction == NativeTransitionDirection.EXIT -> input.fenceIds.size
                else -> preparedCount
            },
            traceId = input.traceId,
            eventId = input.callbackParams.firstOrNull()?.eventId,
        )
        val message = when (direction) {
            NativeTransitionDirection.ENTER -> if (result == "no_confirmation_claims") {
                "Native ENTER suppressed; no outside smart fence needs confirmation " +
                    "source=${input.source} ids=${input.fenceIds}."
            } else {
                "Native ENTER suppressed; no smart fence needs inside confirmation " +
                    "source=${input.source} ids=${input.fenceIds} prepared=$preparedCount."
            }
            NativeTransitionDirection.EXIT ->
                "Native EXIT suppressed; no inside smart fence needs confirmation " +
                    "source=${input.source} ids=${input.fenceIds}."
        }
        SmartGeofenceLogger.d(context, TAG, message)
    }

    private fun prepareClaims(
        context: Context,
        direction: NativeTransitionDirection,
        fenceIds: Collection<String>,
        commitBaseline: Boolean,
    ): List<FenceObservationClaim> = when (direction) {
        NativeTransitionDirection.ENTER ->
            FenceObservationStore.prepareNativeEnterConfirmation(
                context,
                fenceIds,
                commitBaseline,
            )
        NativeTransitionDirection.EXIT ->
            FenceObservationStore.prepareNativeExitConfirmation(
                context,
                fenceIds,
                commitBaseline,
            )
    }

    private fun hasCandidates(context: Context, direction: NativeTransitionDirection): Boolean =
        when (direction) {
            NativeTransitionDirection.ENTER -> FusedLocationConfirm.hasInsideCandidates(context)
            NativeTransitionDirection.EXIT -> FusedLocationConfirm.hasOutsideCandidates(context)
        }

    private fun enqueueConfirmation(
        context: Context,
        direction: NativeTransitionDirection,
        source: String,
        pendingTransitions: List<PendingNativeTransition>,
        traceId: String?,
        launchExemption: WakeExemption = WakeExemption.GEOFENCE,
    ): Boolean {
        val fenceIds = pendingTransitions.map { it.fenceId }
        val fenceInstances = pendingTransitions.associate { it.fenceId to it.instanceId }
        return when (direction) {
            NativeTransitionDirection.ENTER -> LocationConfirmManager.enqueueInside(
                context,
                source = "${Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM}:$source",
                launchExemption = launchExemption,
                nativeFenceIds = fenceIds,
                nativeTransitionInstances = fenceInstances,
                traceId = traceId,
                confirmationNotBeforeMillis = pendingTransitions
                    .mapNotNull { it.confirmationNotBeforeMillis }
                    .minOrNull(),
            )
            NativeTransitionDirection.EXIT -> LocationConfirmManager.enqueueOutside(
                context,
                source = "${Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM}:$source",
                launchExemption = launchExemption,
                nativeFenceIds = fenceIds,
                nativeTransitionInstances = fenceInstances,
                traceId = traceId,
                confirmationNotBeforeMillis = pendingTransitions
                    .mapNotNull { it.confirmationNotBeforeMillis }
                    .minOrNull(),
            )
        }
    }

    private fun enqueueDueValidation(
        context: Context,
        direction: NativeTransitionDirection,
        pendingTransitions: List<PendingNativeTransition>,
        launchExemption: WakeExemption,
    ): Boolean {
        if (pendingTransitions.isEmpty()) return false
        var queued = false
        val native = pendingTransitions.filter { it.nativeCandidate }
        if (native.isNotEmpty()) {
            queued = enqueueConfirmation(
                context,
                direction,
                source = "pending_eligibility",
                pendingTransitions = native,
                traceId = native.firstNotNullOfOrNull { it.traceId },
                launchExemption = launchExemption,
            ) || queued
        }
        val pulsePending = pendingTransitions.filter { !it.nativeCandidate }
        if (pulsePending.isNotEmpty()) {
            queued = LocationConfirmManager.enqueueProximity(
                context,
                source = Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
                launchExemption = launchExemption,
                traceId = pendingTransitions.firstNotNullOfOrNull { it.traceId },
            ) || queued
        }
        return queued
    }

    private fun sharedValidationEnabled(
        config: SmartGeofenceConfig,
        direction: NativeTransitionDirection,
    ): Boolean = config.transitionValidationEnabled && when (direction) {
        NativeTransitionDirection.ENTER -> config.transitionValidationEnterEnabled
        NativeTransitionDirection.EXIT -> config.transitionValidationExitEnabled
    }

    private fun fallbackDelayMillis(
        config: SmartGeofenceConfig,
        direction: NativeTransitionDirection,
    ): Long = when (direction) {
        NativeTransitionDirection.ENTER -> nativeEnterFallbackDelayMillis(config)
        NativeTransitionDirection.EXIT -> nativeExitFallbackDelayMillis(config)
    }

    private fun cancel(
        context: Context,
        direction: NativeTransitionDirection,
        fenceIds: Collection<String>,
        reason: String,
    ): List<PendingNativeTransition> = NativeTransitionCoordinator.cancel(
        context,
        direction,
        fenceIds,
        reason,
    ).also { removed ->
        if (removed.any { it.validationRequired && !it.nativeCandidate }) {
            ProximityPulseController.onPendingTransitionChanged(context, reason)
        }
    }

    private fun resolve(
        context: Context,
        pending: PendingNativeTransition,
        reason: String,
    ) {
        if (NativeTransitionCoordinator.resolveIfCurrent(context, pending, reason) &&
            pending.validationRequired && !pending.nativeCandidate
        ) {
            ProximityPulseController.onPendingTransitionChanged(context, reason)
        }
    }

    private fun cancelConfirmationIfNoPending(
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

    private fun PendingNativeTransition.toLocation(): Location? {
        val lat = latitude ?: return null
        val lng = longitude ?: return null
        return Location("smart_geofence_native_${direction.eventName}_fallback").apply {
            latitude = lat
            longitude = lng
            time = triggeredAtMillis
            accuracyMeters
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { accuracy = it.toFloat() }
        }
    }

    private companion object {
        const val TAG = "SmartGeofenceEventProcessor"
        const val STATIONARY_VALIDATION_RETRY_MILLIS = 5L * 60L * 1_000L
    }
}

internal data class NativeFallbackEmission(
    val pending: PendingNativeTransition,
    val fence: SmartGeofenceFence,
    val source: String,
    val location: Location?,
    val onEnqueueFinished: (Boolean) -> Unit,
)

internal data class NativeOppositeCancellation(
    val input: NativeEventInput,
    val cancelledCount: Int,
)

internal enum class NativeCandidateCheckOrder {
    BEFORE_ARM,
    AFTER_ARM,
}

internal val NativeTransitionDirection.candidateCheckOrder: NativeCandidateCheckOrder
    get() = when (this) {
        NativeTransitionDirection.ENTER -> NativeCandidateCheckOrder.AFTER_ARM
        NativeTransitionDirection.EXIT -> NativeCandidateCheckOrder.BEFORE_ARM
    }

internal val NativeTransitionDirection.eventName: String
    get() = when (this) {
        NativeTransitionDirection.ENTER -> "enter"
        NativeTransitionDirection.EXIT -> "exit"
    }

internal val NativeTransitionDirection.opposite: NativeTransitionDirection
    get() = when (this) {
        NativeTransitionDirection.ENTER -> NativeTransitionDirection.EXIT
        NativeTransitionDirection.EXIT -> NativeTransitionDirection.ENTER
    }

private val NativeTransitionDirection.noCandidatesResult: String
    get() = when (this) {
        NativeTransitionDirection.ENTER -> "no_inside_candidates"
        NativeTransitionDirection.EXIT -> "no_outside_candidates"
    }

private val NativeTransitionDirection.fallbackSource: String
    get() = when (this) {
        NativeTransitionDirection.ENTER -> Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_FALLBACK
        NativeTransitionDirection.EXIT -> Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_FALLBACK
    }
