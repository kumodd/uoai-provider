package com.uoai.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps this app's provider credential encrypted with a non-exportable Android Keystore key. */
internal class TokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("uoai_sdk", Context.MODE_PRIVATE)
    private val alias = "uoai_sdk_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun save(token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString("t", Base64.encodeToString(c.iv + c.doFinal(token.toByteArray()), Base64.NO_WRAP)).apply()
    }

    fun load(): String? = prefs.getString("t", null)?.let { raw ->
        runCatching {
            val b = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOf(12))) }
            String(c.doFinal(b, 12, b.size - 12))
        }.getOrNull()
    }

    fun clear() = prefs.edit().remove("t").apply()
}
