package com.glasspay.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesFrameTest {
    @Test
    fun i420SizeIsLumaPlusQuarterChromaPlanes() {
        assertEquals(720 * 1280 * 3 / 2, i420Size(720, 1280))
    }

    @Test
    fun i420SizeRejectsOddOrEmptyDimensions() {
        assertNull(i420Size(0, 4))
        assertNull(i420Size(3, 4))
        assertNull(i420Size(4, 5))
    }

    @Test
    fun convertsI420ToNv21WithInterleavedVu() {
        // 4x2: Y = 1..8, U = [20, 21], V = [30, 31].
        val i420 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 21, 30, 31)
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 30, 20, 31, 21)
        assertArrayEquals(expected, i420ToNv21(i420, 4, 2))
    }

    @Test
    fun rejectsTruncatedFrames() {
        assertNull(i420ToNv21(ByteArray(11), 4, 2))
    }
}
