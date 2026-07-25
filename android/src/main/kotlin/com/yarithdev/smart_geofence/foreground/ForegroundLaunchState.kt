package com.yarithdev.smart_geofence.foreground

import android.content.Context
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString

data class ForegroundLaunchSnapshot(
    val activeToken: Long,
    val launchRequestedAtMillis: Long,
    val foregroundReadyAtMillis: Long,
    val lastFailureAtMillis: Long,
    val lastFailureReason: String?,
    val rearmAttemptedAtMillis: Long,
)

object ForegroundLaunchState {
    const val SERVICE_LOCATION_CONFIRM = "locationConfirm"

    private const val KEY_NEXT_TOKEN = "foreground_launch_next_token"
    private const val KEY_ACTIVE_TOKEN = "active_token"
    private const val KEY_REQUESTED_AT = "requested_at"
    private const val KEY_READY_AT = "ready_at"
    private const val KEY_LAST_FAILURE_AT = "last_failure_at"
    private const val KEY_LAST_FAILURE_REASON = "last_failure_reason"
    private const val KEY_LAST_REASON = "last_reason"
    private const val KEY_REARM_ATTEMPTED_AT = "rearm_scheduled_at"

    @Synchronized
    fun begin(context: Context, service: String, reason: String): Long {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val token = prefs.safeLong(KEY_NEXT_TOKEN, 0L) + 1L
        prefs.edit()
            .putLong(KEY_NEXT_TOKEN, token)
            .putLong(key(service, KEY_ACTIVE_TOKEN), token)
            .putLong(key(service, KEY_REQUESTED_AT), System.currentTimeMillis())
            .putLong(key(service, KEY_READY_AT), 0L)
            .putString(key(service, KEY_LAST_REASON), reason)
            .remove(key(service, KEY_LAST_FAILURE_REASON))
            .putLong(key(service, KEY_LAST_FAILURE_AT), 0L)
            .putLong(key(service, KEY_REARM_ATTEMPTED_AT), 0L)
            .apply()
        return token
    }

    @Synchronized
    fun ensureToken(context: Context, service: String, reason: String): Long {
        val active = snapshot(context, service).activeToken
        return if (active > 0L) active else begin(context, service, reason)
    }

    @Synchronized
    fun isCurrent(context: Context, service: String, token: Long): Boolean {
        if (token <= 0L) return false
        return prefs(context.applicationContext).safeLong(key(service, KEY_ACTIVE_TOKEN), 0L) == token
    }

    @Synchronized
    fun markReady(context: Context, service: String, token: Long): ForegroundLaunchSnapshot? {
        val appContext = context.applicationContext
        if (!isCurrent(appContext, service, token)) return null
        prefs(appContext).edit()
            .putLong(key(service, KEY_READY_AT), System.currentTimeMillis())
            .putLong(key(service, KEY_LAST_FAILURE_AT), 0L)
            .remove(key(service, KEY_LAST_FAILURE_REASON))
            .putLong(key(service, KEY_REARM_ATTEMPTED_AT), 0L)
            .apply()
        return snapshot(appContext, service)
    }

    @Synchronized
    fun markFailure(
        context: Context,
        service: String,
        token: Long,
        reason: String,
    ): ForegroundLaunchSnapshot? {
        val appContext = context.applicationContext
        if (!isCurrent(appContext, service, token)) return null
        prefs(appContext).edit()
            .putLong(key(service, KEY_READY_AT), 0L)
            .putLong(key(service, KEY_LAST_FAILURE_AT), System.currentTimeMillis())
            .putString(key(service, KEY_LAST_FAILURE_REASON), reason)
            .apply()
        return snapshot(appContext, service)
    }

    @Synchronized
    fun markRearmAttempted(
        context: Context,
        service: String,
        token: Long,
    ): ForegroundLaunchSnapshot? {
        val appContext = context.applicationContext
        if (!isCurrent(appContext, service, token)) return null
        prefs(appContext).edit()
            .putLong(key(service, KEY_REARM_ATTEMPTED_AT), System.currentTimeMillis())
            .apply()
        return snapshot(appContext, service)
    }

    @Synchronized
    fun clearRearm(context: Context, service: String) {
        prefs(context.applicationContext).edit()
            .putLong(key(service, KEY_REARM_ATTEMPTED_AT), 0L)
            .apply()
    }

    @Synchronized
    fun markStopped(context: Context, service: String) {
        prefs(context.applicationContext).edit()
            .putLong(key(service, KEY_READY_AT), 0L)
            .apply()
    }

    @Synchronized
    fun markStoppedIfCurrent(context: Context, service: String, token: Long): Boolean {
        val appContext = context.applicationContext
        if (!isCurrent(appContext, service, token)) return false
        prefs(appContext).edit()
            .putLong(key(service, KEY_READY_AT), 0L)
            .apply()
        return true
    }

    @Synchronized
    fun snapshot(context: Context, service: String): ForegroundLaunchSnapshot {
        val prefs = prefs(context.applicationContext)
        return ForegroundLaunchSnapshot(
            activeToken = prefs.safeLong(key(service, KEY_ACTIVE_TOKEN), 0L),
            launchRequestedAtMillis = prefs.safeLong(key(service, KEY_REQUESTED_AT), 0L),
            foregroundReadyAtMillis = prefs.safeLong(key(service, KEY_READY_AT), 0L),
            lastFailureAtMillis = prefs.safeLong(key(service, KEY_LAST_FAILURE_AT), 0L),
            lastFailureReason = prefs.safeString(key(service, KEY_LAST_FAILURE_REASON)),
            rearmAttemptedAtMillis = prefs.safeLong(key(service, KEY_REARM_ATTEMPTED_AT), 0L),
        )
    }

    private fun key(service: String, suffix: String): String =
        "foreground_launch_${service}_$suffix"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
