package com.example.openaichat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(
    context: Context,
    @get:VisibleForTesting internal val encryptOverride: ((ByteArray) -> ByteArray)? = null
) {
    companion object {
        private val keystoreLock = Any()
        @Volatile
        @VisibleForTesting
        internal var failWritesForTesting: Boolean = false
    }

    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val alias = "OpenAIApiChatKey"

    private fun key(): SecretKey = synchronized(keystoreLock) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        gen.generateKey()
    }

    private fun setSecret(name: String, value: String): Boolean = synchronized(keystoreLock) {
        if (value.isBlank()) {
            return@synchronized prefs.edit().remove(name).commit()
        }
        try {
            if (failWritesForTesting) {
                throw GeneralSecurityException("Synthetic Keystore write failure")
            }
            val raw = value.toByteArray(Charsets.UTF_8)
            val payload = if (encryptOverride != null) {
                encryptOverride.invoke(raw)
            } else {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key())
                c.iv + c.doFinal(raw)
            }
            val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
            if (!prefs.edit().putString(name, encoded).commit()) {
                prefs.edit().remove(name).commit()
                return@synchronized false
            }
            true
        } catch (_: Exception) {
            // Never leave an older account's credential active when a key replacement fails.
            prefs.edit().remove(name).commit()
            false
        }
    }

    private fun getSecret(name: String): String = synchronized(keystoreLock) {
        try {
            val p = Base64.decode(prefs.getString(name, null) ?: return@synchronized "", Base64.NO_WRAP)
            if (p.size < 13) return@synchronized ""
            val iv = p.copyOfRange(0, 12)
            val d = p.copyOfRange(12, p.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(d), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    /** Remove the retired credential without reading it or changing shared encryption keys. */
    fun discardLegacyOpenAiCredential(): Boolean = synchronized(keystoreLock) {
        !prefs.contains("key") || prefs.edit().remove("key").commit()
    }

    fun setOpenRouterApiKey(v: String): Boolean = setSecret("openrouter_key", v)
    fun getOpenRouterApiKey(): String = getSecret("openrouter_key")

    fun setGitHubToken(v: String): Boolean = setSecret("github_pat", v)
    fun getGitHubToken(): String = getSecret("github_pat")
}
