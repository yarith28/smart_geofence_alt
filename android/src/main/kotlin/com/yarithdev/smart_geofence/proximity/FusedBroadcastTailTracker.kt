package com.yarithdev.smart_geofence.proximity

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

internal interface FusedBroadcastTailScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
}

private class HandlerFusedBroadcastTailScheduler(
    private val handler: Handler,
) : FusedBroadcastTailScheduler {
    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        handler.postDelayed(runnable, delayMillis)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

class FusedBroadcastTailTracker internal constructor(
    private val finish: () -> Unit,
    private val scheduler: FusedBroadcastTailScheduler =
        HandlerFusedBroadcastTailScheduler(Handler(Looper.getMainLooper())),
    private val settleDelayMillis: Long = SETTLE_DELAY_MILLIS,
    timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    interface Tail {
        fun complete()
    }

    private val lock = Object()
    private val timeoutRunnable = Runnable { finishNow() }
    private val settleRunnable = Runnable { finishNow() }
    private var evaluationComplete = false
    private var pendingTails = 0
    private var settleScheduled = false
    private var finished = false

    init {
        scheduler.postDelayed(timeoutRunnable, timeoutMillis)
    }

    fun registerTail(): Tail {
        var cancelSettle = false
        synchronized(lock) {
            if (finished) return NOOP_TAIL
            pendingTails += 1
            if (settleScheduled) {
                settleScheduled = false
                cancelSettle = true
            }
        }
        if (cancelSettle) scheduler.removeCallbacks(settleRunnable)
        return object : Tail {
            private val done = AtomicBoolean(false)

            override fun complete() {
                if (done.compareAndSet(false, true)) {
                    completeTail()
                }
            }
        }
    }

    fun markEvaluationComplete() {
        if (markEvaluationCompleteLocked()) {
            scheduler.postDelayed(settleRunnable, settleDelayMillis)
        }
    }

    private fun markEvaluationCompleteLocked(): Boolean =
        synchronized(lock) {
            if (finished) {
                false
            } else {
                evaluationComplete = true
                scheduleSettleIfReadyLocked()
            }
        }

    private fun completeTail() {
        if (completeTailLocked()) {
            scheduler.postDelayed(settleRunnable, settleDelayMillis)
        }
    }

    private fun completeTailLocked(): Boolean =
        synchronized(lock) {
            if (finished) {
                false
            } else {
                if (pendingTails > 0) pendingTails -= 1
                scheduleSettleIfReadyLocked()
            }
        }

    private fun scheduleSettleIfReadyLocked(): Boolean {
        if (!evaluationComplete || pendingTails > 0 || settleScheduled) return false
        settleScheduled = true
        return true
    }

    private fun finishNow() {
        val shouldFinish = synchronized(lock) {
            if (finished) {
                false
            } else {
                finished = true
                true
            }
        }
        if (!shouldFinish) return
        scheduler.removeCallbacks(timeoutRunnable)
        scheduler.removeCallbacks(settleRunnable)
        finish()
    }

    companion object {
        const val SETTLE_DELAY_MILLIS = 500L
        const val TIMEOUT_MILLIS = 5_000L

        private val NOOP_TAIL = object : Tail {
            override fun complete() = Unit
        }
    }
}
