package com.uoai.inference.runtime

import com.uoai.inference.ChatMessage
import com.uoai.inference.RuntimeLoadConfig
import kotlinx.coroutines.flow.Flow

/**
 * Dispatches model loading and inference between MediaPipe (`.task`) and LiteRT-LM (`.litertlm`)
 * while strictly guaranteeing at most one loaded model instance at any time.
 */
class CompositeInferenceRuntime(
    private val taskRuntime: InferenceRuntime,
    private val litertLmRuntime: InferenceRuntime,
) : InferenceRuntime {

    @Volatile private var active: InferenceRuntime? = null

    override val isLoaded: Boolean get() = active?.isLoaded == true
    override val loadedConfig: RuntimeLoadConfig? get() = active?.loadedConfig

    override suspend fun load(modelPath: String, config: RuntimeLoadConfig) {
        check(!isLoaded) { "Model already loaded" }
        val target = if (modelPath.endsWith(".litertlm", ignoreCase = true)) litertLmRuntime else taskRuntime
        target.load(modelPath, config)
        active = target
    }

    override suspend fun unload() {
        val current = active
        active = null
        current?.unload()
        if (taskRuntime.isLoaded) taskRuntime.unload()
        if (litertLmRuntime.isLoaded) litertLmRuntime.unload()
    }

    override fun generate(prompt: String, params: GenerationParams): Flow<String> =
        checkNotNull(active) { "Model not loaded" }.generate(prompt, params)

    override fun generateMessages(messages: List<ChatMessage>, params: GenerationParams): Flow<String> =
        checkNotNull(active) { "Model not loaded" }.generateMessages(messages, params)
}

