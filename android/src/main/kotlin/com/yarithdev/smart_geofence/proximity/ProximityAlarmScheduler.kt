package com.yarithdev.smart_geofence.proximity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

internal enum class ProximityAlarmKind {
    PULSE,
    LIVENESS,
}

internal data class ProximityAlarmOwnershipState(
    val actualTriggerAtMillis: Long? = null,
    val actualKind: ProximityAlarmKind? = null,
    val pendingLivenessTriggerAtMillis: Long? = null,
)

internal object ProximityAlarmOwnershipReducer {
    fun scheduleRequested(
        state: ProximityAlarmOwnershipState,
        kind: ProximityAlarmKind,
        triggerAtMillis: Long,
    ): ProximityAlarmOwnershipState {
        val pendingLiveness = if (kind == ProximityAlarmKind.LIVENESS) {
            listOfNotNull(state.pendingLivenessTriggerAtMillis, triggerAtMillis).minOrNull()
        } else {
            state.pendingLivenessTriggerAtMillis
        }
        return if (state.actualTriggerAtMillis != null &&
            state.actualTriggerAtMillis <= triggerAtMillis
        ) {
            state.copy(pendingLivenessTriggerAtMillis = pendingLiveness)
        } else {
            state.copy(
                actualTriggerAtMillis = triggerAtMillis,
                actualKind = kind,
                pendingLivenessTriggerAtMillis = pendingLiveness,
            )
        }
    }

    fun pulseStoppedSoftly(
        state: ProximityAlarmOwnershipState,
    ): ProximityAlarmOwnershipState = if (state.actualKind == ProximityAlarmKind.PULSE) {
        state.copy(actualTriggerAtMillis = null, actualKind = null)
    } else {
        state
    }

    fun alarmConsumed(
        state: ProximityAlarmOwnershipState,
        pulseActive: Boolean,
    ): ProximityAlarmOwnershipState = state.copy(
        actualTriggerAtMillis = null,
        actualKind = null,
        pendingLivenessTriggerAtMillis = if (
            state.actualKind == ProximityAlarmKind.LIVENESS && !pulseActive
        ) {
            null
        } else {
            state.pendingLivenessTriggerAtMillis
        },
    )

    fun physicalScheduleFailed(
        state: ProximityAlarmOwnershipState,
    ): ProximityAlarmOwnershipState = state.copy(
        actualTriggerAtMillis = null,
        actualKind = null,
    )

    fun reconcileMissingPhysicalAlarm(
        state: ProximityAlarmOwnershipState,
        pulseActive: Boolean,
    ): ProximityAlarmOwnershipState {
        val deadline = state.pendingLivenessTriggerAtMillis
        return if (!pulseActive && state.actualTriggerAtMillis == null && deadline != null) {
            state.copy(
                actualTriggerAtMillis = deadline,
                actualKind = ProximityAlarmKind.LIVENESS,
            )
        } else {
            state
        }
    }

    fun fullStop(): ProximityAlarmOwnershipState = ProximityAlarmOwnershipState()
}

internal enum class ProximityAlarmScheduleDisposition {
    KEPT_EARLIER,
    KEPT_UNKNOWN,
    SCHEDULED,
    FAILED,
}

internal data class ProximityAlarmScheduleResult(
    val disposition: ProximityAlarmScheduleDisposition,
    val existingTriggerAtMillis: Long? = null,
) {
    val succeeded: Boolean
        get() = disposition != ProximityAlarmScheduleDisposition.FAILED
}

internal class ProximityAlarmOwnershipCoordinator(
    private val loadState: () -> ProximityAlarmOwnershipState,
    private val saveState: (ProximityAlarmOwnershipState) -> Unit,
    private val physicalAlarmExists: () -> Boolean,
    private val schedulePhysical: (ProximityAlarmKind, Long) -> Boolean,
    private val cancelPhysical: () -> Unit,
    private val nowMillis: () -> Long,
) {
    fun schedule(
        kind: ProximityAlarmKind,
        triggerAtMillis: Long,
        replaceExisting: Boolean = false,
    ): ProximityAlarmScheduleResult {
        val physicalExists = physicalAlarmExists()
        val current = normalizedState(physicalExists)
        val requested = ProximityAlarmOwnershipReducer.scheduleRequested(
            current,
            kind,
            triggerAtMillis,
        ).let { reduced ->
            if (replaceExisting) {
                reduced.copy(
                    actualTriggerAtMillis = triggerAtMillis,
                    actualKind = kind,
                )
            } else {
                reduced
            }
        }
        saveState(
            current.copy(
                pendingLivenessTriggerAtMillis = requested.pendingLivenessTriggerAtMillis,
            )
        )
        if (!replaceExisting && physicalExists && current.actualTriggerAtMillis != null &&
            current.actualTriggerAtMillis <= triggerAtMillis
        ) {
            return ProximityAlarmScheduleResult(
                ProximityAlarmScheduleDisposition.KEPT_EARLIER,
                current.actualTriggerAtMillis,
            )
        }
        if (!replaceExisting && physicalExists && current.actualTriggerAtMillis == null) {
            return ProximityAlarmScheduleResult(ProximityAlarmScheduleDisposition.KEPT_UNKNOWN)
        }
        if (replaceExisting && physicalExists) cancelPhysical()
        if (schedulePhysical(kind, triggerAtMillis)) {
            saveState(requested)
            return ProximityAlarmScheduleResult(ProximityAlarmScheduleDisposition.SCHEDULED)
        }
        saveState(ProximityAlarmOwnershipReducer.physicalScheduleFailed(requested))
        return ProximityAlarmScheduleResult(ProximityAlarmScheduleDisposition.FAILED)
    }

    fun cancelAll() {
        if (physicalAlarmExists()) cancelPhysical()
        saveState(ProximityAlarmOwnershipReducer.fullStop())
    }

    fun cancelPulseAndRestore(): Boolean {
        val physicalExists = physicalAlarmExists()
        val current = normalizedState(physicalExists)
        if (current.actualKind != ProximityAlarmKind.PULSE) {
            reconcilePendingLiveness(pulseActive = false)
            return false
        }
        if (physicalExists) cancelPhysical()
        saveState(ProximityAlarmOwnershipReducer.pulseStoppedSoftly(current))
        reconcilePendingLiveness(pulseActive = false)
        return true
    }

    fun consume(pulseActive: Boolean) {
        val physicalExists = physicalAlarmExists()
        val current = normalizedState(physicalExists)
        if (physicalExists) cancelPhysical()
        saveState(ProximityAlarmOwnershipReducer.alarmConsumed(current, pulseActive))
        if (!pulseActive) reconcilePendingLiveness(pulseActive = false)
    }

    fun reconcilePendingLiveness(pulseActive: Boolean): Boolean {
        if (physicalAlarmExists()) return true
        val current = normalizedState(physicalExists = false)
        val reconciled = ProximityAlarmOwnershipReducer.reconcileMissingPhysicalAlarm(
            current,
            pulseActive,
        )
        val deadline = reconciled.actualTriggerAtMillis ?: run {
            saveState(current)
            return false
        }
        return schedule(
            ProximityAlarmKind.LIVENESS,
            maxOf(deadline, nowMillis()),
        ).succeeded
    }

    private fun normalizedState(physicalExists: Boolean): ProximityAlarmOwnershipState =
        if (physicalExists) {
            loadState()
        } else {
            loadState().copy(actualTriggerAtMillis = null, actualKind = null)
        }
}

object ProximityAlarmScheduler {
    private const val TAG = "ProximityAlarmScheduler"
    internal const val SCHEDULE_KEY_PROXIMITY = "proximity_alarm"
    internal val SCHEDULE_POLICY = AlarmSchedulePolicy.ExactOnly
    private const val KEY_TRIGGER_AT_MILLIS = "proximity_pulse_trigger_at_millis"
    private const val KEY_TRIGGER_KIND = "proximity_pulse_trigger_kind"
    private const val KEY_PENDING_LIVENESS_TRIGGER_AT_MILLIS =
        "proximity_pending_liveness_trigger_at_millis"

    fun schedule(
        context: Context,
        delayMillis: Long,
        replaceExisting: Boolean = false,
    ): Boolean =
        scheduleInternal(
            context,
            delayMillis,
            ProximityAlarmKind.PULSE,
            "pulse_tick",
            replaceExisting,
        )

    fun scheduleLivenessTrigger(
        context: Context,
        delayMillis: Long,
        event: String,
    ): Boolean = scheduleInternal(
        context,
        delayMillis,
        ProximityAlarmKind.LIVENESS,
        "liveness_trigger event=$event",
        replaceExisting = false,
    )

    @Synchronized
    private fun scheduleInternal(
        context: Context,
        delayMillis: Long,
        kind: ProximityAlarmKind,
        detail: String,
        replaceExisting: Boolean,
    ): Boolean {
        val appContext = context.applicationContext
        val normalizedDelay = delayMillis.coerceAtLeast(0L)
        val triggerAt = System.currentTimeMillis() + normalizedDelay
        val result = ownershipCoordinator(appContext, detail).schedule(
            kind,
            triggerAt,
            replaceExisting,
        )
        when (result.disposition) {
            ProximityAlarmScheduleDisposition.KEPT_EARLIER -> SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Shared proximity alarm kept earlier triggerAt=" +
                    "${result.existingTriggerAtMillis} requestedTriggerAt=$triggerAt $detail.",
            )
            ProximityAlarmScheduleDisposition.KEPT_UNKNOWN -> SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Shared proximity alarm kept because its trigger time is unknown; $detail.",
            )
            ProximityAlarmScheduleDisposition.SCHEDULED,
            ProximityAlarmScheduleDisposition.FAILED -> Unit
        }
        return result.succeeded
    }

    @Synchronized
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        ownershipCoordinator(appContext, "full_stop").cancelAll()
        SmartGeofenceLogger.d(appContext, TAG, "Proximity alarm and liveness obligation cancelled.")
    }

    @Synchronized
    internal fun cancelIfKind(context: Context, owner: ProximityAlarmKind): Boolean {
        val appContext = context.applicationContext
        if (owner != ProximityAlarmKind.PULSE) {
            val scheduledKind = scheduledKind(appContext)
            if (!shouldCancelForOwner(scheduledKind, owner)) return false
            ownershipCoordinator(appContext, "cancel_${owner.name.lowercase()}").cancelAll()
            return true
        }
        val cancelled = ownershipCoordinator(
            appContext,
            "restore_after_pulse_stop",
        ).cancelPulseAndRestore()
        if (!cancelled) {
            val scheduledKind = scheduledKind(appContext)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Shared proximity alarm retained scheduledKind=" +
                    "${scheduledKind?.name?.lowercase() ?: "unknown"} " +
                    "stoppingOwner=${owner.name.lowercase()}.",
            )
        }
        return cancelled
    }

    @Synchronized
    internal fun consume(context: Context, pulseActive: Boolean) {
        val appContext = context.applicationContext
        ownershipCoordinator(appContext, "restore_after_consumption").consume(pulseActive)
    }

    @Synchronized
    internal fun reconcilePendingLiveness(context: Context, pulseActive: Boolean): Boolean {
        val appContext = context.applicationContext
        return ownershipCoordinator(
            appContext,
            "restore_pending_liveness",
        ).reconcilePendingLiveness(pulseActive)
    }

    fun pendingIntentExists(context: Context): Boolean =
        existingPendingIntent(context.applicationContext) != null

    internal fun scheduledKind(context: Context): ProximityAlarmKind? =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeString(KEY_TRIGGER_KIND)
            ?.let { runCatching { ProximityAlarmKind.valueOf(it) }.getOrNull() }

    internal fun pendingLivenessTriggerAtMillis(context: Context): Long? =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeLong(KEY_PENDING_LIVENESS_TRIGGER_AT_MILLIS, 0L)
            .takeIf { it > 0L }

    internal fun shouldKeepExistingTrigger(
        existingTriggerAtMillis: Long?,
        requestedTriggerAtMillis: Long,
    ): Boolean = existingTriggerAtMillis != null &&
        existingTriggerAtMillis <= requestedTriggerAtMillis

    internal fun shouldCancelForOwner(
        scheduledKind: ProximityAlarmKind?,
        owner: ProximityAlarmKind,
    ): Boolean = scheduledKind == owner

    internal fun restorationDelayMillis(deadlineMillis: Long, nowMillis: Long): Long =
        (deadlineMillis - nowMillis).coerceAtLeast(0L)

    private fun ownershipCoordinator(
        context: Context,
        detail: String,
    ): ProximityAlarmOwnershipCoordinator = ProximityAlarmOwnershipCoordinator(
        loadState = {
            val existing = existingPendingIntent(context)
            val loaded = loadOwnershipState(context)
            if (existing == null) {
                loaded.copy(actualTriggerAtMillis = null, actualKind = null)
            } else {
                loaded.copy(
                    actualTriggerAtMillis = storedOrDiagnosticTriggerAt(context, existing),
                )
            }
        },
        saveState = { saveOwnershipState(context, it) },
        physicalAlarmExists = { existingPendingIntent(context) != null },
        schedulePhysical = { kind, triggerAtMillis ->
            schedulePhysicalAlarm(context, kind, triggerAtMillis, detail)
        },
        cancelPhysical = { cancelPhysicalAlarm(context) },
        nowMillis = System::currentTimeMillis,
    )

    private fun schedulePhysicalAlarm(
        context: Context,
        kind: ProximityAlarmKind,
        triggerAtMillis: Long,
        detail: String,
    ): Boolean {
        val delayMillis = restorationDelayMillis(triggerAtMillis, System.currentTimeMillis())
        val pending = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: run {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Failed to create proximity alarm PendingIntent.",
                )
                return false
            }
        val result = AlarmPolicyScheduler.schedule(
            context,
            AlarmScheduleRequest(
                alarmType = AlarmManager.RTC_WAKEUP,
                triggerAtMillis = triggerAtMillis,
                primary = pending,
                policy = SCHEDULE_POLICY,
                scheduleKey = SCHEDULE_KEY_PROXIMITY,
                logTag = TAG,
                logEventPrefix = "proximity_alarm",
                detail = "delay=${delayMillis}ms kind=${kind.name.lowercase()} $detail",
            )
        )
        if (result.scheduled) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Shared proximity alarm ${result.eventSuffix} in ${delayMillis}ms " +
                    "triggerAt=$triggerAtMillis mode=${result.primaryMode?.configValue} $detail.",
            )
            return true
        }
        pending.cancel()
        SmartGeofenceLogger.w(
            context,
            TAG,
            "Failed to schedule proximity alarm: ${result.failureReason ?: result.eventSuffix}",
        )
        return false
    }

    private fun existingPendingIntent(context: Context): PendingIntent? =
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE)

    private fun pendingIntent(context: Context, baseFlags: Int): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            Constants.PENDING_INTENT_REQUEST_PROXIMITY_ALARM,
            Intent(context, ProximityAlarmReceiver::class.java),
            flags,
        )
    }

    private fun storedTriggerAt(context: Context): Long? =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeLong(KEY_TRIGGER_AT_MILLIS, 0L)
            .takeIf { it > 0L }

    private fun storedOrDiagnosticTriggerAt(
        context: Context,
        existing: PendingIntent?,
    ): Long? = if (existing == null) {
        null
    } else {
        storedTriggerAt(context)
            ?: (AlarmPolicyScheduler.diagnosticStatus(
                context,
                SCHEDULE_KEY_PROXIMITY,
            )["triggerAtMillis"] as? Number)?.toLong()
    }

    private fun loadOwnershipState(context: Context): ProximityAlarmOwnershipState =
        ProximityAlarmOwnershipState(
            actualTriggerAtMillis = storedTriggerAt(context),
            actualKind = scheduledKind(context),
            pendingLivenessTriggerAtMillis = pendingLivenessTriggerAtMillis(context),
        )

    private fun saveOwnershipState(
        context: Context,
        state: ProximityAlarmOwnershipState,
    ) {
        val editor = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
        state.actualTriggerAtMillis?.let { editor.putLong(KEY_TRIGGER_AT_MILLIS, it) }
            ?: editor.remove(KEY_TRIGGER_AT_MILLIS)
        state.actualKind?.let { editor.putString(KEY_TRIGGER_KIND, it.name) }
            ?: editor.remove(KEY_TRIGGER_KIND)
        state.pendingLivenessTriggerAtMillis?.let {
            editor.putLong(KEY_PENDING_LIVENESS_TRIGGER_AT_MILLIS, it)
        } ?: editor.remove(KEY_PENDING_LIVENESS_TRIGGER_AT_MILLIS)
        editor.commit()
    }

    private fun cancelPhysicalAlarm(context: Context) {
        existingPendingIntent(context)?.let {
            AlarmPolicyScheduler.cancel(context, SCHEDULE_KEY_PROXIMITY, it)
        }
    }
}
