package com.yarithdev.smart_geofence.confirm

import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.transition.NativeTransitionDirection

internal fun nativeTransitionOwnershipForSource(
    source: String,
    direction: NativeTransitionDirection,
    instances: Map<String, String>?,
): Map<String, String>? {
    val nativeSource = when (direction) {
        NativeTransitionDirection.ENTER ->
            Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM
        NativeTransitionDirection.EXIT ->
            Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM
    }
    if (!source.startsWith(nativeSource)) return null
    val snapshot = instances.orEmpty()
    return snapshot.takeIf { ownership ->
        ownership.isNotEmpty() && ownership.all { (fenceId, instanceId) ->
            fenceId.isNotBlank() && instanceId.isNotBlank()
        }
    }?.toMap().orEmpty()
}

internal fun ownsNativeTransitionInstance(
    ownership: Map<String, String>?,
    fenceId: String,
    currentInstanceId: String?,
): Boolean {
    if (ownership == null) return true
    val expectedInstanceId = ownership[fenceId]
        ?.takeIf { fenceId.isNotBlank() && it.isNotBlank() }
        ?: return false
    return currentInstanceId == expectedInstanceId
}
