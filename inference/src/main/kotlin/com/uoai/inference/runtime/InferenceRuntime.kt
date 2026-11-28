package com.uoai.inference.runtime

import com.uoai.inference.ChatMessage
import com.uoai.inference.RuntimeLoadConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class GenerationParams(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val topP: Float? = null,
    val topK: Int? = null,
)

/**
 * Replaceable runtime abstraction. Implementations own the native model instance.
 * Only the inference module may call it; the HTTP layer must go through [com.uoai.inference.InferenceEngine].
 */
interface InferenceRuntime {
    val isLoaded: Boolean
    val loadedConfig: RuntimeLoadConfig? get() = null
    /** True when the currently loaded native model accepts multimodal audio input (e.g. Gemma 4). */
    val supportsAudioInput: Boolean get() = false
    suspend fun load(modelPath: String, config: RuntimeLoadConfig = RuntimeLoadConfig())
    suspend fun unload()

    /** Emits generated text chunks. Cancelling collection must cancel generation. */
    fun generate(prompt: String, params: GenerationParams): Flow<String>

    /** Message-aware generation hook used by engines to support native chat runtimes like LiteRT-LM. */
    fun generateMessages(messages: List<ChatMessage>, params: GenerationParams): Flow<String> =
        generate(GemmaPromptFormatter.format(messages), params)

    /** Multimodal audio + text generation hook (WAV PCM16 bytes). */
    fun generateWithAudio(
        messages: List<ChatMessage>,
        audioWavBytes: ByteArray,
        params: GenerationParams,
    ): Flow<String> = flow {
        throw UnsupportedOperationException(
            "Loaded LLM does not support native audio input. Load a multimodal Gemma 4 (.litertlm) model.",
        )
    }
}
