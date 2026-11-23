package com.uoai.inference

import com.uoai.inference.runtime.CompositeInferenceRuntime
import com.uoai.inference.runtime.GenerationParams
import com.uoai.inference.runtime.InferenceRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class RecordingRuntime(private val tag: String) : InferenceRuntime {
    override var isLoaded: Boolean = false
        private set
    override var loadedConfig: RuntimeLoadConfig? = null
        private set
    var lastPath: String? = null
    var lastMessages: List<ChatMessage>? = null

    override suspend fun load(modelPath: String, config: RuntimeLoadConfig) {
        isLoaded = true
        loadedConfig = config
        lastPath = modelPath
    }

    override suspend fun unload() {
        isLoaded = false
        loadedConfig = null
    }

    override fun generate(prompt: String, params: GenerationParams): Flow<String> = flowOf("$tag:$prompt")

    override fun generateMessages(messages: List<ChatMessage>, params: GenerationParams): Flow<String> {
        lastMessages = messages
        return flowOf("$tag:msgs=${messages.size}")
    }
}

class ModelCatalogTest {
    @Test fun gemma4CatalogIncludesAllFiveModelTiersAndRequiresNoToken() {
        val gemma4 = ModelCatalog.byFamily(ModelFamily.GEMMA_4)
        val tiers = gemma4.map { it.tier }.toSet()
        assertEquals(setOf("E2B", "E4B", "12B", "26B-A4B", "31B"), tiers)
        assertTrue(gemma4.all { !it.gated })
        assertTrue(gemma4.all { it.runtime == ModelRuntimeKind.LITERT_LM })
        assertTrue(gemma4.all { it.maxContextTokens == 32_768 })
        assertFalse(ModelCatalog.requiresHfToken("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"))
        assertTrue(ModelCatalog.requiresHfToken("litert-community/Gemma3-1B-IT", "gemma3-1b-it-int4.task"))
    }

    @Test fun inferModelIdMapsGemma4AndGemma3Correctly() {
        assertEquals("gemma-4-e2b", ModelCatalog.inferModelId("gemma-4-E2B-it.litertlm"))
        assertEquals("gemma-4-e2b", ModelCatalog.inferModelId("gemma-4-E2B-it_Google_Tensor_G5.litertlm"))
        assertEquals("gemma-4-e4b", ModelCatalog.inferModelId("gemma-4-E4B-it-gpu.litertlm"))
        assertEquals("gemma-4-12b", ModelCatalog.inferModelId("gemma-4-12B-it.litertlm"))
        assertEquals("gemma-4-26b-a4b", ModelCatalog.inferModelId("gemma-4-26B-A4B-it-gpu.litertlm"))
        assertEquals("gemma-4-31b", ModelCatalog.inferModelId("gemma-4-31B-it-gpu.litertlm"))
        assertEquals("gemma-3", ModelCatalog.inferModelId("gemma3-1b-it-int4.task"))
    }

    @Test fun compositeRuntimeRoutesLitertLmAndTaskBundles() = runTest {
        val taskRt = RecordingRuntime("task")
        val litertRt = RecordingRuntime("litert")
        val composite = CompositeInferenceRuntime(taskRt, litertRt)

        composite.load("/models/gemma-4-E2B-it.litertlm", RuntimeLoadConfig(contextWindowTokens = 8192))
        assertTrue(composite.isLoaded)
        assertTrue(litertRt.isLoaded)
        assertFalse(taskRt.isLoaded)
        assertEquals(8192, composite.loadedConfig?.contextWindowTokens)

        val out = composite.generateMessages(listOf(ChatMessage("user", "hi")), GenerationParams()).toList()
        assertEquals(listOf("litert:msgs=1"), out)

        // Switching to .task unloads LiteRT-LM cleanly first
        composite.unload()
        assertFalse(litertRt.isLoaded)
        assertFalse(composite.isLoaded)

        composite.load("/models/gemma3-1b-it-int4.task", RuntimeLoadConfig(contextWindowTokens = 4096))
        assertTrue(taskRt.isLoaded)
        assertFalse(litertRt.isLoaded)
        val out2 = composite.generateMessages(listOf(ChatMessage("user", "hi")), GenerationParams()).toList()
        assertEquals(listOf("task:msgs=1"), out2)
    }
}

