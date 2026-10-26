package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactResolveTest {
    private val resolution = PayerResolution("Ana", "res-ana", "2099-01-01T00:00:00Z", "c1")

    private fun identifiedBy(machine: ResolveStateMachine) = machine.state as ResolveState.Identified

    @Test
    fun qrSuccessWithContactIdKeepsIt() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "t", false))
        machine.dispatch(ResolveEvent.Success(1, "Ana", "res", "exp", "c1"))
        assertEquals("c1", identifiedBy(machine).contactId)
    }

    @Test
    fun qrSuccessWithoutContactIdHasNullContact() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "t", false))
        machine.dispatch(ResolveEvent.Success(1, "Ana", "res", "exp"))
        assertNull(identifiedBy(machine).contactId)
    }

    @Test
    fun contactResolvedFromScanningBecomesIdentifiedWithAllFields() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.ContactResolved(resolution))
        assertEquals(ResolveState.Identified("Ana", "res-ana", "2099-01-01T00:00:00Z", "c1"), machine.state)
    }

    @Test
    fun contactResolvedFromIdentifiedReplacesPayer() {
        val machine = ResolveStateMachine(ResolveState.Identified("Beto", "res-beto", "exp", null))
        machine.dispatch(ResolveEvent.ContactResolved(resolution))
        assertEquals("Ana", identifiedBy(machine).displayName)
        assertEquals("res-ana", identifiedBy(machine).resolutionId)
    }

    @Test
    fun contactResolvedFromRetryableErrorBecomesIdentified() {
        val machine = ResolveStateMachine(ResolveState.RetryableError(1, "t", "m"))
        machine.dispatch(ResolveEvent.ContactResolved(resolution))
        assertEquals("Ana", identifiedBy(machine).displayName)
    }

    @Test
    fun contactResolvedFromFailedBecomesIdentified() {
        val machine = ResolveStateMachine(ResolveState.Failed("m"))
        machine.dispatch(ResolveEvent.ContactResolved(resolution))
        assertEquals("Ana", identifiedBy(machine).displayName)
    }

    @Test
    fun contactResolvedWhileResolvingIsIgnored() {
        val resolving = ResolveState.Resolving(1, "t", false)
        val machine = ResolveStateMachine(resolving)
        machine.dispatch(ResolveEvent.ContactResolved(resolution))
        assertEquals(resolving, machine.state)
    }

    @Test
    fun contactSavedUpdatesContactIdKeepingOtherFields() {
        val machine = ResolveStateMachine(ResolveState.Identified("Ana", "res", "exp", null))
        machine.dispatch(ResolveEvent.ContactSaved("c9"))
        assertEquals(ResolveState.Identified("Ana", "res", "exp", "c9"), machine.state)
    }

    @Test
    fun contactSavedInScanningDoesNothing() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.ContactSaved("c9"))
        assertEquals(ResolveState.Scanning, machine.state)
    }

    @Test
    fun lateQrSuccessAfterContactResolvedDoesNotReplaceContactPayer() {
        val machine = ResolveStateMachine()
        machine.dispatch(ResolveEvent.Begin(1, "t", false))
        machine.dispatch(ResolveEvent.Reset)
        machine.dispatch(ResolveEvent.ContactResolved(resolution))
        machine.dispatch(ResolveEvent.Success(1, "Otro", "res-otro", "exp"))
        assertEquals("Ana", identifiedBy(machine).displayName)
    }
}
