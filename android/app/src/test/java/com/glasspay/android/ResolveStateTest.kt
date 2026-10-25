package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveStateTest {
    @Test
    fun retryableFailureCanSucceedWithSameQr() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "token", waking = false))
        machine.dispatch(ResolveEvent.Failure(1, ResolveFailureType.TIMEOUT))
        assertTrue(machine.state is ResolveState.RetryableError)

        machine.dispatch(ResolveEvent.Begin(2, "token", waking = false))
        machine.dispatch(ResolveEvent.Success(2, "Jonathan", "resolution", "expires"))
        assertEquals("Jonathan", (machine.state as ResolveState.Identified).displayName)
    }

    @Test
    fun notFoundReturnsToScanning() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "token", waking = false))
        machine.dispatch(ResolveEvent.Failure(1, ResolveFailureType.NOT_FOUND))
        assertEquals(ResolveState.Scanning, machine.state)
    }

    @Test
    fun delayedResultAfterResetIsIgnored() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "token", waking = false))
        machine.dispatch(ResolveEvent.Reset)
        machine.dispatch(ResolveEvent.Success(1, "Jonathan", "resolution", "expires"))
        assertEquals(ResolveState.Scanning, machine.state)
    }

    @Test
    fun sameActiveTokenDoesNotStartAnotherAttempt() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "token", waking = false))
        machine.dispatch(ResolveEvent.Begin(2, "token", waking = false))
        assertEquals(1L, (machine.state as ResolveState.Resolving).attemptId)
    }

    @Test
    fun delayedFailureAfterSuccessDoesNotReplacePayer() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "token", waking = false))
        machine.dispatch(ResolveEvent.Success(1, "Jonathan", "resolution", "expires"))
        machine.dispatch(ResolveEvent.Failure(1, ResolveFailureType.NETWORK))
        assertEquals("Jonathan", (machine.state as ResolveState.Identified).displayName)
    }
}
