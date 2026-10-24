package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrTokenParserTest {
    @Test
    fun acceptsGlassPayToken() {
        assertEquals(
            "abc123456789012345678901",
            QrTokenParser.extractToken("glasspay:abc123456789012345678901"),
        )
    }

    @Test
    fun rejectsQrWithoutPrefix() {
        assertNull(QrTokenParser.extractToken("abc123456789012345678901"))
    }

    @Test
    fun rejectsMalformedToken() {
        assertNull(QrTokenParser.extractToken("glasspay:not valid"))
        assertNull(QrTokenParser.extractToken("glasspay:short"))
    }
}
