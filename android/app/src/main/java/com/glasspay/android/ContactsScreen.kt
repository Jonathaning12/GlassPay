package com.glasspay.android

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onChargeContact: (PayerResolution) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<List<Contact>>>(LoadState.Loading) }
    var busyContactId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Contact?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        state = LoadState.Loading
        try {
            state = LoadState.Loaded(api.listContacts(session))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "contacts failure type=${exception.type} httpCode=${exception.httpCode}")
            if (isSessionInvalid(exception.type)) {
                onSessionInvalid(SESSION_INVALID_MESSAGE)
            } else {
                state = LoadState.Failed
            }
        } catch (_: Exception) {
            Log.i("GlassPay", "contacts failure type=UNKNOWN httpCode=none")
            state = LoadState.Failed
        }
    }

    fun chargeContact(contact: Contact) {
        if (busyContactId != null) return
        busyContactId = contact.contactId
        message = null
        scope.launch {
            try {
                val resolution = api.contactResolution(session, contact.contactId)
                onChargeContact(resolution)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "contactResolution failure type=${exception.type} httpCode=${exception.httpCode}")
                when (exception.type) {
                    ApiFailureType.UNAUTHORIZED -> onSessionInvalid(SESSION_INVALID_MESSAGE)
                    ApiFailureType.NOT_FOUND -> {
                        message = "Este contacto ya no existe."
                        reload += 1
                    }
                    ApiFailureType.RATE_LIMITED -> message = "Demasiados intentos. Espera unos segundos."
                    else -> message = "No pudimos preparar el cobro. Revisa tu conexión."
                }
            } catch (_: Exception) {
                Log.i("GlassPay", "contactResolution failure type=UNKNOWN httpCode=none")
                message = "No pudimos preparar el cobro. Revisa tu conexión."
            } finally {
                busyContactId = null
            }
        }
    }

    fun deleteContact(contact: Contact) {
        if (busyContactId != null) return
        busyContactId = contact.contactId
        message = null
        scope.launch {
            try {
                api.deleteContact(session, contact.contactId)
                reload += 1
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "deleteContact failure type=${exception.type} httpCode=${exception.httpCode}")
                when (exception.type) {
                    ApiFailureType.UNAUTHORIZED -> onSessionInvalid(SESSION_INVALID_MESSAGE)
                    ApiFailureType.NOT_FOUND -> reload += 1
                    ApiFailureType.RATE_LIMITED -> message = "Demasiados intentos. Espera unos segundos."
                    else -> message = "No pudimos eliminar el contacto. Revisa tu conexión."
                }
            } catch (_: Exception) {
                Log.i("GlassPay", "deleteContact failure type=UNKNOWN httpCode=none")
                message = "No pudimos eliminar el contacto. Revisa tu conexión."
            } finally {
                busyContactId = null
            }
        }
    }

    pendingDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text("¿Eliminar a ${contact.displayName} de tus contactos?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteContact(contact)
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Contactos", style = MaterialTheme.typography.headlineMedium)
        message?.let { Text(it, color = GlassPayColors.Warning) }
        when (val current = state) {
            LoadState.Loading -> CircularProgressIndicator()
            LoadState.Failed -> {
                Text("No pudimos cargar tus contactos.", color = GlassPayColors.Error)
                Button(onClick = { reload += 1 }) { Text("Reintentar") }
            }
            is LoadState.Loaded -> {
                OutlinedButton(onClick = { reload += 1 }) { Text("Actualizar") }
                if (current.value.isEmpty()) {
                    Text(
                        "Escanea el QR de un amigo y guárdalo para cobrarle más rápido.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = GlassPayColors.MutedText,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(current.value, key = { it.contactId }) { contact ->
                        ContactRow(
                            contact = contact,
                            busy = busyContactId == contact.contactId,
                            enabled = busyContactId == null,
                            onCharge = { chargeContact(contact) },
                            onDelete = { pendingDelete = contact },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    busy: Boolean,
    enabled: Boolean,
    onCharge: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(contact.displayName.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Button(onClick = onCharge, enabled = enabled) { Text("Cobrar") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("Eliminar") }
            }
        }
    }
}
