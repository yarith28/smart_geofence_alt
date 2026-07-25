package com.yarithdev.smart_geofence.activity

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yarithdev.smart_geofence.core.AndroidPackageManagerCompat
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBoolean

enum class ActivityRecognitionPermissionStatus(val configValue: String) {
    NotRequired("notRequired"),
    Granted("granted"),
    Denied("denied"),
    PermanentlyDenied("permanentlyDenied"),
    RequestUnavailable("requestUnavailable"),
    SettingsUnavailable("settingsUnavailable"),
}

object ActivityRecognitionPermissionController {
    const val REQUEST_CODE = 62104

    private const val KEY_REQUESTED = "activity_recognition_permission_requested"
    private const val GMS_ACTIVITY_RECOGNITION_PERMISSION =
        "com.google.android.gms.permission.ACTIVITY_RECOGNITION"

    fun status(
        context: Context,
        activity: Activity? = null,
    ): ActivityRecognitionPermissionStatus {
        val appContext = context.applicationContext
        val permission = requiredPermission()
        if (!isPermissionDeclared(appContext, permission)) {
            return ActivityRecognitionPermissionStatus.RequestUnavailable
        }
        if (hasRequiredPermission(appContext)) {
            return ActivityRecognitionPermissionStatus.Granted
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ActivityRecognitionPermissionStatus.RequestUnavailable
        }
        val requested = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .safeBoolean(KEY_REQUESTED, false)
        val permanentlyDenied = requested &&
            activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        return if (permanentlyDenied) {
            ActivityRecognitionPermissionStatus.PermanentlyDenied
        } else {
            ActivityRecognitionPermissionStatus.Denied
        }
    }

    fun request(activity: Activity) {
        markRequested(activity.applicationContext)
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
            REQUEST_CODE
        )
    }

    fun resolveRequestResult(
        context: Context,
        activity: Activity?,
        grantResults: IntArray,
    ): ActivityRecognitionPermissionStatus {
        markRequested(context.applicationContext)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return status(context, activity)
        }
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            return ActivityRecognitionPermissionStatus.Granted
        }
        return status(context, activity)
    }

    fun openSettings(context: Context): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun markRequested(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }

    internal fun hasRequiredPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context.applicationContext,
            requiredPermission()
        ) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            GMS_ACTIVITY_RECOGNITION_PERMISSION
        } else {
            Manifest.permission.ACTIVITY_RECOGNITION
        }

    private fun isPermissionDeclared(context: Context, permission: String): Boolean {
        val appContext = context.applicationContext
        val packageInfo = try {
            AndroidPackageManagerCompat.getPackageInfo(
                appContext.packageManager,
                appContext.packageName,
                PackageManager.GET_PERMISSIONS.toLong(),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (_: RuntimeException) {
            return false
        }
        return packageInfo.requestedPermissions
            ?.contains(permission) == true
    }
}
