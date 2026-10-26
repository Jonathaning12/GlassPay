package com.glasspay.android

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.content.Context

/** Autenticación del sistema (huella o bloqueo del teléfono). No guarda ni registra nada. */
object DeviceAuthenticator {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun availability(context: Context): DeviceAuthAvailability {
        val manager = context.getSystemService(BiometricManager::class.java)
            ?: return DeviceAuthAvailability.UNSUPPORTED
        // Requiere USE_BIOMETRIC en el manifiesto; ante cualquier fallo del servicio
        // del sistema se trata como no soportado en lugar de cerrar la app.
        val result = try {
            manager.canAuthenticate(AUTHENTICATORS)
        } catch (_: SecurityException) {
            return DeviceAuthAvailability.UNSUPPORTED
        }
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> DeviceAuthAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> DeviceAuthAvailability.NOT_CONFIGURED
            else -> DeviceAuthAvailability.UNSUPPORTED
        }
    }

    /** Devuelve la señal para cancelar el diálogo, o null si no pudo mostrarse (se llama onFailure). */
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ): CancellationSignal? {
        return try {
            // Sin setNegativeButton: no se permite junto con DEVICE_CREDENTIAL.
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            val signal = CancellationSignal()
            prompt.authenticate(
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        onFailure()
                    }

                    // onAuthenticationFailed (huella no reconocida): el sistema permite reintentar.
                },
            )
            signal
        } catch (_: IllegalArgumentException) {
            onFailure()
            null
        } catch (_: IllegalStateException) {
            onFailure()
            null
        }
    }
}

/** Para confirmar acciones sensibles (p. ej. enviar dinero). */
fun requireDeviceAuth(activity: Activity, onSuccess: () -> Unit, onFailure: () -> Unit = {}) {
    DeviceAuthenticator.authenticate(
        activity,
        "Confirma el envío",
        "Usa tu huella o el bloqueo de tu teléfono",
        onSuccess,
        onFailure,
    )
}
