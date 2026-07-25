package com.yarithdev.smart_geofence.alarm

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLongOrNull
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

enum class ExactAlarmPermissionMode(val configValue: String) {
    BestEffort("bestEffort"),
    Strict("strict");

    companion object {
        const val CONFIG_BEST_EFFORT = "bestEffort"
        const val CONFIG_STRICT = "strict"

        val Default: ExactAlarmPermissionMode = BestEffort

        fun fromConfigValue(value: String?): ExactAlarmPermissionMode? = when (value) {
            CONFIG_BEST_EFFORT -> BestEffort
            CONFIG_STRICT -> Strict
            else -> null
        }
    }
}

enum class ExactAlarmPermissionStatus(val configValue: String) {
    NotRequired("notRequired"),
    Granted("granted"),
    Denied("denied"),
    SettingsUnavailable("settingsUnavailable"),
}

data class ExactAlarmSettingsOpenResult(
    val opened: Boolean,
    val status: ExactAlarmPermissionStatus,
    val route: String?,
    val failureReason: String? = null,
)

internal interface ExactAlarmPermissionBackend {
    val sdkInt: Int
    fun canScheduleExactAlarms(): Boolean
    fun canResolveExactAlarmSettings(): Boolean
    fun canResolveAppSettings(): Boolean
    fun startExactAlarmSettings()
    fun startAppSettings()
}

object ExactAlarmPermissionController {
    const val ERROR_CODE = "exact_alarm_permission_denied"
    private const val TAG = "ExactAlarmPermission"

    private const val ROUTE_EXACT_ALARM_SETTINGS = "exactAlarmSettings"
    private const val ROUTE_APP_SETTINGS = "appSettings"
    private const val RESULT_OPENED = "opened"
    private const val RESULT_SKIPPED = "skipped"
    private const val RESULT_FAILED = "failed"

    private const val LAST_OPENED_AT = "exact_alarm_last_settings_opened_at"
    private const val LAST_OPEN_RESULT = "exact_alarm_last_settings_open_result"
    private const val LAST_OPEN_ROUTE = "exact_alarm_last_settings_open_route"
    private const val LAST_OPEN_FAILURE = "exact_alarm_last_settings_open_failure"

    fun status(context: Context): ExactAlarmPermissionStatus =
        details(AndroidExactAlarmPermissionBackend(context.applicationContext)).status

    fun canScheduleExactAlarms(context: Context): Boolean =
        details(AndroidExactAlarmPermissionBackend(context.applicationContext)).canScheduleExactAlarms

    fun isStrictBlocked(context: Context, config: SmartGeofenceConfig): Boolean =
        isStrictBlocked(
            config.exactAlarmPermissionMode,
            status(context.applicationContext),
        )

    fun isStrictBlocked(
        mode: ExactAlarmPermissionMode,
        status: ExactAlarmPermissionStatus,
    ): Boolean = mode == ExactAlarmPermissionMode.Strict &&
        status != ExactAlarmPermissionStatus.NotRequired &&
        status != ExactAlarmPermissionStatus.Granted

    fun strictFailureDetails(
        context: Context,
        config: SmartGeofenceConfig,
        where: String,
    ): Map<String, Any?> {
        val permission = details(context.applicationContext)
        return permission + linkedMapOf(
            "where" to where,
            "exactAlarmPermissionMode" to config.exactAlarmPermissionMode.configValue,
            "exactAlarmStrictStartupBlocked" to isStrictBlocked(
                config.exactAlarmPermissionMode,
                permission.statusFromMap(),
            ),
        )
    }

    fun details(context: Context): Map<String, Any?> {
        val appContext = context.applicationContext
        val backendDetails = details(AndroidExactAlarmPermissionBackend(appContext))
        val prefs = prefs(appContext)
        return linkedMapOf(
            "exactAlarmPermissionRequired" to backendDetails.permissionRequired,
            "exactAlarmPermissionStatus" to backendDetails.status.configValue,
            "exactAlarmPermissionGranted" to backendDetails.canScheduleExactAlarms,
            "exactAlarmSettingsIntentAvailable" to backendDetails.exactAlarmSettingsIntentAvailable,
            "exactAlarmAppSettingsFallbackAvailable" to backendDetails.appSettingsFallbackAvailable,
            "exactAlarmSettingsCanOpen" to backendDetails.settingsCanOpen,
            "lastExactAlarmSettingsOpenedAtMillis" to nullableLong(prefs, LAST_OPENED_AT),
            "lastExactAlarmSettingsOpenResult" to prefs.safeString(LAST_OPEN_RESULT),
            "lastExactAlarmSettingsOpenRoute" to prefs.safeString(LAST_OPEN_ROUTE),
            "lastExactAlarmSettingsOpenFailureReason" to prefs.safeString(LAST_OPEN_FAILURE),
        )
    }

    fun openSettings(context: Context): ExactAlarmSettingsOpenResult {
        val appContext = context.applicationContext
        val backend = AndroidExactAlarmPermissionBackend(appContext)
        val result = openSettings(backend)
        recordOpenResult(appContext, result)
        val detail = "status=${result.status.configValue} route=${result.route} reason=${result.failureReason}"
        if (result.opened) {
            SmartGeofenceLogger.d(appContext, TAG, "exact_alarm_settings_opened $detail")
        } else {
            SmartGeofenceLogger.w(appContext, TAG, "exact_alarm_settings_not_opened $detail")
        }
        return result
    }

    internal fun details(backend: ExactAlarmPermissionBackend): ExactAlarmPermissionDetails {
        val permissionRequired = backend.sdkInt >= Build.VERSION_CODES.S
        val canSchedule = !permissionRequired || backend.canScheduleExactAlarms()
        val exactSettingsAvailable = permissionRequired && backend.canResolveExactAlarmSettings()
        val appSettingsAvailable = backend.canResolveAppSettings()
        val status = when {
            !permissionRequired -> ExactAlarmPermissionStatus.NotRequired
            canSchedule -> ExactAlarmPermissionStatus.Granted
            exactSettingsAvailable || appSettingsAvailable -> ExactAlarmPermissionStatus.Denied
            else -> ExactAlarmPermissionStatus.SettingsUnavailable
        }
        return ExactAlarmPermissionDetails(
            permissionRequired = permissionRequired,
            status = status,
            canScheduleExactAlarms = canSchedule,
            exactAlarmSettingsIntentAvailable = exactSettingsAvailable,
            appSettingsFallbackAvailable = appSettingsAvailable,
        )
    }

    internal fun openSettings(
        backend: ExactAlarmPermissionBackend,
    ): ExactAlarmSettingsOpenResult {
        val current = details(backend)
        if (!current.permissionRequired || current.canScheduleExactAlarms) {
            return ExactAlarmSettingsOpenResult(
                opened = false,
                status = current.status,
                route = null,
            )
        }

        var exactFailure: Throwable? = null
        if (current.exactAlarmSettingsIntentAvailable) {
            runCatching { backend.startExactAlarmSettings() }
                .onSuccess {
                    return ExactAlarmSettingsOpenResult(
                        opened = true,
                        status = current.status,
                        route = ROUTE_EXACT_ALARM_SETTINGS,
                    )
                }
                .onFailure { exactFailure = it }
        }

        if (current.appSettingsFallbackAvailable) {
            runCatching { backend.startAppSettings() }
                .onSuccess {
                    return ExactAlarmSettingsOpenResult(
                        opened = true,
                        status = current.status,
                        route = ROUTE_APP_SETTINGS,
                        failureReason = exactFailure?.javaClass?.simpleName,
                    )
                }
                .onFailure { appFailure ->
                    return ExactAlarmSettingsOpenResult(
                        opened = false,
                        status = ExactAlarmPermissionStatus.SettingsUnavailable,
                        route = ROUTE_APP_SETTINGS,
                        failureReason = exactFailureReason(exactFailure, appFailure),
                    )
                }
        }

        return ExactAlarmSettingsOpenResult(
            opened = false,
            status = ExactAlarmPermissionStatus.SettingsUnavailable,
            route = null,
            failureReason = exactFailure?.javaClass?.simpleName ?: "settings_unavailable",
        )
    }

    private fun recordOpenResult(
        context: Context,
        result: ExactAlarmSettingsOpenResult,
    ) {
        val openResult = when {
            result.opened -> RESULT_OPENED
            result.failureReason == null -> RESULT_SKIPPED
            else -> RESULT_FAILED
        }
        prefs(context).edit()
            .putLong(LAST_OPENED_AT, System.currentTimeMillis())
            .putString(LAST_OPEN_RESULT, openResult)
            .putNullableString(LAST_OPEN_ROUTE, result.route)
            .putNullableString(LAST_OPEN_FAILURE, result.failureReason)
            .apply()
    }

    private fun exactFailureReason(exactFailure: Throwable?, fallbackFailure: Throwable): String =
        "exact=${exactFailure?.javaClass?.simpleName ?: "unavailable"};" +
            "fallback=${fallbackFailure.javaClass.simpleName}"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun nullableLong(prefs: SharedPreferences, key: String): Long? =
        prefs.safeLongOrNull(key)

    private fun SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)
}

data class ExactAlarmPermissionDetails(
    val permissionRequired: Boolean,
    val status: ExactAlarmPermissionStatus,
    val canScheduleExactAlarms: Boolean,
    val exactAlarmSettingsIntentAvailable: Boolean,
    val appSettingsFallbackAvailable: Boolean,
) {
    val settingsCanOpen: Boolean
        get() = exactAlarmSettingsIntentAvailable || appSettingsFallbackAvailable
}

private fun Map<String, Any?>.statusFromMap(): ExactAlarmPermissionStatus {
    val status = this["exactAlarmPermissionStatus"] as? String
    return ExactAlarmPermissionStatus.values().firstOrNull { it.configValue == status }
        ?: ExactAlarmPermissionStatus.SettingsUnavailable
}

private class AndroidExactAlarmPermissionBackend(context: Context) : ExactAlarmPermissionBackend {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override val sdkInt: Int = Build.VERSION.SDK_INT

    override fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() == true
    }

    override fun canResolveExactAlarmSettings(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canResolve(exactAlarmSettingsIntent())

    override fun canResolveAppSettings(): Boolean = canResolve(appSettingsIntent())

    override fun startExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        start(exactAlarmSettingsIntent())
    }

    override fun startAppSettings() {
        start(appSettingsIntent())
    }

    private fun start(intent: Intent) {
        val launchIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(launchIntent)
        } catch (failure: ActivityNotFoundException) {
            throw failure
        }
    }

    private fun canResolve(intent: Intent): Boolean =
        intent.resolveActivity(appContext.packageManager) != null

    private fun exactAlarmSettingsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = packageUri()
        }

    private fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = packageUri()
        }

    private fun packageUri(): Uri = Uri.parse("package:${appContext.packageName}")
}
