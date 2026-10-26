package com.glasspay.android

import java.math.BigDecimal

const val MAX_TRANSFER_AMOUNT = 1000.0
const val MAX_TRANSFER_NOTE_LENGTH = 127

private val AMOUNT_PATTERN = Regex("^\\d+(\\.\\d{1,2})?$")
private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** Devuelve el monto normalizado con 2 decimales ("100.00") o null si no es válido. */
fun parseAmountInput(text: String, max: Double = MAX_TRANSFER_AMOUNT): String? {
    val normalized = text.trim().replace(',', '.')
    if (!AMOUNT_PATTERN.matches(normalized)) return null
    val value = try {
        BigDecimal(normalized)
    } catch (_: NumberFormatException) {
        return null
    }
    if (value.signum() <= 0 || value > BigDecimal(max.toString())) return null
    return value.setScale(2).toPlainString()
}

fun isValidPayoutEmail(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.length <= 127 && EMAIL_PATTERN.matches(trimmed)
}

fun transferErrorMessage(type: ApiFailureType, serverError: String?, recipientName: String?): String {
    return when (type) {
        ApiFailureType.CONFLICT -> when (serverError) {
            "recipient cannot receive" ->
                "${recipientName ?: "El destinatario"} todavía no registró su correo para recibir envíos."
            "insufficient balance" -> "No tienes saldo suficiente."
            else -> "No se pudo completar el envío."
        }
        ApiFailureType.BAD_REQUEST -> "Revisa el monto (máximo 1,000 MXN)."
        ApiFailureType.NOT_FOUND -> "La vista previa venció. Revísala de nuevo."
        ApiFailureType.RATE_LIMITED -> "Demasiados intentos. Espera unos segundos."
        else -> "No pudimos confirmar el envío. Revisa Movimientos antes de intentarlo de nuevo."
    }
}
