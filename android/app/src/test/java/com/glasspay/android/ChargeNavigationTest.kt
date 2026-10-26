package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeNavigationTest {
    private val proposal = ChargeProposal("proposal", "350.00", "MXN", "cena", "2099-01-01T00:00:00Z")

    private fun previewMachine(remaining: Long = 120): ChargeStateMachine {
        val machine = ChargeStateMachine()
        machine.beginListening()
        machine.beginInterpretation()
        machine.preview(proposal, remaining)
        return machine
    }

    private fun createdMachine(): ChargeStateMachine {
        val machine = previewMachine()
        machine.beginConfirmation()
        machine.created("request", "status")
        return machine
    }

    // availableActions

    @Test
    fun readyOffersDictateAndScanAnother() {
        assertEquals(
            setOf(ChargeAction.DICTATE, ChargeAction.SCAN_ANOTHER),
            availableActions(ChargeState.Ready()),
        )
    }

    @Test
    fun listeningOffersNoActions() {
        assertTrue(availableActions(ChargeState.Listening).isEmpty())
    }

    @Test
    fun interpretingOffersNoActions() {
        assertTrue(availableActions(ChargeState.Interpreting).isEmpty())
    }

    @Test
    fun confirmingOffersNoActions() {
        assertTrue(availableActions(ChargeState.Confirming).isEmpty())
    }

    @Test
    fun clarificationOffersAnswerStartOverScanAnother() {
        assertEquals(
            setOf(ChargeAction.ANSWER, ChargeAction.START_OVER, ChargeAction.SCAN_ANOTHER),
            availableActions(ChargeState.Clarification("¿Cuánto?")),
        )
    }

    @Test
    fun validPreviewOffersConfirmCancelStartOverBack() {
        assertEquals(
            setOf(ChargeAction.CONFIRM, ChargeAction.CANCEL, ChargeAction.START_OVER, ChargeAction.BACK),
            availableActions(ChargeState.Preview(proposal, 30)),
        )
    }

    @Test
    fun expiredPreviewOffersOnlyBack() {
        assertEquals(setOf(ChargeAction.BACK), availableActions(ChargeState.Preview(proposal, 0)))
    }

    @Test
    fun createdWaitingOffersOnlyStopWaiting() {
        val actions = availableActions(ChargeState.Created("r", "s"))
        assertEquals(setOf(ChargeAction.STOP_WAITING), actions)
        assertFalse("SCAN_ANOTHER no debe estar mientras espera", ChargeAction.SCAN_ANOTHER in actions)
    }

    @Test
    fun createdWaitingWithMessageOffersOnlyStopWaiting() {
        val actions = availableActions(
            ChargeState.Created("r", "s", PaymentTracking.WaitingWithMessage("Pago en proceso")),
        )
        assertEquals(setOf(ChargeAction.STOP_WAITING), actions)
    }

    @Test
    fun createdTerminalStatesOfferOnlyScanAnother() {
        listOf(
            PaymentTracking.Paid,
            PaymentTracking.Rejected,
            PaymentTracking.Expired,
            PaymentTracking.Failed,
            PaymentTracking.Error("x"),
        ).forEach { tracking ->
            assertEquals(
                "Estado terminal $tracking",
                setOf(ChargeAction.SCAN_ANOTHER),
                availableActions(ChargeState.Created("r", "s", tracking)),
            )
        }
    }

    @Test
    fun recoverableErrorOffersRetryAndScanAnother() {
        assertEquals(
            setOf(ChargeAction.RETRY, ChargeAction.SCAN_ANOTHER),
            availableActions(ChargeState.Error("e", recoverable = true)),
        )
    }

    @Test
    fun nonRecoverableErrorOffersOnlyScanAnother() {
        assertEquals(
            setOf(ChargeAction.SCAN_ANOTHER),
            availableActions(ChargeState.Error("e", recoverable = false)),
        )
    }

    // transiciones

    @Test
    fun backFromPreviewGoesToReadyWithoutMessage() {
        val machine = previewMachine()
        machine.back()
        assertEquals(ChargeState.Ready(null), machine.state)
    }

    @Test
    fun backFromCreatedDoesNotChangeState() {
        val machine = createdMachine()
        val before = machine.state
        machine.back()
        assertEquals(before, machine.state)
    }

    @Test
    fun backFromConfirmingDoesNotChangeState() {
        val machine = previewMachine()
        machine.beginConfirmation()
        machine.back()
        assertEquals(ChargeState.Confirming, machine.state)
    }

    @Test
    fun startOverFromPreviewGoesToReadyAndDoesNotResurrectProposal() {
        val machine = previewMachine()
        machine.startOver()
        assertEquals(ChargeState.Ready(), machine.state)
        machine.beginListening()
        assertEquals(ChargeState.Listening, machine.state)
    }

    @Test
    fun startOverFromRecoverableErrorGoesToReadyThenListening() {
        val machine = ChargeStateMachine(ChargeState.Error("e", recoverable = true))
        machine.startOver()
        assertEquals(ChargeState.Ready(), machine.state)
        machine.beginListening()
        assertEquals(ChargeState.Listening, machine.state)
    }

    @Test
    fun stopWaitingFromCreatedWaitingGoesToReadyWithStopMessage() {
        val machine = createdMachine()
        machine.stopWaiting()
        assertEquals(ChargeState.Ready(STOP_WAITING_MESSAGE), machine.state)
    }

    @Test
    fun stopWaitingFromCreatedPaidDoesNotChangeState() {
        val machine = createdMachine()
        machine.paymentUpdated(PaymentTracking.Paid)
        val before = machine.state
        machine.stopWaiting()
        assertEquals(before, machine.state)
    }

    @Test
    fun stopWaitingMessageDoesNotClaimCancellation() {
        assertFalse(STOP_WAITING_MESSAGE.contains("cancel", ignoreCase = true))
    }

    @Test
    fun stopWaitingMessageSaysPayerCanStillApprove() {
        assertTrue(STOP_WAITING_MESSAGE.contains("todavía"))
    }

    @Test
    fun resetForNewScanFromCreatedGoesToReadyAndListeningDoesNotRestoreCreated() {
        val machine = createdMachine()
        machine.resetForNewScan()
        assertEquals(ChargeState.Ready(null), machine.state)
        machine.beginListening()
        assertEquals(ChargeState.Listening, machine.state)
    }

    @Test
    fun resetForNewScanFromPreviewGoesToReady() {
        val machine = previewMachine()
        machine.resetForNewScan()
        assertTrue(machine.state is ChargeState.Ready)
    }

    @Test
    fun resetForNewScanKeepsProvidedMessage() {
        val machine = previewMachine()
        machine.resetForNewScan("La resolución venció. Escanea otro QR.")
        assertEquals(ChargeState.Ready("La resolución venció. Escanea otro QR."), machine.state)
    }

    @Test
    fun inputErrorFromListeningBecomesRecoverableError() {
        val machine = ChargeStateMachine()
        machine.beginListening()
        machine.inputError("Fallo de voz")
        assertEquals(ChargeState.Error("Fallo de voz", recoverable = true), machine.state)
    }

    @Test
    fun inputErrorFromConfirmingDoesNotChangeState() {
        val machine = previewMachine()
        machine.beginConfirmation()
        machine.inputError("Fallo de voz")
        assertEquals(ChargeState.Confirming, machine.state)
    }

    @Test
    fun inputErrorFromCreatedDoesNotChangeState() {
        val machine = createdMachine()
        val before = machine.state
        machine.inputError("Fallo de voz")
        assertEquals(before, machine.state)
    }

    @Test
    fun restartInputFromListeningGoesToReadyWithMessage() {
        val machine = ChargeStateMachine()
        machine.beginListening()
        machine.restartInput("No te entendí")
        assertEquals(ChargeState.Ready("No te entendí"), machine.state)
    }

    @Test
    fun paymentUpdatedAfterStopWaitingDoesNotChangeState() {
        val machine = createdMachine()
        machine.stopWaiting()
        val before = machine.state
        machine.paymentUpdated(PaymentTracking.Paid)
        assertEquals(before, machine.state)
    }

    // showsRecognizedText / showsAccumulatedText

    private val previewState get() = ChargeState.Preview(proposal, 30)
    private val createdState get() = ChargeState.Created("r", "s")

    @Test
    fun recognizedTextShownInInterpreting() = assertTrue(showsRecognizedText(ChargeState.Interpreting))

    @Test
    fun recognizedTextShownInClarification() = assertTrue(showsRecognizedText(ChargeState.Clarification("q")))

    @Test
    fun recognizedTextHiddenInReady() = assertFalse(showsRecognizedText(ChargeState.Ready()))

    @Test
    fun recognizedTextHiddenInReadyWithMessage() = assertFalse(showsRecognizedText(ChargeState.Ready("msg")))

    @Test
    fun recognizedTextHiddenInListening() = assertFalse(showsRecognizedText(ChargeState.Listening))

    @Test
    fun recognizedTextHiddenInPreview() = assertFalse(showsRecognizedText(previewState))

    @Test
    fun recognizedTextHiddenInConfirming() = assertFalse(showsRecognizedText(ChargeState.Confirming))

    @Test
    fun recognizedTextHiddenInCreated() = assertFalse(showsRecognizedText(createdState))

    @Test
    fun recognizedTextHiddenInError() = assertFalse(showsRecognizedText(ChargeState.Error("e", true)))

    @Test
    fun accumulatedTextShownInClarification() = assertTrue(showsAccumulatedText(ChargeState.Clarification("q")))

    @Test
    fun accumulatedTextHiddenInInterpreting() = assertFalse(showsAccumulatedText(ChargeState.Interpreting))

    @Test
    fun accumulatedTextHiddenInPreview() = assertFalse(showsAccumulatedText(previewState))

    @Test
    fun accumulatedTextHiddenInCreated() = assertFalse(showsAccumulatedText(createdState))

    @Test
    fun accumulatedTextHiddenInReady() = assertFalse(showsAccumulatedText(ChargeState.Ready()))

    // paymentStep / Approved

    @Test
    fun stepWaitingIsAwaitingPayer() = assertEquals(PaymentStep.AWAITING_PAYER, paymentStep(PaymentTracking.Waiting))

    @Test
    fun stepWaitingWithMessageIsAwaitingPayer() =
        assertEquals(PaymentStep.AWAITING_PAYER, paymentStep(PaymentTracking.WaitingWithMessage("m")))

    @Test
    fun stepApprovedIsConfirming() = assertEquals(PaymentStep.CONFIRMING, paymentStep(PaymentTracking.Approved))

    @Test
    fun stepPaidIsPaid() = assertEquals(PaymentStep.PAID, paymentStep(PaymentTracking.Paid))

    @Test
    fun stepRejectedIsStopped() = assertEquals(PaymentStep.STOPPED, paymentStep(PaymentTracking.Rejected))

    @Test
    fun stepExpiredIsStopped() = assertEquals(PaymentStep.STOPPED, paymentStep(PaymentTracking.Expired))

    @Test
    fun stepFailedIsStopped() = assertEquals(PaymentStep.STOPPED, paymentStep(PaymentTracking.Failed))

    @Test
    fun stepErrorIsStopped() = assertEquals(PaymentStep.STOPPED, paymentStep(PaymentTracking.Error("x")))

    @Test
    fun onlyPaidTrackingMapsToPaidStep() {
        listOf(
            PaymentTracking.Waiting,
            PaymentTracking.WaitingWithMessage("m"),
            PaymentTracking.Approved,
            PaymentTracking.Rejected,
            PaymentTracking.Expired,
            PaymentTracking.Failed,
            PaymentTracking.Error("x"),
        ).forEach { assertNotEquals("$it no debe ser PAID", PaymentStep.PAID, paymentStep(it)) }
    }

    @Test
    fun approvedIsNotTerminal() = assertFalse(isTerminal(PaymentTracking.Approved))

    @Test
    fun createdApprovedOffersOnlyStopWaiting() {
        assertEquals(
            setOf(ChargeAction.STOP_WAITING),
            availableActions(ChargeState.Created("r", "s", PaymentTracking.Approved)),
        )
    }

    @Test
    fun tabSwitchBlockedWhileCreatedApproved() {
        val identified = ResolveState.Identified("Ana", "res", "exp")
        assertFalse(allowsTabSwitch(identified, ChargeState.Created("r", "s", PaymentTracking.Approved)))
    }

    @Test
    fun paymentUpdatedAppliesWaitingApprovedPaidInOrder() {
        val machine = createdMachine()
        machine.paymentUpdated(PaymentTracking.Waiting)
        assertEquals(PaymentTracking.Waiting, (machine.state as ChargeState.Created).payment)
        machine.paymentUpdated(PaymentTracking.Approved)
        assertEquals(PaymentTracking.Approved, (machine.state as ChargeState.Created).payment)
        machine.paymentUpdated(PaymentTracking.Paid)
        assertEquals(PaymentTracking.Paid, (machine.state as ChargeState.Created).payment)
    }

    // paymentTrackingFor / stopWaiting Approved

    @Test
    fun trackingForPendienteIsWaiting() =
        assertEquals(PaymentTracking.Waiting, paymentTrackingFor("pendiente_de_pagador"))

    @Test
    fun trackingForCreadaIsError() = assertTrue(paymentTrackingFor("creada") is PaymentTracking.Error)

    @Test
    fun trackingForEnviadaIsError() = assertTrue(paymentTrackingFor("enviada") is PaymentTracking.Error)

    @Test
    fun trackingForAprobadaIsApproved() = assertEquals(PaymentTracking.Approved, paymentTrackingFor("aprobada"))

    @Test
    fun trackingForPagadaIsPaid() = assertEquals(PaymentTracking.Paid, paymentTrackingFor("pagada"))

    @Test
    fun trackingForRechazadaIsRejected() = assertEquals(PaymentTracking.Rejected, paymentTrackingFor("rechazada"))

    @Test
    fun trackingForExpiradaIsExpired() = assertEquals(PaymentTracking.Expired, paymentTrackingFor("expirada"))

    @Test
    fun trackingForFallidaIsFailed() = assertEquals(PaymentTracking.Failed, paymentTrackingFor("fallida"))

    @Test
    fun trackingForUnknownIsErrorAndNeverPaid() {
        val result = paymentTrackingFor("valor_raro")
        assertTrue(result is PaymentTracking.Error)
        assertNotEquals(PaymentTracking.Paid, result)
    }

    @Test
    fun stopWaitingFromCreatedApprovedUsesApprovedMessage() {
        val machine = createdMachine()
        machine.paymentUpdated(PaymentTracking.Approved)
        machine.stopWaiting()
        assertEquals(ChargeState.Ready(STOP_WAITING_APPROVED_MESSAGE), machine.state)
    }

    @Test
    fun stopWaitingApprovedMessageDoesNotClaimCancellation() {
        assertFalse(STOP_WAITING_APPROVED_MESSAGE.contains("cancel", ignoreCase = true))
    }

    @Test
    fun stopWaitingFromCreatedWaitingStillUsesStopMessage() {
        val machine = createdMachine()
        machine.paymentUpdated(PaymentTracking.Waiting)
        machine.stopWaiting()
        assertEquals(ChargeState.Ready(STOP_WAITING_MESSAGE), machine.state)
    }

    // showsTextInput

    @Test
    fun textInputShownInReady() = assertTrue(showsTextInput(ChargeState.Ready()))

    @Test
    fun textInputShownInListening() = assertTrue(showsTextInput(ChargeState.Listening))

    @Test
    fun textInputShownInClarification() = assertTrue(showsTextInput(ChargeState.Clarification("q")))

    @Test
    fun textInputHiddenInInterpreting() = assertFalse(showsTextInput(ChargeState.Interpreting))

    @Test
    fun textInputHiddenInPreview() = assertFalse(showsTextInput(previewState))

    @Test
    fun textInputHiddenInConfirming() = assertFalse(showsTextInput(ChargeState.Confirming))

    @Test
    fun textInputHiddenInCreated() = assertFalse(showsTextInput(createdState))

    @Test
    fun textInputHiddenInError() = assertFalse(showsTextInput(ChargeState.Error("e", true)))

    // casos borde de voiceCommand (A.1)

    @Test
    fun emptyTextInPreviewIsNoCommand() {
        assertEquals(VoiceCommand.NONE, voiceCommand("", ChargeState.Preview(proposal, 30)))
    }

    @Test
    fun onlyPunctuationInPreviewIsNoCommand() {
        assertEquals(VoiceCommand.NONE, voiceCommand("...", ChargeState.Preview(proposal, 30)))
    }

    @Test
    fun siCommaConfirmarInPreviewIsNoCommand() {
        assertEquals(VoiceCommand.NONE, voiceCommand("sí, confirmar", ChargeState.Preview(proposal, 30)))
    }

    @Test
    fun confirmarPorFavorInPreviewIsNoCommand() {
        assertEquals(VoiceCommand.NONE, voiceCommand("confirmar por favor", ChargeState.Preview(proposal, 30)))
    }
}
