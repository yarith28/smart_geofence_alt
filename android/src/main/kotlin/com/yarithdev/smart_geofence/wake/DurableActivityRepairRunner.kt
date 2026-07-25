package com.yarithdev.smart_geofence.wake

import com.yarithdev.smart_geofence.activity.ActivityReconcileResult

internal enum class DurableActivityRepairDisposition {
    REMOVED,
    RETAINED,
    STALE,
}

internal class DurableActivityRepairRunner(
    private val reconcile: ((ActivityReconcileResult) -> Unit) -> Unit,
    private val removeIfUnchanged: () -> Boolean,
    private val deferIfUnchanged: (Long) -> Boolean,
    private val deferReplacementAtLeast: (Long) -> Boolean = { false },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) {
    fun run(onSettled: (DurableActivityRepairDisposition, ActivityReconcileResult) -> Unit) {
        reconcile { result ->
            val disposition = when {
                result.succeeded || result.noLongerDesired ->
                    if (removeIfUnchanged()) {
                        DurableActivityRepairDisposition.REMOVED
                    } else {
                        DurableActivityRepairDisposition.STALE
                    }
                else -> {
                    val retryAt = safeRetryAt(nowMillis(), retryDelayMillis)
                    if (deferIfUnchanged(retryAt) || deferReplacementAtLeast(retryAt)) {
                        DurableActivityRepairDisposition.RETAINED
                    } else {
                        DurableActivityRepairDisposition.STALE
                    }
                }
            }
            onSettled(disposition, result)
        }
    }

    companion object {
        internal const val DEFAULT_RETRY_DELAY_MILLIS = 60_000L

        internal fun safeRetryAt(nowMillis: Long, delayMillis: Long): Long {
            val delay = delayMillis.coerceAtLeast(1L)
            return if (delay > Long.MAX_VALUE - nowMillis) Long.MAX_VALUE else nowMillis + delay
        }
    }
}
