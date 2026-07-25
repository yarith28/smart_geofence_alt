package com.yarithdev.smart_geofence.delivery

import android.content.Context
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceCallbackDelivery
import com.chunkytofustudios.native_geofence.bridge.NativeGeofenceCallbackEnqueueResult
import com.chunkytofustudios.native_geofence.generated.GeofenceCallbackParamsWire

internal typealias SmartGeofenceCallbackEnqueueHandler =
    (Context, GeofenceCallbackParamsWire, String, (Boolean) -> Unit) -> Unit
internal typealias NativeGeofenceCallbackDeliveryHandler =
    (
        Context,
        GeofenceCallbackParamsWire,
        String,
        (NativeGeofenceCallbackEnqueueResult) -> Unit,
    ) -> Unit

internal object SmartGeofenceCallbackEnqueuer {
    private val defaultNativeDelivery: NativeGeofenceCallbackDeliveryHandler =
        NativeGeofenceCallbackDelivery::enqueueFinalCallback

    @Volatile
    internal var nativeDelivery: NativeGeofenceCallbackDeliveryHandler = defaultNativeDelivery

    private val defaultHandler: SmartGeofenceCallbackEnqueueHandler =
        { context, params, source, onFinished ->
            nativeDelivery(context, params, source) { result ->
                onFinished(result != NativeGeofenceCallbackEnqueueResult.REJECTED)
            }
        }

    @Volatile
    internal var handler: SmartGeofenceCallbackEnqueueHandler = defaultHandler

    internal fun resetForTests() {
        nativeDelivery = defaultNativeDelivery
        handler = defaultHandler
    }

    fun enqueue(
        context: Context,
        params: GeofenceCallbackParamsWire,
        source: String,
        onFinished: (Boolean) -> Unit,
    ) {
        handler(context, params, source, onFinished)
    }
}
