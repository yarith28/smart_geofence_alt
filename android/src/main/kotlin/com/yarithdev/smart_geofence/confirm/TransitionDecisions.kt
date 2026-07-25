package com.yarithdev.smart_geofence.confirm

import android.content.Context
import android.location.Location
import com.yarithdev.smart_geofence.processing.LocationEventMode
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.transition.NativeTransitionDirection
import com.yarithdev.smart_geofence.transition.PendingNativeTransition
import com.yarithdev.smart_geofence.store.ObservedFenceState
import com.yarithdev.smart_geofence.time.elapsedWallClockOriginsConsistent

internal fun transitionEventName(
    mode: LocationEventMode,
    boundaryPosition: BoundaryPosition,
): String? = when (boundaryPosition) {
    BoundaryPosition.INSIDE -> if (mode == LocationEventMode.OUTSIDE) null else "enter"
    BoundaryPosition.OUTSIDE -> if (mode == LocationEventMode.INSIDE) null else "exit"
}

internal fun sharedTransitionValidationFingerprint(
    direction: NativeTransitionDirection,
    config: SmartGeofenceConfig,
    radiusMeters: Double,
): String = listOf(
    direction.name,
    config.transitionValidationEnabled,
    config.transitionValidationEnterEnabled,
    config.transitionValidationExitEnabled,
    config.transitionValidationMinimumDelayMillis,
    config.nativeConfirmDelayMillis,
    config.nativeEnterConfirmRadiusSlackMeters,
    radiusMeters,
).joinToString("|")

internal fun isPendingTransitionValidationExpired(
    pending: PendingNativeTransition,
    maxAgeMillis: Long,
    nowMillis: Long,
): Boolean = pending.createdAtMillis <= 0L ||
    (maxAgeMillis > 0L &&
        (nowMillis - pending.createdAtMillis).coerceAtLeast(0L) > maxAgeMillis)

internal fun isDistinctPostEligibilityFix(
    context: Context,
    location: Location,
    pending: PendingNativeTransition,
): Boolean = isDistinctPostEligibilityFixTiming(
    fixWallClockMillis = location.time,
    fixElapsedRealtimeNanos = location.elapsedRealtimeNanos.takeIf { it > 0L },
    currentBootCount = captureAndroidMonotonicTime(context).bootCount,
    pending = pending,
)

internal fun isDistinctPostEligibilityFixTiming(
    fixWallClockMillis: Long,
    fixElapsedRealtimeNanos: Long?,
    currentBootCount: Long?,
    pending: PendingNativeTransition,
): Boolean {
    val candidateElapsedNanos = pending.candidateLocationElapsedRealtimeNanos
        ?: pending.eventMonotonicMillis?.let { it * 1_000_000L }
    val candidateWallClockMillis = pending.candidateLocationTimeMillis
        ?: pending.triggeredAtMillis
    val candidateElapsedMillis = candidateElapsedNanos?.div(1_000_000L)
    val candidateSharesEligibilityStart = candidateElapsedMillis != null &&
        candidateElapsedMillis == pending.eligibilityStartedAtElapsedRealtimeMillis &&
        candidateWallClockMillis == pending.eligibilityStartedAtWallClockMillis
    val candidateBootCount = pending.androidBootCount ?: pending.eligibilityBootCount
        ?.takeIf { candidateSharesEligibilityStart }
    var candidatePredatesCurrentBoot = false
    if (candidateElapsedNanos != null && fixElapsedRealtimeNanos != null) {
        if (candidateBootCount != null && currentBootCount != null) {
            if (candidateBootCount == currentBootCount) {
                if (fixElapsedRealtimeNanos <= candidateElapsedNanos) return false
            } else if (candidateBootCount > currentBootCount) {
                return false
            } else {
                candidatePredatesCurrentBoot = true
            }
        } else {
            val originsConsistent = elapsedWallClockOriginsConsistent(
                candidateElapsedNanos / 1_000_000L,
                candidateWallClockMillis,
                fixElapsedRealtimeNanos / 1_000_000L,
                fixWallClockMillis,
            )
            if (!originsConsistent || fixElapsedRealtimeNanos <= candidateElapsedNanos) {
                return false
            }
        }
    } else {
        if (fixWallClockMillis <= candidateWallClockMillis) return false
    }

    val eligibilityElapsedMillis = pending.eligibleAtElapsedRealtimeMillis
    val fixElapsedMillis = fixElapsedRealtimeNanos?.div(1_000_000L)
    if (eligibilityElapsedMillis != null && fixElapsedMillis != null) {
        val eligibilityBootCount = pending.eligibilityBootCount
        if (eligibilityBootCount != null && currentBootCount != null) {
            if (eligibilityBootCount != currentBootCount ||
                fixElapsedMillis < eligibilityElapsedMillis
            ) {
                return false
            }
            if (candidatePredatesCurrentBoot) {
                val eligibilityStartedElapsedMillis =
                    pending.eligibilityStartedAtElapsedRealtimeMillis ?: return false
                val eligibilityStartedWallClockMillis =
                    pending.eligibilityStartedAtWallClockMillis ?: return false
                if (!elapsedWallClockOriginsConsistent(
                        eligibilityStartedElapsedMillis,
                        eligibilityStartedWallClockMillis,
                        fixElapsedMillis,
                        fixWallClockMillis,
                    )
                ) {
                    return false
                }
            }
        } else {
            val eligibilityStartedElapsedMillis =
                pending.eligibilityStartedAtElapsedRealtimeMillis ?: return false
            val eligibilityStartedWallClockMillis =
                pending.eligibilityStartedAtWallClockMillis ?: return false
            val originsConsistent = elapsedWallClockOriginsConsistent(
                eligibilityStartedElapsedMillis,
                eligibilityStartedWallClockMillis,
                fixElapsedMillis,
                fixWallClockMillis,
            )
            if (!originsConsistent || fixElapsedMillis < eligibilityElapsedMillis) {
                return false
            }
        }
    } else if (fixWallClockMillis < (pending.eligibleAtMillis ?: pending.deadlineAtMillis)) {
        return false
    }
    return true
}

internal data class PendingNativeTransitionResolution(
    val eventName: String?,
    val eventAtMillis: Long?,
    val eventTiming: SmartGeofenceEventTiming?,
    val forcedByPending: Boolean,
)

internal fun pendingNativeTransitionResolution(
    observedState: ObservedFenceState,
    transitionEventName: String?,
    pendingNativeExit: PendingNativeExit?,
    pendingNativeEnter: PendingNativeEnter?,
): PendingNativeTransitionResolution {
    val pendingExitAt = pendingNativeExit?.triggeredAtMillis?.takeIf { it > 0L }
    if (observedState == ObservedFenceState.OUTSIDE && pendingExitAt != null) {
        return PendingNativeTransitionResolution(
            eventName = "exit",
            eventAtMillis = pendingExitAt,
            eventTiming = pendingNativeExit.timing(),
            forcedByPending = true,
        )
    }
    val pendingEnterAt = pendingNativeEnter?.triggeredAtMillis?.takeIf { it > 0L }
    if (observedState == ObservedFenceState.INSIDE && pendingEnterAt != null) {
        return PendingNativeTransitionResolution(
            eventName = "enter",
            eventAtMillis = pendingEnterAt,
            eventTiming = pendingNativeEnter.timing(),
            forcedByPending = true,
        )
    }
    return PendingNativeTransitionResolution(
        eventName = transitionEventName,
        eventAtMillis = null,
        eventTiming = null,
        forcedByPending = false,
    )
}

private fun PendingNativeExit.timing(): SmartGeofenceEventTiming = SmartGeofenceEventTiming(
    wallClockEventAtMillis = triggeredAtMillis,
    eventMonotonicMillis = eventMonotonicMillis,
    androidBootCount = androidBootCount,
    timestampOrigin = timestampOrigin,
)

private fun PendingNativeEnter.timing(): SmartGeofenceEventTiming = SmartGeofenceEventTiming(
    wallClockEventAtMillis = triggeredAtMillis,
    eventMonotonicMillis = eventMonotonicMillis,
    androidBootCount = androidBootCount,
    timestampOrigin = timestampOrigin,
)
