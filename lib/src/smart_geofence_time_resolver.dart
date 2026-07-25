import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:native_geofence/native_geofence.dart' as ng;
import 'package:time_integrity/time_integrity.dart';

import 'smart_geofence_callback_params.dart';
import 'smart_geofence_config.dart';
import 'smart_geofence_time_integrity_logger.dart';

const MethodChannel _channel = MethodChannel('smart_geofence');
const String _eventTimeUnavailable = 'EVENT_TIME_UNAVAILABLE';
const String _bootSessionUncorroborated = 'BOOT_SESSION_UNCORROBORATED';
const String _eventTimeInconsistent = 'EVENT_TIME_INCONSISTENT';
const String _anchorInvalidated = 'ANCHOR_INVALIDATED';

@visibleForTesting
Future<TimeIntegrityConfig?> Function()? debugTimeIntegrityConfigLoaderOverride;

@visibleForTesting
Future<SGNativeEventTiming?> Function(
  ng.GeofenceCallbackParams params,
  List<ng.ActiveGeofence> geofences,
)?
debugNativeEventTimingLookupOverride;

@visibleForTesting
Future<ClockAnchor?> Function()? debugClockAnchorLoaderOverride;

@visibleForTesting
Future<int?> Function()? debugMonotonicWatermarkLoaderOverride;

@visibleForTesting
Future<NativeTimeSnapshot?> Function()? debugNativeSnapshotLoaderOverride;

@visibleForTesting
void resetSmartGeofenceTimeResolverOverrides() {
  debugTimeIntegrityConfigLoaderOverride = null;
  debugNativeEventTimingLookupOverride = null;
  debugClockAnchorLoaderOverride = null;
  debugMonotonicWatermarkLoaderOverride = null;
  debugNativeSnapshotLoaderOverride = null;
}

class SGNativeEventTiming {
  const SGNativeEventTiming({
    this.wallClockEventAt,
    this.eventMonotonicMs,
    this.androidBootCount,
    this.timestampOrigin,
  });

  final DateTime? wallClockEventAt;
  final int? eventMonotonicMs;
  final int? androidBootCount;
  final String? timestampOrigin;

  BootState? get eventBootState {
    final monotonicMs = eventMonotonicMs;
    final wallClockMs = wallClockEventAt?.millisecondsSinceEpoch;
    if (monotonicMs == null) return null;
    return BootState(
      platform: 'android',
      nativeAvailable: true,
      bootCount: androidBootCount,
      systemUptimeMs: monotonicMs,
      estimatedBootWallClockMs: wallClockMs == null
          ? null
          : wallClockMs - monotonicMs,
    );
  }

  factory SGNativeEventTiming.fromMap(Map<Object?, Object?> map) {
    final wallClockMillis = _intFromObject(map['wallClockEventAtMillis']);
    return SGNativeEventTiming(
      wallClockEventAt: wallClockMillis == null
          ? null
          : DateTime.fromMillisecondsSinceEpoch(wallClockMillis, isUtc: true),
      eventMonotonicMs: _intFromObject(map['eventMonotonicMillis']),
      androidBootCount: _intFromObject(map['androidBootCount']),
      timestampOrigin: map['timestampOrigin'] as String?,
    );
  }
}

Future<SmartGeofenceCallbackParams> resolveSmartGeofenceCallbackParams({
  required ng.GeofenceCallbackParams nativeParams,
  required List<ng.ActiveGeofence> geofences,
}) async {
  TimeIntegrityConfig? config;
  Object? configError;
  try {
    config = await _loadTimeIntegrityConfig();
  } catch (error) {
    configError = error;
  }
  SGNativeEventTiming? timing;
  SGEventTimeResolution resolution;
  Object? resolutionError;
  try {
    timing = config != null
        ? await _lookupNativeEventTiming(nativeParams, geofences)
        : null;
    resolution = await _resolveEventTime(
      nativeParams: nativeParams,
      timing: timing,
      config: config,
    );
  } catch (error) {
    resolutionError = error;
    resolution = _wallClockResolution(
      wallClockEventAt: timing?.wallClockEventAt ?? nativeParams.eventAt,
      reasonCode: null,
      rejectionReason: SGEventTimeRejectionReason.evaluationFailed,
    );
  }
  final eventAt = _effectiveEventAt(resolution, nativeParams.eventAt);
  if (configError != null) {
    await logSmartGeofenceTimeIntegrity(
      level: SGTimeIntegrityLogLevel.warning,
      stage: 'callback.config',
      message:
          'time-integrity config unavailable; using device wall-clock event time',
      extras: <String, Object?>{
        'event': nativeParams.event.name,
        'fenceIds': geofences.map((geofence) => geofence.id).toList(),
        'error': configError.toString(),
      },
    );
  } else if (resolutionError != null) {
    await logSmartGeofenceTimeIntegrity(
      level: SGTimeIntegrityLogLevel.warning,
      stage: 'callback.resolve',
      message:
          'time-integrity evaluation failed; using device wall-clock event time',
      extras: <String, Object?>{
        'event': nativeParams.event.name,
        'fenceIds': geofences.map((geofence) => geofence.id).toList(),
        'error': resolutionError.toString(),
      },
    );
  } else if (config != null) {
    await _logCallbackTimeResolution(
      nativeParams: nativeParams,
      geofences: geofences,
      timing: timing,
      resolution: resolution,
      effectiveEventAt: eventAt,
    );
  }
  return SmartGeofenceCallbackParams(
    geofences: geofences,
    event: nativeParams.event,
    location: nativeParams.location,
    eventAt: eventAt,
    eventId: nativeParams.eventId,
    traceId: nativeParams.traceId,
    callbackContextsByGeofenceId: {
      for (final geofence in geofences)
        geofence.id: ?nativeParams.callbackContextsByGeofenceId[geofence.id],
    },
    timeResolution: resolution,
  );
}

Future<void> _logCallbackTimeResolution({
  required ng.GeofenceCallbackParams nativeParams,
  required List<ng.ActiveGeofence> geofences,
  required SGNativeEventTiming? timing,
  required SGEventTimeResolution resolution,
  required DateTime? effectiveEventAt,
}) async {
  await logSmartGeofenceTimeIntegrity(
    level: resolution.isTrusted
        ? SGTimeIntegrityLogLevel.debug
        : SGTimeIntegrityLogLevel.warning,
    stage: 'callback.resolve',
    message: resolution.isTrusted
        ? 'callback event time resolved from trusted time'
        : 'callback event time fell back to the device wall clock',
    extras: <String, Object?>{
      'event': nativeParams.event.name,
      'fenceIds': geofences.map((geofence) => geofence.id).toList(),
      'source': resolution.source.name,
      'trusted': resolution.isTrusted,
      'reasonCode': resolution.reasonCode,
      'rejectionReason': resolution.rejectionReason,
      'evidenceQuality': resolution.evidence?.quality.name,
      'effectiveEventAtMs': effectiveEventAt?.toUtc().millisecondsSinceEpoch,
      'trustedEventAtMs': resolution.trustedEventAt
          ?.toUtc()
          .millisecondsSinceEpoch,
      'wallClockEventAtMs': resolution.wallClockEventAt
          ?.toUtc()
          .millisecondsSinceEpoch,
      'nativeEventAtMs': nativeParams.eventAt?.toUtc().millisecondsSinceEpoch,
      'eventMonotonicMs': timing?.eventMonotonicMs,
      'eventBootCount': timing?.androidBootCount,
      'timestampOrigin': timing?.timestampOrigin,
    },
  );
}

DateTime? _effectiveEventAt(
  SGEventTimeResolution resolution,
  DateTime? nativeEventAt,
) => switch (resolution.source) {
  SGEventTimeSource.trustedTime => resolution.trustedEventAt,
  SGEventTimeSource.deviceWallClock =>
    resolution.wallClockEventAt ?? nativeEventAt,
};

Future<SGEventTimeResolution> _resolveEventTime({
  required ng.GeofenceCallbackParams nativeParams,
  required SGNativeEventTiming? timing,
  required TimeIntegrityConfig? config,
}) async {
  final wallClockEventAt = timing?.wallClockEventAt ?? nativeParams.eventAt;
  if (config == null) {
    return SGEventTimeResolution(
      source: SGEventTimeSource.deviceWallClock,
      wallClockEventAt: wallClockEventAt,
    );
  }

  if (wallClockEventAt == null) {
    return _wallClockResolution(
      wallClockEventAt: null,
      reasonCode: _eventTimeUnavailable,
      rejectionReason: SGEventTimeRejectionReason.evidenceNotStrong,
    );
  }

  final anchorLoad = await _loadClockState();
  if (anchorLoad.storageUnavailable) {
    return _wallClockResolution(
      wallClockEventAt: wallClockEventAt,
      reasonCode: ClockReasonCode.storageUnavailable,
      rejectionReason: SGEventTimeRejectionReason.evidenceNotStrong,
    );
  }

  final currentSnapshot = await _readNativeSnapshotOrNull();

  final evidence = TimeIntegrityEvaluator.evaluateEvent(
    anchor: anchorLoad.anchor,
    config: config,
    deviceEventAtUtcMs: wallClockEventAt.toUtc().millisecondsSinceEpoch,
    eventMonotonicMs: timing?.eventMonotonicMs,
    eventBootState: timing?.eventBootState,
    currentSnapshot: currentSnapshot,
    highestSeenMonotonicMs: anchorLoad.monotonicWatermarkMs,
    source: 'smart_geofence:${nativeParams.event.name}',
  );

  final rejectionReason = _rejectEventEvidence(
    evidence,
    config,
    timing: timing,
    currentSnapshot: currentSnapshot,
  );
  if (rejectionReason == null) {
    return SGEventTimeResolution(
      source: SGEventTimeSource.trustedTime,
      trustedEventAt: evidence.eventAt,
      wallClockEventAt: wallClockEventAt,
      reasonCode: evidence.reasonCode,
      evidence: evidence,
    );
  }

  return _wallClockResolution(
    wallClockEventAt: wallClockEventAt,
    reasonCode: evidence.reasonCode,
    rejectionReason: rejectionReason,
    evidence: evidence,
  );
}

String? _rejectEventEvidence(
  TimeEvidence evidence,
  TimeIntegrityConfig config, {
  required SGNativeEventTiming? timing,
  required NativeTimeSnapshot? currentSnapshot,
}) {
  switch (evidence.reasonCode) {
    case _bootSessionUncorroborated:
      return SGEventTimeRejectionReason.bootSessionUncorroborated;
    case _eventTimeInconsistent:
    case 'EVENT_TIME_INVALID':
      return SGEventTimeRejectionReason.eventTimeInconsistent;
    case _anchorInvalidated:
      return SGEventTimeRejectionReason.anchorInvalidated;
  }
  if (evidence.rebootDetected) {
    return SGEventTimeRejectionReason.rebootDetected;
  }
  if (evidence.quality.name == 'uncorroborated') {
    return SGEventTimeRejectionReason.bootSessionUncorroborated;
  }
  final uncertaintyMs = evidence.uncertaintyMs;
  if (uncertaintyMs != null &&
      (uncertaintyMs < 0 ||
          uncertaintyMs > config.maxSyncUncertainty.inMilliseconds)) {
    return SGEventTimeRejectionReason.syncUncertaintyUnacceptable;
  }
  if (!evidence.isStrong || evidence.eventAtUtcMs == null) {
    return SGEventTimeRejectionReason.evidenceNotStrong;
  }

  if (!evidence.bootSessionCorroborated) {
    return SGEventTimeRejectionReason.bootSessionUncorroborated;
  }

  if (uncertaintyMs == null) {
    return SGEventTimeRejectionReason.syncUncertaintyUnacceptable;
  }

  final eventMonotonicMs = timing?.eventMonotonicMs;
  if (eventMonotonicMs != null &&
      currentSnapshot != null &&
      eventMonotonicMs > currentSnapshot.monotonicMs) {
    return SGEventTimeRejectionReason.eventTimeInconsistent;
  }

  final anchorAgeMs = evidence.anchorAgeMs;
  if (anchorAgeMs == null ||
      anchorAgeMs.abs() > config.maxOfflineAnchorAge.inMilliseconds) {
    return SGEventTimeRejectionReason.anchorNotFresh;
  }

  return null;
}

SGEventTimeResolution _wallClockResolution({
  required DateTime? wallClockEventAt,
  required String? reasonCode,
  required String rejectionReason,
  TimeEvidence? evidence,
}) => SGEventTimeResolution(
  source: SGEventTimeSource.deviceWallClock,
  wallClockEventAt: wallClockEventAt,
  reasonCode: reasonCode,
  rejectionReason: rejectionReason,
  evidence: evidence,
);

Future<TimeIntegrityConfig?> _loadTimeIntegrityConfig() async {
  final override = debugTimeIntegrityConfigLoaderOverride;
  if (override != null) return override();
  final raw = await _channel.invokeMethod<Object?>('getTimeIntegrityConfig');
  if (raw is! Map) {
    throw StateError(
      'smart_geofence time-integrity configuration is unavailable or malformed.',
    );
  }
  final map = raw.cast<Object?, Object?>();
  final enabled = map['timeIntegrityEnabled'];
  if (enabled is! bool) {
    throw StateError(
      'smart_geofence time-integrity configuration is unavailable or malformed.',
    );
  }
  if (!enabled) return null;
  final configJson = map['timeIntegrityConfigJson'];
  if (configJson is! String) {
    throw StateError(
      'smart_geofence time-integrity configuration is unavailable or malformed.',
    );
  }
  try {
    final decoded = jsonDecode(configJson);
    if (decoded is! Map) {
      throw const FormatException('Expected a JSON object.');
    }
    final config = TimeIntegrityConfig.fromJson(
      decoded.cast<String, dynamic>(),
    );
    SGConfig(timeIntegrity: config).validate();
    return config;
  } catch (error) {
    throw StateError(
      'smart_geofence time-integrity configuration is malformed: $error',
    );
  }
}

Future<SGNativeEventTiming?> _lookupNativeEventTiming(
  ng.GeofenceCallbackParams params,
  List<ng.ActiveGeofence> geofences,
) async {
  final override = debugNativeEventTimingLookupOverride;
  if (override != null) return override(params, geofences);
  final wallClockMillis = params.eventAt?.millisecondsSinceEpoch;
  if (geofences.isEmpty || wallClockMillis == null) return null;
  try {
    final raw = await _channel.invokeMethod<Object?>('getEventTiming', {
      'fenceId': geofences.first.id,
      'event': params.event.name,
      'eventAtMillis': wallClockMillis,
    });
    if (raw is Map) {
      return SGNativeEventTiming.fromMap(raw.cast<Object?, Object?>());
    }
  } catch (_) {}
  return null;
}

Future<_AnchorLoad> _loadClockState() async {
  final anchorOverride = debugClockAnchorLoaderOverride;
  final watermarkOverride = debugMonotonicWatermarkLoaderOverride;
  try {
    if (anchorOverride != null || watermarkOverride != null) {
      final anchorFuture = anchorOverride?.call() ?? Future.value(null);
      final watermarkFuture = watermarkOverride?.call() ?? Future.value(null);
      return _AnchorLoad(
        await anchorFuture,
        monotonicWatermarkMs: await watermarkFuture,
      );
    }
    final state = await SecureClockAnchorStorage().loadClockState();
    return _AnchorLoad(
      state.anchor,
      monotonicWatermarkMs: state.monotonicWatermarkMs,
    );
  } on ClockStorageUnavailableException {
    return const _AnchorLoad.unavailable();
  } catch (_) {
    return const _AnchorLoad.unavailable();
  }
}

Future<NativeTimeSnapshot?> _readNativeSnapshotOrNull() async {
  final override = debugNativeSnapshotLoaderOverride;
  if (override != null) return override();
  try {
    return await MethodChannelNativeClock().readSnapshot();
  } catch (_) {
    return null;
  }
}

int? _intFromObject(Object? value) => switch (value) {
  int() => value,
  _ => null,
};

class _AnchorLoad {
  const _AnchorLoad(this.anchor, {required this.monotonicWatermarkMs})
    : storageUnavailable = false;
  const _AnchorLoad.unavailable()
    : anchor = null,
      monotonicWatermarkMs = null,
      storageUnavailable = true;

  final ClockAnchor? anchor;
  final int? monotonicWatermarkMs;
  final bool storageUnavailable;
}
