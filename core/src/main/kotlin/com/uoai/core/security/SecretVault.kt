package com.uoai.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores small secrets (e.g. Hugging Face token) encrypted with a non-exportable Android Keystore AES key. */
class SecretVault(context: Context, private val alias: String = "uoai_secret_key") {
    private val prefs = context.getSharedPreferences("uoai_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return runCatching {
            val b = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            String(c.doFinal(b, 12, b.size - 12))
        }.getOrNull()
    }

    fun remove(name: String) = prefs.edit().remove(name).apply()
}
