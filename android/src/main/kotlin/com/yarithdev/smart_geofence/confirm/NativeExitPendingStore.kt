package com.yarithdev.smart_geofence.confirm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator
import com.yarithdev.smart_geofence.transition.NativeTransitionDirection
import com.yarithdev.smart_geofence.transition.NativeTransitionScheduler
import com.yarithdev.smart_geofence.transition.PendingNativeTransition
import com.yarithdev.smart_geofence.wake.ForegroundWorkKind

data class PendingNativeExit(
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

internal fun nativeTransitionFallbackDelayMillis(
    config: SmartGeofenceConfig,
    kind: ForegroundWorkKind,
): Long {
    val confirmDelay = LocationConfirmManager.confirmStartDelayMillis(config, kind)
    val confirmTimeout = config.locationConfirmTimeoutMillis.coerceAtLeast(0L)
    val attemptWindow = safeAddLong(confirmDelay, confirmTimeout).coerceAtLeast(0L)
    return safeMultiplyLong(
        attemptWindow,
        config.nativeConfirmMaxAttempts.coerceAtLeast(1).toLong(),
    )
}

internal fun nativeExitFallbackDelayMillis(config: SmartGeofenceConfig): Long =
    nativeTransitionFallbackDelayMillis(config, ForegroundWorkKind.CONFIRM_OUTSIDE)

internal fun nativeEnterFallbackDelayMillis(config: SmartGeofenceConfig): Long =
    nativeTransitionFallbackDelayMillis(config, ForegroundWorkKind.CONFIRM_INSIDE)

internal fun safeAddLong(base: Long, delta: Long): Long =
    if (delta > Long.MAX_VALUE - base) Long.MAX_VALUE else base + delta

internal fun safeMultiplyLong(base: Long, multiplier: Long): Long =
    when {
        base <= 0L || multiplier <= 0L -> 0L
        base > Long.MAX_VALUE / multiplier -> Long.MAX_VALUE
        else -> base * multiplier
    }

object NativeExitPendingStore {
    internal const val SCHEDULE_KEY = "native_exit_fallback"

    fun arm(
        context: Context,
        fenceIds: Collection<String>,
        source: String,
        location: NativeEventLocation?,
        triggeredAtMillis: Long,
        delayMillis: Long,
        eventTiming: SmartGeofenceEventTiming? = null,
        traceId: String? = null,
    ): List<PendingNativeExit> {
        val timing = eventTiming ?: SmartGeofenceEventTimingStore.captureNow(
            context.applicationContext,
            triggeredAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            NativeTransitionDirection.EXIT.timestampOrigin,
        )
        return NativeTransitionCoordinator.arm(
            context = context,
            direction = NativeTransitionDirection.EXIT,
            fenceIds = fenceIds,
            source = source,
            location = location?.toEventLocationEvidence(),
            triggeredAtMillis = triggeredAtMillis,
            delayMillis = delayMillis,
            eventTiming = timing.toEventTimingEvidence(),
            traceId = traceId,
        ).map { it.toPendingNativeExit() }
    }

    @Deprecated("Use confirm.NativeEventLocation")
    @Suppress("DEPRECATION")
    fun arm(
        context: Context,
        fenceIds: Collection<String>,
        source: String,
        location: com.yarithdev.smart_geofence.processing.NativeEventLocation,
        triggeredAtMillis: Long,
        delayMillis: Long,
        eventTiming: SmartGeofenceEventTiming? = null,
        traceId: String? = null,
    ): List<PendingNativeExit> = arm(
        context = context,
        fenceIds = fenceIds,
        source = source,
        location = NativeEventLocation(
            location.latitude,
            location.longitude,
            location.accuracyMeters,
            location.isMock,
        ),
        triggeredAtMillis = triggeredAtMillis,
        delayMillis = delayMillis,
        eventTiming = eventTiming,
        traceId = traceId,
    )

    fun cancel(
        context: Context,
        fenceIds: Collection<String>,
        reason: String,
    ): List<PendingNativeExit> = NativeTransitionCoordinator.cancel(
        context,
        NativeTransitionDirection.EXIT,
        fenceIds,
        reason,
    ).map { it.toPendingNativeExit() }

    fun resolve(context: Context, fenceId: String, reason: String): Boolean =
        cancel(context, listOf(fenceId), reason).isNotEmpty()

    internal fun resolveIfCurrent(
        context: Context,
        pendingExit: PendingNativeExit,
        reason: String,
    ): Boolean = NativeTransitionCoordinator.resolveIfCurrent(
        context,
        pendingExit.toPendingNativeTransition(),
        reason,
    )

    fun restore(
        context: Context,
        pendingExit: PendingNativeExit,
        reason: String,
    ): Boolean = NativeTransitionCoordinator.restore(
        context,
        pendingExit.toPendingNativeTransition(),
        reason,
    )

    internal fun leaseDue(
        context: Context,
        monotonicNow: AndroidMonotonicTime = captureAndroidMonotonicTime(context),
    ): List<PendingNativeExit> = NativeTransitionCoordinator.leaseDue(
        context,
        NativeTransitionDirection.EXIT,
        monotonicNow,
    ).map { it.toPendingNativeExit() }

    fun leaseAll(context: Context, reason: String): List<PendingNativeExit> =
        NativeTransitionCoordinator.leaseAll(
            context,
            NativeTransitionDirection.EXIT,
            reason,
        ).map { it.toPendingNativeExit() }

    fun hasPending(context: Context, fenceId: String): Boolean =
        pendingFor(context, fenceId) != null

    fun pendingFor(context: Context, fenceId: String): PendingNativeExit? =
        NativeTransitionCoordinator.pendingFor(
            context,
            NativeTransitionDirection.EXIT,
            fenceId,
        )?.toPendingNativeExit()

    fun count(context: Context): Int =
        NativeTransitionCoordinator.count(context, NativeTransitionDirection.EXIT)

    fun pendingFenceIds(context: Context): List<String> =
        NativeTransitionCoordinator.pendingFenceIds(context, NativeTransitionDirection.EXIT)

    fun pendingDiagnostics(context: Context): List<Map<String, Any?>> =
        NativeTransitionCoordinator.pendingDiagnostics(context, NativeTransitionDirection.EXIT)

    fun pendingIntentExists(context: Context): Boolean =
        NativeTransitionCoordinator.pendingIntentExists(context, NativeTransitionDirection.EXIT)

    fun reschedule(context: Context) {
        NativeTransitionCoordinator.reschedule(context, NativeTransitionDirection.EXIT)
    }
}

private fun PendingNativeTransition.toPendingNativeExit(): PendingNativeExit =
    PendingNativeExit(
        fenceId = fenceId,
        source = source,
        createdAtMillis = createdAtMillis,
        triggeredAtMillis = triggeredAtMillis,
        deadlineAtMillis = deadlineAtMillis,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        isMock = isMock,
        eventMonotonicMillis = eventMonotonicMillis,
        androidBootCount = androidBootCount,
        timestampOrigin = timestampOrigin,
        deadlineAtElapsedRealtimeMillis = deadlineAtElapsedRealtimeMillis,
        deadlineBootCount = deadlineBootCount,
        deadlineStartedAtElapsedRealtimeMillis = deadlineStartedAtElapsedRealtimeMillis,
        deadlineStartedAtWallClockMillis = deadlineStartedAtWallClockMillis,
        traceId = traceId,
        instanceId = instanceId,
        validationRequired = validationRequired,
        candidateLocationTimeMillis = candidateLocationTimeMillis,
        candidateLocationElapsedRealtimeNanos = candidateLocationElapsedRealtimeNanos,
        fenceRadiusMeters = fenceRadiusMeters,
        confirmationBoundaryMeters = confirmationBoundaryMeters,
        minimumDelayMillis = minimumDelayMillis,
        validationConfigFingerprint = validationConfigFingerprint,
        nativeCandidate = nativeCandidate,
        eligibleAtMillis = eligibleAtMillis,
        eligibleAtElapsedRealtimeMillis = eligibleAtElapsedRealtimeMillis,
        eligibilityBootCount = eligibilityBootCount,
        eligibilityStartedAtElapsedRealtimeMillis = eligibilityStartedAtElapsedRealtimeMillis,
        eligibilityStartedAtWallClockMillis = eligibilityStartedAtWallClockMillis,
        confirmationNotBeforeMillis = confirmationNotBeforeMillis,
    )

private fun PendingNativeExit.toPendingNativeTransition(): PendingNativeTransition =
    PendingNativeTransition(
        direction = NativeTransitionDirection.EXIT,
        fenceId = fenceId,
        source = source,
        createdAtMillis = createdAtMillis,
        triggeredAtMillis = triggeredAtMillis,
        deadlineAtMillis = deadlineAtMillis,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        isMock = isMock,
        eventMonotonicMillis = eventMonotonicMillis,
        androidBootCount = androidBootCount,
        timestampOrigin = timestampOrigin,
        deadlineAtElapsedRealtimeMillis = deadlineAtElapsedRealtimeMillis,
        deadlineBootCount = deadlineBootCount,
        deadlineStartedAtElapsedRealtimeMillis = deadlineStartedAtElapsedRealtimeMillis,
        deadlineStartedAtWallClockMillis = deadlineStartedAtWallClockMillis,
        traceId = traceId,
        instanceId = instanceId,
        validationRequired = validationRequired,
        candidateLocationTimeMillis = candidateLocationTimeMillis,
        candidateLocationElapsedRealtimeNanos = candidateLocationElapsedRealtimeNanos,
        fenceRadiusMeters = fenceRadiusMeters,
        confirmationBoundaryMeters = confirmationBoundaryMeters,
        minimumDelayMillis = minimumDelayMillis,
        validationConfigFingerprint = validationConfigFingerprint,
        nativeCandidate = nativeCandidate,
        eligibleAtMillis = eligibleAtMillis,
        eligibleAtElapsedRealtimeMillis = eligibleAtElapsedRealtimeMillis,
        eligibilityBootCount = eligibilityBootCount,
        eligibilityStartedAtElapsedRealtimeMillis = eligibilityStartedAtElapsedRealtimeMillis,
        eligibilityStartedAtWallClockMillis = eligibilityStartedAtWallClockMillis,
        confirmationNotBeforeMillis = confirmationNotBeforeMillis,
    )

class NativeExitFallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_NATIVE_EXIT_FALLBACK) return
        val lifetime = FallbackReceiverLifetime(
            context,
            goAsync(),
            "NativeExitFallbackReceiver",
        )
        try {
            SmartGeofenceEventProcessor.emitDueNativeExitFallbacks(
                context.applicationContext,
                launchExemption = NativeTransitionScheduler.firedAlarmWakeExemption(
                    context.applicationContext,
                    NativeTransitionDirection.EXIT,
                ),
            ) { lifetime.finish() }
        } catch (error: Throwable) {
            SmartGeofenceLogger.w(
                context.applicationContext,
                "NativeExitFallbackReceiver",
                "Failed while emitting due native EXIT fallbacks: ${error.message}",
                error,
            )
            lifetime.finish()
        }
    }
}
