package com.yarithdev.smart_geofence.monitoring

internal typealias MonitoringStoppedListener = (MonitoringStoppedEvent) -> Unit

internal object MonitoringStoppedCallbackNotifier {
    private val lock = Object()
    private val listeners = linkedMapOf<String, MonitoringStoppedListener>()
    private val dispatchedEventIds = linkedSetOf<String>()

    fun addListener(ownerId: String, listener: MonitoringStoppedListener) {
        synchronized(lock) {
            listeners[ownerId] = listener
        }
    }

    fun removeListener(ownerId: String) {
        synchronized(lock) {
            listeners.remove(ownerId)
        }
    }

    fun dispatchOnce(event: MonitoringStoppedEvent): Boolean {
        val targets = synchronized(lock) {
            if (event.eventId in dispatchedEventIds || listeners.isEmpty()) return false
            dispatchedEventIds += event.eventId
            listeners.values.toList()
        }
        targets.forEach { listener -> runCatching { listener(event) } }
        return true
    }
}
