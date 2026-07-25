package com.yarithdev.smart_geofence.confirm

import android.content.Context
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.proximitypulse.AdaptivePulseRate
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulsePurpose
import com.yarithdev.smart_geofence.wake.ForegroundWorkItem
import com.yarithdev.smart_geofence.wake.ForegroundWorkKind
import com.yarithdev.smart_geofence.wake.ForegroundQueueMutationResult
import com.yarithdev.smart_geofence.wake.ForegroundQueueMutationStatus
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator
import java.util.concurrent.atomic.AtomicBoolean

enum class FusedLocationConfirmTaskDisposition {
    COMPLETED,
    REARMED,
    PARKED,
    DROPPED,
    DEFERRED,
    FAILED,
}

data class FusedLocationConfirmTaskResult(
    val disposition: FusedLocationConfirmTaskDisposition,
    val reason: String,
    val queueOwnershipFinalized: Boolean = false,
) {
    val shouldRemove: Boolean
        get() = disposition == FusedLocationConfirmTaskDisposition.COMPLETED ||
            disposition == FusedLocationConfirmTaskDisposition.REARMED ||
            disposition == FusedLocationConfirmTaskDisposition.PARKED ||
            disposition == FusedLocationConfirmTaskDisposition.DROPPED ||
            disposition == FusedLocationConfirmTaskDisposition.FAILED
}

internal enum class TransientConfirmFailureAction {
    RETRY_PROXIMITY_PULSE,
    REARM_TRANSITION,
}

internal fun transientConfirmFailureAction(
    kind: ForegroundWorkKind,
): TransientConfirmFailureAction =
    if (kind == ForegroundWorkKind.CONFIRM_PROXIMITY) {
        TransientConfirmFailureAction.RETRY_PROXIMITY_PULSE
    } else {
        TransientConfirmFailureAction.REARM_TRANSITION
    }

internal fun proximityConfirmAttemptsExhaustedResult(
    failureReason: String,
    attempts: Int,
): FusedLocationConfirmTaskResult =
    FusedLocationConfirmTaskResult(
        disposition = FusedLocationConfirmTaskDisposition.COMPLETED,
        reason = "proximity_attempts_exhausted:$failureReason:attempts=$attempts",
    )

internal fun pulseConfirmSessionMaxAgeMillis(config: SmartGeofenceConfig): Long {
    val longestPulseDelayMillis = maxOf(
        AdaptivePulseRate.intervalMillis(
            config,
            ProximityPulsePurpose.PROXIMITY,
            activeHoursNow = false,
        ),
        AdaptivePulseRate.intervalMillis(
            config,
            ProximityPulsePurpose.TRANSITION_CONFIRMATION,
            activeHoursNow = false,
        ),
    )
    val attemptBudget = config.proximityConfirmMaxAttempts.coerceAtLeast(1).toLong()
    val retryWindowMillis =
        if (longestPulseDelayMillis > Long.MAX_VALUE / attemptBudget) {
            Long.MAX_VALUE
        } else {
            longestPulseDelayMillis * attemptBudget
        }
    return maxOf(config.confirmQueueMaxAgeMillis, retryWindowMillis)
}

internal data class NativeFallbackEmissionResult(
    val queueStatus: ForegroundQueueMutationStatus,
    val emitted: Boolean,
)

internal fun emitNativeFallbackIfClaimed(
    claim: () -> ForegroundQueueMutationResult,
    emit: () -> Boolean,
): NativeFallbackEmissionResult {
    val mutation = claim()
    return NativeFallbackEmissionResult(
        queueStatus = mutation.status,
        emitted = if (mutation.status == ForegroundQueueMutationStatus.CLAIMED) emit() else false,
    )
}

internal fun queueMutationDropReason(
    status: ForegroundQueueMutationStatus,
    action: String,
): String =
    when (status) {
        ForegroundQueueMutationStatus.MISSING -> "queue_item_missing_before_$action"
        ForegroundQueueMutationStatus.SUPERSEDED -> "queue_item_superseded_before_$action"
        else -> "queue_item_${status.name.lowercase()}_before_$action"
    }

object FusedLocationConfirmTaskHandler {
    private const val TAG = "FusedLocationConfirmTaskHandler"

    fun execute(
        context: Context,
        item: ForegroundWorkItem,
        onComplete: (FusedLocationConfirmTaskResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        val completed = AtomicBoolean(false)
        fun complete(result: FusedLocationConfirmTaskResult) {
            if (completed.compareAndSet(false, true)) {
                SmartGeofenceDiagnostics.recordTrace(
                    appContext,
                    stage = "confirm_work_finished",
                    reasonCode = result.disposition.name.lowercase(),
                    traceId = item.traceId,
                    source = item.source,
                    extras = linkedMapOf(
                        "requestId" to item.id,
                        "attempt" to item.attemptCount,
                        "detail" to result.reason,
                    ),
                )
                onComplete(result)
            }
        }
        fun completeOutcome(outcome: FusedLocationConfirm.LocationConfirmOutcome) {
            val result = try {
                handleOutcome(appContext, item, outcome)
            } catch (e: Throwable) {
                unexpectedFailureResult(appContext, item, e)
            }
            complete(result)
        }

        try {
            executeInternal(appContext, item, complete = ::complete, completeOutcome = ::completeOutcome)
        } catch (e: Throwable) {
            complete(unexpectedFailureResult(appContext, item, e))
        }
    }

    private fun executeInternal(
        appContext: Context,
        item: ForegroundWorkItem,
        complete: (FusedLocationConfirmTaskResult) -> Unit,
        completeOutcome: (FusedLocationConfirm.LocationConfirmOutcome) -> Unit,
    ) {
        val config = SmartGeofenceConfigStore.load(appContext)
        val nowMillis = System.currentTimeMillis()
        val ageMs = item.sessionAgeAt(nowMillis)
        val usesPulseRetryWindow =
            item.kind == ForegroundWorkKind.CONFIRM_PROXIMITY &&
                FusedLocationConfirm.isPulseSource(item.source)
        val maxAgeMs = if (usesPulseRetryWindow) {
            pulseConfirmSessionMaxAgeMillis(config)
        } else {
            config.confirmQueueMaxAgeMillis
        }

        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "confirm_work_started",
            reasonCode = "started",
            traceId = item.traceId,
            fenceId = item.fenceId,
            source = item.source,
            extras = linkedMapOf(
                "requestId" to item.id,
                "ageMillis" to ageMs,
                "maxAgeMillis" to maxAgeMs,
                "maxAgePolicy" to
                    if (usesPulseRetryWindow) "pulse_retry_window" else "configured",
                "attempt" to item.attemptCount,
                "kind" to item.kind.name.lowercase(),
            ),
        )
        if (!item.isReadyAt(nowMillis)) {
            complete(
                FusedLocationConfirmTaskResult(
                    FusedLocationConfirmTaskDisposition.DEFERRED,
                    "not_ready until=${item.notBeforeMillis}"
                )
            )
            return
        }
        if (item.sessionStartedAtMillis <= 0L || (maxAgeMs > 0L && ageMs > maxAgeMs)) {
            val nativeFallbackReason = "stale_confirm_work:age=${ageMs}ms:maxAge=${maxAgeMs}ms"
            val isNativeConfirm = item.isNativeExitConfirm() || item.isNativeEnterConfirm()
            val nativeFallback = if (isNativeConfirm) {
                emitNativeFallbackIfClaimed(
                    claim = { WakeEventCoordinator.claimConfirmIfUnchanged(appContext, item) },
                    emit = {
                        when {
                            item.isNativeExitConfirm() ->
                                SmartGeofenceEventProcessor.emitPendingNativeExitFallbacks(
                                    appContext,
                                    item.nativeTransitionInstances,
                                    nativeFallbackReason,
                                )
                            item.isNativeEnterConfirm() ->
                                SmartGeofenceEventProcessor.emitPendingNativeEnterFallbacks(
                                    appContext,
                                    item.nativeTransitionInstances,
                                    nativeFallbackReason,
                                )
                            else -> false
                        }
                    },
                )
            } else {
                null
            }
            if (nativeFallback != null &&
                nativeFallback.queueStatus != ForegroundQueueMutationStatus.CLAIMED
            ) {
                val dropReason = queueMutationDropReason(
                    nativeFallback.queueStatus,
                    "native_fallback",
                )
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Skipped stale native fallback id=${item.id} reason=$dropReason.",
                )
                complete(
                    FusedLocationConfirmTaskResult(
                        FusedLocationConfirmTaskDisposition.DROPPED,
                        dropReason,
                        queueOwnershipFinalized = true,
                    )
                )
                return
            }
            val nativeFallbackEmitted = nativeFallback?.emitted ?: false
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Dropping stale confirm work id=${item.id} kind=${item.kind} fence=${item.fenceId} " +
                    "source=${item.source} " +
                    "sessionAge=${ageMs}ms maxAge=${maxAgeMs}ms attempts=${item.attemptCount} " +
                    "nativeFallbackEmitted=$nativeFallbackEmitted."
            )
            complete(
                FusedLocationConfirmTaskResult(
                    FusedLocationConfirmTaskDisposition.DROPPED,
                    "stale age=${ageMs}ms maxAge=${maxAgeMs}ms",
                    queueOwnershipFinalized = nativeFallback != null,
                )
            )
            return
        }
        if (LocationServicesState.isLocationEnabled(appContext) == false) {
            complete(
                LocationConfirmRearm.parkForLocationDisabled(
                    appContext,
                    item,
                    "pre_acquisition_location_services_disabled",
                )
            )
            return
        }

        when (item.kind) {
            ForegroundWorkKind.CONFIRM_PROXIMITY ->
                runProximityConfirm(appContext, item, ageMs, completeOutcome)
            ForegroundWorkKind.CONFIRM_OUTSIDE -> runOutsideConfirm(appContext, item, ageMs, completeOutcome)
            ForegroundWorkKind.CONFIRM_INSIDE -> runInsideConfirm(appContext, item, ageMs, completeOutcome)
        }
    }

    private fun runProximityConfirm(
        context: Context,
        item: ForegroundWorkItem,
        ageMs: Long,
        onComplete: (FusedLocationConfirm.LocationConfirmOutcome) -> Unit,
    ) {
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Running proximity confirm work id=${item.id} source=${item.source} " +
                "sessionAge=${ageMs}ms attempts=${item.attemptCount}."
        )
        runCatching {
            ProximityPulseController.onConfirmAttemptStarted(context, item.source)
        }.onFailure { error ->
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Could not move Pulse cadence before acquisition source=${item.source}; " +
                    "continuing location confirm.",
                error,
            )
        }
        FusedLocationConfirm.confirmProximity(context, item.source, item.traceId) { outcome ->
            onComplete(outcome)
        }
    }

    private fun runOutsideConfirm(
        context: Context,
        item: ForegroundWorkItem,
        ageMs: Long,
        onComplete: (FusedLocationConfirm.LocationConfirmOutcome) -> Unit,
    ) {
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Running outside confirm work id=${item.id} source=${item.source} " +
                "sessionAge=${ageMs}ms attempts=${item.attemptCount}."
        )
        FusedLocationConfirm.confirmOutside(
            context = context,
            source = item.source,
            traceId = item.traceId,
            nativeTransitionInstances = if (item.isNativeExitConfirm()) {
                item.nativeTransitionInstances
            } else {
                null
            },
        ) { outcome ->
            onComplete(outcome)
        }
    }

    private fun runInsideConfirm(
        context: Context,
        item: ForegroundWorkItem,
        ageMs: Long,
        onComplete: (FusedLocationConfirm.LocationConfirmOutcome) -> Unit,
    ) {
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Running inside confirm work id=${item.id} source=${item.source} " +
                "sessionAge=${ageMs}ms attempts=${item.attemptCount}."
        )
        FusedLocationConfirm.confirmInside(
            context = context,
            source = item.source,
            traceId = item.traceId,
            nativeTransitionInstances = if (item.isNativeEnterConfirm()) {
                item.nativeTransitionInstances
            } else {
                null
            },
        ) { outcome ->
            onComplete(outcome)
        }
    }

    internal fun handleOutcome(
        context: Context,
        item: ForegroundWorkItem,
        outcome: FusedLocationConfirm.LocationConfirmOutcome,
    ): FusedLocationConfirmTaskResult =
        when (outcome.status) {
            com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus.SUCCESS ->
                FusedLocationConfirmTaskResult(
                    FusedLocationConfirmTaskDisposition.COMPLETED,
                    outcome.reason,
                )
            com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus.NULL_LOCATION,
            com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus.FAILURE,
            com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus.TIMEOUT ->
                handleTransientFailure(context, item, outcome)
            com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus.PERMISSION_MISSING,
            com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus.SECURITY_EXCEPTION ->
                FusedLocationConfirmTaskResult(
                    FusedLocationConfirmTaskDisposition.FAILED,
                    outcome.reason,
                )
        }

    private fun handleTransientFailure(
        context: Context,
        item: ForegroundWorkItem,
        outcome: FusedLocationConfirm.LocationConfirmOutcome,
    ): FusedLocationConfirmTaskResult =
        when (transientConfirmFailureAction(item.kind)) {
            TransientConfirmFailureAction.RETRY_PROXIMITY_PULSE ->
                LocationConfirmRearm.rearmProximityPulse(context, item, outcome)
            TransientConfirmFailureAction.REARM_TRANSITION ->
                LocationConfirmRearm.rearm(context, item, outcome)
        }

    private fun unexpectedFailureResult(
        context: Context,
        item: ForegroundWorkItem,
        failure: Throwable,
    ): FusedLocationConfirmTaskResult {
        SmartGeofenceLogger.w(
            context,
            TAG,
            "Unexpected confirm task failure id=${item.id} kind=${item.kind}: ${failure.message}",
            failure,
        )
        return try {
            handleTransientFailure(
                context,
                item,
                FusedLocationConfirm.LocationConfirmOutcome(
                    status = com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus.FAILURE,
                    reason = "task_exception",
                    failure = failure,
                ),
            )
        } catch (rearmFailure: Throwable) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Could not settle failed confirm task id=${item.id}: ${rearmFailure.message}",
                rearmFailure,
            )
            FusedLocationConfirmTaskResult(
                FusedLocationConfirmTaskDisposition.FAILED,
                "task_exception_handling_failed:${failure.javaClass.simpleName}",
            )
        }
    }

    private fun ForegroundWorkItem.isNativeExitConfirm(): Boolean =
        kind == ForegroundWorkKind.CONFIRM_OUTSIDE &&
            source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM)

    private fun ForegroundWorkItem.isNativeEnterConfirm(): Boolean =
        kind == ForegroundWorkKind.CONFIRM_INSIDE &&
            source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)
}
