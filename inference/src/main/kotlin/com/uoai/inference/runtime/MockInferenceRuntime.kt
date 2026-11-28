package com.uoai.inference.runtime

import com.uoai.inference.ChatMessage
import com.uoai.inference.RuntimeLoadConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Test/UI-development runtime only. Never the production runtime. */
class MockInferenceRuntime(
    private val tokenDelayMs: Long = 0,
    private val audioCapable: Boolean = false,
) : InferenceRuntime {
    override var isLoaded: Boolean = false
        private set
    override var loadedConfig: RuntimeLoadConfig? = null
        private set
    override val supportsAudioInput: Boolean
        get() = isLoaded && audioCapable

    override suspend fun load(modelPath: String, config: RuntimeLoadConfig) {
        isLoaded = true
        loadedConfig = config
    }

    override suspend fun unload() {
        isLoaded = false
        loadedConfig = null
    }

    override fun generate(prompt: String, params: GenerationParams): Flow<String> = flow {
        check(isLoaded) { "Model not loaded" }
        val words = "[mock] echo: $prompt".split(" ").take(params.maxTokens)
        for (w in words) {
            if (tokenDelayMs > 0) delay(tokenDelayMs)
            emit("$w ")
        }
    }

    override fun generateWithAudio(
        messages: List<ChatMessage>,
        audioWavBytes: ByteArray,
        params: GenerationParams,
    ): Flow<String> {
        if (!audioCapable) return super.generateWithAudio(messages, audioWavBytes, params)
        return flow {
            check(isLoaded) { "Model not loaded" }
            val userText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            val words = "[mock-audio ${audioWavBytes.size}B] $userText".split(" ").take(params.maxTokens)
            for (w in words) {
                if (tokenDelayMs > 0) delay(tokenDelayMs)
                emit("$w ")
            }
        }
    }
}
