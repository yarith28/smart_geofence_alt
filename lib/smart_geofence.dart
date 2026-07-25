library;

export 'package:native_geofence/native_geofence.dart'
    show
        Geofence,
        Location,
        GeofenceEvent,
        ActiveGeofence,
        GeofenceCallback,
        GeofenceCallbackParams,
        IosGeofenceSettings,
        AndroidGeofenceSettings,
        NativeGeofenceStatus,
        NativeGeofenceException;
export 'package:time_integrity/time_integrity.dart'
    show
        ClockHealth,
        ClockReasonCode,
        CurrentTimeGate,
        CurrentTimePolicy,
        SyncResult,
        TimeEvidence,
        TimeEvidenceQuality,
        TimeIntegrityConfig;

export 'src/smart_geofence_callback_params.dart';
export 'src/config/smart_geofence_config_transport.dart'
    show smartGeofenceConfigSchemaVersion, encodeSmartGeofenceConfigTransport;
export 'src/smart_geofence_config.dart';
export 'src/smart_geofence_diagnostics.dart';
export 'src/smart_geofence_readiness.dart';
export 'src/smart_geofence_manager.dart'
    show
        SmartGeofenceManager,
        SmartGeofenceCallback,
        smartGeofenceAndroidMinimumRadiusMeters,
        SmartGeofenceRegistration,
        SGLegacyCallbackContext,
        SGLegacyGeofenceRegistration,
        SGLegacyAdoptionStatus,
        SGLegacyAdoptionItemResult,
        SGLegacyAdoptionResult,
        SGMonitoringInspection,
        SGMonitoringState,
        SGSynchronizationReason,
        SGSynchronizationReport;
export 'src/smart_geofence_monitoring_stopped.dart';
export 'src/smart_geofence_status.dart';
