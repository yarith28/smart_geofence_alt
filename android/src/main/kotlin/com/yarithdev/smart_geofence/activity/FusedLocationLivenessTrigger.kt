package com.yarithdev.smart_geofence.activity

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yarithdev.smart_geofence.confirm.LocationQualityPolicy
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.fused.FusedCurrentLocationStatus
import com.yarithdev.smart_geofence.fused.FusedLocationLiveness
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.mock.MockLocationPolicyGate
import com.yarithdev.smart_geofence.proximity.ProximityAlarmScheduler
import com.yarithdev.smart_geofence.proximity.ProximityLocationEvaluator
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.store.FenceStore
import java.util.concurrent.atomic.AtomicBoolean

internal data class FreshLivenessRearmResult(
    val delayMillis: Long,
    val scheduled: Boolean,
)

internal data class MovementLivenessScheduleResult(
    val delayMillis: Long,
    val scheduled: Boolean,
)

internal fun scheduleMovementLiveness(
    pulseActive: Boolean,
    lastHealthyAtMillis: Long?,
    nowMillis: Long,
    staleAfterMillis: Long,
    movingDelayMillis: Long,
    onPulseAlreadyActive: () -> Unit,
    schedule: (Long) -> Boolean,
): MovementLivenessScheduleResult {
    if (pulseActive) onPulseAlreadyActive()
    val delayMillis = FusedLocationLivenessTrigger.nextCheckDelayMillis(
        lastHealthyAtMillis = lastHealthyAtMillis,
        nowMillis = nowMillis,
        staleAfterMillis = staleAfterMillis,
        movingDelayMillis = movingDelayMillis,
    )
    return MovementLivenessScheduleResult(
        delayMillis = delayMillis,
        scheduled = schedule(delayMillis),
    )
}

internal fun fusedLivenessIneligibilityReason(
    escalationEnabled: Boolean,
    pulseEnabled: Boolean,
    activityIneligibilityReason: String?,
    hasFences: Boolean,
    pulseCanRun: Boolean,
): String? = when {
    !escalationEnabled -> "escalation_disabled"
    !pulseEnabled -> "pulse_disabled"
    activityIneligibilityReason != null -> activityIneligibilityReason
    !hasFences -> "no_fences"
    !pulseCanRun -> "pulse_runtime_ineligible"
    else -> null
}

object FusedLocationLivenessTrigger {
    private const val TAG = "FusedLocationLivenessTrigger"
    private val requestRunning = AtomicBoolean(false)

    fun schedule(context: Context, event: String): Boolean {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        if (!canSchedule(appContext, config)) {
            record(appContext, "liveness_schedule", "ineligible", event)
            return false
        }
        val pulseActive = ProximityPulseController.isSchedulingActive(appContext)
        val nowMillis = System.currentTimeMillis()
        val lastHealthyAtMillis = FusedLocationLiveness.lastHealthyAtMillis(appContext)
        val healthyAge = lastHealthyAtMillis
            ?.let { (nowMillis - it).coerceAtLeast(0L) }
            ?.let { "${it}ms" }
            ?: "unknown"
        val scheduling = scheduleMovementLiveness(
            pulseActive = pulseActive,
            lastHealthyAtMillis = lastHealthyAtMillis,
            nowMillis = nowMillis,
            staleAfterMillis = config.activityFusedLocationStaleAfterMillis,
            movingDelayMillis = movingCheckDelayMillis(config),
            onPulseAlreadyActive = {
                record(appContext, "liveness_schedule", "pulse_already_active", event)
            },
            schedule = { delayMillis ->
                ProximityAlarmScheduler.scheduleLivenessTrigger(
                    appContext,
                    delayMillis,
                    event,
                )
            },
        )
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Scheduling fused liveness check after movement delay=${scheduling.delayMillis}ms " +
                "healthyAge=$healthyAge " +
                "event=$event.",
        )
        record(
            appContext,
            "liveness_schedule",
            if (scheduling.scheduled) "scheduled" else "schedule_failed",
            event,
            extras = mapOf("delayMillis" to scheduling.delayMillis),
        )
        return scheduling.scheduled
    }

    fun run(context: Context, onComplete: () -> Unit): Boolean {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        if (!canExecute(appContext, config)) {
            record(appContext, "liveness_run", "ineligible", null)
            return false
        }
        if (!FusedLocationLiveness.isStale(appContext, config)) {
            val rearm = rearmFreshLivenessCheck(
                lastHealthyAtMillis = FusedLocationLiveness.lastHealthyAtMillis(appContext),
                nowMillis = System.currentTimeMillis(),
                staleAfterMillis = config.activityFusedLocationStaleAfterMillis,
            ) { delayMillis ->
                ProximityAlarmScheduler.scheduleLivenessTrigger(
                    appContext,
                    delayMillis,
                    "health_fresh_recheck",
                )
            }
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Fused health is still fresh after the shared alarm fired; " +
                    "future liveness rearm delay=${rearm.delayMillis}ms " +
                    "scheduled=${rearm.scheduled}.",
            )
            record(
                appContext,
                "liveness_run",
                if (rearm.scheduled) "healthy_rearmed" else "healthy_rearm_failed",
                null,
                extras = mapOf("delayMillis" to rearm.delayMillis),
            )
            return false
        }
        if (ActivityMonitor.isLikelyStationary(appContext)) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Running scheduled fused liveness check despite a newer stationary signal.",
            )
        }
        if (!requestRunning.compareAndSet(false, true)) {
            SmartGeofenceLogger.d(appContext, TAG, "Cached liveness check already running.")
            record(appContext, "liveness_run", "already_running", null)
            return false
        }

        record(appContext, "liveness_run", "cached_fix_requested", null)
        SmartGeofenceLogger.d(appContext, TAG, "Requesting cached fused fix for liveness.")
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        lateinit var timeout: Runnable
        fun complete(action: () -> Unit) {
            if (!completed.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            try {
                val ineligibleReason = executionIneligibilityReason(appContext)
                if (ineligibleReason == null) {
                    action()
                } else {
                    record(
                        appContext,
                        "liveness_result",
                        "ignored_after_controller_change",
                        null,
                        extras = mapOf("ineligibilityReason" to ineligibleReason),
                    )
                    SmartGeofenceLogger.d(
                        appContext,
                        TAG,
                        "Ignoring late liveness result reason=$ineligibleReason.",
                    )
                }
            } finally {
                requestRunning.set(false)
                onComplete()
            }
        }
        timeout = Runnable {
            complete {
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "Cached fused liveness check timed out.",
                )
                startLivenessRecovery(appContext, "cached_fix_timeout")
                record(appContext, "liveness_result", "cached_fix_timeout", null)
            }
        }
        handler.postDelayed(timeout, config.locationConfirmTimeoutMillis.coerceAtLeast(1L))

        FusedLocationManager.requestLastLocation(
            appContext,
            config.locationConfirmTimeoutMillis,
        ) { result ->
            complete {
                try {
                    val location = result.location
                    if (result.status == FusedCurrentLocationStatus.SUCCESS && location != null) {
                        val rejection = LocationQualityPolicy.rejectionReason(
                            location,
                            config.activityFusedLocationStaleAfterMillis,
                            config.pulseLocationMaxAccuracyMeters,
                        )
                        if (rejection == null) {
                            if (MockLocationPolicyGate.evaluateLocation(
                                    appContext,
                                    config,
                                    location,
                                    Constants.LOCATION_WAKE_SOURCE_ACTIVITY,
                                    where = "activity_liveness",
                                ).rejected
                            ) {
                                startLivenessRecovery(appContext, "cached_fix_rejected:mock_location")
                                return@complete
                            }
                            FusedLocationLiveness.recordHealthyFix(
                                appContext,
                                location,
                                Constants.LOCATION_WAKE_SOURCE_ACTIVITY,
                            )
                            ProximityPulseController.onHealthyFusedFix(appContext)
                            ProximityLocationEvaluator.evaluate(
                                appContext,
                                location,
                                Constants.LOCATION_WAKE_SOURCE_ACTIVITY,
                            )
                            record(
                                appContext,
                                "liveness_result",
                                "healthy_fix",
                                null,
                                extras = linkedMapOf(
                                    "provider" to location.provider,
                                    "accuracyMeters" to
                                        location.accuracy.takeIf { location.hasAccuracy() },
                                    "ageMillis" to location.time.takeIf { it > 0L }?.let {
                                        (System.currentTimeMillis() - it).coerceAtLeast(0L)
                                    },
                                ),
                            )
                        } else {
                            startLivenessRecovery(appContext, "cached_fix_rejected:$rejection")
                            record(
                                appContext,
                                "liveness_result",
                                "cached_fix_rejected",
                                null,
                                extras = mapOf("rejection" to rejection),
                            )
                        }
                    } else {
                        SmartGeofenceLogger.d(
                            appContext,
                            TAG,
                            "Cached fused fix unavailable status=${result.status} " +
                                "elapsed=${result.elapsedMillis}ms failure=${result.failure?.message}.",
                        )
                        startLivenessRecovery(
                            appContext,
                            "cached_fix_${result.status.name.lowercase()}",
                        )
                        record(
                            appContext,
                            "liveness_result",
                            "cached_fix_${result.status.name.lowercase()}",
                            null,
                            extras = linkedMapOf(
                                "elapsedMillis" to result.elapsedMillis,
                                "errorType" to result.failure?.javaClass?.name,
                            ),
                        )
                    }
                } catch (e: Throwable) {
                    SmartGeofenceLogger.w(
                        appContext,
                        TAG,
                        "Fused liveness evaluation failed: ${e.message}",
                        e,
                    )
                    startLivenessRecovery(appContext, "cached_fix_processing_failure")
                    record(
                        appContext,
                        "liveness_result",
                        "processing_failure",
                        null,
                        extras = mapOf("errorType" to e.javaClass.name),
                    )
                }
            }
        }
        return true
    }

    internal fun movingCheckDelayMillis(config: SmartGeofenceConfig): Long =
        config.activityMovingProximityCheckDelayMillis.coerceAtLeast(0L)

    internal fun nextCheckDelayMillis(
        lastHealthyAtMillis: Long?,
        nowMillis: Long,
        staleAfterMillis: Long,
        movingDelayMillis: Long,
    ): Long {
        val normalizedMovingDelay = movingDelayMillis.coerceAtLeast(0L)
        val normalizedStaleAfter = staleAfterMillis.coerceAtLeast(1L)
        if (lastHealthyAtMillis == null || nowMillis < lastHealthyAtMillis) {
            return normalizedMovingDelay
        }
        val healthyAge = (nowMillis - lastHealthyAtMillis).coerceAtLeast(0L)
        val untilStale = (normalizedStaleAfter - healthyAge).coerceAtLeast(0L)
        return maxOf(normalizedMovingDelay, untilStale)
    }

    internal fun rearmFreshLivenessCheck(
        lastHealthyAtMillis: Long?,
        nowMillis: Long,
        staleAfterMillis: Long,
        schedule: (Long) -> Boolean,
    ): FreshLivenessRearmResult {
        val normalizedStaleAfter = staleAfterMillis.coerceAtLeast(1L)
        val healthyAge = lastHealthyAtMillis
            ?.takeIf { nowMillis >= it }
            ?.let { (nowMillis - it).coerceAtLeast(0L) }
            ?: normalizedStaleAfter
        val delayMillis = (normalizedStaleAfter - healthyAge).coerceAtLeast(1L)
        return FreshLivenessRearmResult(
            delayMillis = delayMillis,
            scheduled = schedule(delayMillis),
        )
    }

    private fun startLivenessRecovery(context: Context, reason: String) {
        val ineligibleReason = executionIneligibilityReason(context)
        if (ineligibleReason != null) {
            record(
                context,
                "liveness_recovery",
                "ignored_ineligible",
                reason,
                extras = mapOf("ineligibilityReason" to ineligibleReason),
            )
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Fused liveness recovery ignored reason=$reason ineligible=$ineligibleReason.",
            )
            return
        }
        val refreshSubmitted = FusedLocationManager.refreshBalancedUpdate(context)
        if (refreshSubmitted) {
            FusedLocationLiveness.recordBalancedRefresh(context)
        } else {
            FusedLocationManager.startBalancedUpdate(context)
        }
        val started = ProximityPulseController.startLiveness(context, reason)
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Fused liveness recovery requested reason=$reason pulseStarted=$started.",
        )
        record(
            context,
            "liveness_recovery",
            if (started) "started" else "not_started",
            reason,
        )
    }

    private fun record(
        context: Context,
        stage: String,
        reason: String,
        source: String?,
        extras: Map<String, Any?> = emptyMap(),
    ) {
        SmartGeofenceDiagnostics.recordTrace(
            context,
            stage = stage,
            reasonCode = reason,
            source = source,
            extras = extras,
        )
    }

    private fun executionIneligibilityReason(
        context: Context,
        config: SmartGeofenceConfig = SmartGeofenceConfigStore.load(context),
    ): String? = fusedLivenessIneligibilityReason(
        escalationEnabled = config.escalationEnabled,
        pulseEnabled = config.proximityPulseEnabled,
        activityIneligibilityReason = ActivityMonitor.callbackIneligibilityReason(context),
        hasFences = FenceStore.getAll(context).isNotEmpty(),
        pulseCanRun = ProximityPulseController.canRun(context),
    )

    private fun canSchedule(
        context: Context,
        config: SmartGeofenceConfig = SmartGeofenceConfigStore.load(context),
    ): Boolean {
        return config.escalationEnabled &&
            config.proximityPulseEnabled &&
            ActivityMonitor.callbackIneligibilityReason(context) == null &&
            !ActivityMonitor.isLikelyStationary(context) &&
            FenceStore.getAll(context).isNotEmpty() &&
            ProximityPulseController.canRun(context)
    }

    private fun canExecute(
        context: Context,
        config: SmartGeofenceConfig = SmartGeofenceConfigStore.load(context),
    ): Boolean {
        return executionIneligibilityReason(context, config) == null
    }
}
