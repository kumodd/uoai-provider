package com.uoai.providerapi.external

import com.uoai.providerapi.ApiException
import com.uoai.providerapi.ApiJson
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Wire protocol spoken by a configurable external audio inference provider. */
@Serializable
enum class ExternalProviderType(val apiId: String, val label: String) {
    /** Standard OpenAI-compatible multipart `POST {baseUrl}/audio/transcriptions` (OpenAI, Groq, vLLM, LocalAI, Faster-Whisper). */
    @SerialName("openai_compatible_audio")
    OPENAI_COMPATIBLE_AUDIO("openai_compatible_audio", "OpenAI-Compatible Audio (/audio/transcriptions)"),

    /** OpenAI-compatible multimodal `POST {baseUrl}/chat/completions` with `input_audio` WAV payload (remote Gemma 4 / vLLM / LiteLLM / OpenRouter). */
    @SerialName("openai_compatible_chat_audio")
    OPENAI_COMPATIBLE_CHAT_AUDIO("openai_compatible_chat_audio", "OpenAI-Compatible Multimodal Chat (Gemma 4 / Audio LLM)"),

    /** Google Gemini / Gemma REST API `POST {baseUrl}/models/{model}:generateContent` with inline base64 `audio/wav`. */
    @SerialName("gemini_multimodal")
    GEMINI_MULTIMODAL("gemini_multimodal", "Gemini / Gemma Multimodal API (:generateContent)"),
    ;

    fun defaultAuthHeader(): String = when (this) {
        GEMINI_MULTIMODAL -> "x-goog-api-key"
        OPENAI_COMPATIBLE_AUDIO, OPENAI_COMPATIBLE_CHAT_AUDIO -> "Authorization"
    }

    fun defaultAuthPrefix(): String = when (this) {
        GEMINI_MULTIMODAL -> ""
        OPENAI_COMPATIBLE_AUDIO, OPENAI_COMPATIBLE_CHAT_AUDIO -> "Bearer "
    }

    companion object {
        fun parseOrNull(raw: String?): ExternalProviderType? {
            val norm = raw?.trim()?.lowercase()?.replace('-', '_') ?: return null
            return entries.firstOrNull { it.apiId == norm || it.name.lowercase() == norm }
        }
    }
}

/**
 * Non-secret configuration for a developer-registered external inference provider.
 * Upstream API keys are never persisted in this JSON structure; they live in [ExternalSecretStore].
 */
@Serializable
data class ExternalAudioProviderConfig(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val type: ExternalProviderType,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("default_model") val defaultModel: String,
    @SerialName("supported_models") val supportedModels: List<String> = emptyList(),
    @SerialName("api_key_header") val apiKeyHeader: String = type.defaultAuthHeader(),
    @SerialName("api_key_prefix") val apiKeyPrefix: String = type.defaultAuthPrefix(),
    @SerialName("custom_headers") val customHeaders: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    @SerialName("timeout_ms") val timeoutMs: Long = 120_000L,
) {
    fun allModels(): List<String> =
        (listOf(defaultModel) + supportedModels)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun matchesModel(requestedModel: String): Boolean =
        allModels().any { it.equals(requestedModel.trim(), ignoreCase = true) }
}

/** Abstraction for storing upstream external provider API keys (backed by Android Keystore in `:provider-app`). */
interface ExternalSecretStore {
    fun get(providerId: String): String?
    fun set(providerId: String, apiKey: String?)
    fun has(providerId: String): Boolean = !get(providerId).isNullOrBlank()
}

/** In-memory secret store used for unit tests or ephemeral provider registrations. */
class InMemoryExternalSecretStore : ExternalSecretStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(providerId: String): String? = map[providerId]
    override fun set(providerId: String, apiKey: String?) {
        if (apiKey.isNullOrBlank()) map.remove(providerId) else map[providerId] = apiKey.trim()
    }
}

/**
 * Thread-safe registry of developer-configured external inference providers.
 * Persists non-secret metadata to [storageFile] when supplied and delegates secrets to [secretStore].
 */
class ExternalAudioProviderRegistry(
    private val storageFile: File? = null,
    private val secretStore: ExternalSecretStore = InMemoryExternalSecretStore(),
) {
    private val lock = Any()
    private val _providers = MutableStateFlow(loadInitial())
    val providers: StateFlow<List<ExternalAudioProviderConfig>> = _providers.asStateFlow()

    fun list(): List<ExternalAudioProviderConfig> = _providers.value

    fun get(id: String): ExternalAudioProviderConfig? {
        val norm = id.trim().lowercase()
        return _providers.value.firstOrNull { it.id.equals(norm, ignoreCase = true) }
    }

    fun findEnabledForModel(requestedModel: String): ExternalAudioProviderConfig? {
        val norm = requestedModel.trim()
        if (norm.isEmpty()) return null
        return _providers.value.firstOrNull { it.enabled && it.matchesModel(norm) }
    }

    fun hasApiKey(providerId: String): Boolean = secretStore.has(providerId.trim().lowercase())
    fun getApiKey(providerId: String): String? = secretStore.get(providerId.trim().lowercase())

    fun upsert(
        config: ExternalAudioProviderConfig,
        apiKey: String? = null,
        updateApiKey: Boolean = apiKey != null,
    ): ExternalAudioProviderConfig = synchronized(lock) {
        val cleanId = config.id.trim().lowercase()
        require(ID_PATTERN.matches(cleanId)) {
            "Provider id '$cleanId' must be 2–48 chars of lowercase letters, digits, '.', '_' or '-'"
        }
        require(cleanId !in RESERVED_PROVIDER_IDS) {
            "Provider id '$cleanId' is reserved for built-in local providers"
        }
        val cleanUrl = config.baseUrl.trim().trimEnd('/')
        require(cleanUrl.startsWith("http://", ignoreCase = true) || cleanUrl.startsWith("https://", ignoreCase = true)) {
            "base_url must start with http:// or https://"
        }
        val cleanDefaultModel = config.defaultModel.trim()
        require(cleanDefaultModel.isNotEmpty()) { "default_model must not be blank" }

        val normalized = config.copy(
            id = cleanId,
            displayName = config.displayName.trim().ifEmpty { cleanId },
            baseUrl = cleanUrl,
            defaultModel = cleanDefaultModel,
            supportedModels = config.supportedModels.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            apiKeyHeader = config.apiKeyHeader.trim().ifEmpty { config.type.defaultAuthHeader() },
            timeoutMs = config.timeoutMs.coerceIn(5_000L, 300_000L),
        )

        if (updateApiKey) {
            secretStore.set(cleanId, apiKey)
        }
        val updated = _providers.value.filterNot { it.id == cleanId } + normalized
        persist(updated)
        _providers.value = updated
        normalized
    }

    fun setEnabled(id: String, enabled: Boolean): ExternalAudioProviderConfig? = synchronized(lock) {
        val cleanId = id.trim().lowercase()
        val existing = _providers.value.firstOrNull { it.id == cleanId } ?: return null
        val next = existing.copy(enabled = enabled)
        val updated = _providers.value.map { if (it.id == cleanId) next else it }
        persist(updated)
        _providers.value = updated
        next
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val cleanId = id.trim().lowercase()
        require(cleanId !in RESERVED_PROVIDER_IDS) {
            "Cannot remove built-in local provider '$cleanId'"
        }
        val before = _providers.value
        val after = before.filterNot { it.id == cleanId }
        if (after.size == before.size) return false
        secretStore.set(cleanId, null)
        persist(after)
        _providers.value = after
        true
    }

    private fun loadInitial(): List<ExternalAudioProviderConfig> {
        val f = storageFile ?: return emptyList()
        if (!f.isFile) return emptyList()
        return runCatching {
            ApiJson.decodeFromString<List<ExternalAudioProviderConfig>>(f.readText())
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<ExternalAudioProviderConfig>) {
        val f = storageFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(ApiJson.encodeToString(list))
            if (!tmp.renameTo(f)) {
                f.writeText(ApiJson.encodeToString(list))
                tmp.delete()
            }
        }
    }

    companion object {
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{1,47}$")
        val RESERVED_PROVIDER_IDS = setOf(
            "auto",
            "default",
            "gemma4",
            "gemma-4",
            "local-gemma4",
            "llm",
            "whisper",
            "local-whisper",
            "cactus",
            "hinglish",
            "local-hinglish",
            "external",
        )
    }
}

data class ExternalAudioRequest(
    val requestId: String,
    val wavBytes: ByteArray,
    val fileName: String = "audio.wav",
    val contentType: String = "audio/wav",
    val durationSec: Double,
    val model: String,
    val language: String? = null,
    val prompt: String? = null,
    val temperature: Float = 0.0f,
    val translate: Boolean = false,
)

data class ExternalAudioSegment(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val text: String,
)

data class ExternalAudioResult(
    val text: String,
    val model: String,
    val providerId: String,
    val language: String,
    val durationSec: Double,
    val elapsedMs: Long,
    val segments: List<ExternalAudioSegment> = emptyList(),
)

/** Transport interface for invoking external inference providers (HTTP in production, mockable in unit tests). */
interface ExternalAudioProviderClient {
    suspend fun transcribe(
        config: ExternalAudioProviderConfig,
        apiKey: String?,
        request: ExternalAudioRequest,
    ): ExternalAudioResult
}

/**
 * Dependency-free HTTP transport supporting OpenAI-compatible audio endpoints, OpenAI-compatible
 * multimodal chat endpoints (e.g. remote Gemma 4 servers), and Gemini/Gemma REST endpoints.
 */
class HttpExternalAudioProviderClient : ExternalAudioProviderClient {

    override suspend fun transcribe(
        config: ExternalAudioProviderConfig,
        apiKey: String?,
        request: ExternalAudioRequest,
    ): ExternalAudioResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val result = when (config.type) {
            ExternalProviderType.OPENAI_COMPATIBLE_AUDIO -> callOpenAiCompatibleAudio(config, apiKey, request)
            ExternalProviderType.OPENAI_COMPATIBLE_CHAT_AUDIO -> callOpenAiCompatibleChatAudio(config, apiKey, request)
            ExternalProviderType.GEMINI_MULTIMODAL -> callGeminiMultimodal(config, apiKey, request)
        }
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        result.copy(elapsedMs = elapsed)
    }

    private fun callOpenAiCompatibleAudio(
        config: ExternalAudioProviderConfig,
        apiKey: String?,
        request: ExternalAudioRequest,
    ): ExternalAudioResult {
        val path = if (request.translate) "/audio/translations" else "/audio/transcriptions"
        val endpoint = config.baseUrl.trimEnd('/') + path
        val boundary = "----UoaiExtBoundary" + UUID.randomUUID().toString().replace("-", "")
        val safeName = request.fileName.replace("\"", "").replace("\r", "").replace("\n", "").ifBlank { "audio.wav" }

        val fields = buildMap {
            put("model", request.model)
            put("response_format", "verbose_json")
            put("temperature", request.temperature.toString())
            if (!request.translate && !request.language.isNullOrBlank() && request.language != "auto") {
                put("language", request.language)
            }
            request.prompt?.takeIf { it.isNotBlank() }?.let { put("prompt", it) }
        }

        val responseText = executeHttp(
            url = endpoint,
            config = config,
            apiKey = apiKey,
            contentType = "multipart/form-data; boundary=$boundary",
        ) { out ->
            for ((k, v) in fields) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                out.write(v.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray())
            }
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray())
            out.write("Content-Type: ${request.contentType}\r\n\r\n".toByteArray())
            out.write(request.wavBytes)
            out.write("\r\n--$boundary--\r\n".toByteArray())
            out.flush()
        }

        val parsedObj = runCatching { ApiJson.parseToJsonElement(responseText).jsonObject }.getOrNull()
        if (parsedObj == null) {
            return ExternalAudioResult(
                text = responseText.trim(),
                model = request.model,
                providerId = config.id,
                language = request.language ?: "auto",
                durationSec = request.durationSec,
                elapsedMs = 1L,
            )
        }

        val text = parsedObj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val lang = parsedObj["language"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: request.language
            ?: "auto"
        val duration = parsedObj["duration"]?.jsonPrimitive?.doubleOrNull ?: request.durationSec
        val segments = (parsedObj["segments"] as? JsonArray)?.mapIndexedNotNull { idx, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val segText = obj["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapIndexedNotNull null
            ExternalAudioSegment(
                id = obj["id"]?.jsonPrimitive?.intOrNull ?: idx,
                startSec = obj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                endSec = obj["end"]?.jsonPrimitive?.doubleOrNull ?: duration,
                text = segText,
            )
        }.orEmpty()

        return ExternalAudioResult(
            text = text,
            model = request.model,
            providerId = config.id,
            language = lang,
            durationSec = duration,
            elapsedMs = 1L,
            segments = segments,
        )
    }

    private fun callOpenAiCompatibleChatAudio(
        config: ExternalAudioProviderConfig,
        apiKey: String?,
        request: ExternalAudioRequest,
    ): ExternalAudioResult {
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val audioB64 = Base64.getEncoder().encodeToString(request.wavBytes)
        val systemPrompt = Gemma4AudioTranscriber.buildSystemInstruction(request.language, request.translate)
        val userPrompt = Gemma4AudioTranscriber.buildUserPrompt(request.prompt, request.translate)

        val payload = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature.coerceIn(0f, 1f))
            put("stream", false)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_audio")
                            put("input_audio", buildJsonObject {
                                put("data", audioB64)
                                put("format", "wav")
                            })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userPrompt)
                        })
                    })
                })
            })
        }.toString()

        val responseText = executeHttp(
            url = endpoint,
            config = config,
            apiKey = apiKey,
            contentType = "application/json",
        ) { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        val root = runCatching { ApiJson.parseToJsonElement(responseText).jsonObject }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadGateway, "external_provider_error", "invalid_external_response", "External provider '${config.id}' returned non-JSON payload")
        val msgObj = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        val contentEl = msgObj?.get("content")
        val text = when (contentEl) {
            is JsonArray -> contentEl.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("").trim()
            else -> contentEl?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        }
        val returnedModel = root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: request.model
        return ExternalAudioResult(
            text = text,
            model = returnedModel,
            providerId = config.id,
            language = request.language ?: "auto",
            durationSec = request.durationSec,
            elapsedMs = 1L,
        )
    }

    private fun callGeminiMultimodal(
        config: ExternalAudioProviderConfig,
        apiKey: String?,
        request: ExternalAudioRequest,
    ): ExternalAudioResult {
        val cleanModel = request.model.removePrefix("models/")
        val endpoint = "${config.baseUrl.trimEnd('/')}/models/$cleanModel:generateContent"
        val audioB64 = Base64.getEncoder().encodeToString(request.wavBytes)
        val systemPrompt = Gemma4AudioTranscriber.buildSystemInstruction(request.language, request.translate)
        val userPrompt = Gemma4AudioTranscriber.buildUserPrompt(request.prompt, request.translate)

        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "audio/wav")
                                put("data", audioB64)
                            })
                        })
                        add(buildJsonObject { put("text", userPrompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", request.temperature.coerceIn(0f, 1f))
            })
        }.toString()

        val responseText = executeHttp(
            url = endpoint,
            config = config,
            apiKey = apiKey,
            contentType = "application/json",
        ) { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        val root = runCatching { ApiJson.parseToJsonElement(responseText).jsonObject }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadGateway, "external_provider_error", "invalid_external_response", "External provider '${config.id}' returned non-JSON payload")
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
        val text = parts?.mapNotNull { p ->
            (p as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }?.joinToString("")?.trim().orEmpty()

        return ExternalAudioResult(
            text = text,
            model = cleanModel,
            providerId = config.id,
            language = request.language ?: "auto",
            durationSec = request.durationSec,
            elapsedMs = 1L,
        )
    }

    private fun executeHttp(
        url: String,
        config: ExternalAudioProviderConfig,
        apiKey: String?,
        contentType: String,
        writeBody: (java.io.OutputStream) -> Unit,
    ): String {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = minOf(config.timeoutMs.toInt(), 15_000)
                readTimeout = config.timeoutMs.toInt()
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", contentType)
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty(config.apiKeyHeader, "${config.apiKeyPrefix}$apiKey")
                }
                config.customHeaders.forEach { (k, v) ->
                    if (k.isNotBlank()) setRequestProperty(k, v)
                }
                outputStream.use(writeBody)
            }
        } catch (e: SocketTimeoutException) {
            throw ApiException(
                HttpStatusCode.GatewayTimeout,
                "external_provider_error",
                "external_provider_timeout",
                "External provider '${config.id}' timed out",
            )
        } catch (e: IOException) {
            throw ApiException(
                HttpStatusCode.BadGateway,
                "external_provider_error",
                "external_provider_unreachable",
                "Failed to reach external provider '${config.id}': ${e.message}",
            )
        }

        return try {
            val code = try {
                conn.responseCode
            } catch (e: SocketTimeoutException) {
                throw ApiException(
                    HttpStatusCode.GatewayTimeout,
                    "external_provider_error",
                    "external_provider_timeout",
                    "External provider '${config.id}' timed out reading response",
                )
            } catch (e: IOException) {
                throw ApiException(
                    HttpStatusCode.BadGateway,
                    "external_provider_error",
                    "external_provider_unreachable",
                    "Connection to external provider '${config.id}' failed: ${e.message}",
                )
            }
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val snippet = body.take(300).ifBlank { "HTTP $code" }
                throw ApiException(
                    HttpStatusCode.BadGateway,
                    "external_provider_error",
                    "external_provider_http_$code",
                    "External provider '${config.id}' returned HTTP $code: $snippet",
                )
            }
            body
        } finally {
            conn.disconnect()
        }
    }
}
