package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantChatTest {
    private val reply = AssistantResponse(
        reply = AssistantReply(kind = "text", text = "Nadie te debe en este momento."),
        suggestions = listOf("¿Quién me debe?"),
    )

    @Test
    fun startsEmptyWithDefaultSuggestions() {
        val state = ChatState()
        assertTrue(state.messages.isEmpty())
        assertFalse(state.sending)
        assertEquals(DEFAULT_ASSISTANT_SUGGESTIONS, state.suggestions)
    }

    @Test
    fun cannotSendBlankText() {
        assertFalse(ChatState().canSend("   "))
    }

    @Test
    fun cannotSendTextOverTheLimit() {
        assertFalse(ChatState().canSend("a".repeat(MAX_ASSISTANT_MESSAGE_LENGTH + 1)))
    }

    @Test
    fun canSendTextAtTheLimit() {
        assertTrue(ChatState().canSend("a".repeat(MAX_ASSISTANT_MESSAGE_LENGTH)))
    }

    @Test
    fun cannotSendWhileWaitingForAReply() {
        assertFalse(ChatState().userSent("hola").canSend("otra pregunta"))
    }

    @Test
    fun userMessageIsTrimmedAndMarksSending() {
        val state = ChatState().userSent("  ¿Quién me debe?  ")
        assertEquals("¿Quién me debe?", state.messages.single().text)
        assertTrue(state.messages.single().fromUser)
        assertTrue(state.sending)
    }

    @Test
    fun replyIsAppendedAndStopsSending() {
        val state = ChatState().userSent("hola").replied(reply)
        assertEquals(2, state.messages.size)
        assertFalse(state.messages.last().fromUser)
        assertEquals("Nadie te debe en este momento.", state.messages.last().text)
        assertFalse(state.sending)
    }

    @Test
    fun replyReplacesSuggestionsWhenTheServerSendsThem() {
        val state = ChatState().userSent("hola").replied(reply)
        assertEquals(listOf("¿Quién me debe?"), state.suggestions)
    }

    @Test
    fun replyKeepsSuggestionsWhenTheServerSendsNone() {
        val state = ChatState().userSent("hola").replied(reply.copy(suggestions = emptyList()))
        assertEquals(DEFAULT_ASSISTANT_SUGGESTIONS, state.suggestions)
    }

    @Test
    fun failureIsShownAsAnErrorMessageAndStopsSending() {
        val state = ChatState().userSent("hola").failed("Sin conexión.")
        assertTrue(state.messages.last().isError)
        assertFalse(state.messages.last().fromUser)
        assertFalse(state.sending)
    }

    @Test
    fun messageIdsAreUnique() {
        val state = ChatState().userSent("a").replied(reply).userSent("b").failed("x")
        assertEquals(4, state.messages.map { it.id }.toSet().size)
    }

    @Test
    fun networkErrorHasItsOwnMessage() {
        assertNotEquals(
            assistantErrorMessage(ApiFailureType.SERVER),
            assistantErrorMessage(ApiFailureType.NETWORK),
        )
    }

    @Test
    fun rateLimitAsksToWait() {
        assertTrue(assistantErrorMessage(ApiFailureType.RATE_LIMITED).contains("Espera"))
    }

    @Test
    fun periodLabelsAreInSpanish() {
        assertEquals("Esta semana", assistantPeriodLabel("week"))
        assertEquals("Este mes", assistantPeriodLabel("month"))
        assertEquals("Este año", assistantPeriodLabel("year"))
    }

    // Propuestas (C2a)

    @Test
    fun proposalStartsPendingAndConfirmable() {
        assertEquals(ProposalStatus.PENDING, ChatState().proposalStatus("p1"))
        assertTrue(ChatState().canConfirm("p1"))
    }

    @Test
    fun confirmingAProposalBlocksASecondConfirmation() {
        val state = ChatState().proposalConfirming("p1")
        assertEquals(ProposalStatus.CONFIRMING, state.proposalStatus("p1"))
        assertFalse(state.canConfirm("p1"))
    }

    @Test
    fun confirmedProposalAddsAMessageAndCannotBeConfirmedAgain() {
        val state = ChatState().proposalConfirming("p1").proposalConfirmed("p1", "Listo.")
        assertEquals(ProposalStatus.CONFIRMED, state.proposalStatus("p1"))
        assertEquals("Listo.", state.messages.last().text)
        assertFalse(state.canConfirm("p1"))
    }

    @Test
    fun discardedProposalCannotBeConfirmed() {
        val state = ChatState().proposalDiscarded("p1")
        assertEquals(ProposalStatus.DISCARDED, state.proposalStatus("p1"))
        assertFalse(state.canConfirm("p1"))
    }

    @Test
    fun discardDoesNothingWhileConfirming() {
        val state = ChatState().proposalConfirming("p1").proposalDiscarded("p1")
        assertEquals(ProposalStatus.CONFIRMING, state.proposalStatus("p1"))
    }

    @Test
    fun failedProposalShowsAnErrorMessage() {
        val state = ChatState().proposalConfirming("p1").proposalFailed("p1", "Venció.")
        assertEquals(ProposalStatus.FAILED, state.proposalStatus("p1"))
        assertTrue(state.messages.last().isError)
    }

    @Test
    fun proposalsAreTrackedIndependently() {
        val state = ChatState().proposalDiscarded("p1")
        assertTrue(state.canConfirm("p2"))
    }

    @Test
    fun completedScheduledChargeIsNeverCalledPaid() {
        assertEquals("Cobro creado", scheduledStateLabel("completado"))
        assertFalse(scheduledStateLabel("completado").contains("ag"))
    }

    @Test
    fun scheduledStatesMapToStatusKinds() {
        assertEquals(StatusKind.PENDING, scheduledStateKind("programado"))
        assertEquals(StatusKind.PENDING, scheduledStateKind("esperando_confirmacion"))
        assertEquals(StatusKind.SUCCESS, scheduledStateKind("completado"))
        assertEquals(StatusKind.FAILURE, scheduledStateKind("fallido"))
        assertEquals(StatusKind.NEUTRAL, scheduledStateKind("cancelado"))
        assertEquals(StatusKind.NEUTRAL, scheduledStateKind("omitido"))
    }

    @Test
    fun onlyPendingStatesAreActive() {
        assertTrue(isScheduledActive("programado"))
        assertTrue(isScheduledActive("esperando_confirmacion"))
        assertFalse(isScheduledActive("completado"))
        assertFalse(isScheduledActive("cancelado"))
    }

    @Test
    fun knownFailuresHaveSpanishExplanations() {
        assertEquals(null, scheduledFailureMessage(null))
        assertTrue(scheduledFailureMessage("atrasado")!!.contains("inactivo"))
        assertTrue(scheduledFailureMessage("interrumpido")!!.contains("Movimientos"))
        assertEquals("No se pudo crear el cobro.", scheduledFailureMessage("otra_cosa"))
    }

    @Test
    fun expiredProposalAsksToRequestItAgain() {
        assertTrue(proposalErrorMessage(ApiFailureType.NOT_FOUND).contains("de nuevo"))
    }

    // Dividir y recurrentes (C2b)

    private fun item(recurrence: String? = null, total: Int = 1) = ScheduledItem(
        scheduledId = "s", contactName = "Ana", amount = "50.00", currency = "MXN", concept = "gym",
        runAt = "2026-10-31T00:00:00Z", runAtLabel = "vie 30 oct, 18:00", autoExecute = true,
        state = "programado", failure = null, recurrence = recurrence, occurrencesTotal = total,
    )

    @Test
    fun recurrenceLabelsAreInSpanish() {
        assertEquals("Cada semana · 4 veces", recurrenceLabel("weekly", 4))
        assertEquals("Cada mes", recurrenceLabel("monthly", null))
        assertEquals(null, recurrenceLabel(null, 3))
    }

    @Test
    fun splitMessageListsCreatedChargesAndNeverSaysPaid() {
        val message = proposalOutcomeMessage(
            ProposalOutcome.Split(listOf(SplitResult("Ana", "33.35", true, null), SplitResult("Luis", "33.33", true, null))),
            autoExecute = true,
        )
        assertTrue(message.startsWith("Creé 2 cobros: Ana \$33.35, Luis \$33.33."))
        assertFalse(message.contains("pagad"))
    }

    @Test
    fun splitMessageNamesThoseThatFailed() {
        val message = proposalOutcomeMessage(
            ProposalOutcome.Split(listOf(SplitResult("Ana", "50.00", true, null), SplitResult("Luis", "50.00", false, "demasiados_pendientes"))),
            autoExecute = true,
        )
        assertTrue(message.contains("No se creó para: Luis."))
    }

    @Test
    fun splitMessageWhenNothingWasCreated() {
        val message = proposalOutcomeMessage(
            ProposalOutcome.Split(listOf(SplitResult("Ana", "50.00", false, "error"))),
            autoExecute = true,
        )
        assertTrue(message.startsWith("No pude crear ningún cobro."))
    }

    @Test
    fun recurringMessageMentionsTheSeries() {
        val message = proposalOutcomeMessage(ProposalOutcome.Scheduled(listOf(item("weekly", 4))), autoExecute = true)
        assertTrue(message.contains("(Cada semana · 4 veces)"))
    }

    @Test
    fun manualScheduleMessageSaysItWillAskFirst() {
        val message = proposalOutcomeMessage(ProposalOutcome.Scheduled(listOf(item())), autoExecute = false)
        assertTrue(message.contains("te pediré confirmar"))
    }

    @Test
    fun scheduledSplitMessageCountsPeople() {
        val message = proposalOutcomeMessage(ProposalOutcome.Scheduled(listOf(item(), item())), autoExecute = true)
        assertTrue(message.contains("2 personas"))
    }

    @Test
    fun cancelledMessage() {
        assertEquals("Listo, cancelé la programación.", proposalOutcomeMessage(ProposalOutcome.Cancelled, autoExecute = true))
    }

    // Recordatorios (C2c)

    @Test
    fun reminderSentMessagePointsToPorPagar() {
        val message = proposalOutcomeMessage(ProposalOutcome.ReminderSent("Sara"), autoExecute = true)
        assertTrue(message.contains("Sara"))
        assertTrue(message.contains("Por pagar"))
    }

    @Test
    fun reminderLimitHasASpanishExplanation() {
        assertTrue(scheduledFailureMessage("limite_recordatorios")!!.contains("2 recordatorios"))
    }

    @Test
    fun conflictMentionsTheReminderLimit() {
        assertTrue(proposalErrorMessage(ApiFailureType.CONFLICT).contains("recordatorios"))
    }

    // Pagos programados y avisos (C3)

    @Test
    fun notifiesOnlyWhenSomethingNewIsWaiting() {
        assertTrue(shouldNotify(previousCount = 0, currentCount = 1))
        assertTrue(shouldNotify(previousCount = 1, currentCount = 2))
        assertFalse(shouldNotify(previousCount = 1, currentCount = 1))
        assertFalse(shouldNotify(previousCount = 2, currentCount = 1))
        assertFalse(shouldNotify(previousCount = 0, currentCount = 0))
    }

    @Test
    fun notificationTextHasNoAmountsOrNames() {
        val text = confirmationNotificationText(2)
        assertEquals("Tienes 2 programaciones esperando tu confirmación.", text)
        assertFalse(text.contains("$"))
        assertEquals("Tienes 1 programación esperando tu confirmación.", confirmationNotificationText(1))
    }

    @Test
    fun completedLabelsDependOnTheKind() {
        assertEquals("Envío creado", scheduledStateLabel("completado", "payment"))
        assertEquals("Enviado", scheduledStateLabel("completado", "reminder"))
        assertEquals("Cobro creado", scheduledStateLabel("completado", "charge"))
        assertEquals("Programado", scheduledStateLabel("programado", "payment"))
    }

    @Test
    fun paymentFailuresAreExplained() {
        assertTrue(scheduledFailureMessage("saldo_insuficiente")!!.contains("saldo"))
        assertTrue(scheduledFailureMessage("sin_correo")!!.contains("correo"))
    }
}
