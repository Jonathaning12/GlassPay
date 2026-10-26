package com.glasspay.android

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Datos mostrados en la vista previa, guardados solo en memoria para la espera y el éxito. */
data class ConfirmedCharge(
    val amount: String,
    val currency: String,
    val concept: String,
    val payerName: String,
)

private val StepGray = Color(0xFF3A4260)

@Composable
internal fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
}

@Composable
fun PaymentProgress(
    payment: PaymentTracking,
    confirmed: ConfirmedCharge?,
    onNewCharge: () -> Unit,
    onOpenMovements: () -> Unit,
) {
    when (val step = paymentStep(payment)) {
        PaymentStep.PAID -> PaidView(confirmed, onNewCharge, onOpenMovements)
        PaymentStep.STOPPED -> StoppedView(payment, confirmed, onNewCharge)
        else -> WaitingView(step, payment, confirmed)
    }
}

@Composable
private fun ChargeSummary(confirmed: ConfirmedCharge?) {
    if (confirmed == null) return
    Text(
        formatMoney(confirmed.amount, confirmed.currency),
        style = MaterialTheme.typography.displaySmall,
        color = Color.White,
    )
    Text("para ${confirmed.concept}", color = Color.White, style = MaterialTheme.typography.bodyLarge)
    Text("a ${confirmed.payerName}", color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun WaitingView(step: PaymentStep, payment: PaymentTracking, confirmed: ConfirmedCharge?) {
    val activeIndex = if (step == PaymentStep.CONFIRMING) 2 else 1
    val awaitingDetail = (payment as? PaymentTracking.WaitingWithMessage)?.message ?: "Esperando al pagador."
    val titles = listOf("Solicitud enviada", "El pagador está aprobando", "Confirmando con PayPal")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChargeSummary(confirmed)
            Column(modifier = Modifier.padding(top = 12.dp)) {
                titles.forEachIndexed { index, title ->
                    TimelineStep(
                        title = title,
                        detail = if (index == 1 && activeIndex == 1) awaitingDetail else null,
                        done = index < activeIndex,
                        active = index == activeIndex,
                        last = index == titles.lastIndex,
                        nextDone = index + 1 < activeIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineStep(
    title: String,
    detail: String?,
    done: Boolean,
    active: Boolean,
    last: Boolean,
    nextDone: Boolean,
) {
    val animate = animationsEnabled()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                if (active && animate) {
                    val transition = rememberInfiniteTransition(label = "pulse")
                    val scale by transition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.6f,
                        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
                        label = "pulseScale",
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = (1.6f - scale) / 0.6f * 0.5f
                            }
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            when {
                                done -> GlassPayColors.Success
                                active -> MaterialTheme.colorScheme.primary
                                else -> StepGray
                            },
                            CircleShape,
                        )
                        .semantics {
                            contentDescription = when {
                                done -> "Paso completado"
                                active -> "Paso en curso"
                                else -> "Paso pendiente"
                            }
                        },
                ) {
                    if (done) Text("✓", color = Color(0xFF0B1020), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (!last) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(28.dp)
                        .background(if (done) GlassPayColors.Success else StepGray),
                )
            }
        }
        Column(modifier = Modifier.padding(top = 4.dp, bottom = if (last) 0.dp else 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (done || active) Color.White else GlassPayColors.MutedText,
            )
            detail?.let { Text(it, color = GlassPayColors.Warning) }
        }
    }
}

@Composable
private fun PaidView(confirmed: ConfirmedCharge?, onNewCharge: () -> Unit, onOpenMovements: () -> Unit) {
    val animate = animationsEnabled()
    var entered by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "checkScale",
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(GlassPayColors.Success, CircleShape)
                .semantics { contentDescription = "Pago recibido" },
        ) {
            Text("✓", color = Color(0xFF0B1020), style = MaterialTheme.typography.displayMedium)
        }
        Text("¡Pago recibido!", style = MaterialTheme.typography.headlineLarge, color = GlassPayColors.Success)
        confirmed?.let {
            Text(formatMoney(it.amount, it.currency), style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text("de ${it.payerName}", color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodyLarge)
        }
        Button(onClick = onNewCharge) { Text("Nuevo cobro") }
        OutlinedButton(onClick = onOpenMovements) { Text("Ver en Movimientos") }
    }
}

@Composable
private fun StoppedView(payment: PaymentTracking, confirmed: ConfirmedCharge?, onNewCharge: () -> Unit) {
    val (text, color) = when (payment) {
        PaymentTracking.Rejected -> "El pagador rechazó la solicitud." to GlassPayColors.Error
        PaymentTracking.Expired -> "La solicitud expiró." to GlassPayColors.Warning
        PaymentTracking.Failed -> "El pago falló." to GlassPayColors.Error
        is PaymentTracking.Error -> payment.message to GlassPayColors.Error
        else -> "No pudimos consultar el estado del pago." to GlassPayColors.Error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChargeSummary(confirmed)
            Text(text, color = color, style = MaterialTheme.typography.titleMedium)
        }
    }
    Button(onClick = onNewCharge) { Text("Escanear otro QR") }
}
