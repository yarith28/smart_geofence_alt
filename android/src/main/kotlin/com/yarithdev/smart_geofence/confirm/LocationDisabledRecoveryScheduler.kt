package com.yarithdev.smart_geofence.confirm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.wake.ParkedForegroundWorkSummary
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator

internal data class LocationDisabledRecoverySnapshot(
    val parkedCount: Int,
    val deadlineAtMillis: Long,
    val releaseAtDeadline: Boolean,
)

internal enum class LocationDisabledRecoveryDisposition {
    NO_WORK,
    RESUMED,
    EXPIRED_RELEASED,
    REARMED,
    POLLING_WINDOW_EXHAUSTED,
    SCHEDULE_FAILED,
}

internal data class LocationDisabledRecoveryResult(
    val disposition: LocationDisabledRecoveryDisposition,
    val scheduled: Boolean = false,
)

internal class LocationDisabledRecoveryCoordinator(
    private val snapshot: () -> LocationDisabledRecoverySnapshot,
    private val locationEnabled: () -> Boolean,
    private val resume: (String) -> Boolean,
    private val rearm: (Int) -> Boolean,
    private val cancel: () -> Unit,
) {
    fun recover(nowMillis: Long, attempt: Int, source: String): LocationDisabledRecoveryResult {
        val current = snapshot()
        if (current.parkedCount <= 0) {
            cancel()
            return LocationDisabledRecoveryResult(LocationDisabledRecoveryDisposition.NO_WORK)
        }
        if (locationEnabled()) {
            cancel()
            return LocationDisabledRecoveryResult(
                disposition = LocationDisabledRecoveryDisposition.RESUMED,
                scheduled = resume(source),
            )
        }
        if (nowMillis >= current.deadlineAtMillis) {
            cancel()
            if (!current.releaseAtDeadline) {
                return LocationDisabledRecoveryResult(
                    LocationDisabledRecoveryDisposition.POLLING_WINDOW_EXHAUSTED,
                )
            }
            return LocationDisabledRecoveryResult(
                disposition = LocationDisabledRecoveryDisposition.EXPIRED_RELEASED,
                scheduled = resume("${source}_queue_expired"),
            )
        }
        val scheduled = rearm((attempt + 1).coerceAtLeast(1))
        return LocationDisabledRecoveryResult(
            disposition = if (scheduled) {
                LocationDisabledRecoveryDisposition.REARMED
            } else {
                LocationDisabledRecoveryDisposition.SCHEDULE_FAILED
            },
            scheduled = scheduled,
        )
    }
}

internal fun locationDisabledRecoveryDelayMillis(attempt: Int): Long {
    val shift = attempt.coerceIn(0, 20)
    val multiplier = 1L shl shift
    return (LocationDisabledRecoveryScheduler.INITIAL_DELAY_MILLIS * multiplier)
        .coerceAtMost(LocationDisabledRecoveryScheduler.MAX_DELAY_MILLIS)
}

internal fun shouldScheduleLocationDisabledRecovery(
    existingPendingIntent: Boolean,
    existingTriggerAtMillis: Long?,
    desiredTriggerAtMillis: Long,
    nowMillis: Long,
): Boolean = !existingPendingIntent ||
    existingTriggerAtMillis == null ||
    existingTriggerAtMillis <= nowMillis ||
    existingTriggerAtMillis > desiredTriggerAtMillis

object LocationDisabledRecoveryScheduler {
    internal const val SCHEDULE_KEY = "location_disabled_confirm_recovery"
    internal const val INITIAL_DELAY_MILLIS = 60_000L
    internal const val MAX_DELAY_MILLIS = 5 * 60_000L
    internal const val MAX_POLLING_WINDOW_MILLIS = 30 * 60_000L

    private const val TAG = "LocationDisabledRecovery"

    @Synchronized
    fun reconcile(context: Context, reason: String, attempt: Int = 0): Boolean {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val snapshot = recoverySnapshot(appContext, now)
        if (snapshot.parkedCount <= 0) {
            cancel(appContext, "no_parked_work_$reason")
            return false
        }
        if (now >= snapshot.deadlineAtMillis && !snapshot.releaseAtDeadline) {
            cancel(appContext, "polling_window_exhausted_$reason")
            return false
        }
        val desiredTriggerAt = minOf(
            safeAdd(now, locationDisabledRecoveryDelayMillis(attempt)),
            snapshot.deadlineAtMillis,
        )
        val existing = existingPendingIntent(appContext)
        val existingTriggerAt = if (existing != null) {
            (AlarmPolicyScheduler.diagnosticStatus(appContext, SCHEDULE_KEY)["triggerAtMillis"]
                as? Number)?.toLong()
        } else {
            null
        }
        if (!shouldScheduleLocationDisabledRecovery(
                existingPendingIntent = existing != null,
                existingTriggerAtMillis = existingTriggerAt,
                desiredTriggerAtMillis = desiredTriggerAt,
                nowMillis = now,
            )
        ) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Kept earlier location-disabled recovery alarm triggerAt=$existingTriggerAt " +
                    "desired=$desiredTriggerAt reason=$reason.",
            )
            return true
        }
        val pending = pendingIntent(
            appContext,
            PendingIntent.FLAG_UPDATE_CURRENT,
            attempt = attempt,
        ) ?: return false
        val result = AlarmPolicyScheduler.schedule(
            appContext,
            AlarmScheduleRequest(
                alarmType = AlarmManager.RTC_WAKEUP,
                triggerAtMillis = desiredTriggerAt,
                primary = pending,
                policy = AlarmSchedulePolicy.ExactWithInexactFallback,
                scheduleKey = SCHEDULE_KEY,
                logTag = TAG,
                logEventPrefix = "location_disabled_recovery",
                detail = "attempt=$attempt parked=${snapshot.parkedCount} reason=$reason",
            ),
        )
        if (!result.scheduled) pending.cancel()
        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "location_disabled_recovery",
            reasonCode = if (result.scheduled) "armed" else "schedule_failed",
            source = reason,
            extras = linkedMapOf(
                "attempt" to attempt,
                "parkedCount" to snapshot.parkedCount,
                "triggerAtMillis" to desiredTriggerAt,
                "deadlineAtMillis" to snapshot.deadlineAtMillis,
                "releaseAtDeadline" to snapshot.releaseAtDeadline,
            ),
        )
        return result.scheduled
    }

    fun recoverNow(context: Context, attempt: Int, source: String): Boolean {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val result = LocationDisabledRecoveryCoordinator(
            snapshot = { recoverySnapshot(appContext, now) },
            locationEnabled = {
                LocationServicesState.isLocationEnabled(appContext) == true
            },
            resume = { reason ->
                LocationConfirmManager.resumeLocationDisabledWork(appContext, reason)
            },
            rearm = { nextAttempt ->
                reconcile(appContext, "${source}_still_disabled", nextAttempt)
            },
            cancel = { cancel(appContext, source) },
        ).recover(now, attempt, source)
        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "location_disabled_recovery",
            reasonCode = result.disposition.name.lowercase(),
            source = source,
            extras = mapOf("attempt" to attempt, "scheduled" to result.scheduled),
        )
        return result.scheduled ||
            result.disposition == LocationDisabledRecoveryDisposition.NO_WORK ||
            result.disposition == LocationDisabledRecoveryDisposition.POLLING_WINDOW_EXHAUSTED
    }

    fun handleAlarm(context: Context, attempt: Int) {
        val appContext = context.applicationContext
        cancel(appContext, "alarm_fired")
        recoverNow(appContext, attempt, "durable_alarm")
    }

    @Synchronized
    fun cancel(context: Context, reason: String) {
        val appContext = context.applicationContext
        existingPendingIntent(appContext)?.let { pending ->
            AlarmPolicyScheduler.cancel(appContext, SCHEDULE_KEY, pending)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Cancelled location-disabled recovery alarm reason=$reason.",
            )
        }
    }

    fun pendingIntentExists(context: Context): Boolean =
        existingPendingIntent(context.applicationContext) != null

    private fun recoverySnapshot(
        context: Context,
        nowMillis: Long,
    ): LocationDisabledRecoverySnapshot {
        val summary = WakeEventCoordinator.parkedForegroundWorkSummary(
            context,
            Constants.CONFIRM_PARKED_REASON_LOCATION_DISABLED,
        )
        return locationDisabledRecoverySnapshot(
            summary = summary,
            configuredMaxAgeMillis = SmartGeofenceConfigStore.load(context)
                .confirmQueueMaxAgeMillis,
            nowMillis = nowMillis,
        )
    }

    private fun existingPendingIntent(context: Context): PendingIntent? =
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE, attempt = null)

    private fun pendingIntent(
        context: Context,
        baseFlags: Int,
        attempt: Int?,
    ): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return runCatching {
            val intent = Intent(context, LocationConfirmAlarmReceiver::class.java).apply {
                putExtra(
                    LocationConfirmManager.EXTRA_ALARM_PURPOSE,
                    LocationConfirmManager.PURPOSE_LOCATION_DISABLED_RECOVERY,
                )
                if (attempt != null) {
                    putExtra(LocationConfirmManager.EXTRA_START_ATTEMPT, attempt)
                }
            }
            PendingIntent.getBroadcast(
                context,
                Constants.PENDING_INTENT_REQUEST_LOCATION_DISABLED_RECOVERY,
                intent,
                flags,
            )
        }.getOrNull()
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}

internal fun locationDisabledRecoverySnapshot(
    summary: ParkedForegroundWorkSummary,
    configuredMaxAgeMillis: Long,
    nowMillis: Long,
): LocationDisabledRecoverySnapshot {
    if (summary.count <= 0) {
        return LocationDisabledRecoverySnapshot(0, nowMillis, releaseAtDeadline = false)
    }
    val configuredExpiryEnabled = configuredMaxAgeMillis > 0L
    val recoveryWindow = when {
        configuredExpiryEnabled -> minOf(
            configuredMaxAgeMillis,
            LocationDisabledRecoveryScheduler.MAX_POLLING_WINDOW_MILLIS,
        )
        else -> LocationDisabledRecoveryScheduler.MAX_POLLING_WINDOW_MILLIS
    }
    val sessionStartedAt = summary.earliestSessionStartedAtMillis
        ?.takeIf { it > 0L }
        ?: nowMillis
    val deadlineAt = if (recoveryWindow > Long.MAX_VALUE - sessionStartedAt) {
        Long.MAX_VALUE
    } else {
        sessionStartedAt + recoveryWindow
    }
    return LocationDisabledRecoverySnapshot(
        parkedCount = summary.count,
        deadlineAtMillis = deadlineAt,
        releaseAtDeadline = configuredExpiryEnabled &&
            configuredMaxAgeMillis <= LocationDisabledRecoveryScheduler.MAX_POLLING_WINDOW_MILLIS,
    )
}
