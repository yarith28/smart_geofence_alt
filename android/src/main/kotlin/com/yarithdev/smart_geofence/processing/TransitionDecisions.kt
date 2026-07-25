package com.yarithdev.smart_geofence.processing

import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.delivery.EventDedupDeliveryState
import com.yarithdev.smart_geofence.delivery.EventDedupRecord
import com.yarithdev.smart_geofence.delivery.hasFreshPendingEnqueue
import com.yarithdev.smart_geofence.time.AndroidMonotonicTime

internal fun nativeEnterPayloadTooFarOutside(
    edgeDistanceMeters: Double,
    accuracyMeters: Double?,
    slackMeters: Double,
): Boolean {
    if (!edgeDistanceMeters.isFinite()) return false
    val accuracySlack = accuracyMeters?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    return edgeDistanceMeters > slackMeters.coerceAtLeast(0.0) + accuracySlack
}

internal fun nativeFallbackSupersededByOppositeEvent(
    record: EventDedupRecord?,
    oppositeEventName: String,
    triggeredAtMillis: Long,
): Boolean =
    record?.eventName == oppositeEventName &&
        record.deliveryState == EventDedupDeliveryState.ENQUEUED &&
        record.deliveredAtMillis > triggeredAtMillis

internal fun nativeFallbackBlockedByPendingOppositeEvent(
    record: EventDedupRecord?,
    oppositeEventName: String,
    triggeredAtMillis: Long,
    now: AndroidMonotonicTime,
): Boolean =
    record?.eventName == oppositeEventName &&
        record.hasFreshPendingEnqueue(now) &&
        record.deliveredAtMillis > triggeredAtMillis

internal fun nativeFallbackSupersedeDiagnostic(
    pendingEventName: String,
    pendingTriggeredAtMillis: Long,
    oppositeEventName: String,
    oppositeDeliveredAtMillis: Long?,
    source: String,
    reason: String,
): String {
    val deliveryGapMillis = oppositeDeliveredAtMillis?.let { it - pendingTriggeredAtMillis }
    return "pendingEvent=$pendingEventName " +
        "pendingTriggeredAtMillis=$pendingTriggeredAtMillis " +
        "oppositeEvent=$oppositeEventName " +
        "oppositeDeliveredAtMillis=$oppositeDeliveredAtMillis " +
        "deliveryGapMillis=$deliveryGapMillis " +
        "source=$source reason=$reason"
}

internal fun smartCallbackDeliveryPath(source: String): String = when {
    source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_FALLBACK) ||
        source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_FALLBACK) -> "fallback"
    source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM) ||
        source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM) -> "confirmed"
    else -> "smart"
}

internal fun smartCallbackTimestampSource(eventAtMillis: Long?): String =
    if (eventAtMillis != null && eventAtMillis > 0L) "os_delivery" else "enqueue_time"

internal fun triggerToDeliveryLatencyMillis(
    enqueuedAtMillis: Long,
    eventAtMillis: Long?,
): Long? = eventAtMillis
    ?.takeIf { it > 0L }
    ?.let { (enqueuedAtMillis - it).coerceAtLeast(0L) }
