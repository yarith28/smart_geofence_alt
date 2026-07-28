import 'dart:convert';

import '../smart_geofence_config.dart';

const int smartGeofenceConfigSchemaVersion = 7;

final class SmartGeofenceConfigDocument {
  const SmartGeofenceConfigDocument._({
    required this.schemaVersion,
    required this.config,
  });

  factory SmartGeofenceConfigDocument.fromConfig(SGConfig source) {
    final config = Map<String, Object?>.from(source.toMap());

    config
      ..remove('timeIntegrityEnabled')
      ..remove('timeIntegrityConfigJson')
      ..['timeIntegrity'] = source.timeIntegrity?.toJson();

    return SmartGeofenceConfigDocument._(
      schemaVersion: smartGeofenceConfigSchemaVersion,
      config: Map<String, Object?>.unmodifiable(config),
    );
  }

  final int schemaVersion;
  final Map<String, Object?> config;

  Map<String, Object?> toJson() => <String, Object?>{
    'schemaVersion': schemaVersion,
    'config': config,
  };

  String encode() => jsonEncode(toJson());
}

String encodeSmartGeofenceConfigTransport(SGConfig config) =>
    SmartGeofenceConfigDocument.fromConfig(config).encode();
