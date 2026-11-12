package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Idioma inglés: mismos casos que `EnglishLanguageTests` de iOS más la revisión del diccionario. */
class EnglishLanguageTest {
    private val proposal = ChargeProposal("proposal", "350.00", "MXN", "cena", "2099-01-01T00:00:00Z")

    private fun previewState(): ChargeState {
        val machine = ChargeStateMachine()
        machine.beginListening()
        machine.beginInterpretation()
        return machine.preview(proposal, 120)
    }

    @Test
    fun englishConfirmAndYesConfirm() {
        val state = previewState()
        assertEquals(VoiceCommand.CONFIRM, voiceCommand("Confirm.", state, AppLanguage.EN))
        assertEquals(VoiceCommand.CONFIRM, voiceCommand("yes", state, AppLanguage.EN))
    }

    @Test
    fun englishCancelAndStartOver() {
        val state = previewState()
        assertEquals(VoiceCommand.CANCEL, voiceCommand("cancel", state, AppLanguage.EN))
        assertEquals(VoiceCommand.CANCEL, voiceCommand("No", state, AppLanguage.EN))
        assertEquals(VoiceCommand.START_OVER, voiceCommand("start over", state, AppLanguage.EN))
    }

    @Test
    fun receiptQuestionEchoDoesNotConfirm() {
        val state = previewState()
        assertEquals(VoiceCommand.NONE, voiceCommand("Charge of 50 pesos for dinner. Do you confirm?", state, AppLanguage.EN))
        assertEquals(VoiceCommand.NONE, voiceCommand("Do you confirm?", state, AppLanguage.EN))
        assertEquals(VoiceCommand.NONE, voiceCommand("yes please confirm", state, AppLanguage.EN))
    }

    @Test
    fun englishConfirmationOnlyInPreview() {
        assertEquals(VoiceCommand.NONE, voiceCommand("yes", ChargeState.Listening, AppLanguage.EN))
    }

    @Test
    fun commandsDoNotCrossLanguages() {
        val state = previewState()
        assertEquals(VoiceCommand.NONE, voiceCommand("confirmar", state, AppLanguage.EN))
        assertEquals(VoiceCommand.NONE, voiceCommand("confirm", state, AppLanguage.ES))
    }

    @Test
    fun englishChargeVerbStartsNewSession() {
        assertTrue(startsWithChargeVerb("Charge 350 for dinner", AppLanguage.EN))
        assertFalse(startsWithChargeVerb("Cóbrale 350 por la cena", AppLanguage.EN))
        assertFalse(startsWithChargeVerb("no, make it 400", AppLanguage.EN))
    }

    @Test
    fun spanishIsTheDefault() {
        assertEquals(AppLanguage.ES, currentLanguage)
        assertEquals("Pago recibido.", L("Pago recibido."))
    }

    @Test
    fun translatesAndFormats() {
        assertEquals("Payment received.", localized("Pago recibido.", AppLanguage.EN))
        assertEquals("Pago recibido.", localized("Pago recibido.", AppLanguage.ES))
        assertEquals(
            "Charge of 350.00 MXN for cena. Do you confirm?",
            localized("Cobro de %s %s por %s. ¿Confirmas?", AppLanguage.EN, "350.00", "MXN", "cena"),
        )
        assertEquals("Texto sin traducción", localized("Texto sin traducción", AppLanguage.EN))
    }

    @Test
    fun everyTranslationKeepsItsFormats() {
        val format = Regex("%[sd]")
        englishStrings.forEach { (spanish, english) ->
            assertEquals(spanish, format.findAll(spanish).map { it.value }.toList(), format.findAll(english).map { it.value }.toList())
        }
    }

    @Test
    fun everyTextInTheAppHasATranslation() {
        // Las pruebas JVM corren en android/app: se leen las claves L("...") del código fuente.
        val sources = File("src/main/java/com/glasspay/android")
        assertTrue("No se encontró el código fuente", sources.isDirectory)
        val literal = Regex("""\bL\(\s*"((?:[^"\\]|\\.)*)"""")
        val missing = sources.listFiles { file -> file.extension == "kt" && file.name != "EnglishStrings.kt" }!!
            .flatMap { file -> literal.findAll(file.readText()).map { it.groupValues[1].replace("\\\$", "$") }.toList() }
            .distinct()
            .filterNot { it in englishStrings }
        assertEquals(emptyList<String>(), missing)
    }
}
