package com.glasspay.android

import android.util.Log
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
    onAddWithQr: () -> Unit,
    onSend: (Contact) -> Unit,
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Contactos", style = MaterialTheme.typography.headlineMedium, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
            CircleIconButton(R.drawable.ic_plus, "Agregar con QR", onAddWithQr, tint = GlassPayColors.Accent)
        }
        message?.let { InlineNotice(it, kind = StatusKind.PENDING) }
        when (val current = state) {
            LoadState.Loading -> repeat(3) { SkeletonBox(Modifier.fillMaxWidth().height(124.dp)) }
            LoadState.Failed -> {
                InlineNotice("No pudimos cargar tus contactos.", kind = StatusKind.FAILURE)
                GpButton("Reintentar", onClick = { reload += 1 }, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
            }
            is LoadState.Loaded -> {
                if (current.value.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBadge(R.drawable.ic_contacts, tint = GlassPayColors.Accent, size = 72.dp, background = GlassPayColors.AccentSoft)
                        Text("Aún no tienes contactos", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
                        Text(
                            "Escanea el QR de un amigo y guárdalo para cobrarle más rápido.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassPayColors.MutedText,
                            textAlign = TextAlign.Center,
                        )
                        GpButton("Agregar con QR", onClick = onAddWithQr, icon = R.drawable.ic_qr, modifier = Modifier.fillMaxWidth())
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    items(current.value, key = { it.contactId }) { contact ->
                        ContactRow(
                            contact = contact,
                            busy = busyContactId == contact.contactId,
                            enabled = busyContactId == null,
                            onCharge = { chargeContact(contact) },
                            onSend = { onSend(contact) },
                            onDelete = { pendingDelete = contact },
                        )
                    }
                    item {
                        GpButton("Actualizar", onClick = { reload += 1 }, kind = GpButtonKind.GHOST, modifier = Modifier.fillMaxWidth())
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
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), spacing = 14.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(contact.displayName)
            Text(
                contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = GlassPayColors.Text,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = GlassPayColors.Accent)
            } else {
                CircleIconButton(R.drawable.ic_trash, "Eliminar a ${contact.displayName}", onDelete, tint = GlassPayColors.Error)
            }
        }
        if (!busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GpButton("Cobrar", onClick = onCharge, enabled = enabled, icon = R.drawable.ic_receive, modifier = Modifier.weight(1f))
                GpButton("Enviar", onClick = onSend, enabled = enabled, kind = GpButtonKind.SECONDARY, icon = R.drawable.ic_send, modifier = Modifier.weight(1f))
            }
        }
    }
}
