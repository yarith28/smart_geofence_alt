package com.yarithdev.smart_geofence.transition

import android.content.Context
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.model.EventLocationEvidence
import com.yarithdev.smart_geofence.model.EventTimingEvidence
import com.yarithdev.smart_geofence.time.AndroidMonotonicTime
import com.yarithdev.smart_geofence.time.captureAndroidMonotonicTime
import com.yarithdev.smart_geofence.time.elapsedWallClockOriginsConsistent
import com.yarithdev.smart_geofence.time.monotonicDeadlineRemainingMillis
import java.util.UUID

internal fun reconcileValidationEligibility(
    pending: PendingNativeTransition,
    monotonicNow: AndroidMonotonicTime,
    wallClockNow: Long,
): PendingNativeTransition? {
    if (!pending.validationRequired) return pending
    val minimumDelayMillis = pending.minimumDelayMillis ?: return null
    if (minimumDelayMillis < 0L) return null
    val currentElapsedMillis = monotonicNow.elapsedRealtimeMillis ?: return null
    val startedElapsedMillis = pending.eligibilityStartedAtElapsedRealtimeMillis
    val startedWallClockMillis = pending.eligibilityStartedAtWallClockMillis
    val eligibleElapsedMillis = pending.eligibleAtElapsedRealtimeMillis
    val storedBootCount = pending.eligibilityBootCount
    val currentBootCount = monotonicNow.bootCount
    val timingShapeValid = startedElapsedMillis != null &&
        eligibleElapsedMillis != null &&
        startedElapsedMillis >= 0L &&
        eligibleElapsedMillis >= startedElapsedMillis &&
        currentElapsedMillis >= startedElapsedMillis
    val clockContinuous = when {
        storedBootCount != null && currentBootCount != null ->
            storedBootCount == currentBootCount && timingShapeValid
        !timingShapeValid || startedElapsedMillis == null || startedWallClockMillis == null ->
            false
        else -> elapsedWallClockOriginsConsistent(
            startedElapsedMillis,
            startedWallClockMillis,
            currentElapsedMillis,
            wallClockNow,
        )
    }
    if (clockContinuous) return pending

    val acquisitionDelayMillis = pending.confirmationNotBeforeMillis?.let { notBefore ->
        if (pending.triggeredAtMillis >= 0L && notBefore >= pending.triggeredAtMillis) {
            notBefore - pending.triggeredAtMillis
        } else {
            0L
        }
    }
    val rebasedEligibleAtMillis = safeTransitionAdd(wallClockNow, minimumDelayMillis)
    val rebasedEligibleAtElapsedRealtimeMillis = safeTransitionAdd(
        currentElapsedMillis,
        minimumDelayMillis,
    )
    return pending.copy(
        deadlineAtMillis = rebasedEligibleAtMillis,
        deadlineAtElapsedRealtimeMillis = rebasedEligibleAtElapsedRealtimeMillis,
        deadlineBootCount = currentBootCount,
        deadlineStartedAtElapsedRealtimeMillis = currentElapsedMillis,
        deadlineStartedAtWallClockMillis = wallClockNow,
        androidBootCount = pending.androidBootCount ?: storedBootCount,
        eligibleAtMillis = rebasedEligibleAtMillis,
        eligibleAtElapsedRealtimeMillis = rebasedEligibleAtElapsedRealtimeMillis,
        eligibilityBootCount = currentBootCount,
        eligibilityStartedAtElapsedRealtimeMillis = currentElapsedMillis,
        eligibilityStartedAtWallClockMillis = wallClockNow,
        confirmationNotBeforeMillis = acquisitionDelayMillis?.let {
            safeTransitionAdd(wallClockNow, it)
        },
    )
}

internal object NativeTransitionCoordinator {
    private const val RETRY_DELAY_MILLIS = 60_000L

    @Synchronized
    fun arm(
        context: Context,
        direction: NativeTransitionDirection,
        fenceIds: Collection<String>,
        source: String,
        location: EventLocationEvidence?,
        triggeredAtMillis: Long,
        delayMillis: Long,
        eventTiming: EventTimingEvidence,
        traceId: String? = null,
        validationRequired: Boolean = false,
        candidateLocationTimeMillis: Long? = null,
        candidateLocationElapsedRealtimeNanos: Long? = null,
        fenceRadiusMeters: Double? = null,
        confirmationBoundaryMeters: Double? = null,
        validationConfigFingerprint: String? = null,
        nativeCandidate: Boolean = false,
        confirmationNotBeforeMillis: Long? = null,
    ): List<PendingNativeTransition> {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val monotonicNow = captureAndroidMonotonicTime(appContext)
        val normalizedDelay = delayMillis.coerceAtLeast(0L)
        val requestedCandidateAtMillis = eventTiming.wallClockEventAtMillis
            .takeIf { it > 0L }
            ?: triggeredAtMillis.takeIf { it > 0L }
            ?: now
        val requestedCandidateElapsedRealtimeNanos = candidateLocationElapsedRealtimeNanos
            ?: eventTiming.eventMonotonicMillis?.let { it * 1_000_000L }
        val eligibilityStartedAtWallClockMillis = if (validationRequired) {
            requestedCandidateAtMillis
        } else {
            now
        }
        val eligibleAtMillis = safeTransitionAdd(
            eligibilityStartedAtWallClockMillis,
            normalizedDelay,
        )
        val eligibilityStartedAtElapsedRealtimeMillis = if (validationRequired) {
            candidateLocationElapsedRealtimeNanos?.div(1_000_000L)
                ?: eventTiming.eventMonotonicMillis
                ?: monotonicNow.elapsedRealtimeMillis
        } else {
            monotonicNow.elapsedRealtimeMillis
        }
        val eligibleAtElapsedRealtimeMillis = eligibilityStartedAtElapsedRealtimeMillis
            ?.let { safeTransitionAdd(it, normalizedDelay) }
        val eligibilityBootCount = if (validationRequired) {
            eventTiming.androidBootCount ?: monotonicNow.bootCount
        } else {
            monotonicNow.bootCount
        }
        val initialRemainingMillis = monotonicDeadlineRemainingMillis(
            eligibleAtElapsedRealtimeMillis,
            eligibilityBootCount,
            monotonicNow,
            eligibilityStartedAtElapsedRealtimeMillis,
            eligibilityStartedAtWallClockMillis,
            now,
        )
        val deadlineAtMillis = safeTransitionAdd(now, initialRemainingMillis)
        val deadlineAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis
            ?.let { safeTransitionAdd(it, initialRemainingMillis) }
        val pending = NativeTransitionStore.read(appContext)
        val candidateEvidenceUpdates = mutableListOf<Pair<PendingNativeTransition, String>>()
        val armed = fenceIds.distinct().filter { it.isNotBlank() }.map { fenceId ->
            val key = NativeTransitionKey(direction, fenceId)
            val current = pending[key]
            if (current != null &&
                current.validationRequired == validationRequired &&
                (!validationRequired ||
                    (current.fenceRadiusMeters == fenceRadiusMeters &&
                        current.minimumDelayMillis == normalizedDelay &&
                        current.validationConfigFingerprint == validationConfigFingerprint))
            ) {
                if (!validationRequired) return@map current
                val incomingIsEarlier = isEarlierTransitionCandidate(
                    current,
                    requestedCandidateAtMillis,
                    requestedCandidateElapsedRealtimeNanos,
                    eligibilityBootCount,
                )
                val preservedCandidateAtMillis = if (incomingIsEarlier) {
                    requestedCandidateAtMillis
                } else {
                    current.triggeredAtMillis
                }
                val currentAcquisitionDelayMillis = current.confirmationNotBeforeMillis?.let {
                    (it - current.triggeredAtMillis).coerceAtLeast(0L)
                }
                val requestedAcquisitionDelayMillis = confirmationNotBeforeMillis?.let {
                    (it - requestedCandidateAtMillis).coerceAtLeast(0L)
                }
                val preserved = if (incomingIsEarlier) {
                    current.copy(
                        source = source,
                        triggeredAtMillis = requestedCandidateAtMillis,
                        deadlineAtMillis = deadlineAtMillis,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        accuracyMeters = location?.accuracyMeters,
                        isMock = location?.isMock ?: false,
                        eventMonotonicMillis = candidateLocationElapsedRealtimeNanos
                            ?.div(1_000_000L)
                            ?: eventTiming.eventMonotonicMillis,
                        androidBootCount = eventTiming.androidBootCount,
                        timestampOrigin = eventTiming.timestampOrigin,
                        deadlineAtElapsedRealtimeMillis = deadlineAtElapsedRealtimeMillis,
                        deadlineBootCount = monotonicNow.bootCount,
                        deadlineStartedAtElapsedRealtimeMillis =
                            monotonicNow.elapsedRealtimeMillis,
                        deadlineStartedAtWallClockMillis = now,
                        traceId = traceId,
                        candidateLocationTimeMillis = candidateLocationTimeMillis,
                        candidateLocationElapsedRealtimeNanos =
                            candidateLocationElapsedRealtimeNanos,
                        eligibleAtMillis = eligibleAtMillis,
                        eligibleAtElapsedRealtimeMillis = eligibleAtElapsedRealtimeMillis,
                        eligibilityBootCount = eligibilityBootCount,
                        eligibilityStartedAtElapsedRealtimeMillis =
                            eligibilityStartedAtElapsedRealtimeMillis,
                        eligibilityStartedAtWallClockMillis =
                            eligibilityStartedAtWallClockMillis,
                    )
                } else {
                    current
                }
                val joinedAcquisitionDelayMillis = listOfNotNull(
                    currentAcquisitionDelayMillis,
                    requestedAcquisitionDelayMillis,
                ).minOrNull()
                val joinedConfirmationNotBeforeMillis = joinedAcquisitionDelayMillis?.let {
                    safeTransitionAdd(preservedCandidateAtMillis, it)
                }
                val joined = preserved.copy(
                    nativeCandidate = preserved.nativeCandidate || nativeCandidate,
                    confirmationNotBeforeMillis = joinedConfirmationNotBeforeMillis,
                )
                if (incomingIsEarlier) {
                    candidateEvidenceUpdates += joined to "earlier_candidate_joined"
                }
                return@map joined
            }
            PendingNativeTransition(
                direction = direction,
                fenceId = fenceId,
                source = source,
                createdAtMillis = now,
                triggeredAtMillis = requestedCandidateAtMillis,
                deadlineAtMillis = deadlineAtMillis,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracyMeters = location?.accuracyMeters,
                isMock = location?.isMock ?: false,
                eventMonotonicMillis = candidateLocationElapsedRealtimeNanos
                    ?.div(1_000_000L)
                    ?: eventTiming.eventMonotonicMillis,
                androidBootCount = eventTiming.androidBootCount,
                timestampOrigin = eventTiming.timestampOrigin,
                deadlineAtElapsedRealtimeMillis = deadlineAtElapsedRealtimeMillis,
                deadlineBootCount = monotonicNow.bootCount,
                deadlineStartedAtElapsedRealtimeMillis =
                    monotonicNow.elapsedRealtimeMillis,
                deadlineStartedAtWallClockMillis = now,
                traceId = traceId,
                instanceId = UUID.randomUUID().toString(),
                validationRequired = validationRequired,
                candidateLocationTimeMillis = candidateLocationTimeMillis,
                candidateLocationElapsedRealtimeNanos =
                    candidateLocationElapsedRealtimeNanos,
                fenceRadiusMeters = fenceRadiusMeters,
                confirmationBoundaryMeters = confirmationBoundaryMeters,
                minimumDelayMillis = normalizedDelay,
                validationConfigFingerprint = validationConfigFingerprint,
                nativeCandidate = nativeCandidate,
                eligibleAtMillis = eligibleAtMillis,
                eligibleAtElapsedRealtimeMillis = eligibleAtElapsedRealtimeMillis,
                eligibilityBootCount = eligibilityBootCount,
                eligibilityStartedAtElapsedRealtimeMillis =
                    eligibilityStartedAtElapsedRealtimeMillis,
                eligibilityStartedAtWallClockMillis = eligibilityStartedAtWallClockMillis,
                confirmationNotBeforeMillis = confirmationNotBeforeMillis,
            ).also {
                if (validationRequired) {
                    candidateEvidenceUpdates += it to "armed"
                }
            }
        }
        armed.forEach { pending[it.key] = it }
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                direction,
                NativeTransitionStore.read(appContext).values,
            )
            return emptyList()
        }
        NativeTransitionScheduler.reschedule(appContext, direction, pending.values)
        candidateEvidenceUpdates.forEach { (transition, reason) ->
            SmartGeofenceDiagnostics.recordTransitionCandidateEvidence(
                appContext,
                transition,
                reason,
            )
        }
        SmartGeofenceLogger.i(
            appContext,
            direction.logTag,
            "Armed/joined ${armed.size} pending ${direction.name} transition(s) " +
                "deadline=$deadlineAtMillis source=$source ids=${armed.joinToString(",") { it.fenceId }}.",
        )
        return armed
    }

    @Synchronized
    fun joinEarlierValidatedCandidateIfCurrent(
        context: Context,
        pendingTransition: PendingNativeTransition,
        source: String,
        location: EventLocationEvidence,
        triggeredAtMillis: Long,
        eventTiming: EventTimingEvidence,
        candidateLocationTimeMillis: Long?,
        candidateLocationElapsedRealtimeNanos: Long?,
    ): PendingNativeTransition? {
        val appContext = context.applicationContext
        val pending = NativeTransitionStore.read(appContext)
        val current = pending[pendingTransition.key] ?: return null
        if (current.instanceId != pendingTransition.instanceId ||
            !current.validationRequired
        ) {
            return null
        }

        val now = System.currentTimeMillis()
        val monotonicNow = captureAndroidMonotonicTime(appContext)
        val requestedCandidateAtMillis = eventTiming.wallClockEventAtMillis
            .takeIf { it > 0L }
            ?: triggeredAtMillis.takeIf { it > 0L }
            ?: now
        val requestedCandidateElapsedRealtimeNanos =
            candidateLocationElapsedRealtimeNanos
                ?: eventTiming.eventMonotonicMillis?.let { it * 1_000_000L }
        val requestedBootCount = eventTiming.androidBootCount ?: monotonicNow.bootCount
        if (!isEarlierTransitionCandidate(
                current,
                requestedCandidateAtMillis,
                requestedCandidateElapsedRealtimeNanos,
                requestedBootCount,
            )
        ) {
            return current
        }

        val delayMillis = current.minimumDelayMillis?.coerceAtLeast(0L) ?: return null
        val eligibilityStartedAtElapsedRealtimeMillis =
            candidateLocationElapsedRealtimeNanos?.div(1_000_000L)
                ?: eventTiming.eventMonotonicMillis
                ?: monotonicNow.elapsedRealtimeMillis
        val eligibleAtMillis = safeTransitionAdd(requestedCandidateAtMillis, delayMillis)
        val eligibleAtElapsedRealtimeMillis = eligibilityStartedAtElapsedRealtimeMillis
            ?.let { safeTransitionAdd(it, delayMillis) }
        val initialRemainingMillis = monotonicDeadlineRemainingMillis(
            eligibleAtElapsedRealtimeMillis,
            requestedBootCount,
            monotonicNow,
            eligibilityStartedAtElapsedRealtimeMillis,
            requestedCandidateAtMillis,
            now,
        )
        val acquisitionDelayMillis = current.confirmationNotBeforeMillis?.let {
            (it - current.triggeredAtMillis).coerceAtLeast(0L)
        }
        val updated = current.copy(
            source = source,
            triggeredAtMillis = requestedCandidateAtMillis,
            deadlineAtMillis = safeTransitionAdd(now, initialRemainingMillis),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracyMeters,
            isMock = location.isMock,
            eventMonotonicMillis = requestedCandidateElapsedRealtimeNanos
                ?.div(1_000_000L),
            androidBootCount = eventTiming.androidBootCount,
            timestampOrigin = eventTiming.timestampOrigin,
            deadlineAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis
                ?.let { safeTransitionAdd(it, initialRemainingMillis) },
            deadlineBootCount = monotonicNow.bootCount,
            deadlineStartedAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis,
            deadlineStartedAtWallClockMillis = now,
            candidateLocationTimeMillis = candidateLocationTimeMillis,
            candidateLocationElapsedRealtimeNanos =
                candidateLocationElapsedRealtimeNanos,
            eligibleAtMillis = eligibleAtMillis,
            eligibleAtElapsedRealtimeMillis = eligibleAtElapsedRealtimeMillis,
            eligibilityBootCount = requestedBootCount,
            eligibilityStartedAtElapsedRealtimeMillis =
                eligibilityStartedAtElapsedRealtimeMillis,
            eligibilityStartedAtWallClockMillis = requestedCandidateAtMillis,
            confirmationNotBeforeMillis = acquisitionDelayMillis?.let {
                safeTransitionAdd(requestedCandidateAtMillis, it)
            },
        )
        pending[updated.key] = updated
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                updated.direction,
                NativeTransitionStore.read(appContext).values,
            )
            return null
        }
        NativeTransitionScheduler.reschedule(appContext, updated.direction, pending.values)
        SmartGeofenceDiagnostics.recordTransitionCandidateEvidence(
            appContext,
            updated,
            "earlier_candidate_joined",
        )
        SmartGeofenceLogger.i(
            appContext,
            updated.direction.logTag,
            "Joined earlier fused ${updated.direction.name} candidate " +
                "id=${updated.fenceId} eventAt=${updated.triggeredAtMillis} source=$source.",
        )
        return updated
    }

    @Synchronized
    fun cancel(
        context: Context,
        direction: NativeTransitionDirection,
        fenceIds: Collection<String>,
        reason: String,
    ): List<PendingNativeTransition> {
        val appContext = context.applicationContext
        val ids = fenceIds.toSet()
        if (ids.isEmpty()) return emptyList()
        val pending = NativeTransitionStore.read(appContext)
        val removed = ids.mapNotNull { pending.remove(NativeTransitionKey(direction, it)) }
        if (removed.isEmpty()) return emptyList()
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                direction,
                NativeTransitionStore.read(appContext).values,
            )
            return emptyList()
        }
        NativeTransitionScheduler.reschedule(appContext, direction, pending.values)
        SmartGeofenceLogger.i(
            appContext,
            direction.logTag,
            "Cancelled ${removed.size} pending native ${direction.name} fallback(s) reason=$reason " +
                "ids=${removed.joinToString(",") { it.fenceId }}.",
        )
        return removed
    }

    @Synchronized
    fun restore(
        context: Context,
        pendingTransition: PendingNativeTransition,
        reason: String,
    ): Boolean {
        val appContext = context.applicationContext
        val pending = NativeTransitionStore.read(appContext)
        if (pending.containsKey(pendingTransition.key)) return false
        val now = System.currentTimeMillis()
        val monotonicNow = captureAndroidMonotonicTime(appContext)
        val restored = pendingTransition.copy(
            deadlineAtMillis = safeTransitionAdd(now, RETRY_DELAY_MILLIS),
            deadlineAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis
                ?.let { safeTransitionAdd(it, RETRY_DELAY_MILLIS) },
            deadlineBootCount = monotonicNow.bootCount,
            deadlineStartedAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis,
            deadlineStartedAtWallClockMillis = now,
        )
        pending[restored.key] = restored
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                restored.direction,
                NativeTransitionStore.read(appContext).values,
            )
            return false
        }
        NativeTransitionScheduler.reschedule(appContext, restored.direction, pending.values)
        SmartGeofenceLogger.w(
            appContext,
            restored.direction.logTag,
            "Restored pending native ${restored.direction.name} fallback reason=$reason " +
                "id=${restored.fenceId} retryAt=${restored.deadlineAtMillis}.",
        )
        return true
    }

    @Synchronized
    fun deferIfCurrent(
        context: Context,
        pendingTransition: PendingNativeTransition,
        delayMillis: Long,
        reason: String,
    ): Boolean {
        val appContext = context.applicationContext
        val pending = NativeTransitionStore.read(appContext)
        val current = pending[pendingTransition.key] ?: return false
        if (current.instanceId != pendingTransition.instanceId) return false
        val now = System.currentTimeMillis()
        val monotonicNow = captureAndroidMonotonicTime(appContext)
        val normalizedDelay = delayMillis.coerceAtLeast(0L)
        val deferred = current.copy(
            deadlineAtMillis = safeTransitionAdd(now, normalizedDelay),
            deadlineAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis
                ?.let { safeTransitionAdd(it, normalizedDelay) },
            deadlineBootCount = monotonicNow.bootCount,
            deadlineStartedAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis,
            deadlineStartedAtWallClockMillis = now,
        )
        pending[deferred.key] = deferred
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                deferred.direction,
                NativeTransitionStore.read(appContext).values,
            )
            return false
        }
        NativeTransitionScheduler.reschedule(appContext, deferred.direction, pending.values)
        SmartGeofenceLogger.d(
            appContext,
            deferred.direction.logTag,
            "Deferred pending ${deferred.direction.name} acquisition reason=$reason " +
                "id=${deferred.fenceId} retryAt=${deferred.deadlineAtMillis}.",
        )
        return true
    }

    @Synchronized
    fun resolveIfCurrent(
        context: Context,
        pendingTransition: PendingNativeTransition,
        reason: String,
    ): Boolean {
        val appContext = context.applicationContext
        val pending = NativeTransitionStore.read(appContext)
        val current = pending[pendingTransition.key] ?: return false
        if (current.instanceId != pendingTransition.instanceId) return false
        pending.remove(pendingTransition.key)
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                pendingTransition.direction,
                NativeTransitionStore.read(appContext).values,
            )
            return false
        }
        NativeTransitionScheduler.reschedule(
            appContext,
            pendingTransition.direction,
            pending.values,
        )
        SmartGeofenceLogger.i(
            appContext,
            pendingTransition.direction.logTag,
            "Resolved exact pending native ${pendingTransition.direction.name} fallback " +
                "reason=$reason id=${pendingTransition.fenceId} " +
                "instance=${pendingTransition.instanceId}.",
        )
        return true
    }

    @Synchronized
    fun leaseDue(
        context: Context,
        direction: NativeTransitionDirection,
        monotonicNow: AndroidMonotonicTime,
    ): List<PendingNativeTransition> {
        val appContext = context.applicationContext
        val pending = NativeTransitionStore.read(appContext)
        val wallClockNow = System.currentTimeMillis()
        val blockedValidationKeys = mutableSetOf<NativeTransitionKey>()
        val rebasedValidation = mutableListOf<PendingNativeTransition>()
        pending.values.filter {
            it.direction == direction && it.validationRequired
        }.forEach { item ->
            val reconciled = reconcileValidationEligibility(
                item,
                monotonicNow,
                wallClockNow,
            )
            if (reconciled == null) {
                blockedValidationKeys += item.key
            } else if (reconciled != item) {
                pending[item.key] = reconciled
                rebasedValidation += reconciled
            }
        }
        if (rebasedValidation.isNotEmpty()) {
            if (!NativeTransitionStore.persist(appContext, pending.values)) {
                NativeTransitionScheduler.reschedule(
                    appContext,
                    direction,
                    NativeTransitionStore.read(appContext).values,
                )
                return emptyList()
            }
            rebasedValidation.forEach {
                SmartGeofenceDiagnostics.recordTransitionCandidateEvidence(
                    appContext,
                    it,
                    "eligibility_rebased_clock_discontinuity",
                )
            }
            SmartGeofenceLogger.w(
                appContext,
                direction.logTag,
                "Rebased ${rebasedValidation.size} validated ${direction.name} transition " +
                    "deadline(s) after a boot/clock discontinuity; full minimum delay reapplied.",
            )
        }
        val due = pending.values.filter {
            it.direction == direction &&
                it.key !in blockedValidationKeys &&
                monotonicDeadlineRemainingMillis(
                    it.deadlineAtElapsedRealtimeMillis,
                    it.deadlineBootCount,
                    monotonicNow,
                    it.deadlineStartedAtElapsedRealtimeMillis,
                    it.deadlineStartedAtWallClockMillis,
                    wallClockNow,
                ) == 0L
        }
        if (due.isEmpty()) {
            NativeTransitionScheduler.reschedule(appContext, direction, pending.values)
            return emptyList()
        }
        val retryStartedAtMillis = wallClockNow
        val retryAtMillis = safeTransitionAdd(retryStartedAtMillis, RETRY_DELAY_MILLIS)
        val retryAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis
            ?.let { safeTransitionAdd(it, RETRY_DELAY_MILLIS) }
        due.forEach { item ->
            pending[item.key] = item.copy(
                deadlineAtMillis = retryAtMillis,
                deadlineAtElapsedRealtimeMillis = retryAtElapsedRealtimeMillis,
                deadlineBootCount = monotonicNow.bootCount,
                deadlineStartedAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis,
                deadlineStartedAtWallClockMillis = retryStartedAtMillis,
            )
        }
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                direction,
                NativeTransitionStore.read(appContext).values,
            )
            return emptyList()
        }
        NativeTransitionScheduler.reschedule(appContext, direction, pending.values)
        SmartGeofenceLogger.w(
            appContext,
            direction.logTag,
            "Leased ${due.size} due native ${direction.name} fallback(s) until enqueue completes " +
                "ids=${due.joinToString(",") { it.fenceId }}.",
        )
        return due
    }

    @Synchronized
    fun leaseAll(
        context: Context,
        direction: NativeTransitionDirection,
        reason: String,
    ): List<PendingNativeTransition> {
        val appContext = context.applicationContext
        val pending = NativeTransitionStore.read(appContext)
        val leased = pending.values.filter { it.direction == direction }
        if (leased.isEmpty()) {
            NativeTransitionScheduler.reschedule(appContext, direction, pending.values)
            return emptyList()
        }
        val monotonicNow = captureAndroidMonotonicTime(appContext)
        val retryStartedAtMillis = System.currentTimeMillis()
        val retryAtMillis = safeTransitionAdd(retryStartedAtMillis, RETRY_DELAY_MILLIS)
        val retryAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis
            ?.let { safeTransitionAdd(it, RETRY_DELAY_MILLIS) }
        leased.forEach { item ->
            pending[item.key] = item.copy(
                deadlineAtMillis = retryAtMillis,
                deadlineAtElapsedRealtimeMillis = retryAtElapsedRealtimeMillis,
                deadlineBootCount = monotonicNow.bootCount,
                deadlineStartedAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis,
                deadlineStartedAtWallClockMillis = retryStartedAtMillis,
            )
        }
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                direction,
                NativeTransitionStore.read(appContext).values,
            )
            return emptyList()
        }
        NativeTransitionScheduler.reschedule(appContext, direction, pending.values)
        SmartGeofenceLogger.w(
            appContext,
            direction.logTag,
            "Leased ${leased.size} pending native ${direction.name} fallback(s) reason=$reason " +
                "ids=${leased.joinToString(",") { it.fenceId }}.",
        )
        return leased
    }

    @Synchronized
    fun leaseFenceInstances(
        context: Context,
        direction: NativeTransitionDirection,
        fenceInstances: Map<String, String>,
        reason: String,
    ): List<PendingNativeTransition> {
        val appContext = context.applicationContext
        val represented = fenceInstances.filter { (fenceId, instanceId) ->
            fenceId.isNotBlank() && instanceId.isNotBlank()
        }
        val pending = NativeTransitionStore.read(appContext)
        val leased = pending.values.filter {
            it.direction == direction && represented[it.fenceId] == it.instanceId
        }
        if (leased.isEmpty()) {
            NativeTransitionScheduler.reschedule(appContext, direction, pending.values)
            return emptyList()
        }
        val monotonicNow = captureAndroidMonotonicTime(appContext)
        val retryStartedAtMillis = System.currentTimeMillis()
        val retryAtMillis = safeTransitionAdd(retryStartedAtMillis, RETRY_DELAY_MILLIS)
        val retryAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis
            ?.let { safeTransitionAdd(it, RETRY_DELAY_MILLIS) }
        leased.forEach { item ->
            pending[item.key] = item.copy(
                deadlineAtMillis = retryAtMillis,
                deadlineAtElapsedRealtimeMillis = retryAtElapsedRealtimeMillis,
                deadlineBootCount = monotonicNow.bootCount,
                deadlineStartedAtElapsedRealtimeMillis = monotonicNow.elapsedRealtimeMillis,
                deadlineStartedAtWallClockMillis = retryStartedAtMillis,
            )
        }
        if (!NativeTransitionStore.persist(appContext, pending.values)) {
            NativeTransitionScheduler.reschedule(
                appContext,
                direction,
                NativeTransitionStore.read(appContext).values,
            )
            return emptyList()
        }
        NativeTransitionScheduler.reschedule(appContext, direction, pending.values)
        SmartGeofenceLogger.w(
            appContext,
            direction.logTag,
            "Leased ${leased.size} lane-owned native ${direction.name} fallback(s) reason=$reason " +
                "ids=${leased.joinToString(",") { it.fenceId }}.",
        )
        return leased
    }

    @Synchronized
    fun pendingFor(
        context: Context,
        direction: NativeTransitionDirection,
        fenceId: String,
    ): PendingNativeTransition? = NativeTransitionStore.read(context.applicationContext)[
        NativeTransitionKey(direction, fenceId)
    ]

    private fun isEarlierTransitionCandidate(
        current: PendingNativeTransition,
        requestedCandidateAtMillis: Long,
        requestedCandidateElapsedRealtimeNanos: Long?,
        requestedBootCount: Long?,
    ): Boolean {
        val currentCandidateElapsedRealtimeNanos =
            current.candidateLocationElapsedRealtimeNanos
                ?: current.eventMonotonicMillis?.let { it * 1_000_000L }
        val currentBootCount = current.androidBootCount ?: current.eligibilityBootCount
        if (currentBootCount != null && requestedBootCount != null) {
            if (currentBootCount != requestedBootCount) return false
            return currentCandidateElapsedRealtimeNanos != null &&
                requestedCandidateElapsedRealtimeNanos != null &&
                requestedCandidateElapsedRealtimeNanos < currentCandidateElapsedRealtimeNanos
        }

        val currentElapsedNanos = currentCandidateElapsedRealtimeNanos ?: return false
        val requestedElapsedNanos = requestedCandidateElapsedRealtimeNanos ?: return false
        val originsConsistent = elapsedWallClockOriginsConsistent(
            currentElapsedNanos / 1_000_000L,
            current.triggeredAtMillis,
            requestedElapsedNanos / 1_000_000L,
            requestedCandidateAtMillis,
        )
        return originsConsistent && requestedElapsedNanos < currentElapsedNanos
    }

    @Synchronized
    fun isEligible(context: Context, pending: PendingNativeTransition): Boolean {
        val appContext = context.applicationContext
        val stored = NativeTransitionStore.read(appContext)
        val current = stored[pending.key] ?: return false
        if (current.instanceId != pending.instanceId) return false
        val monotonicNow = captureAndroidMonotonicTime(appContext)
        val wallClockNow = System.currentTimeMillis()
        val reconciled = reconcileValidationEligibility(
            current,
            monotonicNow,
            wallClockNow,
        ) ?: return false
        if (reconciled != current) {
            stored[reconciled.key] = reconciled
            if (!NativeTransitionStore.persist(appContext, stored.values)) {
                NativeTransitionScheduler.reschedule(
                    appContext,
                    reconciled.direction,
                    NativeTransitionStore.read(appContext).values,
                )
                return false
            }
            NativeTransitionScheduler.reschedule(
                appContext,
                reconciled.direction,
                stored.values,
            )
            SmartGeofenceDiagnostics.recordTransitionCandidateEvidence(
                appContext,
                reconciled,
                "eligibility_rebased_clock_discontinuity",
            )
            SmartGeofenceLogger.w(
                appContext,
                reconciled.direction.logTag,
                "Rebased validated ${reconciled.direction.name} transition eligibility after " +
                    "a boot/clock discontinuity; full minimum delay reapplied " +
                    "id=${reconciled.fenceId} instance=${reconciled.instanceId}.",
            )
        }
        return monotonicDeadlineRemainingMillis(
            reconciled.eligibleAtElapsedRealtimeMillis,
            reconciled.eligibilityBootCount,
            monotonicNow,
            reconciled.eligibilityStartedAtElapsedRealtimeMillis,
            reconciled.eligibilityStartedAtWallClockMillis,
            wallClockNow,
        ) == 0L
    }

    @Synchronized
    fun count(context: Context, direction: NativeTransitionDirection): Int =
        NativeTransitionStore.read(context.applicationContext).values.count { it.direction == direction }

    @Synchronized
    fun pendingFenceIds(context: Context, direction: NativeTransitionDirection): List<String> =
        NativeTransitionStore.read(context.applicationContext).values
            .asSequence()
            .filter { it.direction == direction }
            .map { it.fenceId }
            .sorted()
            .toList()

    @Synchronized
    fun allPending(context: Context): List<PendingNativeTransition> =
        NativeTransitionStore.read(context.applicationContext).values.toList()

    @Synchronized
    fun clear(context: Context, reason: String): List<PendingNativeTransition> {
        val appContext = context.applicationContext
        val pending = NativeTransitionStore.read(appContext)
        if (pending.isEmpty()) return emptyList()
        if (!NativeTransitionStore.persist(appContext, emptyList())) return emptyList()
        NativeTransitionDirection.entries.forEach { direction ->
            NativeTransitionScheduler.reschedule(appContext, direction, emptyList())
        }
        SmartGeofenceLogger.i(
            appContext,
            "NativeTransitionStore",
            "Cleared ${pending.size} pending transition(s) reason=$reason.",
        )
        return pending.values.toList()
    }

    @Synchronized
    fun pendingDiagnostics(
        context: Context,
        direction: NativeTransitionDirection,
    ): List<Map<String, Any?>> = NativeTransitionStore.read(context.applicationContext).values
        .filter { it.direction == direction }
        .sortedBy { it.fenceId }
        .map { pending ->
            linkedMapOf(
                "fenceId" to pending.fenceId,
                "source" to pending.source,
                "createdAtMillis" to pending.createdAtMillis,
                "triggeredAtMillis" to pending.triggeredAtMillis,
                "traceId" to pending.traceId,
                "instanceId" to pending.instanceId,
                "deadlineAtMillis" to pending.deadlineAtMillis,
                "deadlineAtElapsedRealtimeMillis" to pending.deadlineAtElapsedRealtimeMillis,
                "deadlineBootCount" to pending.deadlineBootCount,
                "deadlineStartedAtElapsedRealtimeMillis" to
                    pending.deadlineStartedAtElapsedRealtimeMillis,
                "deadlineStartedAtWallClockMillis" to
                    pending.deadlineStartedAtWallClockMillis,
                "hasLocation" to (pending.latitude != null && pending.longitude != null),
                "accuracyMeters" to pending.accuracyMeters,
                "isMock" to pending.isMock,
                "eventMonotonicMillis" to pending.eventMonotonicMillis,
                "androidBootCount" to pending.androidBootCount,
                "timestampOrigin" to pending.timestampOrigin,
                "validationRequired" to pending.validationRequired,
                "candidateLocationTimeMillis" to pending.candidateLocationTimeMillis,
                "candidateLocationElapsedRealtimeNanos" to
                    pending.candidateLocationElapsedRealtimeNanos,
                "fenceRadiusMeters" to pending.fenceRadiusMeters,
                "confirmationBoundaryMeters" to pending.confirmationBoundaryMeters,
                "minimumDelayMillis" to pending.minimumDelayMillis,
                "validationConfigFingerprint" to pending.validationConfigFingerprint,
                "nativeCandidate" to pending.nativeCandidate,
                "eligibleAtMillis" to pending.eligibleAtMillis,
                "eligibleAtElapsedRealtimeMillis" to pending.eligibleAtElapsedRealtimeMillis,
                "eligibilityBootCount" to pending.eligibilityBootCount,
                "confirmationNotBeforeMillis" to pending.confirmationNotBeforeMillis,
            )
        }

    @Synchronized
    fun reschedule(context: Context, direction: NativeTransitionDirection) {
        val appContext = context.applicationContext
        NativeTransitionScheduler.reschedule(
            appContext,
            direction,
            NativeTransitionStore.read(appContext).values,
        )
    }

    @Synchronized
    fun pendingIntentExists(context: Context, direction: NativeTransitionDirection): Boolean =
        NativeTransitionScheduler.pendingIntentExists(context, direction)
}
