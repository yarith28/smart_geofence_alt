package com.yarithdev.smart_geofence.dormant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.yarithdev.smart_geofence.alarm.AlarmPolicyScheduler
import com.yarithdev.smart_geofence.alarm.AlarmSchedulePolicy
import com.yarithdev.smart_geofence.alarm.AlarmScheduleRequest
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

object DormantFarProbeScheduler {
    private const val TAG = "DormantFarProbeScheduler"
    const val SCHEDULE_KEY = "dormant_far_probe"
    internal const val EXTRA_STATE_GENERATION =
        "com.yarithdev.smart_geofence.extra.DORMANT_FAR_STATE_GENERATION"
    private const val KEY_SCHEDULED_GENERATION = "dormant_far_probe_scheduled_generation"

    @Synchronized
    fun schedule(
        context: Context,
        delayMillis: Long,
        stateGeneration: Long,
        detail: String,
    ): Boolean {
        val appContext = context.applicationContext
        val normalizedDelay = delayMillis.coerceAtLeast(0L)
        val triggerAt = System.currentTimeMillis() + normalizedDelay
        cancelPendingIntents(appContext)
        prefs(appContext).edit().putLong(KEY_SCHEDULED_GENERATION, stateGeneration).apply()
        val pending = pendingIntent(
            appContext,
            PendingIntent.FLAG_UPDATE_CURRENT,
            stateGeneration,
        )
            ?: run {
                prefs(appContext).edit().remove(KEY_SCHEDULED_GENERATION).apply()
                SmartGeofenceLogger.w(
                    appContext,
                    TAG,
                    "Failed to create dormant probe PendingIntent.",
                )
                return false
            }
        val result = AlarmPolicyScheduler.schedule(
            appContext,
            AlarmScheduleRequest(
                alarmType = AlarmManager.RTC_WAKEUP,
                triggerAtMillis = triggerAt,
                primary = pending,
                policy = AlarmSchedulePolicy.InexactOnly,
                scheduleKey = SCHEDULE_KEY,
                logTag = TAG,
                logEventPrefix = "dormant_far_probe",
                detail = "delay=${normalizedDelay}ms $detail",
            ),
        )
        if (result.scheduled) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Dormant probe ${result.eventSuffix} in ${normalizedDelay}ms " +
                    "triggerAt=$triggerAt mode=${result.primaryMode?.configValue} $detail.",
            )
            return true
        }
        pending.cancel()
        prefs(appContext).edit().remove(KEY_SCHEDULED_GENERATION).apply()
        SmartGeofenceLogger.w(
            appContext,
            TAG,
            "Failed to schedule dormant probe: ${result.failureReason ?: result.eventSuffix}",
        )
        return false
    }

    @Synchronized
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        cancelPendingIntents(appContext)
        prefs(appContext).edit().remove(KEY_SCHEDULED_GENERATION).apply()
    }

    private fun cancelPendingIntents(context: Context) {
        val candidates = buildList {
            scheduledGeneration(context)?.let { generation ->
                pendingIntent(context, PendingIntent.FLAG_NO_CREATE, generation)?.let { add(it) }
            }
            legacyPendingIntent(context, PendingIntent.FLAG_NO_CREATE)?.let { add(it) }
        }.distinct()
        candidates.forEach {
            AlarmPolicyScheduler.cancel(context, SCHEDULE_KEY, it)
            it.cancel()
            SmartGeofenceLogger.d(context, TAG, "Dormant probe alarm cancelled.")
        }
    }

    fun pendingIntentExists(context: Context): Boolean =
        context.applicationContext.let { appContext ->
            scheduledGeneration(appContext)?.let { generation ->
                pendingIntent(appContext, PendingIntent.FLAG_NO_CREATE, generation)
            } != null || legacyPendingIntent(appContext, PendingIntent.FLAG_NO_CREATE) != null
        }

    private fun pendingIntent(
        context: Context,
        baseFlags: Int,
        stateGeneration: Long? = null,
    ): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val intent = Intent(context, DormantFarProbeReceiver::class.java).apply {
            action = Constants.ACTION_DORMANT_FAR_PROBE
            stateGeneration?.let {
                data = probeIdentityUri(context, it)
                putExtra(EXTRA_STATE_GENERATION, it)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            Constants.PENDING_INTENT_REQUEST_DORMANT_PROBE,
            intent,
            flags,
        )
    }

    private fun legacyPendingIntent(context: Context, baseFlags: Int): PendingIntent? {
        var flags = baseFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val intent = Intent(context, DormantFarProbeReceiver::class.java).apply {
            action = Constants.ACTION_DORMANT_FAR_PROBE
        }
        return PendingIntent.getBroadcast(
            context,
            Constants.PENDING_INTENT_REQUEST_DORMANT_PROBE,
            intent,
            flags,
        )
    }

    private fun scheduledGeneration(context: Context): Long? =
        prefs(context).takeIf { it.contains(KEY_SCHEDULED_GENERATION) }
            ?.getLong(KEY_SCHEDULED_GENERATION, 0L)

    private fun probeIdentityUri(context: Context, generation: Long): Uri =
        Uri.Builder()
            .scheme("smart-geofence")
            .authority(context.packageName)
            .appendPath("dormant-far-probe")
            .appendPath(generation.toString())
            .build()

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
