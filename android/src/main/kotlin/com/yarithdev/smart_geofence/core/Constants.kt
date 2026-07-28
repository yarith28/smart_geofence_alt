package com.yarithdev.smart_geofence.core

import com.google.android.gms.location.Priority
import com.yarithdev.smart_geofence.confirm.TeleportGuard

object Constants {
    const val METHOD_CHANNEL_NAME = "smart_geofence"

    const val PREFS_NAME = "smart_geofence"

    const val CONFIG_BATTERY_MODE = "config_battery_mode"
    const val CONFIG_PROXIMITY_RADIUS_METERS = "config_proximity_radius_meters"
    const val CONFIG_ESCALATION_ENABLED = "config_escalation_enabled"
    const val CONFIG_PROXIMITY_LOCATION_PRIORITY = "config_proximity_location_priority"
    const val CONFIG_PROXIMITY_INTERVAL_MILLIS = "config_proximity_interval_millis"
    const val CONFIG_PROXIMITY_FASTEST_INTERVAL_MILLIS =
        "config_proximity_fastest_interval_millis"
    const val CONFIG_PROXIMITY_MAX_WAIT_MILLIS = "config_proximity_max_wait_millis"
    const val CONFIG_PROXIMITY_MIN_DISPLACEMENT_METERS =
        "config_proximity_min_displacement_meters"
    const val CONFIG_PROXIMITY_ADAPTIVE_DISPLACEMENT_ENABLED =
        "config_proximity_adaptive_displacement_enabled"
    const val CONFIG_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISTANCE_METERS =
        "config_proximity_adaptive_near_boundary_distance_meters"
    const val CONFIG_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISPLACEMENT_METERS =
        "config_proximity_adaptive_near_boundary_displacement_meters"
    const val CONFIG_PROXIMITY_ADAPTIVE_STATIONARY_DISPLACEMENT_METERS =
        "config_proximity_adaptive_stationary_displacement_meters"
    const val CONFIG_PROXIMITY_ADAPTIVE_HYSTERESIS_METERS =
        "config_proximity_adaptive_hysteresis_meters"
    const val CONFIG_PASSIVE_LOCATION_PRIORITY = "config_passive_location_priority"
    const val CONFIG_PASSIVE_LOCATION_INTERVAL_MILLIS =
        "config_passive_location_interval_millis"
    const val CONFIG_PASSIVE_LOCATION_FASTEST_INTERVAL_MILLIS =
        "config_passive_location_fastest_interval_millis"
    const val CONFIG_PASSIVE_LOCATION_MAX_WAIT_MILLIS =
        "config_passive_location_max_wait_millis"
    const val CONFIG_PASSIVE_FOLLOW_UP_ENABLED = "config_passive_follow_up_enabled"
    const val LEGACY_CONFIG_PASSIVE_AMBIGUOUS_CONFIRM_ENABLED =
        "config_passive_ambiguous_confirm_enabled"
    const val CONFIG_LOCATION_CONFIRM_TIMEOUT_MILLIS = "config_gps_confirm_timeout_millis"
    const val CONFIG_PULSE_LOCATION_MAX_ACCURACY_METERS =
        "config_pulse_location_max_accuracy_meters"
    const val CONFIG_EVENT_LOCATION_MAX_ACCURACY_METERS =
        "config_event_location_max_accuracy_meters"
    const val CONFIG_INSIDE_EVENT_LOCATION_MAX_ACCURACY_METERS =
        "config_inside_event_location_max_accuracy_meters"
    const val CONFIG_NATIVE_EXIT_CONFIRMATION_ENABLED =
        "config_native_exit_confirmation_enabled"
    const val CONFIG_NATIVE_ENTER_CONFIRMATION_ENABLED =
        "config_native_enter_confirmation_enabled"
    const val CONFIG_NATIVE_CONFIRM_MAX_ATTEMPTS =
        "config_native_confirm_max_attempts"
    const val CONFIG_NATIVE_ENTER_CONFIRM_RADIUS_SLACK_METERS =
        "config_native_enter_confirm_radius_slack_meters"
    const val CONFIG_NATIVE_ENTER_PAYLOAD_SANITY_ENABLED =
        "config_native_enter_payload_sanity_enabled"
    const val CONFIG_NATIVE_ENTER_PAYLOAD_DISTANCE_SLACK_METERS =
        "config_native_enter_payload_distance_slack_meters"
    const val LEGACY_CONFIG_BOUNDARY_MIN_MARGIN_METERS = "config_boundary_min_margin_meters"
    const val LEGACY_CONFIG_BOUNDARY_MARGIN_RATIO = "config_boundary_margin_ratio"
    const val LEGACY_CONFIG_BOUNDARY_MAX_MARGIN_RATIO = "config_boundary_max_margin_ratio"
    const val CONFIG_TELEPORT_GUARD_ENABLED = "config_teleport_guard_enabled"
    const val CONFIG_TELEPORT_MAX_SPEED_MPS = "config_teleport_max_speed_mps"
    const val CONFIG_MOCK_LOCATION_POLICY = "config_mock_location_policy"
    const val CONFIG_PROXIMITY_PULSE_ENABLED = "config_proximity_pulse_enabled"
    const val CONFIG_PROXIMITY_PULSE_DURATION_MINUTES = "config_proximity_pulse_duration_minutes"
    const val CONFIG_PROXIMITY_PULSE_INTERVAL_SECONDS = "config_proximity_pulse_interval_seconds"
    const val CONFIG_PROXIMITY_PULSE_HIGH_RATE_INTERVAL_MILLIS =
        "config_proximity_pulse_high_rate_interval_millis"
    const val CONFIG_PROXIMITY_PULSE_HIGH_RATE_DISTANCE_METERS =
        "config_proximity_pulse_high_rate_distance_meters"
    const val CONFIG_PROXIMITY_PULSE_ACTIVE_START_MINUTE_OF_DAY =
        "config_proximity_pulse_active_start_minute_of_day"
    const val CONFIG_PROXIMITY_PULSE_ACTIVE_END_MINUTE_OF_DAY =
        "config_proximity_pulse_active_end_minute_of_day"
    const val CONFIG_PROXIMITY_PULSE_MIN_INTERVAL_MILLIS =
        "config_proximity_pulse_min_interval_millis"
    const val CONFIG_PROXIMITY_PULSE_MAX_IDLE_TICKS = "config_proximity_pulse_max_idle_ticks"
    const val CONFIG_PROXIMITY_PULSE_FIRST_TICK_DELAY_MILLIS =
        "config_proximity_pulse_exact_alarm_start_delay_millis"
    const val CONFIG_PROXIMITY_PULSE_ACTIVATION_DISTANCE_METERS =
        "config_proximity_pulse_activation_distance_meters"
    const val CONFIG_PROXIMITY_PULSE_NEAR_FENCE_DISTANCE_METERS =
        "config_proximity_pulse_near_fence_distance_meters"
    const val CONFIG_PROXIMITY_PULSE_NEAR_FENCE_INTERVAL_MILLIS =
        "config_proximity_pulse_near_fence_interval_millis"
    const val CONFIG_PROXIMITY_PULSE_TRANSITION_CONFIRMATION_INTERVAL_MILLIS =
        "config_proximity_pulse_transition_confirmation_interval_millis"
    const val CONFIG_PROXIMITY_PULSE_TRANSITION_CONFIRMATION_BURST_DURATION_MILLIS =
        "config_proximity_pulse_transition_confirmation_burst_duration_millis"
    const val CONFIG_FOREGROUND_NOTIFICATION_TITLE =
        "config_proximity_pulse_notification_title"
    const val CONFIG_FOREGROUND_NOTIFICATION_CHANNEL_ID =
        "config_proximity_pulse_notification_channel_id"
    const val CONFIG_FOREGROUND_NOTIFICATION_CHANNEL_NAME =
        "config_proximity_pulse_notification_channel_name"
    const val CONFIG_FOREGROUND_NOTIFICATION_ID = "config_proximity_pulse_notification_id"
    const val CONFIG_FOREGROUND_NOTIFICATION_SMALL_ICON_RESOURCE_NAME =
        "config_proximity_pulse_notification_small_icon_resource_name"
    const val CONFIG_FOREGROUND_NOTIFICATION_STICKY =
        "config_foreground_notification_sticky"
    const val CONFIG_FOREGROUND_NOTIFICATION_TAP_ACTION =
        "config_foreground_notification_tap_action"
    const val LEGACY_CONFIG_FOREGROUND_NOTIFICATION_BEHAVIOR =
        "config_foreground_notification_behavior"
    const val LEGACY_CONFIG_FOREGROUND_NOTIFICATION_ONGOING =
        "config_foreground_notification_ongoing"
    const val LEGACY_CONFIG_FOREGROUND_NOTIFICATION_AUTO_CANCEL =
        "config_foreground_notification_auto_cancel"
    const val LEGACY_CONFIG_FOREGROUND_NOTIFICATION_REMOVE_WHEN_IDLE =
        "config_foreground_notification_remove_when_idle"
    const val CONFIG_FOREGROUND_NOTIFICATION_SHOW_WHILE_MONITORING =
        "config_foreground_notification_show_while_monitoring"
    const val CONFIG_ACTIVITY_STATIONARY_TTL_MILLIS = "config_activity_stationary_ttl_millis"
    const val CONFIG_ACTIVITY_PERIODIC_BACKSTOP_ENABLED =
        "config_activity_periodic_backstop_enabled"
    const val CONFIG_ACTIVITY_UPDATE_INTERVAL_MILLIS =
        "config_activity_update_interval_millis"
    const val CONFIG_ACTIVITY_MOVING_PROXIMITY_CHECK_DELAY_MILLIS =
        "config_activity_moving_proximity_check_delay_millis"
    const val CONFIG_ACTIVITY_FUSED_LOCATION_STALE_AFTER_MILLIS =
        "config_activity_fused_location_stale_after_millis"
    const val CONFIG_RECOVERY_TIMES_MINUTE_OF_DAY =
        "config_recovery_times_minute_of_day"
    const val CONFIG_RECOVERY_ALARM_POLICY = "config_recovery_alarm_policy"
    const val CONFIG_RECOVERY_INEXACT_GUARD_DELAY_MILLIS =
        "config_recovery_inexact_guard_delay_millis"
    const val CONFIG_EXACT_ALARM_PERMISSION_MODE =
        "config_exact_alarm_permission_mode"
    const val CONFIG_LOG_FILE_ENABLED = "config_log_file_enabled"
    const val CONFIG_LOG_FILE_VERBOSE = "config_log_file_verbose"
    const val CONFIG_MAX_LOG_FILE_BYTES = "config_max_log_file_bytes"
    const val CONFIG_RETRY_ON_CALLBACK_FAILURE = "config_retry_on_callback_failure"
    const val CONFIG_PASSIVE_LOCATION_ENABLED = "config_passive_location_enabled"
    const val CONFIG_FOREGROUND_SERVICE_LAUNCH_TIMEOUT_MILLIS =
        "config_foreground_service_launch_timeout_millis"
    const val CONFIG_FOREGROUND_SERVICE_START_DELAY_MILLIS =
        "config_foreground_service_start_delay_millis"
    const val CONFIG_FOREGROUND_SERVICE_REARM_DELAY_MILLIS =
        "config_foreground_service_rearm_delay_millis"
    const val CONFIG_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS =
        "config_foreground_service_callback_timeout_millis"
    const val CONFIG_FOREGROUND_SERVICE_STICKY =
        "config_foreground_service_sticky"
    const val CONFIG_CONFIRM_QUEUE_MAX_AGE_MILLIS =
        "config_confirm_queue_max_age_millis"
    const val CONFIG_TIME_INTEGRITY_ENABLED =
        "config_time_integrity_enabled"
    const val CONFIG_TIME_INTEGRITY_CONFIG_JSON =
        "config_time_integrity_config_json"

    const val LEGACY_CONFIG_TIME_INTEGRITY_STRICTNESS =
        "config_time_integrity_strictness"
    const val LEGACY_CONFIG_TIME_INTEGRITY_EVENT_TRUST_POLICY_JSON =
        "config_time_integrity_event_trust_policy_json"

    const val MIN_ANDROID_GEOFENCE_RADIUS_METERS = 100.0
    const val DEFAULT_PROXIMITY_RADIUS_METERS = 1000.0
    const val DEFAULT_LOCATION_PRIORITY_HIGH_ACCURACY = "highAccuracy"
    const val DEFAULT_LOCATION_PRIORITY_BALANCED_POWER_ACCURACY = "balancedPowerAccuracy"
    const val DEFAULT_LOCATION_PRIORITY_LOW_POWER = "lowPower"
    const val DEFAULT_LOCATION_PRIORITY_PASSIVE = "passive"

    fun toAndroidLocationPriority(name: String): Int =
        when (name) {
            DEFAULT_LOCATION_PRIORITY_HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
            DEFAULT_LOCATION_PRIORITY_LOW_POWER -> Priority.PRIORITY_LOW_POWER
            DEFAULT_LOCATION_PRIORITY_PASSIVE -> Priority.PRIORITY_PASSIVE
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

    const val BATTERY_MODE_BALANCED = "balanced"
    const val BATTERY_MODE_HIGH_ACCURACY = "highAccuracy"
    const val BATTERY_MODE_LOW_POWER = "lowPower"
    const val DEFAULT_PROXIMITY_INTERVAL_MILLIS = 2 * 60 * 1000L
    const val DEFAULT_PROXIMITY_FASTEST_INTERVAL_MILLIS = 1 * 60 * 1000L
    const val DEFAULT_PROXIMITY_MAX_WAIT_MILLIS = 5 * 60 * 1000L
    const val DEFAULT_PROXIMITY_MIN_DISPLACEMENT_METERS = 200.0
    const val DEFAULT_PROXIMITY_ADAPTIVE_DISPLACEMENT_ENABLED = true
    const val DEFAULT_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISTANCE_METERS = 300.0
    const val DEFAULT_PROXIMITY_ADAPTIVE_NEAR_BOUNDARY_DISPLACEMENT_METERS = 75.0
    const val DEFAULT_PROXIMITY_ADAPTIVE_STATIONARY_DISPLACEMENT_METERS = 500.0
    const val DEFAULT_PROXIMITY_ADAPTIVE_HYSTERESIS_METERS = 100.0
    const val DEFAULT_PASSIVE_LOCATION_INTERVAL_MILLIS = 20 * 60 * 1000L
    const val DEFAULT_PASSIVE_LOCATION_FASTEST_INTERVAL_MILLIS = 5 * 60 * 1000L
    const val DEFAULT_PASSIVE_LOCATION_MAX_WAIT_MILLIS = 40 * 60 * 1000L
    const val PASSIVE_LOCATION_MAX_EVIDENCE_AGE_MILLIS = 10 * 60 * 1000L
    const val DEFAULT_PASSIVE_FOLLOW_UP_ENABLED = true
    const val DEFAULT_LOCATION_CONFIRM_TIMEOUT_MILLIS = 8 * 1000L
    const val PROXIMITY_CONFIRM_TIMEOUT_MILLIS = 2 * 1000L
    const val DEFAULT_PROXIMITY_CONFIRM_MAX_ATTEMPTS = 10
    const val DEFAULT_LAST_LOCATION_TIMEOUT_MILLIS = 5 * 1000L
    const val DEFAULT_PULSE_LOCATION_MAX_ACCURACY_METERS = 300.0
    const val DEFAULT_EVENT_LOCATION_MAX_ACCURACY_METERS = 150.0
    const val DEFAULT_INSIDE_EVENT_LOCATION_MAX_ACCURACY_METERS = 300.0
    const val DEFAULT_NATIVE_EXIT_CONFIRMATION_ENABLED = true
    const val DEFAULT_NATIVE_ENTER_CONFIRMATION_ENABLED = true
    const val DEFAULT_NATIVE_CONFIRM_MAX_ATTEMPTS = 3
    const val DEFAULT_NATIVE_CONFIRM_DELAY_MILLIS = 2 * 60 * 1000L
    const val DEFAULT_TRANSITION_VALIDATION_ENABLED = true
    const val DEFAULT_TRANSITION_VALIDATION_ENTER_ENABLED = true
    const val DEFAULT_TRANSITION_VALIDATION_EXIT_ENABLED = true
    const val DEFAULT_TRANSITION_VALIDATION_MINIMUM_DELAY_MILLIS = 2 * 60 * 1000L
    const val DEFAULT_NATIVE_ENTER_CONFIRM_RADIUS_SLACK_METERS = 300.0
    const val DEFAULT_NATIVE_ENTER_PAYLOAD_SANITY_ENABLED = true
    const val DEFAULT_NATIVE_ENTER_PAYLOAD_DISTANCE_SLACK_METERS = 1000.0
    const val DEFAULT_TELEPORT_GUARD_ENABLED = true
    const val DEFAULT_TELEPORT_MAX_SPEED_MPS = TeleportGuard.MAX_SPEED_MPS
    const val DEFAULT_PROXIMITY_PULSE_ENABLED = true
    const val DEFAULT_PROXIMITY_PULSE_INTERVAL_SECONDS = 6 * 60L
    const val DEFAULT_PROXIMITY_PULSE_ACTIVATION_DISTANCE_METERS = 1_500.0
    const val DEFAULT_PROXIMITY_PULSE_NEAR_FENCE_DISTANCE_METERS = 300.0
    const val DEFAULT_PROXIMITY_PULSE_NEAR_FENCE_INTERVAL_MILLIS = 150_000L
    const val DEFAULT_PROXIMITY_PULSE_TRANSITION_CONFIRMATION_INTERVAL_MILLIS = 90_000L
    const val DEFAULT_PROXIMITY_PULSE_TRANSITION_CONFIRMATION_BURST_DURATION_MILLIS =
        5 * 60_000L
    const val DEFAULT_PROXIMITY_PULSE_ACTIVE_START_MINUTE_OF_DAY = 0
    const val DEFAULT_PROXIMITY_PULSE_ACTIVE_END_MINUTE_OF_DAY = 0
    const val DEFAULT_PROXIMITY_PULSE_OUTSIDE_ACTIVE_HOURS_INTERVAL_MULTIPLIER = 2
    const val DEFAULT_PROXIMITY_PULSE_MIN_INTERVAL_MILLIS = 30 * 1000L
    const val DEFAULT_FOREGROUND_NOTIFICATION_TITLE = "Checking nearby geofence"
    const val DEFAULT_FOREGROUND_NOTIFICATION_CHANNEL_ID = "smart_geofence_foreground"
    const val DEFAULT_FOREGROUND_NOTIFICATION_CHANNEL_NAME = "Geofence monitoring"
    const val DEFAULT_FOREGROUND_NOTIFICATION_ID = 393_939
    const val DEFAULT_FOREGROUND_NOTIFICATION_SHOW_WHILE_MONITORING = false
    const val DEFAULT_ACTIVITY_STATIONARY_TTL_MILLIS = 15 * 60 * 1000L
    const val DEFAULT_ACTIVITY_PERIODIC_BACKSTOP_ENABLED = false
    const val DEFAULT_ACTIVITY_UPDATE_INTERVAL_MILLIS = 5 * 60 * 1000L
    const val DEFAULT_ACTIVITY_BOOTSTRAP_TIMEOUT_MILLIS = 60 * 1000L
    const val DEFAULT_ACTIVITY_MOVING_PROXIMITY_CHECK_DELAY_MILLIS = 60 * 1000L
    const val DEFAULT_ACTIVITY_FUSED_LOCATION_STALE_AFTER_MILLIS = 10 * 60 * 1000L
    const val DEFAULT_RECOVERY_TIME_MINUTE_OF_DAY = 2 * 60
    const val DEFAULT_PASSIVE_LOCATION_ENABLED = true
    const val DEFAULT_FOREGROUND_SERVICE_LAUNCH_TIMEOUT_MILLIS = 10 * 1000L
    const val DEFAULT_FOREGROUND_SERVICE_START_DELAY_MILLIS = 1_000L
    const val DEFAULT_FOREGROUND_SERVICE_REARM_DELAY_MILLIS = 250L
    const val MIN_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS = 30 * 1000L
    const val DEFAULT_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS = 2 * 60 * 1000L
    const val MAX_FOREGROUND_SERVICE_CALLBACK_TIMEOUT_MILLIS = 2 * 60 * 1000L
    const val DEFAULT_FOREGROUND_SERVICE_STICKY = true
    const val DEFAULT_CONFIRM_QUEUE_MAX_AGE_MILLIS = 7 * 60 * 1000L
    const val DEFAULT_CONFIRM_MAX_TRANSIENT_FAILURES = 5
    const val DEFAULT_CONFIRM_RETRY_MAX_DELAY_MILLIS = 15 * 60 * 1000L
    const val DEFAULT_TIME_INTEGRITY_ENABLED = false

    const val DEFAULT_TIME_INTEGRITY_CONFIG_JSON = "{}"
    const val CONFIRM_PARKED_REASON_LOCATION_DISABLED = "location_services_disabled"
    const val CONFIRM_PARKED_REASON_TRANSIENT_FAILURES_EXHAUSTED =
        "transient_failures_exhausted"
    const val DEFAULT_LOG_FILE_VERBOSE = false
    const val DEFAULT_RETRY_ON_CALLBACK_FAILURE = false
    const val DEFAULT_LOG_FILE_MAX_BYTES = 5 * 1024 * 1024
    const val MIN_LOG_FILE_MAX_BYTES = 16 * 1024
    const val MAX_LOG_FILE_MAX_BYTES = 50 * 1024 * 1024

    const val LOG_FILE_NAME = "smart_geofence.log"

    const val EXTRA_LOCATION_WAKE_SOURCE = "smart_geofence.location_wake_source"
    const val PENDING_INTENT_DATA_PROXIMITY = "smart-geofence://proximity"
    const val PENDING_INTENT_DATA_PASSIVE_LOCATION = "smart-geofence://passive-location"
    const val LOCATION_WAKE_SOURCE_PROXIMITY = "proximity"
    const val LOCATION_WAKE_SOURCE_PASSIVE = "passive"
    const val LOCATION_WAKE_SOURCE_ACTIVITY = "activity"
    const val LOCATION_WAKE_SOURCE_DORMANT_PROBE = "dormant_probe"

    const val EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY = "smart_geofence_proximity"
    const val EVENT_SOURCE_SMART_GEOFENCE_PASSIVE = "smart_geofence_passive"
    const val EVENT_SOURCE_SMART_GEOFENCE_ACTIVITY = "smart_geofence_activity"
    const val EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM = "smart_geofence_proximity_pulse_confirm"
    const val EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS = "smart_geofence_fused_liveness"
    const val EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM =
        "smart_geofence_native_exit_confirm"
    const val EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_FALLBACK =
        "smart_geofence_native_exit_fallback"
    const val EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM =
        "smart_geofence_native_enter_confirm"
    const val EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_FALLBACK =
        "smart_geofence_native_enter_fallback"

    const val PENDING_INTENT_REQUEST_BASE = 9100
    const val PENDING_INTENT_REQUEST_PASSIVE_LOCATION = 9101
    const val PENDING_INTENT_REQUEST_PROXIMITY_ALARM = -393_939
    const val PENDING_INTENT_REQUEST_RECOVERY_ALARM = 9103
    const val PENDING_INTENT_REQUEST_CONFIRM_LAUNCH = 9104
    const val PENDING_INTENT_REQUEST_CONFIRM_WATCHDOG = 9105
    const val PENDING_INTENT_REQUEST_FGS_BATCH_LAUNCH = 9106
    const val PENDING_INTENT_REQUEST_ACTIVITY_TRANSITION = 9107
    const val PENDING_INTENT_REQUEST_ACTIVITY_UPDATE = 9108
    const val PENDING_INTENT_REQUEST_ACTIVITY_BOOTSTRAP_TIMEOUT = 9118
    const val PENDING_INTENT_REQUEST_CONFIRM_REARM = 9110
    const val PENDING_INTENT_REQUEST_BOOT_FOLLOW_UP = 9111
    const val PENDING_INTENT_REQUEST_RECOVERY_ALARM_GUARD = 9112
    const val PENDING_INTENT_REQUEST_NOTIFICATION_DISMISS = 9113
    const val PENDING_INTENT_REQUEST_DORMANT_PROBE = 9114
    const val PENDING_INTENT_REQUEST_NATIVE_EXIT_FALLBACK = 9115
    const val PENDING_INTENT_REQUEST_NATIVE_ENTER_FALLBACK = 9116
    const val PENDING_INTENT_REQUEST_LOCATION_DISABLED_RECOVERY = 9117
    const val PENDING_INTENT_REQUEST_TERMINAL_CLEANUP_RETRY = 9119

    const val ACTION_FOREGROUND_NOTIFICATION_DISMISS =
        "com.yarithdev.smart_geofence.action.FOREGROUND_NOTIFICATION_DISMISS"
    const val ACTION_DORMANT_FAR_PROBE =
        "com.yarithdev.smart_geofence.action.DORMANT_FAR_PROBE"
    const val ACTION_NATIVE_EXIT_FALLBACK =
        "com.yarithdev.smart_geofence.action.NATIVE_EXIT_FALLBACK"
    const val ACTION_NATIVE_ENTER_FALLBACK =
        "com.yarithdev.smart_geofence.action.NATIVE_ENTER_FALLBACK"
}
