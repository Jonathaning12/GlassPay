package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateTest {
    private val proposal = ChargeProposal("p", "350.00", "MXN", "cena", "2099-01-01T00:00:00Z")
    private val identified = ResolveState.Identified("Ana", "res", "2099-01-01T00:00:00Z")

    private fun created(tracking: PaymentTracking) = ChargeState.Created("r", "s", tracking)

    // allowsTabSwitch

    @Test
    fun tabSwitchAllowedWhenScanningWithAnyCharge() {
        assertTrue(allowsTabSwitch(ResolveState.Scanning, ChargeState.Listening))
        assertTrue(allowsTabSwitch(ResolveState.Scanning, ChargeState.Confirming))
    }

    @Test
    fun tabSwitchBlockedWhenResolving() {
        assertFalse(allowsTabSwitch(ResolveState.Resolving(1, "t", false), ChargeState.Ready()))
    }

    @Test
    fun tabSwitchAllowedOnRetryableResolveError() {
        assertTrue(allowsTabSwitch(ResolveState.RetryableError(1, "t", "m"), ChargeState.Listening))
    }

    @Test
    fun tabSwitchAllowedOnFailedResolve() {
        assertTrue(allowsTabSwitch(ResolveState.Failed("m"), ChargeState.Listening))
    }

    @Test
    fun tabSwitchAllowedIdentifiedReady() = assertTrue(allowsTabSwitch(identified, ChargeState.Ready()))

    @Test
    fun tabSwitchAllowedIdentifiedReadyWithMessage() =
        assertTrue(allowsTabSwitch(identified, ChargeState.Ready("msg")))

    @Test
    fun tabSwitchAllowedIdentifiedRecoverableError() =
        assertTrue(allowsTabSwitch(identified, ChargeState.Error("e", true)))

    @Test
    fun tabSwitchBlockedOnNonRecoverableError() =
        assertFalse(allowsTabSwitch(identified, ChargeState.Error("e", false)))

    @Test
    fun tabSwitchAllowedIdentifiedCreatedPaid() =
        assertTrue(allowsTabSwitch(identified, created(PaymentTracking.Paid)))

    @Test
    fun tabSwitchAllowedIdentifiedCreatedRejected() =
        assertTrue(allowsTabSwitch(identified, created(PaymentTracking.Rejected)))

    @Test
    fun tabSwitchAllowedIdentifiedCreatedExpired() =
        assertTrue(allowsTabSwitch(identified, created(PaymentTracking.Expired)))

    @Test
    fun tabSwitchAllowedIdentifiedCreatedFailed() =
        assertTrue(allowsTabSwitch(identified, created(PaymentTracking.Failed)))

    @Test
    fun tabSwitchAllowedIdentifiedCreatedError() =
        assertTrue(allowsTabSwitch(identified, created(PaymentTracking.Error("x"))))

    @Test
    fun tabSwitchBlockedIdentifiedListening() = assertFalse(allowsTabSwitch(identified, ChargeState.Listening))

    @Test
    fun tabSwitchBlockedIdentifiedInterpreting() = assertFalse(allowsTabSwitch(identified, ChargeState.Interpreting))

    @Test
    fun tabSwitchBlockedIdentifiedClarification() =
        assertFalse(allowsTabSwitch(identified, ChargeState.Clarification("q")))

    @Test
    fun tabSwitchBlockedIdentifiedPreview() =
        assertFalse(allowsTabSwitch(identified, ChargeState.Preview(proposal, 30)))

    @Test
    fun tabSwitchBlockedIdentifiedConfirming() = assertFalse(allowsTabSwitch(identified, ChargeState.Confirming))

    @Test
    fun tabSwitchBlockedIdentifiedCreatedWaiting() =
        assertFalse(allowsTabSwitch(identified, created(PaymentTracking.Waiting)))

    @Test
    fun tabSwitchBlockedIdentifiedCreatedWaitingWithMessage() =
        assertFalse(allowsTabSwitch(identified, created(PaymentTracking.WaitingWithMessage("m"))))

    // movementStatusLabel

    @Test
    fun labelPagada() = assertEquals("Pagado", movementStatusLabel("pagada"))

    @Test
    fun labelAprobada() = assertEquals("Procesando", movementStatusLabel("aprobada"))

    @Test
    fun labelEnviada() = assertEquals("Procesando", movementStatusLabel("enviada"))

    @Test
    fun labelPendienteDePagador() = assertEquals("Esperando al pagador", movementStatusLabel("pendiente_de_pagador"))

    @Test
    fun labelCreada() = assertEquals("Esperando al pagador", movementStatusLabel("creada"))

    @Test
    fun labelRechazada() = assertEquals("Rechazado", movementStatusLabel("rechazada"))

    @Test
    fun labelExpirada() = assertEquals("Expirado", movementStatusLabel("expirada"))

    @Test
    fun labelFallida() = assertEquals("Falló", movementStatusLabel("fallida"))

    @Test
    fun labelUnknown() = assertEquals("Desconocido", movementStatusLabel("otra_cosa"))

    // movementStatusKind

    @Test
    fun kindPagadaIsSuccess() = assertEquals(StatusKind.SUCCESS, movementStatusKind("pagada"))

    @Test
    fun kindPendienteIsPending() = assertEquals(StatusKind.PENDING, movementStatusKind("pendiente_de_pagador"))

    @Test
    fun kindAprobadaIsPending() = assertEquals(StatusKind.PENDING, movementStatusKind("aprobada"))

    @Test
    fun kindRechazadaIsFailure() = assertEquals(StatusKind.FAILURE, movementStatusKind("rechazada"))

    @Test
    fun kindExpiradaIsFailure() = assertEquals(StatusKind.FAILURE, movementStatusKind("expirada"))

    @Test
    fun kindFallidaIsFailure() = assertEquals(StatusKind.FAILURE, movementStatusKind("fallida"))

    @Test
    fun kindUnknownIsNeutral() = assertEquals(StatusKind.NEUTRAL, movementStatusKind("otra_cosa"))

    // formatMoney

    @Test
    fun moneyKeepsTwoDecimals() = assertEquals("\$150.00 MXN", formatMoney("150.00", "MXN"))

    @Test
    fun moneyAddsDecimalsToIntegers() = assertEquals("\$150.00 MXN", formatMoney("150", "MXN"))

    @Test
    fun moneyPadsSingleDecimalWithoutGroupingThousands() =
        assertEquals("\$1200.50 MXN", formatMoney("1200.5", "MXN"))

    @Test
    fun moneyNonNumericHasNoDollarSign() {
        val result = formatMoney("abc", "MXN")
        assertFalse(result.contains("$"))
        assertEquals("abc MXN", result)
    }

    // formatMovementDate

    @Test
    fun validIsoDateIsFormatted() {
        val iso = "2026-10-25T18:30:00Z"
        val result = formatMovementDate(iso)
        assertTrue(result.isNotEmpty())
        assertNotEquals(iso, result)
    }

    @Test
    fun invalidDateReturnsOriginalText() = assertEquals("no-fecha", formatMovementDate("no-fecha"))

    @Test
    fun failedChargesCanBeHidden() {
        assertTrue(canHideMovement("expirada"))
        assertTrue(canHideMovement("rechazada"))
        assertTrue(canHideMovement("fallida"))
    }

    @Test
    fun paidOrInProgressChargesCannotBeHidden() {
        listOf("pagada", "aprobada", "enviada", "pendiente_de_pagador", "creada").forEach {
            assertFalse(it, canHideMovement(it))
        }
    }

    @Test
    fun onlyFailedTransfersCanBeHidden() {
        assertTrue(canHideMovement("fallida", "envio"))
        listOf("procesando", "completada", "no_reclamada").forEach { assertFalse(it, canHideMovement(it, "envio")) }
    }
}
