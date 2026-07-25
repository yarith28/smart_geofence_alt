package com.yarithdev.smart_geofence.config

import io.flutter.plugin.common.MethodCall

internal object SmartGeofenceConfigTransport {
    private const val LEGACY_FIELD_SCHEMA_VERSION = "schemaVersion"
    private const val LEGACY_FIELD_CONFIG_JSON = "configJson"

    fun decode(call: MethodCall): SmartGeofenceConfig {
        val arguments = call.arguments
        if (arguments is String) {
            return SmartGeofenceConfigCodec.decode(arguments)
        }

        val map = arguments as? Map<*, *> ?: emptyMap<Any?, Any?>()
        if (!map.containsKey(LEGACY_FIELD_SCHEMA_VERSION) &&
            !map.containsKey(LEGACY_FIELD_CONFIG_JSON)
        ) {
            return SmartGeofenceConfigCodec.decodeLegacyMap(map)
        }

        val schemaVersion = (map[LEGACY_FIELD_SCHEMA_VERSION] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing configuration schemaVersion.")
        if (schemaVersion != SmartGeofenceConfigCodec.LEGACY_SCHEMA_VERSION) {
            throw IllegalArgumentException(
                "Unsupported configuration schemaVersion=$schemaVersion."
            )
        }
        val configJson = map[LEGACY_FIELD_CONFIG_JSON] as? String
            ?: throw IllegalArgumentException("Missing configuration configJson.")
        return SmartGeofenceConfigCodec.decodeLegacyEnvelope(configJson)
    }
}
