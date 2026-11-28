package com.uoai.providerapi

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Manages short-lived audio upload staging files under `cacheDir/asr_uploads/`.
 *
 * - Generates random UUID filenames (never trusts client-supplied filenames to avoid path traversal).
 * - Streams directly to disk with hard byte-limit enforcement (never buffers 25 MiB in JVM heap).
 * - Deletes files immediately on error/oversize and provides startup sweeping for process-death recovery.
 */
class AsrTempFileStore(private val uploadDir: File) {
    init {
        uploadDir.mkdirs()
    }

    suspend fun writeChannelBounded(channel: ByteReadChannel, maxBytes: Long): Pair<File, Long> =
        withContext(Dispatchers.IO) {
            uploadDir.mkdirs()
            val tmp = File(uploadDir, "asr_${UUID.randomUUID()}.tmp")
            var total = 0L
            try {
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) continue
                        total += n
                        if (total > maxBytes) {
                            throw ApiException(
                                HttpStatusCode.PayloadTooLarge,
                                "invalid_request_error",
                                "audio_too_large",
                                "Audio upload exceeds maximum allowed $maxBytes bytes",
                            )
                        }
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
                tmp to total
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }

    fun deleteQuietly(file: File?) {
        if (file != null) runCatching { file.delete() }
    }

    /** Removes all orphaned staging files (called on app startup and manual cache clear). */
    fun sweepAll(): Int {
        val files = uploadDir.listFiles() ?: return 0
        var deleted = 0
        for (f in files) {
            if (f.isFile && f.delete()) deleted++
        }
        return deleted
    }

    fun totalCacheBytes(): Long = uploadDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}

