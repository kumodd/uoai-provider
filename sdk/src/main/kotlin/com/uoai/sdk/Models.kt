package com.uoai.sdk

import android.content.Intent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** FR-07 client configuration. */
@Serializable
data class ClientConfig(
    val provider: String = "universal.offline.ai",
    val connection: String = "http",
    val baseUrl: String = "http://127.0.0.1:8765/v1",
    val model: String = "auto",
    val features: List<String> = listOf("chat", "streaming", "tool-calling"),
    val providerPackage: String = "com.uoai.provider",
    val requestTimeoutMs: Int = 300_000,
)

@Serializable
data class ClientToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object describing arguments. */
    val parameters: JsonObject,
)

@Serializable
data class ClientToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val name: String? = null,
)

data class GenerationResult(
    val text: String,
    val requestId: String?,
    val toolCalls: List<ClientToolCall> = emptyList(),
)

@Serializable data class ProviderHealth(
    val status: String,
    @SerialName("model_state") val modelState: String,
    @SerialName("model_loaded") val modelLoaded: Boolean,
    @SerialName("model_error") val modelError: String? = null,
) { val isReady get() = modelLoaded }

@Serializable data class ModelInfo(
    val id: String, val name: String? = null, val format: String? = null, val quantization: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null, val ready: Boolean = false,
    @SerialName("context_window") val contextWindow: Int? = null,
)

@Serializable data class AsrCapabilities(
    val enabled: Boolean = true,
    @SerialName("active_model_id") val activeModelId: String = "whisper-base-q5",
    @SerialName("active_profile") val activeProfile: String = "balanced",
    val ready: Boolean = false,
    @SerialName("max_upload_bytes") val maxUploadBytes: Long = 26_214_400L,
    @SerialName("max_audio_duration_sec") val maxAudioDurationSec: Int = 600,
    @SerialName("concurrent_transcriptions") val concurrentTranscriptions: Int = 1,
    val streaming: Boolean = true,
    @SerialName("supported_formats") val supportedFormats: List<String> = emptyList(),
    @SerialName("gemma4_audio_ready") val gemma4AudioReady: Boolean = false,
    @SerialName("preferred_provider") val preferredProvider: String = "gemma4",
    @SerialName("external_inference_enabled") val externalInferenceEnabled: Boolean = true,
    val providers: List<String> = emptyList(),
)

@Serializable data class Capabilities(
    val features: List<String>,
    @SerialName("max_request_bytes") val maxRequestBytes: Int,
    @SerialName("max_tokens_limit") val maxTokensLimit: Int,
    @SerialName("concurrent_generations") val concurrentGenerations: Int,
    val embeddings: Boolean,
    @SerialName("provider_tools") val providerTools: List<String> = emptyList(),
    @SerialName("context_window_tokens") val contextWindowTokens: Int? = null,
    @SerialName("default_max_tokens") val defaultMaxTokens: Int? = null,
    val asr: AsrCapabilities? = null,
)

@Serializable data class AudioModelInfo(
    val id: String,
    val profile: String,
    val name: String,
    @SerialName("file_name") val fileName: String? = null,
    val format: String? = null,
    val quantization: String? = null,
    val multilingual: Boolean = true,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    @SerialName("approx_ram_mb") val approxRamMb: Int = 0,
    val installed: Boolean = false,
    val selected: Boolean = false,
    val loaded: Boolean = false,
    val recommended: Boolean = false,
    val provider: String = "whisper",
    @SerialName("engine_type") val engineType: String = "local_asr",
)

@Serializable data class AudioStatus(
    val state: String,
    @SerialName("model_loaded") val modelLoaded: Boolean,
    @SerialName("active_model_id") val activeModelId: String,
    @SerialName("active_profile") val activeProfile: String,
    @SerialName("auto_load_enabled") val autoLoadEnabled: Boolean = true,
    @SerialName("default_language") val defaultLanguage: String = "auto",
    @SerialName("queue_depth") val queueDepth: Int = 0,
    @SerialName("running_request_id") val runningRequestId: String? = null,
    @SerialName("download_progress") val downloadProgress: Float? = null,
    val error: String? = null,
    @SerialName("max_upload_bytes") val maxUploadBytes: Long = 26_214_400L,
    @SerialName("max_audio_duration_sec") val maxAudioDurationSec: Int = 600,
    @SerialName("supported_formats") val supportedFormats: List<String> = emptyList(),
    @SerialName("gemma4_audio_ready") val gemma4AudioReady: Boolean = false,
    @SerialName("preferred_provider") val preferredProvider: String = "gemma4",
    @SerialName("external_providers_count") val externalProvidersCount: Int = 0,
) {
    val isReady: Boolean get() = modelLoaded || gemma4AudioReady
}

@Serializable data class TranscriptionSegment(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    @SerialName("no_speech_prob") val noSpeechProb: Float? = null,
)

@Serializable data class TranscriptionResult(
    val id: String,
    val text: String,
    val model: String,
    val language: String,
    val duration: Double? = null,
    val segments: List<TranscriptionSegment> = emptyList(),
    @SerialName("elapsed_ms") val elapsedMs: Long? = null,
    val provider: String? = null,
    @SerialName("roman_hinglish") val romanHinglish: String? = null,
    @SerialName("devanagari_hinglish") val devanagariHinglish: String? = null,
)

@Serializable data class AudioProviderInfo(
    val id: String,
    val name: String,
    val kind: String,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val ready: Boolean = false,
    val recommended: Boolean = false,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("default_model") val defaultModel: String? = null,
    val models: List<String> = emptyList(),
    @SerialName("has_api_key") val hasApiKey: Boolean? = null,
    @SerialName("supports_streaming") val supportsStreaming: Boolean = false,
    val note: String? = null,
)

@Serializable data class ExternalAudioProviderRegistration(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    /** One of: `openai_compatible_audio`, `openai_compatible_chat_audio`, `gemini_multimodal`. */
    val type: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("default_model") val defaultModel: String,
    @SerialName("supported_models") val supportedModels: List<String> = emptyList(),
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("api_key_header") val apiKeyHeader: String? = null,
    @SerialName("api_key_prefix") val apiKeyPrefix: String? = null,
    @SerialName("custom_headers") val customHeaders: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    @SerialName("timeout_ms") val timeoutMs: Long = 120_000L,
)

sealed interface TranscriptionStreamEvent {
    data class Started(val requestId: String, val model: String, val language: String) : TranscriptionStreamEvent
    data class Segment(val requestId: String, val segment: TranscriptionSegment, val language: String) : TranscriptionStreamEvent
    data class Completed(
        val requestId: String,
        val model: String,
        val language: String,
        val text: String,
        val duration: Double?,
    ) : TranscriptionStreamEvent
}

@Serializable data class AudioModelLoadResult(
    val loaded: Boolean,
    @SerialName("model_id") val modelId: String,
    val profile: String,
    val state: String,
    @SerialName("load_time_ms") val loadTimeMs: Long = 0L,
)

@Serializable data class AudioModelUnloadResult(
    val unloaded: Boolean,
    val state: String,
)

sealed interface RegistrationResult {
    data object Approved : RegistrationResult
    data class Denied(val code: String, val message: String) : RegistrationResult
}

sealed interface ConnectionState {
    data object ProviderNotInstalled : ConnectionState
    data class NeedsRegistration(val registrationIntent: Intent) : ConnectionState
    data class ProviderNotRunning(val launchProviderIntent: Intent?, val message: String) : ConnectionState
    data class ModelNotReady(
        val modelState: String,
        val launchProviderIntent: Intent?,
        val message: String,
    ) : ConnectionState
    data class Connected(
        val health: ProviderHealth,
        val activeModel: ModelInfo?,
        val capabilities: Capabilities,
    ) : ConnectionState
    data class Error(val message: String, val cause: Throwable? = null) : ConnectionState
}
