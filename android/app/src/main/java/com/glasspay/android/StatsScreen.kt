package com.glasspay.android

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

@Composable
fun StatsScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onBack: () -> Unit,
) {
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoadState<Stats>>(LoadState.Loading) }

    BackHandler { onBack() }

    LaunchedEffect(period, reload) {
        state = LoadState.Loading
        state = try {
            LoadState.Loaded(api.getStats(session, period))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "stats failure type=${exception.type} httpCode=${exception.httpCode}")
            if (isSessionInvalid(exception.type)) {
                onSessionInvalid(SESSION_INVALID_MESSAGE)
                return@LaunchedEffect
            }
            LoadState.Failed
        } catch (_: Exception) {
            Log.i("GlassPay", "stats failure type=UNKNOWN httpCode=none")
            LoadState.Failed
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Estadísticas", onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PeriodSelector(period, onSelect = { period = it })
            when (val current = state) {
                LoadState.Loading -> {
                    SkeletonBox(Modifier.fillMaxWidth(0.7f).height(56.dp))
                    SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
                    SkeletonBox(Modifier.fillMaxWidth().height(260.dp))
                }
                LoadState.Failed -> {
                    InlineNotice("No pudimos cargar tus estadísticas.", kind = StatusKind.FAILURE)
                    GpButton("Reintentar", onClick = { reload += 1 }, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
                }
                is LoadState.Loaded -> StatsContent(current.value, period)
            }
            Text(
                "Actividad en GlassPay (Sandbox). Solo cuenta cobros pagados y envíos completados; no es tu saldo bancario.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassPayColors.MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

/** Control segmentado de 3 opciones. Cambiar de periodo es frecuente: sin animación de deslizamiento. */
@Composable
private fun PeriodSelector(selected: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(GlassPayColors.Card)
            .border(1.dp, GlassPayColors.Line, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatsPeriod.entries.forEach { option ->
            val active = option == selected
            Surface(
                onClick = { onSelect(option) },
                shape = RoundedCornerShape(50),
                color = if (active) GlassPayColors.Raised else Color.Transparent,
                contentColor = if (active) GlassPayColors.Text else GlassPayColors.MutedText,
                modifier = Modifier.weight(1f).height(40.dp).semantics {
                    contentDescription = if (active) "${option.label}, seleccionado" else option.label
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(option.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun StatsContent(stats: Stats, period: StatsPeriod) {
    val empty = stats.paidCount == 0 && stats.sentCount == 0 &&
        stats.pendingByStatus == 0 && stats.notCompletedByStatus == 0
    // Hero: balance del periodo y variación contra el anterior.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Overline("Balance del periodo")
        Text(formatGroupedMoney(stats.netTotal, signed = true), style = MaterialTheme.typography.displaySmall, color = GlassPayColors.Text)
        netChangePercent(stats.netTotal, stats.previousNetTotal)?.let { change ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val up = change >= 0
                Row(
                    modifier = Modifier
                        .background(if (up) GlassPayColors.SuccessSoft else GlassPayColors.ErrorSoft, RoundedCornerShape(50))
                        .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (up) R.drawable.ic_send else R.drawable.ic_receive),
                        contentDescription = if (up) "Subió" else "Bajó",
                        tint = if (up) GlassPayColors.Success else GlassPayColors.Error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("${abs(change)} %", style = MaterialTheme.typography.labelLarge, color = if (up) GlassPayColors.Success else GlassPayColors.Error)
                }
                Text(period.previousLabel, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TotalTile("Cobrado", stats.paidTotal, if (stats.paidCount == 1) "1 cobro pagado" else "${stats.paidCount} cobros pagados", GlassPayColors.ChartPaid, Modifier.weight(1f))
        TotalTile("Enviado", stats.sentTotal, if (stats.sentCount == 1) "1 envío completado" else "${stats.sentCount} envíos completados", GlassPayColors.ChartSent, Modifier.weight(1f))
    }
    if (empty) {
        InlineNotice("Sin actividad en este periodo. Cuando cobres o envíes, aparecerá aquí.", icon = R.drawable.ic_chart)
        return
    }
    ChartCard(stats, period)
    StatusCard(stats)
    if (stats.topPayers.isNotEmpty()) TopPayersCard(stats.topPayers)
}

@Composable
private fun TotalTile(label: String, total: Double, detail: String, color: Color, modifier: Modifier = Modifier) {
    GpCard(modifier = modifier, contentPadding = PaddingValues(16.dp), spacing = 6.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.labelLarge, color = GlassPayColors.MutedText)
        }
        // Las cifras van con colores de texto; el color de la serie solo identifica (punto).
        Text(formatGroupedMoney(total), style = MaterialTheme.typography.headlineSmall, color = GlassPayColors.Text, maxLines = 1)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
    }
}

@Composable
private fun ChartCard(stats: Stats, period: StatsPeriod) {
    var selected by remember(stats) { mutableIntStateOf(defaultSelectedBucket(stats.buckets)) }
    val bucket = stats.buckets.getOrNull(selected)
    GpCard(modifier = Modifier.fillMaxWidth(), spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Por ${period.bucketNoun}", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendItem("Cobrado", GlassPayColors.ChartPaid)
                LegendItem("Enviado", GlassPayColors.ChartSent)
            }
        }
        // Lectura del periodo seleccionado: arriba de la gráfica para que el dedo no la tape.
        if (bucket != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(GlassPayColors.Raised, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(bucket.label, style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
                Box(Modifier.size(8.dp).background(GlassPayColors.ChartPaid, CircleShape))
                Text(formatGroupedMoney(bucket.paidTotal), style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Text)
                Box(Modifier.size(8.dp).background(GlassPayColors.ChartSent, CircleShape))
                Text(formatGroupedMoney(bucket.sentTotal), style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Text)
            }
        }
        DotLineChart(
            labels = stats.buckets.map { it.label },
            series = listOf(
                DotSeries(stats.buckets.map { it.sentTotal }, GlassPayColors.ChartSent),
                DotSeries(stats.buckets.map { it.paidTotal }, GlassPayColors.ChartPaid),
            ),
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics {
                    contentDescription = "Gráfica de cobrado y enviado por ${period.bucketNoun}. " +
                        stats.buckets.joinToString(". ") {
                            "${it.label}: cobrado ${formatGroupedMoney(it.paidTotal)}, enviado ${formatGroupedMoney(it.sentTotal)}"
                        }
                },
        )
        Text("Toca o desliza sobre la gráfica para ver cada ${period.bucketNoun}.", style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
    }
}

@Composable
private fun StatusCard(stats: Stats) {
    val parts = listOf(
        Triple("Pagados", stats.paidByStatus, StatusKind.SUCCESS),
        Triple("Pendientes", stats.pendingByStatus, StatusKind.PENDING),
        Triple("No completados", stats.notCompletedByStatus, StatusKind.FAILURE),
    )
    val total = parts.sumOf { it.second }
    if (total == 0) return
    GpCard(modifier = Modifier.fillMaxWidth(), spacing = 14.dp) {
        Text("Cobros por estado", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
        val max = parts.maxOf { it.second }.coerceAtLeast(1)
        parts.forEachIndexed { order, (label, count, kind) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(
                            when (kind) {
                                StatusKind.SUCCESS -> R.drawable.ic_check
                                StatusKind.PENDING -> R.drawable.ic_clock
                                else -> R.drawable.ic_close
                            },
                        ),
                        contentDescription = null,
                        tint = statusColor(kind),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(label, style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
                    Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
                }
                DotTrack(count.toFloat() / max, statusColor(kind), order = order)
            }
        }
        Text("No completados = rechazados, expirados o fallidos.", style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
    }
}

@Composable
private fun TopPayersCard(payers: List<StatsPayer>) {
    val max = payers.maxOf { it.total }.coerceAtLeast(0.01)
    GpCard(modifier = Modifier.fillMaxWidth(), spacing = 16.dp) {
        Text("Quién te pagó más", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
        payers.forEachIndexed { order, payer ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(payer.name, size = 36.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(
                            "${payer.name} · ${payer.count} ${if (payer.count == 1) "cobro" else "cobros"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = GlassPayColors.Text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatGroupedMoney(payer.total), style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Text)
                    }
                    DotTrack((payer.total / max).toFloat(), GlassPayColors.ChartPaid, order = order)
                }
            }
        }
    }
}
