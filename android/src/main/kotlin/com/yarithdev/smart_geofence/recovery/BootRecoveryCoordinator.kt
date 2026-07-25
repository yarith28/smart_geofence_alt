package com.yarithdev.smart_geofence.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.chunkytofustudios.native_geofence.api.NativeGeofenceApiImpl
import com.chunkytofustudios.native_geofence.util.NativeGeofencePersistence
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.confirm.NativeEnterPendingStore
import com.yarithdev.smart_geofence.confirm.NativeExitPendingStore
import com.yarithdev.smart_geofence.confirm.SmartGeofenceEventProcessor
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.MainThreadRunner
import com.yarithdev.smart_geofence.core.SmartGeofenceController
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.store.FenceStore
import java.util.concurrent.atomic.AtomicBoolean

object BootRecoveryCoordinator {
    const val ACTION_BOOT_FOLLOW_UP = "com.yarithdev.smart_geofence.action.BOOT_FOLLOW_UP"
    const val SOURCE_BOOT_IMMEDIATE = "boot_immediate"
    const val SOURCE_BOOT_FOLLOW_UP = "boot_follow_up"
    internal const val EXTRA_ORIGINAL_ACTION = "smart_geofence.boot_original_action"

    private const val TAG = "BootRecoveryCoordinator"
    internal const val SCHEDULE_KEY_BOOT_FOLLOW_UP = "boot_follow_up"
    internal const val MAIN_THREAD_RECOVERY_TIMEOUT_MILLIS = 8_000L
    private const val BROADCAST_FINISH_TIMEOUT_MILLIS = 9_000L
    private const val FOLLOW_UP_DELAY_MILLIS = 120_000L

    fun finishPendingResultOnDeadline(
        context: Context,
        pending: BroadcastReceiver.PendingResult,
        tag: String,
        reason: String,
    ): () -> Unit {
        val appContext = context.applicationContext
        val finished = AtomicBoolean(false)
        val deadline = Thread {
            try {
                Thread.sleep(BROADCAST_FINISH_TIMEOUT_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (finished.compareAndSet(false, true)) {
                SmartGeofenceDiagnostics.recordBroadcastDeadlineFinished(
                    appContext,
                    tag,
                    reason,
                )
                SmartGeofenceLogger.w(
                    appContext,
                    tag,
                    "Finishing $reason broadcast after ${BROADCAST_FINISH_TIMEOUT_MILLIS}ms; " +
                        "recovery may continue in the follow-up pass.",
                )
                pending.finish()
            }
        }.apply {
            name = "smart-geofence-broadcast-deadline"
            isDaemon = true
            start()
        }
        return {
            deadline.interrupt()
            if (finished.compareAndSet(false, true)) {
                pending.finish()
            }
        }
    }

    fun runAsync(
        context: Context,
        source: String,
        action: String?,
        scheduleFollowUp: Boolean,
        onComplete: () -> Unit,
    ) {
        val appContext = context.applicationContext
        Thread {
            try {
                runFullStackRecovery(appContext, source, action, scheduleFollowUp, onComplete)
            } catch (e: Throwable) {
                SmartGeofenceDiagnostics.recordBootRecoveryResult(
                    appContext,
                    source,
                    action,
                    result = "failed",
                    elapsedMillis = 0L,
                    nativeFenceCount = nativeFenceCount(appContext),
                    smartFenceCount = FenceStore.getAll(appContext).size,
                    failureMessage = e.message,
                )
                SmartGeofenceLogger.e(
                    appContext,
                    TAG,
                    "Boot recovery crashed source=$source action=$action: ${e.message}",
                    e,
                )
                onComplete()
            }
        }.start()
    }

    fun scheduleFollowUp(context: Context, originalAction: String?): Boolean {
        val appContext = context.applicationContext
        val pending = followUpPendingIntent(
            appContext,
            PendingIntent.FLAG_UPDATE_CURRENT,
            originalAction,
        ) ?: run {
            SmartGeofenceDiagnostics.recordBootFollowUpSchedule(
                appContext,
                originalAction,
                triggerAtMillis = null,
                result = "failed",
                failureMessage = "Failed to create boot follow-up PendingIntent.",
            )
            return false
        }
        val triggerAtElapsed = SystemClock.elapsedRealtime() + FOLLOW_UP_DELAY_MILLIS
        val triggerAtWall = System.currentTimeMillis() + FOLLOW_UP_DELAY_MILLIS
        val result = AlarmPolicyScheduler.schedule(
            appContext,
            AlarmScheduleRequest(
                alarmType = AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis = triggerAtElapsed,
                primary = pending,
                policy = AlarmSchedulePolicy.InexactOnly,
                scheduleKey = SCHEDULE_KEY_BOOT_FOLLOW_UP,
                logTag = TAG,
                logEventPrefix = "boot_follow_up",
                detail = "originalAction=$originalAction triggerAtWall=$triggerAtWall delay=${FOLLOW_UP_DELAY_MILLIS}ms",
            )
        )
        SmartGeofenceDiagnostics.recordBootFollowUpSchedule(
            appContext,
            originalAction,
            triggerAtMillis = triggerAtWall,
            result = if (result.scheduled) "scheduled" else "failed",
            failureMessage = result.failureReason,
        )
        if (!result.scheduled) {
            pending.cancel()
        }
        return result.scheduled
    }

    fun followUpPendingIntentExists(context: Context): Boolean =
        followUpPendingIntent(
            context.applicationContext,
            PendingIntent.FLAG_NO_CREATE,
            null,
        ) != null

    private fun runFullStackRecovery(
        context: Context,
        source: String,
        action: String?,
        scheduleFollowUp: Boolean,
        onComplete: () -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        val completed = AtomicBoolean(false)
        fun complete(nativeFailure: Throwable?) {
            if (!completed.compareAndSet(false, true)) return
            try {
                finishFullStackRecovery(context, source, action, startedAt, nativeFailure)
            } finally {
                onComplete()
            }
        }

        if (scheduleFollowUp) {
            scheduleFollowUp(context, action)
        }
        SmartGeofenceDiagnostics.recordBootRecoveryStarted(
            context,
            source,
            action,
            nativeFenceCount = nativeFenceCount(context),
            smartFenceCount = FenceStore.getAll(context).size,
        )
        SmartGeofenceLogger.i(
            context,
            TAG,
            "Boot recovery started source=$source action=$action."
        )

        try {
            NativeGeofenceApiImpl(context).reCreateAfterReboot { result ->
                complete(result.exceptionOrNull())
            }
        } catch (e: Throwable) {
            complete(e)
        }
    }

    private fun finishFullStackRecovery(
        context: Context,
        source: String,
        action: String?,
        startedAt: Long,
        nativeFailure: Throwable?,
    ) {
        var failure = nativeFailure
        try {
            MainThreadRunner.runBlocking(MAIN_THREAD_RECOVERY_TIMEOUT_MILLIS) {
                SmartGeofenceController.refresh(context, scheduleRecovery = true)
                SmartGeofenceEventProcessor.emitDueNativeExitFallbacks(context)
                SmartGeofenceEventProcessor.emitDueNativeEnterFallbacks(context)
                NativeExitPendingStore.reschedule(context)
                NativeEnterPendingStore.reschedule(context)
            }
        } catch (e: Throwable) {
            if (e is java.util.concurrent.TimeoutException) {
                SmartGeofenceDiagnostics.recordRecoveryMainThreadTimeout(
                    context,
                    source,
                    e.message ?: "main_thread_timeout",
                )
            }
            val previousFailure = failure
            failure = if (previousFailure == null) e else RuntimeException(
                "${previousFailure.message}; smart refresh failed: ${e.message}",
                e,
            )
        }
        val result = if (failure == null) "completed" else "failed"
        SmartGeofenceDiagnostics.recordBootRecoveryResult(
            context,
            source,
            action,
            result,
            elapsedMillis = System.currentTimeMillis() - startedAt,
            nativeFenceCount = nativeFenceCount(context),
            smartFenceCount = FenceStore.getAll(context).size,
            failureMessage = failure?.message,
        )
        if (failure == null) {
            SmartGeofenceLogger.i(
                context,
                TAG,
                "Boot recovery completed source=$source action=$action."
            )
        } else {
            SmartGeofenceLogger.w(
                context,
                TAG,
                "Boot recovery failed source=$source action=$action: ${failure.message}",
                failure,
            )
        }
    }

    private fun nativeFenceCount(context: Context): Int =
        try {
            NativeGeofencePersistence.getAllGeofences(context).size
        } catch (ignored: Throwable) {
            -1
        }

    private fun followUpPendingIntent(
        context: Context,
        baseFlags: Int,
        originalAction: String?,
    ): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            Constants.PENDING_INTENT_REQUEST_BOOT_FOLLOW_UP,
            Intent(context, BootRecoveryReceiver::class.java).apply {
                action = ACTION_BOOT_FOLLOW_UP
                putExtra(EXTRA_ORIGINAL_ACTION, originalAction)
            },
            flags,
        )
    }
}

class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BootRecoveryCoordinator.ACTION_BOOT_FOLLOW_UP) {
            return
        }
        val pending = goAsync()
        val finishPending = BootRecoveryCoordinator.finishPendingResultOnDeadline(
            context,
            pending,
            "BootRecoveryReceiver",
            "boot follow-up action=${intent.action}",
        )
        BootRecoveryCoordinator.runAsync(
            context,
            BootRecoveryCoordinator.SOURCE_BOOT_FOLLOW_UP,
            intent.getStringExtra(BootRecoveryCoordinator.EXTRA_ORIGINAL_ACTION) ?: intent.action,
            scheduleFollowUp = false,
        ) {
            finishPending()
        }
    }
}
