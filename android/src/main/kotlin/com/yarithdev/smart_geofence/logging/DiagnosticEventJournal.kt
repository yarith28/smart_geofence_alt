package com.yarithdev.smart_geofence.logging

import android.content.SharedPreferences
import com.yarithdev.smart_geofence.core.safeLongOrNull
import com.yarithdev.smart_geofence.core.safeString
import org.json.JSONArray
import org.json.JSONObject

internal data class DiagnosticEventJournalSnapshot(
    val entries: List<Map<String, Any?>>,
    val sequence: Long?,
    val droppedCount: Long,
    val corruptEntryCount: Long,
)

internal object DiagnosticEventJournal {
    const val MAX_ENTRIES = 512

    private const val LEGACY_ARRAY_KEY = "diagnostic_event_journal"
    private const val SEQUENCE_KEY = "diagnostic_event_journal_sequence"
    private const val DROPPED_KEY = "diagnostic_event_journal_dropped"
    private const val SLOT_PREFIX = "diagnostic_event_journal_slot_"

    @Synchronized
    fun append(preferences: SharedPreferences, entry: JSONObject): Boolean = try {
        val previousSequence = preferences.safeLongOrNull(SEQUENCE_KEY) ?: 0L
        val sequence = Math.addExact(previousSequence, 1L)
        entry.put("sequence", sequence)
        val slot = ((sequence - 1L) % MAX_ENTRIES).toInt()
        val overwritten = if (previousSequence >= MAX_ENTRIES) 1L else 0L
        val dropped = Math.addExact(
            preferences.safeLongOrNull(DROPPED_KEY) ?: 0L,
            overwritten,
        )
        preferences.edit()
            .putString("$SLOT_PREFIX$slot", entry.toString())
            .putLong(SEQUENCE_KEY, sequence)
            .putLong(DROPPED_KEY, dropped)
            .apply()
        true
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    fun read(preferences: SharedPreferences): DiagnosticEventJournalSnapshot {
        val sequence = preferences.safeLongOrNull(SEQUENCE_KEY)
        val entriesBySequence = linkedMapOf<Long, JSONObject>()
        var corruptCount = 0L

        fun accept(raw: String?) {
            if (raw == null) return
            val entry = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                corruptCount += 1L
                return
            }
            val entrySequence = entry.optLong("sequence", Long.MIN_VALUE)
            if (entrySequence <= 0L) {
                corruptCount += 1L
                return
            }
            entriesBySequence[entrySequence] = entry
        }

        for (slot in 0 until MAX_ENTRIES) {
            accept(preferences.safeString("$SLOT_PREFIX$slot"))
        }

        val legacyRaw = preferences.safeString(LEGACY_ARRAY_KEY)
        if (legacyRaw != null) {
            try {
                val legacy = JSONArray(legacyRaw)
                for (index in 0 until legacy.length()) {
                    val entry = legacy.optJSONObject(index)
                    if (entry == null) {
                        corruptCount += 1L
                    } else {
                        val entrySequence = entry.optLong("sequence", Long.MIN_VALUE)
                        if (entrySequence <= 0L) {
                            corruptCount += 1L
                        } else {
                            entriesBySequence.putIfAbsent(entrySequence, entry)
                        }
                    }
                }
            } catch (_: Throwable) {
                corruptCount += 1L
            }
        }

        val recoveredSequence = sequence ?: entriesBySequence.keys.maxOrNull()
        val oldestRetainedSequence = recoveredSequence
            ?.let { (it - MAX_ENTRIES + 1L).coerceAtLeast(1L) }
            ?: 1L
        val entries = entriesBySequence.entries
            .filter { (entrySequence, _) ->
                entrySequence >= oldestRetainedSequence &&
                    (recoveredSequence == null || entrySequence <= recoveredSequence)
            }
            .sortedBy { it.key }
            .takeLast(MAX_ENTRIES)
            .map { (_, value) -> jsonObjectToMap(value) }
            .toList()
        return DiagnosticEventJournalSnapshot(
            entries = entries,
            sequence = recoveredSequence,
            droppedCount = (preferences.safeLongOrNull(DROPPED_KEY) ?: 0L) + corruptCount,
            corruptEntryCount = corruptCount,
        )
    }

    private fun jsonObjectToMap(value: JSONObject): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        value.keys().forEach { key ->
            val item = value.opt(key)
            result[key] = if (item == JSONObject.NULL) null else item
        }
        return result
    }
}
