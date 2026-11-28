package com.uoai.providerapi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudioModelDto(
    val id: String,
    val profile: String,
    @SerialName("object") val obj: String = "audio.model",
    @SerialName("owned_by") val ownedBy: String = "universal-offline-ai",
    val name: String,
    @SerialName("file_name") val fileName: String,
    val format: String,
    val quantization: String,
    val multilingual: Boolean = true,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("approx_ram_mb") val approxRamMb: Int,
    val installed: Boolean,
    val selected: Boolean,
    val loaded: Boolean,
    val recommended: Boolean = false,
    val provider: String = "whisper",
    @SerialName("engine_type") val engineType: String = "local_asr",
)

@Serializable
data class AudioModelsResponse(
    @SerialName("object") val obj: String = "list",
    @SerialName("active_profile") val activeProfile: String,
    @SerialName("preferred_provider") val preferredProvider: String = "gemma4",
    val data: List<AudioModelDto>,
)

@Serializable
data class AudioStatusResponse(
    val state: String,
    @SerialName("model_loaded") val modelLoaded: Boolean,
    @SerialName("active_model_id") val activeModelId: String,
    @SerialName("active_profile") val activeProfile: String,
    @SerialName("auto_load_enabled") val autoLoadEnabled: Boolean,
    @SerialName("default_language") val defaultLanguage: String,
    @SerialName("queue_depth") val queueDepth: Int,
    @SerialName("running_request_id") val runningRequestId: String? = null,
    @SerialName("download_progress") val downloadProgress: Float? = null,
    val error: String? = null,
    @SerialName("max_upload_bytes") val maxUploadBytes: Long,
    @SerialName("max_audio_duration_sec") val maxAudioDurationSec: Int,
    @SerialName("supported_formats") val supportedFormats: List<String> = SUPPORTED_AUDIO_FORMATS,
    @SerialName("gemma4_audio_ready") val gemma4AudioReady: Boolean = false,
    @SerialName("preferred_provider") val preferredProvider: String = "gemma4",
    @SerialName("external_providers_count") val externalProvidersCount: Int = 0,
) {
    companion object {
        val SUPPORTED_AUDIO_FORMATS = listOf("wav", "pcm", "mp3", "m4a", "aac", "ogg", "flac", "webm", "amr")
    }
}

@Serializable
data class TranscriptionResponse(
    val id: String,
    val text: String,
    val model: String,
    val language: String,
    val provider: String? = null,
    @SerialName("roman_hinglish") val romanHinglish: String? = null,
    @SerialName("devanagari_hinglish") val devanagariHinglish: String? = null,
)

@Serializable
data class TranscriptionSegmentDto(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    @SerialName("no_speech_prob") val noSpeechProb: Float? = null,
)

@Serializable
data class VerboseTranscriptionResponse(
    val id: String,
    val task: String = "transcribe",
    val model: String,
    val language: String,
    val duration: Double,
    val text: String,
    val segments: List<TranscriptionSegmentDto> = emptyList(),
    @SerialName("elapsed_ms") val elapsedMs: Long? = null,
    val provider: String? = null,
    @SerialName("roman_hinglish") val romanHinglish: String? = null,
    @SerialName("devanagari_hinglish") val devanagariHinglish: String? = null,
)

@Serializable
data class AudioStreamEventDto(
    val id: String,
    @SerialName("object") val obj: String,
    val model: String? = null,
    val language: String? = null,
    val index: Int? = null,
    val start: Double? = null,
    val end: Double? = null,
    val text: String? = null,
    val duration: Double? = null,
    @SerialName("is_final") val isFinal: Boolean? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    val provider: String? = null,
    @SerialName("roman_hinglish") val romanHinglish: String? = null,
    @SerialName("devanagari_hinglish") val devanagariHinglish: String? = null,
)

@Serializable
data class AudioModelLoadRequest(
    val model: String? = null,
)

@Serializable
data class AudioModelLoadResponse(
    val loaded: Boolean,
    @SerialName("model_id") val modelId: String,
    val profile: String,
    val state: String,
    @SerialName("load_time_ms") val loadTimeMs: Long,
)

@Serializable
data class AudioModelUnloadRequest(
    val force: Boolean = false,
)

@Serializable
data class AudioModelUnloadResponse(
    val unloaded: Boolean,
    val state: String,
)

@Serializable
data class AudioProviderDto(
    val id: String,
    val name: String,
    val kind: String,
    val builtin: Boolean,
    val enabled: Boolean,
    val ready: Boolean,
    val recommended: Boolean = false,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("default_model") val defaultModel: String? = null,
    val models: List<String> = emptyList(),
    @SerialName("has_api_key") val hasApiKey: Boolean? = null,
    @SerialName("supports_streaming") val supportsStreaming: Boolean = false,
    val note: String? = null,
)

@Serializable
data class AudioProvidersResponse(
    @SerialName("object") val obj: String = "list",
    @SerialName("preferred_provider") val preferredProvider: String = "gemma4",
    val data: List<AudioProviderDto>,
)

@Serializable
data class RegisterExternalProviderRequest(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
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

@Serializable
data class DeleteExternalProviderResponse(
    val id: String,
    val deleted: Boolean,
)

@Serializable
data class AsrCapabilitiesDto(
    val enabled: Boolean = true,
    @SerialName("active_model_id") val activeModelId: String,
    @SerialName("active_profile") val activeProfile: String,
    val ready: Boolean,
    @SerialName("max_upload_bytes") val maxUploadBytes: Long,
    @SerialName("max_audio_duration_sec") val maxAudioDurationSec: Int,
    @SerialName("concurrent_transcriptions") val concurrentTranscriptions: Int = 1,
    val streaming: Boolean = true,
    @SerialName("supported_formats") val supportedFormats: List<String> = AudioStatusResponse.SUPPORTED_AUDIO_FORMATS,
    @SerialName("gemma4_audio_ready") val gemma4AudioReady: Boolean = false,
    @SerialName("preferred_provider") val preferredProvider: String = "gemma4",
    @SerialName("external_inference_enabled") val externalInferenceEnabled: Boolean = true,
    val providers: List<String> = emptyList(),
)

