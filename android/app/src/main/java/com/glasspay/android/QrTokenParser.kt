package com.glasspay.android

object QrTokenParser {
    private const val PREFIX = "glasspay:"

    fun extractToken(rawValue: String?): String? {
        if (rawValue == null || !rawValue.startsWith(PREFIX)) return null
        val token = rawValue.removePrefix(PREFIX)
        return token.takeIf { it.length >= 20 && it.length <= 256 && it.all(::isTokenCharacter) }
    }

    private fun isTokenCharacter(character: Char): Boolean {
        return character.isLetterOrDigit() || character == '-' || character == '_' || character == '='
    }
}
