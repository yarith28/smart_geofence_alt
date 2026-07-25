package com.yarithdev.smart_geofence.confirm

import android.content.BroadcastReceiver
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import java.util.concurrent.atomic.AtomicBoolean

internal class FallbackReceiverLifetime(
    context: Context,
    private val pendingResult: BroadcastReceiver.PendingResult,
    private val tag: String,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private val timeout = Runnable {
        if (finished.compareAndSet(false, true)) {
            SmartGeofenceLogger.w(
                appContext,
                tag,
                "Fallback receiver timed out waiting for callback enqueue; releasing broadcast.",
            )
            pendingResult.finish()
        }
    }

    init {
        handler.postDelayed(timeout, 9_000L)
    }

    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        pendingResult.finish()
    }
}
