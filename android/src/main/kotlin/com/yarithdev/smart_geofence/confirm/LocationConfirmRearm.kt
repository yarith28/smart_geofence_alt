package com.yarithdev.smart_geofence.confirm

import android.content.Context
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.wake.ForegroundWorkItem
import com.yarithdev.smart_geofence.wake.ForegroundWorkKind
import com.yarithdev.smart_geofence.wake.ForegroundQueueMutationStatus
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator

object LocationConfirmRearm {
    private const val TAG = "LocationConfirmRearm"

    internal data class ProximityRetryPolicy(
        val failureCount: Int,
        val shouldRetry: Boolean,
    )

    internal fun proximityRetryPolicy(
        attemptCount: Int,
        maxAttempts: Int,
    ): ProximityRetryPolicy {
        val effectiveMaxAttempts = maxAttempts.coerceAtLeast(1)
        val failureCount = attemptCount.coerceIn(0, effectiveMaxAttempts - 1) + 1
        return ProximityRetryPolicy(
            failureCount = failureCount,
            shouldRetry = failureCount < effectiveMaxAttempts,
        )
    }

    fun parkForLocationDisabled(
        context: Context,
        item: ForegroundWorkItem,
        failureReason: String,
    ): FusedLocationConfirmTaskResult =
        park(
            context.applicationContext,
            item,
            item.attemptCount,
            Constants.CONFIRM_PARKED_REASON_LOCATION_DISABLED,
            failureReason,
        )

    fun rearm(
        context: Context,
        item: ForegroundWorkItem,
        outcome: FusedLocationConfirm.LocationConfirmOutcome,
    ): FusedLocationConfirmTaskResult {
        val appContext = context.applicationContext
        if (item.source == Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS &&
            !ProximityPulseController.isLivenessSchedulingActive(appContext)
        ) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Dropping stopped fused liveness work id=${item.id} outcome=${outcome.reason}.",
            )
            return FusedLocationConfirmTaskResult(
                FusedLocationConfirmTaskDisposition.DROPPED,
                "liveness_stopped:${outcome.reason}",
            )
        }
        if (LocationServicesState.isLocationEnabled(appContext) == false) {
            return park(
                appContext,
                item,
                item.attemptCount,
                Constants.CONFIRM_PARKED_REASON_LOCATION_DISABLED,
                outcome.reason,
            )
        }
        val failureCount = item.attemptCount + 1
        val config = SmartGeofenceConfigStore.load(appContext)
        if (item.isNativeTransitionConfirm()) {
            return rearmNativeTransitionConfirm(appContext, item, outcome, config, failureCount)
        }
        if (failureCount >= Constants.DEFAULT_CONFIRM_MAX_TRANSIENT_FAILURES) {
            return park(
                appContext,
                item,
                failureCount,
                Constants.CONFIRM_PARKED_REASON_TRANSIENT_FAILURES_EXHAUSTED,
                outcome.reason,
            )
        }
        val delayMillis = retryDelayMillis(config, item.kind, failureCount)
        val now = System.currentTimeMillis()
        val notBeforeMillis =
            if (delayMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMillis

        val mutation = WakeEventCoordinator.rearmConfirmIfUnchanged(
            appContext,
            item,
            notBeforeMillis,
            failureCount,
            outcome.reason,
        )
        val queued = mutation.item
        if (mutation.status != ForegroundQueueMutationStatus.REPLACED || queued == null) {
            return droppedMutation(mutation.status, "rearm", outcome.reason)
        }
        val scheduled = LocationConfirmManager.scheduleNextReadyWork(
            appContext,
            reason = "rearmed ${item.kind} after ${outcome.reason}",
        )
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Rearmed confirm work oldId=${item.id} newId=${queued.id} kind=${item.kind} " +
                "source=${item.source} reason=${outcome.reason} attempts=$failureCount " +
                "delay=${delayMillis}ms scheduled=$scheduled failure=${outcome.failure?.message}."
        )
        return FusedLocationConfirmTaskResult(
            FusedLocationConfirmTaskDisposition.REARMED,
            "rearmed:${outcome.reason}:attempts=$failureCount:scheduled=$scheduled:newId=${queued.id}",
            queueOwnershipFinalized = true,
        )
    }

    fun rearmProximityPulse(
        context: Context,
        item: ForegroundWorkItem,
        outcome: FusedLocationConfirm.LocationConfirmOutcome,
    ): FusedLocationConfirmTaskResult {
        val appContext = context.applicationContext
        if (item.source == Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS &&
            !ProximityPulseController.isLivenessSchedulingActive(appContext)
        ) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Dropping stopped fused liveness work id=${item.id} outcome=${outcome.reason}.",
            )
            return FusedLocationConfirmTaskResult(
                FusedLocationConfirmTaskDisposition.DROPPED,
                "liveness_stopped:${outcome.reason}",
            )
        }
        if (LocationServicesState.isLocationEnabled(appContext) == false) {
            return park(
                appContext,
                item,
                item.attemptCount,
                Constants.CONFIRM_PARKED_REASON_LOCATION_DISABLED,
                outcome.reason,
            )
        }

        val config = SmartGeofenceConfigStore.load(appContext)
        val policy = proximityRetryPolicy(
            item.attemptCount,
            config.proximityConfirmMaxAttempts,
        )
        if (!policy.shouldRetry) {
            SmartGeofenceDiagnostics.recordProximityConfirmRetryExhausted(
                context = appContext,
                failureReason = outcome.reason,
                source = item.source,
                traceId = item.traceId,
                attempts = policy.failureCount,
            )
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Proximity confirm attempts exhausted id=${item.id} source=${item.source} " +
                    "reason=${outcome.reason} attempts=${policy.failureCount}/" +
                    "${config.proximityConfirmMaxAttempts}; " +
                    "waiting for the next scheduled pulse.",
            )
            return proximityConfirmAttemptsExhaustedResult(
                outcome.reason,
                policy.failureCount,
            )
        }

        val retryTiming = ProximityPulseController.currentRetryTiming(appContext)
        if (retryTiming == null) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Dropping proximity retry id=${item.id} source=${item.source}; " +
                    "pulse scheduling is no longer active.",
            )
            return FusedLocationConfirmTaskResult(
                FusedLocationConfirmTaskDisposition.DROPPED,
                "proximity_retry_pulse_inactive:${outcome.reason}",
            )
        }
        val now = System.currentTimeMillis()
        val retryDelayMillis = retryTiming.delayMillis
        val notBeforeMillis = if (retryDelayMillis > Long.MAX_VALUE - now) {
            Long.MAX_VALUE
        } else {
            now + retryDelayMillis
        }
        val mutation = WakeEventCoordinator.rearmConfirmIfUnchanged(
            appContext,
            item,
            notBeforeMillis,
            policy.failureCount,
            outcome.reason,
        )
        val queued = mutation.item
        if (mutation.status != ForegroundQueueMutationStatus.REPLACED || queued == null) {
            return droppedMutation(mutation.status, "proximity_rearm", outcome.reason)
        }
        val scheduled = LocationConfirmManager.scheduleNextReadyWork(
            appContext,
            reason = "rearmed proximity after ${outcome.reason}",
        )
        SmartGeofenceDiagnostics.recordProximityConfirmRetryScheduled(
            context = appContext,
            failureReason = outcome.reason,
            source = item.source,
            traceId = item.traceId,
            nextAttempt = policy.failureCount + 1,
            delayMillis = retryDelayMillis,
            pulsePurpose = retryTiming.purpose.name.lowercase(),
            activeHoursNow = retryTiming.activeHoursNow,
            scheduled = scheduled,
        )
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Rearmed proximity confirm oldId=${item.id} newId=${queued.id} " +
                "source=${item.source} reason=${outcome.reason} " +
                "nextAttempt=${policy.failureCount + 1}/" +
                "${config.proximityConfirmMaxAttempts} " +
                "delay=${retryDelayMillis}ms pulsePurpose=" +
                "${retryTiming.purpose.name.lowercase()} " +
                "activeHoursNow=${retryTiming.activeHoursNow} scheduled=$scheduled.",
        )
        return FusedLocationConfirmTaskResult(
            FusedLocationConfirmTaskDisposition.REARMED,
            "proximity_rearmed:${outcome.reason}:nextAttempt=${policy.failureCount + 1}:" +
                "scheduled=$scheduled:newId=${queued.id}",
            queueOwnershipFinalized = true,
        )
    }

    private fun rearmNativeTransitionConfirm(
        context: Context,
        item: ForegroundWorkItem,
        outcome: FusedLocationConfirm.LocationConfirmOutcome,
        config: SmartGeofenceConfig,
        failureCount: Int,
    ): FusedLocationConfirmTaskResult {
        val maxAttempts = config.nativeConfirmMaxAttempts.coerceAtLeast(1)
        if (failureCount >= maxAttempts) {
            val reason = "confirm_attempts_exhausted:${outcome.reason}:attempts=$failureCount"
            val fallback = emitNativeFallbackIfClaimed(
                claim = { WakeEventCoordinator.claimConfirmIfUnchanged(context, item) },
                emit = {
                    when {
                        item.isNativeExitConfirm() ->
                            SmartGeofenceEventProcessor.emitPendingNativeExitFallbacks(
                                context,
                                item.nativeTransitionInstances,
                                reason,
                            )
                        item.isNativeEnterConfirm() ->
                            SmartGeofenceEventProcessor.emitPendingNativeEnterFallbacks(
                                context,
                                item.nativeTransitionInstances,
                                reason,
                            )
                        else -> false
                    }
                },
            )
            if (fallback.queueStatus != ForegroundQueueMutationStatus.CLAIMED) {
                return droppedMutation(
                    fallback.queueStatus,
                    "native_fallback",
                    outcome.reason,
                )
            }
            val emitted = fallback.emitted
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Native transition confirm exhausted attempts kind=${item.kind} " +
                    "source=${item.source} attempts=$failureCount emittedFallback=$emitted " +
                    "reason=${outcome.reason} failure=${outcome.failure?.message}.",
            )
            return FusedLocationConfirmTaskResult(
                FusedLocationConfirmTaskDisposition.COMPLETED,
                "native_transition_attempts_exhausted:attempts=$failureCount:emitted=$emitted",
                queueOwnershipFinalized = true,
            )
        }

        val delayMillis = LocationConfirmManager.confirmStartDelayMillis(config, item.kind)
        val now = System.currentTimeMillis()
        val notBeforeMillis =
            if (delayMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMillis
        val mutation = WakeEventCoordinator.rearmConfirmIfUnchanged(
            context,
            item,
            notBeforeMillis,
            failureCount,
            outcome.reason,
        )
        val queued = mutation.item
        if (mutation.status != ForegroundQueueMutationStatus.REPLACED || queued == null) {
            return droppedMutation(mutation.status, "rearm", outcome.reason)
        }
        val scheduled = LocationConfirmManager.scheduleNextReadyWork(
            context,
            reason = "rearmed native ${item.kind} after ${outcome.reason}",
        )
        SmartGeofenceLogger.w(
            context,
            TAG,
            "Rearmed native transition confirm oldId=${item.id} newId=${queued.id} " +
                "kind=${item.kind} source=${item.source} attempts=$failureCount/$maxAttempts " +
                "delay=${delayMillis}ms scheduled=$scheduled reason=${outcome.reason} " +
                "failure=${outcome.failure?.message}.",
        )
        return FusedLocationConfirmTaskResult(
            FusedLocationConfirmTaskDisposition.REARMED,
            "native_transition_rearmed:${outcome.reason}:attempts=$failureCount:" +
                "scheduled=$scheduled:newId=${queued.id}",
            queueOwnershipFinalized = true,
        )
    }

    private fun park(
        context: Context,
        item: ForegroundWorkItem,
        attemptCount: Int,
        parkedReason: String,
        failureReason: String,
    ): FusedLocationConfirmTaskResult {
        val appContext = context.applicationContext
        val mutation = WakeEventCoordinator.parkConfirmIfUnchanged(
            appContext,
            item,
            attemptCount,
            parkedReason,
            failureReason,
        )
        val parked = mutation.item
        if (mutation.status != ForegroundQueueMutationStatus.REPLACED || parked == null) {
            return droppedMutation(mutation.status, "park", failureReason)
        }
        LocationConfirmManager.onConfirmWorkParked(appContext, parkedReason)
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Parked confirm work oldId=${item.id} newId=${parked.id} kind=${item.kind} " +
                "source=${item.source} reason=$parkedReason failure=$failureReason " +
                "attempts=$attemptCount.",
        )
        return FusedLocationConfirmTaskResult(
            FusedLocationConfirmTaskDisposition.PARKED,
            "parked:$parkedReason:$failureReason:newId=${parked.id}",
            queueOwnershipFinalized = true,
        )
    }

    private fun droppedMutation(
        status: ForegroundQueueMutationStatus,
        action: String,
        detail: String,
    ): FusedLocationConfirmTaskResult {
        val reason = queueMutationDropReason(status, action)
        return FusedLocationConfirmTaskResult(
            FusedLocationConfirmTaskDisposition.DROPPED,
            "$reason:$detail",
            queueOwnershipFinalized = true,
        )
    }

    private fun retryDelayMillis(
        config: SmartGeofenceConfig,
        kind: ForegroundWorkKind,
        failureCount: Int,
    ): Long {
        val baseDelay = LocationConfirmManager.confirmStartDelayMillis(config, kind)
            .coerceAtLeast(1_000L)
        val exponent = (failureCount - 1).coerceIn(0, 10)
        val multiplier = 1L shl exponent
        val delay = if (baseDelay > Long.MAX_VALUE / multiplier) {
            Long.MAX_VALUE
        } else {
            baseDelay * multiplier
        }
        return delay.coerceAtMost(Constants.DEFAULT_CONFIRM_RETRY_MAX_DELAY_MILLIS)
    }

    private fun ForegroundWorkItem.isNativeTransitionConfirm(): Boolean =
        isNativeExitConfirm() || isNativeEnterConfirm()

    private fun ForegroundWorkItem.isNativeExitConfirm(): Boolean =
        kind == ForegroundWorkKind.CONFIRM_OUTSIDE &&
            source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM)

    private fun ForegroundWorkItem.isNativeEnterConfirm(): Boolean =
        kind == ForegroundWorkKind.CONFIRM_INSIDE &&
            source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)
}
