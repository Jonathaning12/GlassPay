package com.glasspay.android

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

/**
 * Lentes simulados (Mock Device Kit) para probar Cobrar sin lentes: el video sale de la cámara trasera
 * del teléfono. Solo existe en la versión de depuración y vive en memoria: al cerrar la app desaparece.
 */
object MockGlasses {
    const val AVAILABLE = true

    fun isEnabled(context: Context): Boolean = MockDeviceKit.getInstance(context.applicationContext).isEnabled

    /** Devuelve un mensaje de error o nulo si los lentes simulados quedaron listos. */
    fun enable(context: Context): String? {
        val kit = MockDeviceKit.getInstance(context.applicationContext)
        // Registrados y con permisos concedidos: se prueba la sesión y el video, no el paso por Meta AI.
        kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
        var error: String? = null
        kit.pairGlasses(GlassesModel.OAKLEY_META_HSTN)
            .onSuccess { glasses ->
                glasses.powerOn()
                glasses.unfold()
                glasses.don()
                glasses.services.camera.setCameraFeed(CameraFacing.BACK)
            }
            .onFailure { failure, _ ->
                Log.i("GlassPay", "mock glasses failure ${failure.description}")
                error = "No se pudieron crear los lentes simulados."
                kit.disable()
            }
        return error
    }

    fun disable(context: Context) {
        MockDeviceKit.getInstance(context.applicationContext).disable()
    }
}
