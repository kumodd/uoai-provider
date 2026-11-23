package com.uoai.inference.runtime

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.uoai.inference.AcceleratorBackend
import com.uoai.inference.InferenceSettings
import com.uoai.inference.RuntimeLoadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * MediaPipe LLM Inference adapter for Gemma 3 `.task` bundles.
 * Owns exactly one native [LlmInference]; each generation uses a short-lived session so
 * sampling parameters can vary per request. Supports configurable context windows up to 32K.
 */
class GemmaRuntimeAdapter(context: Context) : InferenceRuntime {
    private val appContext = context.applicationContext
    @Volatile private var llm: LlmInference? = null
    @Volatile override var loadedConfig: RuntimeLoadConfig? = null
        private set

    override val isLoaded: Boolean get() = llm != null

    override suspend fun load(modelPath: String, config: RuntimeLoadConfig) = withContext(Dispatchers.IO) {
        check(llm == null) { "Model already loaded" }
        val safeCtx = config.contextWindowTokens.coerceIn(
            InferenceSettings.MIN_CONTEXT_TOKENS,
            InferenceSettings.MAX_TOKENS_32K,
        )
        val safeTopK = config.maxTopK.coerceIn(1, InferenceSettings.MAX_TOP_K)
        val builder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(safeCtx)
            .setMaxTopK(safeTopK)
        when (config.preferredBackend) {
            AcceleratorBackend.CPU -> builder.setPreferredBackend(LlmInference.Backend.CPU)
            AcceleratorBackend.GPU -> builder.setPreferredBackend(LlmInference.Backend.GPU)
            AcceleratorBackend.AUTO, AcceleratorBackend.NPU -> Unit
        }
        try {
            llm = LlmInference.createFromOptions(appContext, builder.build())
            loadedConfig = config.copy(contextWindowTokens = safeCtx, maxTopK = safeTopK)
        } catch (e: Throwable) {
            llm = null
            loadedConfig = null
            val hint = if (safeCtx > 4096) {
                " Pre-compiled .task bundles (e.g. ekv2048/ekv4096) or device RAM may cap the maximum supported KV cache."
            } else {
                ""
            }
            throw IllegalStateException("${e.message ?: e::class.simpleName}$hint", e)
        }
        Unit
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        llm?.close()
        llm = null
        loadedConfig = null
    }

    override fun generate(prompt: String, params: GenerationParams): Flow<String> = callbackFlow {
        val engine = checkNotNull(llm) { "Model not loaded" }
        val maxTopK = loadedConfig?.maxTopK ?: InferenceSettings.MAX_TOP_K
        val safeTopK = (params.topK ?: 40).coerceIn(1, maxTopK)
        val safeTopP = (params.topP ?: 0.95f).coerceIn(0.05f, 1.0f)
        val maxChunks = params.maxTokens.coerceIn(1, InferenceSettings.MAX_TOKENS_32K)
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(params.temperature)
            .setTopK(safeTopK)
            .setTopP(safeTopP)
            .build()
        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
        session.addQueryChunk(prompt)
        var finished = false
        var chunks = 0
        val future = session.generateResponseAsync { partial, done ->
            if (partial.isNotEmpty()) {
                trySend(partial)
                // Approximation: one callback chunk ~ one token.
                if (++chunks >= maxChunks && !done && !finished) {
                    finished = true
                    runCatching { session.cancelGenerateResponseAsync() }
                    close()
                }
            }
            if (done && !finished) { finished = true; close() }
        }
        future.addListener({
            if (!finished) {
                finished = true
                runCatching { future.get() }.onFailure { close(it.cause ?: it) }.onSuccess { close() }
            }
        }, Runnable::run)
        awaitClose {
            if (!finished) runCatching { session.cancelGenerateResponseAsync() }
            // Closing right after cancel is acceptable; failures are swallowed.
            runCatching { session.close() }
        }
    }
}
