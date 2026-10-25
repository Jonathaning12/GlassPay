package com.glasspay.android

import java.text.Normalizer
import java.util.Locale

sealed interface ChargeState {
    data class Ready(val message: String? = null) : ChargeState
    data object Listening : ChargeState
    data object Interpreting : ChargeState
    data class Clarification(val question: String) : ChargeState
    data class Preview(
        val proposal: ChargeProposal,
        val remainingSeconds: Long,
        val message: String? = null,
    ) : ChargeState
    data object Confirming : ChargeState
    data class Created(
        val requestId: String,
        val statusToken: String,
        val payment: PaymentTracking = PaymentTracking.Waiting,
    ) : ChargeState
    data class Error(val message: String, val recoverable: Boolean) : ChargeState
}

sealed interface PaymentTracking {
    data object Waiting : PaymentTracking
    data class WaitingWithMessage(val message: String) : PaymentTracking
    data object Paid : PaymentTracking
    data object Rejected : PaymentTracking
    data object Expired : PaymentTracking
    data object Failed : PaymentTracking
    data class Error(val message: String) : PaymentTracking
}

data class ChargeProposal(
    val proposalId: String,
    val amount: String,
    val currency: String,
    val concept: String,
    val expiresAt: String,
)

const val MAX_CLARIFICATION_ROUNDS = 3
const val MAX_ACCUMULATED_TEXT_LENGTH = 480

data class ChargeInputSession(
    val resolutionId: String,
    val text: String,
    val clarificationRounds: Int = 0,
) {
    companion object {
        fun start(resolutionId: String, text: String): ChargeInputSession? {
            val normalized = text.trim()
            if (normalized.isEmpty() || normalized.length > MAX_ACCUMULATED_TEXT_LENGTH) return null
            return ChargeInputSession(resolutionId, normalized)
        }
    }

    fun appendClarification(reply: String): ChargeInputAppendResult {
        val normalized = reply.trim()
        if (normalized.isEmpty()) return ChargeInputAppendResult.Invalid
        if (startsWithChargeVerb(normalized)) {
            return ChargeInputAppendResult.Accepted(
                ChargeInputSession(
                    resolutionId = resolutionId,
                    text = normalized,
                ),
            )
        }
        if (clarificationRounds >= MAX_CLARIFICATION_ROUNDS) {
            return ChargeInputAppendResult.TooManyRounds
        }
        val combined = "$text $normalized"
        if (combined.length > MAX_ACCUMULATED_TEXT_LENGTH) {
            return ChargeInputAppendResult.TooLong
        }
        return ChargeInputAppendResult.Accepted(
            copy(text = combined, clarificationRounds = clarificationRounds + 1),
        )
    }
}

private val CHARGE_VERBS = setOf("cobrale", "cobrales", "cobra", "cobrar")

fun startsWithChargeVerb(text: String): Boolean {
    val firstWord = normalizeCommandText(text)
        .split(Regex("\\s+"))
        .firstOrNull()
        ?: return false
    return firstWord in CHARGE_VERBS
}

enum class VoiceCommand {
    CANCEL,
    START_OVER,
    CONFIRM,
    NONE,
}

fun voiceCommand(text: String, state: ChargeState): VoiceCommand {
    return when {
        isCancellation(text) &&
            (state is ChargeState.Listening ||
                state is ChargeState.Clarification ||
                state is ChargeState.Preview) -> VoiceCommand.CANCEL
        normalizeCommandText(text) == "empezar de nuevo" &&
            (state is ChargeState.Clarification || state is ChargeState.Preview) -> VoiceCommand.START_OVER
        isConfirmation(text) && state is ChargeState.Preview -> VoiceCommand.CONFIRM
        else -> VoiceCommand.NONE
    }
}

fun normalizeCommandText(text: String): String {
    val withoutMarks = Normalizer.normalize(
        text.lowercase(Locale("es", "MX")).trim(),
        Normalizer.Form.NFD,
    ).filterNot { Character.getType(it) == Character.NON_SPACING_MARK }
    var start = 0
    var end = withoutMarks.length
    while (start < end && isCommandBoundaryPunctuation(withoutMarks[start])) start += 1
    while (end > start && isCommandBoundaryPunctuation(withoutMarks[end - 1])) end -= 1
    return withoutMarks.substring(start, end).replace(Regex("\\s+"), " ")
}

private fun isCommandBoundaryPunctuation(character: Char): Boolean {
    return when (Character.getType(character)) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }
}

fun displayText(text: String, maxLength: Int): String {
    return text.trim().take(maxLength)
}

enum class InputSessionDecisionReason {
    SERVER_CLARIFICATION,
    VOICE_ERROR_6,
    VOICE_ERROR_7,
    VOICE_ERROR_8,
    VOICE_ERROR_OTHER,
    INTERPRET_NETWORK,
    INTERPRET_TIMEOUT,
    INTERPRET_RATE_LIMIT,
    INTERPRET_DEFINITIVE_ERROR,
}

fun inputSessionAfterDecision(
    session: ChargeInputSession?,
    reason: InputSessionDecisionReason,
): ChargeInputSession? {
    return when (reason) {
        InputSessionDecisionReason.INTERPRET_DEFINITIVE_ERROR -> null
        else -> session
    }
}

fun inputSessionAfterVoiceError(
    session: ChargeInputSession?,
    errorCode: Int,
): ChargeInputSession? {
    return inputSessionAfterDecision(
        session,
        when (errorCode) {
            6 -> InputSessionDecisionReason.VOICE_ERROR_6
            7 -> InputSessionDecisionReason.VOICE_ERROR_7
            8 -> InputSessionDecisionReason.VOICE_ERROR_8
            else -> InputSessionDecisionReason.VOICE_ERROR_OTHER
        },
    )
}

enum class ChargeInputClearReason {
    PROPOSAL_CREATED,
    CANCELLED,
    RESTARTED,
    QR_CHANGED,
    RESOLUTION_EXPIRED,
    PROPOSAL_EXPIRED,
}

fun inputSessionAfter(
    session: ChargeInputSession?,
    reason: ChargeInputClearReason,
): ChargeInputSession? {
    return when (reason) {
        ChargeInputClearReason.RECOVERABLE_VOICE_ERROR -> session
        ChargeInputClearReason.PROPOSAL_CREATED,
        ChargeInputClearReason.CANCELLED,
        ChargeInputClearReason.RESTARTED,
        ChargeInputClearReason.QR_CHANGED,
        ChargeInputClearReason.RESOLUTION_EXPIRED,
        ChargeInputClearReason.PROPOSAL_EXPIRED -> null
    }
}

sealed interface ChargeInputAppendResult {
    data class Accepted(val session: ChargeInputSession) : ChargeInputAppendResult
    data object TooManyRounds : ChargeInputAppendResult
    data object TooLong : ChargeInputAppendResult
    data object Invalid : ChargeInputAppendResult
}

fun shouldAcceptVoiceError(state: ChargeState): Boolean {
    return state is ChargeState.Listening ||
        state is ChargeState.Preview
}

enum class ChargeFailureType {
    NETWORK,
    TIMEOUT,
    RATE_LIMITED,
    PROPOSAL_INVALID,
    SERVER,
}

class ChargeStateMachine(
    initialState: ChargeState = ChargeState.Ready(),
) {
    var state: ChargeState = initialState
        private set

    fun beginListening(): ChargeState {
        if (state is ChargeState.Ready || state is ChargeState.Clarification) {
            state = ChargeState.Listening
        }
        return state
    }

    fun beginInterpretation(): ChargeState {
        if (state is ChargeState.Listening) state = ChargeState.Interpreting
        return state
    }

    fun clarification(question: String): ChargeState {
        if (state is ChargeState.Interpreting) state = ChargeState.Clarification(question)
        return state
    }

    fun preview(proposal: ChargeProposal, remainingSeconds: Long): ChargeState {
        if (state is ChargeState.Interpreting) {
            state = ChargeState.Preview(proposal, remainingSeconds)
        }
        return state
    }

    fun previewMessage(message: String): ChargeState {
        val current = state as? ChargeState.Preview ?: return state
        state = current.copy(message = message)
        return state
    }

    fun tick(remainingSeconds: Long): ChargeState {
        val current = state as? ChargeState.Preview ?: return state
        state = if (remainingSeconds <= 0) {
            ChargeState.Ready("La propuesta venció. Dicta el cobro otra vez.")
        } else {
            current.copy(remainingSeconds = remainingSeconds)
        }
        return state
    }

    fun beginConfirmation(): ChargeState {
        val current = state as? ChargeState.Preview ?: return state
        if (current.remainingSeconds > 0) state = ChargeState.Confirming
        return state
    }

    fun created(requestId: String, statusToken: String): ChargeState {
        if (state is ChargeState.Confirming) state = ChargeState.Created(requestId, statusToken)
        return state
    }

    fun paymentUpdated(tracking: PaymentTracking): ChargeState {
        val current = state as? ChargeState.Created ?: return state
        state = current.copy(payment = tracking)
        return state
    }

    fun failure(type: ChargeFailureType): ChargeState {
        state = when (type) {
            ChargeFailureType.NETWORK,
            ChargeFailureType.TIMEOUT -> ChargeState.Error(
                "No pudimos interpretar. Revisa tu conexión e inténtalo de nuevo.",
                recoverable = true,
            )
            ChargeFailureType.RATE_LIMITED -> ChargeState.Error(
                "Demasiados intentos. Espera unos segundos.",
                recoverable = true,
            )
            ChargeFailureType.PROPOSAL_INVALID -> ChargeState.Ready(
                "La propuesta ya venció o no está disponible. Dicta el cobro otra vez.",
            )
            ChargeFailureType.SERVER -> ChargeState.Error(
                "GlassPay no pudo procesar la solicitud.",
                recoverable = true,
            )
        }
        return state
    }

    fun confirmationFailure(): ChargeState {
        state = ChargeState.Error(
            "No pudimos confirmar. Revisa con el pagador antes de intentarlo de nuevo",
            recoverable = false,
        )
        return state
    }

    fun cancel(): ChargeState {
        state = ChargeState.Ready("Cobro cancelado.")
        return state
    }
}
