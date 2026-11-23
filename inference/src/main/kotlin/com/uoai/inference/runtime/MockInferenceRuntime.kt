package com.uoai.inference.runtime

import com.uoai.inference.RuntimeLoadConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Test/UI-development runtime only. Never the production runtime. */
class MockInferenceRuntime(private val tokenDelayMs: Long = 0) : InferenceRuntime {
    override var isLoaded: Boolean = false
        private set
    override var loadedConfig: RuntimeLoadConfig? = null
        private set

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
}
