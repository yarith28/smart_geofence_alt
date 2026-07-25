package com.yarithdev.smart_geofence.logging

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionController
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionMode
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionReceiver
import com.yarithdev.smart_geofence.BootReceiver
import com.yarithdev.smart_geofence.BuildConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.AndroidPackageManagerCompat
import com.yarithdev.smart_geofence.core.safeBooleanOrNull
import com.yarithdev.smart_geofence.core.safeLongOrNull
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.fused.FusedLocationLiveness
import com.yarithdev.smart_geofence.fused.FusedLocationManager
import com.yarithdev.smart_geofence.monitoring.MonitoringStopStateStore
import com.yarithdev.smart_geofence.proximitypulse.AdaptivePulseRate
import com.yarithdev.smart_geofence.proximity.ProximityAlarmReceiver
import com.yarithdev.smart_geofence.proximity.ProximityAlarmScheduler
import com.yarithdev.smart_geofence.proximitypulse.ProximityPulseStateStore
import com.yarithdev.smart_geofence.foreground.ForegroundLaunchState
import com.yarithdev.smart_geofence.foreground.ForegroundStartCoordinator
import com.yarithdev.smart_geofence.foreground.ForegroundServiceLaunchReceiver
import com.yarithdev.smart_geofence.activity.ActivityReceiver
import com.yarithdev.smart_geofence.activity.ActivityMonitor
import com.yarithdev.smart_geofence.activity.ActivityRecognitionPermissionController
import com.yarithdev.smart_geofence.activity.belongsToMonitoringSession
import com.yarithdev.smart_geofence.confirm.ForegroundServiceRearm
import com.yarithdev.smart_geofence.confirm.LocationConfirmAlarmReceiver
import com.yarithdev.smart_geofence.confirm.LocationConfirmManager
import com.yarithdev.smart_geofence.confirm.LocationDisabledRecoveryScheduler
import com.yarithdev.smart_geofence.confirm.LocationProviderChangedReceiver
import com.yarithdev.smart_geofence.confirm.LocationConfirmService
import com.yarithdev.smart_geofence.confirm.NativeEnterFallbackReceiver
import com.yarithdev.smart_geofence.confirm.NativeEnterPendingStore
import com.yarithdev.smart_geofence.confirm.NativeExitFallbackReceiver
import com.yarithdev.smart_geofence.confirm.NativeExitPendingStore
import com.yarithdev.smart_geofence.delivery.EventDedupStore
import com.yarithdev.smart_geofence.dormant.DormantFarProbeReceiver
import com.yarithdev.smart_geofence.dormant.DormantFarProbeScheduler
import com.yarithdev.smart_geofence.dormant.DormantFarStateStore
import com.yarithdev.smart_geofence.proximity.FusedLocationUpdateReceiver
import com.yarithdev.smart_geofence.proximity.AdaptiveProximityDisplacement
import com.yarithdev.smart_geofence.recovery.BootRecoveryCoordinator
import com.yarithdev.smart_geofence.recovery.BootRecoveryReceiver
import com.yarithdev.smart_geofence.recovery.RecoveryAlarmReceiver
import com.yarithdev.smart_geofence.recovery.RecoveryScheduler
import com.yarithdev.smart_geofence.store.FenceObservationStore
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.time.captureAndroidMonotonicTime
import com.yarithdev.smart_geofence.transition.PendingNativeTransition
import com.yarithdev.smart_geofence.wake.BackgroundQueue
import com.yarithdev.smart_geofence.wake.ForegroundQueue
import com.yarithdev.smart_geofence.wake.WakeEventCoordinator
import org.json.JSONObject

object SmartGeofenceDiagnostics {
    private val PROCESS_STARTED_AT_MILLIS = System.currentTimeMillis()
    private const val SMART_STATUS_SCHEMA_VERSION = 1

    private const val COUNTER_PREFIX = "diagnostic_counter_"

    private const val PROCESS_START_AT = "diagnostic_process_start_at"
    private const val LAST_ALIVE_AT = "diagnostic_last_alive_at"
    private const val LAST_ALIVE_STATUS = "diagnostic_last_alive_status"

    private const val LAST_LOCATION_WAKE_AT = "diagnostic_last_location_wake_at"
    private const val LAST_LOCATION_WAKE_SOURCE = "diagnostic_last_location_wake_source"
    private const val LAST_LOCATION_WAKE_PROVIDER = "diagnostic_last_location_wake_provider"
    private const val LAST_LOCATION_WAKE_ACCURACY = "diagnostic_last_location_wake_accuracy"
    private const val LAST_LOCATION_WAKE_AGE = "diagnostic_last_location_wake_age"
    private const val LAST_LOCATION_WAKE_NEAREST_FENCE_ID =
        "diagnostic_last_location_wake_nearest_fence_id"
    private const val LAST_LOCATION_WAKE_EDGE_DISTANCE =
        "diagnostic_last_location_wake_edge_distance"
    private const val LAST_LOCATION_WAKE_WITHIN_PROXIMITY =
        "diagnostic_last_location_wake_within_proximity"

    private const val LAST_CONFIRM_QUEUE_AT = "diagnostic_last_confirm_queue_at"
    private const val LAST_CONFIRM_QUEUE_REQUEST_ID = "diagnostic_last_confirm_queue_request_id"
    private const val LAST_CONFIRM_QUEUE_FENCE_ID = "diagnostic_last_confirm_queue_fence_id"
    private const val LAST_CONFIRM_QUEUE_IS_PROXIMITY =
        "diagnostic_last_confirm_queue_is_proximity"
    private const val LEGACY_LAST_CONFIRM_QUEUE_IS_NEAREST =
        "diagnostic_last_confirm_queue_is_nearest"
    private const val LAST_CONFIRM_QUEUE_SOURCE = "diagnostic_last_confirm_queue_source"
    private const val LAST_CONFIRM_QUEUE_AGE = "diagnostic_last_confirm_queue_age"
    private const val LAST_CONFIRM_REQUEST_AT = "diagnostic_last_confirm_request_at"
    private const val LAST_CONFIRM_SOURCE = "diagnostic_last_confirm_source"
    private const val LAST_CONFIRM_PRIORITY = "diagnostic_last_confirm_priority"
    private const val LAST_CONFIRM_TIMEOUT = "diagnostic_last_confirm_timeout"
    private const val LAST_CONFIRM_RESULT = "diagnostic_last_confirm_result"
    private const val LAST_CONFIRM_ELAPSED = "diagnostic_last_confirm_elapsed"
    private const val LAST_CONFIRM_LOCATION_PROVIDER = "diagnostic_last_confirm_location_provider"
    private const val LAST_CONFIRM_LOCATION_ACCURACY = "diagnostic_last_confirm_location_accuracy"
    private const val LAST_CONFIRM_LOCATION_AGE = "diagnostic_last_confirm_location_age"
    private const val LAST_CONFIRM_FAILURE_MESSAGE = "diagnostic_last_confirm_failure_message"
    private const val LAST_NATIVE_EXIT_CONFIRM_TIMING_MODE =
        "diagnostic_last_native_exit_confirm_timing_mode"
    private const val LAST_NATIVE_EXIT_CONFIRM_TIMING_REASON =
        "diagnostic_last_native_exit_confirm_timing_reason"
    private const val LAST_NATIVE_EXIT_CONFIRM_TIMING_AT =
        "diagnostic_last_native_exit_confirm_timing_at"
    private const val LAST_NATIVE_ENTER_CONFIRM_TIMING_MODE =
        "diagnostic_last_native_enter_confirm_timing_mode"
    private const val LAST_NATIVE_ENTER_CONFIRM_TIMING_REASON =
        "diagnostic_last_native_enter_confirm_timing_reason"
    private const val LAST_NATIVE_ENTER_CONFIRM_TIMING_AT =
        "diagnostic_last_native_enter_confirm_timing_at"

    private const val LAST_BOUNDARY_DECISION_AT = "diagnostic_last_boundary_decision_at"
    private const val LAST_BOUNDARY_DECISION = "diagnostic_last_boundary_decision"
    private const val LAST_BOUNDARY_DECISION_FENCE_ID =
        "diagnostic_last_boundary_decision_fence_id"
    private const val LAST_BOUNDARY_DECISION_SOURCE = "diagnostic_last_boundary_decision_source"
    private const val LAST_BOUNDARY_DISTANCE = "diagnostic_last_boundary_distance"
    private const val LAST_BOUNDARY_RADIUS = "diagnostic_last_boundary_radius"
    private const val LAST_BOUNDARY_EDGE_DISTANCE = "diagnostic_last_boundary_edge_distance"
    private const val LAST_BOUNDARY_ACCURACY = "diagnostic_last_boundary_accuracy"
    private const val LAST_BOUNDARY_MOCK = "diagnostic_last_boundary_mock"
    private const val LAST_BOUNDARY_QUEUED_EVENT = "diagnostic_last_boundary_queued_event"

    private const val LAST_MOCK_LOCATION_DECISION_AT =
        "diagnostic_last_mock_location_decision_at"
    private const val LAST_MOCK_LOCATION_POLICY = "diagnostic_last_mock_location_policy"
    private const val LAST_MOCK_LOCATION_ACTION = "diagnostic_last_mock_location_action"
    private const val LAST_MOCK_LOCATION_SOURCE = "diagnostic_last_mock_location_source"
    private const val LAST_MOCK_LOCATION_PROVIDER = "diagnostic_last_mock_location_provider"
    private const val LAST_MOCK_LOCATION_ACCURACY = "diagnostic_last_mock_location_accuracy"
    private const val LAST_MOCK_LOCATION_AGE = "diagnostic_last_mock_location_age"
    private const val LAST_MOCK_LOCATION_SUPPRESSED = "diagnostic_last_mock_location_suppressed"

    private const val LAST_EVENT_PROCESSOR_AT = "diagnostic_last_event_processor_at"
    private const val LAST_EVENT_PROCESSOR_INPUT = "diagnostic_last_event_processor_input"
    private const val LAST_EVENT_PROCESSOR_SOURCE = "diagnostic_last_event_processor_source"
    private const val LAST_EVENT_PROCESSOR_RESULT = "diagnostic_last_event_processor_result"
    private const val LAST_EVENT_PROCESSOR_CANDIDATE_COUNT =
        "diagnostic_last_event_processor_candidate_count"

    private const val LAST_SMART_CALLBACK_AT = "diagnostic_last_smart_callback_at"
    private const val LAST_SMART_CALLBACK_EVENT = "diagnostic_last_smart_callback_event"
    private const val LAST_SMART_CALLBACK_FENCE_ID = "diagnostic_last_smart_callback_fence_id"
    private const val LAST_SMART_CALLBACK_SOURCE = "diagnostic_last_smart_callback_source"
    private const val LAST_SMART_CALLBACK_RESULT = "diagnostic_last_smart_callback_result"
    private const val LAST_SMART_CALLBACK_ENQUEUED_AT =
        "diagnostic_last_smart_callback_enqueued_at"
    private const val LAST_SMART_CALLBACK_EVENT_AT =
        "diagnostic_last_smart_callback_event_at"
    private const val LAST_SMART_CALLBACK_TIMESTAMP_SOURCE =
        "diagnostic_last_smart_callback_timestamp_source"
    private const val LAST_SMART_CALLBACK_DELIVERY_PATH =
        "diagnostic_last_smart_callback_delivery_path"
    private const val LAST_SMART_CALLBACK_TRIGGER_TO_DELIVERY_LATENCY =
        "diagnostic_last_smart_callback_trigger_to_delivery_latency"
    private const val LAST_SMART_CALLBACK_DEVICE_IDLE_AT_DELIVERY =
        "diagnostic_last_smart_callback_device_idle_at_delivery"

    private const val LAST_CALLBACK_DISPATCH_AT = "diagnostic_last_callback_dispatch_at"
    private const val LAST_CALLBACK_DISPATCH_EVENT = "diagnostic_last_callback_dispatch_event"
    private const val LAST_CALLBACK_DISPATCH_FENCE_ID =
        "diagnostic_last_callback_dispatch_fence_id"
    private const val LAST_CALLBACK_DISPATCH_RESULT =
        "diagnostic_last_callback_dispatch_result"
    private const val LEGACY_LAST_CALLBACK_DISPATCH_CALLBACK_HANDLE =
        "diagnostic_last_callback_dispatch_callback_handle"
    private const val LAST_CALLBACK_DISPATCH_RETRY_ON_FAILURE =
        "diagnostic_last_callback_dispatch_retry_on_failure"
    private const val LAST_CALLBACK_DISPATCH_EVENT_AT =
        "diagnostic_last_callback_dispatch_event_at"
    private const val LAST_CALLBACK_DISPATCH_TIMESTAMP_SOURCE =
        "diagnostic_last_callback_dispatch_timestamp_source"
    private const val LAST_CALLBACK_DISPATCH_TIME_REASON_CODE =
        "diagnostic_last_callback_dispatch_time_reason_code"
    private const val LAST_CALLBACK_DISPATCH_TIME_TRUSTED =
        "diagnostic_last_callback_dispatch_time_trusted"
    private const val LAST_CALLBACK_DISPATCH_TIME_REJECTION_REASON =
        "diagnostic_last_callback_dispatch_time_rejection_reason"
    private const val LAST_CALLBACK_DISPATCH_EVIDENCE_QUALITY =
        "diagnostic_last_callback_dispatch_evidence_quality"
    private const val LAST_CALLBACK_DISPATCH_ERROR =
        "diagnostic_last_callback_dispatch_error"

    private const val LAST_BOOT_RECOVERY_SOURCE = "diagnostic_last_boot_recovery_source"
    private const val LAST_BOOT_RECOVERY_ACTION = "diagnostic_last_boot_recovery_action"
    private const val LAST_BOOT_RECOVERY_STARTED_AT =
        "diagnostic_last_boot_recovery_started_at"
    private const val LAST_BOOT_RECOVERY_FINISHED_AT =
        "diagnostic_last_boot_recovery_finished_at"
    private const val LAST_BOOT_RECOVERY_RESULT = "diagnostic_last_boot_recovery_result"
    private const val LAST_BOOT_RECOVERY_NATIVE_FENCE_COUNT =
        "diagnostic_last_boot_recovery_native_fence_count"
    private const val LAST_BOOT_RECOVERY_SMART_FENCE_COUNT =
        "diagnostic_last_boot_recovery_smart_fence_count"
    private const val LAST_BOOT_RECOVERY_ELAPSED =
        "diagnostic_last_boot_recovery_elapsed"
    private const val LAST_BOOT_RECOVERY_FAILURE_MESSAGE =
        "diagnostic_last_boot_recovery_failure_message"
    private const val LAST_BOOT_FOLLOW_UP_SCHEDULED_AT =
        "diagnostic_last_boot_follow_up_scheduled_at"
    private const val LAST_BOOT_FOLLOW_UP_TRIGGER_AT =
        "diagnostic_last_boot_follow_up_trigger_at"
    private const val LAST_BOOT_FOLLOW_UP_ACTION =
        "diagnostic_last_boot_follow_up_action"
    private const val LAST_BOOT_FOLLOW_UP_RESULT =
        "diagnostic_last_boot_follow_up_result"
    private const val LAST_BOOT_FOLLOW_UP_FAILURE_MESSAGE =
        "diagnostic_last_boot_follow_up_failure_message"
    private const val LAST_BROADCAST_DEADLINE_FINISHED_AT =
        "diagnostic_last_broadcast_deadline_finished_at"
    private const val LAST_BROADCAST_DEADLINE_REASON =
        "diagnostic_last_broadcast_deadline_reason"
    private const val LAST_BROADCAST_DEADLINE_TAG =
        "diagnostic_last_broadcast_deadline_tag"
    private const val LAST_RECOVERY_MAIN_THREAD_TIMEOUT_AT =
        "diagnostic_last_recovery_main_thread_timeout_at"
    private const val LAST_RECOVERY_MAIN_THREAD_TIMEOUT_SOURCE =
        "diagnostic_last_recovery_main_thread_timeout_source"
    private const val LAST_RECOVERY_MAIN_THREAD_TIMEOUT_REASON =
        "diagnostic_last_recovery_main_thread_timeout_reason"

    fun recordHeartbeat(context: Context, status: String) {
        prefs(context).edit()
            .putLong(PROCESS_START_AT, PROCESS_STARTED_AT_MILLIS)
            .putLong(LAST_ALIVE_AT, System.currentTimeMillis())
            .putString(LAST_ALIVE_STATUS, status)
            .apply()
    }

    fun recordLocationWake(
        context: Context,
        source: String,
        location: Location,
        nearestFenceId: String?,
        edgeDistanceMeters: Double?,
        withinProximity: Boolean
    ) {
        recordHeartbeat(context, "location_wake:$source")
        prefs(context).edit()
            .putLong(LAST_LOCATION_WAKE_AT, System.currentTimeMillis())
            .putString(LAST_LOCATION_WAKE_SOURCE, source)
            .putNullableString(LAST_LOCATION_WAKE_PROVIDER, location.provider)
            .putNullableDoubleString(LAST_LOCATION_WAKE_ACCURACY, accuracyMeters(location))
            .putNullableLong(LAST_LOCATION_WAKE_AGE, ageMillis(location))
            .putNullableString(LAST_LOCATION_WAKE_NEAREST_FENCE_ID, nearestFenceId)
            .putNullableDoubleString(LAST_LOCATION_WAKE_EDGE_DISTANCE, edgeDistanceMeters)
            .putBoolean(LAST_LOCATION_WAKE_WITHIN_PROXIMITY, withinProximity)
            .apply()
        recordJournal(
            context,
            stage = "location_wake",
            reasonCode = if (withinProximity) "within_proximity" else "outside_proximity",
            fenceId = nearestFenceId,
            source = source,
            extras = linkedMapOf(
                "provider" to location.provider,
                "accuracyMeters" to accuracyMeters(location),
                "ageMillis" to ageMillis(location),
                "edgeDistanceMeters" to edgeDistanceMeters,
            ),
        )
    }

    fun recordConfirmQueueRequest(
        context: Context,
        requestId: Long,
        fenceId: String?,
        isProximity: Boolean,
        source: String,
        ageMillis: Long,
        traceId: String? = null,
    ) {
        prefs(context).edit()
            .putLong(LAST_CONFIRM_QUEUE_AT, System.currentTimeMillis())
            .putLong(LAST_CONFIRM_QUEUE_REQUEST_ID, requestId)
            .putNullableString(LAST_CONFIRM_QUEUE_FENCE_ID, fenceId)
            .putBoolean(LAST_CONFIRM_QUEUE_IS_PROXIMITY, isProximity)
            .remove(LEGACY_LAST_CONFIRM_QUEUE_IS_NEAREST)
            .putString(LAST_CONFIRM_QUEUE_SOURCE, source)
            .putLong(LAST_CONFIRM_QUEUE_AGE, ageMillis)
            .apply()
        recordJournal(
            context,
            stage = "confirm_enqueued",
            reasonCode = if (isProximity) "proximity" else "transition",
            fenceId = fenceId,
            source = source,
            traceId = traceId,
            extras = linkedMapOf(
                "requestId" to requestId,
                "ageMillis" to ageMillis,
            ),
        )
    }

    fun recordConfirmRequest(
        context: Context,
        source: String,
        priority: String,
        timeoutMillis: Long,
        requestedAtMillis: Long,
        traceId: String? = null,
    ) {
        prefs(context).edit()
            .putLong(LAST_CONFIRM_REQUEST_AT, requestedAtMillis)
            .putString(LAST_CONFIRM_SOURCE, source)
            .putString(LAST_CONFIRM_PRIORITY, priority)
            .putLong(LAST_CONFIRM_TIMEOUT, timeoutMillis)
            .putString(LAST_CONFIRM_RESULT, "requested")
            .remove(LAST_CONFIRM_ELAPSED)
            .remove(LAST_CONFIRM_LOCATION_PROVIDER)
            .remove(LAST_CONFIRM_LOCATION_ACCURACY)
            .remove(LAST_CONFIRM_LOCATION_AGE)
            .remove(LAST_CONFIRM_FAILURE_MESSAGE)
            .apply()
        recordJournal(
            context,
            stage = "confirm_request",
            reasonCode = "requested",
            source = source,
            traceId = traceId,
            extras = linkedMapOf(
                "priority" to priority,
                "timeoutMillis" to timeoutMillis,
            ),
        )
    }

    fun recordConfirmResult(
        context: Context,
        source: String,
        result: String,
        elapsedMillis: Long,
        location: Location? = null,
        failureMessage: String? = null,
        traceId: String? = null,
    ) {
        prefs(context).edit()
            .putString(LAST_CONFIRM_SOURCE, source)
            .putString(LAST_CONFIRM_RESULT, result)
            .putLong(LAST_CONFIRM_ELAPSED, elapsedMillis.coerceAtLeast(0L))
            .putNullableString(LAST_CONFIRM_LOCATION_PROVIDER, location?.provider)
            .putNullableDoubleString(LAST_CONFIRM_LOCATION_ACCURACY, location?.let(::accuracyMeters))
            .putNullableLong(LAST_CONFIRM_LOCATION_AGE, location?.let(::ageMillis))
            .putNullableString(LAST_CONFIRM_FAILURE_MESSAGE, failureMessage)
            .apply()
        incrementCounter(context, "confirm_result_$result")
        recordJournal(
            context,
            stage = "confirm_result",
            reasonCode = result,
            source = source,
            traceId = traceId,
            extras = linkedMapOf(
                "elapsedMillis" to elapsedMillis.coerceAtLeast(0L),
                "provider" to location?.provider,
                "accuracyMeters" to location?.let(::accuracyMeters),
                "ageMillis" to location?.let(::ageMillis),
                "failureMessage" to failureMessage,
            ),
        )
    }

    fun recordNativeExitConfirmTiming(
        context: Context,
        mode: String,
        reason: String,
    ) {
        prefs(context.applicationContext).edit()
            .putString(LAST_NATIVE_EXIT_CONFIRM_TIMING_MODE, mode)
            .putString(LAST_NATIVE_EXIT_CONFIRM_TIMING_REASON, reason)
            .putLong(LAST_NATIVE_EXIT_CONFIRM_TIMING_AT, System.currentTimeMillis())
            .apply()
        recordJournal(
            context,
            stage = "native_exit_confirm_timing",
            reasonCode = reason,
            extras = linkedMapOf("mode" to mode),
        )
    }

    fun recordNativeEnterConfirmTiming(
        context: Context,
        mode: String,
        reason: String,
    ) {
        prefs(context.applicationContext).edit()
            .putString(LAST_NATIVE_ENTER_CONFIRM_TIMING_MODE, mode)
            .putString(LAST_NATIVE_ENTER_CONFIRM_TIMING_REASON, reason)
            .putLong(LAST_NATIVE_ENTER_CONFIRM_TIMING_AT, System.currentTimeMillis())
            .apply()
        recordJournal(
            context,
            stage = "native_enter_confirm_timing",
            reasonCode = reason,
            extras = linkedMapOf("mode" to mode),
        )
    }

    internal fun recordTransitionCandidateEvidence(
        context: Context,
        pending: PendingNativeTransition,
        reason: String,
    ) {
        if (!pending.validationRequired) return
        recordJournal(
            context = context,
            stage = "transition_validation_candidate",
            reasonCode = reason,
            traceId = pending.traceId,
            fenceId = pending.fenceId,
            event = pending.direction.name.lowercase(),
            source = pending.source,
            extras = linkedMapOf(
                "transitionInstanceId" to pending.instanceId,
                "direction" to pending.direction.name,
                "candidateSource" to pending.source,
                "candidateEventAtMillis" to pending.triggeredAtMillis,
                "candidateMonotonicMillis" to pending.eventMonotonicMillis,
                "candidateBootCount" to
                    (pending.androidBootCount ?: pending.eligibilityBootCount),
                "candidateLocationTimeMillis" to pending.candidateLocationTimeMillis,
                "candidateLocationElapsedRealtimeNanos" to
                    pending.candidateLocationElapsedRealtimeNanos,
                "candidateAccuracyMeters" to pending.accuracyMeters,
                "candidateIsMock" to pending.isMock,
                "candidateHasLocation" to
                    (pending.latitude != null && pending.longitude != null),
                "eligibleAtMillis" to pending.eligibleAtMillis,
                "eligibleAtElapsedRealtimeMillis" to
                    pending.eligibleAtElapsedRealtimeMillis,
                "eligibilityBootCount" to pending.eligibilityBootCount,
                "minimumDelayMillis" to pending.minimumDelayMillis,
                "confirmationNotBeforeMillis" to pending.confirmationNotBeforeMillis,
            ),
        )
    }

    internal fun recordTransitionConfirmationEvidence(
        context: Context,
        pending: PendingNativeTransition,
        confirmationSource: String,
        confirmationLocation: Location?,
        confirmationIsMock: Boolean,
        confirmedEventName: String?,
        eventId: String? = null,
        traceId: String? = null,
    ) {
        if (!pending.validationRequired) return
        val confirmedAtMillis = System.currentTimeMillis()
        val monotonic = captureAndroidMonotonicTime(context.applicationContext)
        recordJournal(
            context = context,
            stage = "transition_validation_confirmation",
            reasonCode = "confirmed",
            traceId = traceId ?: pending.traceId,
            eventId = eventId,
            fenceId = pending.fenceId,
            event = confirmedEventName ?: pending.direction.name.lowercase(),
            source = confirmationSource,
            extras = linkedMapOf(
                "transitionInstanceId" to pending.instanceId,
                "direction" to pending.direction.name,
                "confirmationSource" to confirmationSource,
                "confirmationLocationWallTimeMillis" to
                    confirmationLocation?.time?.takeIf { it > 0L },
                "confirmationLocationElapsedRealtimeNanos" to
                    confirmationLocation?.elapsedRealtimeNanos?.takeIf { it > 0L },
                "confirmationBootCount" to monotonic.bootCount,
                "confirmationAccuracyMeters" to
                    confirmationLocation?.let(::accuracyMeters),
                "confirmationIsMock" to confirmationIsMock,
                "confirmationHasLocation" to (confirmationLocation != null),
                "eligibleAtMillis" to pending.eligibleAtMillis,
                "eligibleAtElapsedRealtimeMillis" to
                    pending.eligibleAtElapsedRealtimeMillis,
                "eligibilityBootCount" to pending.eligibilityBootCount,
                "minimumDelayMillis" to pending.minimumDelayMillis,
                "confirmationNotBeforeMillis" to pending.confirmationNotBeforeMillis,
                "confirmedAtMillis" to confirmedAtMillis,
                "confirmedEventName" to confirmedEventName,
            ),
        )
    }

    fun recordBoundaryDecision(
        context: Context,
        fenceId: String,
        source: String,
        decision: String,
        distanceMeters: Double,
        radiusMeters: Double,
        edgeDistanceMeters: Double,
        accuracyMeters: Double,
        isMock: Boolean,
        queuedEvent: String?
    ) {
        prefs(context).edit()
            .putLong(LAST_BOUNDARY_DECISION_AT, System.currentTimeMillis())
            .putString(LAST_BOUNDARY_DECISION, decision)
            .putString(LAST_BOUNDARY_DECISION_FENCE_ID, fenceId)
            .putString(LAST_BOUNDARY_DECISION_SOURCE, source)
            .putNullableDoubleString(LAST_BOUNDARY_DISTANCE, distanceMeters)
            .putNullableDoubleString(LAST_BOUNDARY_RADIUS, radiusMeters)
            .putNullableDoubleString(LAST_BOUNDARY_EDGE_DISTANCE, edgeDistanceMeters)
            .putNullableDoubleString(LAST_BOUNDARY_ACCURACY, accuracyMeters)
            .putBoolean(LAST_BOUNDARY_MOCK, isMock)
            .putNullableString(LAST_BOUNDARY_QUEUED_EVENT, queuedEvent)
            .apply()
        recordJournal(
            context,
            stage = "boundary_decision",
            reasonCode = decision,
            fenceId = fenceId,
            event = queuedEvent,
            source = source,
            extras = linkedMapOf(
                "distanceMeters" to distanceMeters,
                "radiusMeters" to radiusMeters,
                "edgeDistanceMeters" to edgeDistanceMeters,
                "accuracyMeters" to accuracyMeters,
                "isMock" to isMock,
            ),
        )
    }

    fun recordMockLocationPolicy(
        context: Context,
        policy: String,
        action: String,
        source: String,
        provider: String?,
        accuracyMeters: Double?,
        ageMillis: Long?,
        suppressed: Boolean,
    ) {
        prefs(context).edit()
            .putLong(LAST_MOCK_LOCATION_DECISION_AT, System.currentTimeMillis())
            .putString(LAST_MOCK_LOCATION_POLICY, policy)
            .putString(LAST_MOCK_LOCATION_ACTION, action)
            .putString(LAST_MOCK_LOCATION_SOURCE, source)
            .putNullableString(LAST_MOCK_LOCATION_PROVIDER, provider)
            .putNullableDoubleString(LAST_MOCK_LOCATION_ACCURACY, accuracyMeters)
            .putNullableLong(LAST_MOCK_LOCATION_AGE, ageMillis)
            .putBoolean(LAST_MOCK_LOCATION_SUPPRESSED, suppressed)
            .apply()
        recordJournal(
            context,
            stage = "mock_location_policy",
            reasonCode = action,
            source = source,
            extras = linkedMapOf(
                "policy" to policy,
                "provider" to provider,
                "accuracyMeters" to accuracyMeters,
                "ageMillis" to ageMillis,
                "suppressed" to suppressed,
            ),
        )
    }

    fun recordSmartCallback(
        context: Context,
        fenceId: String,
        event: String,
        source: String,
        result: String,
        enqueuedAtMillis: Long? = null,
        eventAtMillis: Long? = null,
        timestampSource: String? = null,
        deliveryPath: String? = null,
        triggerToDeliveryLatencyMillis: Long? = null,
        deviceIdleModeAtDelivery: Boolean? = null,
        traceId: String? = null,
        eventId: String? = null,
        transitionInstanceId: String? = null,
    ) {
        prefs(context).edit()
            .putLong(LAST_SMART_CALLBACK_AT, System.currentTimeMillis())
            .putString(LAST_SMART_CALLBACK_EVENT, event)
            .putString(LAST_SMART_CALLBACK_FENCE_ID, fenceId)
            .putString(LAST_SMART_CALLBACK_SOURCE, source)
            .putString(LAST_SMART_CALLBACK_RESULT, result)
            .putNullableLong(LAST_SMART_CALLBACK_ENQUEUED_AT, enqueuedAtMillis)
            .putNullableLong(LAST_SMART_CALLBACK_EVENT_AT, eventAtMillis)
            .putNullableString(LAST_SMART_CALLBACK_TIMESTAMP_SOURCE, timestampSource)
            .putNullableString(LAST_SMART_CALLBACK_DELIVERY_PATH, deliveryPath)
            .putNullableLong(
                LAST_SMART_CALLBACK_TRIGGER_TO_DELIVERY_LATENCY,
                triggerToDeliveryLatencyMillis,
            )
            .putNullableBoolean(
                LAST_SMART_CALLBACK_DEVICE_IDLE_AT_DELIVERY,
                deviceIdleModeAtDelivery,
            )
            .apply()
        incrementCounter(context, "smart_callback_$result")
        when (result) {
            "enqueue_succeeded" -> {
                incrementCounter(context, "events_enqueued")
                deliveryPath?.let { incrementCounter(context, "event_delivery_path_$it") }
            }
            "enqueue_failed" -> incrementCounter(context, "event_enqueue_failures")
            "deduped" -> incrementCounter(context, "events_deduped")
        }
        recordJournal(
            context,
            stage = "smart_callback",
            reasonCode = result,
            fenceId = fenceId,
            event = event,
            source = source,
            traceId = traceId,
            eventId = eventId,
            extras = linkedMapOf(
                "enqueuedAtMillis" to enqueuedAtMillis,
                "eventAtMillis" to eventAtMillis,
                "timestampSource" to timestampSource,
                "deliveryPath" to deliveryPath,
                "triggerToDeliveryLatencyMillis" to triggerToDeliveryLatencyMillis,
                "deviceIdleModeAtDelivery" to deviceIdleModeAtDelivery,
                "transitionInstanceId" to transitionInstanceId,
            ),
        )
    }

    fun recordCallbackDispatch(
        context: Context,
        fenceId: String?,
        event: String?,
        result: String,
        retryOnFailure: Boolean?,
        eventAtMillis: Long?,
        errorMessage: String?,
        timestampSource: String? = null,
        timeReasonCode: String? = null,
        timeTrusted: Boolean? = null,
        timeRejectionReason: String? = null,
        evidenceQuality: String? = null,
        traceId: String? = null,
        eventId: String? = null,
    ) {
        prefs(context).edit()
            .putLong(LAST_CALLBACK_DISPATCH_AT, System.currentTimeMillis())
            .putNullableString(LAST_CALLBACK_DISPATCH_EVENT, event)
            .putNullableString(LAST_CALLBACK_DISPATCH_FENCE_ID, fenceId)
            .putString(LAST_CALLBACK_DISPATCH_RESULT, result)
            .remove(LEGACY_LAST_CALLBACK_DISPATCH_CALLBACK_HANDLE)
            .putNullableBoolean(LAST_CALLBACK_DISPATCH_RETRY_ON_FAILURE, retryOnFailure)
            .putNullableLong(LAST_CALLBACK_DISPATCH_EVENT_AT, eventAtMillis)
            .putNullableString(LAST_CALLBACK_DISPATCH_TIMESTAMP_SOURCE, timestampSource)
            .putNullableString(LAST_CALLBACK_DISPATCH_TIME_REASON_CODE, timeReasonCode)
            .putNullableBoolean(LAST_CALLBACK_DISPATCH_TIME_TRUSTED, timeTrusted)
            .putNullableString(
                LAST_CALLBACK_DISPATCH_TIME_REJECTION_REASON,
                timeRejectionReason,
            )
            .putNullableString(LAST_CALLBACK_DISPATCH_EVIDENCE_QUALITY, evidenceQuality)
            .putNullableString(LAST_CALLBACK_DISPATCH_ERROR, errorMessage)
            .apply()
        incrementCounter(context, "callback_dispatch_$result")
        recordJournal(
            context,
            stage = "callback_dispatch",
            reasonCode = result,
            fenceId = fenceId,
            event = event,
            traceId = traceId,
            eventId = eventId,
            extras = linkedMapOf(
                "retryOnFailure" to retryOnFailure,
                "eventAtMillis" to eventAtMillis,
                "timestampSource" to timestampSource,
                "timeReasonCode" to timeReasonCode,
                "timeTrusted" to timeTrusted,
                "timeRejectionReason" to timeRejectionReason,
                "evidenceQuality" to evidenceQuality,
                "errorMessage" to errorMessage,
            ),
        )
    }

    fun recordGeofenceSync(
        context: Context,
        result: String,
        desiredCount: Int,
        previousCount: Int,
        errorMessage: String?,
        rollbackFailures: List<String>,
    ) {
        incrementCounter(context, "geofence_sync_$result")
        recordJournal(
            context,
            stage = "geofence_sync",
            reasonCode = result,
            extras = linkedMapOf(
                "desiredCount" to desiredCount,
                "previousCount" to previousCount,
                "errorMessage" to errorMessage,
                "rollbackFailureCount" to rollbackFailures.size,
                "rollbackFailures" to rollbackFailures,
            ),
        )
    }

    fun recordForegroundLifecycleFailure(
        context: Context,
        stage: String,
        errorMessage: String?,
    ) {
        incrementCounter(context, "callback_foreground_${stage}_failure")
        recordJournal(
            context,
            stage = "callback_foreground_lifecycle",
            reasonCode = "${stage}_failure",
            extras = linkedMapOf("errorMessage" to errorMessage),
        )
    }

    fun recordEventProcessor(
        context: Context,
        inputType: String,
        source: String,
        result: String,
        candidateCount: Int? = null,
        traceId: String? = null,
        eventId: String? = null,
    ) {
        prefs(context).edit()
            .putLong(LAST_EVENT_PROCESSOR_AT, System.currentTimeMillis())
            .putString(LAST_EVENT_PROCESSOR_INPUT, inputType)
            .putString(LAST_EVENT_PROCESSOR_SOURCE, source)
            .putString(LAST_EVENT_PROCESSOR_RESULT, result)
            .putNullableLong(LAST_EVENT_PROCESSOR_CANDIDATE_COUNT, candidateCount?.toLong())
            .apply()
        incrementCounter(context, "event_processor_${inputType}_$result")
        recordJournal(
            context,
            stage = "${inputType}_event_processor",
            reasonCode = result,
            source = source,
            traceId = traceId,
            eventId = eventId,
            extras = linkedMapOf("candidateCount" to candidateCount),
        )
    }

    fun recordTrace(
        context: Context,
        stage: String,
        reasonCode: String,
        traceId: String? = null,
        eventId: String? = null,
        fenceId: String? = null,
        event: String? = null,
        source: String? = null,
        extras: Map<String, Any?> = emptyMap(),
    ) {
        runCatching {
            recordJournal(
                context = context,
                stage = stage,
                reasonCode = reasonCode,
                traceId = traceId,
                eventId = eventId,
                fenceId = fenceId,
                event = event,
                source = source,
                extras = extras,
            )
        }
    }

    fun recordConfirmWorkParked(
        context: Context,
        reason: String,
    ) {
        incrementCounter(context, "confirm_work_parked")
        recordJournal(
            context,
            stage = "confirm_work_parked",
            reasonCode = reason,
        )
    }

    fun recordProximityConfirmRetryScheduled(
        context: Context,
        failureReason: String,
        source: String,
        traceId: String?,
        nextAttempt: Int,
        delayMillis: Long,
        pulsePurpose: String,
        activeHoursNow: Boolean,
        scheduled: Boolean,
    ) {
        incrementCounter(context, "confirm_proximity_retry_scheduled")
        recordJournal(
            context = context,
            stage = "confirm_retry",
            reasonCode = if (scheduled) "scheduled" else "schedule_failed",
            source = source,
            traceId = traceId,
            extras = linkedMapOf(
                "failureReason" to failureReason,
                "nextAttempt" to nextAttempt,
                "delayMillis" to delayMillis,
                "pulsePurpose" to pulsePurpose,
                "activeHoursNow" to activeHoursNow,
                "scheduled" to scheduled,
            ),
        )
    }

    fun recordProximityConfirmRetryExhausted(
        context: Context,
        failureReason: String,
        source: String,
        traceId: String?,
        attempts: Int,
    ) {
        incrementCounter(context, "confirm_proximity_retry_exhausted")
        recordJournal(
            context = context,
            stage = "confirm_retry",
            reasonCode = "exhausted",
            source = source,
            traceId = traceId,
            extras = linkedMapOf(
                "failureReason" to failureReason,
                "attempts" to attempts,
            ),
        )
    }

    fun recordProximityConfirmCoalesced(
        context: Context,
        source: String,
        fenceId: String,
    ) {
        incrementCounter(context, "confirm_proximity_coalesced_known_fix")
        recordJournal(
            context = context,
            stage = "confirm_queue",
            reasonCode = "coalesced_known_fix",
            source = source,
            fenceId = fenceId,
        )
    }

    fun recordBootRecoveryStarted(
        context: Context,
        source: String,
        action: String?,
        nativeFenceCount: Int,
        smartFenceCount: Int,
    ) {
        prefs(context).edit()
            .putString(LAST_BOOT_RECOVERY_SOURCE, source)
            .putNullableString(LAST_BOOT_RECOVERY_ACTION, action)
            .putLong(LAST_BOOT_RECOVERY_STARTED_AT, System.currentTimeMillis())
            .remove(LAST_BOOT_RECOVERY_FINISHED_AT)
            .putString(LAST_BOOT_RECOVERY_RESULT, "started")
            .putLong(LAST_BOOT_RECOVERY_NATIVE_FENCE_COUNT, nativeFenceCount.toLong())
            .putLong(LAST_BOOT_RECOVERY_SMART_FENCE_COUNT, smartFenceCount.toLong())
            .remove(LAST_BOOT_RECOVERY_ELAPSED)
            .remove(LAST_BOOT_RECOVERY_FAILURE_MESSAGE)
            .apply()
        recordJournal(
            context,
            stage = "boot_recovery",
            reasonCode = "started",
            source = source,
            extras = linkedMapOf(
                "action" to action,
                "nativeFenceCount" to nativeFenceCount,
                "smartFenceCount" to smartFenceCount,
            ),
        )
    }

    fun recordBootRecoveryResult(
        context: Context,
        source: String,
        action: String?,
        result: String,
        elapsedMillis: Long,
        nativeFenceCount: Int,
        smartFenceCount: Int,
        failureMessage: String?,
    ) {
        prefs(context).edit()
            .putString(LAST_BOOT_RECOVERY_SOURCE, source)
            .putNullableString(LAST_BOOT_RECOVERY_ACTION, action)
            .putLong(LAST_BOOT_RECOVERY_FINISHED_AT, System.currentTimeMillis())
            .putString(LAST_BOOT_RECOVERY_RESULT, result)
            .putLong(LAST_BOOT_RECOVERY_ELAPSED, elapsedMillis.coerceAtLeast(0L))
            .putLong(LAST_BOOT_RECOVERY_NATIVE_FENCE_COUNT, nativeFenceCount.toLong())
            .putLong(LAST_BOOT_RECOVERY_SMART_FENCE_COUNT, smartFenceCount.toLong())
            .putNullableString(LAST_BOOT_RECOVERY_FAILURE_MESSAGE, failureMessage)
            .apply()
        recordJournal(
            context,
            stage = "boot_recovery",
            reasonCode = result,
            source = source,
            extras = linkedMapOf(
                "action" to action,
                "elapsedMillis" to elapsedMillis.coerceAtLeast(0L),
                "nativeFenceCount" to nativeFenceCount,
                "smartFenceCount" to smartFenceCount,
                "failureMessage" to failureMessage,
            ),
        )
    }

    fun recordBootFollowUpSchedule(
        context: Context,
        action: String?,
        triggerAtMillis: Long?,
        result: String,
        failureMessage: String?,
    ) {
        prefs(context).edit()
            .putLong(LAST_BOOT_FOLLOW_UP_SCHEDULED_AT, System.currentTimeMillis())
            .putNullableLong(LAST_BOOT_FOLLOW_UP_TRIGGER_AT, triggerAtMillis)
            .putNullableString(LAST_BOOT_FOLLOW_UP_ACTION, action)
            .putString(LAST_BOOT_FOLLOW_UP_RESULT, result)
            .putNullableString(LAST_BOOT_FOLLOW_UP_FAILURE_MESSAGE, failureMessage)
            .apply()
        recordJournal(
            context,
            stage = "boot_follow_up",
            reasonCode = result,
            extras = linkedMapOf(
                "action" to action,
                "triggerAtMillis" to triggerAtMillis,
                "failureMessage" to failureMessage,
            ),
        )
    }

    fun recordBroadcastDeadlineFinished(
        context: Context,
        tag: String,
        reason: String,
    ) {
        prefs(context).edit()
            .putLong(LAST_BROADCAST_DEADLINE_FINISHED_AT, System.currentTimeMillis())
            .putString(LAST_BROADCAST_DEADLINE_TAG, tag)
            .putString(LAST_BROADCAST_DEADLINE_REASON, reason)
            .apply()
        incrementCounter(context, "broadcast_deadline_finished")
        recordJournal(
            context,
            stage = "broadcast_deadline",
            reasonCode = "finished_by_deadline",
            extras = linkedMapOf(
                "tag" to tag,
                "reason" to reason,
            ),
        )
    }

    fun recordRecoveryMainThreadTimeout(
        context: Context,
        source: String,
        reason: String,
    ) {
        prefs(context).edit()
            .putLong(LAST_RECOVERY_MAIN_THREAD_TIMEOUT_AT, System.currentTimeMillis())
            .putString(LAST_RECOVERY_MAIN_THREAD_TIMEOUT_SOURCE, source)
            .putString(LAST_RECOVERY_MAIN_THREAD_TIMEOUT_REASON, reason)
            .apply()
        incrementCounter(context, "recovery_main_thread_timeout")
        recordJournal(
            context,
            stage = "recovery_main_thread_timeout",
            reasonCode = reason,
            source = source,
        )
    }

    fun getStatus(context: Context): Map<String, Any?> {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        val monitoringStop = MonitoringStopStateStore.snapshot(appContext)
        val fences = FenceStore.getAll(appContext)
        val mirroredFenceIds = fences.map { it.id }.sorted()
        val radiusNormalizations = fences.sortedBy { it.id }.associate {
            it.id to it.radiusNormalizationMap()
        }
        val locationServicesEnabled = locationServicesEnabled(appContext)
        val locationPermissionGranted = hasLocationPermission(appContext)
        val fineLocationPermissionGranted =
            granted(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundLocationPermissionGranted = hasBackgroundLocationPermission(appContext)
        val activityPermissionGranted = hasActivityPermission(appContext)
        val foregroundServicePermissionGranted =
            granted(appContext, Manifest.permission.FOREGROUND_SERVICE)
        val foregroundServiceLocationPermissionGranted =
            hasForegroundServiceLocationPermission(appContext)
        val notificationPermissionGranted = hasNotificationPermission(appContext)
        val exactAlarmPermission = ExactAlarmPermissionController.details(appContext)
        val exactAlarmPermissionDeclared =
            permissionDeclared(appContext, Manifest.permission.SCHEDULE_EXACT_ALARM)
        val exactAlarmPermissionGranted =
            exactAlarmPermission["exactAlarmPermissionGranted"] as? Boolean
                ?: exactAlarmPermissionGranted(appContext)
        val exactAlarmStrictStartupBlocked =
            ExactAlarmPermissionController.isStrictBlocked(appContext, config)
        val nativeConfirmDelayRequiresExactAlarm =
            (config.nativeExitConfirmationEnabled || config.nativeEnterConfirmationEnabled) &&
                config.nativeConfirmDelayMillis > 0L
        val transitionValidationEnterActive = config.transitionValidationEnabled &&
            config.transitionValidationEnterEnabled
        val transitionValidationExitActive = config.transitionValidationEnabled &&
            config.transitionValidationExitEnabled
        val nativeEnterConfirmImmediateTimingBypassPossible =
            config.nativeEnterConfirmationEnabled &&
                config.nativeConfirmDelayMillis > 0L &&
                !exactAlarmPermissionGranted &&
                config.exactAlarmPermissionMode == ExactAlarmPermissionMode.BestEffort &&
                !transitionValidationEnterActive
        val nativeExitConfirmImmediateTimingBypassPossible =
            config.nativeExitConfirmationEnabled &&
                config.nativeConfirmDelayMillis > 0L &&
                !exactAlarmPermissionGranted &&
                config.exactAlarmPermissionMode == ExactAlarmPermissionMode.BestEffort &&
                !transitionValidationExitActive
        val batteryState = batteryState(appContext)
        val fusedLocationUpdateReceiverDeclared = hasReceiver(appContext, FusedLocationUpdateReceiver::class.java)
        val activityReceiverDeclared = hasReceiver(appContext, ActivityReceiver::class.java)
        val bootReceiverDeclared = hasReceiver(appContext, BootReceiver::class.java)
        val exactAlarmPermissionStateReceiverDeclared =
            hasReceiver(appContext, ExactAlarmPermissionReceiver::class.java)
        val bootFollowUpReceiverDeclared =
            hasReceiver(appContext, BootRecoveryReceiver::class.java)
        val recoveryAlarmReceiverDeclared =
            hasReceiver(appContext, RecoveryAlarmReceiver::class.java)
        val locationConfirmReceiverDeclared =
            hasReceiver(appContext, LocationConfirmAlarmReceiver::class.java)
        val locationProviderChangedReceiverDeclared =
            hasReceiver(appContext, LocationProviderChangedReceiver::class.java)
        val nativeExitFallbackReceiverDeclared =
            hasReceiver(appContext, NativeExitFallbackReceiver::class.java)
        val nativeEnterFallbackReceiverDeclared =
            hasReceiver(appContext, NativeEnterFallbackReceiver::class.java)
        val foregroundServiceLaunchReceiverDeclared =
            hasReceiver(appContext, ForegroundServiceLaunchReceiver::class.java)
        val locationConfirmServiceInfo = serviceInfo(appContext, LocationConfirmService::class.java)
        val locationConfirmServiceDeclared = locationConfirmServiceInfo != null
        val locationConfirmServiceHasLocationType =
            serviceHasLocationType(locationConfirmServiceInfo)
        val proximityAlarmReceiverDeclared =
            hasReceiver(appContext, ProximityAlarmReceiver::class.java)
        val dormantFarProbeReceiverDeclared =
            hasReceiver(appContext, DormantFarProbeReceiver::class.java)
        val dormantFarStatus = DormantFarStateStore.diagnosticMap(appContext)
        val locationConfirmCanRun = locationServicesEnabled != false &&
            locationPermissionGranted &&
            backgroundLocationPermissionGranted &&
            foregroundServicePermissionGranted &&
            foregroundServiceLocationPermissionGranted &&
            locationConfirmReceiverDeclared &&
            foregroundServiceLaunchReceiverDeclared &&
            locationConfirmServiceDeclared &&
            locationConfirmServiceHasLocationType
        val proximityPulseActiveHoursNow = AdaptivePulseRate.isActive(config)
        val proximityPulseCanRun = config.proximityPulseEnabled &&
            proximityAlarmReceiverDeclared
        val locationConfirmLaunch = ForegroundLaunchState.snapshot(
            appContext,
            ForegroundLaunchState.SERVICE_LOCATION_CONFIRM
        )
        val proximityPulseState = ProximityPulseStateStore.load(appContext)
        val fusedLiveness = FusedLocationLiveness.snapshot(appContext)
        val fusedRequestLifecycle = FusedLocationManager.lifecycleState(appContext)
        val fusedRequestRemovalInFlightSinceMillis = listOfNotNull(
            fusedRequestLifecycle.balanced.operationStartedAtMillis
                ?.takeIf { fusedRequestLifecycle.balanced.removalInFlight },
            fusedRequestLifecycle.passive.operationStartedAtMillis
                ?.takeIf { fusedRequestLifecycle.passive.removalInFlight },
        ).minOrNull()
        val activityTransitionPendingIntentExists =
            ActivityMonitor.transitionPendingIntentExists(appContext)
        val activityPeriodicPendingIntentExists =
            ActivityMonitor.periodicPendingIntentExists(appContext)
        val activityLifecycle = ActivityMonitor.lifecycleState(appContext)
        val activityRemovalInFlightSinceMillis = listOfNotNull(
            activityLifecycle.transition.operationStartedAtMillis
                ?.takeIf { activityLifecycle.transition.removalInFlight },
            activityLifecycle.periodic.operationStartedAtMillis
                ?.takeIf { activityLifecycle.periodic.removalInFlight },
        ).minOrNull()
        val activityRemovalOverdue = activityRemovalInFlightSinceMillis?.let {
            System.currentTimeMillis() - it > 120_000L
        } == true
        val foregroundQueueItems = ForegroundQueue.diagnosticItems(appContext)
        val nativeExitPendingDetails = NativeExitPendingStore.pendingDiagnostics(appContext)
        val nativeEnterPendingDetails = NativeEnterPendingStore.pendingDiagnostics(appContext)
        val fenceStates = fenceStateDiagnostics(
            appContext,
            fences,
            nativeExitPendingDetails,
            nativeEnterPendingDetails,
        )
        val hasFences = mirroredFenceIds.isNotEmpty()
        val commonLocationEligible = hasFences &&
            config.escalationEnabled &&
            locationServicesEnabled != false &&
            locationPermissionGranted &&
            backgroundLocationPermissionGranted &&
            fusedLocationUpdateReceiverDeclared
        val smartLayerMode = smartLayerMode(
            hasFences = hasFences,
            config = config,
            locationServicesEnabled = locationServicesEnabled,
            locationPermissionGranted = locationPermissionGranted,
            fineLocationPermissionGranted = fineLocationPermissionGranted,
            backgroundLocationPermissionGranted = backgroundLocationPermissionGranted,
            fusedLocationUpdateReceiverDeclared = fusedLocationUpdateReceiverDeclared,
            locationConfirmCanRun = locationConfirmCanRun,
        )
        val diagnosticJournalSnapshot = DiagnosticEventJournal.read(prefs(appContext))
        val diagnosticJournal = diagnosticJournalSnapshot.entries

        return linkedMapOf(
            "smartStatusSchemaVersion" to SMART_STATUS_SCHEMA_VERSION,
            "androidSdkInt" to Build.VERSION.SDK_INT,
            "deviceManufacturer" to Build.MANUFACTURER,
            "deviceModel" to Build.MODEL,
            "processStartAtMillis" to (
                readLong(appContext, PROCESS_START_AT) ?: PROCESS_STARTED_AT_MILLIS
            ),
            "lastAliveAtMillis" to readLong(appContext, LAST_ALIVE_AT),
            "lastAliveStatus" to readString(appContext, LAST_ALIVE_STATUS),
            "config" to config.toMap(),
            "configAppliedAtMillis" to SmartGeofenceConfigStore.appliedAtMillis(appContext),
            "configFingerprint" to (
                SmartGeofenceConfigStore.fingerprint(appContext)
                    ?: SmartGeofenceConfigStore.fingerprint(config)
            ),
            "packageVersion" to BuildConfig.SMART_GEOFENCE_PACKAGE_VERSION,
            "buildRevision" to BuildConfig.SMART_GEOFENCE_BUILD_REVISION,
            "smartLayerMode" to smartLayerMode.mode,
            "smartLayerModeReason" to smartLayerMode.reason,
            "monitoringTerminallyStopped" to monitoringStop.terminallyStopped,
            "monitoringStopPhase" to monitoringStop.phase?.configValue,
            "monitoringStopReason" to monitoringStop.event?.reason?.configValue,
            "monitoringStoppedAtMillis" to monitoringStop.event?.stoppedAtMillis,
            "monitoringStopEventId" to monitoringStop.event?.eventId,
            "monitoringStopCallbackPending" to monitoringStop.callbackPending,
            "monitoringNativeCleanupComplete" to monitoringStop.nativeCleanupComplete,
            "monitoringNativeCleanupPendingCount" to
                monitoringStop.pendingNativeCleanupFenceIds.size,
            "diagnosticEventJournal" to diagnosticJournal,
            "diagnosticEventJournalCapacity" to DiagnosticEventJournal.MAX_ENTRIES,
            "diagnosticEventJournalCount" to diagnosticJournal.size,
            "diagnosticEventJournalSequence" to diagnosticJournalSnapshot.sequence,
            "diagnosticEventJournalDroppedCount" to diagnosticJournalSnapshot.droppedCount,
            "diagnosticEventJournalCorruptCount" to
                diagnosticJournalSnapshot.corruptEntryCount,
            "diagnosticEventJournalOldestAtMillis" to
                (diagnosticJournal.firstOrNull()?.get("atMillis") as? Number)?.toLong(),
            "diagnosticEventJournalNewestAtMillis" to
                (diagnosticJournal.lastOrNull()?.get("atMillis") as? Number)?.toLong(),
            "diagnosticCounters" to readCounters(appContext),
            "fenceStates" to fenceStates,
            "mirroredFenceIds" to mirroredFenceIds,
            "mirroredFenceCount" to mirroredFenceIds.size,
            "radiusNormalizations" to radiusNormalizations,
            "locationPermissionGranted" to locationPermissionGranted,
            "fineLocationGranted" to fineLocationPermissionGranted,
            "backgroundLocationPermissionGranted" to backgroundLocationPermissionGranted,
            "activityPermissionGranted" to activityPermissionGranted,
            "foregroundServicePermissionGranted" to foregroundServicePermissionGranted,
            "foregroundServiceLocationPermissionGranted" to foregroundServiceLocationPermissionGranted,
            "notificationPermissionGranted" to notificationPermissionGranted,
            "foregroundNotificationSticky" to config.foregroundNotificationSticky,
            "foregroundNotificationTapAction" to config.foregroundNotificationTapAction.configValue,
            "foregroundNotificationShowWhileMonitoring" to config.foregroundNotificationShowWhileMonitoring,
            "powerSaveMode" to isPowerSaveMode(appContext),
            "deviceIdleMode" to isDeviceIdleMode(appContext),
            "batteryOptimizationsIgnored" to isIgnoringBatteryOptimizations(appContext),
            "batteryLevelPercent" to batteryState.levelPercent,
            "batteryCharging" to batteryState.charging,
            "exactAlarmPermissionMode" to config.exactAlarmPermissionMode.configValue,
            "exactAlarmPermissionDeclared" to exactAlarmPermissionDeclared,
            "exactAlarmPermissionRequired" to
                exactAlarmPermission["exactAlarmPermissionRequired"],
            "exactAlarmPermissionStatus" to
                exactAlarmPermission["exactAlarmPermissionStatus"],
            "exactAlarmPermissionGranted" to exactAlarmPermissionGranted,
            "exactAlarmSettingsIntentAvailable" to
                exactAlarmPermission["exactAlarmSettingsIntentAvailable"],
            "exactAlarmAppSettingsFallbackAvailable" to
                exactAlarmPermission["exactAlarmAppSettingsFallbackAvailable"],
            "exactAlarmSettingsCanOpen" to
                exactAlarmPermission["exactAlarmSettingsCanOpen"],
            "exactAlarmStrictStartupBlocked" to exactAlarmStrictStartupBlocked,
            "nativeConfirmDelayRequiresExactAlarm" to nativeConfirmDelayRequiresExactAlarm,
            "nativeConfirmDelayExactSchedulingAvailable" to exactAlarmPermissionGranted,
            "nativeEnterConfirmImmediateTimingBypassPossible" to
                nativeEnterConfirmImmediateTimingBypassPossible,
            "nativeExitConfirmImmediateTimingBypassPossible" to
                nativeExitConfirmImmediateTimingBypassPossible,
            "transitionValidationEnterBlocksEarlyConfirmationAcquisition" to
                transitionValidationEnterActive,
            "transitionValidationExitBlocksEarlyConfirmationAcquisition" to
                transitionValidationExitActive,
            "transitionValidationEnterBlocksRawNativeFallback" to
                transitionValidationEnterActive,
            "transitionValidationExitBlocksRawNativeFallback" to
                transitionValidationExitActive,
            "nativeConfirmDelayMillis" to config.nativeConfirmDelayMillis,
            "nativeConfirmMaxAttempts" to config.nativeConfirmMaxAttempts,
            "transitionValidationEnabled" to config.transitionValidationEnabled,
            "transitionValidationEnterEnabled" to config.transitionValidationEnterEnabled,
            "transitionValidationExitEnabled" to config.transitionValidationExitEnabled,
            "transitionValidationMinimumDelayMillis" to
                config.transitionValidationMinimumDelayMillis,
            "lastExactAlarmSettingsOpenedAtMillis" to
                exactAlarmPermission["lastExactAlarmSettingsOpenedAtMillis"],
            "lastExactAlarmSettingsOpenResult" to
                exactAlarmPermission["lastExactAlarmSettingsOpenResult"],
            "lastExactAlarmSettingsOpenRoute" to
                exactAlarmPermission["lastExactAlarmSettingsOpenRoute"],
            "lastExactAlarmSettingsOpenFailureReason" to
                exactAlarmPermission["lastExactAlarmSettingsOpenFailureReason"],
            "locationServicesEnabled" to locationServicesEnabled,
            "fusedLocationUpdateReceiverDeclared" to fusedLocationUpdateReceiverDeclared,
            "activityReceiverDeclared" to activityReceiverDeclared,
            "bootReceiverDeclared" to bootReceiverDeclared,
            "exactAlarmPermissionStateReceiverDeclared" to
                exactAlarmPermissionStateReceiverDeclared,
            "bootFollowUpReceiverDeclared" to bootFollowUpReceiverDeclared,
            "recoveryAlarmReceiverDeclared" to recoveryAlarmReceiverDeclared,
            "locationConfirmReceiverDeclared" to locationConfirmReceiverDeclared,
            "foregroundServiceLaunchReceiverDeclared" to foregroundServiceLaunchReceiverDeclared,
            "foregroundStartCoordinatorWindowClosed" to
                ForegroundStartCoordinator.batchWindowClosed(appContext),
            "foregroundStartQueuedServices" to
                ForegroundStartCoordinator.queuedServiceKeys(appContext),
            "foregroundStartBatchPendingIntentExists" to
                ForegroundStartCoordinator.batchStartPendingIntentExists(appContext),
            "foregroundStartBatchAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                ForegroundStartCoordinator.SCHEDULE_KEY_BATCH_START,
            ),
            "locationConfirmServiceDeclared" to locationConfirmServiceDeclared,
            "locationConfirmServiceHasLocationType" to locationConfirmServiceHasLocationType,
            "locationConfirmServiceRunning" to LocationConfirmService.isRunning,
            "locationConfirmServiceForegroundReady" to LocationConfirmService.isForegroundReady,
            "locationConfirmLaunchToken" to locationConfirmLaunch.activeToken,
            "locationConfirmLaunchRequestedAtMillis" to locationConfirmLaunch.launchRequestedAtMillis,
            "locationConfirmForegroundReadyAtMillis" to locationConfirmLaunch.foregroundReadyAtMillis,
            "locationConfirmLastLaunchFailureAtMillis" to locationConfirmLaunch.lastFailureAtMillis,
            "locationConfirmLastLaunchFailureReason" to locationConfirmLaunch.lastFailureReason,
            "locationConfirmCanRun" to locationConfirmCanRun,
            "locationConfirmQueueSize" to WakeEventCoordinator.foregroundWorkCount(appContext),
            "locationConfirmQueueTotalSize" to WakeEventCoordinator.totalForegroundWorkCount(appContext),
            "locationConfirmQueueParkedSize" to WakeEventCoordinator.parkedForegroundWorkCount(appContext),
            "foregroundQueueSize" to ForegroundQueue.count(appContext),
            "foregroundQueueTotalSize" to ForegroundQueue.totalCount(appContext),
            "foregroundQueueParkedSize" to ForegroundQueue.parkedCount(appContext),
            "foregroundQueueItems" to foregroundQueueItems,
            "locationConfirmQueueItems" to foregroundQueueItems,
            "backgroundQueueSize" to BackgroundQueue.count(appContext),
            "locationProviderChangedReceiverDeclared" to locationProviderChangedReceiverDeclared,
            "nativeExitFallbackReceiverDeclared" to nativeExitFallbackReceiverDeclared,
            "nativeExitFallbackPendingCount" to NativeExitPendingStore.count(appContext),
            "nativeExitFallbackPendingFenceIds" to NativeExitPendingStore.pendingFenceIds(appContext),
            "nativeExitFallbackPendingDetails" to nativeExitPendingDetails,
            "nativeExitFallbackAlarmPendingIntentExists" to
                NativeExitPendingStore.pendingIntentExists(appContext),
            "nativeExitFallbackAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                NativeExitPendingStore.SCHEDULE_KEY,
            ),
            "nativeEnterFallbackReceiverDeclared" to nativeEnterFallbackReceiverDeclared,
            "nativeEnterFallbackPendingCount" to NativeEnterPendingStore.count(appContext),
            "nativeEnterFallbackPendingFenceIds" to NativeEnterPendingStore.pendingFenceIds(appContext),
            "nativeEnterFallbackPendingDetails" to nativeEnterPendingDetails,
            "nativeEnterFallbackAlarmPendingIntentExists" to
                NativeEnterPendingStore.pendingIntentExists(appContext),
            "nativeEnterFallbackAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                NativeEnterPendingStore.SCHEDULE_KEY,
            ),
            "proximityAlarmReceiverDeclared" to proximityAlarmReceiverDeclared,
            "dormantFarProbeReceiverDeclared" to dormantFarProbeReceiverDeclared,
            "dormantFarActive" to dormantFarStatus["dormantFarActive"],
            "dormantFarReason" to dormantFarStatus["dormantFarReason"],
            "dormantFarBatteryMode" to dormantFarStatus["dormantFarBatteryMode"],
            "dormantFarLastEdgeDistanceMeters" to
                dormantFarStatus["dormantFarLastEdgeDistanceMeters"],
            "dormantFarNearestFenceId" to dormantFarStatus["dormantFarNearestFenceId"],
            "dormantFarLastAcceptedFixSource" to
                dormantFarStatus["dormantFarLastAcceptedFixSource"],
            "dormantFarLastAcceptedFixAtMillis" to
                dormantFarStatus["dormantFarLastAcceptedFixAtMillis"],
            "dormantFarNextProbeAtMillis" to
                dormantFarStatus["dormantFarNextProbeAtMillis"],
            "dormantFarLastProbeAtMillis" to
                dormantFarStatus["dormantFarLastProbeAtMillis"],
            "dormantFarLastProbeResult" to
                dormantFarStatus["dormantFarLastProbeResult"],
            "proximityPulseStartedAtMillis" to proximityPulseState?.startedAtMillis,
            "proximityPulsePurpose" to proximityPulseState?.purpose?.name?.lowercase(),
            "proximityPulseSchedulingActive" to proximityPulseState?.schedulingActive,
            "proximityPulseProximityFenceIds" to
                proximityPulseState?.proximityFenceIds?.sorted(),
            "proximityPulseInsideFenceIds" to proximityPulseState?.insideFenceIds?.sorted(),
            "proximityPulseLivenessStartedAtMillis" to
                proximityPulseState?.livenessStartedAtMillis,
            "fusedLocationLastHealthyAtMillis" to fusedLiveness.lastHealthyAtMillis,
            "fusedLocationHealthyAgeMillis" to
                FusedLocationLiveness.healthyAgeMillis(appContext),
            "fusedLocationLastHealthySource" to fusedLiveness.lastHealthySource,
            "fusedLocationRecoveryStartedAtMillis" to fusedLiveness.recoveryStartedAtMillis,
            "fusedLocationLastRecoveryEndedAtMillis" to
                fusedLiveness.lastRecoveryEndedAtMillis,
            "fusedLocationLastRecoveryReason" to fusedLiveness.lastRecoveryReason,
            "fusedLocationBalancedRefreshCount" to fusedLiveness.balancedRefreshCount,
            "proximityAlarmKind" to
                ProximityAlarmScheduler.scheduledKind(appContext)?.name?.lowercase(),
            "adaptiveProximityDisplacementMode" to
                AdaptiveProximityDisplacement.currentMode(appContext)?.name?.lowercase(),
            "adaptiveProximityDisplacementMeters" to
                AdaptiveProximityDisplacement.appliedDisplacementMeters(appContext),
            "adaptiveProximityLastEdgeDistanceMeters" to
                AdaptiveProximityDisplacement.lastEdgeDistanceMeters(appContext),
            "proximityPulseActiveHoursNow" to proximityPulseActiveHoursNow,
            "proximityPulseCanRun" to proximityPulseCanRun,
            "proximityEligible" to commonLocationEligible,
            "passiveLocationEligible" to (commonLocationEligible && config.passiveLocationEnabled),
            "fusedBalancedDesired" to fusedRequestLifecycle.balanced.desired,
            "fusedBalancedDesiredEpoch" to fusedRequestLifecycle.balanced.desiredEpoch,
            "fusedBalancedRequestInFlight" to
                fusedRequestLifecycle.balanced.requestInFlight,
            "fusedBalancedConfirmed" to fusedRequestLifecycle.balanced.confirmed,
            "fusedBalancedRemovalInFlight" to
                fusedRequestLifecycle.balanced.removalInFlight,
            "fusedBalancedRemovalConfirmed" to
                fusedRequestLifecycle.balanced.removalConfirmed,
            "fusedBalancedDesiredPriority" to
                fusedRequestLifecycle.balanced.desiredSpec?.priorityName,
            "fusedBalancedConfirmedPriority" to
                fusedRequestLifecycle.balanced.confirmedSpec?.priorityName,
            "fusedBalancedDesiredIntervalMillis" to
                fusedRequestLifecycle.balanced.desiredSpec?.intervalMillis,
            "fusedBalancedConfirmedIntervalMillis" to
                fusedRequestLifecycle.balanced.confirmedSpec?.intervalMillis,
            "fusedBalancedDesiredDisplacementMeters" to
                fusedRequestLifecycle.balanced.desiredSpec?.minDisplacementMeters,
            "fusedBalancedConfirmedDisplacementMeters" to
                fusedRequestLifecycle.balanced.confirmedSpec?.minDisplacementMeters,
            "fusedBalancedDesiredAdaptiveMode" to
                fusedRequestLifecycle.balanced.desiredSpec?.adaptiveMode?.lowercase(),
            "fusedBalancedConfirmedAdaptiveMode" to
                fusedRequestLifecycle.balanced.confirmedSpec?.adaptiveMode?.lowercase(),
            "fusedBalancedLastSuccessAtMillis" to
                fusedRequestLifecycle.balanced.lastSuccessAtMillis,
            "fusedBalancedLastFailureAtMillis" to
                fusedRequestLifecycle.balanced.lastFailureAtMillis,
            "fusedBalancedLastFailureReason" to
                fusedRequestLifecycle.balanced.lastFailureReason,
            "fusedPassiveDesired" to fusedRequestLifecycle.passive.desired,
            "fusedPassiveDesiredEpoch" to fusedRequestLifecycle.passive.desiredEpoch,
            "fusedPassiveRequestInFlight" to fusedRequestLifecycle.passive.requestInFlight,
            "fusedPassiveConfirmed" to fusedRequestLifecycle.passive.confirmed,
            "fusedPassiveRemovalInFlight" to fusedRequestLifecycle.passive.removalInFlight,
            "fusedPassiveRemovalConfirmed" to fusedRequestLifecycle.passive.removalConfirmed,
            "fusedPassiveDesiredPriority" to
                fusedRequestLifecycle.passive.desiredSpec?.priorityName,
            "fusedPassiveConfirmedPriority" to
                fusedRequestLifecycle.passive.confirmedSpec?.priorityName,
            "fusedPassiveDesiredIntervalMillis" to
                fusedRequestLifecycle.passive.desiredSpec?.intervalMillis,
            "fusedPassiveConfirmedIntervalMillis" to
                fusedRequestLifecycle.passive.confirmedSpec?.intervalMillis,
            "fusedPassiveLastSuccessAtMillis" to
                fusedRequestLifecycle.passive.lastSuccessAtMillis,
            "fusedPassiveLastFailureAtMillis" to
                fusedRequestLifecycle.passive.lastFailureAtMillis,
            "fusedPassiveLastFailureReason" to
                fusedRequestLifecycle.passive.lastFailureReason,
            "fusedRequestRemovalInFlightSinceMillis" to
                fusedRequestRemovalInFlightSinceMillis,
            "fusedRequestStaleCallbackCount" to fusedRequestLifecycle.staleCallbackCount,
            "fusedRequestLastStaleCallbackReason" to
                fusedRequestLifecycle.lastStaleCallbackReason,
            "fusedRequestIgnoredCallbackCount" to
                FusedLocationManager.ignoredCallbackCount(appContext),
            "fusedRequestLastIgnoredCallbackReason" to
                FusedLocationManager.lastIgnoredCallbackReason(appContext),
            "activityEligible" to
                (ActivityMonitor.operationalIneligibilityReason(appContext) == null),
            "recoveryEligible" to hasFences,
            "proximityPendingIntentExists" to proximityPendingIntentExists(appContext),
            "passiveLocationPendingIntentExists" to passiveLocationPendingIntentExists(appContext),
            "activityPendingIntentExists" to (
                activityTransitionPendingIntentExists ||
                    activityPeriodicPendingIntentExists
                ),
            "activityTransitionPendingIntentExists" to activityTransitionPendingIntentExists,
            "activityPeriodicPendingIntentExists" to activityPeriodicPendingIntentExists,
            "activityControllerDesired" to activityLifecycle.controllerDesired,
            "activityTransitionDesired" to activityLifecycle.controllerDesired,
            "activityPeriodicDesired" to (
                activityLifecycle.controllerDesired &&
                    activityLifecycle.transition.confirmed &&
                    activityLifecycle.transition.belongsToMonitoringSession(
                        activityLifecycle.monitoringSessionGeneration,
                    ) &&
                    (activityLifecycle.periodicBackstopEnabled ||
                        (!activityLifecycle.bootstrapCompleted &&
                            !activityLifecycle.periodicRemovalRequired))
                ),
            "activityPeriodicMode" to activityLifecycle.periodicMode.configValue,
            "activityPeriodicBackstopEnabled" to
                activityLifecycle.periodicBackstopEnabled,
            "activityOperationEpoch" to activityLifecycle.controllerEpoch,
            "activityMonitoringSessionGeneration" to
                activityLifecycle.monitoringSessionGeneration,
            "activityDesiredPeriodicIntervalMillis" to
                activityLifecycle.desiredPeriodicIntervalMillis,
            "activityConfirmedPeriodicIntervalMillis" to
                activityLifecycle.confirmedPeriodicIntervalMillis,
            "activityConfirmedPeriodicOwner" to activityLifecycle.periodicOwner,
            "activityTransitionRequested" to (
                activityLifecycle.transition.requestInFlight ||
                    activityLifecycle.transition.confirmed ||
                    activityLifecycle.transition.removalInFlight
                ),
            "activityTransitionRequestInFlight" to
                activityLifecycle.transition.requestInFlight,
            "activityTransitionConfirmed" to activityLifecycle.transition.confirmed,
            "activityTransitionRemovalInFlight" to
                activityLifecycle.transition.removalInFlight,
            "activityTransitionRemovalConfirmed" to
                activityLifecycle.transition.removalConfirmed,
            "activityTransitionLastSuccessAtMillis" to
                activityLifecycle.transition.lastSuccessAtMillis,
            "activityTransitionLastFailureAtMillis" to
                activityLifecycle.transition.lastFailureAtMillis,
            "activityTransitionLastFailureReason" to
                activityLifecycle.transition.lastFailureReason,
            "activityPeriodicRequested" to (
                activityLifecycle.periodic.requestInFlight ||
                    activityLifecycle.periodic.confirmed ||
                    activityLifecycle.periodic.removalInFlight
                ),
            "activityPeriodicRequestInFlight" to activityLifecycle.periodic.requestInFlight,
            "activityPeriodicConfirmed" to activityLifecycle.periodic.confirmed,
            "activityPeriodicRemovalInFlight" to activityLifecycle.periodic.removalInFlight,
            "activityPeriodicRemovalConfirmed" to
                activityLifecycle.periodic.removalConfirmed,
            "activityPeriodicRemovalRequired" to
                activityLifecycle.periodicRemovalRequired,
            "activityBootstrapRequestedAtMillis" to
                activityLifecycle.bootstrapRequestedAtMillis,
            "activityBootstrapDeadlineMillis" to
                activityLifecycle.bootstrapDeadlineMillis,
            "activityBootstrapResultReceived" to
                activityLifecycle.bootstrapResultReceived,
            "activityBootstrapCompleted" to activityLifecycle.bootstrapCompleted,
            "activityBootstrapTimeoutPendingIntentExists" to
                ActivityMonitor.bootstrapTimeoutPendingIntentExists(appContext),
            "activityPeriodicLastSuccessAtMillis" to
                activityLifecycle.periodic.lastSuccessAtMillis,
            "activityPeriodicLastFailureAtMillis" to
                activityLifecycle.periodic.lastFailureAtMillis,
            "activityPeriodicLastFailureReason" to
                activityLifecycle.periodic.lastFailureReason,
            "activityRemovalInFlightSinceMillis" to activityRemovalInFlightSinceMillis,
            "activityRemovalOverdue" to activityRemovalOverdue,
            "activityStaleOperationCallbackCount" to activityLifecycle.staleCallbackCount,
            "activityLastStaleOperationCallbackReason" to
                activityLifecycle.lastStaleCallbackReason,
            "activityIgnoredCallbackCount" to ActivityMonitor.ignoredCallbackCount(appContext),
            "activityLastIgnoredCallbackReason" to
                ActivityMonitor.lastIgnoredCallbackReason(appContext),
            "activityPeriodicReason" to ActivityMonitor.periodicReason(appContext),
            "activityPeriodicRequestedAtMillis" to
                ActivityMonitor.periodicRequestedAtMillis(appContext),
            "activityPeriodicIntervalMillis" to
                ActivityMonitor.periodicIntervalMillis(appContext),
            "lastActivityPeriodicResultAtMillis" to
                ActivityMonitor.lastPeriodicResultAtMillis(appContext),
            "activityStationarySource" to ActivityMonitor.stationarySource(appContext),
            "bootFollowUpPendingIntentExists" to
                BootRecoveryCoordinator.followUpPendingIntentExists(appContext),
            "bootFollowUpAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                BootRecoveryCoordinator.SCHEDULE_KEY_BOOT_FOLLOW_UP,
            ),
            "recoveryAlarmPendingIntentExists" to RecoveryScheduler.pendingIntentExists(appContext),
            "recoveryAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                RecoveryScheduler.SCHEDULE_KEY,
            ),
            "locationConfirmStartPendingIntentExists" to
                LocationConfirmManager.pendingIntentExists(appContext),
            "locationConfirmWatchdogPendingIntentExists" to
                LocationConfirmManager.watchdogPendingIntentExists(appContext),
            "locationConfirmStartAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                ForegroundStartCoordinator.startScheduleKey(LocationConfirmManager.LAUNCH_SPEC),
            ),
            "locationConfirmWatchdogAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                ForegroundStartCoordinator.watchdogScheduleKey(LocationConfirmManager.LAUNCH_SPEC),
            ),
            "locationDisabledRecoveryPendingIntentExists" to
                LocationDisabledRecoveryScheduler.pendingIntentExists(appContext),
            "locationDisabledRecoveryAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                LocationDisabledRecoveryScheduler.SCHEDULE_KEY,
            ),
            "foregroundServiceRearmPendingIntentExists" to
                ForegroundServiceRearm.pendingIntentExists(appContext),
            "foregroundServiceRearmAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                ForegroundServiceRearm.SCHEDULE_KEY_REARM,
            ),
            "proximityAlarmPendingIntentExists" to
                ProximityAlarmScheduler.pendingIntentExists(appContext),
            "proximityAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                ProximityAlarmScheduler.SCHEDULE_KEY_PROXIMITY,
            ),
            "dormantFarProbePendingIntentExists" to
                DormantFarProbeScheduler.pendingIntentExists(appContext),
            "dormantFarProbeAlarmSchedule" to AlarmPolicyScheduler.diagnosticStatus(
                appContext,
                DormantFarProbeScheduler.SCHEDULE_KEY,
            ),
            "lastLocationWakeAtMillis" to readLong(appContext, LAST_LOCATION_WAKE_AT),
            "lastLocationWakeSource" to readString(appContext, LAST_LOCATION_WAKE_SOURCE),
            "lastLocationWakeProvider" to readString(appContext, LAST_LOCATION_WAKE_PROVIDER),
            "lastLocationWakeAccuracyMeters" to readDouble(appContext, LAST_LOCATION_WAKE_ACCURACY),
            "lastLocationWakeAgeMillis" to readLong(appContext, LAST_LOCATION_WAKE_AGE),
            "lastLocationWakeNearestFenceId" to
                readString(appContext, LAST_LOCATION_WAKE_NEAREST_FENCE_ID),
            "lastLocationWakeEdgeDistanceMeters" to
                readDouble(appContext, LAST_LOCATION_WAKE_EDGE_DISTANCE),
            "lastLocationWakeWithinProximity" to
                readBoolean(appContext, LAST_LOCATION_WAKE_WITHIN_PROXIMITY),
            "lastConfirmQueueAtMillis" to readLong(appContext, LAST_CONFIRM_QUEUE_AT),
            "lastConfirmQueueRequestId" to readLong(appContext, LAST_CONFIRM_QUEUE_REQUEST_ID),
            "lastConfirmQueueFenceId" to readString(appContext, LAST_CONFIRM_QUEUE_FENCE_ID),
            "lastConfirmQueueIsProximity" to (
                readBoolean(appContext, LAST_CONFIRM_QUEUE_IS_PROXIMITY)
                    ?: readBoolean(appContext, LEGACY_LAST_CONFIRM_QUEUE_IS_NEAREST)
            ),
            "lastConfirmQueueSource" to readString(appContext, LAST_CONFIRM_QUEUE_SOURCE),
            "lastConfirmQueueAgeMillis" to readLong(appContext, LAST_CONFIRM_QUEUE_AGE),
            "lastConfirmRequestAtMillis" to readLong(appContext, LAST_CONFIRM_REQUEST_AT),
            "lastConfirmSource" to readString(appContext, LAST_CONFIRM_SOURCE),
            "lastConfirmPriority" to readString(appContext, LAST_CONFIRM_PRIORITY),
            "lastConfirmTimeoutMillis" to readLong(appContext, LAST_CONFIRM_TIMEOUT),
            "lastConfirmResult" to readString(appContext, LAST_CONFIRM_RESULT),
            "lastConfirmElapsedMillis" to readLong(appContext, LAST_CONFIRM_ELAPSED),
            "lastConfirmLocationProvider" to
                readString(appContext, LAST_CONFIRM_LOCATION_PROVIDER),
            "lastConfirmLocationAccuracyMeters" to
                readDouble(appContext, LAST_CONFIRM_LOCATION_ACCURACY),
            "lastConfirmLocationAgeMillis" to readLong(appContext, LAST_CONFIRM_LOCATION_AGE),
            "lastConfirmFailureMessage" to readString(appContext, LAST_CONFIRM_FAILURE_MESSAGE),
            "lastNativeExitConfirmTimingMode" to
                readString(appContext, LAST_NATIVE_EXIT_CONFIRM_TIMING_MODE),
            "lastNativeExitConfirmTimingReason" to
                readString(appContext, LAST_NATIVE_EXIT_CONFIRM_TIMING_REASON),
            "lastNativeExitConfirmTimingAtMillis" to
                readLong(appContext, LAST_NATIVE_EXIT_CONFIRM_TIMING_AT),
            "lastNativeEnterConfirmTimingMode" to
                readString(appContext, LAST_NATIVE_ENTER_CONFIRM_TIMING_MODE),
            "lastNativeEnterConfirmTimingReason" to
                readString(appContext, LAST_NATIVE_ENTER_CONFIRM_TIMING_REASON),
            "lastNativeEnterConfirmTimingAtMillis" to
                readLong(appContext, LAST_NATIVE_ENTER_CONFIRM_TIMING_AT),
            "lastBoundaryDecisionAtMillis" to
                readLong(appContext, LAST_BOUNDARY_DECISION_AT),
            "lastBoundaryDecision" to readString(appContext, LAST_BOUNDARY_DECISION),
            "lastBoundaryDecisionFenceId" to
                readString(appContext, LAST_BOUNDARY_DECISION_FENCE_ID),
            "lastBoundaryDecisionSource" to
                readString(appContext, LAST_BOUNDARY_DECISION_SOURCE),
            "lastBoundaryDistanceMeters" to readDouble(appContext, LAST_BOUNDARY_DISTANCE),
            "lastBoundaryRadiusMeters" to readDouble(appContext, LAST_BOUNDARY_RADIUS),
            "lastBoundaryEdgeDistanceMeters" to
                readDouble(appContext, LAST_BOUNDARY_EDGE_DISTANCE),
            "lastBoundaryAccuracyMeters" to readDouble(appContext, LAST_BOUNDARY_ACCURACY),
            "lastBoundaryMock" to readBoolean(appContext, LAST_BOUNDARY_MOCK),
            "lastBoundaryQueuedEvent" to readString(appContext, LAST_BOUNDARY_QUEUED_EVENT),
            "lastMockLocationDecisionAtMillis" to
                readLong(appContext, LAST_MOCK_LOCATION_DECISION_AT),
            "lastMockLocationPolicy" to readString(appContext, LAST_MOCK_LOCATION_POLICY),
            "lastMockLocationAction" to readString(appContext, LAST_MOCK_LOCATION_ACTION),
            "lastMockLocationSource" to readString(appContext, LAST_MOCK_LOCATION_SOURCE),
            "lastMockLocationProvider" to readString(appContext, LAST_MOCK_LOCATION_PROVIDER),
            "lastMockLocationAccuracyMeters" to
                readDouble(appContext, LAST_MOCK_LOCATION_ACCURACY),
            "lastMockLocationAgeMillis" to readLong(appContext, LAST_MOCK_LOCATION_AGE),
            "lastMockLocationSuppressed" to
                readBoolean(appContext, LAST_MOCK_LOCATION_SUPPRESSED),
            "lastEventProcessorAtMillis" to readLong(appContext, LAST_EVENT_PROCESSOR_AT),
            "lastEventProcessorInput" to readString(appContext, LAST_EVENT_PROCESSOR_INPUT),
            "lastEventProcessorSource" to readString(appContext, LAST_EVENT_PROCESSOR_SOURCE),
            "lastEventProcessorResult" to readString(appContext, LAST_EVENT_PROCESSOR_RESULT),
            "lastEventProcessorCandidateCount" to
                readLong(appContext, LAST_EVENT_PROCESSOR_CANDIDATE_COUNT),
            "lastSmartCallbackAtMillis" to readLong(appContext, LAST_SMART_CALLBACK_AT),
            "lastSmartCallbackEvent" to readString(appContext, LAST_SMART_CALLBACK_EVENT),
            "lastSmartCallbackFenceId" to readString(appContext, LAST_SMART_CALLBACK_FENCE_ID),
            "lastSmartCallbackSource" to readString(appContext, LAST_SMART_CALLBACK_SOURCE),
            "lastSmartCallbackResult" to readString(appContext, LAST_SMART_CALLBACK_RESULT),
            "lastSmartCallbackEnqueuedAtMillis" to
                readLong(appContext, LAST_SMART_CALLBACK_ENQUEUED_AT),
            "lastSmartCallbackEventAtMillis" to
                readLong(appContext, LAST_SMART_CALLBACK_EVENT_AT),
            "lastSmartCallbackTimestampSource" to
                readString(appContext, LAST_SMART_CALLBACK_TIMESTAMP_SOURCE),
            "lastSmartCallbackDeliveryPath" to
                readString(appContext, LAST_SMART_CALLBACK_DELIVERY_PATH),
            "lastSmartCallbackTriggerToDeliveryLatencyMillis" to
                readLong(appContext, LAST_SMART_CALLBACK_TRIGGER_TO_DELIVERY_LATENCY),
            "lastSmartCallbackDeviceIdleModeAtDelivery" to
                readBoolean(appContext, LAST_SMART_CALLBACK_DEVICE_IDLE_AT_DELIVERY),
            "lastCallbackDispatchAtMillis" to
                readLong(appContext, LAST_CALLBACK_DISPATCH_AT),
            "lastCallbackDispatchEvent" to
                readString(appContext, LAST_CALLBACK_DISPATCH_EVENT),
            "lastCallbackDispatchFenceId" to
                readString(appContext, LAST_CALLBACK_DISPATCH_FENCE_ID),
            "lastCallbackDispatchResult" to
                readString(appContext, LAST_CALLBACK_DISPATCH_RESULT),
            "lastCallbackDispatchRetryOnFailure" to
                readBoolean(appContext, LAST_CALLBACK_DISPATCH_RETRY_ON_FAILURE),
            "lastCallbackDispatchEventAtMillis" to
                readLong(appContext, LAST_CALLBACK_DISPATCH_EVENT_AT),
            "lastCallbackDispatchTimestampSource" to
                readString(appContext, LAST_CALLBACK_DISPATCH_TIMESTAMP_SOURCE),
            "lastCallbackDispatchTimeReasonCode" to
                readString(appContext, LAST_CALLBACK_DISPATCH_TIME_REASON_CODE),
            "lastCallbackDispatchTimeTrusted" to
                readBoolean(appContext, LAST_CALLBACK_DISPATCH_TIME_TRUSTED),
            "lastCallbackDispatchTimeRejectionReason" to
                readString(appContext, LAST_CALLBACK_DISPATCH_TIME_REJECTION_REASON),
            "lastCallbackDispatchEvidenceQuality" to
                readString(appContext, LAST_CALLBACK_DISPATCH_EVIDENCE_QUALITY),
            "lastCallbackDispatchError" to
                readString(appContext, LAST_CALLBACK_DISPATCH_ERROR),
            "lastBootRecoverySource" to readString(appContext, LAST_BOOT_RECOVERY_SOURCE),
            "lastBootRecoveryAction" to readString(appContext, LAST_BOOT_RECOVERY_ACTION),
            "lastBootRecoveryStartedAtMillis" to
                readLong(appContext, LAST_BOOT_RECOVERY_STARTED_AT),
            "lastBootRecoveryFinishedAtMillis" to
                readLong(appContext, LAST_BOOT_RECOVERY_FINISHED_AT),
            "lastBootRecoveryResult" to readString(appContext, LAST_BOOT_RECOVERY_RESULT),
            "lastBootRecoveryNativeFenceCount" to
                readLong(appContext, LAST_BOOT_RECOVERY_NATIVE_FENCE_COUNT),
            "lastBootRecoverySmartFenceCount" to
                readLong(appContext, LAST_BOOT_RECOVERY_SMART_FENCE_COUNT),
            "lastBootRecoveryElapsedMillis" to
                readLong(appContext, LAST_BOOT_RECOVERY_ELAPSED),
            "lastBootRecoveryFailureMessage" to
                readString(appContext, LAST_BOOT_RECOVERY_FAILURE_MESSAGE),
            "lastBootFollowUpScheduledAtMillis" to
                readLong(appContext, LAST_BOOT_FOLLOW_UP_SCHEDULED_AT),
            "lastBootFollowUpTriggerAtMillis" to
                readLong(appContext, LAST_BOOT_FOLLOW_UP_TRIGGER_AT),
            "lastBootFollowUpAction" to readString(appContext, LAST_BOOT_FOLLOW_UP_ACTION),
            "lastBootFollowUpScheduleResult" to
                readString(appContext, LAST_BOOT_FOLLOW_UP_RESULT),
            "lastBootFollowUpFailureMessage" to
                readString(appContext, LAST_BOOT_FOLLOW_UP_FAILURE_MESSAGE),
            "lastBroadcastDeadlineFinishedAtMillis" to
                readLong(appContext, LAST_BROADCAST_DEADLINE_FINISHED_AT),
            "lastBroadcastDeadlineReason" to
                readString(appContext, LAST_BROADCAST_DEADLINE_REASON),
            "lastBroadcastDeadlineTag" to
                readString(appContext, LAST_BROADCAST_DEADLINE_TAG),
            "lastRecoveryMainThreadTimeoutAtMillis" to
                readLong(appContext, LAST_RECOVERY_MAIN_THREAD_TIMEOUT_AT),
            "lastRecoveryMainThreadTimeoutSource" to
                readString(appContext, LAST_RECOVERY_MAIN_THREAD_TIMEOUT_SOURCE),
            "lastRecoveryMainThreadTimeoutReason" to
                readString(appContext, LAST_RECOVERY_MAIN_THREAD_TIMEOUT_REASON),
        )
    }

    private fun fenceStateDiagnostics(
        context: Context,
        fences: List<com.yarithdev.smart_geofence.store.SmartGeofenceFence>,
        pendingExitDetails: List<Map<String, Any?>>,
        pendingEnterDetails: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val observedStates = FenceObservationStore.snapshot(context)
        val dedupRecords = EventDedupStore.snapshot(context)
        val exitsByFence = pendingExitDetails.associateBy { it["fenceId"] as? String ?: "" }
        val entersByFence = pendingEnterDetails.associateBy { it["fenceId"] as? String ?: "" }
        val fencesById = fences.associateBy { it.id }
        val ids = linkedSetOf<String>()
        ids += fencesById.keys
        ids += observedStates.keys
        ids += dedupRecords.keys
        ids += exitsByFence.keys.filter { it.isNotBlank() }
        ids += entersByFence.keys.filter { it.isNotBlank() }

        return ids.sorted().associateWith { fenceId ->
            val fence = fencesById[fenceId]
            val dedup = dedupRecords[fenceId]
            val pendingExit = exitsByFence[fenceId]
            val pendingEnter = entersByFence[fenceId]
            linkedMapOf(
                "mirrored" to (fence != null),
                "armed" to fence?.armed,
                "radiusMeters" to fence?.radiusMeters,
                "requestedRadiusMeters" to fence?.requestedRadiusMeters,
                "effectiveRadiusMeters" to fence?.radiusMeters,
                "radiusNormalization" to fence?.radiusNormalizationMap(),
                "triggers" to fence?.let { triggersFor(it) },
                "observedState" to observedStates[fenceId],
                "lastEmittedEvent" to dedup?.get("event"),
                "lastEmittedSource" to dedup?.get("source"),
                "lastEmittedAtMillis" to dedup?.get("deliveredAtMillis"),
                "pendingNativeExit" to (pendingExit != null),
                "pendingNativeExitTriggeredAtMillis" to pendingExit?.get("triggeredAtMillis"),
                "pendingNativeExitDeadlineAtMillis" to pendingExit?.get("deadlineAtMillis"),
                "pendingNativeExitSource" to pendingExit?.get("source"),
                "pendingNativeEnter" to (pendingEnter != null),
                "pendingNativeEnterTriggeredAtMillis" to pendingEnter?.get("triggeredAtMillis"),
                "pendingNativeEnterDeadlineAtMillis" to pendingEnter?.get("deadlineAtMillis"),
                "pendingNativeEnterSource" to pendingEnter?.get("source"),
            )
        }
    }

    private fun triggersFor(
        fence: com.yarithdev.smart_geofence.store.SmartGeofenceFence,
    ): List<String> = buildList {
        if (fence.triggersEnter) add("enter")
        if (fence.triggersExit) add("exit")
        if (fence.triggersDwell) add("dwell")
    }

    private fun SmartGeofenceConfig.toMap(): Map<String, Any?> = linkedMapOf(
        "batteryMode" to batteryMode,
        "locationUnavailablePolicy" to locationUnavailablePolicy.configValue,
        "proximityRadiusMeters" to proximityRadiusMeters,
        "escalationEnabled" to escalationEnabled,
        "proximityLocationPriority" to proximityLocationPriority,
        "proximityIntervalMillis" to proximityIntervalMillis,
        "proximityFastestIntervalMillis" to proximityFastestIntervalMillis,
        "proximityMaxWaitMillis" to proximityMaxWaitMillis,
        "proximityMinDisplacementMeters" to proximityMinDisplacementMeters,
        "proximityAdaptiveDisplacementEnabled" to proximityAdaptiveDisplacementEnabled,
        "proximityAdaptiveNearBoundaryDistanceMeters" to
            proximityAdaptiveNearBoundaryDistanceMeters,
        "proximityAdaptiveNearBoundaryDisplacementMeters" to
            proximityAdaptiveNearBoundaryDisplacementMeters,
        "proximityAdaptiveStationaryDisplacementMeters" to
            proximityAdaptiveStationaryDisplacementMeters,
        "proximityAdaptiveHysteresisMeters" to proximityAdaptiveHysteresisMeters,
        "passiveLocationPriority" to passiveLocationPriority,
        "passiveLocationIntervalMillis" to passiveLocationIntervalMillis,
        "passiveLocationFastestIntervalMillis" to passiveLocationFastestIntervalMillis,
        "passiveLocationMaxWaitMillis" to passiveLocationMaxWaitMillis,
        "passiveFollowUpEnabled" to passiveFollowUpEnabled,
        "locationConfirmTimeoutMillis" to locationConfirmTimeoutMillis,
        "proximityConfirmTimeoutMillis" to Constants.PROXIMITY_CONFIRM_TIMEOUT_MILLIS,
        "proximityConfirmMaxAttempts" to proximityConfirmMaxAttempts,
        "proximityConfirmRetryDelayPolicy" to "current_pulse_interval",
        "pulseLocationMaxAccuracyMeters" to pulseLocationMaxAccuracyMeters,
        "eventLocationMaxAccuracyMeters" to eventLocationMaxAccuracyMeters,
        "nativeExitConfirmationEnabled" to nativeExitConfirmationEnabled,
        "nativeEnterConfirmationEnabled" to nativeEnterConfirmationEnabled,
        "nativeConfirmDelayMillis" to nativeConfirmDelayMillis,
        "nativeConfirmMaxAttempts" to nativeConfirmMaxAttempts,
        "transitionValidationEnabled" to transitionValidationEnabled,
        "transitionValidationEnterEnabled" to transitionValidationEnterEnabled,
        "transitionValidationExitEnabled" to transitionValidationExitEnabled,
        "transitionValidationMinimumDelayMillis" to transitionValidationMinimumDelayMillis,
        "nativeEnterConfirmRadiusSlackMeters" to nativeEnterConfirmRadiusSlackMeters,
        "nativeEnterPayloadSanityEnabled" to nativeEnterPayloadSanityEnabled,
        "nativeEnterPayloadDistanceSlackMeters" to nativeEnterPayloadDistanceSlackMeters,
        "teleportGuardEnabled" to teleportGuardEnabled,
        "teleportMaxSpeedMetersPerSecond" to teleportMaxSpeedMetersPerSecond,
        "mockLocationPolicy" to mockLocationPolicy.configValue,
        "proximityPulseEnabled" to proximityPulseEnabled,
        "proximityPulseActivationDistanceMeters" to proximityPulseActivationDistanceMeters,
        "proximityPulseIntervalMillis" to proximityPulseIntervalMillis,
        "proximityPulseTransitionConfirmationIntervalMillis" to
            proximityPulseTransitionConfirmationIntervalMillis,
        "proximityPulseTransitionConfirmationBurstDurationMillis" to
            proximityPulseTransitionConfirmationBurstDurationMillis,
        "proximityPulseActiveStartMinuteOfDay" to proximityPulseActiveStartMinuteOfDay,
        "proximityPulseActiveEndMinuteOfDay" to proximityPulseActiveEndMinuteOfDay,
        "proximityPulseOutsideActiveHoursIntervalMultiplier" to
            proximityPulseOutsideActiveHoursIntervalMultiplier,
        "proximityPulseMinIntervalMillis" to proximityPulseMinIntervalMillis,
        "foregroundNotificationTitle" to foregroundNotificationTitle,
        "foregroundNotificationChannelId" to foregroundNotificationChannelId,
        "foregroundNotificationChannelName" to foregroundNotificationChannelName,
        "foregroundNotificationId" to foregroundNotificationId,
        "foregroundNotificationSmallIconResourceName" to foregroundNotificationSmallIconResourceName,
        "foregroundNotificationSticky" to foregroundNotificationSticky,
        "foregroundNotificationTapAction" to foregroundNotificationTapAction.configValue,
        "foregroundNotificationShowWhileMonitoring" to foregroundNotificationShowWhileMonitoring,
        "activityStationaryTtlMillis" to activityStationaryTtlMillis,
        "activityPeriodicBackstopEnabled" to activityPeriodicBackstopEnabled,
        "activityUpdateIntervalMillis" to activityUpdateIntervalMillis,
        "activityMovingProximityCheckDelayMillis" to activityMovingProximityCheckDelayMillis,
        "activityFusedLocationStaleAfterMillis" to activityFusedLocationStaleAfterMillis,
        "recoveryTimesMinuteOfDay" to recoveryTimesMinuteOfDay,
        "recoveryAlarmPolicy" to recoveryAlarmPolicy.configValue,
        "recoveryInexactGuardDelayMillis" to recoveryInexactGuardDelayMillis,
        "exactAlarmPermissionMode" to exactAlarmPermissionMode.configValue,
        "passiveLocationEnabled" to passiveLocationEnabled,
        "foregroundServiceLaunchTimeoutMillis" to foregroundServiceLaunchTimeoutMillis,
        "foregroundServiceStartDelayMillis" to foregroundServiceStartDelayMillis,
        "foregroundServiceRearmDelayMillis" to foregroundServiceRearmDelayMillis,
        "foregroundServiceCallbackTimeoutMillis" to foregroundServiceCallbackTimeoutMillis,
        "foregroundServiceSticky" to foregroundServiceSticky,
        "confirmQueueMaxAgeMillis" to confirmQueueMaxAgeMillis,
        "timeIntegrityEnabled" to timeIntegrityEnabled,
        "timeIntegrityConfigJson" to timeIntegrityConfigJson,
        "confirmMaxTransientFailures" to Constants.DEFAULT_CONFIRM_MAX_TRANSIENT_FAILURES,
        "confirmRetryMaxDelayMillis" to Constants.DEFAULT_CONFIRM_RETRY_MAX_DELAY_MILLIS,
        "logFileEnabled" to logFileEnabled,
        "logFileVerbose" to logFileVerbose,
        "maxLogFileBytes" to maxLogFileBytes,
        "retryOnCallbackFailure" to retryOnCallbackFailure,
    )

    private fun proximityPendingIntentExists(context: Context): Boolean {
        val intent = Intent(context, FusedLocationUpdateReceiver::class.java).apply {
            data = Uri.parse(Constants.PENDING_INTENT_DATA_PROXIMITY)
            putExtra(
                Constants.EXTRA_LOCATION_WAKE_SOURCE,
                Constants.LOCATION_WAKE_SOURCE_PROXIMITY
            )
        }
        return pendingIntentExists(context, Constants.PENDING_INTENT_REQUEST_BASE, intent)
    }

    private fun passiveLocationPendingIntentExists(context: Context): Boolean {
        val intent = Intent(context, FusedLocationUpdateReceiver::class.java).apply {
            data = Uri.parse(Constants.PENDING_INTENT_DATA_PASSIVE_LOCATION)
            putExtra(
                Constants.EXTRA_LOCATION_WAKE_SOURCE,
                Constants.LOCATION_WAKE_SOURCE_PASSIVE
            )
        }
        return pendingIntentExists(context, Constants.PENDING_INTENT_REQUEST_PASSIVE_LOCATION, intent)
    }

    private fun pendingIntentExists(
        context: Context,
        requestCode: Int,
        intent: Intent,
    ): Boolean {
        var flags = PendingIntent.FLAG_NO_CREATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags) != null
    }

    private fun hasLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun smartLayerMode(
        hasFences: Boolean,
        config: SmartGeofenceConfig,
        locationServicesEnabled: Boolean?,
        locationPermissionGranted: Boolean,
        fineLocationPermissionGranted: Boolean,
        backgroundLocationPermissionGranted: Boolean,
        fusedLocationUpdateReceiverDeclared: Boolean,
        locationConfirmCanRun: Boolean,
    ): SmartLayerMode {
        if (!hasFences) return SmartLayerMode("disabled", "no_fences")
        if (!config.escalationEnabled) {
            return SmartLayerMode("native_only", "escalation_disabled")
        }
        if (locationServicesEnabled == false) {
            return SmartLayerMode("disabled", "location_services_disabled")
        }
        if (!locationPermissionGranted) {
            return SmartLayerMode("native_only", "location_permission_missing")
        }
        if (!backgroundLocationPermissionGranted) {
            return SmartLayerMode("native_only", "background_location_missing")
        }
        if (!fineLocationPermissionGranted) {
            return SmartLayerMode("coarse_rejected", "fine_location_missing")
        }
        if (!fusedLocationUpdateReceiverDeclared) {
            return SmartLayerMode("native_only", "fused_receiver_missing")
        }
        if (!locationConfirmCanRun) {
            return SmartLayerMode("limited", "foreground_confirm_unavailable")
        }
        return SmartLayerMode("full", "ready")
    }

    private fun hasBackgroundLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun hasActivityPermission(context: Context): Boolean =
        ActivityRecognitionPermissionController.hasRequiredPermission(context)

    private fun hasForegroundServiceLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return granted(context, "android.permission.FOREGROUND_SERVICE_LOCATION")
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return granted(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isPowerSaveMode(context: Context): Boolean? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return powerManager.isPowerSaveMode
    }

    private fun isDeviceIdleMode(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return powerManager.isDeviceIdleMode
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun batteryState(context: Context): BatteryState {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) {
            (level * 100.0 / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        return BatteryState(levelPercent, charging)
    }

    private fun exactAlarmPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarmManager?.canScheduleExactAlarms() == true
    }

    private fun permissionDeclared(context: Context, permission: String): Boolean {
        return try {
            val packageInfo = AndroidPackageManagerCompat.getPackageInfo(
                context.packageManager,
                context.packageName,
                PackageManager.GET_PERMISSIONS.toLong(),
            )
            packageInfo.requestedPermissions?.contains(permission) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun locationServicesEnabled(context: Context): Boolean? {
        return try {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return null
            LocationManagerCompat.isLocationEnabled(locationManager)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun hasReceiver(context: Context, receiverClass: Class<*>): Boolean {
        return try {
            val component = ComponentName(context, receiverClass)
            AndroidPackageManagerCompat.getReceiverInfo(
                context.packageManager,
                component,
            )
            true
        } catch (ignored: PackageManager.NameNotFoundException) {
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
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun serviceHasLocationType(serviceInfo: ServiceInfo?): Boolean {
        if (serviceInfo == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return (serviceInfo.foregroundServiceType and
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
    }

    private fun recordJournal(
        context: Context,
        stage: String,
        reasonCode: String,
        traceId: String? = null,
        eventId: String? = null,
        fenceId: String? = null,
        event: String? = null,
        source: String? = null,
        extras: Map<String, Any?> = emptyMap(),
    ) {
        runCatching {
            val appContext = context.applicationContext
            val entry = JSONObject()
                .put("atMillis", System.currentTimeMillis())
                .put("stage", stage)
                .put("reasonCode", reasonCode)
                .put("deviceIdleMode", isDeviceIdleMode(appContext))
                .put("powerSaveMode", isPowerSaveMode(appContext))
            fenceId?.let { entry.put("fenceId", it) }
            event?.let { entry.put("event", it) }
            source?.let { entry.put("source", it) }
            traceId?.takeIf { it.isNotBlank() }?.let { entry.put("traceId", it.take(128)) }
            eventId?.takeIf { it.isNotBlank() }?.let { entry.put("eventId", it.take(128)) }
            extras.forEach { (key, value) -> putJsonValue(entry, key, value) }
            DiagnosticEventJournal.append(prefs(appContext), entry)
        }
    }

    @Synchronized
    private fun incrementCounter(context: Context, name: String) {
        runCatching {
            val appContext = context.applicationContext
            val key = COUNTER_PREFIX + sanitizeCounterName(name)
            val next = (prefs(appContext).safeLongOrNull(key) ?: 0L) + 1L
            prefs(appContext).edit().putLong(key, next).apply()
        }
    }

    private fun readCounters(context: Context): Map<String, Long> =
        prefs(context).all.entries
            .filter { it.key.startsWith(COUNTER_PREFIX) }
            .sortedBy { it.key }
            .associate { (key, value) ->
                key.removePrefix(COUNTER_PREFIX) to when (value) {
                    is Number -> value.toLong()
                    else -> 0L
                }
            }

    private fun putJsonValue(target: JSONObject, key: String, value: Any?) {
        when (value) {
            null -> target.put(key, JSONObject.NULL)
            is Boolean -> target.put(key, value)
            is Int -> target.put(key, value)
            is Long -> target.put(key, value)
            is Float -> target.put(key, value.toDouble())
            is Double -> if (value.isFinite()) target.put(key, value) else target.put(key, JSONObject.NULL)
            is String -> target.put(key, value)
            else -> target.put(key, value.toString())
        }
    }

    private fun sanitizeCounterName(value: String): String =
        value.lowercase().map { char ->
            when {
                char in 'a'..'z' -> char
                char in '0'..'9' -> char
                else -> '_'
            }
        }.joinToString("")

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun readString(context: Context, key: String): String? =
        prefs(context).safeString(key)

    private fun readLong(context: Context, key: String): Long? {
        return prefs(context).safeLongOrNull(key)
    }

    private fun readDouble(context: Context, key: String): Double? =
        prefs(context).safeString(key)?.toDoubleOrNull()

    private fun readBoolean(context: Context, key: String): Boolean? {
        return prefs(context).safeBooleanOrNull(key)
    }

    private fun SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?
    ): SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    private fun SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?
    ): SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, value)

    private fun SharedPreferences.Editor.putNullableBoolean(
        key: String,
        value: Boolean?
    ): SharedPreferences.Editor =
        if (value == null) remove(key) else putBoolean(key, value)

    private fun SharedPreferences.Editor.putNullableDoubleString(
        key: String,
        value: Double?
    ): SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value.toString())

    private fun accuracyMeters(location: Location): Double? =
        if (location.hasAccuracy()) location.accuracy.toDouble() else null

    private fun ageMillis(location: Location): Long? =
        if (location.time > 0L) {
            (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        } else {
            null
        }

    private data class BatteryState(
        val levelPercent: Int?,
        val charging: Boolean?,
    )

    private data class SmartLayerMode(
        val mode: String,
        val reason: String,
    )
}
