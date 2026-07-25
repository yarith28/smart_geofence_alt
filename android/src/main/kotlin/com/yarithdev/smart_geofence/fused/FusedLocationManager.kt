package com.yarithdev.smart_geofence.fused

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBoolean
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.proximity.AdaptiveDisplacementMode
import com.yarithdev.smart_geofence.proximity.AdaptiveProximityDisplacement
import com.yarithdev.smart_geofence.proximity.AdaptiveSamplingDecision
import com.yarithdev.smart_geofence.proximity.FusedBroadcastTailTracker
import com.yarithdev.smart_geofence.proximity.FusedLocationUpdateReceiver
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

object FusedLocationManager {
    private const val TAG = "FusedLocationManager"
    private const val CURRENT_LOCATION_WAKE_LOCK_TAG =
        "smart_geofence:fused_current_location"
    internal const val MAX_CURRENT_LOCATION_REQUEST_TIMEOUT_MILLIS = 60_000L
    internal const val CURRENT_LOCATION_WAKE_LOCK_GRACE_MILLIS = 5_000L
    private const val KEY_NEXT_OPERATION_GENERATION = "fused_request_next_operation_generation"
    private const val KEY_STALE_CALLBACK_COUNT = "fused_request_stale_callback_count"
    private const val KEY_LAST_STALE_CALLBACK_REASON = "fused_request_last_stale_callback_reason"
    private const val KEY_BALANCED_PREFIX = "fused_balanced_request"
    private const val KEY_PASSIVE_PREFIX = "fused_passive_request"
    private const val KEY_IGNORED_CALLBACK_COUNT = "fused_request_ignored_callback_count"
    private const val KEY_LAST_IGNORED_CALLBACK_REASON = "fused_request_last_ignored_callback_reason"

    private val lifecycleLock = Any()
    private val lifecycles = IdentityHashMap<Context, FusedRequestLifecycle>()

    fun startBalancedUpdate(context: Context) {
        val appContext = context.applicationContext
        val config = SmartGeofenceConfigStore.load(appContext)
        val decision = AdaptiveProximityDisplacement.select(appContext, config)
        lifecycle(appContext).setDesired(
            kind = FusedRequestKind.BALANCED,
            desired = true,
            spec = balancedSpecFor(decision),
            reason = "start_balanced",
        )
    }

    fun refreshBalancedUpdate(context: Context): Boolean =
        lifecycle(context.applicationContext).refreshConfirmed(
            kind = FusedRequestKind.BALANCED,
            reason = "liveness_refresh_balanced",
        )

    fun stopBalancedUpdate(
        context: Context,
        tailTracker: FusedBroadcastTailTracker? = null,
    ) {
        val appContext = context.applicationContext
        val tail = tailTracker?.registerTail()
        lifecycle(appContext).setDesired(
            kind = FusedRequestKind.BALANCED,
            desired = false,
            spec = null,
            reason = "stop_balanced",
            onComplete = tail?.let { registeredTail -> { _ -> registeredTail.complete() } },
        )
        AdaptiveProximityDisplacement.reset(appContext)
    }

    fun updateBalancedDisplacement(
        context: Context,
        edgeDistanceMeters: Double? = null,
        tailTracker: FusedBroadcastTailTracker? = null,
    ): Boolean {
        val appContext = context.applicationContext
        if (!lifecycleState(appContext).balanced.desired) return false

        val config = SmartGeofenceConfigStore.load(appContext)
        val previousApplied = AdaptiveProximityDisplacement.loadApplied(appContext)
        val decision = AdaptiveProximityDisplacement.select(
            appContext,
            config,
            edgeDistanceMeters,
        )
        if (previousApplied != null &&
            AdaptiveProximityDisplacement.sameRequest(previousApplied, decision)
        ) {
            if (previousApplied.mode != decision.mode) {
                SmartGeofenceLogger.d(
                    appContext,
                    FusedLocationManager.TAG,
                    "Adaptive band changed ${previousApplied.mode} -> ${decision.mode}; " +
                        "request unchanged.",
                )
            }
        }
        val tail = tailTracker?.registerTail()
        val update = lifecycle(appContext).setDesired(
            kind = FusedRequestKind.BALANCED,
            desired = true,
            spec = balancedSpecFor(decision),
            reason = "adaptive_displacement",
            onComplete = tail?.let { registeredTail -> { _ -> registeredTail.complete() } },
        )
        if (update.requestRequired) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Adaptive sampling update mode=${previousApplied?.mode} -> ${decision.mode} " +
                    "priority=${decision.priorityName} interval=${decision.intervalMillis}ms " +
                    "displacement=${decision.displacementMeters}m.",
            )
        }
        return update.requestRequired
    }

    fun startPassiveUpdates(context: Context) {
        val appContext = context.applicationContext
        lifecycle(appContext).setDesired(
            kind = FusedRequestKind.PASSIVE,
            desired = true,
            spec = passiveSpec(SmartGeofenceConfigStore.load(appContext)),
            reason = "start_passive",
        )
    }

    fun stopPassiveUpdates(
        context: Context,
        tailTracker: FusedBroadcastTailTracker? = null,
    ) {
        val appContext = context.applicationContext
        val tail = tailTracker?.registerTail()
        lifecycle(appContext).setDesired(
            kind = FusedRequestKind.PASSIVE,
            desired = false,
            spec = null,
            reason = "stop_passive",
            onComplete = tail?.let { registeredTail -> { _ -> registeredTail.complete() } },
        )
    }

    fun stopBackgroundUpdates(
        context: Context,
        tailTracker: FusedBroadcastTailTracker? = null,
    ) {
        val appContext = context.applicationContext
        val tails = FusedRequestKind.entries.associateWith { tailTracker?.registerTail() }
        lifecycle(appContext).stopAll("stop_background") { kind, _ ->
            tails[kind]?.complete()
        }
        AdaptiveProximityDisplacement.reset(appContext)
    }

    @SuppressLint("MissingPermission")
    fun requestLastLocation(
        context: Context,
        timeoutMillis: Long = Constants.DEFAULT_LAST_LOCATION_TIMEOUT_MILLIS,
        onResult: (FusedCurrentLocationResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        val requestedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        if (!FusedLocationPermissions.hasBackgroundCapableLocation(appContext)) {
            onResult(
                FusedCurrentLocationResult(
                    status = FusedCurrentLocationStatus.PERMISSION_MISSING,
                    elapsedMillis = elapsedRealtimeSince(
                        requestedAtElapsedRealtimeMillis,
                        SystemClock.elapsedRealtime(),
                    ),
                )
            )
            return
        }

        val done = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                onResult(
                    FusedCurrentLocationResult(
                        status = FusedCurrentLocationStatus.TIMEOUT,
                        elapsedMillis = elapsedRealtimeSince(
                            requestedAtElapsedRealtimeMillis,
                            SystemClock.elapsedRealtime(),
                        ),
                    )
                )
            }
        }
        handler.postDelayed(timeout, timeoutMillis.coerceAtLeast(1L))

        fun finish(
            status: FusedCurrentLocationStatus,
            location: android.location.Location? = null,
            failure: Throwable? = null,
        ) {
            if (done.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                onResult(
                    FusedCurrentLocationResult(
                        status = status,
                        location = location,
                        failure = failure,
                        elapsedMillis = elapsedRealtimeSince(
                            requestedAtElapsedRealtimeMillis,
                            SystemClock.elapsedRealtime(),
                        ),
                    )
                )
            }
        }

        try {
            LocationServices.getFusedLocationProviderClient(appContext)
                .lastLocation
                .addOnSuccessListener { location ->
                    finish(
                        status = if (location == null) {
                            FusedCurrentLocationStatus.NULL_LOCATION
                        } else {
                            FusedCurrentLocationStatus.SUCCESS
                        },
                        location = location,
                    )
                }
                .addOnFailureListener { failure ->
                    finish(FusedCurrentLocationStatus.FAILURE, failure = failure)
                }
        } catch (e: SecurityException) {
            finish(FusedCurrentLocationStatus.SECURITY_EXCEPTION, failure = e)
        } catch (e: Throwable) {
            finish(FusedCurrentLocationStatus.FAILURE, failure = e)
        }
    }

    internal fun lifecycleState(context: Context): FusedRequestLifecycleState =
        synchronized(lifecycleLock) {
            lifecycles[context.applicationContext]?.snapshot()
        } ?: loadLifecycleState(context.applicationContext)

    internal fun callbackIneligibilityReason(context: Context, source: String): String? {
        return fusedCallbackIneligibilityReason(
            lifecycle(context.applicationContext).snapshot(),
            source,
        )
    }

    internal fun recordIgnoredCallback(context: Context, source: String, reason: String) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.safeLong(KEY_IGNORED_CALLBACK_COUNT, 0L) + 1L
        prefs.edit()
            .putLong(KEY_IGNORED_CALLBACK_COUNT, count)
            .putString(KEY_LAST_IGNORED_CALLBACK_REASON, "$source:$reason")
            .apply()
        SmartGeofenceDiagnostics.recordTrace(
            appContext,
            stage = "fused_callback_ignored",
            reasonCode = reason,
            source = source,
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

    private fun lifecycle(context: Context): FusedRequestLifecycle {
        val appContext = context.applicationContext
        val lifecycle = synchronized(lifecycleLock) {
            lifecycles[appContext] ?: FusedRequestLifecycle(
                initialState = loadLifecycleState(appContext),
                backend = AndroidFusedRequestBackend(appContext),
                persist = { persistLifecycleState(appContext, it) },
                onConfirmed = { kind, spec ->
                    if (kind == FusedRequestKind.BALANCED) {
                        spec.toAdaptiveDecision()?.let {
                            AdaptiveProximityDisplacement.markApplied(appContext, it)
                        }
                    }
                },
                onNoConfirmedRequest = { kind ->
                    if (kind == FusedRequestKind.BALANCED) {
                        AdaptiveProximityDisplacement.clearApplied(appContext)
                    }
                },
            ).also { lifecycles[appContext] = it }
        }
        lifecycle.reconcileProcessBootstrap()
        return lifecycle
    }

    private fun loadLifecycleState(context: Context): FusedRequestLifecycleState {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        return FusedRequestLifecycleState(
            nextOperationGeneration = prefs.safeLong(KEY_NEXT_OPERATION_GENERATION, 0L),
            balanced = prefs.requestPart(KEY_BALANCED_PREFIX),
            passive = prefs.requestPart(KEY_PASSIVE_PREFIX),
            staleCallbackCount = prefs.safeLong(KEY_STALE_CALLBACK_COUNT, 0L),
            lastStaleCallbackReason = prefs.safeString(KEY_LAST_STALE_CALLBACK_REASON),
        )
    }

    private fun persistLifecycleState(context: Context, state: FusedRequestLifecycleState) {
        val editor = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_NEXT_OPERATION_GENERATION, state.nextOperationGeneration)
            .putLong(KEY_STALE_CALLBACK_COUNT, state.staleCallbackCount)
        editor.putNullableString(KEY_LAST_STALE_CALLBACK_REASON, state.lastStaleCallbackReason)
        editor.putRequestPart(KEY_BALANCED_PREFIX, state.balanced)
        editor.putRequestPart(KEY_PASSIVE_PREFIX, state.passive)
        editor.apply()
    }

    private fun SharedPreferences.requestPart(prefix: String): FusedRequestPartState =
        FusedRequestPartState(
            desired = safeBoolean("${prefix}_desired", false),
            desiredEpoch = safeLong("${prefix}_desired_epoch", 0L),
            desiredSpec = requestSpec("${prefix}_desired_spec"),
            confirmed = safeBoolean("${prefix}_confirmed", false),
            confirmedSpec = requestSpec("${prefix}_confirmed_spec"),
            requestInFlight = safeBoolean("${prefix}_request_in_flight", false),
            removalInFlight = safeBoolean("${prefix}_removal_in_flight", false),
            removalConfirmed = safeBoolean("${prefix}_removal_confirmed", false),
            operationGeneration = safeLong("${prefix}_operation_generation", 0L),
            operationDesiredEpoch = safeLong("${prefix}_operation_desired_epoch", 0L),
            operationStartedAtMillis = nullableLong("${prefix}_operation_started_at"),
            requestReplacesConfirmed = safeBoolean("${prefix}_request_replaces_confirmed", false),
            requestBlockedEpoch = nullableLong("${prefix}_request_blocked_epoch"),
            removalBlockedEpoch = nullableLong("${prefix}_removal_blocked_epoch"),
            lastSuccessAtMillis = nullableLong("${prefix}_last_success_at"),
            lastFailureAtMillis = nullableLong("${prefix}_last_failure_at"),
            lastFailureReason = safeString("${prefix}_last_failure_reason"),
            failureSerial = safeLong("${prefix}_failure_serial", 0L),
        )

    private fun SharedPreferences.requestSpec(prefix: String): FusedRequestSpec? {
        if (!safeBoolean("${prefix}_present", false)) return null
        val displacementBits = nullableLong("${prefix}_displacement_bits") ?: return null
        return FusedRequestSpec(
            priorityName = safeString("${prefix}_priority") ?: return null,
            intervalMillis = safeLong("${prefix}_interval", 0L),
            fastestIntervalMillis = safeLong("${prefix}_fastest", 0L),
            maxWaitMillis = safeLong("${prefix}_max_wait", 0L),
            minDisplacementMeters = Double.fromBits(displacementBits),
            adaptiveMode = safeString("${prefix}_adaptive_mode"),
        )
    }

    private fun SharedPreferences.Editor.putRequestPart(
        prefix: String,
        part: FusedRequestPartState,
    ) {
        putBoolean("${prefix}_desired", part.desired)
        putLong("${prefix}_desired_epoch", part.desiredEpoch)
        putRequestSpec("${prefix}_desired_spec", part.desiredSpec)
        putBoolean("${prefix}_confirmed", part.confirmed)
        putRequestSpec("${prefix}_confirmed_spec", part.confirmedSpec)
        putBoolean("${prefix}_request_in_flight", part.requestInFlight)
        putBoolean("${prefix}_removal_in_flight", part.removalInFlight)
        putBoolean("${prefix}_removal_confirmed", part.removalConfirmed)
        putLong("${prefix}_operation_generation", part.operationGeneration)
        putLong("${prefix}_operation_desired_epoch", part.operationDesiredEpoch)
        putNullableLong("${prefix}_operation_started_at", part.operationStartedAtMillis)
        putBoolean("${prefix}_request_replaces_confirmed", part.requestReplacesConfirmed)
        putNullableLong("${prefix}_request_blocked_epoch", part.requestBlockedEpoch)
        putNullableLong("${prefix}_removal_blocked_epoch", part.removalBlockedEpoch)
        putNullableLong("${prefix}_last_success_at", part.lastSuccessAtMillis)
        putNullableLong("${prefix}_last_failure_at", part.lastFailureAtMillis)
        putNullableString("${prefix}_last_failure_reason", part.lastFailureReason)
        putLong("${prefix}_failure_serial", part.failureSerial)
    }

    private fun SharedPreferences.Editor.putRequestSpec(
        prefix: String,
        spec: FusedRequestSpec?,
    ) {
        putBoolean("${prefix}_present", spec != null)
        if (spec == null) {
            remove("${prefix}_priority")
            remove("${prefix}_interval")
            remove("${prefix}_fastest")
            remove("${prefix}_max_wait")
            remove("${prefix}_displacement_bits")
            remove("${prefix}_adaptive_mode")
            return
        }
        putString("${prefix}_priority", spec.priorityName)
        putLong("${prefix}_interval", spec.intervalMillis)
        putLong("${prefix}_fastest", spec.fastestIntervalMillis)
        putLong("${prefix}_max_wait", spec.maxWaitMillis)
        putLong("${prefix}_displacement_bits", spec.minDisplacementMeters.toRawBits())
        putNullableString("${prefix}_adaptive_mode", spec.adaptiveMode)
    }

    private fun SharedPreferences.nullableLong(key: String): Long? =
        if (contains(key)) safeLong(key, 0L) else null

    private fun SharedPreferences.Editor.putNullableLong(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private fun SharedPreferences.Editor.putNullableString(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private class AndroidFusedRequestBackend(
        private val context: Context,
    ) : FusedRequestBackend {
        override fun pendingIntentExists(kind: FusedRequestKind): Boolean =
            FusedLocationManager.existingPendingIntent(context, kind) != null

        @SuppressLint("MissingPermission")
        override fun request(
            kind: FusedRequestKind,
            spec: FusedRequestSpec,
            onComplete: (FusedRequestOperationResult) -> Unit,
        ) {
            if (!FusedLocationPermissions.hasBackgroundCapableLocation(context)) {
                onComplete(FusedRequestOperationResult(false, "location_permission_missing"))
                return
            }
            try {
                val request = LocationRequest.Builder(
                    Constants.toAndroidLocationPriority(spec.priorityName),
                    spec.intervalMillis.coerceAtLeast(1L),
                )
                    .setMinUpdateIntervalMillis(spec.fastestIntervalMillis.coerceAtLeast(0L))
                    .setMaxUpdateDelayMillis(spec.maxWaitMillis.coerceAtLeast(0L))
                    .setMinUpdateDistanceMeters(
                        spec.minDisplacementMeters.coerceAtLeast(0.0).toFloat(),
                    )
                    .build()
                val pending = FusedLocationManager.pendingIntent(
                    context,
                    kind,
                    PendingIntent.FLAG_UPDATE_CURRENT,
                )
                    ?: run {
                        onComplete(
                            FusedRequestOperationResult(false, "pending_intent_creation_failed"),
                        )
                        return
                    }
                val task = LocationServices.getFusedLocationProviderClient(context)
                    .requestLocationUpdates(request, pending)
                completeTask(kind, "request", spec, task, onComplete)
            } catch (e: Throwable) {
                SmartGeofenceLogger.w(
                    context,
                    FusedLocationManager.TAG,
                    "Failed to submit ${kind.label()} request: ${e.message}",
                    e,
                )
                onComplete(
                    FusedRequestOperationResult(
                        false,
                        "request_submission_failed:${e.javaClass.simpleName}",
                    ),
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun remove(
            kind: FusedRequestKind,
            onComplete: (FusedRequestOperationResult) -> Unit,
        ) {
            try {
                val pending = FusedLocationManager.pendingIntent(
                    context,
                    kind,
                    PendingIntent.FLAG_UPDATE_CURRENT,
                )
                    ?: run {
                        onComplete(
                            FusedRequestOperationResult(false, "pending_intent_creation_failed"),
                        )
                        return
                    }
                val task = LocationServices.getFusedLocationProviderClient(context)
                    .removeLocationUpdates(pending)
                completeTask(kind, "remove", null, task, onComplete)
            } catch (e: Throwable) {
                SmartGeofenceLogger.w(
                    context,
                    FusedLocationManager.TAG,
                    "Failed to submit ${kind.label()} removal: ${e.message}",
                    e,
                )
                onComplete(
                    FusedRequestOperationResult(
                        false,
                        "removal_submission_failed:${e.javaClass.simpleName}",
                    ),
                )
            }
        }

        override fun cancelPendingIntent(kind: FusedRequestKind) {
            FusedLocationManager.existingPendingIntent(context, kind)?.cancel()
        }

        private fun completeTask(
            kind: FusedRequestKind,
            operation: String,
            spec: FusedRequestSpec?,
            task: Task<Void>,
            onComplete: (FusedRequestOperationResult) -> Unit,
        ) {
            task.addOnCompleteListener { completed ->
                val failure = completed.exception
                val result = FusedRequestOperationResult(
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
                    val detail = spec?.let {
                        " priority=${it.priorityName} interval=${it.intervalMillis}ms " +
                            "min=${it.fastestIntervalMillis}ms maxWait=${it.maxWaitMillis}ms " +
                            "displacement=${it.minDisplacementMeters}m"
                    }.orEmpty()
                    SmartGeofenceLogger.d(
                        context,
                        FusedLocationManager.TAG,
                        "${kind.label()} $operation confirmed.$detail",
                    )
                } else {
                    SmartGeofenceLogger.w(
                        context,
                        FusedLocationManager.TAG,
                        "${kind.label()} $operation failed: ${result.failureReason}",
                        failure,
                    )
                }
                onComplete(result)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun requestCurrentLocation(
        context: Context,
        priorityName: String,
        timeoutMillis: Long,
        maximumUpdateAgeMillis: Long? = null,
        onResult: (FusedCurrentLocationResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        val requestedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        if (!FusedLocationPermissions.hasLocationPermission(appContext)) {
            onResult(
                FusedCurrentLocationResult(
                    status = FusedCurrentLocationStatus.PERMISSION_MISSING,
                    elapsedMillis = elapsedRealtimeSince(
                        requestedAtElapsedRealtimeMillis,
                        SystemClock.elapsedRealtime(),
                    )
                )
            )
            return
        }

        val safeTimeoutMillis = boundedCurrentLocationRequestTimeoutMillis(timeoutMillis)
        val done = AtomicBoolean(false)
        val cts = CancellationTokenSource()
        val handler = Handler(Looper.getMainLooper())
        val wakeLockLease = acquireCurrentLocationWakeLock(
            appContext,
            safeTimeoutMillis,
        )
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                try {
                    cts.cancel()
                    onResult(
                        FusedCurrentLocationResult(
                            status = FusedCurrentLocationStatus.TIMEOUT,
                            elapsedMillis = elapsedRealtimeSince(
                                requestedAtElapsedRealtimeMillis,
                                SystemClock.elapsedRealtime(),
                            )
                        )
                    )
                } finally {
                    wakeLockLease?.release()
                }
            }
        }
        handler.postDelayed(timeout, safeTimeoutMillis)

        fun finish(
            status: FusedCurrentLocationStatus,
            location: android.location.Location? = null,
            failure: Throwable? = null,
        ) {
            if (done.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                try {
                    onResult(
                        FusedCurrentLocationResult(
                            status = status,
                            location = location,
                            failure = failure,
                            elapsedMillis = elapsedRealtimeSince(
                                requestedAtElapsedRealtimeMillis,
                                SystemClock.elapsedRealtime(),
                            )
                        )
                    )
                } finally {
                    wakeLockLease?.release()
                }
            }
        }

        try {
            val client = LocationServices.getFusedLocationProviderClient(appContext)
            client.getCurrentLocation(
                currentLocationRequest(
                    priorityName = priorityName,
                    maximumUpdateAgeMillis = maximumUpdateAgeMillis,
                    durationMillis = safeTimeoutMillis,
                ),
                cts.token,
            )
                .addOnSuccessListener { location ->
                    if (location == null) {
                        finish(FusedCurrentLocationStatus.NULL_LOCATION)
                    } else {
                        finish(FusedCurrentLocationStatus.SUCCESS, location)
                    }
                }
                .addOnFailureListener { e ->
                    finish(FusedCurrentLocationStatus.FAILURE, failure = e)
                }
        } catch (e: SecurityException) {
            finish(FusedCurrentLocationStatus.SECURITY_EXCEPTION, failure = e)
        } catch (e: Throwable) {
            finish(FusedCurrentLocationStatus.FAILURE, failure = e)
        }
    }

    private fun acquireCurrentLocationWakeLock(
        context: Context,
        requestTimeoutMillis: Long,
    ): CurrentLocationWakeLockLease? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "PowerManager unavailable; current-location request will continue without a wake lock.",
            )
            return null
        }

        return try {
            val wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CURRENT_LOCATION_WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire(currentLocationWakeLockTimeoutMillis(requestTimeoutMillis))
                }
            CurrentLocationWakeLockLease {
                try {
                    if (wakeLock.isHeld) wakeLock.release()
                } catch (e: RuntimeException) {
                    SmartGeofenceLogger.w(
                        context,
                        TAG,
                        "Could not release current-location wake lock: ${e.message}",
                        e,
                    )
                }
            }
        } catch (e: RuntimeException) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Could not acquire current-location wake lock; request will continue: ${e.message}",
                e,
            )
            null
        }
    }

    internal fun currentLocationWakeLockTimeoutMillis(requestTimeoutMillis: Long): Long {
        val safeRequestTimeoutMillis =
            boundedCurrentLocationRequestTimeoutMillis(requestTimeoutMillis)
        return safeRequestTimeoutMillis + CURRENT_LOCATION_WAKE_LOCK_GRACE_MILLIS
    }

    internal fun boundedCurrentLocationRequestTimeoutMillis(requestTimeoutMillis: Long): Long =
        requestTimeoutMillis.coerceIn(
            minimumValue = 1L,
            maximumValue = MAX_CURRENT_LOCATION_REQUEST_TIMEOUT_MILLIS,
        )

    internal fun elapsedRealtimeSince(
        requestedAtElapsedRealtimeMillis: Long,
        nowElapsedRealtimeMillis: Long,
    ): Long = if (nowElapsedRealtimeMillis <= requestedAtElapsedRealtimeMillis) {
        0L
    } else {
        nowElapsedRealtimeMillis - requestedAtElapsedRealtimeMillis
    }

    internal fun currentLocationRequest(
        priorityName: String,
        maximumUpdateAgeMillis: Long?,
        durationMillis: Long,
    ): CurrentLocationRequest {
        val builder = CurrentLocationRequest.Builder()
            .setPriority(Constants.toAndroidLocationPriority(priorityName))
            .setDurationMillis(boundedCurrentLocationRequestTimeoutMillis(durationMillis))
        maximumUpdateAgeMillis?.let {
            builder.setMaxUpdateAgeMillis(it.coerceAtLeast(0L))
        }
        return builder.build()
    }

    private fun existingPendingIntent(context: Context, kind: FusedRequestKind): PendingIntent? =
        pendingIntent(context, kind, PendingIntent.FLAG_NO_CREATE)

    private fun pendingIntent(
        context: Context,
        kind: FusedRequestKind,
        baseFlags: Int,
    ): PendingIntent? {
        val intent = Intent(context, FusedLocationUpdateReceiver::class.java).apply {
            data = Uri.parse(kind.pendingIntentData())
            putExtra(Constants.EXTRA_LOCATION_WAKE_SOURCE, kind.wakeSource())
        }
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, kind.requestCode(), intent, flags)
    }

    private fun balancedSpecFor(decision: AdaptiveSamplingDecision): FusedRequestSpec =
        FusedRequestSpec(
            priorityName = decision.priorityName,
            intervalMillis = decision.intervalMillis,
            fastestIntervalMillis = decision.fastestIntervalMillis,
            maxWaitMillis = decision.maxWaitMillis,
            minDisplacementMeters = decision.displacementMeters,
            adaptiveMode = decision.mode.name,
        )

    private fun passiveSpec(config: SmartGeofenceConfig): FusedRequestSpec =
        FusedRequestSpec(
            priorityName = config.passiveLocationPriority,
            intervalMillis = config.passiveLocationIntervalMillis,
            fastestIntervalMillis = config.passiveLocationFastestIntervalMillis,
            maxWaitMillis = config.passiveLocationMaxWaitMillis,
            minDisplacementMeters = 0.0,
        )

    private fun FusedRequestSpec.toAdaptiveDecision(): AdaptiveSamplingDecision? {
        val mode = adaptiveMode?.let {
            runCatching { AdaptiveDisplacementMode.valueOf(it) }.getOrNull()
        } ?: return null
        return AdaptiveSamplingDecision(
            mode = mode,
            priorityName = priorityName,
            intervalMillis = intervalMillis,
            fastestIntervalMillis = fastestIntervalMillis,
            maxWaitMillis = maxWaitMillis,
            displacementMeters = minDisplacementMeters,
        )
    }

    private fun FusedRequestLifecycleState.part(kind: FusedRequestKind): FusedRequestPartState =
        when (kind) {
            FusedRequestKind.BALANCED -> balanced
            FusedRequestKind.PASSIVE -> passive
        }

}

internal class CurrentLocationWakeLockLease(
    private val releaseHeldWakeLock: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) {
            releaseHeldWakeLock()
        }
    }
}

internal fun fusedCallbackIneligibilityReason(
    state: FusedRequestLifecycleState,
    source: String,
): String? {
    val kind = when (source) {
        Constants.LOCATION_WAKE_SOURCE_PROXIMITY -> FusedRequestKind.BALANCED
        Constants.LOCATION_WAKE_SOURCE_PASSIVE -> FusedRequestKind.PASSIVE
        else -> return "unsupported_source:$source"
    }
    val current = when (kind) {
        FusedRequestKind.BALANCED -> state.balanced
        FusedRequestKind.PASSIVE -> state.passive
    }
    if (!current.desired) return "${kind.name.lowercase()}_not_desired"
    if (current.removalInFlight) return "${kind.name.lowercase()}_removal_in_flight"
    if (!current.confirmed) return "${kind.name.lowercase()}_unconfirmed"
    return null
}

private fun FusedRequestKind.label(): String = when (this) {
    FusedRequestKind.BALANCED -> "balanced fused update"
    FusedRequestKind.PASSIVE -> "passive fused location"
}

private fun FusedRequestKind.pendingIntentData(): String = when (this) {
    FusedRequestKind.BALANCED -> Constants.PENDING_INTENT_DATA_PROXIMITY
    FusedRequestKind.PASSIVE -> Constants.PENDING_INTENT_DATA_PASSIVE_LOCATION
}

private fun FusedRequestKind.requestCode(): Int = when (this) {
    FusedRequestKind.BALANCED -> Constants.PENDING_INTENT_REQUEST_BASE
    FusedRequestKind.PASSIVE -> Constants.PENDING_INTENT_REQUEST_PASSIVE_LOCATION
}

private fun FusedRequestKind.wakeSource(): String = when (this) {
    FusedRequestKind.BALANCED -> Constants.LOCATION_WAKE_SOURCE_PROXIMITY
    FusedRequestKind.PASSIVE -> Constants.LOCATION_WAKE_SOURCE_PASSIVE
}

enum class FusedCurrentLocationStatus {
    SUCCESS,
    NULL_LOCATION,
    FAILURE,
    TIMEOUT,
    PERMISSION_MISSING,
    SECURITY_EXCEPTION,
}

data class FusedCurrentLocationResult(
    val status: FusedCurrentLocationStatus,
    val location: android.location.Location? = null,
    val failure: Throwable? = null,
    val elapsedMillis: Long,
)
