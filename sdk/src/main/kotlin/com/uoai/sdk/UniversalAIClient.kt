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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
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

    // ---- Shared Local & External Audio Transcription APIs ----

    suspend fun audioModels(): List<AudioModelInfo> = withContext(Dispatchers.IO) {
        val data = api.json.parseToJsonElement(api.call("GET", "/audio/models").first).jsonObject["data"] ?: JsonArray(emptyList())
        api.json.decodeFromJsonElement(ListSerializer(AudioModelInfo.serializer()), data)
    }

    suspend fun audioProviders(): List<AudioProviderInfo> = withContext(Dispatchers.IO) {
        val data = api.json.parseToJsonElement(api.call("GET", "/audio/providers").first).jsonObject["data"] ?: JsonArray(emptyList())
        api.json.decodeFromJsonElement(ListSerializer(AudioProviderInfo.serializer()), data)
    }

    suspend fun registerExternalAudioProvider(registration: ExternalAudioProviderRegistration): AudioProviderInfo =
        withContext(Dispatchers.IO) {
            val payload = api.json.encodeToString(ExternalAudioProviderRegistration.serializer(), registration)
            val text = api.call("POST", "/audio/providers", body = payload).first
            api.json.decodeFromString(AudioProviderInfo.serializer(), text)
        }

    suspend fun removeExternalAudioProvider(providerId: String): Boolean = withContext(Dispatchers.IO) {
        val text = api.call("DELETE", "/audio/providers/${providerId.trim()}").first
        api.json.parseToJsonElement(text).jsonObject["deleted"]?.jsonPrimitive?.boolean == true
    }

    suspend fun audioStatus(): AudioStatus = withContext(Dispatchers.IO) {
        api.json.decodeFromString(AudioStatus.serializer(), api.call("GET", "/audio/status").first)
    }

    suspend fun transcribe(
        audioFile: File,
        model: String = "auto",
        language: String? = null,
        prompt: String? = null,
        translate: Boolean = false,
        temperature: Float = 0.0f,
        timestamps: Boolean = true,
        contentType: String = inferAudioMimeType(audioFile.name),
        provider: String? = null,
        script: String? = null,
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val fields = buildAsrFormFields(model, provider, language, prompt, script, translate, temperature, timestamps)
        val (text, _) = api.callMultipart(
            path = "/audio/transcriptions",
            fileName = audioFile.name,
            fileContentType = contentType,
            audioStream = FileInputStream(audioFile),
            fields = fields,
        )
        api.json.decodeFromString(TranscriptionResult.serializer(), text)
    }

    suspend fun transcribe(
        audioBytes: ByteArray,
        fileName: String = "audio.wav",
        contentType: String = inferAudioMimeType(fileName),
        model: String = "auto",
        language: String? = null,
        prompt: String? = null,
        translate: Boolean = false,
        temperature: Float = 0.0f,
        timestamps: Boolean = true,
        provider: String? = null,
        script: String? = null,
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val fields = buildAsrFormFields(model, provider, language, prompt, script, translate, temperature, timestamps)
        val (text, _) = api.callMultipart(
            path = "/audio/transcriptions",
            fileName = fileName,
            fileContentType = contentType,
            audioStream = ByteArrayInputStream(audioBytes),
            fields = fields,
        )
        api.json.decodeFromString(TranscriptionResult.serializer(), text)
    }

    fun transcribeStream(
        audioFile: File,
        model: String = "auto",
        language: String? = null,
        prompt: String? = null,
        translate: Boolean = false,
        temperature: Float = 0.0f,
        contentType: String = inferAudioMimeType(audioFile.name),
        provider: String? = null,
        script: String? = null,
    ): Flow<TranscriptionStreamEvent> = callbackFlow {
        var conn: java.net.HttpURLConnection? = null
        var requestId: String? = null
        var done = false
        launch(Dispatchers.IO) {
            try {
                val fields = buildAsrFormFields(model, provider, language, prompt, script, translate, temperature, timestamps = true)
                val c = api.openMultipart(
                    path = "/audio/stream",
                    fileName = audioFile.name,
                    fileContentType = contentType,
                    audioStream = FileInputStream(audioFile),
                    fields = fields,
                    accept = "text/event-stream",
                )
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
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: requestId.orEmpty()
                        when (obj["object"]?.jsonPrimitive?.contentOrNull) {
                            "audio.transcription.start" -> trySend(
                                TranscriptionStreamEvent.Started(
                                    requestId = id,
                                    model = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    language = obj["language"]?.jsonPrimitive?.contentOrNull ?: "auto",
                                ),
                            )
                            "audio.transcription.segment" -> trySend(
                                TranscriptionStreamEvent.Segment(
                                    requestId = id,
                                    segment = TranscriptionSegment(
                                        id = obj["index"]?.jsonPrimitive?.intOrNull ?: 0,
                                        start = obj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                                        end = obj["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                                        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    ),
                                    language = obj["language"]?.jsonPrimitive?.contentOrNull ?: "auto",
                                ),
                            )
                            "audio.transcription.done" -> {
                                if (obj["finish_reason"]?.jsonPrimitive?.contentOrNull == "cancelled") {
                                    throw RequestCancelledException("ASR request was cancelled")
                                }
                                trySend(
                                    TranscriptionStreamEvent.Completed(
                                        requestId = id,
                                        model = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                        language = obj["language"]?.jsonPrimitive?.contentOrNull ?: "auto",
                                        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                        duration = obj["duration"]?.jsonPrimitive?.doubleOrNull,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (!done) throw ProviderNotRunningException("ASR stream ended unexpectedly")
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

    suspend fun loadAudioModel(model: String? = null): AudioModelLoadResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { model?.let { put("model", it) } }.toString()
        val text = api.call("POST", "/audio/models/load", body = payload).first
        api.json.decodeFromString(AudioModelLoadResult.serializer(), text)
    }

    suspend fun unloadAudioModel(force: Boolean = false): AudioModelUnloadResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("force", force) }.toString()
        val text = api.call("POST", "/audio/models/unload", body = payload).first
        api.json.decodeFromString(AudioModelUnloadResult.serializer(), text)
    }

    private fun buildAsrFormFields(
        model: String,
        provider: String?,
        language: String?,
        prompt: String?,
        script: String?,
        translate: Boolean,
        temperature: Float,
        timestamps: Boolean,
    ): Map<String, String> = buildMap {
        put("model", model)
        provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
        language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
        prompt?.takeIf { it.isNotBlank() }?.let { put("prompt", it) }
        script?.takeIf { it.isNotBlank() }?.let { put("script", it) }
        put("response_format", if (timestamps) "verbose_json" else "json")
        put("temperature", temperature.toString())
        if (translate) put("translate", "true")
        if (timestamps) put("timestamps", "true")
    }

    private fun inferAudioMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "wav" -> "audio/wav"
        "pcm", "raw" -> "audio/pcm"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        "amr" -> "audio/amr"
        else -> "application/octet-stream"
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
