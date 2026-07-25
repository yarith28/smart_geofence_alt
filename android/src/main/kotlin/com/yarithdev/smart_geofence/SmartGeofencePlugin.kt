package com.yarithdev.smart_geofence

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yarithdev.smart_geofence.activity.ActivityRecognitionPermissionController
import com.yarithdev.smart_geofence.activity.ActivityRecognitionPermissionStatus
import com.yarithdev.smart_geofence.alarm.ExactAlarmPermissionController
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigTransport
import com.yarithdev.smart_geofence.confirm.LocationConfirmManager
import com.yarithdev.smart_geofence.confirm.SmartGeofenceEventTimingStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.SmartGeofenceController
import com.yarithdev.smart_geofence.foreground.CallbackForegroundService
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.monitoring.LocationAvailabilityStopController
import com.yarithdev.smart_geofence.monitoring.MonitoringStopSnapshot
import com.yarithdev.smart_geofence.monitoring.MonitoringStopStateStore
import com.yarithdev.smart_geofence.monitoring.MonitoringStoppedCallbackNotifier
import com.yarithdev.smart_geofence.monitoring.MonitoringStoppedEvent
import com.yarithdev.smart_geofence.monitoring.TerminalMonitoringStopController
import com.yarithdev.smart_geofence.registration.SharedPreferencesRegistrationRevisionStore
import com.yarithdev.smart_geofence.registration.SmartGeofenceRegistrationTransactions
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.store.SmartGeofenceFence
import com.yarithdev.smart_geofence.transition.NativeTransitionCoordinator
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.util.Locale
import java.util.UUID

class SmartGeofencePlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {
    private lateinit var channel: MethodChannel
    private lateinit var appContext: Context
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingActivityPermissionResult: Result? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val registrationOwnerId = UUID.randomUUID().toString()
    private val registrationRevisionStore by lazy {
        SharedPreferencesRegistrationRevisionStore(appContext)
    }
    private var locationAvailabilityLifecycleCallbacks:
        Application.ActivityLifecycleCallbacks? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        SmartGeofenceLogger.initialize(appContext)
        channel = MethodChannel(binding.binaryMessenger, Constants.METHOD_CHANNEL_NAME)
        channel.setMethodCallHandler(this)
        MonitoringStoppedCallbackNotifier.addListener(
            registrationOwnerId,
            ::deliverMonitoringStoppedEvent,
        )
        registerLocationAvailabilityLifecycleCallbacks()
        LocationAvailabilityStopController.stopIfUnavailable(
            appContext,
            source = "plugin_attached",
        )
        MonitoringStopStateStore.pendingEvent(appContext)?.let(
            MonitoringStoppedCallbackNotifier::dispatchOnce,
        )
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")

            "beginRegistrationTransaction" -> beginRegistrationTransaction(call, result)

            "commitRegistrationTransaction" -> commitRegistrationTransaction(call, result)

            "abortRegistrationTransaction" -> abortRegistrationTransaction(call, result)

            "configure" -> {
                val config = try {
                    parseConfig(call)
                } catch (error: IllegalArgumentException) {
                    result.error("invalid_argument", error.message, null)
                    return
                }
                if (failIfStrictExactAlarmDenied(config, "configure", result)) return
                try {
                    val previousConfig = SmartGeofenceConfigStore.load(appContext)
                    SmartGeofenceConfigStore.save(appContext, config)
                    if (!previousConfig.hasSameTransitionValidation(config)) {
                        NativeTransitionCoordinator.clear(appContext, "transition_config_changed")
                        LocationConfirmManager.cancelNativeExitConfirmIfNoPending(
                            appContext,
                            "transition_config_changed",
                        )
                        LocationConfirmManager.cancelNativeEnterConfirmIfNoPending(
                            appContext,
                            "transition_config_changed",
                        )
                        LocationConfirmManager.cancelPendingValidationPulseIfNoPending(
                            appContext,
                            "transition_config_changed",
                        )
                    }
                } catch (error: IllegalArgumentException) {
                    result.error("invalid_argument", error.message, null)
                    return
                } catch (error: IllegalStateException) {
                    result.error("persistence_error", error.message, null)
                    return
                }
                SmartGeofenceLogger.configure(
                    appContext,
                    config.logFileEnabled,
                    config.maxLogFileBytes,
                    config.logFileVerbose
                )
                SmartGeofenceController.refresh(appContext)
                SmartGeofenceLogger.d(appContext, TAG, "Configured: $config")
                result.success(null)
            }

            "start" -> {
                val config = SmartGeofenceConfigStore.load(appContext)
                if (ExactAlarmPermissionController.isStrictBlocked(appContext, config)) {
                    SmartGeofenceController.stop(appContext)
                    failIfStrictExactAlarmDenied(config, "start", result)
                    return
                }
                SmartGeofenceController.start(appContext, config)
                result.success(null)
            }

            "stop" -> {
                SmartGeofenceController.stop(appContext)
                result.success(null)
            }

            "registerFence" -> {
                if (!requireRegistrationTransaction(call, result)) return
                val config = SmartGeofenceConfigStore.load(appContext)
                if (failIfStrictExactAlarmDenied(config, "registerFence", result)) return
                val parsed = parseFence(call, result) ?: return
                val previous = FenceStore.upsert(appContext, parsed.fence)
                if (parsed.resetState) {
                    FenceStore.applyDefinitionChange(appContext, parsed.fence.id)
                }
                if (parsed.refresh) {
                    SmartGeofenceController.refresh(appContext)
                }
                result.success(previous?.toMap())
            }

            "getFenceMirror" -> {
                val id = nullableStringArg(call, "id")
                result.success(
                    id?.let { FenceStore.get(appContext, it, includePending = true)?.toMap() }
                )
            }

            "getFenceMirrors" -> result.success(
                FenceStore.getAll(appContext, includePending = true).map { it.toMap() }
            )

            "replaceFenceMirrors" -> {
                if (!requireRegistrationTransaction(call, result)) return
                val args = call.arguments as? Map<*, *>
                val rawFences = args?.get("fences") as? List<*>
                val refresh = args?.let { boolArg(it, "refresh", true) }
                val applyStateChanges = args?.let { boolArg(it, "applyStateChanges", true) }
                if (rawFences == null || refresh == null || applyStateChanges == null) {
                    result.error(
                        "invalid_argument",
                        "replaceFenceMirrors expects a fences list and optional refresh flag.",
                        null,
                    )
                    return
                }
                val fences = rawFences.mapIndexed { index, item ->
                    val parsed = parseFenceArgs(
                        item as? Map<*, *>,
                        result,
                        "replaceFenceMirrors[$index]",
                    ) ?: return
                    parsed.fence
                }
                val stateChanges = FenceStore.replaceAll(appContext, fences)
                if (applyStateChanges) {
                    FenceStore.applyReplacementState(appContext, stateChanges)
                }
                if (refresh) {
                    SmartGeofenceController.refresh(appContext)
                }
                result.success(null)
            }

            "getRetryOnCallbackFailure" -> result.success(
                SmartGeofenceConfigStore.load(appContext).retryOnCallbackFailure
            )

            "getTimeIntegrityConfig" -> {
                val config = SmartGeofenceConfigStore.load(appContext)
                result.success(
                    mapOf(
                        "timeIntegrityEnabled" to config.timeIntegrityEnabled,
                        "timeIntegrityConfigJson" to config.timeIntegrityConfigJson,
                    )
                )
            }

            "getEventTiming" -> {
                val fenceId = nullableStringArg(call, "fenceId")
                val eventName = nullableStringArg(call, "event")
                val eventAtMillis = longArgOrNull(call, "eventAtMillis")
                result.success(
                    if (fenceId == null || eventName == null || eventAtMillis == null) {
                        null
                    } else {
                        SmartGeofenceEventTimingStore.lookup(
                            appContext,
                            fenceId,
                            eventName,
                            eventAtMillis,
                        )?.toMap()
                    }
                )
            }

            "logTimeIntegrity" -> {
                logTimeIntegrity(call)
                result.success(null)
            }

            "reportCallbackDispatch" -> {
                SmartGeofenceDiagnostics.recordCallbackDispatch(
                    appContext,
                    fenceId = nullableStringArg(call, "fenceId"),
                    event = nullableStringArg(call, "event"),
                    result = stringArg(call, "result", "unknown"),
                    retryOnFailure = boolArgOrNull(call, "retryOnFailure"),
                    eventAtMillis = longArgOrNull(call, "eventAtMillis"),
                    errorMessage = nullableStringArg(call, "error"),
                    timestampSource = nullableStringArg(call, "timestampSource"),
                    timeReasonCode = nullableStringArg(call, "timeReasonCode"),
                    timeTrusted = boolArgOrNull(call, "timeTrusted"),
                    timeRejectionReason = nullableStringArg(call, "timeRejectionReason"),
                    evidenceQuality = nullableStringArg(call, "evidenceQuality"),
                    traceId = nullableStringArg(call, "traceId"),
                    eventId = nullableStringArg(call, "eventId"),
                )
                result.success(null)
            }

            "reportGeofenceSync" -> {
                val rollbackFailures = (rawArg(call, "rollbackFailures") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?: emptyList()
                SmartGeofenceDiagnostics.recordGeofenceSync(
                    appContext,
                    result = stringArg(call, "result", "unknown"),
                    desiredCount = intArg(call, "desiredCount", 0),
                    previousCount = intArg(call, "previousCount", 0),
                    errorMessage = nullableStringArg(call, "error"),
                    rollbackFailures = rollbackFailures,
                )
                result.success(null)
            }

            "removeFence" -> {
                if (!requireRegistrationTransaction(call, result)) return
                val id = nullableStringArg(call, "id")
                if (id == null) {
                    result.error("invalid_argument", "Missing fence id.", null)
                } else {
                    val removedIds = FenceStore.remove(appContext, id)
                    FenceStore.applyRemovedState(appContext, removedIds)
                    SmartGeofenceController.refresh(appContext)
                    result.success(null)
                }
            }

            "removeAllFences" -> {
                if (!requireRegistrationTransaction(call, result)) return
                val removedIds = FenceStore.removeAll(appContext)
                SmartGeofenceController.stop(appContext)
                FenceStore.applyRemoveAllState(appContext, removedIds)
                result.success(null)
            }

            "getStatus" -> result.success(SmartGeofenceDiagnostics.getStatus(appContext))

            "isTerminallyStopped" -> result.success(
                MonitoringStopStateStore.snapshot(appContext).terminallyStopped,
            )

            "getPendingMonitoringStoppedEvent" -> result.success(
                MonitoringStopStateStore.pendingEvent(appContext)?.toMap(),
            )

            "ackMonitoringStoppedEvent" -> {
                val eventId = nullableStringArg(call, "eventId")
                if (eventId == null) {
                    result.error("invalid_argument", "Missing monitoring stop event id.", null)
                } else {
                    val acknowledged = MonitoringStopStateStore.acknowledge(appContext, eventId)
                    result.success(acknowledged)
                    if (acknowledged) dispatchNextMonitoringStoppedEvent()
                }
            }

            "getExactAlarmPermissionStatus" -> result.success(
                ExactAlarmPermissionController.status(appContext).configValue
            )

            "canScheduleExactAlarms" -> result.success(
                ExactAlarmPermissionController.canScheduleExactAlarms(appContext)
            )

            "openExactAlarmPermissionSettings" -> result.success(
                ExactAlarmPermissionController.openSettings(appContext).opened
            )

            "getActivityRecognitionPermissionStatus" -> result.success(
                ActivityRecognitionPermissionController.status(appContext, activity).configValue
            )

            "requestActivityRecognitionPermission" -> requestActivityRecognitionPermission(result)

            "openActivityRecognitionPermissionSettings" -> result.success(
                ActivityRecognitionPermissionController.openSettings(appContext)
            )

            "readLogs" -> {
                SmartGeofenceLogger.readAsync(appContext) { outcome ->
                    reply(result, outcome)
                }
            }

            "clearLogs" -> {
                SmartGeofenceLogger.clearAsync(appContext) { outcome ->
                    replyEmpty(result, outcome)
                }
            }

            "promoteCallbackToForeground" -> {
                CallbackForegroundService.promote(appContext) { error ->
                    if (error == null) {
                        result.success(null)
                    } else {
                        replyCallbackForegroundError(result, error)
                    }
                }
            }

            "demoteCallbackToBackground" -> {
                CallbackForegroundService.demote(appContext)
                result.success(null)
            }

            "reportCallbackForegroundLifecycleFailure" -> {
                SmartGeofenceDiagnostics.recordForegroundLifecycleFailure(
                    appContext,
                    call.argument<String>("stage") ?: "unknown",
                    call.argument<String>("error")
                )
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        unregisterLocationAvailabilityLifecycleCallbacks()
        MonitoringStoppedCallbackNotifier.removeListener(registrationOwnerId)
        registrationTransactionCoordinator.releaseOwner(registrationOwnerId)
        enforceTerminalStopAfterRegistrationSettlement("registration_owner_released")
        channel.setMethodCallHandler(null)
        finishPendingActivityPermission(ActivityRecognitionPermissionStatus.RequestUnavailable)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity(completePendingRequest = false)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachActivity(completePendingRequest = true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != ActivityRecognitionPermissionController.REQUEST_CODE) return false
        val status = ActivityRecognitionPermissionController.resolveRequestResult(
            appContext,
            activity,
            grantResults
        )
        finishPendingActivityPermission(status)
        return true
    }

    private fun attachActivity(binding: ActivityPluginBinding) {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = binding
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        LocationAvailabilityStopController.stopIfUnavailable(
            appContext,
            source = "activity_attached",
        )
    }

    private fun detachActivity(completePendingRequest: Boolean) {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
        activity = null
        if (completePendingRequest) {
            finishPendingActivityPermission(ActivityRecognitionPermissionStatus.RequestUnavailable)
        }
    }

    private fun requestActivityRecognitionPermission(result: Result) {
        val current = ActivityRecognitionPermissionController.status(appContext, activity)
        if (current != ActivityRecognitionPermissionStatus.Denied) {
            finishActivityPermissionRequest(result, current)
            return
        }
        val activeActivity = activity
        if (activeActivity == null) {
            finishActivityPermissionRequest(
                result,
                ActivityRecognitionPermissionStatus.RequestUnavailable
            )
            return
        }
        if (pendingActivityPermissionResult != null) {
            result.error(
                "request_in_progress",
                "Activity Recognition permission request is already in progress.",
                null
            )
            return
        }
        pendingActivityPermissionResult = result
        ActivityRecognitionPermissionController.request(activeActivity)
    }

    private fun beginRegistrationTransaction(call: MethodCall, result: Result) {
        val operation = nullableStringArg(call, "operation")?.takeIf { it.isNotBlank() }
        if (operation == null) {
            result.error("invalid_argument", "Missing registration transaction operation.", null)
            return
        }
        val startsNewMonitoringSession = operation.startsNewMonitoringSession()
        val cleanupFenceIds = (rawArg(call, "cleanupFenceIds") as? List<*>)
            ?.mapNotNull { it as? String }
            ?.filterTo(linkedSetOf()) { it.isNotBlank() }
            ?: emptySet()
        var stopState = MonitoringStopStateStore.snapshot(appContext)
        if (shouldRejectRegistrationDuringTerminalStop(
                startsNewMonitoringSession,
                stopState,
            )
        ) {
            result.error(
                "monitoring_terminally_stopped",
                "The current monitoring session has permanently stopped.",
                stopState.toMap(),
            )
            return
        }
        if (shouldReconcileTerminalStopBeforeRegistration(
                startsNewMonitoringSession,
                stopState,
            )
        ) {
            TerminalMonitoringStopController.enforce(
                appContext,
                "registration_transaction_cleanup_retry",
            )
            stopState = MonitoringStopStateStore.snapshot(appContext)
            if (!stopState.nativeCleanupComplete) {
                result.error(
                    "terminal_stop_cleanup_pending",
                    "The previous terminal stop is still removing native registrations.",
                    stopState.toMap(),
                )
                return
            }
        }
        try {
            val grant = registrationTransactionCoordinator.begin(
                registrationOwnerId,
                operation,
                registrationRevisionStore,
                cleanupFenceIds,
            )
            if (grant.acquired &&
                startsNewMonitoringSession &&
                !MonitoringStopStateStore.beginNewSession(appContext)
            ) {
                grant.token?.let {
                    registrationTransactionCoordinator.abort(registrationOwnerId, it)
                }
                result.error(
                    "terminal_stop_session_start_failed",
                    "Could not persist the new monitoring session.",
                    MonitoringStopStateStore.snapshot(appContext).toMap(),
                )
                return
            }
            if (grant.acquired &&
                startsNewMonitoringSession &&
                LocationAvailabilityStopController.stopIfUnavailable(
                    appContext,
                    source = "registration_transaction_started",
                )
            ) {
                grant.token?.let {
                    registrationTransactionCoordinator.abort(registrationOwnerId, it)
                }
                enforceTerminalStopAfterRegistrationSettlement(
                    "registration_transaction_rejected_location_unavailable",
                )
                result.error(
                    "monitoring_terminally_stopped",
                    "Monitoring stopped because location is unavailable.",
                    MonitoringStopStateStore.snapshot(appContext).toMap(),
                )
                return
            }
            result.success(
                mapOf(
                    "acquired" to grant.acquired,
                    "token" to grant.token,
                    "revision" to grant.revision,
                    "activeOperation" to grant.activeOperation,
                )
            )
        } catch (error: Throwable) {
            result.error("registration_transaction_persistence", error.message, null)
        }
    }

    private fun commitRegistrationTransaction(call: MethodCall, result: Result) {
        val token = nullableStringArg(call, "token")
        val revision = longArgOrNull(call, "revision")
        val advanceRevision = boolArgOrNull(call, "advanceRevision") ?: true
        if (token == null || revision == null) {
            result.error("invalid_argument", "Missing registration transaction identity.", null)
            return
        }
        try {
            val committedRevision = registrationTransactionCoordinator.commit(
                registrationOwnerId,
                token,
                revision,
                registrationRevisionStore,
                advanceRevision,
            )
            val stopState = MonitoringStopStateStore.snapshot(appContext)
            if (stopState.terminallyStopped) {
                TerminalMonitoringStopController.enforce(
                    appContext,
                    "registration_transaction_committed_after_terminal_stop",
                )
                result.error(
                    "monitoring_terminally_stopped",
                    "Monitoring stopped while the registration transaction was active.",
                    MonitoringStopStateStore.snapshot(appContext).toMap(),
                )
            } else {
                result.success(mapOf("revision" to committedRevision))
            }
        } catch (error: Throwable) {
            result.error("registration_transaction_conflict", error.message, null)
        }
    }

    private fun abortRegistrationTransaction(call: MethodCall, result: Result) {
        val token = nullableStringArg(call, "token")
        if (token == null) {
            result.error("invalid_argument", "Missing registration transaction token.", null)
            return
        }
        val aborted = registrationTransactionCoordinator.abort(registrationOwnerId, token)
        if (aborted) {
            enforceTerminalStopAfterRegistrationSettlement(
                "registration_transaction_aborted",
            )
        }
        result.success(aborted)
    }

    private fun requireRegistrationTransaction(call: MethodCall, result: Result): Boolean {
        val token = nullableStringArg(call, "registrationTransactionToken")
        val revision = longArgOrNull(call, "registrationTransactionRevision")
        if (token == null || revision == null) {
            result.error(
                "registration_transaction_required",
                "Registration mirror mutations require an active transaction.",
                null,
            )
            return false
        }
        return try {
            val validation = registrationTransactionCoordinator.validate(
                registrationOwnerId,
                token,
                revision,
                registrationRevisionStore,
            )
            if (!validation.valid) {
                result.error(
                    "registration_transaction_conflict",
                    "Registration transaction is no longer current: ${validation.reason}.",
                    null,
                )
            }
            validation.valid
        } catch (error: Throwable) {
            result.error("registration_transaction_persistence", error.message, null)
            false
        }
    }

    private fun finishPendingActivityPermission(status: ActivityRecognitionPermissionStatus) {
        val pending = pendingActivityPermissionResult ?: return
        pendingActivityPermissionResult = null
        finishActivityPermissionRequest(pending, status)
    }

    private fun enforceTerminalStopAfterRegistrationSettlement(source: String) {
        if (MonitoringStopStateStore.snapshot(appContext).terminallyStopped) {
            TerminalMonitoringStopController.enforce(appContext, source)
        }
    }

    private fun finishActivityPermissionRequest(
        result: Result,
        status: ActivityRecognitionPermissionStatus,
    ) {
        if (status == ActivityRecognitionPermissionStatus.NotRequired ||
            status == ActivityRecognitionPermissionStatus.Granted
        ) {
            SmartGeofenceController.refresh(appContext)
        }
        result.success(status.configValue)
    }

    private fun registerLocationAvailabilityLifecycleCallbacks() {
        if (locationAvailabilityLifecycleCallbacks != null) return
        val application = appContext.applicationContext as? Application
        if (application == null) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Could not register location-availability resume checks: " +
                    "application context is unavailable.",
            )
            return
        }
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                mainHandler.post {
                    LocationAvailabilityStopController.stopIfUnavailable(
                        appContext,
                        source = "activity_resumed",
                    )
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        try {
            application.registerActivityLifecycleCallbacks(callbacks)
            locationAvailabilityLifecycleCallbacks = callbacks
        } catch (error: Throwable) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Could not register location-availability resume checks.",
                error,
            )
        }
    }

    private fun unregisterLocationAvailabilityLifecycleCallbacks() {
        val callbacks = locationAvailabilityLifecycleCallbacks ?: return
        val application = appContext.applicationContext as? Application
        if (application != null) {
            try {
                application.unregisterActivityLifecycleCallbacks(callbacks)
            } catch (error: Throwable) {
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "Could not unregister location-availability resume checks.",
                    error,
                )
            }
        }
        locationAvailabilityLifecycleCallbacks = null
    }

    private fun deliverMonitoringStoppedEvent(event: MonitoringStoppedEvent) {
        mainHandler.post {
            channel.invokeMethod(
                "monitoringStopped",
                event.toMap(),
                object : Result {
                    override fun success(result: Any?) {
                        if (MonitoringStopStateStore.acknowledge(appContext, event.eventId)) {
                            dispatchNextMonitoringStoppedEvent()
                        }
                    }

                    override fun error(
                        errorCode: String,
                        errorMessage: String?,
                        errorDetails: Any?,
                    ) {
                        SmartGeofenceLogger.w(
                            appContext,
                            TAG,
                            "Monitoring stopped callback failed code=$errorCode " +
                                "message=$errorMessage.",
                        )
                    }

                    override fun notImplemented() {
                        SmartGeofenceLogger.d(
                            appContext,
                            TAG,
                            "Monitoring stopped callback has no Dart listener yet.",
                        )
                    }
                },
            )
        }
    }

    private fun dispatchNextMonitoringStoppedEvent() {
        MonitoringStopStateStore.pendingEvent(appContext)?.let(
            MonitoringStoppedCallbackNotifier::dispatchOnce,
        )
    }

    private fun <T> reply(result: Result, outcome: kotlin.Result<T>) {
        mainHandler.post {
            outcome.fold(
                onSuccess = { result.success(it) },
                onFailure = { replyLogFileError(result, it) }
            )
        }
    }

    private fun replyEmpty(result: Result, outcome: kotlin.Result<Unit>) {
        mainHandler.post {
            outcome.fold(
                onSuccess = { result.success(null) },
                onFailure = { replyLogFileError(result, it) }
            )
        }
    }

    private fun replyLogFileError(result: Result, throwable: Throwable) {
        result.error(
            "smart_geofence_log_file_io_failed",
            throwable.message,
            Log.getStackTraceString(throwable)
        )
    }

    private fun replyCallbackForegroundError(result: Result, throwable: Throwable) {
        result.error(
            "callback_foreground_start_failed",
            callbackForegroundFailureMessage(throwable),
            Log.getStackTraceString(throwable)
        )
    }

    private fun logTimeIntegrity(call: MethodCall) {
        val level = stringArg(call, "level", "info").lowercase(Locale.US)
        val message = timeIntegrityLogMessage(call)
        when (level) {
            "debug" -> SmartGeofenceLogger.d(appContext, TIME_INTEGRITY_TAG, message)
            "warning", "warn" -> SmartGeofenceLogger.w(appContext, TIME_INTEGRITY_TAG, message)
            "error" -> SmartGeofenceLogger.e(appContext, TIME_INTEGRITY_TAG, message)
            else -> SmartGeofenceLogger.i(appContext, TIME_INTEGRITY_TAG, message)
        }
    }

    private fun timeIntegrityLogMessage(call: MethodCall): String {
        val stage = nullableStringArg(call, "stage")
            ?.takeIf { it.isNotBlank() }
            ?: "event"
        val message = nullableStringArg(call, "message")
            ?.takeIf { it.isNotBlank() }
            ?: stage
        val extras = timeIntegrityExtras(call)
        return buildString {
            append(stage)
            if (message != stage) {
                append(" ")
                append(message)
            }
            extras.forEach { (key, value) ->
                append(" ")
                append(key)
                append("=")
                append(value)
            }
        }
    }

    private fun timeIntegrityExtras(call: MethodCall): Map<String, String> {
        val rawExtras = rawArg(call, "extras") as? Map<*, *> ?: return emptyMap()
        return rawExtras.entries
            .mapNotNull { entry ->
                val key = entry.key as? String ?: return@mapNotNull null
                key to formatTimeIntegrityLogValue(entry.value)
            }
            .sortedBy { it.first }
            .toMap()
    }

    private fun formatTimeIntegrityLogValue(value: Any?): String =
        when (value) {
            null -> "null"
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") {
                formatTimeIntegrityLogValue(it)
            }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") {
                formatTimeIntegrityLogValue(it)
            }
            else -> value.toString()
        }

    private fun callbackForegroundFailureMessage(throwable: Throwable): String {
        val base = "Failed to promote smart_geofence callback to a foreground service: " +
            "${throwable.javaClass.simpleName}: ${throwable.message ?: throwable.toString()}."
        val android12 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            " Android 12+ can deny foreground-service starts from background callbacks " +
                "outside an allowed launch window."
        } else {
            ""
        }
        val android14 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            " Android 14+ also requires location foreground-service prerequisites, " +
                "including while-in-use location access and FOREGROUND_SERVICE_LOCATION."
        } else {
            ""
        }
        return base + android12 + android14
    }

    private fun parseConfig(rawCall: MethodCall): SmartGeofenceConfig =
        SmartGeofenceConfigTransport.decode(rawCall)

    private fun rawArg(call: MethodCall, key: String): Any? =
        (call.arguments as? Map<*, *>)?.get(key)

    private fun boolArgOrNull(call: MethodCall, key: String): Boolean? =
        rawArg(call, key) as? Boolean

    private fun stringArg(call: MethodCall, key: String, defaultValue: String): String =
        rawArg(call, key) as? String ?: defaultValue

    private fun nullableStringArg(call: MethodCall, key: String): String? =
        rawArg(call, key) as? String

    private fun intArg(call: MethodCall, key: String, defaultValue: Int): Int =
        (rawArg(call, key) as? Number)?.toInt() ?: defaultValue

    private fun longArgOrNull(call: MethodCall, key: String): Long? =
        (rawArg(call, key) as? Number)?.toLong()

    private data class ParsedFenceCall(
        val fence: SmartGeofenceFence,
        val refresh: Boolean,
        val resetState: Boolean,
    )

    private fun parseFence(call: MethodCall, result: Result): ParsedFenceCall? {
        return parseFenceArgs(
            call.arguments as? Map<*, *>,
            result,
            "registerFence",
        )
    }

    private fun parseFenceArgs(
        args: Map<*, *>?,
        result: Result,
        label: String,
    ): ParsedFenceCall? {
        if (args == null) {
            result.error(
                "invalid_argument",
                "$label expects a map of fence fields.",
                null,
            )
            return null
        }

        val id = args["id"] as? String
        val latitude = doubleArgOrNull(args, "latitude")
            ?.takeIf { it in -90.0..90.0 }
        val longitude = doubleArgOrNull(args, "longitude")
            ?.takeIf { it in -180.0..180.0 }
        val radiusMeters = doubleArgOrNull(args, "radiusMeters")?.takeIf { it > 0.0 }
        val requestedRadiusMeters = if (args.containsKey("requestedRadiusMeters")) {
            doubleArgOrNull(args, "requestedRadiusMeters")?.takeIf { it > 0.0 }
        } else {
            radiusMeters
        }
        val effectiveRadiusMeters = if (args.containsKey("effectiveRadiusMeters")) {
            doubleArgOrNull(args, "effectiveRadiusMeters")?.takeIf { it > 0.0 }
        } else {
            radiusMeters
        }
        val radiusNormalizationValid = radiusMeters != null &&
            requestedRadiusMeters != null &&
            effectiveRadiusMeters == radiusMeters &&
            (
                !args.containsKey("requestedRadiusMeters") ||
                    radiusMeters == requestedRadiusMeters.coerceAtLeast(
                        Constants.MIN_ANDROID_GEOFENCE_RADIUS_METERS,
                    )
                )
        val triggers = stringListArgOrNull(args, "triggers")
        val callbackHandle = callbackHandleArgOrNull(args, "callbackHandle")
        val dispatchCallbackHandle = callbackHandleArgOrNull(args, "dispatchCallbackHandle")
        val armed = boolArg(args, "armed", true)
        val refresh = boolArg(args, "refresh", true)
        val resetState = boolArg(args, "resetState", false)

        if (
            id == null ||
            latitude == null ||
            longitude == null ||
            radiusMeters == null ||
            requestedRadiusMeters == null ||
            !radiusNormalizationValid ||
            triggers == null ||
            callbackHandle == null ||
            armed == null ||
            refresh == null ||
            resetState == null
        ) {
            val invalidFields = mutableListOf<String>()
            if (id == null) invalidFields.add("id")
            if (latitude == null) invalidFields.add("latitude")
            if (longitude == null) invalidFields.add("longitude")
            if (radiusMeters == null) invalidFields.add("radiusMeters")
            if (requestedRadiusMeters == null) invalidFields.add("requestedRadiusMeters")
            if (!radiusNormalizationValid) invalidFields.add("radiusNormalization")
            if (triggers == null) invalidFields.add("triggers")
            if (callbackHandle == null) invalidFields.add("callbackHandle")
            if (armed == null) invalidFields.add("armed")
            if (refresh == null) invalidFields.add("refresh")
            if (resetState == null) invalidFields.add("resetState")
            result.error(
                "invalid_argument",
                "Missing or invalid fence field(s): ${invalidFields.joinToString(", ")}.",
                null,
            )
            return null
        }

        return ParsedFenceCall(
            fence = SmartGeofenceFence(
                id = id,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                requestedRadiusMeters = requestedRadiusMeters,
                triggersEnter = triggers.contains("enter"),
                triggersExit = triggers.contains("exit"),
                triggersDwell = triggers.contains("dwell"),
                callbackHandle = callbackHandle,
                dispatchCallbackHandle = dispatchCallbackHandle ?: callbackHandle,
                armed = armed,
            ),
            refresh = refresh,
            resetState = resetState,
        )
    }

    private fun doubleArgOrNull(args: Map<*, *>, key: String): Double? {
        val value = args[key] as? Number ?: return null
        val doubleValue = value.toDouble()
        return if (!doubleValue.isNaN() && !doubleValue.isInfinite()) doubleValue else null
    }

    private fun callbackHandleArgOrNull(args: Map<*, *>, key: String): Long? =
        when (val value = args[key]) {
            is Long -> value
            is Int -> value.toLong()
            else -> null
        }?.takeIf { it != 0L }

    private fun boolArg(args: Map<*, *>, key: String, defaultValue: Boolean): Boolean? =
        when (val value = args[key]) {
            null -> defaultValue
            is Boolean -> value
            else -> null
        }

    private fun stringListArgOrNull(args: Map<*, *>, key: String): List<String>? {
        val value = args[key] ?: return emptyList()
        if (value !is List<*>) return null
        return if (value.all { it is String }) value.filterIsInstance<String>() else null
    }

    private fun failIfStrictExactAlarmDenied(
        config: SmartGeofenceConfig,
        where: String,
        result: Result,
    ): Boolean {
        if (!ExactAlarmPermissionController.isStrictBlocked(appContext, config)) return false
        val details = ExactAlarmPermissionController.strictFailureDetails(appContext, config, where)
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "exact_alarm_permission_denied where=$where " +
                "mode=${config.exactAlarmPermissionMode.configValue} " +
                "status=${details["exactAlarmPermissionStatus"]}",
        )
        result.error(
            ExactAlarmPermissionController.ERROR_CODE,
            "Exact alarm special access is required by smart_geofence strict mode.",
            details,
        )
        return true
    }

    companion object {
        const val TAG = "SmartGeofencePlugin"
        private const val TIME_INTEGRITY_TAG = "time_integrity"
        private val registrationTransactionCoordinator =
            SmartGeofenceRegistrationTransactions.coordinator
    }
}

private fun String.startsNewMonitoringSession(): Boolean =
    startsWith("createGeofence:") ||
        this == "ensureSynchronized" ||
        this == "adoptLegacyRegistrations"

internal fun shouldReconcileTerminalStopBeforeRegistration(
    startsNewMonitoringSession: Boolean,
    stopState: MonitoringStopSnapshot,
): Boolean =
    startsNewMonitoringSession &&
        stopState.terminallyStopped

internal fun shouldRejectRegistrationDuringTerminalStop(
    startsNewMonitoringSession: Boolean,
    stopState: MonitoringStopSnapshot,
): Boolean = stopState.terminallyStopped && !startsNewMonitoringSession

private fun SmartGeofenceConfig.hasSameTransitionValidation(other: SmartGeofenceConfig): Boolean =
    transitionValidationEnabled == other.transitionValidationEnabled &&
        transitionValidationEnterEnabled == other.transitionValidationEnterEnabled &&
        transitionValidationExitEnabled == other.transitionValidationExitEnabled &&
        transitionValidationMinimumDelayMillis ==
        other.transitionValidationMinimumDelayMillis &&
        nativeConfirmDelayMillis == other.nativeConfirmDelayMillis &&
        nativeEnterConfirmRadiusSlackMeters == other.nativeEnterConfirmRadiusSlackMeters &&
        nativeExitConfirmationEnabled == other.nativeExitConfirmationEnabled &&
        nativeEnterConfirmationEnabled == other.nativeEnterConfirmationEnabled
