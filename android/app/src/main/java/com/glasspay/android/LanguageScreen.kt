package com.glasspay.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Región que acompaña al nombre del idioma en la lista (se muestra en ese mismo idioma). */
private val AppLanguage.regionName: String
    get() = when (this) {
        AppLanguage.ES -> "Español (México)"
        AppLanguage.EN -> "English (United States)"
    }

/**
 * Pantalla "Idioma" (se abre desde Perfil): lista de idiomas disponibles con el actual marcado.
 * Al tocar uno cambia al instante la interfaz, la voz del cobro y las respuestas del servidor.
 */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val selected = currentLanguage
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(L("Idioma"), onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                L("Elige el idioma de las pantallas, la voz del cobro y el asistente."),
                style = MaterialTheme.typography.bodyMedium,
                color = GlassPayColors.MutedText,
            )
            GpCard(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                contentPadding = PaddingValues(0.dp),
                spacing = 0.dp,
            ) {
                AppLanguage.entries.forEachIndexed { index, option ->
                    if (index > 0) HorizontalDivider(color = GlassPayColors.Line, modifier = Modifier.padding(start = 16.dp))
                    LanguageOption(
                        language = option,
                        selected = option == selected,
                        onSelect = { AppLanguageStore.set(context, option) },
                    )
                }
            }
            InlineNotice(
                L("Se guarda solo en este teléfono. Los mensajes que ya estaban en pantalla no cambian."),
                icon = R.drawable.ic_globe,
            )
        }
    }
}

@Composable
private fun LanguageOption(language: AppLanguage, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                language.nativeName,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) GlassPayColors.Accent else GlassPayColors.Text,
            )
            Text(language.regionName, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
        }
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = GlassPayColors.Accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
