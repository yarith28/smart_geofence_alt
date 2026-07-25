package com.yarithdev.smart_geofence.core

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

internal object AndroidPackageManagerCompat {
    fun getPackageInfo(
        packageManager: PackageManager,
        packageName: String,
        flags: Long,
    ): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags),
            )
        } else {
            getPackageInfoLegacy(packageManager, packageName, flags.toInt())
        }

    fun getReceiverInfo(
        packageManager: PackageManager,
        component: ComponentName,
        flags: Long = 0L,
    ): ActivityInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getReceiverInfo(
                component,
                PackageManager.ComponentInfoFlags.of(flags),
            )
        } else {
            getReceiverInfoLegacy(packageManager, component, flags.toInt())
        }

    fun getServiceInfo(
        packageManager: PackageManager,
        component: ComponentName,
        flags: Long = 0L,
    ): ServiceInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(flags),
            )
        } else {
            getServiceInfoLegacy(packageManager, component, flags.toInt())
        }

    @Suppress("DEPRECATION")
    private fun getPackageInfoLegacy(
        packageManager: PackageManager,
        packageName: String,
        flags: Int,
    ): PackageInfo = packageManager.getPackageInfo(packageName, flags)

    @Suppress("DEPRECATION")
    private fun getReceiverInfoLegacy(
        packageManager: PackageManager,
        component: ComponentName,
        flags: Int,
    ): ActivityInfo = packageManager.getReceiverInfo(component, flags)

    @Suppress("DEPRECATION")
    private fun getServiceInfoLegacy(
        packageManager: PackageManager,
        component: ComponentName,
        flags: Int,
    ): ServiceInfo = packageManager.getServiceInfo(component, flags)
}
