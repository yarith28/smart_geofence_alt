package com.yarithdev.smart_geofence.monitoring

import android.content.Context
import com.chunkytofustudios.native_geofence.api.NativeGeofenceApiImpl
import com.chunkytofustudios.native_geofence.util.NativeGeofencePersistence
import com.yarithdev.smart_geofence.confirm.SmartGeofenceEventTimingStore
import com.yarithdev.smart_geofence.core.SmartGeofenceController
import com.yarithdev.smart_geofence.delivery.EventDeliveryOutboxStore
import com.yarithdev.smart_geofence.foreground.CallbackForegroundService
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.processing.LastLocationFixStore
import com.yarithdev.smart_geofence.registration.SmartGeofenceRegistrationTransactions
import com.yarithdev.smart_geofence.store.FenceStore
import com.yarithdev.smart_geofence.wake.BackgroundQueue
import java.util.concurrent.atomic.AtomicBoolean

object TerminalMonitoringStopController {
    private const val TAG = "TerminalMonitoringStop"
    private val teardownRunning = AtomicBoolean(false)

    fun trigger(
        context: Context,
        reason: MonitoringStopReason,
        source: String,
    ) {
        val appContext = context.applicationContext
        val ownedFenceIds = linkedSetOf<String>().apply {
            addAll(
                FenceStore.getAll(appContext, includePending = true)
                    .map { it.id },
            )
            addAll(
                SmartGeofenceRegistrationTransactions.coordinator.activeCleanupFenceIds(),
            )
        }
        MonitoringStopStateStore.begin(
            appContext,
            reason,
            nativeCleanupFenceIds = ownedFenceIds,
        )
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Terminal monitoring stop triggered reason=${reason.configValue} source=$source " +
                "ownedFenceCount=${ownedFenceIds.size}.",
        )
        TerminalCleanupRetryScheduler.ensureScheduled(
            appContext,
            reason = "terminal_stop_triggered",
        )
        enforce(appContext, source)
    }

    fun enforce(context: Context, source: String): Boolean {
        val appContext = context.applicationContext
        if (!MonitoringStopStateStore.snapshot(appContext).terminallyStopped) {
            TerminalCleanupRetryScheduler.cancel(appContext, "terminal_stop_inactive")
            return false
        }
        TerminalCleanupRetryScheduler.ensureScheduled(appContext, "enforce_$source")
        if (!teardownRunning.compareAndSet(false, true)) return true
        try {
            stopOperationalLayersAndClearState(appContext)
        } catch (error: Throwable) {
            teardownRunning.set(false)
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Terminal smart-layer teardown failed source=$source.",
                error,
            )
            return true
        }
        if (SmartGeofenceRegistrationTransactions.coordinator.hasActiveTransaction()) {
            teardownRunning.set(false)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Terminal native cleanup deferred for active registration " +
                    "transaction source=$source.",
            )
            return true
        }
        try {
            val ownedFenceIds = FenceStore.getAll(appContext, includePending = true)
                .mapTo(linkedSetOf()) { it.id }
            val state = MonitoringStopStateStore.mergePendingNativeCleanupFenceIds(
                appContext,
                ownedFenceIds,
            )
            if (state.phase == MonitoringStopPhase.STOPPED &&
                state.nativeCleanupComplete &&
                ownedFenceIds.isEmpty()
            ) {
                teardownRunning.set(false)
                TerminalCleanupRetryScheduler.cancel(appContext, "cleanup_complete")
                MonitoringStopStateStore.pendingEvent(appContext)?.let(
                    MonitoringStoppedCallbackNotifier::dispatchOnce,
                )
                return true
            }
            clearFenceState(appContext)
            removeNativeRegistrations(
                appContext,
                state.pendingNativeCleanupFenceIds,
                source,
            )
        } catch (error: Throwable) {
            teardownRunning.set(false)
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Terminal native cleanup setup failed source=$source.",
                error,
            )
            TerminalCleanupRetryScheduler.ensureScheduled(
                appContext,
                reason = "native_cleanup_setup_failed",
            )
        }
        return true
    }

    private fun stopOperationalLayersAndClearState(context: Context) {
        SmartGeofenceController.stop(context)
        CallbackForegroundService.demote(context)
        check(EventDeliveryOutboxStore.clear(context)) {
            "Failed to clear the smart callback outbox."
        }
        SmartGeofenceEventTimingStore.clear(context)
        LastLocationFixStore.clear(context)
        BackgroundQueue.clear(context)
    }

    private fun clearFenceState(context: Context) {
        val removedIds = FenceStore.removeAll(context)
        FenceStore.applyRemoveAllState(context, removedIds)
    }

    private fun removeNativeRegistrations(
        context: Context,
        requestedFenceIds: Set<String>,
        source: String,
    ) {
        val rawIdsResult = runCatching {
            NativeGeofencePersistence.getAllRawGeofenceIds(context).toSet()
        }
        val alreadyAbsent = rawIdsResult.getOrNull()
            ?.let { requestedFenceIds - it }
            .orEmpty()
        val pending = rawIdsResult.getOrNull()
            ?.let { requestedFenceIds.intersect(it).toList() }
            ?: requestedFenceIds.toList()
        if (pending.isEmpty()) {
            finishAttempt(context, alreadyAbsent, source, failures = emptyList())
            return
        }
        NativeGeofencePersistence.markAllGeofencesForPlatformCleanup(context, pending)
        val removed = alreadyAbsent.toMutableSet()
        val failures = mutableListOf<String>()
        val api = NativeGeofenceApiImpl(context)

        fun removeAt(index: Int) {
            if (index >= pending.size) {
                finishAttempt(context, removed, source, failures)
                return
            }
            val fenceId = pending[index]
            api.removeGeofenceById(fenceId) { result ->
                if (result.isSuccess) {
                    removed += fenceId
                } else {
                    failures += "$fenceId:${result.exceptionOrNull()?.javaClass?.simpleName}"
                }
                removeAt(index + 1)
            }
        }
        removeAt(0)
    }

    private fun finishAttempt(
        context: Context,
        removedFenceIds: Set<String>,
        source: String,
        failures: List<String>,
    ) {
        val state = MonitoringStopStateStore.completeCleanupAttempt(context, removedFenceIds)
        teardownRunning.set(false)
        if (state.nativeCleanupComplete) {
            TerminalCleanupRetryScheduler.cancel(context, "cleanup_complete")
            SmartGeofenceLogger.i(
                context,
                TAG,
                "Terminal monitoring stop completed source=$source.",
            )
        } else {
            TerminalCleanupRetryScheduler.ensureScheduled(
                context,
                reason = "native_cleanup_pending",
            )
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Terminal monitoring stop cleanup attempt finished with native cleanup pending " +
                    "source=$source failures=$failures.",
            )
        }
        MonitoringStopStateStore.pendingEvent(context)?.let(
            MonitoringStoppedCallbackNotifier::dispatchOnce,
        )
    }
}
