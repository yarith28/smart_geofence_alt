import 'dart:math' as math;

import 'package:native_geofence/native_geofence.dart' as ng;
import 'package:time_integrity/time_integrity.dart';

const double _earthRadiusMeters = 6371000;

class SGGeofenceDistance {
  const SGGeofenceDistance({
    required this.geofenceId,
    required this.radiusMeters,
    required this.distanceToCenterMeters,
    required this.signedDistanceToBoundaryMeters,
    required this.isInsideByDistance,
    required this.locationAccuracyMeters,
    required this.locationFixTime,
    required this.isMockLocation,
  });

  final String geofenceId;
  final double radiusMeters;
  final double? distanceToCenterMeters;
  final double? signedDistanceToBoundaryMeters;
  final bool? isInsideByDistance;
  final double? locationAccuracyMeters;
  final DateTime? locationFixTime;
  final bool? isMockLocation;

  bool get isAvailable => distanceToCenterMeters != null;

  Map<String, Object?> toJson() => {
    'geofenceId': geofenceId,
    'radiusMeters': radiusMeters,
    'distanceToCenterMeters': distanceToCenterMeters,
    'signedDistanceToBoundaryMeters': signedDistanceToBoundaryMeters,
    'isInsideByDistance': isInsideByDistance,
    'locationAccuracyMeters': locationAccuracyMeters,
    'locationFixTime': locationFixTime?.toIso8601String(),
    'isMockLocation': isMockLocation,
    'isAvailable': isAvailable,
  };
}

enum SGEventTimeSource { trustedTime, deviceWallClock }

class SGEventTimeRejectionReason {
  SGEventTimeRejectionReason._();

  static const String evidenceNotStrong = 'EVIDENCE_NOT_STRONG';

  static const String anchorNotFresh = 'ANCHOR_NOT_FRESH';

  static const String syncUncertaintyUnacceptable =
      'SYNC_UNCERTAINTY_UNACCEPTABLE';

  static const String rebootDetected = 'REBOOT_DETECTED';

  static const String bootSessionUncorroborated = 'BOOT_SESSION_UNCORROBORATED';

  static const String eventTimeInconsistent = 'EVENT_TIME_INCONSISTENT';

  static const String anchorInvalidated = 'ANCHOR_INVALIDATED';

  static const String evaluationFailed = 'TIME_EVALUATION_FAILED';
}

class SGEventTimeResolution {
  const SGEventTimeResolution({
    required this.source,
    this.trustedEventAt,
    this.wallClockEventAt,
    this.reasonCode,
    this.rejectionReason,
    this.evidence,
  });

  final SGEventTimeSource source;

  final DateTime? trustedEventAt;

  final DateTime? wallClockEventAt;

  final String? reasonCode;

  final String? rejectionReason;

  final TimeEvidence? evidence;

  bool get isTrusted => source == SGEventTimeSource.trustedTime;

  Map<String, Object?> toJson() => {
    'timestampSource': source.name,
    'timestampTrusted': isTrusted,
    'trustedEventAt': trustedEventAt?.toIso8601String(),
    'wallClockEventAt': wallClockEventAt?.toIso8601String(),
    'timeReasonCode': reasonCode,
    'timeRejectionReason': rejectionReason,
    'timeEvidence': evidence?.toJson(),
  };

  @override
  String toString() =>
      'SGEventTimeResolution(source: $source, '
      'trustedEventAt: $trustedEventAt, wallClockEventAt: $wallClockEventAt, '
      'reasonCode: $reasonCode, rejectionReason: $rejectionReason, '
      'evidence: $evidence)';
}

class SmartGeofenceCallbackParams extends ng.GeofenceCallbackParams {
  const SmartGeofenceCallbackParams({
    required super.geofences,
    required super.event,
    required super.location,
    required super.eventAt,
    required this.timeResolution,
    super.eventId,
    super.traceId,
    super.callbackContextsByGeofenceId,
  });

  final SGEventTimeResolution timeResolution;

  List<SGGeofenceDistance> get geofenceDistances {
    final sortedGeofences = [...geofences]
      ..sort((left, right) => left.id.compareTo(right.id));
    return List.unmodifiable(
      sortedGeofences.map((geofence) => _distanceFor(geofence, location)),
    );
  }

  SGGeofenceDistance? distanceForGeofence(String geofenceId) {
    for (final distance in geofenceDistances) {
      if (distance.geofenceId == geofenceId) return distance;
    }
    return null;
  }

  Map<String, Object?> toJson() {
    final sortedGeofences = [...geofences]
      ..sort((left, right) => left.id.compareTo(right.id));
    final distances = geofenceDistances;
    return {
      'schema': 2,
      'eventId': eventId,
      'traceId': traceId,
      'event': event.name,
      'eventAt': eventAt?.toIso8601String(),
      'geofenceCount': sortedGeofences.length,
      'geofenceIds': sortedGeofences.map((geofence) => geofence.id).toList(),
      'geofences': sortedGeofences
          .map((geofence) => geofence.toJson())
          .toList(growable: false),
      'location': location?.toJson(),
      'hasLocation': location != null,
      'isMockLocation': location?.isMock,
      'geofenceDistances': distances
          .map((distance) => distance.toJson())
          .toList(growable: false),
      'timeResolution': timeResolution.toJson(),
    };
  }

  @override
  String toString() =>
      'SmartGeofenceCallbackParams('
      'event: ${event.name}, '
      'geofenceCount: ${geofences.length}, '
      'hasLocation: ${location != null}, '
      'hasEventTime: ${eventAt != null}, '
      'timestampSource: ${timeResolution.source.name}, '
      'timestampTrusted: ${timeResolution.isTrusted})';
}

SGGeofenceDistance _distanceFor(
  ng.ActiveGeofence geofence,
  ng.Location? location,
) {
  final hasValidCoordinates =
      location != null &&
      _isValidCoordinate(location.latitude, location.longitude) &&
      _isValidCoordinate(
        geofence.location.latitude,
        geofence.location.longitude,
      ) &&
      geofence.radiusMeters.isFinite &&
      geofence.radiusMeters >= 0;

  double? centerDistance;
  if (hasValidCoordinates) {
    centerDistance = _haversineDistanceMeters(
      location.latitude,
      location.longitude,
      geofence.location.latitude,
      geofence.location.longitude,
    );
  }

  final boundaryDistance = centerDistance == null
      ? null
      : centerDistance - geofence.radiusMeters;
  return SGGeofenceDistance(
    geofenceId: geofence.id,
    radiusMeters: geofence.radiusMeters,
    distanceToCenterMeters: centerDistance,
    signedDistanceToBoundaryMeters: boundaryDistance,
    isInsideByDistance: centerDistance == null
        ? null
        : centerDistance <= geofence.radiusMeters,
    locationAccuracyMeters: location?.accuracyMeters,
    locationFixTime: location?.fixTime,
    isMockLocation: location?.isMock,
  );
}

bool _isValidCoordinate(double latitude, double longitude) =>
    latitude.isFinite &&
    longitude.isFinite &&
    latitude >= -90 &&
    latitude <= 90 &&
    longitude >= -180 &&
    longitude <= 180;

double _haversineDistanceMeters(
  double latitudeA,
  double longitudeA,
  double latitudeB,
  double longitudeB,
) {
  const degreesToRadians = math.pi / 180;
  final latitudeARadians = latitudeA * degreesToRadians;
  final latitudeBRadians = latitudeB * degreesToRadians;
  final latitudeDelta = (latitudeB - latitudeA) * degreesToRadians;
  final longitudeDelta = (longitudeB - longitudeA) * degreesToRadians;
  final latitudeTerm = math.sin(latitudeDelta / 2);
  final longitudeTerm = math.sin(longitudeDelta / 2);
  final haversine =
      latitudeTerm * latitudeTerm +
      math.cos(latitudeARadians) *
          math.cos(latitudeBRadians) *
          longitudeTerm *
          longitudeTerm;
  final clampedHaversine = haversine.clamp(0.0, 1.0);
  final distance =
      2 * _earthRadiusMeters * math.asin(math.sqrt(clampedHaversine));
  return distance.isFinite && distance >= 0 ? distance : 0;
}
