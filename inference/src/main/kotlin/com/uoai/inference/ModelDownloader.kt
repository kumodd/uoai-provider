package com.uoai.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DownloadException(message: String, val httpCode: Int? = null) : Exception(message)

data class HuggingFaceSource(
    val repo: String = "litert-community/gemma-4-E2B-it-litert-lm",
    val fileName: String = "gemma-4-E2B-it.litertlm",
    val revision: String = "main",
) {
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
}

/**
 * Downloads a model from Hugging Face with resume support.
 * Gated repositories require a user access token; open Apache-2.0 Gemma 4 bundles do not.
 * The token is only sent to huggingface.co, never to the CDN host it redirects to.
 */
class ModelDownloader {
    suspend fun download(
        source: HuggingFaceSource,
        token: String?,
        target: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        var resumeFrom = if (part.exists()) part.length() else 0L
        var url = URL(source.url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 30_000
                if (!token.isNullOrBlank() && url.host.endsWith("huggingface.co")) setRequestProperty("Authorization", "Bearer $token")
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw DownloadException("Redirect without Location", code)
                url = URL(url, loc); conn.disconnect()
                if (++hops > 5) throw DownloadException("Too many redirects")
                continue
            }
            if (code == 401 || code == 403) throw DownloadException(
                "Access denied ($code). This model repository is gated: accept the license on huggingface.co and provide a valid access token.", code)
            if (code == 416) { part.delete(); resumeFrom = 0; conn.disconnect(); continue }
            if (code !in 200..299) throw DownloadException("HTTP $code", code)
            break
        }
        val append = conn.responseCode == 206
        if (!append) { resumeFrom = 0 }
        val total = conn.contentLengthLong.takeIf { it > 0 }?.plus(resumeFrom)
        if (total != null) {
            val usable = target.parentFile?.usableSpace ?: 0L
            val needed = total - resumeFrom
            if (usable > 0L && needed > usable - 64L * 1024 * 1024) {
                conn.disconnect()
                throw DownloadException(
                    "Not enough free storage: needs ~${total / 1_048_576} MB, device has ${usable / 1_048_576} MB available.",
                )
            }
        }
        var done = resumeFrom
        try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - lastReport > 512 * 1024) { onProgress(done, total); lastReport = done }
                    }
                }
            }
        } finally { conn.disconnect() }
        onProgress(done, total)
        if (total != null && done != total) throw DownloadException("Incomplete download ($done/$total)")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw DownloadException("Could not finalize file")
    }
}
