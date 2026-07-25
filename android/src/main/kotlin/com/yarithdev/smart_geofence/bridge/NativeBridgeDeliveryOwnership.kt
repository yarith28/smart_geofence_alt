package com.yarithdev.smart_geofence.bridge

import android.os.SystemClock
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceBridgeDecision
import java.util.concurrent.atomic.AtomicReference

internal enum class NativeBridgeOwnershipState {
    SMART_PENDING,
    SMART_COMMITTED,
    NATIVE_FALLBACK,
}

internal class NativeBridgeDeliveryOwnership private constructor(
    private val deadlineAtElapsedMillis: Long?,
    private val elapsedRealtimeMillis: () -> Long,
    initialState: NativeBridgeOwnershipState,
) {
    private val state = AtomicReference(initialState)

    @Volatile
    private var plannedSmartDecision: NativeGeofenceBridgeDecision? = null

    fun prepareSmartDecision(decision: NativeGeofenceBridgeDecision) {
        plannedSmartDecision = decision
    }

    fun canContinueSmart(): Boolean {
        return when (state.get()) {
            NativeBridgeOwnershipState.SMART_COMMITTED -> true
            NativeBridgeOwnershipState.NATIVE_FALLBACK -> false
            NativeBridgeOwnershipState.SMART_PENDING -> {
                if (deadlineExpired()) {
                    yieldToNativeFallback()
                    false
                } else {
                    true
                }
            }
        }
    }

    fun tryCommitSmart(): Boolean {
        while (true) {
            when (state.get()) {
                NativeBridgeOwnershipState.SMART_COMMITTED -> return true
                NativeBridgeOwnershipState.NATIVE_FALLBACK -> return false
                NativeBridgeOwnershipState.SMART_PENDING -> {
                    if (deadlineExpired()) {
                        yieldToNativeFallback()
                        return false
                    }
                    if (state.compareAndSet(
                            NativeBridgeOwnershipState.SMART_PENDING,
                            NativeBridgeOwnershipState.SMART_COMMITTED,
                        )
                    ) {
                        return true
                    }
                }
            }
        }
    }

    fun yieldToNativeFallback(): Boolean = state.compareAndSet(
        NativeBridgeOwnershipState.SMART_PENDING,
        NativeBridgeOwnershipState.NATIVE_FALLBACK,
    )

    fun committedSmartDecision(): NativeGeofenceBridgeDecision? =
        plannedSmartDecision.takeIf {
            state.get() == NativeBridgeOwnershipState.SMART_COMMITTED
        }

    internal fun currentState(): NativeBridgeOwnershipState = state.get()

    private fun deadlineExpired(): Boolean = deadlineAtElapsedMillis?.let { deadline ->
        elapsedRealtimeMillis() >= deadline
    } ?: false

    companion object {
        fun bounded(
            timeoutMillis: Long,
            elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
        ): NativeBridgeDeliveryOwnership {
            val startedAt = elapsedRealtimeMillis()
            val boundedTimeout = timeoutMillis.coerceAtLeast(0L)
            val deadlineAt = if (boundedTimeout > Long.MAX_VALUE - startedAt) {
                Long.MAX_VALUE
            } else {
                startedAt + boundedTimeout
            }
            return NativeBridgeDeliveryOwnership(
                deadlineAtElapsedMillis = deadlineAt,
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                initialState = NativeBridgeOwnershipState.SMART_PENDING,
            )
        }

        fun unrestricted(): NativeBridgeDeliveryOwnership =
            NativeBridgeDeliveryOwnership(
                deadlineAtElapsedMillis = null,
                elapsedRealtimeMillis = { 0L },
                initialState = NativeBridgeOwnershipState.SMART_COMMITTED,
            )
    }
}
