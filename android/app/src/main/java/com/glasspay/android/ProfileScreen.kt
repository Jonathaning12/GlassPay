package com.glasspay.android

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * Perfil del cobrador: accesos a Cobrar, Enviar y Mi QR, ajustes de la cuenta y seguridad.
 * Solo muestra datos que el servidor ya entrega (nombre y correo para recibir envíos).
 */
@Composable
fun ProfileScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onBack: () -> Unit,
    onCharge: () -> Unit,
    onSend: () -> Unit,
    onOpenMyQr: () -> Unit,
    onPayoutEmail: () -> Unit,
    onOpenScheduled: () -> Unit,
    amountsVisible: Boolean,
    onToggleAmounts: () -> Unit,
    lockAvailability: DeviceAuthAvailability,
) {
    var profile by remember { mutableStateOf<LoadState<CollectorProfile>>(LoadState.Loading) }
    var revealed by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    // Se vuelve a cargar cada vez que se entra (por ejemplo, al volver de "Recibir envíos").
    LaunchedEffect(Unit) {
        revealed = true
        profile = try {
            LoadState.Loaded(api.getCollectorProfile(session))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "profile failure type=${exception.type} httpCode=${exception.httpCode}")
            if (isSessionInvalid(exception.type)) onSessionInvalid(SESSION_INVALID_MESSAGE)
            LoadState.Failed
        } catch (_: Exception) {
            Log.i("GlassPay", "profile failure type=UNKNOWN httpCode=none")
            LoadState.Failed
        }
    }

    val payoutDescription = when (val current = profile) {
        LoadState.Loading -> "Cargando…"
        LoadState.Failed -> "Correo de PayPal Sandbox para recibir envíos"
        is LoadState.Loaded -> current.value.payoutEmail?.let { "Recibes en $it" }
            ?: "Registra tu correo de PayPal Sandbox"
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Mi perfil", onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Reveal(revealed, index = 0) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Avatar(session.displayName, size = 80.dp, highlighted = true)
                    Text(
                        session.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = GlassPayColors.Text,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text("Cuenta de cobrador · Sandbox", style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
                }
            }
            Reveal(revealed, index = 1) {
                ProfileSection("Mover dinero") {
                    ProfileRow(R.drawable.ic_charge, "Cobrar", "Escanea el QR de un amigo y dicta el cobro", onCharge, highlighted = true)
                    RowDivider()
                    ProfileRow(R.drawable.ic_send, "Enviar", "Envía dinero a uno de tus contactos", onSend)
                    RowDivider()
                    ProfileRow(R.drawable.ic_qr, "Mi QR", "Muéstralo para que te cobren o te agreguen", onOpenMyQr)
                }
            }
            Reveal(revealed, index = 2) {
                ProfileSection("Cuenta") {
                    ProfileRow(R.drawable.ic_mail, "Recibir envíos", payoutDescription, onPayoutEmail)
                    RowDivider()
                    ProfileRow(R.drawable.ic_clock, "Programados", "Cobros y pagos que programaste con el asistente", onOpenScheduled)
                }
            }
            Reveal(revealed, index = 3) {
                ProfileSection("Seguridad y privacidad") {
                    ProfileSwitchRow(
                        title = "Mostrar montos",
                        description = "Se ocultan de nuevo al salir de la app",
                        checked = amountsVisible,
                        onToggle = onToggleAmounts,
                    )
                    RowDivider()
                    val lockOn = lockAvailability == DeviceAuthAvailability.AVAILABLE
                    ProfileInfoRow(
                        icon = R.drawable.ic_lock,
                        title = "Bloqueo con tu teléfono",
                        description = if (lockOn) {
                            "Se pide al abrir, tras 5 min fuera y antes de enviar dinero"
                        } else {
                            "Configura un bloqueo de pantalla en tu teléfono para proteger GlassPay"
                        },
                        status = if (lockOn) "Activo" else "Inactivo",
                        statusKind = if (lockOn) StatusKind.SUCCESS else StatusKind.PENDING,
                    )
                }
            }
            Reveal(revealed, index = 4) {
                InlineNotice("Actividad en GlassPay (Sandbox). No es una cuenta bancaria.", icon = R.drawable.ic_lock)
            }
        }
    }
}

/** Entrada escalonada y discreta: aparece y sube 12 dp en 220 ms. */
@Composable
private fun Reveal(visible: Boolean, index: Int, content: @Composable () -> Unit) {
    val animate = animationsEnabled()
    val progress by animateFloatAsState(
        targetValue = if (visible || !animate) 1f else 0f,
        animationSpec = tween(durationMillis = if (animate) 220 else 0, delayMillis = if (animate) index * 40 else 0, easing = FastOutSlowInEasing),
        label = "profileReveal",
    )
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 12.dp.toPx()
        },
    ) { content() }
}

@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Overline(title, modifier = Modifier.padding(start = 4.dp))
        GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp), spacing = 0.dp, content = content)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = GlassPayColors.Line, modifier = Modifier.padding(start = 72.dp))
}

@Composable
private fun ProfileRow(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = GlassPayColors.Text,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon,
                tint = GlassPayColors.Accent,
                size = 40.dp,
                background = if (highlighted) GlassPayColors.AccentSoft else GlassPayColors.Raised,
            )
            RowTexts(title, description, Modifier.weight(1f))
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = GlassPayColors.MutedText,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ProfileSwitchRow(title: String, description: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EyeBadge(checked)
        RowTexts(title, description, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GlassPayColors.OnAccent,
                checkedTrackColor = GlassPayColors.Accent,
                uncheckedTrackColor = GlassPayColors.Card,
            ),
        )
    }
}

/** Fila informativa (sin acción): no tiene chevron ni efecto al tocar. */
@Composable
private fun ProfileInfoRow(@DrawableRes icon: Int, title: String, description: String, status: String, statusKind: StatusKind) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint = statusColor(statusKind), size = 40.dp, background = statusSoftColor(statusKind))
        RowTexts(title, description, Modifier.weight(1f))
        StatusPill(status, statusKind)
    }
}

@Composable
private fun RowTexts(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
        Text(description, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
    }
}

/** Ícono del ojo dentro de la fila "Mostrar montos"; decorativo, la fila ya describe el estado. */
@Composable
private fun EyeBadge(open: Boolean) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(40.dp).background(GlassPayColors.Raised, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        EyeGlyph(open = open, modifier = Modifier.size(22.dp))
    }
}
