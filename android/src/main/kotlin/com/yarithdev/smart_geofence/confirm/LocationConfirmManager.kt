package com.yarithdev.smart_geofence.confirm

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionMode
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.AndroidPackageManagerCompat
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.foreground.ForegroundLaunchState
import com.yarithdev.smart_geofence.foreground.ForegroundStartAlarmResult
import com.yarithdev.smart_geofence.foreground.ForegroundStartCoordinator
import com.yarithdev.smart_geofence.foreground.ForegroundStartSpec
import com.yarithdev.smart_geofence.fused.FusedLocationPermissions
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator
import com.yarithdev.smart_geofence.wake.ForegroundWorkItem
import com.yarithdev.smart_geofence.wake.ForegroundWorkKind
import com.yarithdev.smart_geofence.wake.ForegroundQueueMutationStatus
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator
import com.yarithdev.smart_geofence.wake.WakeExemption
import com.yarithdev.smart_geofence.wake.allowsForegroundServiceLaunch
import com.yarithdev.smart_geofence.wake.nativeConfirmDedupeKey

internal data class NativeExitConfirmTimingDecision(
    val mode: String,
    val reason: String,
    val bypassDelay: Boolean,
)

internal fun nativeExitConfirmTimingDecision(
    kind: ForegroundWorkKind,
    source: String,
    launchExemption: WakeExemption,
    exactAlarmPermissionMode: ExactAlarmPermissionMode,
    startAlarmResult: ForegroundStartAlarmResult,
): NativeExitConfirmTimingDecision {
    if (startAlarmResult.scheduled) {
        return NativeExitConfirmTimingDecision(
            mode = "delayed",
            reason = "exit_confirm_delay_exact_alarm_scheduled",
            bypassDelay = false,
        )
    }
    val decision = nativeTransitionConfirmTimingDecision(
        kind,
        source,
        launchExemption,
        exactAlarmPermissionMode,
        startAlarmResult,
    )
    return if (decision.reason == "not_native_transition_confirm") {
        decision.copy(reason = "not_native_exit_outside_confirm")
    } else {
        decision
    }
}

internal fun nativeTransitionConfirmTimingDecision(
    kind: ForegroundWorkKind,
    source: String,
    launchExemption: WakeExemption,
    exactAlarmPermissionMode: ExactAlarmPermissionMode,
    startAlarmResult: ForegroundStartAlarmResult,
): NativeExitConfirmTimingDecision {
    val transitionName = nativeTransitionName(kind, source)
    if (startAlarmResult.scheduled) {
        return NativeExitConfirmTimingDecision(
            mode = "delayed",
            reason = if (transitionName == null) {
                "confirm_delay_exact_alarm_scheduled"
            } else {
                "${transitionName}_confirm_delay_exact_alarm_scheduled"
            },
            bypassDelay = false,
        )
    }
    if (transitionName == null) {
        return NativeExitConfirmTimingDecision(
            mode = "deferred",
            reason = "not_native_transition_confirm",
            bypassDelay = false,
        )
    }
    if (launchExemption != WakeExemption.GEOFENCE) {
        return NativeExitConfirmTimingDecision(
            mode = "deferred",
            reason = "no_geofence_exemption",
            bypassDelay = false,
        )
    }
    if (exactAlarmPermissionMode == ExactAlarmPermissionMode.Strict) {
        return NativeExitConfirmTimingDecision(
            mode = "deferred",
            reason = "strict_exact_alarm_required",
            bypassDelay = false,
        )
    }
    if (startAlarmResult.exactUnavailable) {
        return NativeExitConfirmTimingDecision(
            mode = "immediate",
            reason = "${transitionName}_confirm_delay_bypassed_exact_unavailable",
            bypassDelay = true,
        )
    }
    return NativeExitConfirmTimingDecision(
        mode = "deferred",
        reason = startAlarmResult.failureReason ?: startAlarmResult.eventSuffix,
        bypassDelay = false,
    )
}

private fun ForegroundWorkItem.isNativeExitOutsideConfirm(): Boolean =
    isOutsideConfirm && source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM)

private fun ForegroundWorkItem.isNativeEnterInsideConfirm(): Boolean =
    isInsideConfirm && source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)

private fun nativeTransitionName(kind: ForegroundWorkKind, source: String): String? =
    when {
        kind == ForegroundWorkKind.CONFIRM_OUTSIDE &&
            source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM) -> "exit"
        kind == ForegroundWorkKind.CONFIRM_INSIDE &&
            source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM) -> "enter"
        else -> null
    }

internal fun queuedConfirmStartDelayMillis(
    nextReadyAtMillis: Long,
    nowMillis: Long,
): Long = if (nextReadyAtMillis <= nowMillis) 0L else nextReadyAtMillis - nowMillis

internal fun shouldScheduleQueueStartAlarm(
    existingPendingIntent: Boolean,
    existingTriggerAtMillis: Long?,
    desiredTriggerAtMillis: Long,
    nowMillis: Long,
): Boolean = !existingPendingIntent ||
    existingTriggerAtMillis == null ||
    existingTriggerAtMillis <= nowMillis ||
    existingTriggerAtMillis > desiredTriggerAtMillis

object LocationConfirmManager {
    private const val TAG = "LocationConfirmManager"
    private const val FGS_LOG_SERVICE = "confirm"
    const val EXTRA_START_ATTEMPT = "smart_geofence.location_confirm_start_attempt"
    const val EXTRA_LAUNCH_TOKEN = "smart_geofence.location_confirm_launch_token"
    const val EXTRA_ALARM_PURPOSE = "smart_geofence.location_confirm_alarm_purpose"
    const val PURPOSE_START = "start"
    const val PURPOSE_WATCHDOG = "watchdog"
    const val PURPOSE_REARM = "foreground_rearm"
    const val PURPOSE_LOCATION_DISABLED_RECOVERY = "location_disabled_recovery"

    internal val LAUNCH_SPEC = ForegroundStartSpec(
        serviceKey = ForegroundLaunchState.SERVICE_LOCATION_CONFIRM,
        logService = FGS_LOG_SERVICE,
        tag = TAG,
        receiverClass = LocationConfirmAlarmReceiver::class.java,
        startRequestCode = Constants.PENDING_INTENT_REQUEST_CONFIRM_LAUNCH,
        watchdogRequestCode = Constants.PENDING_INTENT_REQUEST_CONFIRM_WATCHDOG,
        startAttemptExtra = EXTRA_START_ATTEMPT,
        launchTokenExtra = EXTRA_LAUNCH_TOKEN,
        alarmPurposeExtra = EXTRA_ALARM_PURPOSE,
        purposeStart = PURPOSE_START,
        purposeWatchdog = PURPOSE_WATCHDOG,
    )

    fun enqueueProximity(
        context: Context,
        source: String,
        launchExemption: WakeExemption,
        traceId: String? = null,
    ): Boolean {
        val appContext = context.applicationContext
        val notBeforeMillis = nextConfirmNotBeforeMillis(
            appContext,
            ForegroundWorkKind.CONFIRM_PROXIMITY,
            source,
        )
        val request = if (FusedLocationConfirm.isPulseSource(source)) {
            WakeEventCoordinator.enqueuePulseConfirmUnlessRunning(
                appContext,
                source,
                notBeforeMillis,
                traceId = traceId,
            )
        } else {
            WakeEventCoordinator.enqueueProximityConfirm(
                appContext,
                source,
                notBeforeMillis,
                traceId = traceId,
            )
        }
        if (request == null) {
            SmartGeofenceDiagnostics.recordTrace(
                appContext,
                stage = "pulse_confirm_deduped",
                reasonCode = "running_acquisition_owns_lane",
                traceId = traceId,
                source = source,
                extras = mapOf("freshOnly" to true),
            )
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Pulse confirm already running source=$source; shared acquisition owns this tick.",
            )
            return true
        }
        SmartGeofenceDiagnostics.recordConfirmQueueRequest(
            appContext,
            request.id,
            request.fenceId,
            request.isProximityConfirm,
            request.source,
            ageMillis = 0L,
            traceId = request.traceId,
        )
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Confirm proximity work queued id=${request.id} source=$source " +
                "notBefore=${request.notBeforeMillis} exemption=$launchExemption."
        )
        if (LocationServicesState.isLocationEnabled(appContext) == false) {
            parkFreshLocationDisabled(
                context = appContext,
                request = request,
                label = "proximity",
                failureReason = "fresh_proximity_location_services_disabled",
            )
            return true
        }
        return launchQueuedRequest(
            appContext,
            request = request,
            tokenReason = "queued proximity confirm work id=${request.id}",
            logDetail = "workId=${request.id} source=$source type=confirm_proximity",
            launchExemption = launchExemption,
        )
    }

    fun enqueueOutside(
        context: Context,
        source: String,
        launchExemption: WakeExemption,
        nativeFenceIds: Collection<String> = emptyList(),
        nativeTransitionInstances: Map<String, String> = emptyMap(),
        traceId: String? = null,
        confirmationNotBeforeMillis: Long? = null,
    ): Boolean {
        val appContext = context.applicationContext
        val notBeforeMillis = confirmationNotBeforeMillis
            ?: nextConfirmNotBeforeMillis(
                appContext,
                ForegroundWorkKind.CONFIRM_OUTSIDE,
                source,
            )
        val request = WakeEventCoordinator.enqueueOutsideConfirm(
            appContext,
            source,
            notBeforeMillis,
            dedupeKey = if (nativeFenceIds.isEmpty()) {
                com.yarithdev.smart_geofence.wake.normalizedConfirmDedupeKey(
                    ForegroundWorkKind.CONFIRM_OUTSIDE,
                )
            } else {
                nativeConfirmDedupeKey(ForegroundWorkKind.CONFIRM_OUTSIDE, nativeFenceIds)
            },
            nativeFenceIds = nativeFenceIds,
            nativeTransitionInstances = nativeTransitionInstances,
            traceId = traceId,
        )
        SmartGeofenceDiagnostics.recordConfirmQueueRequest(
            appContext,
            request.id,
            request.fenceId,
            request.isProximityConfirm,
            request.source,
            ageMillis = 0L,
            traceId = request.traceId,
        )
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Confirm outside work queued id=${request.id} source=$source " +
                "notBefore=${request.notBeforeMillis} exemption=$launchExemption."
        )
        if (LocationServicesState.isLocationEnabled(appContext) == false) {
            parkFreshLocationDisabled(
                context = appContext,
                request = request,
                label = "outside",
                failureReason = "fresh_outside_location_services_disabled",
            )
            return true
        }
        return launchQueuedRequest(
            appContext,
            request = request,
            tokenReason = "queued outside confirm work id=${request.id}",
            logDetail = "workId=${request.id} source=$source type=confirm_outside",
            launchExemption = launchExemption,
        )
    }

    fun enqueueInside(
        context: Context,
        source: String,
        launchExemption: WakeExemption,
        nativeFenceIds: Collection<String> = emptyList(),
        nativeTransitionInstances: Map<String, String> = emptyMap(),
        traceId: String? = null,
        confirmationNotBeforeMillis: Long? = null,
    ): Boolean {
        val appContext = context.applicationContext
        val notBeforeMillis = confirmationNotBeforeMillis
            ?: nextConfirmNotBeforeMillis(
                appContext,
                ForegroundWorkKind.CONFIRM_INSIDE,
                source,
            )
        val request = WakeEventCoordinator.enqueueInsideConfirm(
            appContext,
            source,
            notBeforeMillis,
            dedupeKey = if (nativeFenceIds.isEmpty()) {
                com.yarithdev.smart_geofence.wake.normalizedConfirmDedupeKey(
                    ForegroundWorkKind.CONFIRM_INSIDE,
                )
            } else {
                nativeConfirmDedupeKey(ForegroundWorkKind.CONFIRM_INSIDE, nativeFenceIds)
            },
            nativeFenceIds = nativeFenceIds,
            nativeTransitionInstances = nativeTransitionInstances,
            traceId = traceId,
        )
        SmartGeofenceDiagnostics.recordConfirmQueueRequest(
            appContext,
            request.id,
            request.fenceId,
            request.isProximityConfirm,
            request.source,
            ageMillis = 0L,
            traceId = request.traceId,
        )
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Confirm inside work queued id=${request.id} source=$source " +
                "notBefore=${request.notBeforeMillis} exemption=$launchExemption."
        )
        if (LocationServicesState.isLocationEnabled(appContext) == false) {
            parkFreshLocationDisabled(
                context = appContext,
                request = request,
                label = "inside",
                failureReason = "fresh_inside_location_services_disabled",
            )
            return true
        }
        return launchQueuedRequest(
            appContext,
            request = request,
            tokenReason = "queued inside confirm work id=${request.id}",
            logDetail = "workId=${request.id} source=$source type=confirm_inside",
            launchExemption = launchExemption,
        )
    }

    private fun parkFreshLocationDisabled(
        context: Context,
        request: ForegroundWorkItem,
        label: String,
        failureReason: String,
    ) {
        val mutation = WakeEventCoordinator.parkConfirmIfUnchanged(
            context,
            request,
            request.attemptCount,
            Constants.CONFIRM_PARKED_REASON_LOCATION_DISABLED,
            failureReason,
        )
        val parked = mutation.item
        if (mutation.status == ForegroundQueueMutationStatus.REPLACED && parked != null) {
            onConfirmWorkParked(context, failureReason)
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Parked $label confirm work id=${parked.id}; location services are disabled.",
            )
        } else {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Skipped parking stale $label confirm work id=${request.id} " +
                    "queueStatus=${mutation.status.name.lowercase()}.",
            )
        }
    }

    private fun launchQueuedRequest(
        context: Context,
        request: ForegroundWorkItem,
        tokenReason: String,
        logDetail: String,
        launchExemption: WakeExemption,
    ): Boolean {
        val appContext = context.applicationContext
        if (request.isParked) {
            SmartGeofenceDiagnostics.recordTrace(
                appContext,
                stage = "confirm_launch",
                reasonCode = "parked",
                traceId = request.traceId,
                source = request.source,
                extras = mapOf("requestId" to request.id),
            )
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Confirm work queued but parked; launch skipped. $logDetail " +
                    "parkedReason=${request.parkedReason} attempts=${request.attemptCount}.",
            )
            return true
        }
        val now = System.currentTimeMillis()
        val canLaunchForegroundService = launchExemption.allowsForegroundServiceLaunch()
        if (!canLaunchForegroundService && !LocationConfirmService.isForegroundReady) {
            val scheduled = scheduleNextReadyWork(
                appContext,
                reason = "queued confirm work without launch exemption",
            )
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Confirm work queued without foreground-service launch exemption; " +
                    "$logDetail notBefore=${request.notBeforeMillis} " +
                    "exemption=$launchExemption scheduledReadyAlarm=$scheduled."
            )
            SmartGeofenceDiagnostics.recordTrace(
                appContext,
                stage = "confirm_launch",
                reasonCode = if (scheduled) {
                    if (request.isReadyAt(now)) {
                        "ready_exact_bridge_scheduled"
                    } else {
                        "deferred_alarm_scheduled"
                    }
                } else {
                    "deferred_no_exemption"
                },
                traceId = request.traceId,
                source = request.source,
                extras = linkedMapOf(
                    "requestId" to request.id,
                    "notBeforeMillis" to request.notBeforeMillis,
                    "exemption" to launchExemption.name.lowercase(),
                ),
            )
            return true
        }
        val reuseLaunch = (
            LocationConfirmService.isRunning ||
                pendingIntentExists(appContext) ||
                watchdogPendingIntentExists(appContext)
            )
        val token = if (reuseLaunch) {
            ForegroundStartCoordinator.ensureLaunchToken(appContext, LAUNCH_SPEC, tokenReason)
        } else {
            ForegroundServiceRearm.cancel(appContext, "new_confirm_launch")
            ForegroundStartCoordinator.beginLaunch(appContext, LAUNCH_SPEC, tokenReason)
        }
        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "confirm_launch",
            reasonCode = if (reuseLaunch) "reused" else "requested",
            traceId = request.traceId,
            source = request.source,
            extras = linkedMapOf(
                "requestId" to request.id,
                "launchToken" to token,
                "exemption" to launchExemption.name.lowercase(),
            ),
        )
        SmartGeofenceLogger.fgs(
            appContext,
            FGS_LOG_SERVICE,
            "launch_requested",
            token,
            0,
            "$logDetail reused=$reuseLaunch notBefore=${request.notBeforeMillis} " +
                "exemption=$launchExemption"
        )
        if (reuseLaunch) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Confirm launch already running or scheduled; wake work will drain token=$token."
            )
            if (LocationConfirmService.isForegroundReady &&
                request.isReadyAt(now)
            ) {
                WakeEventCoordinator.drainForegroundWork(appContext)
                return true
            }
            if (!request.isReadyAt(now)) {
                val startAlarmResult = scheduleNextReadyAlarmDetailed(
                    appContext,
                    reason = "refreshed queued confirm work",
                    launchToken = token,
                )
                val timingResult = startAlarmResult?.let {
                    handleNativeTransitionConfirmTimingAfterAlarm(
                        appContext,
                        request = request,
                        now = now,
                        delayMs = (request.notBeforeMillis - now).coerceAtLeast(0L),
                        launchExemption = launchExemption,
                        startAlarmResult = it,
                        launchToken = token,
                    )
                }
                if (timingResult != null) return timingResult
                return startAlarmResult?.scheduled == true || pendingIntentExists(appContext)
            }
            return true
        }

        val delayMs = (request.notBeforeMillis - now).coerceAtLeast(0L)
        if (delayMs == 0L) {
            return requestStart(appContext, attempt = 0, reason = "queued", launchToken = token)
        }

        val startAlarmResult = scheduleStartAlarmDetailed(
            appContext,
            delayMs,
            attempt = 0,
            launchToken = token,
            reason = "queued"
        )
        val timingResult = handleNativeTransitionConfirmTimingAfterAlarm(
            appContext,
            request = request,
            now = now,
            delayMs = delayMs,
            launchExemption = launchExemption,
            startAlarmResult = startAlarmResult,
            launchToken = token,
        )
        if (timingResult != null) return timingResult

        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Delayed exact confirm start alarm was not scheduled; queued work will wait for a real " +
                "foreground-service exemption."
        )
        return true
    }

    private fun handleNativeTransitionConfirmTimingAfterAlarm(
        context: Context,
        request: ForegroundWorkItem,
        now: Long,
        delayMs: Long,
        launchExemption: WakeExemption,
        startAlarmResult: ForegroundStartAlarmResult,
        launchToken: Long,
    ): Boolean? {
        val appContext = context.applicationContext
        val timingDecision = nativeTransitionConfirmTimingDecision(
            kind = request.kind,
            source = request.source,
            launchExemption = launchExemption,
            exactAlarmPermissionMode = SmartGeofenceConfigStore.load(appContext)
                .exactAlarmPermissionMode,
            startAlarmResult = startAlarmResult,
        )
        if (request.isNativeExitOutsideConfirm()) {
            SmartGeofenceDiagnostics.recordNativeExitConfirmTiming(
                appContext,
                timingDecision.mode,
                timingDecision.reason,
            )
        } else if (request.isNativeEnterInsideConfirm()) {
            SmartGeofenceDiagnostics.recordNativeEnterConfirmTiming(
                appContext,
                timingDecision.mode,
                timingDecision.reason,
            )
        }
        if (startAlarmResult.scheduled) return true
        if (!timingDecision.bypassDelay) return null

        val config = SmartGeofenceConfigStore.load(appContext)
        val sharedValidationBlocksEarlyStart = when {
            request.isNativeExitOutsideConfirm() ->
                config.transitionValidationEnabled && config.transitionValidationExitEnabled
            request.isNativeEnterInsideConfirm() ->
                config.transitionValidationEnabled && config.transitionValidationEnterEnabled
            else -> false
        }
        if (sharedValidationBlocksEarlyStart) {
            recordNativeTransitionTiming(
                appContext,
                request,
                "deferred",
                "shared_transition_validation_blocks_early_start",
            )
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Native transition confirmation remains queued until eligibility; " +
                    "shared validation forbids exact-alarm delay bypass " +
                    "workId=${request.id} delay=${delayMs}ms.",
            )
            return true
        }

        val readyRequest = WakeEventCoordinator.markForegroundWorkReady(
            appContext,
            request,
            now,
        )
        if (readyRequest == null) {
            recordNativeTransitionTiming(
                appContext,
                request,
                "immediate_start_failed",
                "queued_work_changed",
            )
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Native transition confirm delay bypass failed; queued work changed before " +
                    "it could be marked ready. workId=${request.id} source=${request.source}.",
            )
            return false
        }
        SmartGeofenceLogger.fgsWarning(
            appContext,
            FGS_LOG_SERVICE,
            timingDecision.reason,
            launchToken,
            0,
            "workId=${readyRequest.id} delay=${delayMs}ms " +
                "reason=${startAlarmResult.failureReason ?: startAlarmResult.eventSuffix}"
        )
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Bypassing native transition confirm delay because exact alarms are unavailable; " +
                "starting confirm immediately workId=${readyRequest.id} delay=${delayMs}ms.",
        )
        val started = requestStart(
            appContext,
            attempt = 0,
            reason = timingDecision.reason,
            launchToken = launchToken,
        )
        if (!started) {
            recordNativeTransitionTiming(
                appContext,
                request,
                "immediate_start_failed",
                "immediate_start_rejected",
            )
        }
        return started
    }

    private fun recordNativeTransitionTiming(
        context: Context,
        request: ForegroundWorkItem,
        mode: String,
        reason: String,
    ) {
        if (request.isNativeExitOutsideConfirm()) {
            SmartGeofenceDiagnostics.recordNativeExitConfirmTiming(context, mode, reason)
        } else if (request.isNativeEnterInsideConfirm()) {
            SmartGeofenceDiagnostics.recordNativeEnterConfirmTiming(context, mode, reason)
        }
    }

    fun requestStart(
        context: Context,
        attempt: Int,
        reason: String,
        launchToken: Long = 0L,
    ): Boolean {
        val appContext = context.applicationContext
        val token = if (launchToken > 0L) {
            launchToken
        } else {
            ForegroundStartCoordinator.beginLaunch(appContext, LAUNCH_SPEC, reason)
        }
        if (!ForegroundStartCoordinator.isCurrent(appContext, LAUNCH_SPEC, token)) {
            SmartGeofenceLogger.fgsWarning(
                appContext,
                FGS_LOG_SERVICE,
                "start_skipped_stale",
                token,
                attempt
            )
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Confirm service start skipped; stale launch token=$token attempt=$attempt."
            )
            return false
        }
        ForegroundStartCoordinator.cancelStart(appContext, LAUNCH_SPEC)
        val pendingCount = WakeEventCoordinator.foregroundWorkCount(appContext)
        if (pendingCount == 0) {
            SmartGeofenceLogger.fgs(
                appContext,
                FGS_LOG_SERVICE,
                "start_skipped",
                token,
                attempt,
                "reason=empty_queue"
            )
            SmartGeofenceLogger.d(appContext, TAG, "Confirm service start skipped; queue is empty.")
            return false
        }
        val readyCount = WakeEventCoordinator.readyForegroundWorkCount(appContext)
        if (readyCount == 0) {
            val scheduled = scheduleNextReadyWork(
                appContext,
                reason = "start requested before confirm work became eligible",
                launchToken = token,
            )
            SmartGeofenceLogger.fgs(
                appContext,
                FGS_LOG_SERVICE,
                "start_deferred",
                token,
                attempt,
                "reason=work_not_ready pending=$pendingCount scheduled=$scheduled"
            )
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Confirm service start deferred; pending=$pendingCount ready=0 scheduled=$scheduled."
            )
            return scheduled
        }
        if (LocationConfirmService.isForegroundReady) {
            SmartGeofenceLogger.fgs(
                appContext,
                FGS_LOG_SERVICE,
                "start_skipped",
                token,
                attempt,
                "reason=already_foreground_ready pending=$pendingCount"
            )
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Confirm service is already foreground-ready; handing work to WakeEventCoordinator."
            )
            WakeEventCoordinator.drainForegroundWork(appContext)
            return true
        }
        if (!canStartForegroundLocationService(appContext)) {
            ForegroundStartCoordinator.markFailure(
                appContext,
                LAUNCH_SPEC,
                token,
                "foreground-location service prerequisites missing"
            )
            SmartGeofenceLogger.fgsWarning(
                appContext,
                FGS_LOG_SERVICE,
                "start_skipped",
                token,
                attempt,
                "reason=missing_prerequisites"
            )
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Confirm service start skipped; foreground-location service prerequisites are missing."
            )
            return false
        }
        return try {
            val intent = Intent(appContext, LocationConfirmService::class.java)
                .putExtra(EXTRA_START_ATTEMPT, attempt)
                .putExtra(EXTRA_LAUNCH_TOKEN, token)
            ContextCompat.startForegroundService(appContext, intent)
            SmartGeofenceLogger.fgs(
                appContext,
                FGS_LOG_SERVICE,
                "startForegroundService_accepted",
                token,
                attempt,
                "reason=$reason pending=$pendingCount"
            )
            val watchdogScheduled = scheduleWatchdog(appContext, token, attempt)
            if (!watchdogScheduled) {
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Foreground-ready watchdog unavailable token=$token; arming rearm as a safety net."
                )
                ForegroundServiceRearm.arm(
                    appContext,
                    attempt,
                    token,
                    "foreground watchdog unavailable",
                )
            }
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Requested confirm foreground service start token=$token attempt=$attempt " +
                    "reason=$reason pending=$pendingCount."
            )
            true
        } catch (e: Throwable) {
            val failureReason = "startForegroundService ${e.javaClass.simpleName}"
            ForegroundStartCoordinator.markFailure(
                appContext,
                LAUNCH_SPEC,
                token,
                failureReason
            )
            SmartGeofenceLogger.fgsWarning(
                appContext,
                FGS_LOG_SERVICE,
                "startForegroundService_failed",
                token,
                attempt,
                "reason=$reason error=${e.javaClass.simpleName}: ${e.message}",
                e
            )
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Confirm foreground service start failed token=$token attempt=$attempt reason=$reason: " +
                    "${e.javaClass.simpleName}: ${e.message}",
                e
            )
            if (LocationConfirmService.isForegroundReady) {
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Confirm service is already foreground-ready; handing work to WakeEventCoordinator."
                )
                WakeEventCoordinator.drainForegroundWork(appContext)
                true
            } else {
                if (LocationConfirmService.isRunning) {
                    SmartGeofenceLogger.d(
                        appContext,
                        TAG,
                        "Confirm service exists but is not foreground-ready; scheduling rearm."
                    )
                }
                val rearmed = ForegroundServiceRearm.arm(appContext, attempt, token, failureReason)
                if (rearmed) {
                    ForegroundStartCoordinator.markBatchServiceFinished(
                        appContext,
                        LAUNCH_SPEC.serviceKey,
                        "start_rearmed",
                        LAUNCH_SPEC.logService,
                        token,
                        attempt,
                    )
                }
                rearmed
            }
        }
    }

    fun onForegroundReady(context: Context, attempt: Int, launchToken: Long): Boolean {
        val appContext = context.applicationContext
        val accepted = ForegroundStartCoordinator.onForegroundReady(
            appContext,
            LAUNCH_SPEC,
            attempt,
            launchToken,
        )
        if (accepted) ForegroundServiceRearm.cancel(appContext, "foreground_ready")
        return accepted
    }

    fun onForegroundStartFailed(
        context: Context,
        attempt: Int,
        launchToken: Long,
        reason: String,
    ): Boolean =
        ForegroundStartCoordinator.onForegroundStartFailed(
            context.applicationContext,
            LAUNCH_SPEC,
            attempt,
            launchToken,
            reason
        )

    fun onServiceStopped(context: Context, launchToken: Long) {
        if (launchToken <= 0L) return
        ForegroundStartCoordinator.markStoppedIfCurrent(
            context.applicationContext,
            LAUNCH_SPEC,
            launchToken,
        )
    }

    fun isCurrentLaunchToken(context: Context, launchToken: Long): Boolean =
        ForegroundStartCoordinator.isCurrent(context.applicationContext, LAUNCH_SPEC, launchToken)

    fun stop(context: Context) {
        val appContext = context.applicationContext
        ForegroundStartCoordinator.cancelStart(appContext, LAUNCH_SPEC)
        ForegroundStartCoordinator.cancelWatchdog(appContext, LAUNCH_SPEC)
        ForegroundServiceRearm.cancel(appContext, "manager_stop")
        LocationDisabledRecoveryScheduler.cancel(appContext, "manager_stop")
        ForegroundStartCoordinator.removeQueuedStart(appContext, LAUNCH_SPEC)
        ForegroundStartCoordinator.markStopped(appContext, LAUNCH_SPEC)
        WakeEventCoordinator.clearConfirmWork(appContext)
        try {
            appContext.stopService(Intent(appContext, LocationConfirmService::class.java))
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(appContext, TAG, "Could not stop confirm service: ${e.message}", e)
        }
    }

    fun stopServiceIfIdle(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!LocationConfirmService.isRunning ||
            WakeEventCoordinator.foregroundWorkCount(appContext) > 0 ||
            WakeEventCoordinator.isForegroundWorkRunning()
        ) {
            return
        }
        try {
            appContext.stopService(Intent(appContext, LocationConfirmService::class.java))
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Stopped idle confirm foreground service reason=$reason.",
            )
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Could not stop idle confirm service reason=$reason: ${e.message}",
                e,
            )
        }
    }

    fun onConfirmWorkParked(context: Context, reason: String) {
        val appContext = context.applicationContext
        SmartGeofenceDiagnostics.recordConfirmWorkParked(appContext, reason)
        if (WakeEventCoordinator.foregroundWorkCount(appContext) == 0) {
            ForegroundStartCoordinator.cancelStart(appContext, LAUNCH_SPEC)
            ForegroundStartCoordinator.cancelWatchdog(appContext, LAUNCH_SPEC)
            ForegroundServiceRearm.cancel(appContext, "parked_$reason")
            ForegroundStartCoordinator.removeQueuedStart(appContext, LAUNCH_SPEC)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Cancelled confirm launch alarms after parking all active work reason=$reason " +
                    "parked=${WakeEventCoordinator.parkedForegroundWorkCount(appContext)}.",
            )
        }
        LocationDisabledRecoveryScheduler.reconcile(appContext, "work_parked_$reason")
        stopServiceIfIdle(appContext, "parked_$reason")
    }

    fun onLocationProvidersEnabled(context: Context): Boolean {
        return LocationDisabledRecoveryScheduler.recoverNow(
            context.applicationContext,
            attempt = 0,
            source = "provider_changed_broadcast",
        )
    }

    internal fun resumeLocationDisabledWork(context: Context, reason: String): Boolean {
        val appContext = context.applicationContext
        val unparked = WakeEventCoordinator.unparkForegroundWorkByReason(
            appContext,
            Constants.CONFIRM_PARKED_REASON_LOCATION_DISABLED,
        ) { kind ->
            nextConfirmNotBeforeMillis(appContext, kind)
        }
        if (unparked <= 0) return false
        val scheduled = scheduleNextReadyWork(
            appContext,
            reason = reason,
        )
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Unparked $unparked location-disabled confirm task(s) reason=$reason; " +
                "scheduled=$scheduled.",
        )
        return scheduled
    }

    fun cancelLivenessWork(context: Context, reason: String) {
        cancelConfirmWorkBySource(
            context.applicationContext,
            Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS,
            "fused liveness",
            reason,
        )
    }

    fun cancelPulseBoundaryWork(context: Context, reason: String) {
        cancelConfirmWorkBySource(
            context.applicationContext,
            Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
            "boundary Pulse",
            reason,
        )
    }

    fun cancelPulseBoundaryWorkIfQueued(context: Context, reason: String): Boolean =
        cancelConfirmWorkBySource(
            context.applicationContext,
            Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
            "boundary Pulse",
            reason,
            skipWhenAbsent = true,
        ) > 0

    fun cancelPendingValidationPulseIfNoPending(context: Context, reason: String) {
        val appContext = context.applicationContext
        val pendingPulseValidation = NativeTransitionCoordinator.allPending(appContext).any {
            it.validationRequired && !it.nativeCandidate
        }
        if (pendingPulseValidation) return
        cancelConfirmWorkBySource(
            appContext,
            Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
            "pending transition Pulse",
            reason,
        )
    }

    private fun cancelConfirmWorkBySource(
        context: Context,
        source: String,
        label: String,
        reason: String,
        skipWhenAbsent: Boolean = false,
    ): Int {
        val appContext = context.applicationContext
        val removed = WakeEventCoordinator.removeForegroundWorkBySource(appContext, source)
        if (skipWhenAbsent && removed == 0) return 0
        val activeWork = WakeEventCoordinator.foregroundWorkCount(appContext)
        if (activeWork == 0 && !WakeEventCoordinator.isForegroundWorkRunning()) {
            ForegroundStartCoordinator.cancelStart(appContext, LAUNCH_SPEC)
            ForegroundStartCoordinator.cancelWatchdog(appContext, LAUNCH_SPEC)
            ForegroundServiceRearm.cancel(appContext, "source_cancelled_$reason")
            ForegroundStartCoordinator.removeQueuedStart(appContext, LAUNCH_SPEC)
            ForegroundStartCoordinator.markStopped(appContext, LAUNCH_SPEC)
        } else if (removed > 0) {
            reconcileQueuedWork(appContext, "source_cancelled_$reason")
        }
        LocationDisabledRecoveryScheduler.reconcile(
            appContext,
            "source_cancelled_$reason",
        )
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Cancelled $label work reason=$reason source=$source removed=$removed " +
                "remaining=$activeWork.",
        )
        return removed
    }

    fun cancelNativeExitConfirmIfNoPending(context: Context, reason: String) {
        val appContext = context.applicationContext
        val pendingNativeExits = NativeExitPendingStore.count(appContext)
        if (pendingNativeExits > 0) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Kept native EXIT confirm work reason=$reason pendingNativeExits=$pendingNativeExits.",
            )
            return
        }
        val removed = WakeEventCoordinator.removeForegroundWorkBySourcePrefix(
            appContext,
            Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM,
        )
        val noForegroundWorkLeft =
            WakeEventCoordinator.foregroundWorkCount(appContext) == 0 &&
                !WakeEventCoordinator.isForegroundWorkRunning()
        if (noForegroundWorkLeft) {
            ForegroundStartCoordinator.cancelStart(appContext, LAUNCH_SPEC)
            ForegroundStartCoordinator.cancelWatchdog(appContext, LAUNCH_SPEC)
            ForegroundServiceRearm.cancel(appContext, "native_exit_cancelled_$reason")
            ForegroundStartCoordinator.removeQueuedStart(appContext, LAUNCH_SPEC)
            ForegroundStartCoordinator.markStopped(appContext, LAUNCH_SPEC)
            stopServiceIfIdle(appContext, "native_exit_cancelled_$reason")
        }
        LocationDisabledRecoveryScheduler.reconcile(
            appContext,
            "native_exit_cancelled_$reason",
        )
        if (removed <= 0) return
        if (!noForegroundWorkLeft) {
            stopServiceIfIdle(appContext, "native_exit_cancelled_$reason")
        }
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Cancelled native EXIT confirm work reason=$reason removed=$removed.",
        )
    }

    fun cancelNativeEnterConfirmIfNoPending(context: Context, reason: String) {
        val appContext = context.applicationContext
        val pendingNativeEnters = NativeEnterPendingStore.count(appContext)
        if (pendingNativeEnters > 0) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Kept native ENTER confirm work reason=$reason pendingNativeEnters=$pendingNativeEnters.",
            )
            return
        }
        val removed = WakeEventCoordinator.removeForegroundWorkBySourcePrefix(
            appContext,
            Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM,
        )
        val noForegroundWorkLeft =
            WakeEventCoordinator.foregroundWorkCount(appContext) == 0 &&
                !WakeEventCoordinator.isForegroundWorkRunning()
        if (noForegroundWorkLeft) {
            ForegroundStartCoordinator.cancelStart(appContext, LAUNCH_SPEC)
            ForegroundStartCoordinator.cancelWatchdog(appContext, LAUNCH_SPEC)
            ForegroundServiceRearm.cancel(appContext, "native_enter_cancelled_$reason")
            ForegroundStartCoordinator.removeQueuedStart(appContext, LAUNCH_SPEC)
            ForegroundStartCoordinator.markStopped(appContext, LAUNCH_SPEC)
            stopServiceIfIdle(appContext, "native_enter_cancelled_$reason")
        }
        LocationDisabledRecoveryScheduler.reconcile(
            appContext,
            "native_enter_cancelled_$reason",
        )
        if (removed <= 0) return
        if (!noForegroundWorkLeft) {
            stopServiceIfIdle(appContext, "native_enter_cancelled_$reason")
        }
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Cancelled native ENTER confirm work reason=$reason removed=$removed.",
        )
    }

    fun pendingIntentExists(context: Context): Boolean =
        ForegroundStartCoordinator.startPendingIntentExists(
            context.applicationContext,
            LAUNCH_SPEC
        ) || ForegroundStartCoordinator.batchStartPendingIntentExists(context.applicationContext)

    fun watchdogPendingIntentExists(context: Context): Boolean =
        ForegroundStartCoordinator.watchdogPendingIntentExists(context.applicationContext, LAUNCH_SPEC)

    fun ensureLaunchToken(context: Context, reason: String): Long =
        ForegroundStartCoordinator.ensureLaunchToken(
            context.applicationContext,
            LAUNCH_SPEC,
            reason
        )

    fun scheduleNextReadyWork(
        context: Context,
        reason: String,
        launchToken: Long = 0L,
    ): Boolean {
        val appContext = context.applicationContext
        val nextReadyAt = WakeEventCoordinator.nextForegroundReadyAtMillis(appContext)
            ?: return false
        val now = System.currentTimeMillis()
        if (nextReadyAt <= now && LocationConfirmService.isForegroundReady) {
            return WakeEventCoordinator.drainForegroundWork(appContext)
        }
        val desiredTriggerAt = nextReadyAt.coerceAtLeast(now)
        val startPending = ForegroundStartCoordinator.startPendingIntentExists(
            appContext,
            LAUNCH_SPEC,
        )
        val existingTriggerAt = if (startPending) {
            (AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                ForegroundStartCoordinator.startScheduleKey(LAUNCH_SPEC),
            )["triggerAtMillis"] as? Number)?.toLong()
        } else {
            null
        }
        val shouldSchedule = shouldScheduleQueueStartAlarm(
            startPending,
            existingTriggerAt,
            desiredTriggerAt,
            now,
        )
        if (!shouldSchedule) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Kept existing confirm start alarm triggerAt=$existingTriggerAt " +
                    "desiredTriggerAt=$desiredTriggerAt reason=$reason.",
            )
            return true
        }
        val token = if (launchToken > 0L) {
            launchToken
        } else {
            ForegroundStartCoordinator.ensureLaunchToken(appContext, LAUNCH_SPEC, reason)
        }
        return scheduleStartAlarm(
            appContext,
            delayMillis = queuedConfirmStartDelayMillis(nextReadyAt, now),
            attempt = 0,
            launchToken = token,
            reason = reason,
            stage = "work_ready",
        )
    }

    fun reconcileQueuedWork(context: Context, reason: String): Boolean {
        val appContext = context.applicationContext
        LocationDisabledRecoveryScheduler.reconcile(
            appContext,
            "queue_reconcile_$reason",
        )
        val activeWork = WakeEventCoordinator.foregroundWorkCount(appContext)
        if (activeWork == 0) {
            if (!WakeEventCoordinator.isForegroundWorkRunning()) {
                ForegroundStartCoordinator.cancelStart(appContext, LAUNCH_SPEC)
                ForegroundStartCoordinator.cancelWatchdog(appContext, LAUNCH_SPEC)
                ForegroundServiceRearm.cancel(appContext, "queue_reconcile_empty_$reason")
                ForegroundStartCoordinator.removeQueuedStart(appContext, LAUNCH_SPEC)
                ForegroundStartCoordinator.markStopped(appContext, LAUNCH_SPEC)
            }
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Confirm queue reconciliation found no active work reason=$reason " +
                    "parked=${WakeEventCoordinator.parkedForegroundWorkCount(appContext)}.",
            )
            stopServiceIfIdle(appContext, "queue_reconcile_empty_$reason")
            return false
        }
        if (WakeEventCoordinator.isForegroundWorkRunning()) return true
        val scheduled = scheduleNextReadyWork(appContext, "queue_reconcile_$reason")
        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "confirm_queue_reconcile",
            reasonCode = if (scheduled) "start_bridge_armed" else "start_bridge_unavailable",
            source = reason,
            extras = mapOf(
                "activeWork" to activeWork,
                "nextReadyAtMillis" to WakeEventCoordinator.nextForegroundReadyAtMillis(appContext),
            ),
        )
        return scheduled
    }

    private fun scheduleNextReadyAlarmDetailed(
        context: Context,
        reason: String,
        launchToken: Long = 0L,
    ): ForegroundStartAlarmResult? {
        val appContext = context.applicationContext
        val nextReadyAt = WakeEventCoordinator.nextForegroundReadyAtMillis(appContext)
            ?: return null
        val now = System.currentTimeMillis()
        val desiredTriggerAt = nextReadyAt.coerceAtLeast(now)
        val startPending = ForegroundStartCoordinator.startPendingIntentExists(
            appContext,
            LAUNCH_SPEC,
        )
        val existingStatus = if (startPending) {
            AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                ForegroundStartCoordinator.startScheduleKey(LAUNCH_SPEC),
            )
        } else {
            emptyMap()
        }
        val existingTriggerAt = (existingStatus["triggerAtMillis"] as? Number)?.toLong()
        val shouldSchedule = shouldScheduleQueueStartAlarm(
            startPending,
            existingTriggerAt,
            desiredTriggerAt,
            now,
        )
        if (!shouldSchedule) {
            return ForegroundStartAlarmResult(
                scheduled = true,
                exactAllowed = existingStatus["exactAllowed"] as? Boolean ?: true,
                triggerAtMillis = existingTriggerAt,
                eventSuffix = "existing_earlier",
                failureReason = null,
            )
        }
        val token = if (launchToken > 0L) {
            launchToken
        } else {
            ForegroundStartCoordinator.ensureLaunchToken(appContext, LAUNCH_SPEC, reason)
        }
        return scheduleStartAlarmDetailed(
            appContext,
            delayMillis = queuedConfirmStartDelayMillis(nextReadyAt, now),
            attempt = 0,
            launchToken = token,
            reason = reason,
            stage = "work_ready",
        )
    }

    internal fun confirmStartDelayMillis(
        config: SmartGeofenceConfig,
        kind: ForegroundWorkKind,
        source: String? = null,
    ): Long =
        when (kind) {
            ForegroundWorkKind.CONFIRM_PROXIMITY -> config.foregroundServiceStartDelayMillis
            ForegroundWorkKind.CONFIRM_OUTSIDE -> {
                val sharedDelay = if (config.transitionValidationEnabled &&
                    config.transitionValidationExitEnabled
                ) {
                    config.transitionValidationMinimumDelayMillis
                } else {
                    0L
                }
                if (source?.startsWith(
                        Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM,
                    ) == true
                ) {
                    maxOf(config.nativeConfirmDelayMillis, sharedDelay)
                } else if (sharedDelay > 0L) {
                    sharedDelay
                } else {
                    config.nativeConfirmDelayMillis
                }
            }
            ForegroundWorkKind.CONFIRM_INSIDE -> {
                val sharedDelay = if (config.transitionValidationEnabled &&
                    config.transitionValidationEnterEnabled
                ) {
                    config.transitionValidationMinimumDelayMillis
                } else {
                    0L
                }
                if (source?.startsWith(
                        Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM,
                    ) == true
                ) {
                    maxOf(config.nativeConfirmDelayMillis, sharedDelay)
                } else if (sharedDelay > 0L) {
                    sharedDelay
                } else {
                    config.nativeConfirmDelayMillis
                }
            }
        }.coerceAtLeast(0L)

    private fun nextConfirmNotBeforeMillis(
        context: Context,
        kind: ForegroundWorkKind,
        source: String? = null,
    ): Long {
        val delayMillis = confirmStartDelayMillis(
            SmartGeofenceConfigStore.load(context.applicationContext),
            kind,
            source,
        )
        val now = System.currentTimeMillis()
        return if (delayMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMillis
    }

    private fun scheduleStartAlarm(
        context: Context,
        delayMillis: Long,
        attempt: Int,
        launchToken: Long,
        reason: String,
        stage: String = "start_alarm",
    ): Boolean =
        scheduleStartAlarmDetailed(
            context,
            delayMillis,
            attempt,
            launchToken,
            reason,
            stage,
        ).scheduled

    private fun scheduleStartAlarmDetailed(
        context: Context,
        delayMillis: Long,
        attempt: Int,
        launchToken: Long,
        reason: String,
        stage: String = "start_alarm",
    ): ForegroundStartAlarmResult {
        val appContext = context.applicationContext
        if (!hasReceiver(appContext, LocationConfirmAlarmReceiver::class.java)) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Confirm start alarm skipped; LocationConfirmAlarmReceiver is not declared."
            )
            return ForegroundStartAlarmResult(
                scheduled = false,
                exactAllowed = false,
                triggerAtMillis = null,
                eventSuffix = "failed",
                failureReason = "receiver_not_declared",
            )
        }
        return ForegroundStartCoordinator.scheduleStartAlarmDetailed(
            appContext,
            LAUNCH_SPEC,
            delayMillis,
            attempt,
            launchToken,
            reason,
            stage
        )
    }

    private fun scheduleWatchdog(context: Context, launchToken: Long, attempt: Int): Boolean {
        val timeoutMs = SmartGeofenceConfigStore.load(context.applicationContext)
            .foregroundServiceLaunchTimeoutMillis
            .coerceAtLeast(1_000L)
        return ForegroundStartCoordinator.scheduleWatchdog(
            context.applicationContext,
            LAUNCH_SPEC,
            launchToken,
            attempt,
            timeoutMs
        )
    }

    private fun canStartForegroundLocationService(context: Context): Boolean {
        if (!FusedLocationPermissions.hasLocationPermission(context)) {
            SmartGeofenceLogger.w(context, TAG, "Location permission is not granted.")
            return false
        }
        if (!FusedLocationPermissions.hasBackgroundLocationPermission(context)) {
            SmartGeofenceLogger.w(context, TAG, "Background location permission is not granted.")
            return false
        }
        if (!granted(context, Manifest.permission.FOREGROUND_SERVICE)) {
            SmartGeofenceLogger.w(context, TAG, "FOREGROUND_SERVICE is not declared.")
            return false
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !granted(context, "android.permission.FOREGROUND_SERVICE_LOCATION")
        ) {
            SmartGeofenceLogger.w(context, TAG, "FOREGROUND_SERVICE_LOCATION is not declared.")
            return false
        }
        val serviceInfo = serviceInfo(context, LocationConfirmService::class.java)
        if (serviceInfo == null) {
            SmartGeofenceLogger.w(context, TAG, "LocationConfirmService is not declared.")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) == 0
        ) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "LocationConfirmService is missing foregroundServiceType=\"location\"."
            )
            return false
        }
        return true
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasReceiver(context: Context, receiverClass: Class<*>): Boolean {
        return try {
            val component = ComponentName(context, receiverClass)
            AndroidPackageManagerCompat.getReceiverInfo(
                context.packageManager,
                component,
            )
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun serviceInfo(context: Context, serviceClass: Class<*>): ServiceInfo? {
        return try {
            val component = ComponentName(context, serviceClass)
            AndroidPackageManagerCompat.getServiceInfo(
                context.packageManager,
                component,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
