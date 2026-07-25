package com.yarithdev.smart_geofence.core

import android.content.SharedPreferences

internal fun SharedPreferences.safeString(key: String, defaultValue: String? = null): String? {
    if (!contains(key)) return defaultValue
    return runCatching { getString(key, defaultValue) }
        .getOrElse {
            removeInvalid(key)
            defaultValue
        }
}

internal fun SharedPreferences.safeStringSet(
    key: String,
    defaultValue: Set<String>? = null,
): Set<String>? {
    if (!contains(key)) return defaultValue
    return runCatching { getStringSet(key, defaultValue) }
        .getOrElse {
            removeInvalid(key)
            defaultValue
        }
        ?.toSet()
}

internal fun SharedPreferences.safeLong(key: String, defaultValue: Long): Long {
    val value = safeNumber(key) ?: return defaultValue
    val longValue = value.toLong()
    if (value !is Long) edit().putLong(key, longValue).apply()
    return longValue
}

internal fun SharedPreferences.safeLongOrNull(key: String): Long? =
    safeNumber(key)?.let { value ->
        val longValue = value.toLong()
        if (value !is Long) edit().putLong(key, longValue).apply()
        longValue
    }

internal fun SharedPreferences.safeInt(key: String, defaultValue: Int): Int {
    val value = safeNumber(key) ?: return defaultValue
    val longValue = value.toLong()
    return if (longValue >= Int.MIN_VALUE && longValue <= Int.MAX_VALUE) {
        if (value !is Int) edit().putInt(key, longValue.toInt()).apply()
        longValue.toInt()
    } else {
        removeInvalid(key)
        defaultValue
    }
}

internal fun SharedPreferences.safeBoolean(key: String, defaultValue: Boolean): Boolean {
    if (!contains(key)) return defaultValue
    return runCatching { getBoolean(key, defaultValue) }
        .getOrElse {
            removeInvalid(key)
            defaultValue
        }
}

internal fun SharedPreferences.safeBooleanOrNull(key: String): Boolean? {
    if (!contains(key)) return null
    return runCatching { getBoolean(key, false) }
        .getOrElse {
            removeInvalid(key)
            null
        }
}

private fun SharedPreferences.safeNumber(key: String): Number? {
    if (!contains(key)) return null
    val value = all[key]
    return if (value is Number) {
        value
    } else {
        removeInvalid(key)
        null
    }
}

private fun SharedPreferences.removeInvalid(key: String) {
    edit().remove(key).apply()
}
