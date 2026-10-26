package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransfersTest {
    // parseAmountInput

    @Test
    fun amountIntegerGetsTwoDecimals() = assertEquals("100.00", parseAmountInput("100"))

    @Test
    fun amountOneDecimalIsPadded() = assertEquals("100.50", parseAmountInput("100.5"))

    @Test
    fun amountCommaDecimalIsAccepted() = assertEquals("100.50", parseAmountInput("100,50"))

    @Test
    fun amountIsTrimmed() = assertEquals("25.00", parseAmountInput(" 25 "))

    @Test
    fun amountAtMaximumIsAccepted() = assertEquals("1000.00", parseAmountInput("1000"))

    @Test
    fun amountEmptyIsNull() = assertNull(parseAmountInput(""))

    @Test
    fun amountZeroIsNull() = assertNull(parseAmountInput("0"))

    @Test
    fun amountNegativeIsNull() = assertNull(parseAmountInput("-5"))

    @Test
    fun amountAboveMaximumIsNull() = assertNull(parseAmountInput("1000.01"))

    @Test
    fun amountWithThreeDecimalsIsNull() = assertNull(parseAmountInput("10.123"))

    @Test
    fun amountNonNumericIsNull() = assertNull(parseAmountInput("abc"))

    @Test
    fun amountScientificNotationIsNull() = assertNull(parseAmountInput("1e3"))

    // "1,000" se normaliza a "1.000" (3 decimales) y se rechaza: devuelve null, nunca un valor mayor.
    @Test
    fun amountThousandsSeparatorIsNull() = assertNull(parseAmountInput("1,000"))

    // isValidPayoutEmail

    @Test
    fun validEmailIsAccepted() = assertTrue(isValidPayoutEmail("luis@example.com"))

    @Test
    fun emptyEmailIsRejected() = assertFalse(isValidPayoutEmail(""))

    @Test
    fun emailWithoutAtIsRejected() = assertFalse(isValidPayoutEmail("luis"))

    @Test
    fun emailWithoutDomainIsRejected() = assertFalse(isValidPayoutEmail("luis@"))

    @Test
    fun emailWithoutLocalPartIsRejected() = assertFalse(isValidPayoutEmail("@example.com"))

    @Test
    fun emailWithSpaceIsRejected() = assertFalse(isValidPayoutEmail("luis example@x.com"))

    // El código permite hasta 254 caracteres; se prueba uno de 300.
    @Test
    fun overlongEmailIsRejected() = assertFalse(isValidPayoutEmail("a".repeat(300) + "@example.com"))

    // transferErrorMessage

    @Test
    fun conflictRecipientCannotReceiveMentionsName() {
        assertEquals(
            "Luis todavía no registró su correo para recibir envíos.",
            transferErrorMessage(ApiFailureType.CONFLICT, "recipient cannot receive", "Luis"),
        )
    }

    @Test
    fun conflictInsufficientBalance() {
        assertEquals(
            "No tienes saldo suficiente.",
            transferErrorMessage(ApiFailureType.CONFLICT, "insufficient balance", "Luis"),
        )
    }

    @Test
    fun badRequestMentionsMaximum() {
        assertEquals(
            "Revisa el monto (máximo 1,000 MXN).",
            transferErrorMessage(ApiFailureType.BAD_REQUEST, null, null),
        )
    }

    @Test
    fun notFoundMeansPreviewExpired() {
        assertEquals(
            "La vista previa venció. Revísala de nuevo.",
            transferErrorMessage(ApiFailureType.NOT_FOUND, null, null),
        )
    }

    @Test
    fun rateLimitedMessage() {
        assertEquals(
            "Demasiados intentos. Espera unos segundos.",
            transferErrorMessage(ApiFailureType.RATE_LIMITED, null, null),
        )
    }

    private val genericMessage =
        "No pudimos confirmar el envío. Revisa Movimientos antes de intentarlo de nuevo."

    @Test
    fun networkUsesGenericMessage() =
        assertEquals(genericMessage, transferErrorMessage(ApiFailureType.NETWORK, null, null))

    @Test
    fun timeoutUsesGenericMessage() =
        assertEquals(genericMessage, transferErrorMessage(ApiFailureType.TIMEOUT, null, null))

    @Test
    fun serverUsesGenericMessage() =
        assertEquals(genericMessage, transferErrorMessage(ApiFailureType.SERVER, null, null))

    // movementStatus con kind

    @Test
    fun transferProcessingLabelAndKind() {
        assertEquals("Enviando", movementStatusLabel("procesando", "envio"))
        assertEquals(StatusKind.PENDING, movementStatusKind("procesando", "envio"))
    }

    @Test
    fun transferCompletedLabelAndKind() {
        assertEquals("Enviado", movementStatusLabel("completada", "envio"))
        assertEquals(StatusKind.SUCCESS, movementStatusKind("completada", "envio"))
    }

    @Test
    fun transferUnclaimedLabelAndKind() {
        assertEquals("Sin reclamar", movementStatusLabel("no_reclamada", "envio"))
        assertEquals(StatusKind.PENDING, movementStatusKind("no_reclamada", "envio"))
    }

    @Test
    fun transferFailedLabelAndKind() {
        assertEquals("Falló", movementStatusLabel("fallida", "envio"))
        assertEquals(StatusKind.FAILURE, movementStatusKind("fallida", "envio"))
    }

    @Test
    fun chargeStatusesUnchangedWithoutKind() {
        assertEquals("Pagado", movementStatusLabel("pagada"))
        assertEquals(StatusKind.SUCCESS, movementStatusKind("pagada"))
        assertEquals("Esperando al pagador", movementStatusLabel("pendiente_de_pagador"))
        assertEquals(StatusKind.PENDING, movementStatusKind("pendiente_de_pagador"))
        assertEquals("Rechazado", movementStatusLabel("rechazada"))
        assertEquals(StatusKind.FAILURE, movementStatusKind("rechazada"))
    }
}
