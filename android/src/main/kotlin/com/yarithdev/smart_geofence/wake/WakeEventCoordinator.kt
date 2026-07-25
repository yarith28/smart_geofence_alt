package com.yarithdev.smart_geofence.wake

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yarithdev.smart_geofence.activity.ActivityMonitor
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.confirm.FusedLocationConfirmTaskHandler
import com.yarithdev.smart_geofence.confirm.FusedLocationConfirmTaskResult
import com.yarithdev.smart_geofence.confirm.FusedLocationConfirmTaskDisposition
import com.yarithdev.smart_geofence.confirm.LocationConfirmManager
import com.yarithdev.smart_geofence.confirm.LocationConfirmService
import com.yarithdev.smart_geofence.foreground.ForegroundLaunchState
import com.yarithdev.smart_geofence.foreground.ForegroundServiceLaunchReceiver
import com.yarithdev.smart_geofence.foreground.ForegroundStartCoordinator
import com.yarithdev.smart_geofence.foreground.QueuedForegroundStart
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import java.util.concurrent.atomic.AtomicBoolean

internal fun foregroundWorkWatchdogDelayMillis(config: SmartGeofenceConfig): Long =
    foregroundWorkWatchdogDelayMillis(
        cachedLocationTimeoutMillis = config.locationConfirmTimeoutMillis,
        currentLocationTimeoutMillis = config.locationConfirmTimeoutMillis,
        bufferMillis = config.foregroundServiceLaunchTimeoutMillis,
    )

internal fun foregroundWorkWatchdogDelayMillis(
    cachedLocationTimeoutMillis: Long,
    currentLocationTimeoutMillis: Long,
    bufferMillis: Long,
): Long {
    fun sanitize(value: Long): Long = value.coerceAtLeast(1L)
    fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    val withCurrent = safeAdd(sanitize(cachedLocationTimeoutMillis), sanitize(currentLocationTimeoutMillis))
    return safeAdd(withCurrent, sanitize(bufferMillis).coerceAtLeast(5_000L))
}

object WakeEventCoordinator {
    private const val TAG = "WakeEventCoordinator"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val drainLock = Any()
    private var foregroundDrainRunning = false
    private var foregroundRunningDedupeKey: String? = null
    private var backgroundDrainRunning = false
    private val foregroundIdleCallbacks = mutableListOf<() -> Unit>()

    fun enqueueProximityConfirm(
        context: Context,
        source: String,
        notBeforeMillis: Long,
        preserveExistingDeadline: Boolean = true,
        traceId: String? = null,
    ): ForegroundWorkItem =
        ForegroundQueue.enqueueProximityConfirm(
            context.applicationContext,
            source,
            notBeforeMillis,
            preserveExistingDeadline,
            traceId,
        )

    fun enqueuePulseConfirmUnlessRunning(
        context: Context,
        source: String,
        notBeforeMillis: Long,
        preserveExistingDeadline: Boolean = true,
        traceId: String? = null,
    ): ForegroundWorkItem? = synchronized(drainLock) {
        if (shouldSuppressRunningPulseEnqueue(
                source,
                foregroundDrainRunning,
                foregroundRunningDedupeKey,
            )
        ) {
            null
        } else {
            ForegroundQueue.enqueueProximityConfirm(
                context.applicationContext,
                source,
                notBeforeMillis,
                preserveExistingDeadline,
                traceId,
            )
        }
    }

    fun enqueueOutsideConfirm(
        context: Context,
        source: String,
        notBeforeMillis: Long,
        preserveExistingDeadline: Boolean = true,
        dedupeKey: String = normalizedConfirmDedupeKey(ForegroundWorkKind.CONFIRM_OUTSIDE),
        nativeFenceIds: Collection<String> = emptyList(),
        nativeTransitionInstances: Map<String, String> = emptyMap(),
        traceId: String? = null,
    ): ForegroundWorkItem =
        ForegroundQueue.enqueueOutsideConfirm(
            context = context.applicationContext,
            source = source,
            notBeforeMillis = notBeforeMillis,
            preserveExistingDeadline = preserveExistingDeadline,
            dedupeKey = dedupeKey,
            nativeFenceIds = nativeFenceIds,
            nativeTransitionInstances = nativeTransitionInstances,
            traceId = traceId,
        )

    fun enqueueInsideConfirm(
        context: Context,
        source: String,
        notBeforeMillis: Long,
        preserveExistingDeadline: Boolean = true,
        dedupeKey: String = normalizedConfirmDedupeKey(ForegroundWorkKind.CONFIRM_INSIDE),
        nativeFenceIds: Collection<String> = emptyList(),
        nativeTransitionInstances: Map<String, String> = emptyMap(),
        traceId: String? = null,
    ): ForegroundWorkItem =
        ForegroundQueue.enqueueInsideConfirm(
            context = context.applicationContext,
            source = source,
            notBeforeMillis = notBeforeMillis,
            preserveExistingDeadline = preserveExistingDeadline,
            dedupeKey = dedupeKey,
            nativeFenceIds = nativeFenceIds,
            nativeTransitionInstances = nativeTransitionInstances,
            traceId = traceId,
        )

    internal fun rearmConfirmIfUnchanged(
        context: Context,
        item: ForegroundWorkItem,
        notBeforeMillis: Long,
        attemptCount: Int,
        failureReason: String,
    ): ForegroundQueueMutationResult =
        ForegroundQueue.rearmIfUnchanged(
            context.applicationContext,
            item,
            notBeforeMillis,
            attemptCount,
            failureReason,
        )

    internal fun parkConfirmIfUnchanged(
        context: Context,
        item: ForegroundWorkItem,
        attemptCount: Int,
        parkedReason: String,
        failureReason: String,
    ): ForegroundQueueMutationResult =
        ForegroundQueue.parkIfUnchanged(
            context.applicationContext,
            item,
            attemptCount,
            parkedReason,
            failureReason,
        )

    internal fun claimConfirmIfUnchanged(
        context: Context,
        item: ForegroundWorkItem,
    ): ForegroundQueueMutationResult =
        ForegroundQueue.claimIfUnchanged(context.applicationContext, item)

    fun markForegroundWorkReady(
        context: Context,
        item: ForegroundWorkItem,
        readyAtMillis: Long = System.currentTimeMillis(),
    ): ForegroundWorkItem? =
        ForegroundQueue.markReady(
            context.applicationContext,
            item,
            readyAtMillis,
        )

    fun unparkForegroundWorkByReason(
        context: Context,
        parkedReason: String,
        notBeforeMillisFor: (ForegroundWorkKind) -> Long,
    ): Int =
        ForegroundQueue.unparkByReason(
            context.applicationContext,
            parkedReason,
            notBeforeMillisFor,
        )

    fun clearConfirmWork(context: Context) {
        ForegroundQueue.clearConfirmWork(context.applicationContext)
    }

    fun foregroundWorkCount(context: Context): Int =
        ForegroundQueue.count(context.applicationContext)

    fun totalForegroundWorkCount(context: Context): Int =
        ForegroundQueue.totalCount(context.applicationContext)

    fun parkedForegroundWorkCount(context: Context): Int =
        ForegroundQueue.parkedCount(context.applicationContext)

    internal fun parkedForegroundWorkSummary(
        context: Context,
        parkedReason: String,
    ): ParkedForegroundWorkSummary =
        ForegroundQueue.parkedSummary(context.applicationContext, parkedReason)

    fun isForegroundWorkRunning(): Boolean = synchronized(drainLock) {
        foregroundDrainRunning
    }

    fun removeForegroundWorkBySource(context: Context, source: String): Int =
        ForegroundQueue.removeBySource(context.applicationContext, source)

    fun removeForegroundWorkBySourcePrefix(context: Context, sourcePrefix: String): Int =
        ForegroundQueue.removeBySourcePrefix(context.applicationContext, sourcePrefix)

    fun readyForegroundWorkCount(context: Context): Int =
        ForegroundQueue.countReady(context.applicationContext)

    fun nextForegroundReadyAtMillis(context: Context): Long? =
        ForegroundQueue.nextReadyAtMillis(context.applicationContext)

    fun submit(context: Context, task: WakeTask): Boolean {
        val appContext = context.applicationContext
        SmartGeofenceDiagnostics.recordHeartbeat(
            appContext,
            "wake:${task.source.name.lowercase()}:${task.reason}",
        )
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Wake received source=${task.source} action=${task.action} " +
                "event=${task.event} exemption=${task.exemption} reason=${task.reason} " +
                "attempt=${task.attempt} token=${task.launchToken} ids=${task.geofenceIds}."
        )
        enqueueBackgroundSafeWork(appContext, task)
        drainBackgroundSafeWork(appContext, task)
        return when (task.action) {
            WakeAction.DRAIN_FOREGROUND_QUEUE -> drainForegroundQueue(appContext, task)
        }
    }

    fun drainForegroundWork(context: Context, onIdle: (() -> Unit)? = null): Boolean {
        val appContext = context.applicationContext
        var itemToRun: ForegroundWorkItem? = null
        var waitingUntilMillis: Long? = null
        var idleCallbacks: List<() -> Unit> = emptyList()
        synchronized(drainLock) {
            if (onIdle != null) foregroundIdleCallbacks.add(onIdle)
            if (foregroundDrainRunning) {
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Foreground queue drain already running; " +
                        "pendingForeground=${ForegroundQueue.count(appContext)}."
                )
                return true
            }
            val next = ForegroundQueue.peekReady(appContext)
            if (next == null) {
                waitingUntilMillis = ForegroundQueue.nextReadyAtMillis(appContext)
                idleCallbacks = foregroundIdleCallbacks.toList()
                foregroundIdleCallbacks.clear()
            } else {
                itemToRun = next
                foregroundDrainRunning = true
                foregroundRunningDedupeKey = next.dedupeKey
            }
        }
        if (idleCallbacks.isNotEmpty()) {
            SmartGeofenceLogger.d(appContext, TAG, "Foreground queue idle; notifying host.")
            idleCallbacks.forEach { it.invoke() }
        }
        val item = itemToRun
        if (item == null) {
            val readyAt = waitingUntilMillis
            if (readyAt != null) {
                val scheduled = LocationConfirmManager.scheduleNextReadyWork(
                    appContext,
                    reason = "foreground queue waiting until $readyAt",
                )
                SmartGeofenceLogger.d(
                    appContext,
                    TAG,
                    "Foreground queue has no eligible work; nextReadyAt=$readyAt scheduled=$scheduled."
                )
                return scheduled
            }
            SmartGeofenceLogger.d(appContext, TAG, "Foreground queue empty.")
            return false
        }

        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Running foreground work id=${item.id} kind=${item.kind} " +
                "source=${item.source} fence=${item.fenceId} dedupe=${item.dedupeKey} " +
                "notBefore=${item.notBeforeMillis}."
        )
        val completed = AtomicBoolean(false)
        val watchdogDelayMillis = foregroundWorkWatchdogDelayMillis(
            SmartGeofenceConfigStore.load(appContext),
        )
        val watchdog = Runnable {
            if (completed.compareAndSet(false, true)) {
                SmartGeofenceDiagnostics.recordConfirmResult(
                    appContext,
                    item.source,
                    result = "foreground_work_timeout",
                    elapsedMillis = watchdogDelayMillis,
                    failureMessage = "Foreground confirm work did not complete before watchdog.",
                    traceId = item.traceId,
                )
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "Foreground work timed out id=${item.id} kind=${item.kind} " +
                        "source=${item.source} watchdog=${watchdogDelayMillis}ms; releasing drain.",
                )
                finishForegroundWork(
                    appContext,
                    item,
                    FusedLocationConfirmTaskResult(
                        FusedLocationConfirmTaskDisposition.FAILED,
                        "foreground_work_timeout:${watchdogDelayMillis}ms",
                    ),
                )
            }
        }
        mainHandler.postDelayed(watchdog, watchdogDelayMillis)
        fun completeOnce(result: FusedLocationConfirmTaskResult) {
            if (completed.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(watchdog)
                finishForegroundWork(appContext, item, result)
            } else {
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "Ignoring late foreground work completion id=${item.id} kind=${item.kind} " +
                        "result=${result.disposition} reason=${result.reason}.",
                )
            }
        }
        try {
            FusedLocationConfirmTaskHandler.execute(appContext, item) { result ->
                completeOnce(result)
            }
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Foreground work failed synchronously id=${item.id}: ${e.message}",
                e
            )
            completeOnce(
                FusedLocationConfirmTaskResult(
                    FusedLocationConfirmTaskDisposition.FAILED,
                    "foreground_work_exception:${e.javaClass.simpleName}",
                ),
            )
        }
        return true
    }

    private fun finishForegroundWork(
        context: Context,
        item: ForegroundWorkItem,
        result: FusedLocationConfirmTaskResult,
    ) {
        var completionSucceeded = false
        try {
            if (result.shouldRemove && !result.queueOwnershipFinalized) {
                ForegroundQueue.removeIfUnchanged(context, item)
            } else if (result.queueOwnershipFinalized) {
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Foreground work queue ownership already finalized id=${item.id} " +
                        "disposition=${result.disposition}.",
                )
            } else {
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Deferred foreground work id=${item.id} reason=${result.reason}."
                )
            }
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Foreground work finished id=${item.id} disposition=${result.disposition} " +
                    "reason=${result.reason} pendingForeground=${ForegroundQueue.count(context)}."
            )
            completionSucceeded = true
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Foreground completion failed id=${item.id}: ${e.message}",
                e,
            )
        } finally {
            synchronized(drainLock) {
                foregroundDrainRunning = false
                foregroundRunningDedupeKey = null
            }
        }
        if (completionSucceeded && result.shouldRemove) {
            mainHandler.post { drainForegroundWork(context) }
        } else {
            notifyForegroundIdle(context)
        }
    }

    private fun drainBackgroundSafeWork(context: Context, task: WakeTask): Boolean {
        val item = synchronized(drainLock) {
            if (backgroundDrainRunning) return true
            BackgroundQueue.peekReady(context)?.also { backgroundDrainRunning = true }
        } ?: return true

        val eligible = activityMonitoringEligible(context)
        if (!shouldExecuteBackgroundWork(item.kind, eligible)) {
            BackgroundQueue.removeIfUnchanged(context, item)
            synchronized(drainLock) { backgroundDrainRunning = false }
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Dropping stale Activity repair work id=${item.id}; " +
                    "monitoring is no longer eligible.",
            )
            return drainBackgroundSafeWork(context, task)
        }

        return try {
            DurableActivityRepairRunner(
                reconcile = { completion ->
                    ActivityMonitor.reconcileDesired(
                        context,
                        "background queue source=${item.source}",
                        completion,
                    )
                },
                removeIfUnchanged = { BackgroundQueue.removeExactIfUnchanged(context, item) },
                deferIfUnchanged = { retryAt ->
                    BackgroundQueue.deferIfUnchanged(context, item, retryAt)
                },
                deferReplacementAtLeast = { retryAt ->
                    BackgroundQueue.deferCurrentDedupeAtLeast(
                        context,
                        item.dedupeKey,
                        retryAt,
                    )
                },
            ).run { disposition, result ->
                synchronized(drainLock) { backgroundDrainRunning = false }
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Background Activity repair settled id=${item.id} disposition=$disposition " +
                        "result=${result.disposition} reason=${result.reason} ${wakeDetail(task)}.",
                )
                if (disposition != DurableActivityRepairDisposition.RETAINED) {
                    mainHandler.post { drainBackgroundSafeWork(context, task) }
                }
            }
            true
        } catch (e: Throwable) {
            val retryAt = DurableActivityRepairRunner.safeRetryAt(
                System.currentTimeMillis(),
                DurableActivityRepairRunner.DEFAULT_RETRY_DELAY_MILLIS,
            )
            try {
                if (!BackgroundQueue.deferIfUnchanged(context, item, retryAt)) {
                    BackgroundQueue.deferCurrentDedupeAtLeast(
                        context,
                        item.dedupeKey,
                        retryAt,
                    )
                }
            } finally {
                synchronized(drainLock) { backgroundDrainRunning = false }
            }
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Background work failed id=${item.id} kind=${item.kind}: ${e.message}",
                e,
            )
            false
        }
    }

    private fun enqueueBackgroundSafeWork(context: Context, task: WakeTask) {
        if (task.source == WakeSource.ACTIVITY || task.source == WakeSource.FOREGROUND_REARM) return
        if (!activityMonitoringEligible(context)) return
        BackgroundQueue.enqueue(
            context,
            kind = BackgroundWorkKind.REFRESH_ACTIVITY_REQUEST,
            source = task.source.name,
            notBeforeMillis = System.currentTimeMillis(),
            dedupeKey = "refresh_activity_request",
        )
    }

    private fun activityMonitoringEligible(context: Context): Boolean {
        return ActivityMonitor.callbackIneligibilityReason(context) == null
    }

    private fun drainForegroundQueue(context: Context, task: WakeTask): Boolean {
        val pendingForegroundBefore = ForegroundQueue.count(context)
        val readyForegroundBefore = ForegroundQueue.countReady(context)
        val batchWindowClosedBefore = ForegroundStartCoordinator.batchWindowClosed(context)
        val batchPendingBefore = ForegroundStartCoordinator.batchStartPendingIntentExists(context)
        val queuedServiceKeys = ForegroundStartCoordinator.queuedServiceKeys(context)
        if (queuedServiceKeys.isNotEmpty()) {
            if (!task.exemption.allowsForegroundServiceLaunch() &&
                !LocationConfirmService.isForegroundReady
            ) {
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Wake left foreground service batch queued without a launch exemption: " +
                        "${wakeDetail(task)} services=$queuedServiceKeys."
                )
                return true
            }
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Wake draining foreground service batch: ${wakeDetail(task)} " +
                    "services=$queuedServiceKeys pendingForegroundWork=$pendingForegroundBefore " +
                    "batchPendingIntent=$batchPendingBefore " +
                    "batchWindowClosed=$batchWindowClosedBefore."
            )
            val result = ForegroundServiceLaunchReceiver.drain(context)
            val detail = wakeDetail(task) +
                " queued=${describeRequests(context, result.queued)}" +
                " accepted=${describeRequests(context, result.accepted)}" +
                " rejected=${describeRequests(context, result.rejected, includeFailure = true)}" +
                " pendingForegroundBefore=$pendingForegroundBefore" +
                " pendingForegroundAfter=${ForegroundQueue.count(context)}" +
                " batchPendingBefore=$batchPendingBefore" +
                " batchPendingAfter=${ForegroundStartCoordinator.batchStartPendingIntentExists(context)}" +
                " batchWindowBefore=$batchWindowClosedBefore" +
                " batchWindowAfter=${ForegroundStartCoordinator.batchWindowClosed(context)}" +
                " confirmRunning=${LocationConfirmService.isRunning}" +
                " confirmForegroundReady=${LocationConfirmService.isForegroundReady}"
            if (result.allAccepted) {
                SmartGeofenceLogger.d(context, TAG, "Wake foreground service batch drain accepted: $detail")
                return true
            }
            if (result.accepted.isNotEmpty()) {
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Wake foreground service batch drain partially accepted; rejected service(s) remain: $detail"
                )
                return true
            }
            SmartGeofenceLogger.w(context, TAG, "Wake foreground service batch drain rejected all service(s): $detail")
            return false
        }

        if (pendingForegroundBefore == 0) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Wake had no foreground work to drain: ${wakeDetail(task)} " +
                    "batchPendingIntent=$batchPendingBefore " +
                    "batchWindowClosed=$batchWindowClosedBefore."
            )
            return true
        }

        if (LocationConfirmService.isForegroundReady) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Wake using active foreground confirm service: ${wakeDetail(task)} " +
                    "pendingForegroundWork=$pendingForegroundBefore."
            )
            return drainForegroundWork(context)
        }

        if (readyForegroundBefore == 0) {
            val nextReadyAt = ForegroundQueue.nextReadyAtMillis(context)
            val scheduled = LocationConfirmManager.scheduleNextReadyWork(
                context,
                reason = "wake waiting for eligible confirm work",
            )
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Wake found queued work that is not eligible yet: ${wakeDetail(task)} " +
                    "pendingForegroundWork=$pendingForegroundBefore nextReadyAt=$nextReadyAt " +
                    "scheduled=$scheduled."
            )
            return scheduled
        }

        if (!task.exemption.allowsForegroundServiceLaunch()) {
            SmartGeofenceLogger.d(
                context,
                TAG,
                "Wake deferred foreground-required work without foreground exemption: ${wakeDetail(task)} " +
                    "pendingForegroundWork=$pendingForegroundBefore."
            )
            return true
        }

        SmartGeofenceLogger.d(
            context,
            TAG,
            "Starting pending foreground confirm work from wake source=${task.source} " +
                "pendingForegroundWork=$pendingForegroundBefore fgsExemption=${task.exemption} " +
                "event=${task.event} ids=${task.geofenceIds} " +
                "batchPendingIntent=$batchPendingBefore " +
                "batchWindowClosed=$batchWindowClosedBefore."
        )
        val accepted = LocationConfirmManager.requestStart(
            context,
            attempt = task.attempt,
            reason = task.reason,
            launchToken = task.launchToken,
        )
        val detail = wakeDetail(task) +
            " accepted=$accepted" +
            " pendingForegroundBefore=$pendingForegroundBefore" +
            " pendingForegroundAfter=${ForegroundQueue.count(context)}" +
            " confirmRunning=${LocationConfirmService.isRunning}" +
            " confirmForegroundReady=${LocationConfirmService.isForegroundReady}" +
            " launchPending=${LocationConfirmManager.pendingIntentExists(context)}" +
            " watchdogPending=${LocationConfirmManager.watchdogPendingIntentExists(context)}" +
            " batchPendingBefore=$batchPendingBefore" +
            " batchPendingAfter=${ForegroundStartCoordinator.batchStartPendingIntentExists(context)}" +
            " batchWindowBefore=$batchWindowClosedBefore" +
            " batchWindowAfter=${ForegroundStartCoordinator.batchWindowClosed(context)}"
        if (accepted) {
            SmartGeofenceLogger.d(context, TAG, "Wake pending foreground confirm start accepted: $detail")
        } else {
            SmartGeofenceLogger.w(context, TAG, "Wake pending foreground confirm start rejected: $detail")
        }
        return accepted
    }

    private fun notifyForegroundIdle(context: Context) {
        val callbacks = synchronized(drainLock) {
            val copy = foregroundIdleCallbacks.toList()
            foregroundIdleCallbacks.clear()
            copy
        }
        if (callbacks.isNotEmpty()) {
            SmartGeofenceLogger.d(context, TAG, "Foreground work deferred; notifying host idle.")
            callbacks.forEach { it.invoke() }
        }
    }

    private fun wakeDetail(task: WakeTask): String =
        "source=${task.source} action=${task.action} fgsExemption=${task.exemption} " +
            "reason=${task.reason} event=${task.event} attempt=${task.attempt} " +
            "token=${task.launchToken} ids=${task.geofenceIds}"

    private fun describeRequests(
        context: Context,
        requests: List<QueuedForegroundStart>,
        includeFailure: Boolean = false,
    ): String =
        if (requests.isEmpty()) {
            "[]"
        } else {
            requests.joinToString(prefix = "[", postfix = "]") {
                val failure = if (includeFailure) {
                    val snapshot = ForegroundLaunchState.snapshot(context, it.serviceKey)
                    ",lastFailureAt=${snapshot.lastFailureAtMillis}" +
                        ",lastFailureReason=${snapshot.lastFailureReason}"
                } else {
                    ""
                }
                "{service=${it.serviceKey},attempt=${it.attempt},token=${it.launchToken}," +
                    "reason=${it.reason}$failure}"
            }
        }
}
