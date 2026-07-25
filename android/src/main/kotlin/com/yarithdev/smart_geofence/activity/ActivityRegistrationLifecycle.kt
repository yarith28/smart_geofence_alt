package com.yarithdev.smart_geofence.activity

internal enum class ActivityRegistrationKind {
    TRANSITION,
    PERIODIC,
}

internal enum class ActivityPeriodicMode(val configValue: String) {
    NONE("none"),
    BOOTSTRAP("bootstrap"),
    PERSISTENT_BACKSTOP("persistent_backstop");

    companion object {
        fun fromConfigValue(value: String?): ActivityPeriodicMode? =
            entries.firstOrNull { it.configValue == value }
    }
}

internal data class ActivityRegistrationOperationResult(
    val succeeded: Boolean,
    val failureReason: String? = null,
)

internal interface ActivityRegistrationBackend {
    fun pendingIntentExists(kind: ActivityRegistrationKind): Boolean

    fun request(
        kind: ActivityRegistrationKind,
        intervalMillis: Long?,
        onComplete: (ActivityRegistrationOperationResult) -> Unit,
    )

    fun remove(
        kind: ActivityRegistrationKind,
        onComplete: (ActivityRegistrationOperationResult) -> Unit,
    )

    fun cancelPendingIntent(kind: ActivityRegistrationKind)
}

internal data class ActivityRegistrationPartState(
    val confirmed: Boolean = false,
    val requestInFlight: Boolean = false,
    val removalInFlight: Boolean = false,
    val removalConfirmed: Boolean = false,
    val operationGeneration: Long = 0L,
    val operationControllerEpoch: Long = 0L,
    val operationMonitoringSessionGeneration: Long = 0L,
    val operationStartedAtMillis: Long? = null,
    val payloadAcceptanceElapsedRealtimeNanos: Long = 0L,
    val requestReplacesConfirmed: Boolean = false,
    val requestBlockedEpoch: Long? = null,
    val removalBlockedEpoch: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val lastFailureReason: String? = null,
)

internal data class ActivityRegistrationLifecycleState(
    val controllerDesired: Boolean = false,
    val controllerEpoch: Long = 0L,
    val monitoringSessionGeneration: Long = 0L,
    val nextOperationGeneration: Long = 0L,
    val desiredPeriodicIntervalMillis: Long? = null,
    val confirmedPeriodicIntervalMillis: Long? = null,
    val periodicOwner: String? = null,
    val transition: ActivityRegistrationPartState = ActivityRegistrationPartState(),
    val periodic: ActivityRegistrationPartState = ActivityRegistrationPartState(),
    val failureSerial: Long = 0L,
    val staleCallbackCount: Long = 0L,
    val lastStaleCallbackReason: String? = null,
    val periodicBackstopEnabled: Boolean = false,
    val periodicMode: ActivityPeriodicMode = ActivityPeriodicMode.NONE,
    val bootstrapRequestedAtMillis: Long? = null,
    val bootstrapDeadlineMillis: Long? = null,
    val bootstrapResultReceived: Boolean = false,
    val bootstrapCompleted: Boolean = false,
    val periodicRemovalRequired: Boolean = false,
)

internal fun ActivityRegistrationPartState.belongsToMonitoringSession(
    monitoringSessionGeneration: Long,
): Boolean = operationMonitoringSessionGeneration == monitoringSessionGeneration

internal enum class ActivityReconcileDisposition {
    HEALTHY,
    RECONCILED,
    STOPPED,
    FAILED,
    SUPERSEDED,
    NOT_DESIRED,
}

internal data class ActivityReconcileResult(
    val disposition: ActivityReconcileDisposition,
    val reason: String,
) {
    val succeeded: Boolean
        get() = disposition == ActivityReconcileDisposition.HEALTHY ||
            disposition == ActivityReconcileDisposition.RECONCILED ||
            disposition == ActivityReconcileDisposition.STOPPED

    val noLongerDesired: Boolean
        get() = disposition == ActivityReconcileDisposition.NOT_DESIRED
}

internal class ActivityRegistrationLifecycle(
    initialState: ActivityRegistrationLifecycleState,
    private val backend: ActivityRegistrationBackend,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nowElapsedRealtimeNanos: () -> Long = { nowMillis() * 1_000_000L },
    private val persist: (ActivityRegistrationLifecycleState) -> Unit = {},
) {
    private data class Waiter(
        val epoch: Long,
        val failureSerial: Long,
        val initiallyHealthy: Boolean,
        val wantsDesired: Boolean,
        val callback: (ActivityReconcileResult) -> Unit,
    )

    private var state = recoverInterruptedOperations(
        normalizeLegacyPeriodicMode(initialState),
        nowMillis(),
    )
    private val waiters = mutableListOf<Waiter>()

    init {
        persist(state)
    }

    @Synchronized
    fun snapshot(): ActivityRegistrationLifecycleState = state

    @Synchronized
    fun setControllerDesired(
        desired: Boolean,
        periodicIntervalMillis: Long?,
        reason: String,
        onComplete: ((ActivityReconcileResult) -> Unit)? = null,
        periodicBackstopEnabled: Boolean = false,
    ) {
        val normalizedInterval = periodicIntervalMillis?.coerceAtLeast(0L)
        val controllerDesiredChanged = state.controllerDesired != desired
        val periodicPolicyChanged = state.periodicBackstopEnabled != periodicBackstopEnabled ||
            (desired && periodicBackstopEnabled &&
                state.desiredPeriodicIntervalMillis != normalizedInterval)
        val changed = controllerDesiredChanged ||
            (desired && periodicPolicyChanged)
        if (changed) {
            val monitoringSessionChanged = controllerDesiredChanged
            val transition = if (monitoringSessionChanged) {
                state.transition.withInvalidatedOwnership()
            } else {
                state.transition
            }
            val periodic = if (monitoringSessionChanged) {
                state.periodic.withInvalidatedOwnership()
            } else {
                state.periodic
            }
            state = state.copy(
                controllerDesired = desired,
                controllerEpoch = state.controllerEpoch + 1L,
                monitoringSessionGeneration = state.monitoringSessionGeneration +
                    if (monitoringSessionChanged) 1L else 0L,
                desiredPeriodicIntervalMillis = if (desired) normalizedInterval else null,
                confirmedPeriodicIntervalMillis = if (desired && !monitoringSessionChanged) {
                    state.confirmedPeriodicIntervalMillis
                } else {
                    null
                },
                periodicOwner = if (desired && !monitoringSessionChanged) {
                    state.periodicOwner
                } else {
                    null
                },
                transition = transition,
                periodic = periodic,
                periodicBackstopEnabled = if (desired) {
                    periodicBackstopEnabled
                } else {
                    state.periodicBackstopEnabled
                },
                bootstrapRequestedAtMillis = if (monitoringSessionChanged) {
                    null
                } else {
                    state.bootstrapRequestedAtMillis
                },
                bootstrapDeadlineMillis = if (monitoringSessionChanged) {
                    null
                } else {
                    state.bootstrapDeadlineMillis
                },
                bootstrapResultReceived = if (monitoringSessionChanged) {
                    false
                } else {
                    state.bootstrapResultReceived
                },
                bootstrapCompleted = if (monitoringSessionChanged) {
                    false
                } else {
                    state.bootstrapCompleted
                },
                periodicRemovalRequired = if (monitoringSessionChanged) {
                    false
                } else {
                    state.periodicRemovalRequired
                },
            )
            persist(state)
        }
        clearOperationBlocks()
        addWaiter(onComplete, wantsDesired = desired)
        drive(allowRequest = true, reason = reason)
    }

    @Synchronized
    fun reconcileDesired(
        reason: String,
        onComplete: ((ActivityReconcileResult) -> Unit)? = null,
    ) {
        if (!state.controllerDesired) {
            onComplete?.invoke(
                ActivityReconcileResult(
                    ActivityReconcileDisposition.NOT_DESIRED,
                    "controller_not_desired:$reason",
                )
            )
            return
        }
        clearOperationBlocks()
        addWaiter(onComplete, wantsDesired = true)
        drive(allowRequest = true, reason = reason)
    }

    @Synchronized
    fun recordPeriodicResult(reason: String) {
        if (!state.controllerDesired || state.periodicBackstopEnabled ||
            state.periodicMode != ActivityPeriodicMode.BOOTSTRAP ||
            state.bootstrapCompleted
        ) {
            return
        }
        state = state.copy(
            bootstrapResultReceived = true,
            periodicRemovalRequired = true,
        )
        persist(state)
        clearOperationBlocks()
        drive(allowRequest = false, reason = "bootstrap_result:$reason")
    }

    @Synchronized
    fun onBootstrapDeadline(
        monitoringSessionGeneration: Long,
        reason: String,
        onComplete: ((ActivityReconcileResult) -> Unit)? = null,
    ): Boolean {
        if (monitoringSessionGeneration != state.monitoringSessionGeneration) {
            recordStaleCallback(
                "bootstrap_timeout_session:$monitoringSessionGeneration",
            )
            return false
        }
        val deadline = state.bootstrapDeadlineMillis ?: return false
        if (nowMillis() < deadline || state.bootstrapCompleted ||
            state.periodicRemovalRequired ||
            state.periodicBackstopEnabled || !state.controllerDesired
        ) {
            return false
        }
        markBootstrapRemovalRequired("bootstrap_timeout:$reason")
        clearOperationBlocks()
        addWaiter(onComplete, wantsDesired = true)
        drive(allowRequest = false, reason = "bootstrap_timeout:$reason")
        return true
    }

    @Synchronized
    fun reconcileProcessBootstrap(
        retainDesired: Boolean,
        reason: String,
    ) {
        val desired = state.controllerDesired && retainDesired
        if (state.controllerDesired != desired) {
            state = state.copy(
                controllerDesired = desired,
                controllerEpoch = state.controllerEpoch + 1L,
                monitoringSessionGeneration = state.monitoringSessionGeneration + 1L,
                desiredPeriodicIntervalMillis = null,
                confirmedPeriodicIntervalMillis = null,
                periodicOwner = null,
                transition = state.transition.withInvalidatedOwnership(),
                periodic = state.periodic.withInvalidatedOwnership(),
                bootstrapRequestedAtMillis = null,
                bootstrapDeadlineMillis = null,
                bootstrapResultReceived = false,
                bootstrapCompleted = false,
                periodicRemovalRequired = false,
            )
            persist(state)
        }
        drive(allowRequest = desired, reason = reason)
    }

    @Synchronized
    fun invalidateConfidence(reason: String) {
        val supersededWaiters = waiters.toList().also { waiters.clear() }
        val at = nowMillis()
        state = state.copy(
            controllerEpoch = state.controllerEpoch + 1L,
            monitoringSessionGeneration = state.monitoringSessionGeneration + 1L,
            transition = state.transition.copy(
                confirmed = false,
                requestInFlight = false,
                removalInFlight = false,
                removalConfirmed = false,
                operationStartedAtMillis = null,
                requestReplacesConfirmed = false,
                requestBlockedEpoch = null,
                removalBlockedEpoch = null,
                lastFailureAtMillis = at,
                lastFailureReason = "confidence_invalidated:$reason",
            ),
            periodic = state.periodic.copy(
                confirmed = false,
                requestInFlight = false,
                removalInFlight = false,
                removalConfirmed = false,
                operationStartedAtMillis = null,
                requestReplacesConfirmed = false,
                requestBlockedEpoch = null,
                removalBlockedEpoch = null,
                lastFailureAtMillis = at,
                lastFailureReason = "confidence_invalidated:$reason",
            ),
            confirmedPeriodicIntervalMillis = null,
            periodicOwner = null,
            bootstrapRequestedAtMillis = null,
            bootstrapDeadlineMillis = null,
            bootstrapResultReceived = false,
            bootstrapCompleted = false,
            periodicRemovalRequired = false,
            failureSerial = state.failureSerial + 1L,
        )
        persist(state)
        supersededWaiters.forEach {
            it.callback(
                ActivityReconcileResult(
                    ActivityReconcileDisposition.SUPERSEDED,
                    "confidence_invalidated:$reason",
                )
            )
        }
    }

    private fun addWaiter(
        callback: ((ActivityReconcileResult) -> Unit)?,
        wantsDesired: Boolean,
    ) {
        if (callback == null) return
        waiters += Waiter(
            epoch = state.controllerEpoch,
            failureSerial = state.failureSerial,
            initiallyHealthy = if (wantsDesired) isHealthy() else isStopped(),
            wantsDesired = wantsDesired,
            callback = callback,
        )
    }

    private fun drive(allowRequest: Boolean, reason: String) {
        expireBootstrapIfDue(reason)
        driveKind(ActivityRegistrationKind.TRANSITION, allowRequest, reason)
        driveKind(ActivityRegistrationKind.PERIODIC, allowRequest, reason)
        settleWaiters(reason)
    }

    private fun driveKind(
        kind: ActivityRegistrationKind,
        allowRequest: Boolean,
        reason: String,
    ) {
        var part = part(kind)
        if (part.requestInFlight || part.removalInFlight) return

        if (part.confirmed &&
            !part.belongsToMonitoringSession(state.monitoringSessionGeneration)
        ) {
            part = part.withInvalidatedOwnership().copy(
                lastFailureAtMillis = nowMillis(),
                lastFailureReason = "confirmed_monitoring_session_stale:$reason",
            )
            setPart(kind, part)
            if (kind == ActivityRegistrationKind.PERIODIC) {
                state = state.copy(
                    confirmedPeriodicIntervalMillis = null,
                    periodicOwner = null,
                )
                persist(state)
            }
        }

        val tokenExists = backend.pendingIntentExists(kind)
        if (part.confirmed && !tokenExists) {
            part = part.copy(
                confirmed = false,
                lastFailureAtMillis = nowMillis(),
                lastFailureReason = "confirmed_pending_intent_missing:$reason",
            )
            setPart(kind, part)
            if (kind == ActivityRegistrationKind.PERIODIC) {
                state = state.copy(
                    confirmedPeriodicIntervalMillis = null,
                    periodicOwner = null,
                    failureSerial = state.failureSerial + 1L,
                )
                persist(state)
            } else {
                state = state.copy(failureSerial = state.failureSerial + 1L)
                persist(state)
            }
        }

        part = part(kind)
        if (!state.controllerDesired) {
            val stoppedStateUncertain = state.controllerEpoch > 0L &&
                !part.removalConfirmed
            if ((part.confirmed || tokenExists || stoppedStateUncertain) &&
                part.removalBlockedEpoch != state.controllerEpoch
            ) {
                beginRemoval(kind, reason)
            }
            return
        }

        if (!part.confirmed && tokenExists) {
            if (part.removalBlockedEpoch != state.controllerEpoch) {
                beginRemoval(kind, "uncertain:$reason")
            }
            return
        }

        if (kind == ActivityRegistrationKind.PERIODIC) {
            val targetMode = desiredPeriodicMode()
            val removalRequired = state.periodicRemovalRequired ||
                targetMode == ActivityPeriodicMode.NONE
            val periodicStateUncertain = state.periodicMode != ActivityPeriodicMode.NONE &&
                !part.removalConfirmed
            if (removalRequired) {
                if ((part.confirmed || tokenExists || periodicStateUncertain) &&
                    part.removalBlockedEpoch != state.controllerEpoch
                ) {
                    beginRemoval(kind, "periodic_not_desired:$reason")
                }
                return
            }

            val targetInterval = periodicIntervalFor(targetMode)
            val periodicNeedsReplacement = part.confirmed &&
                (state.periodicMode != targetMode ||
                    state.confirmedPeriodicIntervalMillis != targetInterval)
            if (periodicNeedsReplacement) {
                if (part.removalBlockedEpoch != state.controllerEpoch) {
                    beginRemoval(kind, "periodic_mode_changed:$reason")
                }
                return
            }
            if (part.confirmed) return
            if (!allowRequest || part.requestBlockedEpoch == state.controllerEpoch) return
            beginRequest(kind, reason)
            return
        }

        if (part.confirmed) return
        if (!allowRequest) return
        if (part.requestBlockedEpoch == state.controllerEpoch) return
        beginRequest(kind, reason)
    }

    private fun beginRequest(kind: ActivityRegistrationKind, reason: String) {
        val requestedPeriodicMode = if (kind == ActivityRegistrationKind.PERIODIC) {
            desiredPeriodicMode()
        } else {
            ActivityPeriodicMode.NONE
        }
        val interval = if (kind == ActivityRegistrationKind.PERIODIC) {
            periodicIntervalFor(requestedPeriodicMode)
        } else {
            null
        }
        if (kind == ActivityRegistrationKind.PERIODIC && interval == null) {
            recordFailure(kind, "periodic_interval_missing:$reason")
            return
        }
        val previous = part(kind)
        val generation = nextGeneration()
        val operationControllerEpoch = state.controllerEpoch
        val operationMonitoringSessionGeneration = state.monitoringSessionGeneration
        val payloadAcceptanceElapsedRealtimeNanos = nowElapsedRealtimeNanos()
        setPart(
            kind,
            previous.copy(
                requestInFlight = true,
                removalInFlight = false,
                removalConfirmed = false,
                operationGeneration = generation,
                operationControllerEpoch = operationControllerEpoch,
                operationMonitoringSessionGeneration = operationMonitoringSessionGeneration,
                operationStartedAtMillis = nowMillis(),
                requestReplacesConfirmed = previous.confirmed,
                requestBlockedEpoch = null,
            ),
        )
        backend.request(kind, interval) { result ->
            onRequestCompleted(
                kind,
                generation,
                operationControllerEpoch,
                operationMonitoringSessionGeneration,
                interval,
                requestedPeriodicMode,
                payloadAcceptanceElapsedRealtimeNanos,
                result,
                reason,
            )
        }
    }

    @Synchronized
    private fun onRequestCompleted(
        kind: ActivityRegistrationKind,
        generation: Long,
        operationControllerEpoch: Long,
        operationMonitoringSessionGeneration: Long,
        requestedIntervalMillis: Long?,
        requestedPeriodicMode: ActivityPeriodicMode,
        payloadAcceptanceElapsedRealtimeNanos: Long,
        result: ActivityRegistrationOperationResult,
        reason: String,
    ) {
        val current = part(kind)
        if (!current.requestInFlight ||
            current.operationGeneration != generation ||
            current.operationControllerEpoch != operationControllerEpoch ||
            current.operationMonitoringSessionGeneration != operationMonitoringSessionGeneration
        ) {
            recordStaleCallback("request:${kind.name.lowercase()}:generation=$generation")
            return
        }
        val monitoringSessionSuperseded = operationMonitoringSessionGeneration !=
            state.monitoringSessionGeneration
        val operationSuperseded = operationControllerEpoch != state.controllerEpoch
        val at = nowMillis()
        if (monitoringSessionSuperseded) {
            setPart(
                kind,
                current.copy(
                    confirmed = false,
                    requestInFlight = false,
                    removalConfirmed = false,
                    operationStartedAtMillis = null,
                    payloadAcceptanceElapsedRealtimeNanos = 0L,
                    requestReplacesConfirmed = false,
                    requestBlockedEpoch = null,
                ),
            )
            if (kind == ActivityRegistrationKind.PERIODIC) {
                state = state.copy(
                    confirmedPeriodicIntervalMillis = null,
                    periodicOwner = null,
                )
                persist(state)
            }
            recordStaleCallback(
                "request_session:${kind.name.lowercase()}:" +
                    "monitoring_session=$operationMonitoringSessionGeneration",
            )
            drive(allowRequest = true, reason = "superseded_request_session:$reason")
            return
        }
        if (result.succeeded) {
            setPart(
                kind,
                current.copy(
                    confirmed = true,
                    requestInFlight = false,
                    removalConfirmed = false,
                    operationStartedAtMillis = null,
                    payloadAcceptanceElapsedRealtimeNanos =
                        payloadAcceptanceElapsedRealtimeNanos,
                    requestReplacesConfirmed = false,
                    requestBlockedEpoch = null,
                    lastSuccessAtMillis = at,
                    lastFailureReason = current.lastFailureReason,
                ),
            )
            if (kind == ActivityRegistrationKind.PERIODIC) {
                state = when (requestedPeriodicMode) {
                    ActivityPeriodicMode.BOOTSTRAP -> state.copy(
                        confirmedPeriodicIntervalMillis = requestedIntervalMillis,
                        periodicOwner = ActivityMonitor.BOOTSTRAP_PERIODIC_REASON,
                        periodicMode = ActivityPeriodicMode.BOOTSTRAP,
                        bootstrapRequestedAtMillis = at,
                        bootstrapDeadlineMillis = safeAdd(
                            at,
                            ActivityMonitor.BOOTSTRAP_TIMEOUT_MILLIS,
                        ),
                        bootstrapResultReceived = false,
                        bootstrapCompleted = false,
                        periodicRemovalRequired = false,
                    )
                    ActivityPeriodicMode.PERSISTENT_BACKSTOP -> state.copy(
                        confirmedPeriodicIntervalMillis = requestedIntervalMillis,
                        periodicOwner = ActivityMonitor.BASELINE_PERIODIC_REASON,
                        periodicMode = ActivityPeriodicMode.PERSISTENT_BACKSTOP,
                        bootstrapRequestedAtMillis = null,
                        bootstrapDeadlineMillis = null,
                        bootstrapResultReceived = false,
                        bootstrapCompleted = false,
                        periodicRemovalRequired = false,
                    )
                    ActivityPeriodicMode.NONE -> state.copy(
                        confirmedPeriodicIntervalMillis = null,
                        periodicOwner = null,
                    )
                }
                persist(state)
            }
            drive(allowRequest = true, reason = "request_completed:$reason")
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
                "request_failure:${kind.name.lowercase()}:" +
                    "controller_epoch=$operationControllerEpoch",
            )
            drive(allowRequest = true, reason = "superseded_request_failed:$reason")
            return
        }

        if (!current.requestReplacesConfirmed) {
            backend.cancelPendingIntent(kind)
        }
        setPart(
            kind,
            current.copy(
                confirmed = current.requestReplacesConfirmed,
                requestInFlight = false,
                operationStartedAtMillis = null,
                requestReplacesConfirmed = false,
                requestBlockedEpoch = operationControllerEpoch,
                lastFailureAtMillis = at,
                lastFailureReason = result.failureReason ?: "request_failed:$reason",
            ),
        )
        state = state.copy(failureSerial = state.failureSerial + 1L)
        if (kind == ActivityRegistrationKind.PERIODIC && !current.requestReplacesConfirmed) {
            state = state.copy(
                confirmedPeriodicIntervalMillis = null,
                periodicOwner = null,
            )
        }
        persist(state)
        drive(allowRequest = false, reason = "request_failed:$reason")
    }

    private fun beginRemoval(kind: ActivityRegistrationKind, reason: String) {
        val current = part(kind)
        val generation = nextGeneration()
        val operationControllerEpoch = state.controllerEpoch
        val operationMonitoringSessionGeneration = state.monitoringSessionGeneration
        setPart(
            kind,
            current.copy(
                requestInFlight = false,
                removalInFlight = true,
                removalConfirmed = false,
                operationGeneration = generation,
                operationControllerEpoch = operationControllerEpoch,
                operationMonitoringSessionGeneration = operationMonitoringSessionGeneration,
                operationStartedAtMillis = nowMillis(),
                requestReplacesConfirmed = false,
                requestBlockedEpoch = null,
            ),
        )
        backend.remove(kind) { result ->
            onRemovalCompleted(
                kind,
                generation,
                operationControllerEpoch,
                operationMonitoringSessionGeneration,
                result,
                reason,
            )
        }
    }

    @Synchronized
    private fun onRemovalCompleted(
        kind: ActivityRegistrationKind,
        generation: Long,
        operationControllerEpoch: Long,
        operationMonitoringSessionGeneration: Long,
        result: ActivityRegistrationOperationResult,
        reason: String,
    ) {
        val current = part(kind)
        if (!current.removalInFlight ||
            current.operationGeneration != generation ||
            current.operationControllerEpoch != operationControllerEpoch ||
            current.operationMonitoringSessionGeneration != operationMonitoringSessionGeneration
        ) {
            recordStaleCallback("remove:${kind.name.lowercase()}:generation=$generation")
            return
        }
        val monitoringSessionSuperseded = operationMonitoringSessionGeneration !=
            state.monitoringSessionGeneration
        val operationSuperseded = operationControllerEpoch != state.controllerEpoch
        val at = nowMillis()
        if (!result.succeeded) {
            val failed = current.copy(
                confirmed = false,
                removalInFlight = false,
                removalConfirmed = false,
                operationStartedAtMillis = null,
                removalBlockedEpoch = if (monitoringSessionSuperseded || operationSuperseded) {
                    null
                } else {
                    operationControllerEpoch
                },
                lastFailureAtMillis = at,
                lastFailureReason = result.failureReason ?: "removal_failed:$reason",
            )
            setPart(kind, failed)
            if (kind == ActivityRegistrationKind.PERIODIC) {
                state = state.copy(
                    confirmedPeriodicIntervalMillis = null,
                    periodicOwner = null,
                    periodicRemovalRequired = state.periodicRemovalRequired ||
                        state.periodicMode == ActivityPeriodicMode.BOOTSTRAP,
                )
                persist(state)
            }
            if (operationSuperseded) {
                recordStaleCallback(
                    "remove_failure:${kind.name.lowercase()}:" +
                        "controller_epoch=$operationControllerEpoch",
                )
                drive(allowRequest = true, reason = "superseded_removal_failed:$reason")
                return
            }
            state = state.copy(failureSerial = state.failureSerial + 1L)
            persist(state)
            drive(allowRequest = false, reason = "removal_failed:$reason")
            return
        }

        backend.cancelPendingIntent(kind)
        val completedBootstrap = kind == ActivityRegistrationKind.PERIODIC &&
            state.controllerDesired &&
            !state.periodicBackstopEnabled &&
            state.periodicMode == ActivityPeriodicMode.BOOTSTRAP &&
            state.periodicRemovalRequired
        setPart(
            kind,
            current.copy(
                confirmed = false,
                removalInFlight = false,
                removalConfirmed = true,
                operationStartedAtMillis = null,
                removalBlockedEpoch = null,
                lastSuccessAtMillis = at,
                lastFailureAtMillis = current.lastFailureAtMillis,
                lastFailureReason = current.lastFailureReason,
            ),
        )
        if (kind == ActivityRegistrationKind.PERIODIC) {
            state = state.copy(
                confirmedPeriodicIntervalMillis = null,
                periodicOwner = null,
                periodicMode = ActivityPeriodicMode.NONE,
                bootstrapRequestedAtMillis = if (completedBootstrap) {
                    state.bootstrapRequestedAtMillis
                } else {
                    null
                },
                bootstrapDeadlineMillis = null,
                bootstrapCompleted = if (completedBootstrap) {
                    true
                } else {
                    state.bootstrapCompleted
                },
                periodicRemovalRequired = false,
            )
        }
        persist(state)
        drive(allowRequest = state.controllerDesired, reason = "removal_completed:$reason")
    }

    private fun desiredPeriodicMode(): ActivityPeriodicMode {
        if (!state.controllerDesired ||
            !state.transition.confirmed ||
            !state.transition.belongsToMonitoringSession(state.monitoringSessionGeneration)
        ) {
            return ActivityPeriodicMode.NONE
        }
        if (state.periodicBackstopEnabled) {
            return ActivityPeriodicMode.PERSISTENT_BACKSTOP
        }
        return if (state.bootstrapCompleted || state.periodicRemovalRequired) {
            ActivityPeriodicMode.NONE
        } else {
            ActivityPeriodicMode.BOOTSTRAP
        }
    }

    private fun periodicIntervalFor(mode: ActivityPeriodicMode): Long? = when (mode) {
        ActivityPeriodicMode.NONE -> null
        ActivityPeriodicMode.BOOTSTRAP -> 0L
        ActivityPeriodicMode.PERSISTENT_BACKSTOP ->
            state.desiredPeriodicIntervalMillis?.coerceAtLeast(0L)
    }

    private fun expireBootstrapIfDue(reason: String) {
        val deadline = state.bootstrapDeadlineMillis ?: return
        if (state.controllerDesired && !state.periodicBackstopEnabled &&
            !state.bootstrapCompleted && !state.periodicRemovalRequired &&
            nowMillis() >= deadline
        ) {
            markBootstrapRemovalRequired("bootstrap_timeout:$reason")
        }
    }

    private fun markBootstrapRemovalRequired(reason: String) {
        state = state.copy(
            periodicRemovalRequired = true,
            periodic = state.periodic.copy(
                lastFailureAtMillis = nowMillis(),
                lastFailureReason = reason,
            ),
        )
        persist(state)
    }

    private fun safeAdd(base: Long, delta: Long): Long =
        if (delta > Long.MAX_VALUE - base) Long.MAX_VALUE else base + delta

    private fun recordFailure(kind: ActivityRegistrationKind, reason: String) {
        val current = part(kind)
        setPart(
            kind,
            current.copy(
                requestBlockedEpoch = state.controllerEpoch,
                lastFailureAtMillis = nowMillis(),
                lastFailureReason = reason,
            ),
        )
        state = state.copy(failureSerial = state.failureSerial + 1L)
        persist(state)
    }

    private fun clearOperationBlocks() {
        val transition = state.transition.let {
            if (it.requestBlockedEpoch == null && it.removalBlockedEpoch == null) {
                it
            } else {
                it.copy(requestBlockedEpoch = null, removalBlockedEpoch = null)
            }
        }
        val periodic = state.periodic.let {
            if (it.requestBlockedEpoch == null && it.removalBlockedEpoch == null) {
                it
            } else {
                it.copy(requestBlockedEpoch = null, removalBlockedEpoch = null)
            }
        }
        if (transition != state.transition || periodic != state.periodic) {
            state = state.copy(transition = transition, periodic = periodic)
            persist(state)
        }
    }

    private fun recordStaleCallback(reason: String) {
        state = state.copy(
            staleCallbackCount = state.staleCallbackCount + 1L,
            lastStaleCallbackReason = reason,
        )
        persist(state)
    }

    private fun settleWaiters(reason: String) {
        if (waiters.isEmpty()) return
        val callbacks = mutableListOf<Pair<Waiter, ActivityReconcileResult>>()
        val iterator = waiters.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            val result = when {
                waiter.wantsDesired && !state.controllerDesired -> ActivityReconcileResult(
                    ActivityReconcileDisposition.NOT_DESIRED,
                    "controller_not_desired:$reason",
                )
                waiter.epoch != state.controllerEpoch -> ActivityReconcileResult(
                    ActivityReconcileDisposition.SUPERSEDED,
                    "controller_epoch_changed:$reason",
                )
                !waiter.wantsDesired && state.controllerDesired -> ActivityReconcileResult(
                    ActivityReconcileDisposition.SUPERSEDED,
                    "controller_restarted:$reason",
                )
                waiter.wantsDesired && isHealthy() -> ActivityReconcileResult(
                    if (waiter.initiallyHealthy) {
                        ActivityReconcileDisposition.HEALTHY
                    } else {
                        ActivityReconcileDisposition.RECONCILED
                    },
                    if (waiter.initiallyHealthy) "already_healthy:$reason" else "registrations_confirmed:$reason",
                )
                !hasOperationInFlight() && state.failureSerial > waiter.failureSerial ->
                    ActivityReconcileResult(
                        ActivityReconcileDisposition.FAILED,
                        "registration_operation_failed:$reason",
                    )
                !waiter.wantsDesired && isStopped() -> ActivityReconcileResult(
                    ActivityReconcileDisposition.STOPPED,
                    "registrations_removed:$reason",
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

    private fun isHealthy(): Boolean {
        val transitionHealthy = state.controllerDesired &&
            state.transition.confirmed &&
            state.transition.belongsToMonitoringSession(state.monitoringSessionGeneration) &&
            !hasOperationInFlight() &&
            backend.pendingIntentExists(ActivityRegistrationKind.TRANSITION)
        if (!transitionHealthy) return false
        return if (state.periodicBackstopEnabled) {
            state.periodic.confirmed &&
                state.periodic.belongsToMonitoringSession(state.monitoringSessionGeneration) &&
                state.periodicMode == ActivityPeriodicMode.PERSISTENT_BACKSTOP &&
                state.periodicOwner == ActivityMonitor.BASELINE_PERIODIC_REASON &&
                state.confirmedPeriodicIntervalMillis == state.desiredPeriodicIntervalMillis &&
                backend.pendingIntentExists(ActivityRegistrationKind.PERIODIC)
        } else {
            state.bootstrapCompleted &&
                state.periodicMode == ActivityPeriodicMode.NONE &&
                !state.periodic.confirmed &&
                state.periodic.removalConfirmed &&
                !state.periodicRemovalRequired &&
                !backend.pendingIntentExists(ActivityRegistrationKind.PERIODIC)
        }
    }

    private fun isStopped(): Boolean = !state.controllerDesired &&
        !state.transition.confirmed &&
        !state.periodic.confirmed &&
        state.periodicMode == ActivityPeriodicMode.NONE &&
        !hasOperationInFlight() &&
        !backend.pendingIntentExists(ActivityRegistrationKind.TRANSITION) &&
        !backend.pendingIntentExists(ActivityRegistrationKind.PERIODIC)

    private fun hasOperationInFlight(): Boolean =
        state.transition.requestInFlight ||
            state.transition.removalInFlight ||
            state.periodic.requestInFlight ||
            state.periodic.removalInFlight

    private fun nextGeneration(): Long {
        val generation = state.nextOperationGeneration + 1L
        state = state.copy(nextOperationGeneration = generation)
        persist(state)
        return generation
    }

    private fun part(kind: ActivityRegistrationKind): ActivityRegistrationPartState =
        when (kind) {
            ActivityRegistrationKind.TRANSITION -> state.transition
            ActivityRegistrationKind.PERIODIC -> state.periodic
        }

    private fun setPart(kind: ActivityRegistrationKind, value: ActivityRegistrationPartState) {
        state = when (kind) {
            ActivityRegistrationKind.TRANSITION -> state.copy(transition = value)
            ActivityRegistrationKind.PERIODIC -> state.copy(periodic = value)
        }
        persist(state)
    }

    private fun ActivityRegistrationPartState.withInvalidatedOwnership(): ActivityRegistrationPartState =
        copy(
            confirmed = false,
            removalConfirmed = false,
            payloadAcceptanceElapsedRealtimeNanos = 0L,
            requestReplacesConfirmed = false,
        )

    companion object {
        private fun normalizeLegacyPeriodicMode(
            state: ActivityRegistrationLifecycleState,
        ): ActivityRegistrationLifecycleState {
            if (state.periodicMode != ActivityPeriodicMode.NONE) return state
            val inferred = when (state.periodicOwner) {
                ActivityMonitor.BOOTSTRAP_PERIODIC_REASON -> ActivityPeriodicMode.BOOTSTRAP
                ActivityMonitor.BASELINE_PERIODIC_REASON ->
                    ActivityPeriodicMode.PERSISTENT_BACKSTOP
                else -> ActivityPeriodicMode.NONE
            }
            return if (inferred == ActivityPeriodicMode.NONE) state else state.copy(
                periodicMode = inferred,
            )
        }

        internal fun recoverInterruptedOperations(
            state: ActivityRegistrationLifecycleState,
            atMillis: Long,
        ): ActivityRegistrationLifecycleState {
            fun recover(kind: String, part: ActivityRegistrationPartState): ActivityRegistrationPartState {
                if (!part.requestInFlight && !part.removalInFlight) return part
                return part.copy(
                    confirmed = false,
                    requestInFlight = false,
                    removalInFlight = false,
                    removalConfirmed = false,
                    operationStartedAtMillis = null,
                    requestReplacesConfirmed = false,
                    requestBlockedEpoch = null,
                    removalBlockedEpoch = null,
                    lastFailureAtMillis = atMillis,
                    lastFailureReason = "process_interrupted:$kind",
                )
            }

            val interrupted = state.transition.requestInFlight ||
                state.transition.removalInFlight ||
                state.periodic.requestInFlight ||
                state.periodic.removalInFlight
            if (!interrupted) return state
            return state.copy(
                controllerEpoch = state.controllerEpoch + 1L,
                transition = recover("transition", state.transition),
                periodic = recover("periodic", state.periodic),
                confirmedPeriodicIntervalMillis = if (
                    state.periodic.requestInFlight || state.periodic.removalInFlight
                ) {
                    null
                } else {
                    state.confirmedPeriodicIntervalMillis
                },
                periodicOwner = if (
                    state.periodic.requestInFlight || state.periodic.removalInFlight
                ) {
                    null
                } else {
                    state.periodicOwner
                },
                failureSerial = state.failureSerial + 1L,
            )
        }
    }
}
