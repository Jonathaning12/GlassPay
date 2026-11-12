package com.glasspay.android

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.core.types.RegistrationState

// Conecta GlassPay con los lentes por medio de Meta AI. El cobro con los lentes se hace en Cobrar.

@Composable
fun GlassesSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(GlassesSupport.initializeIfAllowed(context)) }
    var permissionDenied by remember { mutableStateOf(false) }
    // Se crea de nuevo cuando el SDK queda listo: antes no puede observar el registro.
    val glasses = remember(ready) { GlassesCamera() }
    DisposableEffect(glasses) { onDispose { glasses.tearDown() } }

    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) ready = GlassesSupport.initializeIfAllowed(context)
    }

    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(L("Lentes"), onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GpCard(modifier = Modifier.fillMaxWidth(), spacing = 12.dp) {
                IconBadge(R.drawable.ic_glasses, tint = GlassPayColors.Accent, size = 56.dp, background = GlassPayColors.AccentSoft)
                Text(
                    if (glasses.isRegistered) L("Lentes conectados") else L("Conecta tus lentes"),
                    color = GlassPayColors.Text,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(glassesDetail(ready, glasses), color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodyLarge)
            }
            if (permissionDenied) {
                InlineNotice(L("Sin el permiso de dispositivos cercanos no podemos hablar con tus lentes."), kind = StatusKind.FAILURE)
            }
            glasses.errorMessage?.let { InlineNotice(it, kind = StatusKind.FAILURE) }
            when {
                !ready -> GpButton(
                    L("Permitir Bluetooth"),
                    onClick = { bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    icon = R.drawable.ic_glasses,
                    modifier = Modifier.fillMaxWidth(),
                )
                glasses.isRegistered -> GpButton(
                    L("Desconectar lentes"),
                    onClick = { context.findActivity()?.let(glasses::disconnect) },
                    kind = GpButtonKind.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> GpButton(
                    L("Conectar lentes"),
                    onClick = { context.findActivity()?.let(glasses::connect) },
                    enabled = glasses.registration != RegistrationState.REGISTERING,
                    icon = R.drawable.ic_glasses,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            InlineNotice(L("El video de los lentes solo se usa para leer el QR y no se guarda."), icon = R.drawable.ic_lock)
        }
    }
}

private fun glassesDetail(ready: Boolean, glasses: GlassesCamera): String = when {
    !ready -> L("GlassPay necesita el permiso de dispositivos cercanos para conectarse a tus lentes por Bluetooth.")
    !glasses.isRegistered -> L("Se abrirá Meta AI para autorizar GlassPay. Después podrás cobrar mirando el QR de tu amigo.")
    glasses.hasActiveDevice -> L("Abre Cobrar y mira el QR de tu amigo.")
    else -> L("Póntelos y revisa que estén enlazados para usarlos en Cobrar.")
}
