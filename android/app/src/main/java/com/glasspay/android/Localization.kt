package com.glasspay.android

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

// Idioma de la app (español por defecto, inglés opcional), elegido en Perfil y guardado solo en este
// teléfono. Igual que iOS (`Localization.swift`): los textos se escriben en español y `L(...)` los
// traduce con el diccionario de `EnglishStrings.kt`. Como `L` lee un estado de Compose, cualquier
// pantalla que lo use se vuelve a dibujar al cambiar de idioma.

enum class AppLanguage(
    val code: String,
    /** Nombre del idioma en su propio idioma, para el selector. */
    val nativeName: String,
    /** Reconocimiento y síntesis de voz. */
    val speechTag: String,
    /** Fechas y formatos regionales. */
    val locale: Locale,
) {
    ES("es", "Español", "es-MX", Locale.forLanguageTag("es-MX")),
    EN("en", "English", "en-US", Locale.US),
    ;

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ES
    }
}

object AppLanguageStore {
    private const val PREFS_NAME = "glasspay_settings"
    private const val LANGUAGE_KEY = "language"

    // En las pruebas JVM nunca se llama a load(), así que siempre es español.
    private val state = mutableStateOf(AppLanguage.ES)
    @Volatile private var loaded = false

    val current: AppLanguage get() = state.value

    /** Lee el idioma guardado una sola vez (actividad y WorkManager). */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LANGUAGE_KEY, null)
        }.getOrNull()
        state.value = AppLanguage.fromCode(saved)
    }

    fun set(context: Context, language: AppLanguage) {
        state.value = language
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(LANGUAGE_KEY, language.code)
                .apply()
        }
    }
}

val currentLanguage: AppLanguage get() = AppLanguageStore.current

/**
 * Traduce un texto escrito en español. Con argumentos, la clave es un formato (`%s`) y los valores
 * se insertan después de traducir. Si falta la traducción se muestra el español.
 */
fun L(spanish: String, vararg args: Any?): String = localized(spanish, currentLanguage, *args)

fun localized(spanish: String, language: AppLanguage, vararg args: Any?): String {
    val template = if (language == AppLanguage.EN) englishStrings[spanish] ?: spanish else spanish
    if (args.isEmpty()) return template
    return String.format(language.locale, template, *args)
}
