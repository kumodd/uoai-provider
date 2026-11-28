package com.uoai.core.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class FileDownloadException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Resumable HTTP/HTTPS file downloader shared across LLM and ASR model managers.
 *
 * - Supports HTTP `Range` resumption via a `<target>.part` staging file.
 * - Scopes optional Bearer tokens strictly to [tokenHostSuffix] (never forwarded across CDN redirects).
 * - Enforces a minimum free-storage headroom buffer before writing bytes.
 * - Atomically replaces [target] only after transfer completeness is verified.
 */
class ResumableFileDownloader(
    private val minFreeBufferBytes: Long = 64L * 1024 * 1024,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    suspend fun download(
        urlString: String,
        target: File,
        bearerToken: String? = null,
        tokenHostSuffix: String = "huggingface.co",
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        var resumeFrom = if (part.exists()) part.length() else 0L
        var url = URL(urlString)
        var conn: HttpURLConnection
        var hops = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                if (!bearerToken.isNullOrBlank() && url.host.endsWith(tokenHostSuffix)) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
                if (resumeFrom > 0) {
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                    ?: throw FileDownloadException("Redirect without Location header", code)
                url = URL(url, loc)
                conn.disconnect()
                if (++hops > 8) throw FileDownloadException("Too many HTTP redirects")
                continue
            }
            if (code == 401 || code == 403) {
                conn.disconnect()
                throw FileDownloadException("Access denied (HTTP $code). Check repository permissions or token.", code)
            }
            if (code == 416) {
                part.delete()
                resumeFrom = 0L
                conn.disconnect()
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw FileDownloadException("Download failed with HTTP $code", code)
            }
            break
        }

        val append = conn.responseCode == 206
        if (!append) resumeFrom = 0L
        val total = conn.contentLengthLong.takeIf { it > 0 }?.plus(resumeFrom)
        if (total != null) {
            val usable = target.parentFile?.usableSpace ?: 0L
            val needed = (total - resumeFrom).coerceAtLeast(0L)
            if (usable > 0L && needed > usable - minFreeBufferBytes) {
                conn.disconnect()
                throw FileDownloadException(
                    "Not enough free storage: needs ~${total / 1_048_576} MB, device has ${usable / 1_048_576} MB available.",
                )
            }
        }

        var done = resumeFrom
        try {
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = done
                    onProgress(done, total)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024) {
                            onProgress(done, total)
                            lastReport = done
                        }
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        onProgress(done, total)
        if (total != null && done != total) {
            throw FileDownloadException("Incomplete download ($done / $total bytes)")
        }
        if (target.exists() && !target.delete()) {
            throw FileDownloadException("Could not replace existing file ${target.name}")
        }
        if (!part.renameTo(target)) {
            throw FileDownloadException("Could not finalize downloaded file ${target.name}")
        }
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

