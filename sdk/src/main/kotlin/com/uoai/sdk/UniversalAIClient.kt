package com.uoai.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.uoai.sdk.exceptions.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.IOException

/**
 * Client for the Universal Offline AI Provider. Holds no model and loads no runtime.
 */
class UniversalAIClient(context: Context, val config: ClientConfig = ClientConfig()) {
    private val appContext = context.applicationContext
    private val tokens = TokenStore(appContext)
    private val api = ApiClient(config) { tokens.load() }

    val isRegistered: Boolean get() = tokens.load() != null

    fun isProviderInstalled(): Boolean = try {
        @Suppress("DEPRECATION") appContext.packageManager.getPackageInfo(config.providerPackage, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun registrationIntent(): Intent {
        if (!isProviderInstalled()) throw ProviderNotInstalledException("Provider app ${config.providerPackage} is not installed")
        return Intent("com.uoai.provider.action.REGISTER").setPackage(config.providerPackage)
    }

    fun openProviderIntent(): Intent? =
        appContext.packageManager.getLaunchIntentForPackage(config.providerPackage)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun handleRegistrationResult(resultCode: Int, data: Intent?): RegistrationResult {
        val token = data?.getStringExtra("token")
        if (resultCode == Activity.RESULT_OK && token != null) { tokens.save(token); return RegistrationResult.Approved }
        return RegistrationResult.Denied(data?.getStringExtra("error_code") ?: "cancelled", data?.getStringExtra("error") ?: "Registration cancelled")
    }

    fun clearCredentials() = tokens.clear()

    suspend fun checkConnection(): ConnectionState {
        if (!isProviderInstalled()) return ConnectionState.ProviderNotInstalled
        if (!isRegistered) return ConnectionState.NeedsRegistration(registrationIntent())
        return try {
            val h = try {
                health()
            } catch (e: ProviderNotRunningException) {
                return ConnectionState.ProviderNotRunning(
                    launchProviderIntent = openProviderIntent(),
                    message = e.message ?: "Provider API is not running",
                )
            }
            val (m, c) = try {
                models() to capabilities()
            } catch (_: NotRegisteredException) {
                clearCredentials()
                return ConnectionState.NeedsRegistration(registrationIntent())
            } catch (e: ProviderNotRunningException) {
                return ConnectionState.ProviderNotRunning(
                    launchProviderIntent = openProviderIntent(),
                    message = e.message ?: "Provider API is not running",
                )
            }
            if (!h.isReady) {
                return ConnectionState.ModelNotReady(
                    modelState = h.modelState,
                    launchProviderIntent = openProviderIntent(),
                    message = h.modelError ?: "Model is ${h.modelState}. Open Universal Offline AI Provider to load a model.",
                )
            }
            ConnectionState.Connected(h, m.firstOrNull(), c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionState.Error(e.message ?: "Connection failed", e)
        }
    }

    fun startChat(
        systemPrompt: String? = null,
        model: String = config.model,
        temperature: Float? = null,
        maxTokens: Int? = null,
        maxHistoryMessages: Int = 40,
    ): ChatSession = ChatSession(
        client = this,
        systemPrompt = systemPrompt,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
        maxHistoryMessages = maxHistoryMessages,
    )

    suspend fun connect(): ProviderHealth = withContext(Dispatchers.IO) {
        val health = health()
        models()
        health
    }

    suspend fun health(): ProviderHealth = withContext(Dispatchers.IO) {
        api.json.decodeFromString(ProviderHealth.serializer(), api.call("GET", "/health", authenticated = false).first)
    }

    suspend fun models(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val data = api.json.parseToJsonElement(api.call("GET", "/models").first).jsonObject["data"] ?: JsonArray(emptyList())
        api.json.decodeFromJsonElement(ListSerializer(ModelInfo.serializer()), data)
    }

    suspend fun capabilities(): Capabilities = withContext(Dispatchers.IO) {
        api.json.decodeFromString(Capabilities.serializer(), api.call("GET", "/capabilities").first)
    }

    suspend fun generate(
        prompt: String,
        model: String = config.model,
        system: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        tools: List<ClientToolSpec> = emptyList(),
        useProviderTools: Boolean = false,
    ): GenerationResult = chat(
        listOfNotNull(system?.let { ChatMessage("system", it) }, ChatMessage("user", prompt)),
        model, temperature, maxTokens, tools, useProviderTools,
    )

    suspend fun chat(
        messages: List<ChatMessage>,
        model: String = config.model,
        temperature: Float? = null,
        maxTokens: Int? = null,
        tools: List<ClientToolSpec> = emptyList(),
        useProviderTools: Boolean = false,
    ): GenerationResult = withContext(Dispatchers.IO) {
        val (text, conn) = api.call(
            "POST", "/chat/completions",
            body = body(messages, model, temperature, maxTokens, false, tools, useProviderTools),
        )
        val msg = api.json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        val content = msg["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val calls = msg["tool_calls"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            ClientToolCall(id, name, args)
        }.orEmpty()
        GenerationResult(content, conn.getHeaderField("X-Request-Id"), calls)
    }

    fun generateStream(
        prompt: String, model: String = config.model, system: String? = null,
        temperature: Float? = null, maxTokens: Int? = null,
    ): Flow<String> = chatStream(listOfNotNull(system?.let { ChatMessage("system", it) }, ChatMessage("user", prompt)), model, temperature, maxTokens)

    fun chatStream(messages: List<ChatMessage>, model: String = config.model, temperature: Float? = null, maxTokens: Int? = null): Flow<String> =
        callbackFlow {
            var conn: java.net.HttpURLConnection? = null
            var requestId: String? = null
            var done = false
            launch(Dispatchers.IO) {
                try {
                    val c = api.open("POST", "/chat/completions", body = body(messages, model, temperature, maxTokens, true), accept = "text/event-stream")
                    conn = c
                    val code = c.responseCode
                    requestId = c.getHeaderField("X-Request-Id")
                    if (code !in 200..299) throw api.mapError(code, c.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
                    c.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") { done = true; break }
                            val obj = api.json.parseToJsonElement(data).jsonObject
                            obj["error"]?.let { e -> throw api.mapError(500, """{"error":$e}""") }
                            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                            choice?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.let { trySend(it) }
                            if (choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "cancelled")
                                throw RequestCancelledException("Request was cancelled")
                        }
                    }
                    if (!done) throw ProviderNotRunningException("Stream ended unexpectedly")
                    close()
                } catch (e: UniversalAIException) { close(e) }
                catch (e: IOException) { if (isActive) close(ProviderNotRunningException("Connection lost: ${e.message}", e)) }
                catch (e: Exception) { close(e) }
            }
            awaitClose {
                conn?.disconnect()
                val id = requestId
                if (!done && id != null) CoroutineScope(Dispatchers.IO).launch { runCatching { this@UniversalAIClient.cancel(id) } }
            }
        }

    suspend fun cancel(requestId: String): Boolean = withContext(Dispatchers.IO) {
        val text = api.call("POST", "/cancel", body = buildJsonObject { put("request_id", requestId) }.toString()).first
        api.json.parseToJsonElement(text).jsonObject["cancelled"]?.jsonPrimitive?.boolean == true
    }

    private fun body(
        messages: List<ChatMessage>,
        model: String,
        temperature: Float?,
        maxTokens: Int?,
        stream: Boolean,
        tools: List<ClientToolSpec> = emptyList(),
        useProviderTools: Boolean = false,
    ) = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            messages.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                    m.toolCallId?.let { put("tool_call_id", it) }
                    m.name?.let { put("name", it) }
                })
            }
        })
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        put("stream", stream)
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        })
                    })
                }
            })
        }
        if (useProviderTools) put("use_provider_tools", true)
    }.toString()
}
