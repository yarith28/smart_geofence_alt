package com.yarithdev.smart_geofence.store

import android.content.Context
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.delivery.EventDedupStore
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.proximitypulse.isProximityExitRecoveryEligible
import org.json.JSONObject

enum class ObservedFenceState {
    INSIDE,
    OUTSIDE,
}

enum class FenceObservationDecision {
    BASELINE,
    UNCHANGED,
    TRANSITION,
}

data class FenceObservation(
    val previous: ObservedFenceState?,
    val current: ObservedFenceState,
    val decision: FenceObservationDecision,
)

data class FenceObservationClaim(
    val fenceId: String,
    val previous: ObservedFenceState?,
    val current: ObservedFenceState,
    val committed: Boolean = true,
    val previousInternallyEntered: Boolean = false,
    val previousProximityEligibleExit: Boolean = false,
)

internal fun observationDecision(
    previous: ObservedFenceState?,
    current: ObservedFenceState,
): FenceObservationDecision = when (previous) {
    null -> FenceObservationDecision.BASELINE
    current -> FenceObservationDecision.UNCHANGED
    else -> FenceObservationDecision.TRANSITION
}

object FenceObservationStore {
    private const val TAG = "FenceObservationStore"
    private const val KEY_STATES = "fence_observed_states"
    private const val KEY_INTERNALLY_ENTERED = "fence_internally_entered_ids"
    private const val KEY_PROXIMITY_ELIGIBLE_EXITS = "fence_proximity_eligible_exit_ids"

    @Synchronized
    fun currentState(context: Context, fenceId: String): ObservedFenceState? =
        read(context.applicationContext)[fenceId]

    @Synchronized
    fun snapshot(context: Context): Map<String, String> =
        read(context.applicationContext)
            .toSortedMap()
            .mapValues { it.value.name.lowercase() }

    @Synchronized
    fun observedInsideFenceIds(context: Context): Set<String> =
        read(context.applicationContext)
            .filterValues { it == ObservedFenceState.INSIDE }
            .keys

    @Synchronized
    fun internallyEnteredFenceIds(context: Context): Set<String> =
        readInternallyEntered(context.applicationContext)

    @Synchronized
    fun proximityEligibleExitFenceIds(context: Context): Set<String> =
        readProximityEligibleExits(context.applicationContext)

    @Synchronized
    fun observe(
        context: Context,
        fenceId: String,
        state: ObservedFenceState,
    ): FenceObservation {
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val proximityEligibleExits = readProximityEligibleExits(appContext).toMutableSet()
        val previous = states[fenceId]
        states[fenceId] = state
        if (state == ObservedFenceState.OUTSIDE) internallyEntered.remove(fenceId)
        if (state == ObservedFenceState.INSIDE || previous != ObservedFenceState.OUTSIDE) {
            proximityEligibleExits.remove(fenceId)
        }
        persist(appContext, states, internallyEntered, proximityEligibleExits)
        return FenceObservation(
            previous = previous,
            current = state,
            decision = observationDecision(previous, state),
        )
    }

    @Synchronized
    fun emitTransition(
        context: Context,
        fenceId: String,
        state: ObservedFenceState,
        edgeDistanceMeters: Double? = null,
        invalidateCallbackDedup: Boolean = false,
        forceEmission: Boolean = false,
    ): FenceObservation {
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val proximityEligibleExits = readProximityEligibleExits(appContext).toMutableSet()
        val previous = states[fenceId]
        val decision = observationDecision(previous, state)
        val emittedTransition = forceEmission || decision == FenceObservationDecision.TRANSITION
        states[fenceId] = state
        when (state) {
            ObservedFenceState.INSIDE -> {
                proximityEligibleExits -= fenceId
                if (emittedTransition) {
                    internallyEntered += fenceId
                }
            }
            ObservedFenceState.OUTSIDE -> {
                internallyEntered -= fenceId
                if (emittedTransition &&
                    isExitRecoveryEligible(appContext, fenceId, edgeDistanceMeters)
                ) {
                    proximityEligibleExits += fenceId
                } else if (emittedTransition || decision != FenceObservationDecision.UNCHANGED) {
                    proximityEligibleExits -= fenceId
                }
            }
        }
        persist(
            appContext,
            states,
            internallyEntered,
            proximityEligibleExits,
            invalidateCallbackDedupForFenceId = fenceId.takeIf { invalidateCallbackDedup },
        )
        return FenceObservation(previous, state, decision)
    }

    @Synchronized
    fun restoreIfCurrent(
        context: Context,
        fenceId: String,
        expectedCurrent: ObservedFenceState,
        previous: ObservedFenceState?,
    ) {
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val proximityEligibleExits = readProximityEligibleExits(appContext).toMutableSet()
        if (states[fenceId] != expectedCurrent) return
        if (previous == null) states.remove(fenceId) else states[fenceId] = previous
        if (previous != ObservedFenceState.INSIDE) internallyEntered.remove(fenceId)
        if (previous != ObservedFenceState.OUTSIDE) proximityEligibleExits.remove(fenceId)
        persist(appContext, states, internallyEntered, proximityEligibleExits)
    }

    @Synchronized
    fun recordNativeEvent(
        context: Context,
        fenceIds: Collection<String>,
        event: String?,
        edgeDistanceMetersByFenceId: Map<String, Double> = emptyMap(),
    ): List<FenceObservationClaim> {
        val state = when (event?.lowercase()) {
            "enter", "dwell" -> ObservedFenceState.INSIDE
            "exit" -> ObservedFenceState.OUTSIDE
            else -> return emptyList()
        }
        if (fenceIds.isEmpty()) return emptyList()
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val proximityEligibleExits = readProximityEligibleExits(appContext).toMutableSet()
        val claims = fenceIds.distinct().map { fenceId ->
            FenceObservationClaim(
                fenceId = fenceId,
                previous = states[fenceId],
                current = state,
                previousInternallyEntered = fenceId in internallyEntered,
                previousProximityEligibleExit = fenceId in proximityEligibleExits,
            )
        }
        claims.forEach { claim ->
            states[claim.fenceId] = state
            when (state) {
                ObservedFenceState.INSIDE -> {
                    proximityEligibleExits -= claim.fenceId
                    if (claim.previous == ObservedFenceState.OUTSIDE) {
                        internallyEntered += claim.fenceId
                    }
                }
                ObservedFenceState.OUTSIDE -> {
                    internallyEntered -= claim.fenceId
                    val decision = observationDecision(claim.previous, state)
                    if (decision == FenceObservationDecision.TRANSITION &&
                        isExitRecoveryEligible(
                            appContext,
                            claim.fenceId,
                            edgeDistanceMetersByFenceId[claim.fenceId],
                        )
                    ) {
                        proximityEligibleExits += claim.fenceId
                    } else if (decision != FenceObservationDecision.UNCHANGED) {
                        proximityEligibleExits -= claim.fenceId
                    }
                }
            }
        }
        persist(appContext, states, internallyEntered, proximityEligibleExits)
        claims.filter {
            observationDecision(it.previous, it.current) == FenceObservationDecision.TRANSITION
        }.forEach { claim ->
            runCatching {
                ProximityPulseController.onInternalTransitionCommitted(
                    appContext,
                    claim.fenceId,
                    claim.current,
                    edgeDistanceMeters = edgeDistanceMetersByFenceId[claim.fenceId],
                    source = "native_event",
                )
            }.onFailure { error ->
                runCatching {
                    SmartGeofenceLogger.w(
                        appContext,
                        TAG,
                        "Native transition committed but Pulse reconciliation failed " +
                            "fence=${claim.fenceId}.",
                        error,
                    )
                }
            }
        }
        val insideBaselines = claims
            .filter { it.previous == null && it.current == ObservedFenceState.INSIDE }
            .map { it.fenceId }
        if (insideBaselines.isNotEmpty()) {
            reconcilePulseAfterInsideBaseline(
                appContext,
                source = "native_event",
                fenceIds = insideBaselines,
            )
        }
        return claims
    }

    @Synchronized
    fun prepareNativeExitConfirmation(
        context: Context,
        fenceIds: Collection<String>,
        commitBaseline: Boolean = true,
    ): List<FenceObservationClaim> = prepareNativeConfirmation(
        context,
        fenceIds,
        requiredState = ObservedFenceState.INSIDE,
        observedState = ObservedFenceState.OUTSIDE,
        commitBaseline = commitBaseline,
    )

    @Synchronized
    fun prepareNativeEnterConfirmation(
        context: Context,
        fenceIds: Collection<String>,
        commitBaseline: Boolean = true,
    ): List<FenceObservationClaim> {
        val appContext = context.applicationContext
        val previouslyUnknown = fenceIds.distinct().filter {
            currentState(appContext, it) == null
        }
        val claims = prepareNativeConfirmation(
            appContext,
            fenceIds,
            requiredState = ObservedFenceState.OUTSIDE,
            observedState = ObservedFenceState.INSIDE,
            commitBaseline = commitBaseline,
        )
        val seededInside = previouslyUnknown.filter {
            currentState(appContext, it) == ObservedFenceState.INSIDE
        }
        if (seededInside.isNotEmpty()) {
            reconcilePulseAfterInsideBaseline(
                appContext,
                source = "native_enter_confirmation",
                fenceIds = seededInside,
            )
        }
        return claims
    }

    @Synchronized
    internal fun prepareNativeDwellBaseline(
        context: Context,
        fenceIds: Collection<String>,
    ): Set<String> {
        val appContext = context.applicationContext
        val seeded = seedUnknown(
            appContext,
            fenceIds.distinct().associateWith { ObservedFenceState.INSIDE },
        )
        if (seeded.isNotEmpty()) {
            reconcilePulseAfterInsideBaseline(
                appContext,
                source = "native_dwell",
                fenceIds = seeded,
            )
        }
        return seeded
    }

    @Synchronized
    internal fun seedUnknownFromAcceptedFusedFix(
        context: Context,
        baselines: Map<String, ObservedFenceState>,
    ): Set<String> = seedUnknown(context, baselines)

    private fun prepareNativeConfirmation(
        context: Context,
        fenceIds: Collection<String>,
        requiredState: ObservedFenceState,
        observedState: ObservedFenceState,
        commitBaseline: Boolean,
    ): List<FenceObservationClaim> {
        if (fenceIds.isEmpty()) return emptyList()
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val proximityEligibleExits = readProximityEligibleExits(appContext)
        var seededUnknown = false
        val claims = fenceIds.distinct().mapNotNull { fenceId ->
            when (states[fenceId]) {
                null -> {
                    states[fenceId] = observedState
                    seededUnknown = true
                    null
                }
                requiredState -> FenceObservationClaim(
                    fenceId = fenceId,
                    previous = requiredState,
                    current = requiredState,
                    committed = commitBaseline,
                    previousInternallyEntered = fenceId in internallyEntered,
                    previousProximityEligibleExit = fenceId in proximityEligibleExits,
                )
                observedState -> null
                else -> null
            }
        }
        if (seededUnknown) persist(appContext, states)
        return claims
    }

    private fun seedUnknown(
        context: Context,
        baselines: Map<String, ObservedFenceState>,
    ): Set<String> {
        if (baselines.isEmpty()) return emptySet()
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val seeded = linkedSetOf<String>()
        baselines.forEach { (fenceId, state) ->
            if (states[fenceId] != null) return@forEach
            states[fenceId] = state
            seeded += fenceId
        }
        if (seeded.isNotEmpty()) persist(appContext, states, internallyEntered)
        return seeded
    }

    private fun reconcilePulseAfterInsideBaseline(
        context: Context,
        source: String,
        fenceIds: Collection<String>,
    ) {
        runCatching {
            ProximityPulseController.onConfidentLocationProcessed(context)
        }.onFailure { error ->
            runCatching {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "INSIDE baseline committed but Pulse reconciliation failed " +
                        "source=$source ids=$fenceIds.",
                    error,
                )
            }
        }
    }

    @Synchronized
    fun restoreIfCurrent(context: Context, claims: Collection<FenceObservationClaim>) {
        if (claims.isEmpty()) return
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val proximityEligibleExits = readProximityEligibleExits(appContext).toMutableSet()
        var changed = false
        claims.forEach { claim ->
            if (!claim.committed) return@forEach
            if (states[claim.fenceId] != claim.current) return@forEach
            if (claim.previous == null) {
                changed = states.remove(claim.fenceId) != null || changed
            } else {
                states[claim.fenceId] = claim.previous
                changed = true
            }
            if (claim.previousInternallyEntered) {
                internallyEntered += claim.fenceId
            } else {
                internallyEntered -= claim.fenceId
            }
            if (claim.previousProximityEligibleExit) {
                proximityEligibleExits += claim.fenceId
            } else {
                proximityEligibleExits -= claim.fenceId
            }
        }
        if (changed) persist(appContext, states, internallyEntered, proximityEligibleExits)
    }

    @Synchronized
    fun remove(context: Context, fenceId: String) {
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val proximityEligibleExits = readProximityEligibleExits(appContext).toMutableSet()
        val stateRemoved = states.remove(fenceId) != null
        val markerRemoved = internallyEntered.remove(fenceId)
        val exitMarkerRemoved = proximityEligibleExits.remove(fenceId)
        val changed = stateRemoved || markerRemoved || exitMarkerRemoved
        if (changed) persist(appContext, states, internallyEntered, proximityEligibleExits)
    }

    @Synchronized
    fun retainOnly(context: Context, fenceIds: Set<String>) {
        val appContext = context.applicationContext
        val states = read(appContext)
        val internallyEntered = readInternallyEntered(appContext).toMutableSet()
        val proximityEligibleExits = readProximityEligibleExits(appContext).toMutableSet()
        val statesChanged = states.keys.retainAll(fenceIds)
        val markersChanged = internallyEntered.retainAll(fenceIds)
        val exitMarkersChanged = proximityEligibleExits.retainAll(fenceIds)
        val changed = statesChanged || markersChanged || exitMarkersChanged
        if (changed) persist(appContext, states, internallyEntered, proximityEligibleExits)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context.applicationContext).edit()
            .remove(KEY_STATES)
            .remove(KEY_INTERNALLY_ENTERED)
            .remove(KEY_PROXIMITY_ELIGIBLE_EXITS)
            .apply()
    }

    private fun read(context: Context): MutableMap<String, ObservedFenceState> {
        val raw = prefs(context).safeString(KEY_STATES) ?: return linkedMapOf()
        return try {
            val value = JSONObject(raw)
            val result = linkedMapOf<String, ObservedFenceState>()
            value.keys().forEach { fenceId ->
                runCatching { ObservedFenceState.valueOf(value.getString(fenceId)) }
                    .getOrNull()
                    ?.let { result[fenceId] = it }
            }
            result
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(context, TAG, "Failed to parse observed fence states: ${e.message}", e)
            linkedMapOf()
        }
    }

    private fun readInternallyEntered(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_INTERNALLY_ENTERED, emptySet())
            ?.filterTo(linkedSetOf()) { it.isNotBlank() }
            ?: emptySet()

    private fun readProximityEligibleExits(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PROXIMITY_ELIGIBLE_EXITS, emptySet())
            ?.filterTo(linkedSetOf()) { it.isNotBlank() }
            ?: emptySet()

    private fun isExitRecoveryEligible(
        context: Context,
        fenceId: String,
        edgeDistanceMeters: Double?,
    ): Boolean {
        val config = SmartGeofenceConfigStore.load(context)
        return isProximityExitRecoveryEligible(
            FenceStore.get(context, fenceId),
            edgeDistanceMeters,
            config.proximityPulseActivationDistanceMeters,
        )
    }

    private fun persist(
        context: Context,
        states: Map<String, ObservedFenceState>,
        internallyEntered: Set<String> = readInternallyEntered(context),
        proximityEligibleExits: Set<String> = readProximityEligibleExits(context),
        invalidateCallbackDedupForFenceId: String? = null,
    ) {
        val value = JSONObject()
        states.forEach { (fenceId, state) -> value.put(fenceId, state.name) }
        val editor = prefs(context).edit()
            .putString(KEY_STATES, value.toString())
            .putStringSet(KEY_INTERNALLY_ENTERED, internallyEntered.toSet())
            .putStringSet(KEY_PROXIMITY_ELIGIBLE_EXITS, proximityEligibleExits.toSet())
        val committed = invalidateCallbackDedupForFenceId?.let { fenceId ->
            EventDedupStore.commitWithRemoval(context, fenceId, editor)
        } ?: editor.commit()
        if (!committed) {
            SmartGeofenceLogger.w(context, TAG, "Failed to commit observed fence states.")
            throw IllegalStateException("Failed to commit observed fence states.")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
