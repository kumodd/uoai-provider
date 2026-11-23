package com.uoai.sdk

import com.uoai.sdk.exceptions.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

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

    fun mapError(status: Int, body: String): UniversalAIException {
        val err = runCatching { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
        val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: "http_$status"
        val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $status"
        return when (code) {
            "missing_token", "invalid_token" -> NotRegisteredException(msg)
            "model_not_ready" -> ModelNotReadyException(msg)
            "permission_denied" -> PermissionDeniedException(msg)
            "request_cancelled" -> RequestCancelledException(msg)
            else -> ApiException(status, code, msg)
        }
    }
}
