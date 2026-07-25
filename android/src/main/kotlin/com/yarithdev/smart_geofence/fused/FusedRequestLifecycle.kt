package com.yarithdev.smart_geofence.fused

import kotlin.math.abs

internal enum class FusedRequestKind {
    BALANCED,
    PASSIVE,
}

internal data class FusedRequestSpec(
    val priorityName: String,
    val intervalMillis: Long,
    val fastestIntervalMillis: Long,
    val maxWaitMillis: Long,
    val minDisplacementMeters: Double,
    val adaptiveMode: String? = null,
) {
    fun sameRequest(other: FusedRequestSpec): Boolean =
        priorityName == other.priorityName &&
            intervalMillis == other.intervalMillis &&
            fastestIntervalMillis == other.fastestIntervalMillis &&
            maxWaitMillis == other.maxWaitMillis &&
            abs(minDisplacementMeters - other.minDisplacementMeters) < 0.01
}

internal data class FusedRequestOperationResult(
    val succeeded: Boolean,
    val failureReason: String? = null,
)

internal interface FusedRequestBackend {
    fun pendingIntentExists(kind: FusedRequestKind): Boolean

    fun request(
        kind: FusedRequestKind,
        spec: FusedRequestSpec,
        onComplete: (FusedRequestOperationResult) -> Unit,
    )

    fun remove(
        kind: FusedRequestKind,
        onComplete: (FusedRequestOperationResult) -> Unit,
    )

    fun cancelPendingIntent(kind: FusedRequestKind)
}

internal data class FusedRequestPartState(
    val desired: Boolean = false,
    val desiredEpoch: Long = 0L,
    val desiredSpec: FusedRequestSpec? = null,
    val confirmed: Boolean = false,
    val confirmedSpec: FusedRequestSpec? = null,
    val requestInFlight: Boolean = false,
    val removalInFlight: Boolean = false,
    val removalConfirmed: Boolean = false,
    val operationGeneration: Long = 0L,
    val operationDesiredEpoch: Long = 0L,
    val operationStartedAtMillis: Long? = null,
    val requestReplacesConfirmed: Boolean = false,
    val requestBlockedEpoch: Long? = null,
    val removalBlockedEpoch: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val lastFailureReason: String? = null,
    val failureSerial: Long = 0L,
)

internal data class FusedRequestLifecycleState(
    val nextOperationGeneration: Long = 0L,
    val balanced: FusedRequestPartState = FusedRequestPartState(),
    val passive: FusedRequestPartState = FusedRequestPartState(),
    val staleCallbackCount: Long = 0L,
    val lastStaleCallbackReason: String? = null,
)

internal enum class FusedReconcileDisposition {
    HEALTHY,
    RECONCILED,
    STOPPED,
    FAILED,
    SUPERSEDED,
}

internal data class FusedReconcileResult(
    val disposition: FusedReconcileDisposition,
    val reason: String,
)

internal data class FusedDesiredUpdate(
    val desiredChanged: Boolean,
    val requestRequired: Boolean,
)

internal class FusedRequestLifecycle(
    initialState: FusedRequestLifecycleState,
    private val backend: FusedRequestBackend,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val persist: (FusedRequestLifecycleState) -> Unit = {},
    private val onConfirmed: (FusedRequestKind, FusedRequestSpec) -> Unit = { _, _ -> },
    private val onNoConfirmedRequest: (FusedRequestKind) -> Unit = {},
) {
    private data class Waiter(
        val kind: FusedRequestKind,
        val desiredEpoch: Long,
        val failureSerial: Long,
        val initiallyHealthy: Boolean,
        val wantsDesired: Boolean,
        val callback: (FusedReconcileResult) -> Unit,
    )

    private val processBootstrapKinds = FusedRequestKind.entries.filter { kind ->
        val part = when (kind) {
            FusedRequestKind.BALANCED -> initialState.balanced
            FusedRequestKind.PASSIVE -> initialState.passive
        }
        val operationInterrupted = part.requestInFlight || part.removalInFlight
        val blockedAtCurrentEpoch = part.requestBlockedEpoch == part.desiredEpoch ||
            part.removalBlockedEpoch == part.desiredEpoch
        operationInterrupted ||
            (!blockedAtCurrentEpoch &&
                (part.desired ||
                    part.confirmed ||
                    (part.desiredEpoch > 0L && !part.removalConfirmed)))
    }
    private var state = recoverInterruptedOperations(initialState, nowMillis())
    private val waiters = mutableListOf<Waiter>()
    private var processBootstrapReconciled = processBootstrapKinds.isEmpty()

    init {
        persist(state)
    }

    @Synchronized
    fun snapshot(): FusedRequestLifecycleState = state

    @Synchronized
    fun reconcileProcessBootstrap(reason: String = "process_bootstrap"): Boolean {
        if (processBootstrapReconciled) return false
        processBootstrapReconciled = true
        processBootstrapKinds.forEach { kind ->
            driveKind(kind, allowDesiredRecovery = true, reason = reason)
            settleWaiters(kind, reason)
        }
        return true
    }

    @Synchronized
    fun setDesired(
        kind: FusedRequestKind,
        desired: Boolean,
        spec: FusedRequestSpec?,
        reason: String,
        onComplete: ((FusedReconcileResult) -> Unit)? = null,
    ): FusedDesiredUpdate {
        require(!desired || spec != null) { "A desired fused request requires a specification." }
        val current = part(kind)
        val desiredSpec = spec.takeIf { desired }
        val changed = current.desired != desired || current.desiredSpec != desiredSpec
        if (changed) {
            setPart(
                kind,
                current.copy(
                    desired = desired,
                    desiredEpoch = current.desiredEpoch + 1L,
                    desiredSpec = desiredSpec,
                ),
            )
        }
        clearOperationBlocks(kind)
        val requestRequired = requestRequired(kind)
        addWaiter(kind, onComplete, wantsDesired = desired)
        driveKind(kind, allowDesiredRecovery = true, reason = reason)
        settleWaiters(kind, reason)
        return FusedDesiredUpdate(changed, requestRequired)
    }

    @Synchronized
    fun refreshConfirmed(
        kind: FusedRequestKind,
        reason: String,
    ): Boolean {
        var current = part(kind)
        val desiredSpec = current.desiredSpec ?: return false
        if (!current.desired || current.requestInFlight || current.removalInFlight) return false

        clearOperationBlocks(kind)
        current = part(kind)
        if (!current.confirmed || !backend.pendingIntentExists(kind)) {
            driveKind(kind, allowDesiredRecovery = true, reason = reason)
            settleWaiters(kind, reason)
            return false
        }

        beginRequest(kind, desiredSpec, reason)
        return true
    }

    @Synchronized
    fun stopAll(
        reason: String,
        onComplete: ((FusedRequestKind, FusedReconcileResult) -> Unit)? = null,
    ) {
        FusedRequestKind.entries.forEach { kind ->
            val current = part(kind)
            if (current.desired || current.desiredSpec != null) {
                setPart(
                    kind,
                    current.copy(
                        desired = false,
                        desiredEpoch = current.desiredEpoch + 1L,
                        desiredSpec = null,
                    ),
                )
            }
            clearOperationBlocks(kind)
        }
        FusedRequestKind.entries.forEach { kind ->
            addWaiter(
                kind,
                onComplete?.let { callback -> { result -> callback(kind, result) } },
                wantsDesired = false,
            )
        }
        FusedRequestKind.entries.forEach { kind ->
            driveKind(kind, allowDesiredRecovery = true, reason = reason)
        }
        FusedRequestKind.entries.forEach { kind -> settleWaiters(kind, reason) }
    }

    private fun requestRequired(kind: FusedRequestKind): Boolean {
        val current = part(kind)
        val desiredSpec = current.desiredSpec ?: return false
        if (!current.desired) return false
        if (current.removalInFlight) return true
        if (current.requestInFlight) {
            return current.operationDesiredEpoch != current.desiredEpoch
        }
        if (!current.confirmed || !backend.pendingIntentExists(kind)) return true
        val confirmedSpec = current.confirmedSpec ?: return true
        return !confirmedSpec.sameRequest(desiredSpec)
    }

    private fun addWaiter(
        kind: FusedRequestKind,
        callback: ((FusedReconcileResult) -> Unit)?,
        wantsDesired: Boolean,
    ) {
        if (callback == null) return
        val current = part(kind)
        waiters += Waiter(
            kind = kind,
            desiredEpoch = current.desiredEpoch,
            failureSerial = current.failureSerial,
            initiallyHealthy = if (wantsDesired) isHealthy(kind) else isStopped(kind),
            wantsDesired = wantsDesired,
            callback = callback,
        )
    }

    private fun driveKind(
        kind: FusedRequestKind,
        allowDesiredRecovery: Boolean,
        reason: String,
    ) {
        var current = part(kind)
        if (current.requestInFlight || current.removalInFlight) return

        val tokenExists = backend.pendingIntentExists(kind)
        if (current.confirmed && !tokenExists) {
            current = current.copy(
                confirmed = false,
                confirmedSpec = null,
                removalConfirmed = false,
                lastFailureAtMillis = nowMillis(),
                lastFailureReason = "confirmed_pending_intent_missing:$reason",
                failureSerial = current.failureSerial + 1L,
            )
            setPart(kind, current)
            onNoConfirmedRequest(kind)
        }

        current = part(kind)
        if (!current.desired) {
            if ((current.confirmed || tokenExists) &&
                current.removalBlockedEpoch != current.desiredEpoch
            ) {
                beginRemoval(kind, reason)
            }
            return
        }

        val desiredSpec = current.desiredSpec ?: return
        if (!current.confirmed && tokenExists) {
            if (allowDesiredRecovery &&
                current.removalBlockedEpoch != current.desiredEpoch
            ) {
                beginRemoval(kind, "uncertain:$reason")
            }
            return
        }

        if (current.confirmed) {
            val confirmedSpec = current.confirmedSpec
            if (confirmedSpec != null && confirmedSpec.sameRequest(desiredSpec)) {
                if (confirmedSpec != desiredSpec) {
                    setPart(kind, current.copy(confirmedSpec = desiredSpec))
                    onConfirmed(kind, desiredSpec)
                }
                return
            }
        }
        if (!allowDesiredRecovery) return
        if (current.requestBlockedEpoch == current.desiredEpoch) return
        beginRequest(kind, desiredSpec, reason)
    }

    private fun beginRequest(
        kind: FusedRequestKind,
        spec: FusedRequestSpec,
        reason: String,
    ) {
        val current = part(kind)
        val generation = nextGeneration()
        setPart(
            kind,
            current.copy(
                requestInFlight = true,
                removalInFlight = false,
                removalConfirmed = false,
                operationGeneration = generation,
                operationDesiredEpoch = current.desiredEpoch,
                operationStartedAtMillis = nowMillis(),
                requestReplacesConfirmed = current.confirmed,
                requestBlockedEpoch = null,
            ),
        )
        backend.request(kind, spec) { result ->
            onRequestCompleted(kind, generation, spec, result, reason)
        }
    }

    @Synchronized
    private fun onRequestCompleted(
        kind: FusedRequestKind,
        generation: Long,
        requestedSpec: FusedRequestSpec,
        result: FusedRequestOperationResult,
        reason: String,
    ) {
        val current = part(kind)
        if (!current.requestInFlight || current.operationGeneration != generation) {
            recordStaleCallback("request:${kind.name.lowercase()}:generation=$generation")
            return
        }
        val operationSuperseded = current.operationDesiredEpoch != current.desiredEpoch
        val at = nowMillis()
        if (result.succeeded) {
            setPart(
                kind,
                current.copy(
                    confirmed = true,
                    confirmedSpec = requestedSpec,
                    requestInFlight = false,
                    removalConfirmed = false,
                    operationStartedAtMillis = null,
                    requestReplacesConfirmed = false,
                    requestBlockedEpoch = null,
                    lastSuccessAtMillis = at,
                ),
            )
            if (operationSuperseded) {
                recordStaleCallback(
                    "request:${kind.name.lowercase()}:desired_epoch=${current.operationDesiredEpoch}",
                )
            } else if (part(kind).desired) {
                onConfirmed(kind, requestedSpec)
            }
            driveKind(kind, allowDesiredRecovery = true, reason = "request_completed:$reason")
            settleWaiters(kind, "request_completed:$reason")
            return
        }

        if (operationSuperseded) {
            setPart(
                kind,
                current.copy(
                    requestInFlight = false,
                    operationStartedAtMillis = null,
                    requestReplacesConfirmed = false,
                ),
            )
            recordStaleCallback(
                "request_failure:${kind.name.lowercase()}:desired_epoch=${current.operationDesiredEpoch}",
            )
            driveKind(kind, allowDesiredRecovery = true, reason = "superseded_request_failed:$reason")
            settleWaiters(kind, "superseded_request_failed:$reason")
            return
        }

        val retainedConfirmed = current.requestReplacesConfirmed
        setPart(
            kind,
            current.copy(
                confirmed = retainedConfirmed,
                confirmedSpec = current.confirmedSpec.takeIf { retainedConfirmed },
                requestInFlight = false,
                operationStartedAtMillis = null,
                requestReplacesConfirmed = false,
                requestBlockedEpoch = current.desiredEpoch,
                lastFailureAtMillis = at,
                lastFailureReason = result.failureReason ?: "request_failed:$reason",
                failureSerial = current.failureSerial + 1L,
            ),
        )
        val retainedSpec = part(kind).confirmedSpec
        if (retainedConfirmed && retainedSpec != null && part(kind).desired) {
            onConfirmed(kind, retainedSpec)
        } else {
            onNoConfirmedRequest(kind)
        }
        driveKind(kind, allowDesiredRecovery = false, reason = "request_failed:$reason")
        settleWaiters(kind, "request_failed:$reason")
    }

    private fun beginRemoval(kind: FusedRequestKind, reason: String) {
        val current = part(kind)
        val generation = nextGeneration()
        setPart(
            kind,
            current.copy(
                requestInFlight = false,
                removalInFlight = true,
                removalConfirmed = false,
                operationGeneration = generation,
                operationDesiredEpoch = current.desiredEpoch,
                operationStartedAtMillis = nowMillis(),
                requestReplacesConfirmed = false,
                removalBlockedEpoch = null,
            ),
        )
        backend.remove(kind) { result ->
            onRemovalCompleted(kind, generation, result, reason)
        }
    }

    @Synchronized
    private fun onRemovalCompleted(
        kind: FusedRequestKind,
        generation: Long,
        result: FusedRequestOperationResult,
        reason: String,
    ) {
        val current = part(kind)
        if (!current.removalInFlight || current.operationGeneration != generation) {
            recordStaleCallback("remove:${kind.name.lowercase()}:generation=$generation")
            return
        }
        val operationSuperseded = current.operationDesiredEpoch != current.desiredEpoch
        val at = nowMillis()
        if (!result.succeeded) {
            setPart(
                kind,
                current.copy(
                    removalInFlight = false,
                    removalConfirmed = false,
                    operationStartedAtMillis = null,
                    removalBlockedEpoch = current.desiredEpoch,
                    lastFailureAtMillis = at,
                    lastFailureReason = result.failureReason ?: "removal_failed:$reason",
                    failureSerial = current.failureSerial + 1L,
                ),
            )
            if (operationSuperseded) {
                recordStaleCallback(
                    "remove_failure:${kind.name.lowercase()}:desired_epoch=${current.operationDesiredEpoch}",
                )
            }
            driveKind(kind, allowDesiredRecovery = false, reason = "removal_failed:$reason")
            settleWaiters(kind, "removal_failed:$reason")
            return
        }

        backend.cancelPendingIntent(kind)
        setPart(
            kind,
            current.copy(
                confirmed = false,
                confirmedSpec = null,
                removalInFlight = false,
                removalConfirmed = true,
                operationStartedAtMillis = null,
                removalBlockedEpoch = null,
                lastSuccessAtMillis = at,
            ),
        )
        driveKind(kind, allowDesiredRecovery = part(kind).desired, reason = "removal_completed:$reason")
        settleWaiters(kind, "removal_completed:$reason")
    }

    private fun clearOperationBlocks(kind: FusedRequestKind) {
        val current = part(kind)
        if (current.requestBlockedEpoch != null || current.removalBlockedEpoch != null) {
            setPart(
                kind,
                current.copy(requestBlockedEpoch = null, removalBlockedEpoch = null),
            )
        }
    }

    private fun settleWaiters(kind: FusedRequestKind, reason: String) {
        val callbacks = mutableListOf<Pair<Waiter, FusedReconcileResult>>()
        val current = part(kind)
        val iterator = waiters.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (waiter.kind != kind) continue
            val result = when {
                waiter.desiredEpoch != current.desiredEpoch -> FusedReconcileResult(
                    FusedReconcileDisposition.SUPERSEDED,
                    "desired_epoch_changed:$reason",
                )
                waiter.wantsDesired && !current.desired -> FusedReconcileResult(
                    FusedReconcileDisposition.SUPERSEDED,
                    "request_stopped:$reason",
                )
                !waiter.wantsDesired && current.desired -> FusedReconcileResult(
                    FusedReconcileDisposition.SUPERSEDED,
                    "request_restarted:$reason",
                )
                waiter.wantsDesired && isHealthy(kind) -> FusedReconcileResult(
                    if (waiter.initiallyHealthy) {
                        FusedReconcileDisposition.HEALTHY
                    } else {
                        FusedReconcileDisposition.RECONCILED
                    },
                    if (waiter.initiallyHealthy) "already_healthy:$reason" else "request_confirmed:$reason",
                )
                !hasOperationInFlight(current) && current.failureSerial > waiter.failureSerial ->
                    FusedReconcileResult(
                        FusedReconcileDisposition.FAILED,
                        "operation_failed:$reason",
                    )
                !waiter.wantsDesired && isStopped(kind) -> FusedReconcileResult(
                    FusedReconcileDisposition.STOPPED,
                    "request_removed:$reason",
                )
                else -> null
            }
            if (result != null) {
                iterator.remove()
                callbacks += waiter to result
            }
        }
        callbacks.forEach { (waiter, result) -> waiter.callback(result) }
    }

    private fun isHealthy(kind: FusedRequestKind): Boolean {
        val current = part(kind)
        return current.desired &&
            current.confirmed &&
            !hasOperationInFlight(current) &&
            current.desiredSpec != null &&
            current.desiredSpec == current.confirmedSpec &&
            backend.pendingIntentExists(kind)
    }

    private fun isStopped(kind: FusedRequestKind): Boolean {
        val current = part(kind)
        return !current.desired &&
            !current.confirmed &&
            !hasOperationInFlight(current) &&
            !backend.pendingIntentExists(kind)
    }

    private fun hasOperationInFlight(part: FusedRequestPartState): Boolean =
        part.requestInFlight || part.removalInFlight

    private fun recordStaleCallback(reason: String) {
        state = state.copy(
            staleCallbackCount = state.staleCallbackCount + 1L,
            lastStaleCallbackReason = reason,
        )
        persist(state)
    }

    private fun nextGeneration(): Long {
        val generation = state.nextOperationGeneration + 1L
        state = state.copy(nextOperationGeneration = generation)
        persist(state)
        return generation
    }

    private fun part(kind: FusedRequestKind): FusedRequestPartState = when (kind) {
        FusedRequestKind.BALANCED -> state.balanced
        FusedRequestKind.PASSIVE -> state.passive
    }

    private fun setPart(kind: FusedRequestKind, value: FusedRequestPartState) {
        state = when (kind) {
            FusedRequestKind.BALANCED -> state.copy(balanced = value)
            FusedRequestKind.PASSIVE -> state.copy(passive = value)
        }
        persist(state)
    }

    companion object {
        internal fun recoverInterruptedOperations(
            state: FusedRequestLifecycleState,
            atMillis: Long,
        ): FusedRequestLifecycleState {
            fun recover(kind: String, part: FusedRequestPartState): FusedRequestPartState {
                if (!part.requestInFlight && !part.removalInFlight) return part
                return part.copy(
                    confirmed = false,
                    confirmedSpec = null,
                    requestInFlight = false,
                    removalInFlight = false,
                    removalConfirmed = false,
                    operationStartedAtMillis = null,
                    requestReplacesConfirmed = false,
                    requestBlockedEpoch = null,
                    removalBlockedEpoch = null,
                    lastFailureAtMillis = atMillis,
                    lastFailureReason = "process_interrupted:$kind",
                    failureSerial = part.failureSerial + 1L,
                )
            }

            val interrupted = state.balanced.requestInFlight ||
                state.balanced.removalInFlight ||
                state.passive.requestInFlight ||
                state.passive.removalInFlight
            if (!interrupted) return state
            return state.copy(
                balanced = recover("balanced", state.balanced),
                passive = recover("passive", state.passive),
            )
        }
    }
}
