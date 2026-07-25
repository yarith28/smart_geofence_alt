package com.yarithdev.smart_geofence.activity

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionController
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.core.AndroidPackageManagerCompat
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBoolean
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.dormant.DormantFarController
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseController
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.wake.WakeAction
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator
import com.yarithdev.smart_geofence.wake.WakeExemption
import com.yarithdev.smart_geofence.wake.WakeSource
import com.yarithdev.smart_geofence.wake.WakeTask
import java.util.IdentityHashMap

internal enum class ActivityProcessRecoveryAction {
    NONE,
    RECONCILE_DESIRED,
    RECONCILE_STOP,
}

internal enum class ActivityStationarySource(val configValue: String) {
    TRANSITION("TRANSITION"),
    BOOTSTRAP_PERIODIC("BOOTSTRAP_PERIODIC"),
    BACKSTOP_PERIODIC("BACKSTOP_PERIODIC");

    companion object {
        fun fromConfigValue(value: String?): ActivityStationarySource? =
            entries.firstOrNull { it.configValue == value }
    }
}

internal fun activityLifecycleNeedsProcessRecovery(
    state: ActivityRegistrationLifecycleState,
): Boolean {
    val operationInterrupted = state.transition.requestInFlight ||
        state.transition.removalInFlight ||
        state.periodic.requestInFlight ||
        state.periodic.removalInFlight
    val transitionUnsettled = !state.transition.confirmed ||
        !state.transition.belongsToMonitoringSession(state.monitoringSessionGeneration)
    val periodicUnsettled = if (state.periodicBackstopEnabled) {
        !state.periodic.confirmed ||
            !state.periodic.belongsToMonitoringSession(state.monitoringSessionGeneration) ||
            state.periodicMode != ActivityPeriodicMode.PERSISTENT_BACKSTOP ||
            state.periodicOwner != ActivityMonitor.BASELINE_PERIODIC_REASON ||
            state.confirmedPeriodicIntervalMillis != state.desiredPeriodicIntervalMillis
    } else {
        !state.bootstrapCompleted ||
            state.periodicMode != ActivityPeriodicMode.NONE ||
            state.periodic.confirmed ||
            state.periodicRemovalRequired ||
            !state.periodic.removalConfirmed
    }
    val desiredMetadataUnsettled = state.controllerDesired &&
        (transitionUnsettled || periodicUnsettled)
    val stoppedMetadataUnsettled = !state.controllerDesired &&
        state.controllerEpoch > 0L &&
        (state.transition.confirmed ||
            state.periodic.confirmed ||
            state.periodicMode != ActivityPeriodicMode.NONE ||
            !state.transition.removalConfirmed ||
            !state.periodic.removalConfirmed)
    return operationInterrupted || desiredMetadataUnsettled || stoppedMetadataUnsettled
}

internal fun activityProcessRecoveryAction(
    state: ActivityRegistrationLifecycleState,
    operationalIneligibilityReason: String?,
): ActivityProcessRecoveryAction {
    if (state.controllerDesired && operationalIneligibilityReason != null) {
        return ActivityProcessRecoveryAction.RECONCILE_STOP
    }
    if (!activityLifecycleNeedsProcessRecovery(state)) {
        return ActivityProcessRecoveryAction.NONE
    }
    return if (state.controllerDesired && operationalIneligibilityReason == null) {
        ActivityProcessRecoveryAction.RECONCILE_DESIRED
    } else {
        ActivityProcessRecoveryAction.RECONCILE_STOP
    }
}

internal data class ActivityCallbackRecoveryResult(
    val lifecycle: ActivityRegistrationLifecycle? = null,
    val ineligibilityReason: String? = null,
)

internal fun reconcileActivityCallbackLifecycle(
    existingLifecycle: ActivityRegistrationLifecycle?,
    persistedState: ActivityRegistrationLifecycleState,
    operationalIneligibilityReason: String?,
    createLifecycle: (ActivityRegistrationLifecycleState) -> ActivityRegistrationLifecycle,
): ActivityCallbackRecoveryResult = when (
    activityProcessRecoveryAction(persistedState, operationalIneligibilityReason)
) {
    ActivityProcessRecoveryAction.NONE -> ActivityCallbackRecoveryResult()
    ActivityProcessRecoveryAction.RECONCILE_DESIRED -> {
        val recovered = existingLifecycle ?: createLifecycle(persistedState)
        recovered.reconcileProcessBootstrap(
            retainDesired = true,
            reason = "process_bootstrap_unsettled",
        )
        ActivityCallbackRecoveryResult(lifecycle = recovered)
    }
    ActivityProcessRecoveryAction.RECONCILE_STOP -> {
        val recovered = existingLifecycle ?: createLifecycle(persistedState)
        recovered.reconcileProcessBootstrap(
            retainDesired = false,
            reason = "process_bootstrap:" +
                (operationalIneligibilityReason ?: "controller_not_desired"),
        )
        ActivityCallbackRecoveryResult(
            lifecycle = recovered,
            ineligibilityReason = operationalIneligibilityReason.takeIf {
                persistedState.controllerDesired
            },
        )
    }
}

object ActivityMonitor {
    private const val TAG = "ActivityMonitor"
    internal const val BASELINE_PERIODIC_REASON = "monitoring_baseline"
    internal const val BOOTSTRAP_PERIODIC_REASON = "monitoring_bootstrap"
    internal const val BOOTSTRAP_TIMEOUT_MILLIS =
        Constants.DEFAULT_ACTIVITY_BOOTSTRAP_TIMEOUT_MILLIS
    private const val KEY_STATIONARY = "activity_stationary"
    private const val KEY_STATIONARY_AT = "activity_stationary_at"
    private const val KEY_STATIONARY_SOURCE = "activity_stationary_source"
    private const val KEY_PERIODIC_REASON = "activity_periodic_reason"
    private const val KEY_PERIODIC_REQUESTED_AT = "activity_periodic_requested_at"
    private const val KEY_PERIODIC_INTERVAL_MILLIS = "activity_periodic_interval_millis"
    private const val KEY_LAST_PERIODIC_RESULT_AT = "activity_last_periodic_result_at"
    private const val KEY_CONTROLLER_DESIRED = "activity_controller_desired"
    private const val KEY_CONTROLLER_EPOCH = "activity_controller_epoch"
    private const val KEY_MONITORING_SESSION_GENERATION =
        "activity_monitoring_session_generation"
    private const val KEY_NEXT_OPERATION_GENERATION = "activity_next_operation_generation"
    private const val KEY_DESIRED_PERIODIC_INTERVAL = "activity_desired_periodic_interval_millis"
    private const val KEY_CONFIRMED_PERIODIC_INTERVAL = "activity_confirmed_periodic_interval_millis"
    private const val KEY_PERIODIC_OWNER = "activity_periodic_owner"
    private const val KEY_PERIODIC_BACKSTOP_ENABLED = "activity_periodic_backstop_enabled"
    private const val KEY_PERIODIC_MODE = "activity_periodic_mode"
    private const val KEY_BOOTSTRAP_REQUESTED_AT = "activity_bootstrap_requested_at"
    private const val KEY_BOOTSTRAP_DEADLINE = "activity_bootstrap_deadline"
    private const val KEY_BOOTSTRAP_RESULT_RECEIVED = "activity_bootstrap_result_received"
    private const val KEY_BOOTSTRAP_COMPLETED = "activity_bootstrap_completed"
    private const val KEY_PERIODIC_REMOVAL_REQUIRED = "activity_periodic_removal_required"
    private const val KEY_FAILURE_SERIAL = "activity_failure_serial"
    private const val KEY_STALE_CALLBACK_COUNT = "activity_stale_callback_count"
    private const val KEY_LAST_STALE_CALLBACK_REASON = "activity_last_stale_callback_reason"
    private const val KEY_IGNORED_CALLBACK_COUNT = "activity_ignored_callback_count"
    private const val KEY_LAST_IGNORED_CALLBACK_REASON = "activity_last_ignored_callback_reason"
    private const val KEY_TRANSITION_PREFIX = "activity_transition_registration"
    private const val KEY_PERIODIC_PREFIX = "activity_periodic_registration"

    private val lifecycleLock = Any()
    private val lifecycles = IdentityHashMap<Context, ActivityRegistrationLifecycle>()

    internal fun start(
        context: Context,
        reason: String = "controller",
        onComplete: ((ActivityReconcileResult) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        val ineligibleReason = monitoringIneligibilityReason(appContext, requireDesired = false)
        if (ineligibleReason != null) {
            lifecycle(appContext).setControllerDesired(
                desired = false,
                periodicIntervalMillis = null,
                reason = ineligibleReason,
                onComplete = onComplete,
            )
            clearStationary(appContext)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Activity monitoring not eligible; desired=false reason=$ineligibleReason."
            )
            return
        }
        val config = SmartGeofenceConfigStore.load(appContext)
        val activityLifecycle = lifecycle(appContext)
        val previousSession = activityLifecycle.snapshot().monitoringSessionGeneration
        activityLifecycle.setControllerDesired(
            desired = true,
            periodicIntervalMillis = config.activityUpdateIntervalMillis.coerceAtLeast(0L),
            reason = reason,
            periodicBackstopEnabled = config.activityPeriodicBackstopEnabled,
            onComplete = onComplete,
        )
        if (activityLifecycle.snapshot().monitoringSessionGeneration != previousSession) {
            clearStationary(appContext)
        }
    }

    internal fun reconcileDesired(
        context: Context,
        reason: String,
        onComplete: (ActivityReconcileResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        val ineligibleReason = monitoringIneligibilityReason(appContext, requireDesired = true)
        if (ineligibleReason != null) {
            onComplete(
                ActivityReconcileResult(
                    ActivityReconcileDisposition.NOT_DESIRED,
                    "repair_ineligible:$ineligibleReason",
                )
            )
            return
        }
        lifecycle(appContext).reconcileDesired(reason, onComplete)
    }

    internal fun stop(
        context: Context,
        reason: String = "controller_stop",
        onComplete: ((ActivityReconcileResult) -> Unit)? = null,
    ) {
        lifecycle(context.applicationContext).setControllerDesired(
            desired = false,
            periodicIntervalMillis = null,
            reason = reason,
            onComplete = onComplete,
        )
        clearStationary(context.applicationContext)
    }

    internal fun invalidateRegistrationConfidence(context: Context, reason: String) {
        val appContext = context.applicationContext
        lifecycle(appContext).invalidateConfidence(reason)
        clearStationary(appContext)
        SmartGeofenceLogger.d(appContext, TAG, "Activity registration confidence invalidated reason=$reason.")
    }

    internal fun controllerDesired(context: Context): Boolean =
        lifecycleState(context.applicationContext).controllerDesired

    internal fun recordPeriodicResultForLifecycle(context: Context, reason: String) {
        lifecycle(context.applicationContext).recordPeriodicResult(reason)
    }

    internal fun handleBootstrapTimeout(
        context: Context,
        monitoringSessionGeneration: Long,
        onComplete: ((ActivityReconcileResult) -> Unit)? = null,
    ): Boolean = lifecycle(context.applicationContext).onBootstrapDeadline(
        monitoringSessionGeneration,
        "alarm",
        onComplete,
    )

    internal fun callbackIneligibilityReason(context: Context): String? {
        val appContext = context.applicationContext
        ensureProcessRecovery(appContext)?.let { return it }
        return monitoringIneligibilityReason(appContext, requireDesired = true)
    }

    internal fun operationalIneligibilityReason(context: Context): String? =
        monitoringIneligibilityReason(context.applicationContext, requireDesired = false)

    internal fun callbackIneligibilityReason(
        context: Context,
        kind: ActivityRegistrationKind,
        payloadElapsedRealtimeNanos: Long?,
    ): String? {
        val appContext = context.applicationContext
        ensureProcessRecovery(appContext)?.let { return it }
        return activityCallbackIneligibilityReason(
            state = lifecycleState(appContext),
            operationalIneligibilityReason = monitoringIneligibilityReason(
                appContext,
                requireDesired = false,
            ),
            kind = kind,
            payloadElapsedRealtimeNanos = payloadElapsedRealtimeNanos,
        )
    }

    internal fun recordIgnoredCallback(
        context: Context,
        kind: ActivityRegistrationKind,
        reason: String,
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.safeLong(KEY_IGNORED_CALLBACK_COUNT, 0L) + 1L
        prefs.edit()
            .putLong(KEY_IGNORED_CALLBACK_COUNT, count)
            .putString(KEY_LAST_IGNORED_CALLBACK_REASON, "${kind.name.lowercase()}:$reason")
            .apply()
        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "activity_callback_ignored",
            reasonCode = reason,
            source = kind.name.lowercase(),
        )
    }

    internal fun ignoredCallbackCount(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeLong(KEY_IGNORED_CALLBACK_COUNT, 0L)

    internal fun lastIgnoredCallbackReason(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeString(KEY_LAST_IGNORED_CALLBACK_REASON)

    internal fun lifecycleState(context: Context): ActivityRegistrationLifecycleState =
        synchronized(lifecycleLock) {
            lifecycles[context.applicationContext]?.snapshot()
        } ?: loadLifecycleState(context.applicationContext)

    private fun lifecycle(context: Context): ActivityRegistrationLifecycle {
        val appContext = context.applicationContext
        return synchronized(lifecycleLock) {
            lifecycles[appContext] ?: newLifecycle(
                appContext,
                loadLifecycleState(appContext),
            ).also { lifecycles[appContext] = it }
        }
    }

    private fun ensureProcessRecovery(context: Context): String? {
        val appContext = context.applicationContext
        return synchronized(lifecycleLock) {
            val existing = lifecycles[appContext]
            val persisted = existing?.snapshot() ?: loadLifecycleState(appContext)
            val operationalIneligibility =
                monitoringIneligibilityReason(appContext, requireDesired = false)
            val recovery = reconcileActivityCallbackLifecycle(
                existingLifecycle = existing,
                persistedState = persisted,
                operationalIneligibilityReason = operationalIneligibility,
                createLifecycle = { newLifecycle(appContext, it) },
            )
            if (existing == null && recovery.lifecycle != null) {
                lifecycles[appContext] = recovery.lifecycle
            }
            if (recovery.ineligibilityReason != null) {
                clearStationary(appContext)
            }
            recovery.ineligibilityReason
        }
    }

    private fun newLifecycle(
        context: Context,
        initialState: ActivityRegistrationLifecycleState,
    ): ActivityRegistrationLifecycle = ActivityRegistrationLifecycle(
        initialState = initialState,
        backend = AndroidActivityRegistrationBackend(context),
        nowElapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
        persist = { persistLifecycleState(context, it) },
    )

    private fun monitoringIneligibilityReason(
        context: Context,
        requireDesired: Boolean,
    ): String? {
        val appContext = context.applicationContext
        if (requireDesired && !controllerDesired(appContext)) return "controller_not_desired"
        val config = SmartGeofenceConfigStore.load(appContext)
        if (ExactAlarmPermissionController.isStrictBlocked(appContext, config)) {
            return "strict_exact_alarm_blocked"
        }
        val hasFences = FenceStore.getAll(appContext).isNotEmpty()
        if (!config.escalationEnabled) return "escalation_disabled"
        if (!hasFences) return "no_fences"
        val pulseCanRun = config.proximityPulseEnabled && ProximityPulseController.canRun(appContext)
        if (!shouldMonitorActivity(config, hasFences, pulseCanRun)) return "activity_features_disabled"
        if (!hasPermission(appContext)) return "activity_permission_missing"
        if (!receiverOperable(appContext)) return "activity_receiver_unavailable"
        return null
    }

    private fun receiverOperable(context: Context): Boolean =
        try {
            val component = ComponentName(context, ActivityReceiver::class.java)
            val packageManager = context.packageManager
            val info = AndroidPackageManagerCompat.getReceiverInfo(
                packageManager,
                component,
                PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
            )
            val componentEnabled = when (packageManager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                else -> info.enabled
            }
            componentEnabled && info.applicationInfo?.enabled != false
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun loadLifecycleState(context: Context): ActivityRegistrationLifecycleState {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val periodicOwner = prefs.safeString(KEY_PERIODIC_OWNER)
        val periodicMode = ActivityPeriodicMode.fromConfigValue(
            prefs.safeString(KEY_PERIODIC_MODE),
        ) ?: when (periodicOwner) {
            BOOTSTRAP_PERIODIC_REASON -> ActivityPeriodicMode.BOOTSTRAP
            BASELINE_PERIODIC_REASON -> ActivityPeriodicMode.PERSISTENT_BACKSTOP
            else -> ActivityPeriodicMode.NONE
        }
        return ActivityRegistrationLifecycleState(
            controllerDesired = prefs.safeBoolean(KEY_CONTROLLER_DESIRED, false),
            controllerEpoch = prefs.safeLong(KEY_CONTROLLER_EPOCH, 0L),
            monitoringSessionGeneration = prefs.safeLong(
                KEY_MONITORING_SESSION_GENERATION,
                0L,
            ),
            nextOperationGeneration = prefs.safeLong(KEY_NEXT_OPERATION_GENERATION, 0L),
            desiredPeriodicIntervalMillis = prefs.nullableLong(KEY_DESIRED_PERIODIC_INTERVAL),
            confirmedPeriodicIntervalMillis = prefs.nullableLong(KEY_CONFIRMED_PERIODIC_INTERVAL),
            periodicOwner = periodicOwner,
            transition = prefs.registrationPart(KEY_TRANSITION_PREFIX),
            periodic = prefs.registrationPart(KEY_PERIODIC_PREFIX),
            failureSerial = prefs.safeLong(KEY_FAILURE_SERIAL, 0L),
            staleCallbackCount = prefs.safeLong(KEY_STALE_CALLBACK_COUNT, 0L),
            lastStaleCallbackReason = prefs.safeString(KEY_LAST_STALE_CALLBACK_REASON),
            periodicBackstopEnabled = prefs.safeBoolean(
                KEY_PERIODIC_BACKSTOP_ENABLED,
                false,
            ),
            periodicMode = periodicMode,
            bootstrapRequestedAtMillis = prefs.nullableLong(KEY_BOOTSTRAP_REQUESTED_AT),
            bootstrapDeadlineMillis = prefs.nullableLong(KEY_BOOTSTRAP_DEADLINE),
            bootstrapResultReceived = prefs.safeBoolean(
                KEY_BOOTSTRAP_RESULT_RECEIVED,
                false,
            ),
            bootstrapCompleted = prefs.safeBoolean(KEY_BOOTSTRAP_COMPLETED, false),
            periodicRemovalRequired = prefs.safeBoolean(
                KEY_PERIODIC_REMOVAL_REQUIRED,
                false,
            ),
        )
    }

    private fun persistLifecycleState(
        context: Context,
        state: ActivityRegistrationLifecycleState,
    ) {
        val editor = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONTROLLER_DESIRED, state.controllerDesired)
            .putLong(KEY_CONTROLLER_EPOCH, state.controllerEpoch)
            .putLong(KEY_MONITORING_SESSION_GENERATION, state.monitoringSessionGeneration)
            .putLong(KEY_NEXT_OPERATION_GENERATION, state.nextOperationGeneration)
            .putLong(KEY_FAILURE_SERIAL, state.failureSerial)
            .putLong(KEY_STALE_CALLBACK_COUNT, state.staleCallbackCount)
            .putBoolean(KEY_PERIODIC_BACKSTOP_ENABLED, state.periodicBackstopEnabled)
            .putString(KEY_PERIODIC_MODE, state.periodicMode.configValue)
            .putBoolean(KEY_BOOTSTRAP_RESULT_RECEIVED, state.bootstrapResultReceived)
            .putBoolean(KEY_BOOTSTRAP_COMPLETED, state.bootstrapCompleted)
            .putBoolean(KEY_PERIODIC_REMOVAL_REQUIRED, state.periodicRemovalRequired)
        editor.putNullableLong(KEY_DESIRED_PERIODIC_INTERVAL, state.desiredPeriodicIntervalMillis)
        editor.putNullableLong(KEY_CONFIRMED_PERIODIC_INTERVAL, state.confirmedPeriodicIntervalMillis)
        editor.putNullableLong(KEY_BOOTSTRAP_REQUESTED_AT, state.bootstrapRequestedAtMillis)
        editor.putNullableLong(KEY_BOOTSTRAP_DEADLINE, state.bootstrapDeadlineMillis)
        editor.putNullableString(KEY_PERIODIC_OWNER, state.periodicOwner)
        editor.putNullableString(KEY_LAST_STALE_CALLBACK_REASON, state.lastStaleCallbackReason)
        editor.putRegistrationPart(KEY_TRANSITION_PREFIX, state.transition)
        editor.putRegistrationPart(KEY_PERIODIC_PREFIX, state.periodic)

        if (state.periodic.confirmed &&
            state.periodicOwner != null &&
            state.confirmedPeriodicIntervalMillis != null
        ) {
            editor.putString(KEY_PERIODIC_REASON, state.periodicOwner)
                .putLong(
                    KEY_PERIODIC_REQUESTED_AT,
                    state.periodic.lastSuccessAtMillis ?: System.currentTimeMillis(),
                )
                .putLong(KEY_PERIODIC_INTERVAL_MILLIS, state.confirmedPeriodicIntervalMillis)
        } else {
            editor.remove(KEY_PERIODIC_REASON)
                .remove(KEY_PERIODIC_REQUESTED_AT)
                .remove(KEY_PERIODIC_INTERVAL_MILLIS)
        }
        editor.apply()
        ActivityBootstrapTimeoutScheduler.reconcile(context, state)
    }

    private fun SharedPreferences.registrationPart(prefix: String): ActivityRegistrationPartState =
        ActivityRegistrationPartState(
            confirmed = safeBoolean("${prefix}_confirmed", false),
            requestInFlight = safeBoolean("${prefix}_request_in_flight", false),
            removalInFlight = safeBoolean("${prefix}_removal_in_flight", false),
            removalConfirmed = safeBoolean("${prefix}_removal_confirmed", false),
            operationGeneration = safeLong("${prefix}_operation_generation", 0L),
            operationControllerEpoch = safeLong("${prefix}_operation_controller_epoch", 0L),
            operationMonitoringSessionGeneration = safeLong(
                "${prefix}_operation_monitoring_session_generation",
                0L,
            ),
            operationStartedAtMillis = nullableLong("${prefix}_operation_started_at"),
            payloadAcceptanceElapsedRealtimeNanos = safeLong(
                "${prefix}_payload_acceptance_elapsed_realtime_nanos",
                0L,
            ),
            requestReplacesConfirmed = safeBoolean("${prefix}_request_replaces_confirmed", false),
            requestBlockedEpoch = nullableLong("${prefix}_request_blocked_epoch"),
            removalBlockedEpoch = nullableLong("${prefix}_removal_blocked_epoch"),
            lastSuccessAtMillis = nullableLong("${prefix}_last_success_at"),
            lastFailureAtMillis = nullableLong("${prefix}_last_failure_at"),
            lastFailureReason = safeString("${prefix}_last_failure_reason"),
        )

    private fun SharedPreferences.Editor.putRegistrationPart(
        prefix: String,
        part: ActivityRegistrationPartState,
    ) {
        putBoolean("${prefix}_confirmed", part.confirmed)
        putBoolean("${prefix}_request_in_flight", part.requestInFlight)
        putBoolean("${prefix}_removal_in_flight", part.removalInFlight)
        putBoolean("${prefix}_removal_confirmed", part.removalConfirmed)
        putLong("${prefix}_operation_generation", part.operationGeneration)
        putLong("${prefix}_operation_controller_epoch", part.operationControllerEpoch)
        putLong(
            "${prefix}_operation_monitoring_session_generation",
            part.operationMonitoringSessionGeneration,
        )
        putBoolean("${prefix}_request_replaces_confirmed", part.requestReplacesConfirmed)
        putNullableLong("${prefix}_request_blocked_epoch", part.requestBlockedEpoch)
        putNullableLong("${prefix}_removal_blocked_epoch", part.removalBlockedEpoch)
        putNullableLong("${prefix}_operation_started_at", part.operationStartedAtMillis)
        putLong(
            "${prefix}_payload_acceptance_elapsed_realtime_nanos",
            part.payloadAcceptanceElapsedRealtimeNanos,
        )
        putNullableLong("${prefix}_last_success_at", part.lastSuccessAtMillis)
        putNullableLong("${prefix}_last_failure_at", part.lastFailureAtMillis)
        putNullableString("${prefix}_last_failure_reason", part.lastFailureReason)
    }

    private fun SharedPreferences.nullableLong(key: String): Long? =
        if (contains(key)) safeLong(key, 0L) else null

    private fun SharedPreferences.Editor.putNullableLong(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private fun SharedPreferences.Editor.putNullableString(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private class AndroidActivityRegistrationBackend(
        private val context: Context,
    ) : ActivityRegistrationBackend {
        override fun pendingIntentExists(kind: ActivityRegistrationKind): Boolean =
            when (kind) {
                ActivityRegistrationKind.TRANSITION -> existingTransitionPendingIntent(context) != null
                ActivityRegistrationKind.PERIODIC -> existingActivityUpdatePendingIntent(context) != null
            }

        @SuppressLint("MissingPermission")
        override fun request(
            kind: ActivityRegistrationKind,
            intervalMillis: Long?,
            onComplete: (ActivityRegistrationOperationResult) -> Unit,
        ) {
            try {
                val pendingIntent = when (kind) {
                    ActivityRegistrationKind.TRANSITION ->
                        transitionPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
                    ActivityRegistrationKind.PERIODIC ->
                        activityUpdatePendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
                } ?: run {
                    onComplete(ActivityRegistrationOperationResult(false, "pending_intent_creation_failed"))
                    return
                }
                val task = when (kind) {
                    ActivityRegistrationKind.TRANSITION ->
                        ActivityRecognition.getClient(context).requestActivityTransitionUpdates(
                            ActivityTransitionRequest(
                                listOf(
                                    transition(
                                        DetectedActivity.STILL,
                                        ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                                    ),
                                    transition(
                                        DetectedActivity.STILL,
                                        ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                                    ),
                                    transition(
                                        DetectedActivity.WALKING,
                                        ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                                    ),
                                    transition(
                                        DetectedActivity.RUNNING,
                                        ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                                    ),
                                    transition(
                                        DetectedActivity.ON_BICYCLE,
                                        ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                                    ),
                                    transition(
                                        DetectedActivity.IN_VEHICLE,
                                        ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                                    ),
                                    transition(
                                        DetectedActivity.ON_FOOT,
                                        ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                                    ),
                                )
                            ),
                            pendingIntent,
                        )
                    ActivityRegistrationKind.PERIODIC ->
                        ActivityRecognition.getClient(context).requestActivityUpdates(
                            requireNotNull(intervalMillis) { "Periodic Activity interval is required." },
                            pendingIntent,
                        )
                }
                completeTask(kind, "request", task, onComplete)
            } catch (e: Throwable) {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Activity ${kind.name.lowercase()} request submission failed: ${e.message}",
                    e,
                )
                onComplete(
                    ActivityRegistrationOperationResult(
                        false,
                        "request_submission_failed:${e.javaClass.simpleName}",
                    )
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun remove(
            kind: ActivityRegistrationKind,
            onComplete: (ActivityRegistrationOperationResult) -> Unit,
        ) {
            try {
                val pendingIntent = when (kind) {
                    ActivityRegistrationKind.TRANSITION ->
                        transitionPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
                    ActivityRegistrationKind.PERIODIC ->
                        activityUpdatePendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
                } ?: run {
                    onComplete(ActivityRegistrationOperationResult(false, "pending_intent_creation_failed"))
                    return
                }
                val task = when (kind) {
                    ActivityRegistrationKind.TRANSITION ->
                        ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent)
                    ActivityRegistrationKind.PERIODIC ->
                        ActivityRecognition.getClient(context).removeActivityUpdates(pendingIntent)
                }
                completeTask(kind, "remove", task, onComplete)
            } catch (e: Throwable) {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Activity ${kind.name.lowercase()} removal submission failed: ${e.message}",
                    e,
                )
                onComplete(
                    ActivityRegistrationOperationResult(
                        false,
                        "removal_submission_failed:${e.javaClass.simpleName}",
                    )
                )
            }
        }

        override fun cancelPendingIntent(kind: ActivityRegistrationKind) {
            when (kind) {
                ActivityRegistrationKind.TRANSITION -> existingTransitionPendingIntent(context)?.cancel()
                ActivityRegistrationKind.PERIODIC -> existingActivityUpdatePendingIntent(context)?.cancel()
            }
        }

        private fun completeTask(
            kind: ActivityRegistrationKind,
            operation: String,
            task: Task<Void>,
            onComplete: (ActivityRegistrationOperationResult) -> Unit,
        ) {
            task.addOnCompleteListener { completed ->
                val failure = completed.exception
                val result = ActivityRegistrationOperationResult(
                    succeeded = completed.isSuccessful,
                    failureReason = when {
                        completed.isSuccessful -> null
                        failure != null ->
                            "${operation}_failed:${failure.javaClass.simpleName}:${failure.message}"
                        completed.isCanceled -> "${operation}_cancelled"
                        else -> "${operation}_failed:unknown"
                    },
                )
                if (result.succeeded) {
                    SmartGeofenceLogger.d(
                        context,
                        TAG,
                        "Activity ${kind.name.lowercase()} $operation confirmed by Play Services.",
                    )
                } else {
                    SmartGeofenceLogger.w(
                        context,
                        TAG,
                        "Activity ${kind.name.lowercase()} $operation failed: ${result.failureReason}",
                        failure,
                    )
                }
                onComplete(result)
            }
        }
    }

    fun isLikelyStationary(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.safeBoolean(KEY_STATIONARY, false)) return false
        val source = ActivityStationarySource.fromConfigValue(
            prefs.safeString(KEY_STATIONARY_SOURCE),
        ) ?: ActivityStationarySource.BACKSTOP_PERIODIC
        if (source == ActivityStationarySource.TRANSITION) return true
        val at = prefs.safeLong(KEY_STATIONARY_AT, 0L)
        val ttlMs = SmartGeofenceConfigStore.load(appContext).activityStationaryTtlMillis
        return activityStationaryTimestampIsFresh(
            recordedAtMillis = at,
            nowMillis = System.currentTimeMillis(),
            ttlMillis = ttlMs,
        )
    }

    fun setStationary(context: Context, stationary: Boolean) {
        setStationary(
            context,
            stationary,
            ActivityStationarySource.BACKSTOP_PERIODIC,
        )
    }

    internal fun setStationary(
        context: Context,
        stationary: Boolean,
        source: ActivityStationarySource,
    ) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STATIONARY, stationary)
            .putLong(KEY_STATIONARY_AT, System.currentTimeMillis())
            .putString(KEY_STATIONARY_SOURCE, source.configValue)
            .apply()
    }

    private fun clearStationary(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STATIONARY, false)
            .remove(KEY_STATIONARY_AT)
            .remove(KEY_STATIONARY_SOURCE)
            .apply()
    }

    internal fun periodicStationarySource(context: Context): ActivityStationarySource =
        when (lifecycleState(context.applicationContext).periodicMode) {
            ActivityPeriodicMode.BOOTSTRAP -> ActivityStationarySource.BOOTSTRAP_PERIODIC
            ActivityPeriodicMode.PERSISTENT_BACKSTOP,
            ActivityPeriodicMode.NONE -> ActivityStationarySource.BACKSTOP_PERIODIC
        }

    fun transitionPendingIntentExists(context: Context): Boolean =
        existingTransitionPendingIntent(context.applicationContext) != null

    fun periodicPendingIntentExists(context: Context): Boolean =
        existingActivityUpdatePendingIntent(context.applicationContext) != null

    fun periodicDemandActive(context: Context): Boolean =
        periodicReason(context) != null

    fun periodicReason(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PERIODIC_REASON, null)

    fun periodicRequestedAtMillis(context: Context): Long? {
        val value = context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeLong(KEY_PERIODIC_REQUESTED_AT, 0L)
        return value.takeIf { it > 0L }
    }

    fun periodicIntervalMillis(context: Context): Long? {
        val prefs = context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PERIODIC_INTERVAL_MILLIS)) return null
        return prefs.safeLong(KEY_PERIODIC_INTERVAL_MILLIS, -1L).takeIf { it >= 0L }
    }

    fun lastPeriodicResultAtMillis(context: Context): Long? {
        val value = context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeLong(KEY_LAST_PERIODIC_RESULT_AT, 0L)
        return value.takeIf { it > 0L }
    }

    fun stationarySource(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeString(KEY_STATIONARY_SOURCE)

    fun bootstrapTimeoutPendingIntentExists(context: Context): Boolean =
        ActivityBootstrapTimeoutScheduler.pendingIntentExists(context.applicationContext)

    fun recordPeriodicResult(context: Context, atMillis: Long = System.currentTimeMillis()) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_PERIODIC_RESULT_AT, atMillis)
            .apply()
    }

    internal fun recordPeriodicDemand(
        context: Context,
        reason: String,
        requestedAtMillis: Long = System.currentTimeMillis(),
        intervalMillis: Long,
    ) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERIODIC_REASON, reason)
            .putLong(KEY_PERIODIC_REQUESTED_AT, requestedAtMillis)
            .putLong(KEY_PERIODIC_INTERVAL_MILLIS, intervalMillis.coerceAtLeast(0L))
            .apply()
    }

    internal fun clearPeriodicDemand(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PERIODIC_REASON)
            .remove(KEY_PERIODIC_REQUESTED_AT)
            .remove(KEY_PERIODIC_INTERVAL_MILLIS)
            .apply()
    }

    private fun transition(activity: Int, type: Int): ActivityTransition =
        ActivityTransition.Builder()
            .setActivityType(activity)
            .setActivityTransition(type)
            .build()

    private fun existingTransitionPendingIntent(context: Context): PendingIntent? =
        transitionPendingIntent(context, PendingIntent.FLAG_NO_CREATE)

    private fun existingActivityUpdatePendingIntent(context: Context): PendingIntent? =
        activityUpdatePendingIntent(context, PendingIntent.FLAG_NO_CREATE)

    private fun transitionPendingIntent(context: Context, baseFlags: Int): PendingIntent? =
        pendingIntent(context, baseFlags, Constants.PENDING_INTENT_REQUEST_ACTIVITY_TRANSITION)

    private fun activityUpdatePendingIntent(context: Context, baseFlags: Int): PendingIntent? =
        pendingIntent(context, baseFlags, Constants.PENDING_INTENT_REQUEST_ACTIVITY_UPDATE)

    private fun pendingIntent(context: Context, baseFlags: Int, requestCode: Int): PendingIntent? {
        val intent = Intent(context, ActivityReceiver::class.java)
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun hasPermission(context: Context): Boolean =
        ActivityRecognitionPermissionController.hasRequiredPermission(context)
}

internal object ActivityBootstrapTimeoutScheduler {
    internal const val SCHEDULE_KEY = "activity_bootstrap_timeout"
    private const val ACTION =
        "com.yarithdev.smart_geofence.action.ACTIVITY_BOOTSTRAP_TIMEOUT"
    private const val EXTRA_MONITORING_SESSION_GENERATION =
        "smart_geofence.activity_bootstrap_monitoring_session_generation"
    private const val TAG = "ActivityBootstrapTimeout"
    private val handler = Handler(Looper.getMainLooper())
    private val runnables = IdentityHashMap<Context, Runnable>()

    @Synchronized
    fun reconcile(context: Context, state: ActivityRegistrationLifecycleState) {
        val appContext = context.applicationContext
        cancelHandler(appContext)
        val deadline = state.bootstrapDeadlineMillis
        val shouldSchedule = state.controllerDesired &&
            !state.periodicBackstopEnabled &&
            state.periodicMode == ActivityPeriodicMode.BOOTSTRAP &&
            !state.bootstrapCompleted &&
            !state.periodicRemovalRequired &&
            deadline != null
        if (!shouldSchedule || deadline == null) {
            cancelAlarm(appContext)
            return
        }

        val generation = state.monitoringSessionGeneration
        val runnable = Runnable {
            synchronized(ActivityBootstrapTimeoutScheduler) {
                runnables.remove(appContext)
            }
            ActivityMonitor.handleBootstrapTimeout(appContext, generation)
        }
        runnables[appContext] = runnable
        handler.postDelayed(runnable, (deadline - System.currentTimeMillis()).coerceAtLeast(0L))

        cancelAlarm(appContext)
        val pendingIntent = pendingIntent(
            appContext,
            PendingIntent.FLAG_UPDATE_CURRENT,
            generation,
        ) ?: return
        val result = AlarmPolicyScheduler.schedule(
            appContext,
            AlarmScheduleRequest(
                alarmType = AlarmManager.RTC_WAKEUP,
                triggerAtMillis = deadline,
                primary = pendingIntent,
                policy = AlarmSchedulePolicy.InexactOnly,
                scheduleKey = SCHEDULE_KEY,
                logTag = TAG,
                logEventPrefix = SCHEDULE_KEY,
                detail = "monitoringSessionGeneration=$generation",
            ),
        )
        if (!result.scheduled) pendingIntent.cancel()
    }

    @Synchronized
    fun pendingIntentExists(context: Context): Boolean =
        existingPendingIntent(context.applicationContext) != null

    @Synchronized
    private fun cancelHandler(context: Context) {
        runnables.remove(context)?.let(handler::removeCallbacks)
    }

    private fun cancelAlarm(context: Context) {
        AlarmPolicyScheduler.cancel(context, SCHEDULE_KEY, existingPendingIntent(context))
    }

    private fun existingPendingIntent(context: Context): PendingIntent? =
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE, 0L)

    private fun pendingIntent(
        context: Context,
        baseFlags: Int,
        monitoringSessionGeneration: Long,
    ): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            Constants.PENDING_INTENT_REQUEST_ACTIVITY_BOOTSTRAP_TIMEOUT,
            Intent(context, ActivityBootstrapTimeoutReceiver::class.java)
                .setAction(ACTION)
                .putExtra(
                    EXTRA_MONITORING_SESSION_GENERATION,
                    monitoringSessionGeneration,
                ),
            flags,
        )
    }

    fun generation(intent: Intent): Long? {
        if (intent.action != ACTION) return null
        val value = intent.getLongExtra(EXTRA_MONITORING_SESSION_GENERATION, -1L)
        return value.takeIf { it >= 0L }
    }
}

class ActivityBootstrapTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val generation = ActivityBootstrapTimeoutScheduler.generation(intent) ?: return
        val pending = goAsync()
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) pending.finish()
        }
        mainHandler.postDelayed(timeout, 10_000L)
        val accepted = ActivityMonitor.handleBootstrapTimeout(
            context.applicationContext,
            generation,
        ) {
            if (completed.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                pending.finish()
            }
        }
        if (!accepted && completed.compareAndSet(false, true)) {
            mainHandler.removeCallbacks(timeout)
            pending.finish()
        }
    }
}

internal enum class ActivityObservationOutcome {
    CONFIDENT_STATIONARY,
    CONFIDENT_MOVING,
    AMBIGUOUS,
}

internal enum class ActivityObservationSource {
    TRANSITION,
    PERIODIC,
}

internal data class ActivityObservation(
    val outcome: ActivityObservationOutcome,
    val source: ActivityObservationSource,
    val eventSummary: String,
)

internal fun classifyPeriodicActivity(
    activityType: Int,
    confidence: Int,
): ActivityObservationOutcome =
    when {
        activityType == DetectedActivity.STILL &&
            confidence >= PERIODIC_STILL_CONFIDENCE_THRESHOLD ->
            ActivityObservationOutcome.CONFIDENT_STATIONARY
        isConfidentMovingActivityType(activityType) &&
            confidence >= PERIODIC_MOVING_CONFIDENCE_THRESHOLD ->
            ActivityObservationOutcome.CONFIDENT_MOVING
        else -> ActivityObservationOutcome.AMBIGUOUS
    }

internal fun activityObservationForCallbacks(
    hasTransitionEvent: Boolean,
    isLikelyStationary: Boolean,
    periodicOutcome: ActivityObservationOutcome?,
    eventSummary: String,
): ActivityObservation? =
    when {
        hasTransitionEvent -> ActivityObservation(
            outcome = if (isLikelyStationary) {
                ActivityObservationOutcome.CONFIDENT_STATIONARY
            } else {
                ActivityObservationOutcome.CONFIDENT_MOVING
            },
            source = ActivityObservationSource.TRANSITION,
            eventSummary = eventSummary,
        )
        periodicOutcome != null -> ActivityObservation(
            outcome = periodicOutcome,
            source = ActivityObservationSource.PERIODIC,
            eventSummary = eventSummary,
        )
        else -> null
    }

internal interface ActivityObservationEffects {
    fun recordClassification(observation: ActivityObservation)
    fun setStationary(stationary: Boolean)
    fun exitDormantFar(eventSummary: String)
    fun updateBalancedDisplacement()
    fun submitWake(observation: ActivityObservation)
    fun stopPulseForStationary()
    fun scheduleLiveness(eventSummary: String)
}

internal fun runActivityObservationEffects(
    observation: ActivityObservation,
    effects: ActivityObservationEffects,
): Boolean {
    effects.recordClassification(observation)
    if (observation.outcome == ActivityObservationOutcome.AMBIGUOUS) return false

    val stationary = observation.outcome == ActivityObservationOutcome.CONFIDENT_STATIONARY
    if (observation.source == ActivityObservationSource.PERIODIC) {
        effects.setStationary(stationary)
    }
    if (!stationary) effects.exitDormantFar(observation.eventSummary)
    effects.updateBalancedDisplacement()
    effects.submitWake(observation)
    if (stationary) {
        effects.stopPulseForStationary()
    } else {
        effects.scheduleLiveness(observation.eventSummary)
    }
    return true
}

private fun isConfidentMovingActivityType(activityType: Int): Boolean =
    activityType == DetectedActivity.WALKING ||
        activityType == DetectedActivity.RUNNING ||
        activityType == DetectedActivity.ON_BICYCLE ||
        activityType == DetectedActivity.IN_VEHICLE ||
        activityType == DetectedActivity.ON_FOOT

private const val PERIODIC_STILL_CONFIDENCE_THRESHOLD = 70
private const val PERIODIC_MOVING_CONFIDENCE_THRESHOLD = 50

internal fun shouldMonitorActivity(
    config: SmartGeofenceConfig,
    hasFences: Boolean,
    pulseCanRun: Boolean,
): Boolean = config.escalationEnabled &&
    hasFences &&
    (config.proximityAdaptiveDisplacementEnabled || pulseCanRun)

internal fun activityCallbackIneligibilityReason(
    state: ActivityRegistrationLifecycleState,
    operationalIneligibilityReason: String?,
    kind: ActivityRegistrationKind,
    payloadElapsedRealtimeNanos: Long?,
): String? {
    if (!state.controllerDesired) return "controller_not_desired"
    operationalIneligibilityReason?.let { return it }
    val part = when (kind) {
        ActivityRegistrationKind.TRANSITION -> state.transition
        ActivityRegistrationKind.PERIODIC -> state.periodic
    }
    if (part.removalInFlight) return "${kind.name.lowercase()}_removal_in_flight"
    if (part.requestInFlight &&
        part.operationMonitoringSessionGeneration != state.monitoringSessionGeneration
    ) {
        return "${kind.name.lowercase()}_request_session_stale"
    }
    if (!part.confirmed) return "${kind.name.lowercase()}_unconfirmed"
    if (!part.belongsToMonitoringSession(state.monitoringSessionGeneration)) {
        return "${kind.name.lowercase()}_confirmed_session_stale"
    }
    if (kind == ActivityRegistrationKind.PERIODIC) {
        val expectedOwner = when (state.periodicMode) {
            ActivityPeriodicMode.BOOTSTRAP -> ActivityMonitor.BOOTSTRAP_PERIODIC_REASON
            ActivityPeriodicMode.PERSISTENT_BACKSTOP -> ActivityMonitor.BASELINE_PERIODIC_REASON
            ActivityPeriodicMode.NONE -> null
        }
        if (expectedOwner == null || state.periodicOwner != expectedOwner) {
            return "periodic_owner_invalid"
        }
    }
    return activityPayloadIneligibilityReason(
        acceptanceBoundaryElapsedRealtimeNanos =
            activityPayloadAcceptanceBoundary(
                kind,
                part.payloadAcceptanceElapsedRealtimeNanos,
            ),
        payloadElapsedRealtimeNanos = payloadElapsedRealtimeNanos,
    )
}

internal fun activityStationaryTimestampIsFresh(
    recordedAtMillis: Long,
    nowMillis: Long,
    ttlMillis: Long,
): Boolean {
    if (recordedAtMillis <= 0L || ttlMillis <= 0L) return false
    val ageMillis = nowMillis - recordedAtMillis
    return ageMillis >= 0L && ageMillis < ttlMillis
}

internal fun runActivityCallbackIfEligible(
    ineligibilityReason: () -> String?,
    onIgnored: (String) -> Unit,
    block: () -> Unit,
): Boolean {
    val reason = ineligibilityReason()
    if (reason != null) {
        onIgnored(reason)
        return false
    }
    block()
    return true
}

internal fun activityPayloadIneligibilityReason(
    acceptanceBoundaryElapsedRealtimeNanos: Long,
    payloadElapsedRealtimeNanos: Long?,
): String? {
    if (acceptanceBoundaryElapsedRealtimeNanos <= 0L) return null
    if (payloadElapsedRealtimeNanos == null || payloadElapsedRealtimeNanos <= 0L) {
        return "payload_elapsed_realtime_missing"
    }
    return if (payloadElapsedRealtimeNanos < acceptanceBoundaryElapsedRealtimeNanos) {
        "payload_predates_registration"
    } else {
        null
    }
}

internal fun activityPayloadAcceptanceBoundary(
    kind: ActivityRegistrationKind,
    elapsedRealtimeNanos: Long,
): Long = if (kind == ActivityRegistrationKind.PERIODIC) {
    elapsedRealtimeNanos - (elapsedRealtimeNanos % 1_000_000L)
} else {
    elapsedRealtimeNanos
}

internal fun elapsedRealtimeMillisToNanos(elapsedRealtimeMillis: Long): Long? {
    if (elapsedRealtimeMillis <= 0L) return null
    if (elapsedRealtimeMillis > Long.MAX_VALUE / 1_000_000L) return null
    return elapsedRealtimeMillis * 1_000_000L
}

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        try {
            handleReceive(appContext, intent)
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Activity update handling failed: ${e.message}",
                e,
            )
        }
    }

    private fun handleReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val events = mutableListOf<String>()
        var hasTransitionEvent = false
        var periodicOutcome: ActivityObservationOutcome? = null
        var periodicStationarySource = ActivityStationarySource.BACKSTOP_PERIODIC
        val hasTransitionPayload = ActivityTransitionResult.hasResult(intent)
        val hasPeriodicPayload = ActivityRecognitionResult.hasResult(intent)

        if (hasTransitionPayload) {
            ActivityTransitionResult.extractResult(intent)?.let { result ->
                var ignoredReason: String? = null
                for (event in result.transitionEvents) {
                    runActivityCallbackIfEligible(
                        ineligibilityReason = {
                            ActivityMonitor.callbackIneligibilityReason(
                                appContext,
                                ActivityRegistrationKind.TRANSITION,
                                event.elapsedRealTimeNanos.takeIf { it > 0L },
                            )
                        },
                        onIgnored = { reason ->
                            if (ignoredReason == null) ignoredReason = reason
                        },
                    ) {
                        hasTransitionEvent = true
                        events += handleTransitionEvent(appContext, event)
                    }
                }
                ignoredReason?.let { reason ->
                    recordIgnoredCallback(
                        appContext,
                        ActivityRegistrationKind.TRANSITION,
                        reason,
                    )
                }
            }
        }
        if (hasPeriodicPayload) {
            val result = ActivityRecognitionResult.extractResult(intent)
            periodicStationarySource = ActivityMonitor.periodicStationarySource(appContext)
            runActivityCallbackIfEligible(
                ineligibilityReason = {
                    ActivityMonitor.callbackIneligibilityReason(
                        appContext,
                        ActivityRegistrationKind.PERIODIC,
                        result?.let {
                            elapsedRealtimeMillisToNanos(it.elapsedRealtimeMillis)
                        },
                    )
                },
                onIgnored = { reason ->
                    recordIgnoredCallback(
                        appContext,
                        ActivityRegistrationKind.PERIODIC,
                        reason,
                    )
                },
            ) {
                ActivityMonitor.recordPeriodicResult(appContext)
                result?.let { acceptedResult ->
                    ActivityMonitor.recordPeriodicResultForLifecycle(
                        appContext,
                        "activity_recognition_result",
                    )
                    handleActivityResult(acceptedResult)?.let { observation ->
                        periodicOutcome = observation.outcome
                        events.add(observation.eventSummary)
                    }
                }
            }
        }
        if (events.isEmpty()) return

        val eventSummary = events.joinToString(",")
        val observation = activityObservationForCallbacks(
            hasTransitionEvent = hasTransitionEvent,
            isLikelyStationary = hasTransitionEvent &&
                ActivityMonitor.isLikelyStationary(appContext),
            periodicOutcome = periodicOutcome,
            eventSummary = eventSummary,
        ) ?: return
        runActivityObservationEffects(
            observation,
            object : ActivityObservationEffects {
                override fun recordClassification(observation: ActivityObservation) {
                    SmartGeofenceDiagnostics.recordTrace(
                        appContext,
                        stage = "activity_classification",
                        reasonCode = observation.outcome.name.lowercase(),
                        source = observation.source.name.lowercase(),
                        extras = mapOf("event" to observation.eventSummary),
                    )
                    SmartGeofenceLogger.d(
                        appContext,
                        TAG,
                        "Activity ${observation.source.name.lowercase()} classification=" +
                            "${observation.outcome.name.lowercase()} " +
                            "events=${observation.eventSummary}.",
                    )
                }

                override fun setStationary(stationary: Boolean) {
                    ActivityMonitor.setStationary(
                        appContext,
                        stationary,
                        periodicStationarySource,
                    )
                }

                override fun exitDormantFar(eventSummary: String) {
                    DormantFarController.exitForActivityMoving(appContext, eventSummary)
                }

                override fun updateBalancedDisplacement() {
                    FusedLocationManager.updateBalancedDisplacement(appContext)
                }

                override fun submitWake(observation: ActivityObservation) {
                    val transition = observation.source == ActivityObservationSource.TRANSITION
                    WakeEventCoordinator.submit(
                        appContext,
                        WakeTask(
                            source = WakeSource.ACTIVITY,
                            action = WakeAction.DRAIN_FOREGROUND_QUEUE,
                            exemption = if (transition) {
                                WakeExemption.ACTIVITY_TRANSITION
                            } else {
                                WakeExemption.NONE
                            },
                            reason = if (transition) {
                                "activity_transition"
                            } else {
                                "activity_update"
                            },
                            event = observation.eventSummary,
                        )
                    )
                }

                override fun stopPulseForStationary() {
                    ProximityPulseController.onStationary(appContext)
                }

                override fun scheduleLiveness(eventSummary: String) {
                    FusedLocationLivenessTrigger.schedule(appContext, eventSummary)
                }
            },
        )
    }

    private fun recordIgnoredCallback(
        context: Context,
        kind: ActivityRegistrationKind,
        reason: String,
    ) {
        ActivityMonitor.recordIgnoredCallback(context, kind, reason)
        SmartGeofenceLogger.d(
            context,
            TAG,
            "Activity ${kind.name.lowercase()} callback ignored reason=$reason.",
        )
    }

    private fun handleTransitionEvent(
        context: Context,
        event: ActivityTransitionEvent,
    ): String {
        val entering = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        when (event.activityType) {
            DetectedActivity.STILL -> ActivityMonitor.setStationary(
                context,
                entering,
                ActivityStationarySource.TRANSITION,
            )
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_FOOT ->
                if (entering) ActivityMonitor.setStationary(
                    context,
                    false,
                    ActivityStationarySource.TRANSITION,
                )
        }
        return "${activityName(event.activityType)}:${transitionName(event.transitionType)}"
    }

    private fun handleActivityResult(
        result: ActivityRecognitionResult,
    ): ActivityObservation? {
        val activity = result.mostProbableActivity ?: return null
        val confidence = activity.confidence
        return ActivityObservation(
            outcome = classifyPeriodicActivity(activity.type, confidence),
            source = ActivityObservationSource.PERIODIC,
            eventSummary = "${activityName(activity.type)}:$confidence",
        )
    }

    private fun activityName(activityType: Int): String =
        when (activityType) {
            DetectedActivity.STILL -> "still"
            DetectedActivity.WALKING -> "walking"
            DetectedActivity.RUNNING -> "running"
            DetectedActivity.ON_BICYCLE -> "bicycle"
            DetectedActivity.IN_VEHICLE -> "vehicle"
            DetectedActivity.ON_FOOT -> "on_foot"
            DetectedActivity.TILTING -> "tilting"
            DetectedActivity.UNKNOWN -> "unknown"
            else -> activityType.toString()
        }

    private fun transitionName(transitionType: Int): String =
        when (transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "enter"
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "exit"
            else -> transitionType.toString()
        }

    companion object {
        private const val TAG = "ActivityReceiver"
    }
}
