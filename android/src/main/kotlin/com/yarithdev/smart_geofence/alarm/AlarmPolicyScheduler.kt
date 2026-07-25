package com.yarithdev.smart_geofence.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBooleanOrNull
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeLongOrNull
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics

sealed class AlarmSchedulePolicy(val configValue: String) {
    data object ExactOnly : AlarmSchedulePolicy(CONFIG_EXACT_ONLY)
    data object ExactWithInexactFallback : AlarmSchedulePolicy(CONFIG_EXACT_WITH_INEXACT_FALLBACK)
    data object InexactWithExactGuard : AlarmSchedulePolicy(CONFIG_INEXACT_WITH_EXACT_GUARD)
    data object InexactOnly : AlarmSchedulePolicy(CONFIG_INEXACT_ONLY)

    override fun toString(): String = configValue

    companion object {
        const val CONFIG_EXACT_ONLY = "exactOnly"
        const val CONFIG_EXACT_WITH_INEXACT_FALLBACK = "exactWithInexactFallback"
        const val CONFIG_INEXACT_WITH_EXACT_GUARD = "inexactWithExactGuard"
        const val CONFIG_INEXACT_ONLY = "inexactOnly"

        val Default: AlarmSchedulePolicy = ExactWithInexactFallback

        fun fromConfigValue(value: String?): AlarmSchedulePolicy? = when (value) {
            CONFIG_EXACT_ONLY -> ExactOnly
            CONFIG_EXACT_WITH_INEXACT_FALLBACK -> ExactWithInexactFallback
            CONFIG_INEXACT_WITH_EXACT_GUARD -> InexactWithExactGuard
            CONFIG_INEXACT_ONLY -> InexactOnly
            else -> null
        }
    }
}

enum class AlarmScheduleMode(val configValue: String) {
    Exact("exact"),
    Inexact("inexact");

    companion object {
        fun fromConfigValue(value: String?): AlarmScheduleMode? =
            entries.firstOrNull { it.configValue == value }
    }
}

data class AlarmScheduleRequest<T>(
    val alarmType: Int,
    val triggerAtMillis: Long,
    val primary: T,
    val policy: AlarmSchedulePolicy = AlarmSchedulePolicy.Default,
    val guard: T? = null,
    val guardDelayMillis: Long? = null,
    val scheduleKey: String,
    val scheduleToken: Long = 0L,
    val logTag: String,
    val logEventPrefix: String,
    val detail: String? = null,
)

data class AlarmScheduleResult(
    val scheduled: Boolean,
    val policy: AlarmSchedulePolicy,
    val primaryMode: AlarmScheduleMode?,
    val guardMode: AlarmScheduleMode?,
    val exactAllowed: Boolean,
    val triggerAtMillis: Long,
    val guardTriggerAtMillis: Long?,
    val eventSuffix: String,
    val guardEventSuffix: String? = null,
    val failureReason: String? = null,
)

internal interface AlarmBackend<T> {
    val available: Boolean
    fun canScheduleExactAlarms(): Boolean
    fun scheduleExact(alarmType: Int, triggerAtMillis: Long, operation: T)
    fun scheduleInexact(alarmType: Int, triggerAtMillis: Long, operation: T)
    fun cancel(operation: T)
}

internal interface AlarmGuardTokenStore {
    fun persist(scheduleKey: String, token: Long): Boolean
    fun clear(scheduleKey: String): Boolean
}

object AlarmPolicyScheduler {
    const val EXTRA_SCHEDULE_TOKEN = "smart_geofence.alarm_schedule_token"
    const val EXTRA_IS_GUARD = "smart_geofence.alarm_is_guard"
    const val EXTRA_IS_GUARDED = "smart_geofence.alarm_is_guarded"

    fun schedule(
        context: Context,
        request: AlarmScheduleRequest<PendingIntent>,
    ): AlarmScheduleResult {
        val appContext = context.applicationContext
        val result = schedule(
            request,
            AndroidAlarmBackend(appContext),
            SharedPreferencesAlarmGuardTokenStore(appContext),
        )
        recordDiagnostic(appContext, request, result)
        log(appContext, request, result)
        return result
    }

    internal fun <T> schedule(
        request: AlarmScheduleRequest<T>,
        backend: AlarmBackend<T>,
        guardTokenStore: AlarmGuardTokenStore? = null,
    ): AlarmScheduleResult {
        val requiresGuardToken =
            request.policy == AlarmSchedulePolicy.InexactWithExactGuard &&
                request.scheduleToken > 0L
        if (requiresGuardToken) {
            val persisted = guardTokenStore?.persist(
                request.scheduleKey,
                request.scheduleToken,
            ) == true
            if (!persisted) {
                backend.cancelQuietly(request.primary)
                request.guard?.let { backend.cancelQuietly(it) }
                guardTokenStore?.clear(request.scheduleKey)
                return request.result(
                    scheduled = false,
                    primaryMode = null,
                    guardMode = null,
                    exactAllowed = false,
                    guardTriggerAtMillis = null,
                    eventSuffix = "guard_token_persist_failed",
                    failureReason = "guard_token_persist_failed",
                )
            }
        } else if (
            guardTokenStore != null &&
            !guardTokenStore.clear(request.scheduleKey)
        ) {
            backend.cancelQuietly(request.primary)
            request.guard?.let { backend.cancelQuietly(it) }
            return request.result(
                scheduled = false,
                primaryMode = null,
                guardMode = null,
                exactAllowed = false,
                guardTriggerAtMillis = null,
                eventSuffix = "guard_token_clear_failed",
                failureReason = "guard_token_clear_failed",
            )
        }

        val result = schedulePolicy(request, backend)
        if (requiresGuardToken && !result.scheduled) {
            if (result.guardEventSuffix != "guard_exact_failed") {
                request.guard?.let { backend.cancelQuietly(it) }
            }
            guardTokenStore?.clear(request.scheduleKey)
        }
        return result
    }

    private fun <T> schedulePolicy(
        request: AlarmScheduleRequest<T>,
        backend: AlarmBackend<T>,
    ): AlarmScheduleResult {
        val exactAllowed = backend.available && backend.canScheduleExactAlarms()
        if (!backend.available) {
            return request.result(
                scheduled = false,
                primaryMode = null,
                guardMode = null,
                exactAllowed = false,
                guardTriggerAtMillis = null,
                eventSuffix = "failed",
                failureReason = "alarm_manager_unavailable",
            )
        }

        return when (request.policy) {
            AlarmSchedulePolicy.ExactOnly -> {
                if (!exactAllowed) {
                    request.result(
                        scheduled = false,
                        primaryMode = null,
                        guardMode = null,
                        exactAllowed = false,
                        guardTriggerAtMillis = null,
                        eventSuffix = "exact_unavailable",
                        failureReason = "exact_alarm_unavailable",
                    )
                } else {
                    scheduleExact(request, backend, exactAllowed, "exact_scheduled")
                }
            }

            AlarmSchedulePolicy.ExactWithInexactFallback -> {
                if (exactAllowed) {
                    val exact = runCatching {
                        backend.scheduleExact(request.alarmType, request.triggerAtMillis, request.primary)
                    }
                    if (exact.isSuccess) {
                        request.result(
                            scheduled = true,
                            primaryMode = AlarmScheduleMode.Exact,
                            guardMode = null,
                            exactAllowed = true,
                            guardTriggerAtMillis = null,
                            eventSuffix = "exact_scheduled",
                        )
                    } else {
                        runCatching {
                            backend.scheduleInexact(request.alarmType, request.triggerAtMillis, request.primary)
                        }.fold(
                            onSuccess = {
                                request.result(
                                    scheduled = true,
                                    primaryMode = AlarmScheduleMode.Inexact,
                                    guardMode = null,
                                    exactAllowed = true,
                                    guardTriggerAtMillis = null,
                                    eventSuffix = "exact_failed_inexact_fallback_scheduled",
                                    failureReason = exact.exceptionOrNull()?.javaClass?.simpleName,
                                )
                            },
                            onFailure = { fallbackFailure ->
                                backend.cancelQuietly(request.primary)
                                request.result(
                                    scheduled = false,
                                    primaryMode = null,
                                    guardMode = null,
                                    exactAllowed = true,
                                    guardTriggerAtMillis = null,
                                    eventSuffix = "exact_failed_inexact_fallback_failed",
                                    failureReason = exactFailureReason(
                                        exact.exceptionOrNull(),
                                        fallbackFailure,
                                    ),
                                )
                            },
                        )
                    }
                } else {
                    scheduleInexact(
                        request,
                        backend,
                        exactAllowed = false,
                        eventSuffix = "inexact_scheduled",
                        unavailableExact = true,
                    )
                }
            }

            AlarmSchedulePolicy.InexactWithExactGuard -> {
                val primary = scheduleInexact(
                    request,
                    backend,
                    exactAllowed = exactAllowed,
                    eventSuffix = "inexact_scheduled",
                )
                if (!primary.scheduled) return primary

                val guardOperation = request.guard
                val guardDelay = request.guardDelayMillis?.takeIf { it > 0L }
                val guardTriggerAtMillis = guardDelay?.let { safeAdd(request.triggerAtMillis, it) }
                when {
                    guardOperation == null || guardTriggerAtMillis == null -> primary.copy(
                        guardEventSuffix = "guard_not_configured",
                        failureReason = primary.failureReason ?: "guard_not_configured",
                    )
                    !exactAllowed -> primary.copy(
                        guardEventSuffix = "guard_exact_unavailable",
                        failureReason = primary.failureReason ?: "exact_alarm_unavailable",
                    )
                    else -> runCatching {
                        backend.scheduleExact(request.alarmType, guardTriggerAtMillis, guardOperation)
                    }.fold(
                        onSuccess = {
                            primary.copy(
                                guardMode = AlarmScheduleMode.Exact,
                                guardTriggerAtMillis = guardTriggerAtMillis,
                                guardEventSuffix = "guard_exact_scheduled",
                            )
                        },
                        onFailure = { failure ->
                            backend.cancelQuietly(request.primary)
                            backend.cancelQuietly(guardOperation)
                            primary.copy(
                                scheduled = false,
                                primaryMode = null,
                                guardTriggerAtMillis = guardTriggerAtMillis,
                                guardEventSuffix = "guard_exact_failed",
                                failureReason = primary.failureReason ?: failure.javaClass.simpleName,
                            )
                        },
                    )
                }
            }

            AlarmSchedulePolicy.InexactOnly -> scheduleInexact(
                request,
                backend,
                exactAllowed = exactAllowed,
                eventSuffix = "inexact_scheduled",
            )
        }
    }

    fun cancel(
        context: Context,
        scheduleKey: String,
        vararg pendingIntents: PendingIntent?,
    ) {
        val backend = AndroidAlarmBackend(context.applicationContext)
        pendingIntents.filterNotNull().forEach { backend.cancelQuietly(it) }
        clearGuardToken(context.applicationContext, scheduleKey)
    }

    internal fun <T> cancel(
        request: AlarmScheduleRequest<T>,
        backend: AlarmBackend<T>,
    ) {
        backend.cancelQuietly(request.primary)
        request.guard?.let { backend.cancelQuietly(it) }
    }

    fun newScheduleToken(): Long {
        val mixed = (SystemClock.elapsedRealtimeNanos() xor System.currentTimeMillis()) and
            Long.MAX_VALUE
        return mixed.takeIf { it > 0L } ?: 1L
    }

    fun claimGuardedFire(context: Context, scheduleKey: String, token: Long): Boolean {
        if (token <= 0L) {
            SmartGeofenceDiagnostics.recordTrace(
                context,
                stage = "alarm_fired",
                reasonCode = "unguarded",
                source = scheduleKey,
            )
            return true
        }
        val appContext = context.applicationContext
        synchronized(this) {
            val prefs = prefs(appContext)
            val key = guardTokenKey(scheduleKey)
            if (!prefs.contains(key)) {
                recordAlarmFire(appContext, scheduleKey, token, "missing_token")
                return false
            }
            val activeToken = prefs.safeLong(key, 0L)
            if (activeToken != token) {
                recordAlarmFire(appContext, scheduleKey, token, "stale_token")
                return false
            }
            if (!prefs.edit().remove(key).commit()) {
                recordAlarmFire(appContext, scheduleKey, token, "claim_persist_failed")
                return false
            }
            recordAlarmFire(appContext, scheduleKey, token, "claimed")
            return true
        }
    }

    fun diagnosticStatus(context: Context, scheduleKey: String): Map<String, Any?> {
        val prefs = prefs(context.applicationContext)
        val prefix = diagnosticPrefix(scheduleKey)
        return linkedMapOf(
            "where" to scheduleKey,
            "configuredPolicy" to prefs.safeString("${prefix}_policy"),
            "actualMode" to prefs.safeString("${prefix}_mode"),
            "guardMode" to prefs.safeString("${prefix}_guard_mode"),
            "exactAllowed" to nullableBoolean(prefs, "${prefix}_exact_allowed"),
            "triggerAtMillis" to nullableLong(prefs, "${prefix}_trigger_at"),
            "guardTriggerAtMillis" to nullableLong(prefs, "${prefix}_guard_trigger_at"),
            "scheduledAtMillis" to nullableLong(prefs, "${prefix}_scheduled_at"),
            "result" to prefs.safeString("${prefix}_result"),
            "event" to prefs.safeString("${prefix}_event"),
            "guardEvent" to prefs.safeString("${prefix}_guard_event"),
            "failureReason" to prefs.safeString("${prefix}_failure"),
        )
    }

    fun diagnosticActualMode(context: Context, scheduleKey: String): AlarmScheduleMode? =
        AlarmScheduleMode.fromConfigValue(
            diagnosticStatus(context, scheduleKey)["actualMode"] as? String,
        )

    private fun <T> scheduleExact(
        request: AlarmScheduleRequest<T>,
        backend: AlarmBackend<T>,
        exactAllowed: Boolean,
        eventSuffix: String,
    ): AlarmScheduleResult = runCatching {
        backend.scheduleExact(request.alarmType, request.triggerAtMillis, request.primary)
    }.fold(
        onSuccess = {
            request.result(
                scheduled = true,
                primaryMode = AlarmScheduleMode.Exact,
                guardMode = null,
                exactAllowed = exactAllowed,
                guardTriggerAtMillis = null,
                eventSuffix = eventSuffix,
            )
        },
        onFailure = { failure ->
            backend.cancelQuietly(request.primary)
            request.result(
                scheduled = false,
                primaryMode = null,
                guardMode = null,
                exactAllowed = exactAllowed,
                guardTriggerAtMillis = null,
                eventSuffix = "exact_failed",
                failureReason = failure.javaClass.simpleName,
            )
        },
    )

    private fun <T> scheduleInexact(
        request: AlarmScheduleRequest<T>,
        backend: AlarmBackend<T>,
        exactAllowed: Boolean,
        eventSuffix: String,
        unavailableExact: Boolean = false,
    ): AlarmScheduleResult = runCatching {
        backend.scheduleInexact(request.alarmType, request.triggerAtMillis, request.primary)
    }.fold(
        onSuccess = {
            request.result(
                scheduled = true,
                primaryMode = AlarmScheduleMode.Inexact,
                guardMode = null,
                exactAllowed = exactAllowed,
                guardTriggerAtMillis = null,
                eventSuffix = eventSuffix,
                failureReason = if (unavailableExact) "exact_alarm_unavailable" else null,
            )
        },
        onFailure = { failure ->
            backend.cancelQuietly(request.primary)
            request.result(
                scheduled = false,
                primaryMode = null,
                guardMode = null,
                exactAllowed = exactAllowed,
                guardTriggerAtMillis = null,
                eventSuffix = "failed",
                failureReason = failure.javaClass.simpleName,
            )
        },
    )

    private fun <T> AlarmScheduleRequest<T>.result(
        scheduled: Boolean,
        primaryMode: AlarmScheduleMode?,
        guardMode: AlarmScheduleMode?,
        exactAllowed: Boolean,
        guardTriggerAtMillis: Long?,
        eventSuffix: String,
        failureReason: String? = null,
    ): AlarmScheduleResult = AlarmScheduleResult(
        scheduled = scheduled,
        policy = policy,
        primaryMode = primaryMode,
        guardMode = guardMode,
        exactAllowed = exactAllowed,
        triggerAtMillis = triggerAtMillis,
        guardTriggerAtMillis = guardTriggerAtMillis,
        eventSuffix = eventSuffix,
        failureReason = failureReason,
    )

    private fun <T> AlarmBackend<T>.cancelQuietly(operation: T) {
        runCatching { cancel(operation) }
    }

    private fun clearGuardToken(context: Context, scheduleKey: String) {
        prefs(context).edit().remove(guardTokenKey(scheduleKey)).commit()
    }

    private fun recordDiagnostic(
        context: Context,
        request: AlarmScheduleRequest<PendingIntent>,
        result: AlarmScheduleResult,
    ) {
        val prefix = diagnosticPrefix(request.scheduleKey)
        prefs(context).edit()
            .putString("${prefix}_policy", result.policy.configValue)
            .putNullableString("${prefix}_mode", result.primaryMode?.configValue)
            .putNullableString("${prefix}_guard_mode", result.guardMode?.configValue)
            .putBoolean("${prefix}_exact_allowed", result.exactAllowed)
            .putLong("${prefix}_trigger_at", result.triggerAtMillis)
            .putNullableLong("${prefix}_guard_trigger_at", result.guardTriggerAtMillis)
            .putLong("${prefix}_scheduled_at", System.currentTimeMillis())
            .putString("${prefix}_result", if (result.scheduled) "scheduled" else "failed")
            .putString("${prefix}_event", "${request.logEventPrefix}_${result.eventSuffix}")
            .putNullableString(
                "${prefix}_guard_event",
                result.guardEventSuffix?.let { "${request.logEventPrefix}_$it" },
            )
            .putNullableString("${prefix}_failure", result.failureReason)
            .apply()
        SmartGeofenceDiagnostics.recordTrace(
            context,
            stage = "alarm_scheduled",
            reasonCode = if (result.scheduled) result.eventSuffix else
                (result.failureReason ?: result.eventSuffix),
            source = request.scheduleKey,
            extras = linkedMapOf(
                "policy" to result.policy.configValue,
                "mode" to result.primaryMode?.configValue,
                "guardMode" to result.guardMode?.configValue,
                "exactAllowed" to result.exactAllowed,
                "triggerAtMillis" to result.triggerAtMillis,
                "guardTriggerAtMillis" to result.guardTriggerAtMillis,
                "scheduled" to result.scheduled,
            ),
        )
    }

    private fun recordAlarmFire(
        context: Context,
        scheduleKey: String,
        token: Long,
        outcome: String,
    ) {
        val triggerAt = diagnosticStatus(context, scheduleKey)["triggerAtMillis"] as? Long
        SmartGeofenceDiagnostics.recordTrace(
            context,
            stage = "alarm_fired",
            reasonCode = outcome,
            source = scheduleKey,
            extras = linkedMapOf(
                "scheduleToken" to token,
                "triggerAtMillis" to triggerAt,
                "latenessMillis" to triggerAt?.let {
                    (System.currentTimeMillis() - it).coerceAtLeast(0L)
                },
            ),
        )
    }

    private fun log(
        context: Context,
        request: AlarmScheduleRequest<PendingIntent>,
        result: AlarmScheduleResult,
    ) {
        val detail = buildDetail(request, result)
        val event = "${request.logEventPrefix}_${result.eventSuffix}"
        val exactWarningEvent = exactWarningEvent(request, result)
        if (exactWarningEvent != null && exactWarningEvent != event) {
            SmartGeofenceLogger.w(context, request.logTag, "$exactWarningEvent $detail")
        }
        val primaryShouldWarn = !result.scheduled || exactWarningEvent == event
        if (primaryShouldWarn) {
            SmartGeofenceLogger.w(context, request.logTag, "$event $detail")
        } else {
            SmartGeofenceLogger.d(context, request.logTag, "$event $detail")
        }
        result.guardEventSuffix?.let { suffix ->
            val guardEvent = "${request.logEventPrefix}_$suffix"
            val message = buildString {
                append("$guardEvent where=${request.scheduleKey}")
                append(" policy=${result.policy.configValue}")
                append(" exactAllowed=${result.exactAllowed}")
                append(" guardTriggerAt=${result.guardTriggerAtMillis}")
                result.failureReason?.let { append(" reason=$it") }
                request.detail?.let { append(" $it") }
            }
            if (suffix.endsWith("scheduled")) {
                SmartGeofenceLogger.d(context, request.logTag, message)
            } else {
                SmartGeofenceLogger.w(context, request.logTag, message)
            }
        }
    }

    private fun buildDetail(
        request: AlarmScheduleRequest<PendingIntent>,
        result: AlarmScheduleResult,
    ): String = buildString {
        append("where=${request.scheduleKey}")
        append(" policy=${result.policy.configValue}")
        append(" mode=${result.primaryMode?.configValue}")
        append(" exactAllowed=${result.exactAllowed}")
        append(" triggerAt=${result.triggerAtMillis}")
        result.guardTriggerAtMillis?.let { append(" guardTriggerAt=$it") }
        result.failureReason?.let { append(" reason=$it") }
        request.detail?.let { append(" $it") }
    }

    private fun exactWarningEvent(
        request: AlarmScheduleRequest<PendingIntent>,
        result: AlarmScheduleResult,
    ): String? = when {
        result.eventSuffix == "exact_unavailable" -> "${request.logEventPrefix}_exact_unavailable"
        result.eventSuffix.startsWith("exact_failed") -> "${request.logEventPrefix}_${result.eventSuffix}"
        result.failureReason == "exact_alarm_unavailable" -> "${request.logEventPrefix}_exact_unavailable"
        result.failureReason?.contains("exact=") == true -> "${request.logEventPrefix}_exact_failed"
        else -> null
    }

    private fun exactFailureReason(
        exactFailure: Throwable?,
        fallbackFailure: Throwable,
    ): String = "exact=${exactFailure?.javaClass?.simpleName ?: "unknown"};fallback=${fallbackFailure.javaClass.simpleName}"

    private fun safeAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun guardTokenKey(scheduleKey: String): String = "alarm_guard_token_$scheduleKey"

    private fun diagnosticPrefix(scheduleKey: String): String = "alarm_schedule_$scheduleKey"

    private fun nullableLong(prefs: SharedPreferences, key: String): Long? =
        prefs.safeLongOrNull(key)

    private fun nullableBoolean(prefs: SharedPreferences, key: String): Boolean? =
        prefs.safeBooleanOrNull(key)

    private fun SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?,
    ): SharedPreferences.Editor = if (value == null) remove(key) else putLong(key, value)

    private fun SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)
}

private class SharedPreferencesAlarmGuardTokenStore(context: Context) : AlarmGuardTokenStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    override fun persist(scheduleKey: String, token: Long): Boolean = preferences.edit()
        .putLong("alarm_guard_token_$scheduleKey", token)
        .commit()

    override fun clear(scheduleKey: String): Boolean = preferences.edit()
        .remove("alarm_guard_token_$scheduleKey")
        .commit()
}

private class AndroidAlarmBackend(context: Context) : AlarmBackend<PendingIntent> {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override val available: Boolean = alarmManager != null

    override fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() == true
    }

    override fun scheduleExact(alarmType: Int, triggerAtMillis: Long, operation: PendingIntent) {
        val manager = alarmManager ?: throw IllegalStateException("AlarmManager unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setExactAndAllowWhileIdle(alarmType, triggerAtMillis, operation)
        } else {
            manager.setExact(alarmType, triggerAtMillis, operation)
        }
    }

    override fun scheduleInexact(alarmType: Int, triggerAtMillis: Long, operation: PendingIntent) {
        val manager = alarmManager ?: throw IllegalStateException("AlarmManager unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setAndAllowWhileIdle(alarmType, triggerAtMillis, operation)
        } else {
            manager.set(alarmType, triggerAtMillis, operation)
        }
    }

    override fun cancel(operation: PendingIntent) {
        alarmManager?.cancel(operation)
        operation.cancel()
    }
}
