package com.yarithdev.smart_geofence.transition

import com.yarithdev.smart_geofence.core.Constants

internal enum class NativeTransitionDirection(
    val logTag: String,
    val timestampOrigin: String,
    val scheduleKey: String,
    val action: String,
    val pendingIntentRequestCode: Int,
    val receiverClassName: String,
    val pluralName: String,
) {
    ENTER(
        logTag = "NativeEnterPendingStore",
        timestampOrigin = "native_enter",
        scheduleKey = "native_enter_fallback",
        action = Constants.ACTION_NATIVE_ENTER_FALLBACK,
        pendingIntentRequestCode = Constants.PENDING_INTENT_REQUEST_NATIVE_ENTER_FALLBACK,
        receiverClassName = "com.yarithdev.smart_geofence.confirm.NativeEnterFallbackReceiver",
        pluralName = "enters",
    ),
    EXIT(
        logTag = "NativeExitPendingStore",
        timestampOrigin = "native_exit",
        scheduleKey = "native_exit_fallback",
        action = Constants.ACTION_NATIVE_EXIT_FALLBACK,
        pendingIntentRequestCode = Constants.PENDING_INTENT_REQUEST_NATIVE_EXIT_FALLBACK,
        receiverClassName = "com.yarithdev.smart_geofence.confirm.NativeExitFallbackReceiver",
        pluralName = "exits",
    ),
}

internal data class PendingNativeTransition(
    val direction: NativeTransitionDirection,
    val fenceId: String,
    val source: String,
    val createdAtMillis: Long,
    val triggeredAtMillis: Long,
    val deadlineAtMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
    val isMock: Boolean,
    val eventMonotonicMillis: Long?,
    val androidBootCount: Long?,
    val timestampOrigin: String,
    val deadlineAtElapsedRealtimeMillis: Long? = null,
    val deadlineBootCount: Long? = null,
    val deadlineStartedAtElapsedRealtimeMillis: Long? = null,
    val deadlineStartedAtWallClockMillis: Long? = null,
    val traceId: String? = null,
    val instanceId: String = "",
    val validationRequired: Boolean = false,
    val candidateLocationTimeMillis: Long? = null,
    val candidateLocationElapsedRealtimeNanos: Long? = null,
    val fenceRadiusMeters: Double? = null,
    val confirmationBoundaryMeters: Double? = null,
    val minimumDelayMillis: Long? = null,
    val validationConfigFingerprint: String? = null,
    val nativeCandidate: Boolean = false,
    val eligibleAtMillis: Long? = null,
    val eligibleAtElapsedRealtimeMillis: Long? = null,
    val eligibilityBootCount: Long? = null,
    val eligibilityStartedAtElapsedRealtimeMillis: Long? = null,
    val eligibilityStartedAtWallClockMillis: Long? = null,
    val confirmationNotBeforeMillis: Long? = null,
)

internal data class NativeTransitionKey(
    val direction: NativeTransitionDirection,
    val fenceId: String,
)

internal val PendingNativeTransition.key: NativeTransitionKey
    get() = NativeTransitionKey(direction, fenceId)

internal fun safeTransitionAdd(base: Long, delta: Long): Long =
    if (delta > Long.MAX_VALUE - base) Long.MAX_VALUE else base + delta
