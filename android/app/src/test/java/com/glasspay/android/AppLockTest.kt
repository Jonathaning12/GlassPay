package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    @Test
    fun noBackgroundTimestampDoesNotLock() = assertFalse(shouldLockOnResume(null, 10_000_000L))

    @Test
    fun justBelowTimeoutDoesNotLock() =
        assertFalse(shouldLockOnResume(1_000L, 1_000L + LOCK_TIMEOUT_MS - 1))

    @Test
    fun exactlyTimeoutLocks() = assertTrue(shouldLockOnResume(1_000L, 1_000L + LOCK_TIMEOUT_MS))

    @Test
    fun wellAboveTimeoutLocks() = assertTrue(shouldLockOnResume(1_000L, 1_000L + LOCK_TIMEOUT_MS * 10))

    @Test
    fun customTimeoutBelowDoesNotLock() = assertFalse(shouldLockOnResume(0L, 999L, 1_000L))

    @Test
    fun customTimeoutReachedLocks() = assertTrue(shouldLockOnResume(0L, 1_000L, 1_000L))

    @Test
    fun clockGoingBackwardsDoesNotLock() = assertFalse(shouldLockOnResume(5_000L, 1_000L))

    @Test
    fun visibleAmountIsReturnedUnchanged() = assertEquals("\$150.00 MXN", maskAmount("\$150.00 MXN", true))

    @Test
    fun hiddenAmountKeepsCurrencySuffix() = assertEquals("\$ •••• MXN", maskAmount("\$150.00 MXN", false))

    @Test
    fun hiddenAmountWithoutCurrencyHasNoSuffix() = assertEquals("\$ ••••", maskAmount("\$150.00", false))

    @Test
    fun hiddenAmountContainsNoOriginalDigits() {
        val masked = maskAmount("\$1234.56 MXN", false)
        assertTrue("Contiene dígitos: $masked", masked.none { it.isDigit() })
    }
}
