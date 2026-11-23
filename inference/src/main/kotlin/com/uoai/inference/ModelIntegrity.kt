package com.uoai.inference

import java.io.File
import java.security.MessageDigest

object ModelIntegrity {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Cheap sanity check: .task/.litertlm are zip/flatbuffer containers and are never tiny. */
    fun looksLikeModel(file: File): Boolean = file.isFile && file.length() > 10L * 1024 * 1024
}
