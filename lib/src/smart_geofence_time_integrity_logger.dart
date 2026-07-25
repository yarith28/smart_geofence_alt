import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

const MethodChannel _channel = MethodChannel('smart_geofence');

enum SGTimeIntegrityLogLevel { debug, info, warning, error }

Future<void> logSmartGeofenceTimeIntegrity({
  required SGTimeIntegrityLogLevel level,
  required String stage,
  required String message,
  Map<String, Object?> extras = const <String, Object?>{},
}) async {
  if (kIsWeb) return;
  try {
    await _channel.invokeMethod<void>('logTimeIntegrity', <String, Object?>{
      'level': level.name,
      'stage': stage,
      'message': message,
      if (extras.isNotEmpty) 'extras': _normalizeExtras(extras),
    });
  } catch (_) {}
}

Map<String, Object?> _normalizeExtras(Map<String, Object?> extras) {
  final normalized = <String, Object?>{};
  for (final entry in extras.entries) {
    normalized[entry.key] = _normalizeValue(entry.value);
  }
  return normalized;
}

Object? _normalizeValue(Object? value) {
  if (value == null || value is String || value is num || value is bool) {
    return value;
  }
  if (value is DateTime) return value.toUtc().millisecondsSinceEpoch;
  if (value is Iterable) {
    return value.map((item) => item?.toString()).toList(growable: false);
  }
  return value.toString();
}
