package com.glasspay.android

import org.json.JSONObject

/** Totales que el asistente devuelve para "¿cuánto cobré…?". Montos como texto decimal del servidor. */
data class AssistantSummary(
    val period: String,
    val currency: String,
    val paidTotal: String,
    val paidCount: Int,
    val sentTotal: String,
    val sentCount: Int,
    val netTotal: String,
)

data class AssistantDebtor(val name: String, val total: String, val count: Int)

/** Propuesta guardada en el servidor; solo se aplica si el cobrador la confirma. */
data class AssistantProposal(
    val proposalId: String,
    val action: String,
    val contactName: String,
    /** Nulo en un recordatorio sin monto. */
    val amount: String?,
    val currency: String,
    val concept: String,
    /** Nulo en una división que se cobra al confirmar. */
    val runAt: String?,
    val runAtLabel: String?,
    val expiresAt: String,
    val recurrence: String? = null,
    val occurrencesTotal: Int? = null,
    val participants: List<SplitShare> = emptyList(),
    val myShare: String? = null,
    /** Aviso: hoy el saldo no alcanza (se vuelve a verificar al enviar). */
    val balanceWarning: Boolean = false,
)

data class SplitShare(val contactName: String, val amount: String)

/** Lo que pasó al confirmar una propuesta. */
sealed interface ProposalOutcome {
    data class Scheduled(val items: List<ScheduledItem>) : ProposalOutcome
    data object Cancelled : ProposalOutcome
    data class Split(val results: List<SplitResult>) : ProposalOutcome
    data class ReminderSent(val contactName: String) : ProposalOutcome
}

/** Recordatorio que le llegó al usuario (texto de plantilla fija del servidor). */
data class Reminder(val reminderId: String, val text: String, val createdAt: String)

data class SplitResult(val contactName: String, val amount: String, val ok: Boolean, val failure: String?)

data class ScheduledItem(
    val scheduledId: String,
    /** "charge" o "reminder". */
    val kind: String = "charge",
    val contactName: String,
    val amount: String,
    val currency: String,
    val concept: String,
    val runAt: String,
    val runAtLabel: String,
    val autoExecute: Boolean,
    val state: String,
    val failure: String?,
    val recurrence: String? = null,
    val occurrencesTotal: Int = 1,
    val occurrencesDone: Int = 0,
)

/** Respuesta redactada por el servidor; la app solo la muestra. */
data class AssistantReply(
    val kind: String,
    val text: String,
    val summary: AssistantSummary? = null,
    val debtors: List<AssistantDebtor> = emptyList(),
    val proposal: AssistantProposal? = null,
    val scheduled: List<ScheduledItem> = emptyList(),
)

data class AssistantResponse(val reply: AssistantReply, val suggestions: List<String>)

internal fun parseAssistantResponse(body: String): AssistantResponse {
    val json = JSONObject(body)
    val reply = json.getJSONObject("reply")
    val summary = reply.optJSONObject("summary")?.let {
        AssistantSummary(
            period = it.getString("period"),
            currency = it.getString("currency"),
            paidTotal = it.getString("paidTotal"),
            paidCount = it.getInt("paidCount"),
            sentTotal = it.getString("sentTotal"),
            sentCount = it.getInt("sentCount"),
            netTotal = it.getString("netTotal"),
        )
    }
    val debtors = reply.optJSONArray("debtors")?.let { items ->
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            AssistantDebtor(item.getString("name"), item.getString("total"), item.getInt("count"))
        }
    }.orEmpty()
    val proposal = reply.optJSONObject("proposal")?.let {
        AssistantProposal(
            proposalId = it.getString("proposalId"),
            action = it.getString("action"),
            contactName = it.getString("contactName"),
            amount = it.optStringOrNull("amount"),
            currency = it.getString("currency"),
            concept = it.getString("concept"),
            runAt = it.optStringOrNull("runAt"),
            runAtLabel = it.optStringOrNull("runAtLabel"),
            expiresAt = it.getString("expiresAt"),
            recurrence = it.optStringOrNull("recurrence"),
            occurrencesTotal = if (it.isNull("occurrencesTotal")) null else it.optInt("occurrencesTotal"),
            participants = it.optJSONArray("participants")?.let { shares ->
                (0 until shares.length()).map { index ->
                    val share = shares.getJSONObject(index)
                    SplitShare(share.getString("contactName"), share.getString("amount"))
                }
            }.orEmpty(),
            myShare = it.optStringOrNull("myShare"),
            balanceWarning = it.optBoolean("balanceWarning", false),
        )
    }
    val scheduled = reply.optJSONArray("scheduled")?.let { items ->
        (0 until items.length()).map { parseScheduledItem(items.getJSONObject(it)) }
    }.orEmpty()
    val suggestions = json.optJSONArray("suggestions")?.let { items ->
        (0 until items.length()).map { items.getString(it) }
    }.orEmpty()
    val text = reply.getString("text")
    require(text.isNotBlank())
    return AssistantResponse(
        reply = AssistantReply(reply.getString("kind"), text, summary, debtors, proposal, scheduled),
        suggestions = suggestions,
    )
}

internal fun parseScheduledItem(item: JSONObject): ScheduledItem {
    return ScheduledItem(
        scheduledId = item.getString("scheduledId"),
        kind = item.optString("kind", "charge").ifEmpty { "charge" },
        contactName = item.getString("contactName"),
        amount = item.getString("amount"),
        currency = item.getString("currency"),
        concept = item.getString("concept"),
        runAt = item.getString("runAt"),
        runAtLabel = item.getString("runAtLabel"),
        autoExecute = item.getBoolean("autoExecute"),
        state = item.getString("state"),
        failure = item.optStringOrNull("failure"),
        recurrence = item.optStringOrNull("recurrence"),
        occurrencesTotal = item.optInt("occurrencesTotal", 1),
        occurrencesDone = item.optInt("occurrencesDone", 0),
    )
}

internal fun parseProposalOutcome(body: String): ProposalOutcome {
    val json = JSONObject(body)
    json.optJSONArray("split")?.let { items ->
        return ProposalOutcome.Split(
            (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                SplitResult(item.getString("contactName"), item.getString("amount"), item.getBoolean("ok"), item.optStringOrNull("failure"))
            },
        )
    }
    json.optJSONArray("scheduledItems")?.let { items ->
        return ProposalOutcome.Scheduled((0 until items.length()).map { parseScheduledItem(items.getJSONObject(it)) })
    }
    json.optJSONObject("scheduled")?.let { return ProposalOutcome.Scheduled(listOf(parseScheduledItem(it))) }
    if (json.optBoolean("cancelled")) return ProposalOutcome.Cancelled
    json.optJSONObject("reminder")?.let { return ProposalOutcome.ReminderSent(it.optString("contactName")) }
    throw IllegalArgumentException("unknown proposal outcome")
}

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }

fun recurrenceLabel(recurrence: String?, total: Int?): String? {
    val every = when (recurrence) {
        "weekly" -> "Cada semana"
        "monthly" -> "Cada mes"
        else -> return null
    }
    return if (total != null && total > 0) "$every · $total veces" else every
}

/** Mensaje del chat tras confirmar; nunca dice "pagado": el pagador aún debe aprobar en PayPal. */
fun proposalOutcomeMessage(outcome: ProposalOutcome, autoExecute: Boolean): String {
    return when (outcome) {
        ProposalOutcome.Cancelled -> "Listo, cancelé la programación."
        is ProposalOutcome.ReminderSent ->
            "Listo, le envié el recordatorio a ${outcome.contactName}. Lo verá en su sección «Por pagar»."
        is ProposalOutcome.Split -> {
            val ok = outcome.results.filter { it.ok }
            val failed = outcome.results.filterNot { it.ok }
            buildString {
                append(
                    if (ok.isEmpty()) {
                        "No pude crear ningún cobro."
                    } else {
                        "Creé ${ok.size} ${if (ok.size == 1) "cobro" else "cobros"}: " +
                            ok.joinToString(", ") { "${it.contactName} ${formatMoney(it.amount, "MXN").substringBeforeLast(" ")}" } +
                            ". Cada quien lo aprueba en PayPal."
                    },
                )
                if (failed.isNotEmpty()) {
                    append(" No se creó para: " + failed.joinToString(", ") { it.contactName } + ".")
                }
            }
        }
        is ProposalOutcome.Scheduled -> {
            val first = outcome.items.firstOrNull()
            val who = if (outcome.items.size > 1) "${outcome.items.size} personas" else first?.contactName.orEmpty()
            val series = recurrenceLabel(first?.recurrence, first?.occurrencesTotal)?.let { " ($it)" }.orEmpty()
            if (autoExecute) {
                "Listo. El ${first?.runAtLabel} crearé el cobro a $who$series; luego lo aprueban en PayPal."
            } else {
                "Listo. El ${first?.runAtLabel} te pediré confirmar el cobro a $who$series."
            }
        }
    }
}

const val MAX_ASSISTANT_MESSAGE_LENGTH = 500

/** Estado de una tarjeta de propuesta dentro del chat. */
enum class ProposalStatus { PENDING, CONFIRMING, CONFIRMED, DISCARDED, FAILED }

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val reply: AssistantReply? = null,
    val isError: Boolean = false,
)

/** Conversación solo en memoria: se pierde al salir de la pantalla y nunca se guarda en disco. */
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val suggestions: List<String> = DEFAULT_ASSISTANT_SUGGESTIONS,
    val proposals: Map<String, ProposalStatus> = emptyMap(),
    private val nextId: Long = 1,
) {
    fun proposalStatus(proposalId: String): ProposalStatus = proposals[proposalId] ?: ProposalStatus.PENDING

    /** Solo una propuesta pendiente puede confirmarse, y solo una vez. */
    fun canConfirm(proposalId: String): Boolean = proposalStatus(proposalId) == ProposalStatus.PENDING

    fun proposalConfirming(proposalId: String): ChatState =
        if (canConfirm(proposalId)) copy(proposals = proposals + (proposalId to ProposalStatus.CONFIRMING)) else this

    fun proposalDiscarded(proposalId: String): ChatState =
        if (canConfirm(proposalId)) copy(proposals = proposals + (proposalId to ProposalStatus.DISCARDED)) else this

    fun proposalConfirmed(proposalId: String, message: String): ChatState = copy(
        proposals = proposals + (proposalId to ProposalStatus.CONFIRMED),
        messages = messages + ChatMessage(nextId, fromUser = false, text = message),
        nextId = nextId + 1,
    )

    fun proposalFailed(proposalId: String, message: String): ChatState = copy(
        proposals = proposals + (proposalId to ProposalStatus.FAILED),
        messages = messages + ChatMessage(nextId, fromUser = false, text = message, isError = true),
        nextId = nextId + 1,
    )

    fun canSend(text: String): Boolean {
        val trimmed = text.trim()
        return !sending && trimmed.isNotEmpty() && trimmed.length <= MAX_ASSISTANT_MESSAGE_LENGTH
    }

    fun userSent(text: String): ChatState = copy(
        messages = messages + ChatMessage(nextId, fromUser = true, text = text.trim()),
        sending = true,
        nextId = nextId + 1,
    )

    fun replied(response: AssistantResponse): ChatState = copy(
        messages = messages + ChatMessage(nextId, fromUser = false, text = response.reply.text, reply = response.reply),
        sending = false,
        suggestions = response.suggestions.ifEmpty { suggestions },
        nextId = nextId + 1,
    )

    fun failed(message: String): ChatState = copy(
        messages = messages + ChatMessage(nextId, fromUser = false, text = message, isError = true),
        sending = false,
        nextId = nextId + 1,
    )
}

val DEFAULT_ASSISTANT_SUGGESTIONS = listOf(
    "¿Cuánto cobré esta semana?",
    "¿Quién me debe?",
    "¿Qué tengo programado?",
    "¿Qué puedes hacer?",
)

fun scheduledStateLabel(state: String, kind: String = "charge"): String {
    if (state == "completado") {
        return when (kind) {
            "reminder" -> "Enviado"
            // El envío por PayPal puede seguir procesándose: se ve en Movimientos.
            "payment" -> "Envío creado"
            else -> "Cobro creado"
        }
    }
    return when (state) {
        "programado" -> "Programado"
        "esperando_confirmacion" -> "Espera tu confirmación"
        "ejecutando" -> "Creando cobro"
        // "Creado", nunca "pagado": el pagador todavía tiene que aprobarlo en PayPal.
        "completado" -> "Cobro creado"
        "fallido" -> "No se creó"
        "cancelado" -> "Cancelado"
        "omitido" -> "Omitido"
        else -> state
    }
}

fun scheduledStateKind(state: String): StatusKind {
    return when (state) {
        "programado", "esperando_confirmacion", "ejecutando" -> StatusKind.PENDING
        "completado" -> StatusKind.SUCCESS
        "fallido" -> StatusKind.FAILURE
        else -> StatusKind.NEUTRAL
    }
}

fun scheduledFailureMessage(failure: String?): String? {
    return when (failure) {
        null, "" -> null
        "demasiados_pendientes" -> "Tu contacto ya tenía demasiados cobros pendientes."
        "paypal_no_disponible" -> "PayPal no respondió a tiempo."
        "contacto_eliminado" -> "Eliminaste a este contacto antes de la hora."
        "atrasado" -> "El servidor estaba inactivo a esa hora, así que no se creó."
        "interrumpido" -> "Se interrumpió. Revisa Movimientos antes de volver a cobrar."
        "sin_confirmar" -> "No lo confirmaste dentro de las 2 horas siguientes."
        "limite_recordatorios" -> "Ya le enviaste 2 recordatorios hoy a este contacto."
        "saldo_insuficiente" -> "No se envió: tu saldo no alcanzaba a esa hora."
        "sin_correo" -> "No se envió: tu contacto ya no tiene correo de cobro."
        else -> "No se pudo crear el cobro."
    }
}

fun isScheduledActive(state: String): Boolean =
    state == "programado" || state == "esperando_confirmacion" || state == "ejecutando"

fun proposalErrorMessage(type: ApiFailureType): String {
    return when (type) {
        ApiFailureType.NOT_FOUND -> "La propuesta ya venció o se usó. Pídemela de nuevo."
        ApiFailureType.CONFLICT ->
            "No se pudo: ya pasó la hora, llegaste al máximo de programaciones o ya enviaste 2 recordatorios hoy a ese contacto."
        else -> assistantErrorMessage(type)
    }
}

fun assistantErrorMessage(type: ApiFailureType): String {
    return when (type) {
        ApiFailureType.NETWORK -> "Sin conexión. Revisa tu red e inténtalo de nuevo."
        ApiFailureType.TIMEOUT -> "El servidor tardó demasiado. Si estaba dormido, inténtalo de nuevo en unos segundos."
        ApiFailureType.RATE_LIMITED -> "Demasiados mensajes seguidos. Espera un momento."
        ApiFailureType.BAD_REQUEST -> "Ese mensaje no es válido. Escríbelo de otra forma."
        else -> "No pude responder ahora. Inténtalo de nuevo."
    }
}

fun assistantPeriodLabel(period: String): String {
    return when (period) {
        "month" -> "Este mes"
        "year" -> "Este año"
        else -> "Esta semana"
    }
}
