package com.yarithdev.smart_geofence.proximitypulse

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yarithdev.smart_geofence.activity.ActivityMonitor
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.confirm.LocationConfirmManager
import com.yarithdev.smart_geofence.core.AndroidPackageManagerCompat
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.fused.FusedLocationLiveness
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.proximity.ProximityAlarmKind
import com.yarithdev.smart_geofence.proximity.ProximityAlarmReceiver
import com.yarithdev.smart_geofence.proximity.ProximityAlarmScheduler
import com.yarithdev.smart_geofence.store.FenceObservationStore
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.store.ObservedFenceState
import com.yarithdev.smart_geofence.store.SmartGeofenceFence
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator
import com.yarithdev.smart_geofence.transition.NativeTransitionDirection
import com.yarithdev.smart_geofence.transition.PendingNativeTransition
import com.yarithdev.smart_geofence.wake.WakeExemption

internal enum class PulseStopReason(
    val code: String,
    val cancelBoundaryWork: Boolean = false,
    val cancelSharedAlarm: Boolean = false,
) {
    STOPPED("stopped"),
    DISABLED("disabled", cancelBoundaryWork = true, cancelSharedAlarm = true),
    NO_FENCES("no_fences", cancelBoundaryWork = true, cancelSharedAlarm = true),
    INELIGIBLE("ineligible", cancelBoundaryWork = true, cancelSharedAlarm = true),
    CORRUPT_STATE("corrupt_state", cancelBoundaryWork = true, cancelSharedAlarm = true),
    SCHEDULE_FAILED("schedule_failed"),
    HEALTHY_FUSED_FIX("healthy_fused_fix"),
    STATIONARY("stationary"),
    CONTROLLER_STOP("controller_stop", cancelBoundaryWork = true, cancelSharedAlarm = true),
}

internal enum class PulseReconcileAction { RETAIN, REARM, STOP, CANCEL_STALE_PULSE_ALARM, NONE }

internal data class PulseReconcileDecision(
    val action: PulseReconcileAction,
    val stopReason: PulseStopReason? = null,
)

internal data class PulseRetryTiming(
    val purpose: ProximityPulsePurpose,
    val activeHoursNow: Boolean,
    val delayMillis: Long,
)

internal fun transitionConfirmationBurstActive(
    pending: PendingNativeTransition,
    nowMillis: Long,
    burstDurationMillis: Long,
): Boolean = pending.validationRequired &&
    !pending.nativeCandidate &&
    pending.createdAtMillis > 0L &&
    nowMillis >= pending.createdAtMillis &&
    nowMillis - pending.createdAtMillis < burstDurationMillis.coerceAtLeast(0L)

internal fun isProximityActivationEligible(
    edgeDistanceMeters: Double,
    activationDistanceMeters: Double,
): Boolean = edgeDistanceMeters.isFinite() && activationDistanceMeters.isFinite() &&
    edgeDistanceMeters >= 0.0 &&
    edgeDistanceMeters <= activationDistanceMeters.coerceAtLeast(0.0)

internal fun isProximityExitRecoveryEligible(
    fence: SmartGeofenceFence?,
    edgeDistanceMeters: Double?,
    activationDistanceMeters: Double,
): Boolean = fence != null &&
    fence.armed &&
    (fence.triggersEnter || fence.triggersExit) &&
    edgeDistanceMeters != null &&
    isProximityActivationEligible(edgeDistanceMeters, activationDistanceMeters)

internal fun normalizedProximityFenceIds(
    persistedIds: Set<String>,
    durableNearExitIds: Set<String>,
    eligibleFenceIds: Set<String>,
    outsideFenceIds: Set<String>,
): Set<String> = (persistedIds + durableNearExitIds)
    .intersect(eligibleFenceIds)
    .intersect(outsideFenceIds)

internal fun normalizedInsideFenceIds(
    observedInsideIds: Set<String>,
    eligibleFenceIds: Set<String>,
): Set<String> = observedInsideIds.intersect(eligibleFenceIds)

internal fun stationaryStopsPulsePurpose(purpose: ProximityPulsePurpose): Boolean =
    purpose == ProximityPulsePurpose.FUSED_LIVENESS

internal fun livenessSafetyCapReached(
    livenessStartedAtMillis: Long?,
    nowMillis: Long,
    maxDurationMillis: Long,
): Boolean = livenessStartedAtMillis != null &&
    nowMillis >= livenessStartedAtMillis &&
    nowMillis - livenessStartedAtMillis >= maxDurationMillis.coerceAtLeast(0L)

internal fun shouldStopTickAfterLivenessSafetyCap(
    purpose: ProximityPulsePurpose,
    safetyCapReached: Boolean,
): Boolean = safetyCapReached && purpose == ProximityPulsePurpose.FUSED_LIVENESS

internal fun shouldReplacePulseAlarm(
    forceReschedule: Boolean,
    previousPurpose: ProximityPulsePurpose?,
    previousSchedulingActive: Boolean?,
    selectedPurpose: ProximityPulsePurpose,
): Boolean = forceReschedule ||
    previousPurpose != selectedPurpose ||
    previousSchedulingActive != true

internal fun shouldMovePulseCadenceForConfirmAttempt(
    source: String,
    schedulingActive: Boolean,
): Boolean = schedulingActive && when (source) {
    Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
    Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS,
    -> true
    else -> false
}

internal fun selectPulsePurpose(
    pending: Collection<PendingNativeTransition>,
    proximityTargetCount: Int,
    insideTargetCount: Int,
    livenessRequested: Boolean,
    nowMillis: Long,
    transitionBurstDurationMillis: Long,
): ProximityPulsePurpose? {
    val nonNativePending = pending.filter { it.validationRequired && !it.nativeCandidate }
    if (nonNativePending.any {
            transitionConfirmationBurstActive(it, nowMillis, transitionBurstDurationMillis)
        }
    ) {
        return ProximityPulsePurpose.TRANSITION_CONFIRMATION
    }
    if (insideTargetCount > 0 ||
        nonNativePending.any { it.direction == NativeTransitionDirection.EXIT }
    ) {
        return ProximityPulsePurpose.INSIDE
    }
    if (proximityTargetCount > 0 ||
        nonNativePending.any { it.direction == NativeTransitionDirection.ENTER }
    ) {
        return ProximityPulsePurpose.PROXIMITY
    }
    return ProximityPulsePurpose.FUSED_LIVENESS.takeIf { livenessRequested }
}

object ProximityPulseController {
    private const val TAG = "ProximityPulseController"
    private const val FUSED_LIVENESS_MAX_DURATION_MILLIS = 30 * 60_000L
    private const val FUSED_LIVENESS_FIRST_TICK_DELAY_MILLIS = 250L

    fun canRun(context: Context): Boolean {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        if (!config.proximityPulseEnabled) return false
        if (!hasReceiver(appContext, ProximityAlarmReceiver::class.java)) {
            SmartGeofenceLogger.d(appContext, TAG, "Pulse skipped: alarm receiver not declared.")
            return false
        }
        return true
    }

    fun isSchedulingActive(context: Context): Boolean =
        ProximityPulseStateStore.load(context.applicationContext)?.schedulingActive == true

    fun isLivenessSchedulingActive(context: Context): Boolean =
        ProximityPulseStateStore.load(context.applicationContext)?.let {
            it.schedulingActive && it.purpose == ProximityPulsePurpose.FUSED_LIVENESS
        } == true

    @Synchronized
    fun reconcileScheduling(context: Context, reason: String = "controller_refresh"): Boolean =
        reconcile(context.applicationContext, reason, forceReschedule = false)

    @Synchronized
    fun maybeStart(
        context: Context,
        fenceId: String,
        edgeDistanceMeters: Double,
        source: String,
    ): Boolean {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        val fence = FenceStore.get(appContext, fenceId) ?: return false
        if (!config.proximityPulseEnabled || !fence.armed ||
            (!fence.triggersEnter && !fence.triggersExit) ||
            !isProximityActivationEligible(
                edgeDistanceMeters,
                config.proximityPulseActivationDistanceMeters,
            )
        ) {
            return false
        }
        if (FenceObservationStore.currentState(appContext, fenceId) != ObservedFenceState.OUTSIDE) {
            return false
        }
        val existing = ProximityPulseStateStore.load(appContext)
        if (fenceId in existing?.proximityFenceIds.orEmpty()) return reconcile(
            appContext,
            "proximity_confirmed:$source",
            forceReschedule = false,
        )
        val now = System.currentTimeMillis()
        saveTargets(
            appContext,
            existing,
            proximityIds = existing?.proximityFenceIds.orEmpty() + fenceId,
            insideIds = existing?.insideFenceIds.orEmpty(),
            nowMillis = now,
        )
        return reconcile(appContext, "proximity_target_added:$source", forceReschedule = true)
    }

    @Synchronized
    fun onInternalTransitionCommitted(
        context: Context,
        fenceId: String,
        state: ObservedFenceState,
        edgeDistanceMeters: Double?,
        source: String,
    ) {
        val appContext = context.applicationContext
        val existing = ProximityPulseStateStore.load(appContext)
        val config = SmartGeofenceConfigStore.load(appContext)
        val proximity = existing?.proximityFenceIds.orEmpty().toMutableSet()
        val inside = existing?.insideFenceIds.orEmpty().toMutableSet()
        when (state) {
            ObservedFenceState.INSIDE -> {
                proximity -= fenceId
                inside += fenceId
            }
            ObservedFenceState.OUTSIDE -> {
                inside -= fenceId
                val fence = FenceStore.get(appContext, fenceId)
                if (isProximityExitRecoveryEligible(
                        fence,
                        edgeDistanceMeters,
                        config.proximityPulseActivationDistanceMeters,
                    )
                ) {
                    proximity += fenceId
                } else {
                    proximity -= fenceId
                }
            }
        }
        saveTargets(appContext, existing, proximity, inside, System.currentTimeMillis())
        reconcile(appContext, "internal_${state.name.lowercase()}:$source", forceReschedule = true)
    }

    @Synchronized
    fun onPendingTransitionChanged(
        context: Context,
        reason: String,
        newInstance: Boolean = false,
    ): Boolean = reconcile(
        context.applicationContext,
        "pending_transition:$reason",
        forceReschedule = newInstance,
    )

    @Synchronized
    fun startLiveness(context: Context, reason: String): Boolean {
        val appContext = context.applicationContext
        if (!canRun(appContext) || FenceStore.getAll(appContext).isEmpty()) return false
        val now = System.currentTimeMillis()
        val existing = ProximityPulseStateStore.load(appContext)
        if (existing?.livenessStartedAtMillis == null) {
            FusedLocationLiveness.beginRecovery(appContext, reason, now)
            val purpose = existing?.purpose ?: ProximityPulsePurpose.FUSED_LIVENESS
            ProximityPulseStateStore.save(
                appContext,
                ProximityPulseState(
                    startedAtMillis = existing?.startedAtMillis ?: now,
                    purpose = purpose,
                    schedulingActive = existing?.schedulingActive ?: true,
                    proximityFenceIds = existing?.proximityFenceIds.orEmpty(),
                    insideFenceIds = existing?.insideFenceIds.orEmpty(),
                    livenessStartedAtMillis = now,
                ),
            )
        }
        val onlyLiveness = selectCurrentPurpose(appContext, now) ==
            ProximityPulsePurpose.FUSED_LIVENESS
        return reconcile(
            appContext,
            "liveness_started:$reason",
            forceReschedule = onlyLiveness && existing?.schedulingActive != true,
            explicitDelayMillis = FUSED_LIVENESS_FIRST_TICK_DELAY_MILLIS.takeIf { onlyLiveness },
        )
    }

    @Synchronized
    fun onTick(context: Context) {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        var state = normalizedState(appContext, System.currentTimeMillis())
        if (state == null || !state.schedulingActive) {
            reconcile(appContext, "inactive_tick", forceReschedule = false)
            return
        }
        val now = System.currentTimeMillis()
        if (!config.proximityPulseEnabled || FenceStore.getAll(appContext).isEmpty()) {
            reconcile(appContext, "ineligible_tick", forceReschedule = false)
            return
        }
        val livenessCapReached = livenessSafetyCapReached(
            state.livenessStartedAtMillis,
            now,
            FUSED_LIVENESS_MAX_DURATION_MILLIS,
        )
        if (livenessCapReached) {
            val stopCurrentTick = shouldStopTickAfterLivenessSafetyCap(
                state.purpose,
                livenessCapReached,
            )
            clearLiveness(appContext, "safety_cap")
            if (stopCurrentTick) {
                reconcile(appContext, "liveness_safety_cap", forceReschedule = true)
                return
            }
            state = normalizedState(appContext, now) ?: return
        }
        if (stationaryStopsPulsePurpose(state.purpose) &&
            ActivityMonitor.isLikelyStationary(appContext)
        ) {
            clearLiveness(appContext, PulseStopReason.STATIONARY.code)
            reconcile(appContext, "liveness_stationary", forceReschedule = true)
            return
        }

        val pendingCount = nonNativePending(appContext).size
        val activeHoursNow = AdaptivePulseRate.isActive(config, now)
        val interval = AdaptivePulseRate.intervalMillis(
            config,
            state.purpose,
            activeHoursNow,
        )
        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "pulse_tick",
            reasonCode = "fresh_only_request",
            source = if (state.purpose == ProximityPulsePurpose.FUSED_LIVENESS) {
                Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS
            } else {
                Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM
            },
            extras = mapOf(
                "purpose" to state.purpose.name.lowercase(),
                "freshOnly" to true,
                "pendingInstanceCount" to pendingCount,
                "intervalMillis" to interval,
                "activeHoursNow" to activeHoursNow,
                "cadenceMultiplier" to if (activeHoursNow) {
                    1
                } else {
                    config.proximityPulseOutsideActiveHoursIntervalMultiplier
                },
                "proximityTargetCount" to state.proximityFenceIds.size,
                "insideTargetCount" to state.insideFenceIds.size,
            ),
        )
        val source = if (state.purpose == ProximityPulsePurpose.FUSED_LIVENESS) {
            Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS
        } else {
            Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM
        }
        val enqueued = LocationConfirmManager.enqueueProximity(
            appContext,
            source,
            WakeExemption.NONE,
        )
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Pulse tick purpose=${state.purpose.name.lowercase()} freshOnly=true " +
                "pending=$pendingCount interval=${interval}ms enqueued=$enqueued.",
        )
        reconcile(appContext, "tick_complete:$enqueued", forceReschedule = true)
    }

    @Synchronized
    fun onHealthyFusedFix(context: Context) {
        val appContext = context.applicationContext
        val state = ProximityPulseStateStore.load(appContext) ?: return
        if (state.livenessStartedAtMillis == null) return
        clearLiveness(appContext, PulseStopReason.HEALTHY_FUSED_FIX.code)
        reconcile(appContext, "healthy_fused_fix", forceReschedule = true)
    }

    @Synchronized
    fun onStationary(context: Context) {
        val appContext = context.applicationContext
        val state = ProximityPulseStateStore.load(appContext) ?: return
        if (state.livenessStartedAtMillis == null) return
        clearLiveness(appContext, PulseStopReason.STATIONARY.code)
        reconcile(appContext, "stationary", forceReschedule = true)
    }

    fun onConfidentLocationProcessed(context: Context) {
        reconcileScheduling(context.applicationContext, "confident_location_processed")
    }

    @Synchronized
    fun onConfirmAttemptStarted(context: Context, source: String): Boolean {
        val appContext = context.applicationContext
        val schedulingActive =
            ProximityPulseStateStore.load(appContext)?.schedulingActive == true
        if (!shouldMovePulseCadenceForConfirmAttempt(source, schedulingActive)) return false
        return reconcile(
            appContext,
            "confirm_attempt_started:$source",
            forceReschedule = true,
        )
    }

    @Synchronized
    internal fun currentRetryTiming(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): PulseRetryTiming? {
        val appContext = context.applicationContext
        val state = normalizedState(appContext, nowMillis)
        if (state?.schedulingActive != true) return null
        val purpose = selectCurrentPurpose(appContext, nowMillis) ?: return null
        val config = SmartGeofenceConfigStore.load(appContext)
        val activeHoursNow = AdaptivePulseRate.isActive(config, nowMillis)
        return PulseRetryTiming(
            purpose = purpose,
            activeHoursNow = activeHoursNow,
            delayMillis = AdaptivePulseRate.intervalMillis(
                config,
                purpose,
                activeHoursNow,
            ),
        )
    }

    @Synchronized
    internal fun stopScheduling(
        context: Context,
        reason: PulseStopReason = PulseStopReason.STOPPED,
    ) {
        val appContext = context.applicationContext
        val existing = ProximityPulseStateStore.load(appContext)
        if (!reason.cancelBoundaryWork) {
            existing?.let {
                ProximityPulseStateStore.save(
                    appContext,
                    it.copy(
                        schedulingActive = false,
                        livenessStartedAtMillis = null,
                    ),
                )
            }
        } else {
            ProximityPulseStateStore.clear(appContext)
        }
        if (reason.cancelSharedAlarm) {
            ProximityAlarmScheduler.cancel(appContext)
        } else {
            ProximityAlarmScheduler.cancelIfKind(appContext, ProximityAlarmKind.PULSE)
        }
        if (existing?.livenessStartedAtMillis != null || reason.cancelSharedAlarm) {
            FusedLocationLiveness.endRecovery(appContext, reason.code)
            LocationConfirmManager.cancelLivenessWork(appContext, reason.code)
        }
        if (reason.cancelBoundaryWork) {
            LocationConfirmManager.cancelPulseBoundaryWork(appContext, reason.code)
        }
        LocationConfirmManager.stopServiceIfIdle(appContext, "pulse_${reason.code}")
    }

    @Synchronized
    fun disable(context: Context) = disable(context, PulseStopReason.DISABLED)

    @Synchronized
    internal fun disable(context: Context, reason: PulseStopReason) {
        stopScheduling(context.applicationContext, reason)
        ProximityAlarmScheduler.cancel(context.applicationContext)
    }

    private fun reconcile(
        context: Context,
        reason: String,
        forceReschedule: Boolean,
        explicitDelayMillis: Long? = null,
    ): Boolean {
        val config = SmartGeofenceConfigStore.load(context)
        val now = System.currentTimeMillis()
        if (ProximityPulseStateStore.hasLegacyBoundaryState(context)) {
            ProximityPulseStateStore.clear(context)
            ProximityAlarmScheduler.cancelIfKind(context, ProximityAlarmKind.PULSE)
            LocationConfirmManager.cancelPulseBoundaryWork(context, "schema_v4_migration")
            NativeTransitionDirection.entries.forEach {
                NativeTransitionCoordinator.reschedule(context, it)
            }
        }
        if (ProximityPulseStateStore.isPersistedStateCorrupt(context)) {
            stopScheduling(context, PulseStopReason.CORRUPT_STATE)
            return false
        }
        if (!config.proximityPulseEnabled) {
            stopScheduling(context, PulseStopReason.DISABLED)
            return false
        }
        val fences = FenceStore.getAll(context)
        if (fences.isEmpty()) {
            stopScheduling(context, PulseStopReason.NO_FENCES)
            return false
        }
        val normalized = normalizedState(context, now)
        val purpose = selectCurrentPurpose(context, now)
        if (purpose == null) {
            ProximityPulseStateStore.clear(context)
            ProximityAlarmScheduler.cancelIfKind(context, ProximityAlarmKind.PULSE)
            ProximityAlarmScheduler.reconcilePendingLiveness(context, pulseActive = false)
            return false
        }
        val state = (normalized ?: ProximityPulseState(now, purpose, true)).copy(
            startedAtMillis = if (normalized?.purpose == purpose) {
                normalized.startedAtMillis
            } else {
                now
            },
            purpose = purpose,
            schedulingActive = true,
        )
        ProximityPulseStateStore.save(context, state)
        val alarmExists = ProximityAlarmScheduler.pendingIntentExists(context) &&
            ProximityAlarmScheduler.scheduledKind(context) == ProximityAlarmKind.PULSE
        val replaceExisting = shouldReplacePulseAlarm(
            forceReschedule,
            normalized?.purpose,
            normalized?.schedulingActive,
            purpose,
        )
        if (!replaceExisting && alarmExists) {
            recordReconcileTrace(context, "retained", reason, state, null)
            return true
        }
        val delay = explicitDelayMillis ?: AdaptivePulseRate.intervalMillis(
            config,
            purpose,
            activeHoursNow = AdaptivePulseRate.isActive(config, now),
        )
        val scheduled = ProximityAlarmScheduler.schedule(
            context,
            delay,
            replaceExisting = replaceExisting,
        )
        recordReconcileTrace(
            context,
            if (scheduled) "scheduled" else "schedule_failed",
            reason,
            state,
            delay,
        )
        if (!scheduled) stopScheduling(context, PulseStopReason.SCHEDULE_FAILED)
        return scheduled
    }

    private fun normalizedState(context: Context, nowMillis: Long): ProximityPulseState? {
        val existing = ProximityPulseStateStore.load(context)
        val fenceIds = FenceStore.getAll(context)
            .filter { it.armed && (it.triggersEnter || it.triggersExit) }
            .mapTo(linkedSetOf()) { it.id }
        val inside = normalizedInsideFenceIds(
            observedInsideIds = FenceObservationStore.observedInsideFenceIds(context),
            eligibleFenceIds = fenceIds,
        )
        val outside = fenceIds.filterTo(linkedSetOf()) {
            FenceObservationStore.currentState(context, it) == ObservedFenceState.OUTSIDE
        }
        val proximity = normalizedProximityFenceIds(
            persistedIds = existing?.proximityFenceIds.orEmpty(),
            durableNearExitIds = FenceObservationStore.proximityEligibleExitFenceIds(context),
            eligibleFenceIds = fenceIds,
            outsideFenceIds = outside,
        )
        if (existing == null && proximity.isEmpty() && inside.isEmpty()) return null
        val purpose = existing?.purpose ?: ProximityPulsePurpose.INSIDE
        return ProximityPulseState(
            startedAtMillis = existing?.startedAtMillis ?: nowMillis,
            purpose = purpose,
            schedulingActive = existing?.schedulingActive ?: false,
            proximityFenceIds = proximity,
            insideFenceIds = inside,
            livenessStartedAtMillis = existing?.livenessStartedAtMillis,
        ).also { if (it != existing) ProximityPulseStateStore.save(context, it) }
    }

    private fun selectCurrentPurpose(context: Context, nowMillis: Long): ProximityPulsePurpose? {
        val state = normalizedState(context, nowMillis)
        val config = SmartGeofenceConfigStore.load(context)
        return selectPulsePurpose(
            pending = nonNativePending(context),
            proximityTargetCount = state?.proximityFenceIds?.size ?: 0,
            insideTargetCount = state?.insideFenceIds?.size ?: 0,
            livenessRequested = state?.livenessStartedAtMillis != null,
            nowMillis = nowMillis,
            transitionBurstDurationMillis =
                config.proximityPulseTransitionConfirmationBurstDurationMillis,
        )
    }

    private fun nonNativePending(context: Context): List<PendingNativeTransition> =
        NativeTransitionCoordinator.allPending(context).filter {
            it.validationRequired && !it.nativeCandidate
        }

    private fun saveTargets(
        context: Context,
        existing: ProximityPulseState?,
        proximityIds: Set<String>,
        insideIds: Set<String>,
        nowMillis: Long,
    ) {
        ProximityPulseStateStore.save(
            context,
            ProximityPulseState(
                startedAtMillis = existing?.startedAtMillis ?: nowMillis,
                purpose = existing?.purpose ?: if (insideIds.isNotEmpty()) {
                    ProximityPulsePurpose.INSIDE
                } else {
                    ProximityPulsePurpose.PROXIMITY
                },
                schedulingActive = existing?.schedulingActive ?: false,
                proximityFenceIds = proximityIds,
                insideFenceIds = insideIds,
                livenessStartedAtMillis = existing?.livenessStartedAtMillis,
            ),
        )
    }

    private fun clearLiveness(context: Context, reason: String) {
        val state = ProximityPulseStateStore.load(context) ?: return
        if (state.livenessStartedAtMillis == null) return
        ProximityPulseStateStore.save(context, state.copy(livenessStartedAtMillis = null))
        FusedLocationLiveness.endRecovery(context, reason)
        LocationConfirmManager.cancelLivenessWork(context, reason)
    }

    private fun recordReconcileTrace(
        context: Context,
        result: String,
        reason: String,
        state: ProximityPulseState,
        intervalMillis: Long?,
    ) {
        SmartGeofenceDiagnostics.recordTrace(
            context,
            stage = "pulse_reconcile",
            reasonCode = result,
            source = Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
            extras = mapOf(
                "reason" to reason,
                "purpose" to state.purpose.name.lowercase(),
                "freshOnly" to true,
                "pendingInstanceCount" to nonNativePending(context).size,
                "intervalMillis" to intervalMillis,
                "proximityTargetCount" to state.proximityFenceIds.size,
                "insideTargetCount" to state.insideFenceIds.size,
            ),
        )
    }

    private fun hasReceiver(context: Context, receiverClass: Class<*>): Boolean = try {
        AndroidPackageManagerCompat.getReceiverInfo(
            context.packageManager,
            ComponentName(context, receiverClass),
        )
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

internal fun applyPulseBoundaryWorkStopPolicy(
    reason: PulseStopReason,
    cancelBoundaryWork: () -> Unit,
): Boolean {
    if (!reason.cancelBoundaryWork) return false
    cancelBoundaryWork()
    return true
}

internal fun applyPulseStopOwnershipPolicy(
    reason: PulseStopReason,
    existingPurpose: ProximityPulsePurpose?,
    cancelSharedAlarm: () -> Unit,
    cancelPulseAlarm: () -> Unit,
    endLivenessRecovery: () -> Unit,
    cancelLivenessWork: () -> Unit,
    cancelBoundaryWork: () -> Unit,
) {
    if (reason.cancelSharedAlarm) cancelSharedAlarm() else cancelPulseAlarm()
    if (existingPurpose == ProximityPulsePurpose.FUSED_LIVENESS || reason.cancelSharedAlarm) {
        endLivenessRecovery()
        cancelLivenessWork()
    }
    applyPulseBoundaryWorkStopPolicy(reason, cancelBoundaryWork)
}
