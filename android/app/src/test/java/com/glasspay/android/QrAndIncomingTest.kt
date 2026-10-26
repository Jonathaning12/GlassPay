package com.glasspay.android

import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrAndIncomingTest {
    private val content = "glasspay:abcdefghijklmnopqrstuvwxyz0123456789"

    private fun hasBlackIn(m: BitMatrix, x0: Int, y0: Int, size: Int): Boolean {
        for (x in x0 until x0 + size) for (y in y0 until y0 + size) if (m.get(x, y)) return true
        return false
    }

    @Test
    fun qrMatrixHasRequestedSize() {
        val m = qrMatrix(content, 256)
        assertEquals(256, m.width)
        assertEquals(256, m.height)
    }

    @Test
    fun qrMatrixHasBlackAndWhiteModules() {
        val m = qrMatrix(content, 256)
        var black = 0
        for (x in 0 until 256) for (y in 0 until 256) if (m.get(x, y)) black++
        assertTrue(black > 0)
        assertTrue(black < 256 * 256)
    }

    @Test
    fun qrMatrixHasFinderNearTopLeft() = assertTrue(hasBlackIn(qrMatrix(content, 256), 0, 0, 24))

    @Test
    fun qrMatrixHasFinderNearTopRight() = assertTrue(hasBlackIn(qrMatrix(content, 256), 256 - 24, 0, 24))

    @Test
    fun qrMatrixHasFinderNearBottomLeft() = assertTrue(hasBlackIn(qrMatrix(content, 256), 0, 256 - 24, 24))

    @Test
    fun qrMatrixIsDeterministic() {
        assertEquals(qrMatrix(content, 256), qrMatrix(content, 256))
    }

    // isAllowedApproveUrl

    @Test
    fun sandboxCheckoutUrlIsAllowed() =
        assertTrue(isAllowedApproveUrl("https://www.sandbox.paypal.com/checkoutnow?token=X"))

    @Test
    fun rejectedApproveUrls() {
        listOf(
            null,
            "",
            "http://www.sandbox.paypal.com/x",
            "https://www.paypal.com/x",
            "https://sandbox.paypal.com/x",
            "https://www.sandbox.paypal.com.evil.com/x",
            "https://evil.com/?u=https://www.sandbox.paypal.com",
            "https://user@www.sandbox.paypal.com/x",
            "https://www.sandbox.paypal.com:8443/x",
            "javascript:alert(1)",
            "intent://www.sandbox.paypal.com#Intent;end",
        ).forEach { assertFalse("Debe rechazarse: $it", isAllowedApproveUrl(it)) }
    }

    // OWN_QR

    @Test
    fun ownQrFailureEndsInFailedStateWithoutRetry() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "t", false))
        machine.dispatch(ResolveEvent.Failure(1, ResolveFailureType.OWN_QR))
        assertEquals(ResolveState.Failed("Ese es tu propio QR."), machine.state)
    }
}
