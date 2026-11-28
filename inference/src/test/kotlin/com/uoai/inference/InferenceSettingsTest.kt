package com.uoai.inference

import com.uoai.inference.runtime.MockInferenceRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class InferenceSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun supportsUpTo32kContextAndMaxOutputTokens() {
        val base = InferenceSettings()
        assertEquals(4096, base.contextWindowTokens)
        assertEquals(2048, base.maxOutputTokens)

        // Setting 32K max output expands context window automatically so it doesn't snap back
        val max32k = base.withMaxOutputTokens(32_768)
        assertEquals(32_768, max32k.maxOutputTokens)
        assertEquals(32_768, max32k.contextWindowTokens)

        // Lowering context window below maxOutputTokens clamps output to fit
        val reduced = max32k.withContextWindow(2048)
        assertEquals(2048, reduced.contextWindowTokens)
        assertEquals(2048, reduced.maxOutputTokens)

        // Over-32K values clamp strictly to 32,768
        val clamped = InferenceSettings().withContextWindow(99_999).withMaxOutputTokens(99_999)
        assertEquals(32_768, clamped.contextWindowTokens)
        assertEquals(32_768, clamped.maxOutputTokens)
    }

    @Test fun settingsPersistAcrossInstances() {
        val file = File(tmp.newFolder(), "settings.json")
        val repo1 = FileInferenceSettingsRepository(file)
        repo1.update {
            it.withContextWindow(32_768)
                .withMaxOutputTokens(16_384)
                .copy(temperature = 0.4f, topK = 64, preferredBackend = AcceleratorBackend.GPU)
        }

        val repo2 = FileInferenceSettingsRepository(file)
        val loaded = repo2.settings.value
        assertEquals(32_768, loaded.contextWindowTokens)
        assertEquals(16_384, loaded.maxOutputTokens)
        assertEquals(0.4f, loaded.temperature, 0.001f)
        assertEquals(64, loaded.topK)
        assertEquals(AcceleratorBackend.GPU, loaded.preferredBackend)
    }

    @Test fun reloadAppliesNewRuntimeLoadConfig() = runTest {
        val rt = MockInferenceRuntime()
        val settingsRepo = FileInferenceSettingsRepository(File(tmp.newFolder(), "settings.json"))
        val mgr = ModelManager(FileModelRepository(tmp.newFolder()), rt, settingsRepository = settingsRepo)
        mgr.importModel("gemma-4-E2B-it.litertlm", ByteArrayInputStream(ByteArray(11 * 1024 * 1024)))

        mgr.load()
        assertEquals(4096, mgr.status.value.loadedConfig?.contextWindowTokens)

        settingsRepo.update { it.withContextWindow(32_768) }
        mgr.reload()
        assertEquals(ModelState.READY, mgr.status.value.state)
        assertEquals(32_768, mgr.status.value.loadedConfig?.contextWindowTokens)
        assertTrue(rt.isLoaded)
    }
}

