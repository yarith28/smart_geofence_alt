package com.yarithdev.smart_geofence.config

enum class MockLocationPolicy(val configValue: String) {
    Allow("allow"),
    LogOnly("logOnly"),
    Reject("reject");

    companion object {
        const val CONFIG_ALLOW = "allow"
        const val CONFIG_LOG_ONLY = "logOnly"
        const val CONFIG_REJECT = "reject"

        val Default: MockLocationPolicy = LogOnly

        fun fromConfigValue(value: String?): MockLocationPolicy? = when (value) {
            CONFIG_ALLOW -> Allow
            CONFIG_LOG_ONLY -> LogOnly
            CONFIG_REJECT -> Reject
            else -> null
        }
    }
}
