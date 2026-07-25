package com.yarithdev.smart_geofence.foreground

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yarithdev.smart_geofence.confirm.LocationConfirmManager
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

data class ForegroundServiceDrainResult(
    val queued: List<QueuedForegroundStart>,
    val accepted: List<QueuedForegroundStart>,
    val rejected: List<QueuedForegroundStart>,
) {
    val hadWork: Boolean
        get() = queued.isNotEmpty()

    val allAccepted: Boolean
        get() = rejected.isEmpty()
}

class ForegroundServiceLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        drain(context.applicationContext)
    }

    companion object {
        private const val TAG = "ForegroundServiceLaunchReceiver"
        private val drainLock = Any()

        fun drain(context: Context): ForegroundServiceDrainResult =
            synchronized(drainLock) {
                drainSingleBatch(context.applicationContext)
            }

        private fun drainSingleBatch(appContext: Context): ForegroundServiceDrainResult {
            val queued = ForegroundStartCoordinator.drainQueuedStarts(appContext)
            if (queued.isEmpty()) {
                return ForegroundServiceDrainResult(
                    queued = emptyList(),
                    accepted = emptyList(),
                    rejected = emptyList(),
                )
            }

            val acceptedRequests = mutableListOf<QueuedForegroundStart>()
            val rejectedRequests = mutableListOf<QueuedForegroundStart>()
            for (request in queued) {
                val accepted = when (request.serviceKey) {
                    ForegroundLaunchState.SERVICE_LOCATION_CONFIRM ->
                        LocationConfirmManager.requestStart(
                            appContext,
                            attempt = request.attempt,
                            reason = "batch ${request.reason}",
                            launchToken = request.launchToken,
                        )
                    else -> {
                        SmartGeofenceLogger.w(
                            appContext,
                            TAG,
                            "Unknown foreground-service launch key=${request.serviceKey}; dropping."
                        )
                        false
                    }
                }
                if (accepted) {
                    acceptedRequests.add(request)
                } else {
                    rejectedRequests.add(request)
                    ForegroundStartCoordinator.markBatchServiceFinished(
                        appContext,
                        request.serviceKey,
                        "start_not_accepted",
                        launchToken = request.launchToken,
                        attempt = request.attempt,
                    )
                }
            }
            return ForegroundServiceDrainResult(
                queued = queued,
                accepted = acceptedRequests,
                rejected = rejectedRequests,
            )
        }
    }
}
