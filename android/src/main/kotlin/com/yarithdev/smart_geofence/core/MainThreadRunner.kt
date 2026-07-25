package com.yarithdev.smart_geofence.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object MainThreadRunner {
    private val handler = Handler(Looper.getMainLooper())

    fun runBlocking(timeoutMillis: Long = 0L, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }

        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val cancelled = AtomicBoolean(false)
        val runnable = Runnable {
            if (cancelled.get()) return@Runnable
            try {
                block()
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                latch.countDown()
            }
        }
        handler.post(runnable)
        val completed = if (timeoutMillis > 0L) {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } else {
            latch.await()
            true
        }
        if (!completed) {
            cancelled.set(true)
            handler.removeCallbacks(runnable)
            throw TimeoutException(
                "Timed out waiting ${timeoutMillis}ms for main-thread recovery work."
            )
        }
        failure.get()?.let { throw it }
    }
}
