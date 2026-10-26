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

/** Respuesta redactada por el servidor; la app solo la muestra. */
data class AssistantReply(
    val kind: String,
    val text: String,
    val summary: AssistantSummary? = null,
    val debtors: List<AssistantDebtor> = emptyList(),
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
    val suggestions = json.optJSONArray("suggestions")?.let { items ->
        (0 until items.length()).map { items.getString(it) }
    }.orEmpty()
    val text = reply.getString("text")
    require(text.isNotBlank())
    return AssistantResponse(
        reply = AssistantReply(reply.getString("kind"), text, summary, debtors),
        suggestions = suggestions,
    )
}

const val MAX_ASSISTANT_MESSAGE_LENGTH = 500

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
    private val nextId: Long = 1,
) {
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

val DEFAULT_ASSISTANT_SUGGESTIONS = listOf("¿Cuánto cobré esta semana?", "¿Quién me debe?", "¿Qué puedes hacer?")

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
