package com.yarithdev.smart_geofence.config

enum class ForegroundNotificationTapAction(val configValue: String) {
    OpenApp("openApp"),
    Dismiss("dismiss"),
    None("none");

    companion object {
        val Default: ForegroundNotificationTapAction = OpenApp

        fun fromConfigValue(value: String?): ForegroundNotificationTapAction? = when (value) {
            OpenApp.configValue -> OpenApp
            Dismiss.configValue -> Dismiss
            None.configValue -> None
            else -> null
        }
    }
}
