package com.uoai.inference.runtime

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.uoai.inference.AcceleratorBackend
import com.uoai.inference.ChatMessage
import com.uoai.inference.InferenceSettings
import com.uoai.inference.RuntimeLoadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LiteRT-LM runtime adapter (`litertlm-android` 0.19.x) for the Gemma 4 `.litertlm` model family.
 * Supports CPU (XNNPACK), GPU (ML Drift), NPU backends, multi-turn conversation templating, and
 * native multimodal audio input on Gemma 4 bundles with an embedded audio encoder (E2B / E4B).
 */
class LiteRtLmRuntimeAdapter(context: Context) : InferenceRuntime {
    private val appContext = context.applicationContext
    @Volatile private var engine: Engine? = null
    @Volatile override var loadedConfig: RuntimeLoadConfig? = null
        private set
    @Volatile override var supportsAudioInput: Boolean = false
        private set

    override val isLoaded: Boolean get() = engine != null

    override suspend fun load(modelPath: String, config: RuntimeLoadConfig) = withContext(Dispatchers.IO) {
        check(engine == null) { "Model already loaded" }
        val safeCtx = config.contextWindowTokens.coerceIn(
            InferenceSettings.MIN_CONTEXT_TOKENS,
            InferenceSettings.MAX_TOKENS_32K,
        )
        val safeTopK = config.maxTopK.coerceIn(1, InferenceSettings.MAX_TOP_K)
        val fileName = File(modelPath).name
        val candidates = backendCandidates(fileName, config.preferredBackend)

        var lastError: Throwable? = null
        for ((accType, backend) in candidates) {
            val audioCandidates: List<Backend?> = if (accType == AcceleratorBackend.GPU) {
                // Gemma 4 .litertlm runs its Conformer audio encoder on CPU by default; fall back to GPU or text-only.
                listOf(Backend.CPU(), Backend.GPU(), null)
            } else {
                listOf(Backend.CPU(), null)
            }

            for (audioBackend in audioCandidates) {
                var candidateEngine: Engine? = null
                try {
                    val cfg = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        audioBackend = audioBackend,
                        maxNumTokens = safeCtx,
                        cacheDir = appContext.cacheDir.absolutePath,
                    )
                    candidateEngine = Engine(cfg)
                    candidateEngine.initialize()
                    engine = candidateEngine
                    supportsAudioInput = audioBackend != null
                    loadedConfig = config.copy(
                        contextWindowTokens = safeCtx,
                        maxTopK = safeTopK,
                        preferredBackend = accType,
                    )
                    Log.i(
                        TAG,
                        "Loaded $fileName on $accType (audioBackend=${audioBackend?.name ?: "none"}, ctx=$safeCtx)",
                    )
                    return@withContext
                } catch (e: Throwable) {
                    runCatching { candidateEngine?.close() }
                    Log.w(
                        TAG,
                        "Init failed for $fileName on $accType (audioBackend=${audioBackend?.name ?: "none"}): ${e.message}",
                    )
                    lastError = e
                }
            }
        }
        engine = null
        loadedConfig = null
        supportsAudioInput = false
        val hint = if (safeCtx > 4096) {
            " High context (${safeCtx}) or model size may exceed device RAM."
        } else {
            ""
        }
        throw IllegalStateException("${lastError?.message ?: "LiteRT-LM load failed"}$hint", lastError)
    }

    private fun backendCandidates(
        fileName: String,
        preferred: AcceleratorBackend,
    ): List<Pair<AcceleratorBackend, Backend>> {
        val npuLibDir = appContext.applicationInfo.nativeLibraryDir
        fun toBackend(b: AcceleratorBackend): Backend = when (b) {
            AcceleratorBackend.CPU -> Backend.CPU()
            AcceleratorBackend.GPU -> Backend.GPU()
            AcceleratorBackend.NPU -> Backend.NPU(npuLibDir)
            AcceleratorBackend.AUTO -> Backend.GPU()
        }

        if (preferred != AcceleratorBackend.AUTO) {
            return listOf(preferred to toBackend(preferred))
        }
        val lower = fileName.lowercase()
        val isNpuBuild = lower.contains("tensor") || lower.contains("qualcomm") ||
            lower.contains("qcs") || lower.contains("npu")
        val order = if (isNpuBuild) {
            listOf(AcceleratorBackend.NPU, AcceleratorBackend.GPU, AcceleratorBackend.CPU)
        } else if (lower.contains("-gpu")) {
            listOf(AcceleratorBackend.GPU, AcceleratorBackend.CPU)
        } else {
            listOf(AcceleratorBackend.GPU, AcceleratorBackend.CPU)
        }
        return order.map { it to toBackend(it) }
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        runCatching { engine?.close() }
        engine = null
        loadedConfig = null
        supportsAudioInput = false
    }

    override fun generate(prompt: String, params: GenerationParams): Flow<String> =
        generateMessages(listOf(ChatMessage("user", prompt)), params)

    override fun generateMessages(messages: List<ChatMessage>, params: GenerationParams): Flow<String> =
        streamConversation(messages = messages, audioWavBytes = null, params = params)

    override fun generateWithAudio(
        messages: List<ChatMessage>,
        audioWavBytes: ByteArray,
        params: GenerationParams,
    ): Flow<String> = streamConversation(messages = messages, audioWavBytes = audioWavBytes, params = params)

    private fun streamConversation(
        messages: List<ChatMessage>,
        audioWavBytes: ByteArray?,
        params: GenerationParams,
    ): Flow<String> = flow {
        val activeEngine = checkNotNull(engine) { "Model not loaded" }
        if (audioWavBytes != null && !supportsAudioInput) {
            throw UnsupportedOperationException(
                "Loaded model has no active audio encoder. Load a multimodal Gemma 4 E2B/E4B (.litertlm) model.",
            )
        }

        val systemText = messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }

        val dialogMessages = messages.filter { it.role != "system" }
        val lastIdx = dialogMessages.indexOfLast { it.role == "user" }
        val historyMessages = if (lastIdx > 0) {
            dialogMessages.subList(0, lastIdx).mapNotNull { m ->
                when (m.role) {
                    "user" -> Message.user(m.content)
                    "assistant" -> Message.model(m.content)
                    else -> null
                }
            }
        } else {
            emptyList()
        }
        val lastUserText = if (lastIdx >= 0) dialogMessages[lastIdx].content else dialogMessages.lastOrNull()?.content ?: ""

        val maxTopK = loadedConfig?.maxTopK ?: InferenceSettings.MAX_TOP_K
        val safeTopK = (params.topK ?: 40).coerceIn(1, maxTopK)
        val safeTopP = (params.topP ?: 0.95f).coerceIn(0.05f, 1.0f).toDouble()
        val safeTemp = params.temperature.coerceIn(0f, 2f).toDouble()
        val safeMaxTokens = params.maxTokens.coerceIn(1, InferenceSettings.MAX_TOKENS_32K)

        val convConfig = ConversationConfig(
            systemInstruction = systemText?.let { Contents.of(it) },
            initialMessages = historyMessages,
            samplerConfig = SamplerConfig(topK = safeTopK, topP = safeTopP, temperature = safeTemp),
            automaticToolCalling = false,
            maxOutputToken = safeMaxTokens,
        )

        val conversation = activeEngine.createConversation(convConfig)
        try {
            val stream = if (audioWavBytes != null) {
                val parts = buildList<Content> {
                    add(Content.AudioBytes(audioWavBytes))
                    if (lastUserText.isNotBlank()) add(Content.Text(lastUserText))
                }
                conversation.sendMessageAsync(Contents.of(parts))
            } else {
                conversation.sendMessageAsync(lastUserText)
            }
            stream.collect { msg ->
                val chunk = msg.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (chunk.isNotEmpty()) emit(chunk)
            }
        } finally {
            runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "UOAI-LiteRtLm"
    }
}

