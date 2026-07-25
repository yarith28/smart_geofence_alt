package com.yarithdev.smart_geofence.config

enum class LocationUnavailablePolicy(val configValue: String) {
    Recover("recover"),
    Stop("stop");

    companion object {
        val Default = Recover

        fun fromConfigValue(value: String?): LocationUnavailablePolicy? = when (value) {
            Recover.configValue -> Recover
            Stop.configValue -> Stop
            else -> null
        }
    }
}
