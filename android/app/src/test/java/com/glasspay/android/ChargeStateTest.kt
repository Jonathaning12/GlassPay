package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeStateTest {
    private val proposal = ChargeProposal("proposal", "350.00", "MXN", "cena", "2099-01-01T00:00:00Z")

    @Test
    fun previewRequiresExplicitConfirmationAndExpires() {
        val machine = ChargeStateMachine()
        machine.beginListening()
        machine.beginInterpretation()
        machine.preview(proposal, 120)
        assertTrue(machine.state is ChargeState.Preview)
        machine.tick(0)
        assertTrue(machine.state is ChargeState.Ready)
        machine.beginListening()
        machine.beginInterpretation()
        machine.preview(proposal, 120)
        machine.beginConfirmation()
        assertTrue(machine.state is ChargeState.Confirming)
    }

    @Test
    fun confirmationFailureIsNotRecoverable() {
        val machine = ChargeStateMachine()
        machine.beginListening()
        machine.beginInterpretation()
        machine.preview(proposal, 120)
        machine.beginConfirmation()
        machine.confirmationFailure()
        assertEquals(false, (machine.state as ChargeState.Error).recoverable)
    }

    @Test
    fun exactConfirmationPhrasesOnly() {
        assertTrue(isConfirmation("confirmar"))
        assertTrue(isConfirmation("sí"))
        assertTrue(!isConfirmation("sí, confirmo"))
        assertTrue(isCancellation("no"))
        assertTrue(isCancellation("cancelar"))
    }

    @Test
    fun voiceErrorsHaveSpecificUserMessages() {
        assertTrue(voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            .contains("Permiso"))
        assertTrue(voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            .contains("ocupado"))
        assertTrue(voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
            .contains("entendí"))
        assertTrue(voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            .contains("voz"))
    }

    @Test
    fun voiceErrorCannotReplaceCreatedPaymentState() {
        assertTrue(!shouldAcceptVoiceError(ChargeState.Confirming))
        assertTrue(!shouldAcceptVoiceError(ChargeState.Created("request", "status")))
        assertTrue(shouldAcceptVoiceError(ChargeState.Listening))
        assertTrue(shouldAcceptVoiceError(ChargeState.Preview(proposal, 30)))
    }

    @Test
    fun paymentStatusCanReachPaidOnlyThroughCreatedState() {
        val machine = ChargeStateMachine()
        machine.beginListening()
        machine.beginInterpretation()
        machine.preview(proposal, 120)
        machine.beginConfirmation()
        machine.created("request", "status")
        machine.paymentUpdated(PaymentTracking.Paid)
        assertEquals(PaymentTracking.Paid, (machine.state as ChargeState.Created).payment)
    }

    @Test
    fun paymentStatusTerminalStatesAreRepresented() {
        val machine = ChargeStateMachine(ChargeState.Created("request", "status"))
        machine.paymentUpdated(PaymentTracking.Rejected)
        assertEquals(PaymentTracking.Rejected, (machine.state as ChargeState.Created).payment)
    }

    @Test
    fun initialValidInterpretationSendsOnlyTheOriginalText() {
        val session = ChargeInputSession.start("resolution", "cóbrale 350 pesos por la cena")
        assertEquals("cóbrale 350 pesos por la cena", session?.text)
        assertEquals(0, session?.clarificationRounds)
    }

    @Test
    fun clarificationCompletesMissingAmountByConcatenatingText() {
        val initial = ChargeInputSession.start("resolution", "cóbrale por la cena")
        val result = requireNotNull(initial).appendClarification("350 pesos")
        val session = (result as ChargeInputAppendResult.Accepted).session
        assertEquals("cóbrale por la cena 350 pesos", session.text)
        assertEquals(1, session.clarificationRounds)
    }

    @Test
    fun clarificationCompletesMissingConceptByConcatenatingText() {
        val initial = ChargeInputSession.start("resolution", "cóbrale 350 pesos")
        val result = requireNotNull(initial).appendClarification("por la cena")
        val session = (result as ChargeInputAppendResult.Accepted).session
        assertEquals("cóbrale 350 pesos por la cena", session.text)
    }

    @Test
    fun clarificationSessionIsBoundToOneResolution() {
        val session = requireNotNull(ChargeInputSession.start("resolution-a", "cóbrale por cena"))
        assertTrue(session.resolutionId != "resolution-b")
    }

    @Test
    fun clarificationStopsAfterThreeRounds() {
        var session = requireNotNull(ChargeInputSession.start("resolution", "cóbrale por la cena"))
        repeat(3) {
            session = (session.appendClarification("$it  pesos") as ChargeInputAppendResult.Accepted).session
        }
        assertTrue(session.appendClarification("otro dato") is ChargeInputAppendResult.TooManyRounds)
    }

    @Test
    fun clarificationRejectsTextOverAndroidLimit() {
        val session = requireNotNull(ChargeInputSession.start("resolution", "cóbrale por la cena"))
        assertTrue(
            session.appendClarification("x".repeat(MAX_ACCUMULATED_TEXT_LENGTH)) is
                ChargeInputAppendResult.TooLong,
        )
    }

    @Test
    fun correctionIsPredictableAndSendsBothOriginalAndCorrection() {
        val initial = requireNotNull(ChargeInputSession.start("resolution", "cóbrale 350 por la cena"))
        val corrected = (initial.appendClarification("no, mejor 400") as ChargeInputAppendResult.Accepted).session
        assertEquals("cóbrale 350 por la cena no, mejor 400", corrected.text)
        assertEquals(1, corrected.clarificationRounds)
    }

    @Test
    fun clearsSessionWhenProposalIsCreated() {
        assertEquals(
            null,
            inputSessionAfter(sampleSession(), ChargeInputClearReason.PROPOSAL_CREATED),
        )
    }

    @Test
    fun clearsSessionWhenChargeIsCancelled() {
        assertEquals(
            null,
            inputSessionAfter(sampleSession(), ChargeInputClearReason.CANCELLED),
        )
    }

    @Test
    fun clearsSessionWhenChargeIsRestarted() {
        assertEquals(
            null,
            inputSessionAfter(sampleSession(), ChargeInputClearReason.RESTARTED),
        )
    }

    @Test
    fun clearsSessionWhenQrChanges() {
        assertEquals(
            null,
            inputSessionAfter(sampleSession(), ChargeInputClearReason.QR_CHANGED),
        )
    }

    @Test
    fun clearsSessionWhenResolutionExpires() {
        assertEquals(
            null,
            inputSessionAfter(sampleSession(), ChargeInputClearReason.RESOLUTION_EXPIRED),
        )
    }

    @Test
    fun clearsSessionWhenProposalExpires() {
        assertEquals(
            null,
            inputSessionAfter(sampleSession(), ChargeInputClearReason.PROPOSAL_EXPIRED),
        )
    }

    @Test
    fun otherVoiceErrorKeepsSession() {
        assertEquals(
            sampleSession(),
            inputSessionAfterVoiceError(
                sampleSession(),
                android.speech.SpeechRecognizer.ERROR_CLIENT,
            ),
        )
    }

    @Test
    fun newChargeVerbReplacesAccumulatedText() {
        val initial = requireNotNull(ChargeInputSession.start("resolution", "cóbrale por la cena"))
        val result = initial.appendClarification("cóbrale 100 pesos por la camiseta")
        assertEquals(
            "cóbrale 100 pesos por la camiseta",
            (result as ChargeInputAppendResult.Accepted).session.text,
        )
        assertEquals(0, result.session.clarificationRounds)
    }

    @Test
    fun clarificationWithoutChargeVerbStillAppendsText() {
        val initial = requireNotNull(ChargeInputSession.start("resolution", "cóbrale por la cena"))
        val result = initial.appendClarification("350 pesos")
        assertEquals(
            "cóbrale por la cena 350 pesos",
            (result as ChargeInputAppendResult.Accepted).session.text,
        )
    }

    @Test
    fun chargeVerbsAreRecognizedWithAndWithoutAccents() {
        assertTrue(startsWithChargeVerb("cóbrale 350 pesos por cena"))
        assertTrue(startsWithChargeVerb("cobrales 350 pesos por cena"))
        assertTrue(startsWithChargeVerb("cobra 350 pesos por cena"))
        assertTrue(startsWithChargeVerb("cobrar 350 pesos por cena"))
        assertTrue(!startsWithChargeVerb("por la cena"))
    }

    @Test
    fun normalizesCommandsWithAccentsPunctuationAndSpaces() {
        assertEquals("si", normalizeCommandText(" ¡ Sí... "))
        assertEquals("confirmar", normalizeCommandText("Confirmar."))
        assertEquals("cancelar", normalizeCommandText("  cancelar  "))
    }

    @Test
    fun confirmationVariantsExecuteOnlyInPreview() {
        val preview = ChargeState.Preview(proposal, 30)
        listOf("Sí", "SÍ", "si", "Sí.", "¡Sí!", " confirmar ", "Confirmar.")
            .forEach { assertEquals(VoiceCommand.CONFIRM, voiceCommand(it, preview)) }
    }

    @Test
    fun cancellationVariantsExecute() {
        val preview = ChargeState.Preview(proposal, 30)
        listOf("No.", "Cancelar.", "CANCELAR", "no")
            .forEach { assertEquals(VoiceCommand.CANCEL, voiceCommand(it, preview)) }
    }

    @Test
    fun startOverVariantsExecute() {
        val preview = ChargeState.Preview(proposal, 30)
        assertEquals(VoiceCommand.START_OVER, voiceCommand("empezar de nuevo.", preview))
        assertEquals(VoiceCommand.START_OVER, voiceCommand("Empezar de Nuevo", preview))
    }

    @Test
    fun longerCommandPhrasesDoNotExecute() {
        val preview = ChargeState.Preview(proposal, 30)
        listOf("sí confirmo", "cancelar el cobro", "no sé", "confirmar el cobro", "si quieres")
            .forEach { assertEquals(VoiceCommand.NONE, voiceCommand(it, preview)) }
    }

    @Test
    fun cobrançaLikeWordsDoNotTriggerChargeVerbReplacement() {
        val initial = sampleSession()
        val cobrança = initial.appendClarification("cobranza por la cena")
        val cobrado = initial.appendClarification("cobrado en efectivo")
        assertEquals(
            "cóbrale por la cena cobranza por la cena",
            (cobrança as ChargeInputAppendResult.Accepted).session.text,
        )
        assertEquals(
            "cóbrale por la cena cobrado en efectivo",
            (cobrado as ChargeInputAppendResult.Accepted).session.text,
        )
    }

    @Test
    fun punctuatedChargeVerbsTriggerReplacement() {
        val initial = sampleSession()
        listOf("Cóbrale.", "¡Cóbrale 350!", "cobra 350 por la cena").forEach { text ->
            val result = initial.appendClarification(text) as ChargeInputAppendResult.Accepted
            assertEquals(text, result.session.text)
            assertEquals(0, result.session.clarificationRounds)
        }
    }

    @Test
    fun startOverCommandCleansInputSessionForButtonAndVoice() {
        val session = sampleSession()
        assertEquals(null, inputSessionAfter(session, ChargeInputClearReason.RESTARTED))
        assertEquals(VoiceCommand.START_OVER, voiceCommand("empezar de nuevo", ChargeState.Preview(proposal, 30)))
    }

    @Test
    fun voiceCommandPriorityPrefersCancellation() {
        assertEquals(VoiceCommand.CANCEL, voiceCommand("cancelar", ChargeState.Preview(proposal, 30)))
        assertEquals(VoiceCommand.CANCEL, voiceCommand("no", ChargeState.Clarification("¿Qué falta?")))
    }

    @Test
    fun voiceCommandsRequireExactWholePhrase() {
        assertEquals(VoiceCommand.NONE, voiceCommand("cancelar el cobro", ChargeState.Preview(proposal, 30)))
        assertEquals(VoiceCommand.NONE, voiceCommand("empezar de nuevo por favor", ChargeState.Preview(proposal, 30)))
        assertEquals(VoiceCommand.NONE, voiceCommand("sí confirmo", ChargeState.Preview(proposal, 30)))
    }

    @Test
    fun confirmationCommandsOnlyWorkInPreview() {
        assertEquals(VoiceCommand.CONFIRM, voiceCommand("confirmar", ChargeState.Preview(proposal, 30)))
        assertEquals(VoiceCommand.CONFIRM, voiceCommand("sí", ChargeState.Preview(proposal, 30)))
        assertEquals(VoiceCommand.NONE, voiceCommand("confirmar", ChargeState.Clarification("¿Qué falta?")))
        assertEquals(VoiceCommand.NONE, voiceCommand("sí", ChargeState.Listening))
    }

    @Test
    fun displayTextIsBoundedForUiOnly() {
        assertEquals("abc", displayText(" abc ", 3))
    }

    @Test
    fun serverClarificationKeepsSession() {
        assertEquals(
            sampleSession(),
            inputSessionAfterDecision(
                sampleSession(),
                InputSessionDecisionReason.SERVER_CLARIFICATION,
            ),
        )
    }

    @Test
    fun voiceError6KeepsSession() {
        assertEquals(sampleSession(), inputSessionAfterVoiceError(sampleSession(), 6))
    }

    @Test
    fun voiceError7KeepsSession() {
        assertEquals(sampleSession(), inputSessionAfterVoiceError(sampleSession(), 7))
    }

    @Test
    fun voiceError8KeepsSession() {
        assertEquals(sampleSession(), inputSessionAfterVoiceError(sampleSession(), 8))
    }

    @Test
    fun interpretNetworkTimeoutAndRateLimitKeepSession() {
        val session = sampleSession()
        assertEquals(
            session,
            inputSessionAfterDecision(session, InputSessionDecisionReason.INTERPRET_NETWORK),
        )
        assertEquals(
            session,
            inputSessionAfterDecision(session, InputSessionDecisionReason.INTERPRET_TIMEOUT),
        )
        assertEquals(
            session,
            inputSessionAfterDecision(session, InputSessionDecisionReason.INTERPRET_RATE_LIMIT),
        )
    }

    @Test
    fun definitiveInterpretationErrorClearsSession() {
        assertEquals(
            null,
            inputSessionAfterDecision(
                sampleSession(),
                InputSessionDecisionReason.INTERPRET_DEFINITIVE_ERROR,
            ),
        )
    }

    private fun sampleSession(): ChargeInputSession {
        return requireNotNull(ChargeInputSession.start("resolution", "cóbrale por la cena"))
    }
}
