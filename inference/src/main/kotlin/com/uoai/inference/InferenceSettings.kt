package com.uoai.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Preferred compute backend passed to the inference runtime when supported. */
@Serializable
enum class AcceleratorBackend { AUTO, CPU, GPU, NPU }

/** Runtime load-time options active for the currently loaded model instance. */
@Serializable
data class RuntimeLoadConfig(
    val contextWindowTokens: Int = InferenceSettings.DEFAULT_CONTEXT_TOKENS,
    val maxTopK: Int = InferenceSettings.MAX_TOP_K,
    val preferredBackend: AcceleratorBackend = AcceleratorBackend.AUTO,
)

/**
 * Persisted provider-wide model and inference defaults, supporting context windows and
 * max output tokens up to 32K (32,768).
 */
@Serializable
data class InferenceSettings(
    val contextWindowTokens: Int = DEFAULT_CONTEXT_TOKENS,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val preferredBackend: AcceleratorBackend = AcceleratorBackend.AUTO,
    val huggingFaceRepo: String = "litert-community/gemma-4-E2B-it-litert-lm",
    val huggingFaceFile: String = "gemma-4-E2B-it.litertlm",
) {
    /** Returns a copy with [contextWindowTokens] clamped to 512..32K and [maxOutputTokens] capped to fit. */
    fun withContextWindow(tokens: Int): InferenceSettings {
        val clampedCtx = tokens.coerceIn(MIN_CONTEXT_TOKENS, MAX_TOKENS_32K)
        return copy(
            contextWindowTokens = clampedCtx,
            maxOutputTokens = maxOutputTokens.coerceIn(MIN_OUTPUT_TOKENS, clampedCtx),
        )
    }

    /**
     * Returns a copy with [maxOutputTokens] clamped to 64..32K, expanding [contextWindowTokens]
     * automatically if needed so selecting 16K or 32K output never silently snaps back.
     */
    fun withMaxOutputTokens(tokens: Int): InferenceSettings {
        val clampedOut = tokens.coerceIn(MIN_OUTPUT_TOKENS, MAX_TOKENS_32K)
        val expandedCtx = maxOf(contextWindowTokens.coerceIn(MIN_CONTEXT_TOKENS, MAX_TOKENS_32K), clampedOut)
        return copy(
            contextWindowTokens = expandedCtx,
            maxOutputTokens = clampedOut,
        )
    }

    /** Returns a copy with all fields clamped into valid bounds. */
    fun sanitized(): InferenceSettings {
        val ctx = contextWindowTokens.coerceIn(MIN_CONTEXT_TOKENS, MAX_TOKENS_32K)
        val out = maxOutputTokens.coerceIn(MIN_OUTPUT_TOKENS, ctx)
        return copy(
            contextWindowTokens = ctx,
            maxOutputTokens = out,
            temperature = temperature.coerceIn(0f, 2f),
            topP = topP.coerceIn(0.05f, 1f),
            topK = topK.coerceIn(1, MAX_TOP_K),
            huggingFaceRepo = huggingFaceRepo.trim().ifEmpty { "litert-community/gemma-4-E2B-it-litert-lm" },
            huggingFaceFile = huggingFaceFile.trim().ifEmpty { "gemma-4-E2B-it.litertlm" },
        )
    }

    fun toLoadConfig(): RuntimeLoadConfig = RuntimeLoadConfig(
        contextWindowTokens = contextWindowTokens.coerceIn(MIN_CONTEXT_TOKENS, MAX_TOKENS_32K),
        maxTopK = MAX_TOP_K,
        preferredBackend = preferredBackend,
    )

    companion object {
        const val MIN_CONTEXT_TOKENS = 512
        const val MIN_OUTPUT_TOKENS = 64
        const val DEFAULT_CONTEXT_TOKENS = 4096
        const val DEFAULT_MAX_OUTPUT_TOKENS = 2048
        const val MAX_TOKENS_32K = 32_768
        const val MAX_TOP_K = 128

        val CONTEXT_PRESETS = listOf(1024, 2048, 4096, 8192, 16384, 32768)
        val OUTPUT_PRESETS = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768)
    }
}

/** Observable repository for [InferenceSettings]. */
interface InferenceSettingsRepository {
    val settings: StateFlow<InferenceSettings>
    fun update(transform: (InferenceSettings) -> InferenceSettings): InferenceSettings
    fun reset(): InferenceSettings
}

/** JSON-file-backed implementation of [InferenceSettingsRepository]. */
class FileInferenceSettingsRepository(private val file: File) : InferenceSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val _settings = MutableStateFlow(readInitial())
    override val settings: StateFlow<InferenceSettings> = _settings.asStateFlow()

    private fun readInitial(): InferenceSettings {
        if (!file.isFile) return InferenceSettings()
        return runCatching { json.decodeFromString<InferenceSettings>(file.readText()).sanitized() }
            .getOrDefault(InferenceSettings())
    }

    @Synchronized
    override fun update(transform: (InferenceSettings) -> InferenceSettings): InferenceSettings {
        val next = transform(_settings.value).sanitized()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(next))
        _settings.value = next
        return next
    }

    @Synchronized
    override fun reset(): InferenceSettings = update { InferenceSettings() }
}

