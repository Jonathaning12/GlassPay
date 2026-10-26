package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorSessionTest {
    private class FakeCipher(private val failOnDecrypt: Boolean = false) : SecretCipher {
        private val iv = ByteArray(12) { 7 }

        override fun encrypt(plain: ByteArray) =
            EncryptedValue(iv.copyOf(), ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() })

        override fun decrypt(value: EncryptedValue): ByteArray {
            if (failOnDecrypt) throw IllegalStateException("fallo")
            return ByteArray(value.ciphertext.size) { (value.ciphertext[it].toInt() xor 0x5A).toByte() }
        }
    }

    private class InMemoryStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun get(key: String): String? = data[key]
        override fun put(values: Map<String, String>) { data.putAll(values) }
        override fun clear() { data.clear() }
    }

    private val session = CollectorSession("collector-123", "super-secret-value", "Rodrigo")

    @Test
    fun saveThenLoadReturnsSameSession() {
        val store = InMemoryStore()
        val sessions = CollectorSessionStore(store, FakeCipher())
        sessions.save(session)
        assertEquals(session, sessions.load())
    }

    @Test
    fun plainSecretNeverAppearsInStoredValues() {
        val store = InMemoryStore()
        CollectorSessionStore(store, FakeCipher()).save(session)
        store.data.forEach { (key, value) ->
            assertFalse("Secreto en claro en $key", value.contains(session.sessionSecret))
        }
    }

    @Test
    fun loadWithNothingSavedReturnsNull() {
        assertNull(CollectorSessionStore(InMemoryStore(), FakeCipher()).load())
    }

    @Test
    fun loadWithMissingIvReturnsNullAndEmptiesStore() {
        val store = InMemoryStore()
        val sessions = CollectorSessionStore(store, FakeCipher())
        sessions.save(session)
        store.data.remove("iv")
        assertNull(sessions.load())
        assertTrue("Store debe quedar vacío", store.data.isEmpty())
    }

    @Test
    fun loadWithMissingCiphertextReturnsNullAndEmptiesStore() {
        val store = InMemoryStore()
        val sessions = CollectorSessionStore(store, FakeCipher())
        sessions.save(session)
        store.data.remove("secret")
        assertNull(sessions.load())
        assertTrue("Store debe quedar vacío", store.data.isEmpty())
    }

    @Test
    fun loadWhenDecryptThrowsReturnsNullAndEmptiesStore() {
        val store = InMemoryStore()
        CollectorSessionStore(store, FakeCipher()).save(session)
        val failing = CollectorSessionStore(store, FakeCipher(failOnDecrypt = true))
        assertNull(failing.load())
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun clearEmptiesStoreAndLoadReturnsNull() {
        val store = InMemoryStore()
        val sessions = CollectorSessionStore(store, FakeCipher())
        sessions.save(session)
        sessions.clear()
        assertTrue(store.data.isEmpty())
        assertNull(sessions.load())
    }

    @Test
    fun headersContainExactlyCollectorIdAndSession() {
        assertEquals(
            mapOf("X-Collector-Id" to "collector-123", "X-Collector-Session" to "super-secret-value"),
            session.headers(),
        )
    }

    @Test
    fun toStringDoesNotLeakSecretOrCollectorId() {
        val text = session.toString()
        assertFalse(text.contains(session.sessionSecret))
        assertFalse(text.contains(session.collectorId))
    }

    @Test
    fun unauthorizedInvalidatesSession() {
        assertTrue(isSessionInvalid(ApiFailureType.UNAUTHORIZED))
    }

    @Test
    fun otherFailureTypesDoNotInvalidateSession() {
        ApiFailureType.values().filter { it != ApiFailureType.UNAUTHORIZED }.forEach {
            assertFalse("$it no debe invalidar la sesión", isSessionInvalid(it))
        }
    }
}
