package com.yarithdev.smart_geofence.confirm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator
import com.yarithdev.smart_geofence.transition.NativeTransitionDirection
import com.yarithdev.smart_geofence.transition.NativeTransitionScheduler
import com.yarithdev.smart_geofence.transition.PendingNativeTransition

data class PendingNativeEnter(
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

object NativeEnterPendingStore {
    internal const val SCHEDULE_KEY = "native_enter_fallback"

    fun arm(
        context: Context,
        fenceIds: Collection<String>,
        source: String,
        location: NativeEventLocation?,
        triggeredAtMillis: Long,
        delayMillis: Long,
        eventTiming: SmartGeofenceEventTiming? = null,
        traceId: String? = null,
    ): List<PendingNativeEnter> {
        val timing = eventTiming ?: SmartGeofenceEventTimingStore.captureNow(
            context.applicationContext,
            triggeredAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            NativeTransitionDirection.ENTER.timestampOrigin,
        )
        return NativeTransitionCoordinator.arm(
            context = context,
            direction = NativeTransitionDirection.ENTER,
            fenceIds = fenceIds,
            source = source,
            location = location?.toEventLocationEvidence(),
            triggeredAtMillis = triggeredAtMillis,
            delayMillis = delayMillis,
            eventTiming = timing.toEventTimingEvidence(),
            traceId = traceId,
        ).map { it.toPendingNativeEnter() }
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
    ): List<PendingNativeEnter> = arm(
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
    ): List<PendingNativeEnter> = NativeTransitionCoordinator.cancel(
        context,
        NativeTransitionDirection.ENTER,
        fenceIds,
        reason,
    ).map { it.toPendingNativeEnter() }

    fun resolve(context: Context, fenceId: String, reason: String): Boolean =
        cancel(context, listOf(fenceId), reason).isNotEmpty()

    internal fun resolveIfCurrent(
        context: Context,
        pendingEnter: PendingNativeEnter,
        reason: String,
    ): Boolean = NativeTransitionCoordinator.resolveIfCurrent(
        context,
        pendingEnter.toPendingNativeTransition(),
        reason,
    )

    fun restore(
        context: Context,
        pendingEnter: PendingNativeEnter,
        reason: String,
    ): Boolean = NativeTransitionCoordinator.restore(
        context,
        pendingEnter.toPendingNativeTransition(),
        reason,
    )

    internal fun leaseDue(
        context: Context,
        monotonicNow: AndroidMonotonicTime = captureAndroidMonotonicTime(context),
    ): List<PendingNativeEnter> = NativeTransitionCoordinator.leaseDue(
        context,
        NativeTransitionDirection.ENTER,
        monotonicNow,
    ).map { it.toPendingNativeEnter() }

    fun leaseAll(context: Context, reason: String): List<PendingNativeEnter> =
        NativeTransitionCoordinator.leaseAll(
            context,
            NativeTransitionDirection.ENTER,
            reason,
        ).map { it.toPendingNativeEnter() }

    fun hasPending(context: Context, fenceId: String): Boolean =
        pendingFor(context, fenceId) != null

    fun pendingFor(context: Context, fenceId: String): PendingNativeEnter? =
        NativeTransitionCoordinator.pendingFor(
            context,
            NativeTransitionDirection.ENTER,
            fenceId,
        )?.toPendingNativeEnter()

    fun count(context: Context): Int =
        NativeTransitionCoordinator.count(context, NativeTransitionDirection.ENTER)

    fun pendingFenceIds(context: Context): List<String> =
        NativeTransitionCoordinator.pendingFenceIds(context, NativeTransitionDirection.ENTER)

    fun pendingDiagnostics(context: Context): List<Map<String, Any?>> =
        NativeTransitionCoordinator.pendingDiagnostics(context, NativeTransitionDirection.ENTER)

    fun pendingIntentExists(context: Context): Boolean =
        NativeTransitionCoordinator.pendingIntentExists(context, NativeTransitionDirection.ENTER)

    fun reschedule(context: Context) {
        NativeTransitionCoordinator.reschedule(context, NativeTransitionDirection.ENTER)
    }
}

private fun PendingNativeTransition.toPendingNativeEnter(): PendingNativeEnter =
    PendingNativeEnter(
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

private fun PendingNativeEnter.toPendingNativeTransition(): PendingNativeTransition =
    PendingNativeTransition(
        direction = NativeTransitionDirection.ENTER,
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

class NativeEnterFallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_NATIVE_ENTER_FALLBACK) return
        val lifetime = FallbackReceiverLifetime(
            context,
            goAsync(),
            "NativeEnterFallbackReceiver",
        )
        try {
            SmartGeofenceEventProcessor.emitDueNativeEnterFallbacks(
                context.applicationContext,
                launchExemption = NativeTransitionScheduler.firedAlarmWakeExemption(
                    context.applicationContext,
                    NativeTransitionDirection.ENTER,
                ),
            ) { lifetime.finish() }
        } catch (error: Throwable) {
            SmartGeofenceLogger.w(
                context.applicationContext,
                "NativeEnterFallbackReceiver",
                "Failed while emitting due native ENTER fallbacks: ${error.message}",
                error,
            )
            lifetime.finish()
        }
    }
}
