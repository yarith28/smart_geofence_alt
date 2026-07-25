package com.yarithdev.smart_geofence.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class TerminalCleanupRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val attempt = intent.getIntExtra(TerminalCleanupRetryScheduler.EXTRA_ATTEMPT, 0)
        val completedSynchronously = runCatching {
            TerminalCleanupRetryScheduler.handleAlarm(
                context.applicationContext,
                attempt,
            )
        }.getOrElse {
            TerminalCleanupRetryScheduler.ensureScheduled(
                context.applicationContext,
                reason = "alarm_handler_failed",
                attempt = if (attempt == Int.MAX_VALUE) Int.MAX_VALUE else attempt + 1,
            )
            false
        }
        if (completedSynchronously) {
            pending.finish()
        } else {
            Handler(Looper.getMainLooper()).postDelayed(
                pending::finish,
                RECEIVER_LIFETIME_MILLIS,
            )
        }
    }

    private companion object {
        const val RECEIVER_LIFETIME_MILLIS = 9_000L
    }
}
