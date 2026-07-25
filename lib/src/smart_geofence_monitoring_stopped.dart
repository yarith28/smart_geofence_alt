enum SGMonitoringStoppedReason {
  fineLocationPermissionDenied,
  backgroundLocationPermissionDenied,
  locationServicesDisabled,
}

class SGMonitoringStoppedEvent {
  const SGMonitoringStoppedEvent({
    required this.eventId,
    required this.reason,
    required this.stoppedAt,
  });

  final String eventId;
  final SGMonitoringStoppedReason reason;
  final DateTime stoppedAt;

  Map<String, Object?> toJson() => {
    'eventId': eventId,
    'reason': reason.name,
    'stoppedAt': stoppedAt.toIso8601String(),
  };

  static SGMonitoringStoppedEvent fromMap(Map<Object?, Object?> map) {
    final eventId = map['eventId'];
    final rawReason = map['reason'];
    final stoppedAtMillis = map['stoppedAtMillis'];
    final reason = SGMonitoringStoppedReason.values
        .where((candidate) => candidate.name == rawReason)
        .firstOrNull;
    if (eventId is! String ||
        eventId.isEmpty ||
        reason == null ||
        stoppedAtMillis is! num ||
        stoppedAtMillis.toInt() <= 0) {
      throw FormatException('Invalid monitoring stopped event: $map');
    }
    return SGMonitoringStoppedEvent(
      eventId: eventId,
      reason: reason,
      stoppedAt: DateTime.fromMillisecondsSinceEpoch(
        stoppedAtMillis.toInt(),
        isUtc: true,
      ),
    );
  }

  @override
  String toString() => 'SGMonitoringStoppedEvent(${toJson()})';
}

typedef SmartGeofenceMonitoringStoppedCallback =
    Future<void> Function(SGMonitoringStoppedEvent event);
