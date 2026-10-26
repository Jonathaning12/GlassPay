package com.glasspay.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ContactsScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Contactos", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Próximamente: guarda a tus amigos después de escanear su QR para cobrarles más rápido.",
            style = MaterialTheme.typography.bodyLarge,
            color = GlassPayColors.MutedText,
        )
    }
}
