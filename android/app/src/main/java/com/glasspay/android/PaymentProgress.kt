package com.glasspay.android

import android.provider.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
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

private val StepGray = GlassPayColors.Line

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
    Text("Cobro a ${confirmed.payerName}", color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodySmall)
    MoneyText(formatMoney(confirmed.amount, confirmed.currency), style = MaterialTheme.typography.headlineLarge)
    Text(confirmed.concept, color = GlassPayColors.Text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun WaitingView(step: PaymentStep, payment: PaymentTracking, confirmed: ConfirmedCharge?) {
    val activeIndex = if (step == PaymentStep.CONFIRMING) 2 else 1
    val awaitingDetail = (payment as? PaymentTracking.WaitingWithMessage)?.message ?: "Esperando al pagador."
    val titles = listOf("Solicitud enviada", "El pagador está aprobando", "Confirmando con PayPal")
    val details = listOf(
        "Ya la ve en su teléfono.",
        if (activeIndex == 1) awaitingDetail else "Aprobó en PayPal.",
        "Solo PayPal puede marcarlo como pagado.",
    )
    Text("Esperando el pago", style = MaterialTheme.typography.headlineSmall, color = GlassPayColors.Text)
    if (confirmed != null) {
        GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), spacing = 6.dp) {
            ChargeSummary(confirmed)
        }
    }
    GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), spacing = 0.dp) {
        titles.forEachIndexed { index, title ->
            TimelineStep(
                title = title,
                detail = details[index],
                done = index < activeIndex,
                active = index == activeIndex,
                last = index == titles.lastIndex,
            )
        }
    }
    InlineNotice("Si dejas de esperar, el pagador todavía podrá aprobar desde su teléfono.", icon = R.drawable.ic_lock)
}

@Composable
private fun TimelineStep(
    title: String,
    detail: String?,
    done: Boolean,
    active: Boolean,
    last: Boolean,
) {
    val animate = animationsEnabled()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
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
                            .size(28.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = (1.6f - scale) / 0.6f * 0.5f
                            }
                            .background(GlassPayColors.Accent, CircleShape),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            when {
                                done -> GlassPayColors.SuccessSoft
                                active -> GlassPayColors.AccentSoft
                                else -> GlassPayColors.Background
                            },
                            CircleShape,
                        )
                        .border(
                            2.dp,
                            when {
                                done -> GlassPayColors.SuccessSoft
                                active -> GlassPayColors.Accent.copy(alpha = 0.6f)
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
                    when {
                        done -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = GlassPayColors.Success, modifier = Modifier.size(18.dp))
                        active -> Box(Modifier.size(10.dp).background(GlassPayColors.Accent, CircleShape))
                    }
                }
            }
            if (!last) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(36.dp)
                        .background(if (done) GlassPayColors.Success else StepGray),
                )
            }
        }
        Column(
            modifier = Modifier.padding(top = 3.dp, bottom = if (last) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (done || active) GlassPayColors.Text else GlassPayColors.MutedText,
            )
            detail?.let {
                Text(it, color = if (active) GlassPayColors.Warning else GlassPayColors.MutedText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PaidView(confirmed: ConfirmedCharge?, onNewCharge: () -> Unit, onOpenMovements: () -> Unit) {
    val animate = animationsEnabled()
    var entered by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { entered = true }
    // Celebración poco frecuente: un rebote leve está bien, pero nunca desde escala 0.
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.6f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "checkScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(if (animate) 200 else 0),
        label = "checkAlpha",
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(156.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .background(GlassPayColors.SuccessSoft, CircleShape)
                .semantics { contentDescription = "Pago recibido" },
        ) {
            Box(Modifier.size(104.dp).background(GlassPayColors.Success, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = GlassPayColors.Background, modifier = Modifier.size(56.dp))
            }
        }
        Text("¡Pago recibido!", style = MaterialTheme.typography.headlineMedium, color = GlassPayColors.Success)
        confirmed?.let {
            MoneyText(formatMoney(it.amount, it.currency), style = MaterialTheme.typography.displaySmall)
            Text("De ${it.payerName} · ${it.concept}", color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodyLarge)
        }
        Row(
            modifier = Modifier.background(GlassPayColors.Raised, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = GlassPayColors.MutedText, modifier = Modifier.size(16.dp))
            Text("Confirmado por PayPal (Sandbox)", color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodySmall)
        }
        GpButton("Nuevo cobro", onClick = onNewCharge, icon = R.drawable.ic_charge, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        GpButton("Ver en Movimientos", onClick = onOpenMovements, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StoppedView(payment: PaymentTracking, confirmed: ConfirmedCharge?, onNewCharge: () -> Unit) {
    val (text, kind) = when (payment) {
        PaymentTracking.Rejected -> "El pagador rechazó la solicitud." to StatusKind.FAILURE
        PaymentTracking.Expired -> "La solicitud expiró." to StatusKind.PENDING
        PaymentTracking.Failed -> "El pago falló." to StatusKind.FAILURE
        is PaymentTracking.Error -> payment.message to StatusKind.FAILURE
        else -> "No pudimos consultar el estado del pago." to StatusKind.FAILURE
    }
    GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), spacing = 12.dp) {
        IconBadge(
            if (payment == PaymentTracking.Expired) R.drawable.ic_clock else R.drawable.ic_close,
            tint = statusColor(kind),
            size = 56.dp,
            background = statusSoftColor(kind),
        )
        Text(text, color = statusColor(kind), style = MaterialTheme.typography.headlineSmall)
        ChargeSummary(confirmed)
    }
    GpButton("Escanear otro QR", onClick = onNewCharge, icon = R.drawable.ic_charge, modifier = Modifier.fillMaxWidth())
}
