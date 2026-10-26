package com.glasspay.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CollectorSession(
    val collectorId: String,
    val sessionSecret: String,
    val displayName: String,
) {
    fun headers(): Map<String, String> = mapOf(
        "X-Collector-Id" to collectorId,
        "X-Collector-Session" to sessionSecret,
    )

    // Nunca incluir el secreto ni el id en texto de depuración.
    override fun toString(): String = "CollectorSession(displayName=$displayName)"
}

fun isSessionInvalid(type: ApiFailureType): Boolean = type == ApiFailureType.UNAUTHORIZED

data class EncryptedValue(val iv: ByteArray, val ciphertext: ByteArray)

interface SecretCipher {
    fun encrypt(plain: ByteArray): EncryptedValue
    fun decrypt(value: EncryptedValue): ByteArray
}

interface KeyValueStore {
    fun get(key: String): String?
    fun put(values: Map<String, String>)
    fun clear()
}

class CollectorSessionStore(
    private val store: KeyValueStore,
    private val cipher: SecretCipher,
) {
    fun load(): CollectorSession? {
        val collectorId = store.get(KEY_ID)
        val displayName = store.get(KEY_NAME)
        val iv = store.get(KEY_IV)
        val secret = store.get(KEY_SECRET)
        if (collectorId == null || displayName == null || iv == null || secret == null) {
            // Datos a medias: se limpian; si no hay nada guardado, no hay nada que limpiar.
            if (collectorId != null || displayName != null || iv != null || secret != null) clear()
            return null
        }
        return try {
            val decoded = cipher.decrypt(
                EncryptedValue(
                    Base64.getDecoder().decode(iv),
                    Base64.getDecoder().decode(secret),
                ),
            )
            CollectorSession(collectorId, String(decoded, Charsets.UTF_8), displayName)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(session: CollectorSession) {
        val encrypted = cipher.encrypt(session.sessionSecret.toByteArray(Charsets.UTF_8))
        store.put(
            mapOf(
                KEY_ID to session.collectorId,
                KEY_NAME to session.displayName,
                KEY_IV to Base64.getEncoder().encodeToString(encrypted.iv),
                KEY_SECRET to Base64.getEncoder().encodeToString(encrypted.ciphertext),
            ),
        )
    }

    fun clear() {
        store.clear()
    }

    private companion object {
        const val KEY_ID = "collectorId"
        const val KEY_NAME = "displayName"
        const val KEY_IV = "iv"
        const val KEY_SECRET = "secret"
    }
}

/** Las operaciones de Keystore no deben correr en el hilo principal: llamar desde Dispatchers.IO. */
class KeystoreSecretCipher(
    private val alias: String = "glasspay_collector_session",
) : SecretCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): EncryptedValue {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return EncryptedValue(cipher.iv, cipher.doFinal(plain))
    }

    override fun decrypt(value: EncryptedValue): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.iv))
        return cipher.doFinal(value.ciphertext)
    }
}

/** Usar desde Dispatchers.IO (SharedPreferences con commit()). */
class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("glasspay_collector", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(values: Map<String, String>) {
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        editor.commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}
