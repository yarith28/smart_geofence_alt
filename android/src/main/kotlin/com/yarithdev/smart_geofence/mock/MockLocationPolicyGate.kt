package com.yarithdev.smart_geofence.mock

import android.content.Context
import android.location.Location
import androidx.core.location.LocationCompat
import com.yarithdev.smart_geofence.config.MockLocationPolicy
import com.yarithdev.smart_geofence.config.SmartGeofenceConfig
import com.yarithdev.smart_geofence.logging.SmartGeofenceDiagnostics
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger

data class MockLocationPolicyDecision(
    val isMock: Boolean,
    val rejected: Boolean,
    val action: String?,
)

object MockLocationPolicyGate {
    private const val TAG = "MockLocationPolicy"

    fun evaluatePolicy(
        policy: MockLocationPolicy,
        isMock: Boolean,
        rejectable: Boolean = true,
    ): MockLocationPolicyDecision {
        if (!isMock) return MockLocationPolicyDecision(isMock = false, rejected = false, action = null)
        return when (policy) {
            MockLocationPolicy.Allow ->
                MockLocationPolicyDecision(isMock = true, rejected = false, action = null)
            MockLocationPolicy.LogOnly ->
                MockLocationPolicyDecision(
                    isMock = true,
                    rejected = false,
                    action = "mock_location_allowed",
                )
            MockLocationPolicy.Reject -> {
                if (rejectable) {
                    MockLocationPolicyDecision(
                        isMock = true,
                        rejected = true,
                        action = "mock_location_rejected",
                    )
                } else {
                    MockLocationPolicyDecision(
                        isMock = true,
                        rejected = false,
                        action = "mock_location_rejected_unowned",
                    )
                }
            }
        }
    }

    fun evaluateLocation(
        context: Context,
        config: SmartGeofenceConfig,
        location: Location,
        source: String,
        where: String,
        rejectable: Boolean = true,
    ): MockLocationPolicyDecision {
        return evaluate(
            context = context,
            policy = config.mockLocationPolicy,
            isMock = isMockLocation(location),
            source = source,
            where = where,
            provider = location.provider,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            ageMillis = ageMillis(location),
            rejectable = rejectable,
        )
    }

    fun evaluateNativePayload(
        context: Context,
        config: SmartGeofenceConfig,
        isMock: Boolean,
        source: String,
        where: String,
        accuracyMeters: Double?,
        rejectable: Boolean,
    ): MockLocationPolicyDecision {
        return evaluate(
            context = context,
            policy = config.mockLocationPolicy,
            isMock = isMock,
            source = source,
            where = where,
            provider = "native_geofence",
            accuracyMeters = accuracyMeters,
            ageMillis = null,
            rejectable = rejectable,
        )
    }

    fun isMockLocation(location: Location): Boolean =
        LocationCompat.isMock(location)

    private fun evaluate(
        context: Context,
        policy: MockLocationPolicy,
        isMock: Boolean,
        source: String,
        where: String,
        provider: String?,
        accuracyMeters: Double?,
        ageMillis: Long?,
        rejectable: Boolean,
    ): MockLocationPolicyDecision {
        val decision = evaluatePolicy(policy, isMock, rejectable)
        val action = decision.action ?: return decision
        SmartGeofenceDiagnostics.recordMockLocationPolicy(
            context,
            policy = policy.configValue,
            action = action,
            source = source,
            provider = provider,
            accuracyMeters = accuracyMeters,
            ageMillis = ageMillis,
            suppressed = decision.rejected,
        )
        val detail = "where=$where source=$source policy=${policy.configValue} " +
            "provider=${provider ?: "unknown"} accuracy=${accuracyMeters ?: "unknown"} " +
            "age=${ageMillis ?: "unknown"} suppressed=${decision.rejected}."
        if (decision.rejected || action == "mock_location_rejected_unowned") {
            SmartGeofenceLogger.w(context, TAG, "$action $detail")
        } else {
            SmartGeofenceLogger.d(context, TAG, "$action $detail")
        }
        return decision
    }

    private fun ageMillis(location: Location): Long? =
        if (location.time > 0L) {
            (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        } else {
            null
        }
}
