package com.uoai.sdk

import com.uoai.sdk.exceptions.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** Minimal HTTP layer (no third-party dependencies). Authentication is attached here. */
internal class ApiClient(private val config: ClientConfig, private val token: () -> String?) {
    val json = Json { ignoreUnknownKeys = true }

    fun open(method: String, path: String, authenticated: Boolean = true, body: String? = null, accept: String = "application/json"): HttpURLConnection {
        val conn = try {
            (URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3_000; readTimeout = config.requestTimeoutMs
                setRequestProperty("Accept", accept)
                if (authenticated) {
                    val t = token() ?: throw NotRegisteredException("This app is not registered with the provider")
                    setRequestProperty("Authorization", "Bearer $t")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
        } catch (e: ConnectException) {
            throw ProviderNotRunningException("Provider API is not reachable at ${config.baseUrl}. Start it in the provider app.", e)
        } catch (e: SocketTimeoutException) {
            throw ProviderNotRunningException("Provider API timed out", e)
        }
        return conn
    }

    /** Executes a request and returns the body of a 2xx response; maps errors to typed exceptions. */
    fun call(method: String, path: String, authenticated: Boolean = true, body: String? = null): Pair<String, HttpURLConnection> {
        val conn = open(method, path, authenticated, body)
        try {
            val code = try { conn.responseCode } catch (e: ConnectException) {
                throw ProviderNotRunningException("Provider API is not reachable", e)
            } catch (e: IOException) { throw ProviderNotRunningException("Connection to provider failed: ${e.message}", e) }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw mapError(code, text)
            return text to conn
        } finally { conn.disconnect() }
    }

    fun openMultipart(
        path: String,
        fileName: String,
        fileContentType: String,
        audioStream: InputStream,
        fields: Map<String, String>,
        accept: String = "application/json",
    ): HttpURLConnection {
        val boundary = "----UoaiBoundary" + UUID.randomUUID().toString().replace("-", "")
        val safeName = fileName.replace("\"", "").replace("\r", "").replace("\n", "").ifBlank { "audio.wav" }
        val conn = try {
            (URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3_000
                readTimeout = config.requestTimeoutMs
                doOutput = true
                setChunkedStreamingMode(64 * 1024)
                setRequestProperty("Accept", accept)
                val t = token() ?: throw NotRegisteredException("This app is not registered with the provider")
                setRequestProperty("Authorization", "Bearer $t")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                outputStream.use { out ->
                    for ((k, v) in fields) {
                        out.write("--$boundary\r\n".toByteArray())
                        out.write("Content-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                        out.write(v.toByteArray(Charsets.UTF_8))
                        out.write("\r\n".toByteArray())
                    }
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray())
                    out.write("Content-Type: $fileContentType\r\n\r\n".toByteArray())
                    audioStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                    out.flush()
                }
            }
        } catch (e: ConnectException) {
            throw ProviderNotRunningException("Provider API is not reachable at ${config.baseUrl}. Start it in the provider app.", e)
        } catch (e: SocketTimeoutException) {
            throw ProviderNotRunningException("Provider API timed out", e)
        }
        return conn
    }

    fun callMultipart(
        path: String,
        fileName: String,
        fileContentType: String,
        audioStream: InputStream,
        fields: Map<String, String>,
    ): Pair<String, HttpURLConnection> {
        val conn = openMultipart(path, fileName, fileContentType, audioStream, fields)
        try {
            val code = try { conn.responseCode } catch (e: ConnectException) {
                throw ProviderNotRunningException("Provider API is not reachable", e)
            } catch (e: IOException) {
                throw ProviderNotRunningException("Connection to provider failed: ${e.message}", e)
            }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw mapError(code, text)
            return text to conn
        } finally {
            conn.disconnect()
        }
    }

    fun mapError(status: Int, body: String): UniversalAIException {
        val err = runCatching { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
        val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: "http_$status"
        val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $status"
        return when (code) {
            "missing_token", "invalid_token" -> NotRegisteredException(msg)
            "model_not_ready", "asr_model_not_ready" -> ModelNotReadyException(msg)
            "permission_denied" -> PermissionDeniedException(msg)
            "request_cancelled" -> RequestCancelledException(msg)
            else -> ApiException(status, code, msg)
        }
    }
}
