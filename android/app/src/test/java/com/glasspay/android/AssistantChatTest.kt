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
}
