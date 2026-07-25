package com.yarithdev.smart_geofence.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chunkytofustudios.native_geofence.api.NativeGeofenceApiImpl
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.config.SmartGeofenceConfigStore
import com.yarithdev.smart_geofence.confirm.NativeEnterPendingStore
import com.yarithdev.smart_geofence.confirm.NativeExitPendingStore
import com.yarithdev.smart_geofence.confirm.SmartGeofenceEventProcessor
import com.yarithdev.smart_geofence.core.MainThreadRunner
import com.yarithdev.smart_geofence.core.SmartGeofenceController
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.monitoring.LocationAvailabilityStopController
import com.yarithdev.smart_geofence.store.FenceStore
import java.util.concurrent.atomic.AtomicBoolean

class RecoveryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            if (FenceStore.getAll(appContext).isNotEmpty()) {
                RecoveryScheduler.schedule(
                    appContext,
                    SmartGeofenceConfigStore.load(appContext),
                )
            }
            return
        }
        if (intent.action != RecoveryScheduler.ACTION_RECOVERY_ALARM) return
        if (LocationAvailabilityStopController.stopIfUnavailable(
                appContext,
                "scheduled_recovery",
            )
        ) {
            RecoveryScheduler.cancel(appContext)
            return
        }
        val guarded = intent.getBooleanExtra(AlarmPolicyScheduler.EXTRA_IS_GUARDED, false)
        val scheduleToken = intent.getLongExtra(AlarmPolicyScheduler.EXTRA_SCHEDULE_TOKEN, 0L)
        if (guarded && !AlarmPolicyScheduler.claimGuardedFire(
                context,
                RecoveryScheduler.SCHEDULE_KEY,
                scheduleToken,
            )
        ) {
            SmartGeofenceLogger.d(
                context.applicationContext,
                TAG,
                "Recovery alarm ignored stale duplicate token=$scheduleToken."
            )
            return
        }
        if (guarded) {
            RecoveryScheduler.cancel(context.applicationContext)
        }
        if (!guarded) {
            SmartGeofenceDiagnostics.recordTrace(
                appContext,
                stage = "alarm_fired",
                reasonCode = "primary",
                source = RecoveryScheduler.SCHEDULE_KEY,
                extras = mapOf("scheduleToken" to scheduleToken),
            )
        }
        val pending = goAsync()
        val finishPending = BootRecoveryCoordinator.finishPendingResultOnDeadline(
            appContext,
            pending,
            TAG,
            "scheduled recovery",
        )
        val completed = AtomicBoolean(false)
        fun complete(nativeFailure: Throwable?) {
            if (!completed.compareAndSet(false, true)) return
            finishRecovery(appContext, finishPending, nativeFailure)
        }
        Thread {
            try {
                if (FenceStore.getAll(appContext).isEmpty()) {
                    SmartGeofenceDiagnostics.recordTrace(
                        appContext,
                        stage = "alarm_result",
                        reasonCode = "skipped_no_fences",
                        source = RecoveryScheduler.SCHEDULE_KEY,
                    )
                    SmartGeofenceLogger.d(appContext, TAG, "Recovery skipped; no fences remain.")
                    finishPending()
                    return@Thread
                }
                RecoveryScheduler.schedule(
                    appContext,
                    SmartGeofenceConfigStore.load(appContext),
                )
                NativeGeofenceApiImpl(appContext).reCreateAfterReboot { result ->
                    complete(result.exceptionOrNull())
                }
                return@Thread
            } catch (e: Throwable) {
                complete(e)
            }
        }.start()
    }

    private fun finishRecovery(
        context: Context,
        finishPending: () -> Unit,
        nativeFailure: Throwable?,
    ) {
        try {
            MainThreadRunner.runBlocking(BootRecoveryCoordinator.MAIN_THREAD_RECOVERY_TIMEOUT_MILLIS) {
                SmartGeofenceController.refresh(context, scheduleRecovery = false)
                SmartGeofenceEventProcessor.emitDueNativeExitFallbacks(context)
                SmartGeofenceEventProcessor.emitDueNativeEnterFallbacks(context)
                NativeExitPendingStore.reschedule(context)
                NativeEnterPendingStore.reschedule(context)
            }
            if (nativeFailure == null) {
                SmartGeofenceDiagnostics.recordTrace(
                    context,
                    stage = "alarm_result",
                    reasonCode = "recovery_completed",
                    source = RecoveryScheduler.SCHEDULE_KEY,
                )
                SmartGeofenceLogger.d(
                    context,
                    TAG,
                    "Scheduled recovery re-armed native geofences and smart layers."
                )
            } else {
                SmartGeofenceDiagnostics.recordTrace(
                    context,
                    stage = "alarm_result",
                    reasonCode = "native_rearm_failed_smart_refreshed",
                    source = RecoveryScheduler.SCHEDULE_KEY,
                    extras = mapOf("errorType" to nativeFailure.javaClass.name),
                )
                SmartGeofenceLogger.w(
                    context,
                    TAG,
                    "Scheduled recovery refreshed smart layers after native rearm failed: " +
                        "${nativeFailure.message}",
                    nativeFailure,
                )
            }
        } catch (e: Throwable) {
            if (e is java.util.concurrent.TimeoutException) {
                SmartGeofenceDiagnostics.recordRecoveryMainThreadTimeout(
                    context,
                    "scheduled_recovery",
                    e.message ?: "main_thread_timeout",
                )
            }
            val message =
                if (nativeFailure == null) {
                    "Scheduled recovery failed: ${e.message}"
                } else {
                    "Scheduled recovery failed after native rearm also failed: " +
                        "${nativeFailure.message}; smart refresh failed: ${e.message}"
                }
            SmartGeofenceLogger.w(context, TAG, message, e)
            SmartGeofenceDiagnostics.recordTrace(
                context,
                stage = "alarm_result",
                reasonCode = "recovery_failed",
                source = RecoveryScheduler.SCHEDULE_KEY,
                extras = mapOf("errorType" to e.javaClass.name),
            )
        } finally {
            if (FenceStore.getAll(context).isNotEmpty()) {
                RecoveryScheduler.schedule(
                    context,
                    SmartGeofenceConfigStore.load(context),
                )
            }
            finishPending()
        }
    }

    companion object {
        private const val TAG = "RecoveryAlarmReceiver"
    }
}
