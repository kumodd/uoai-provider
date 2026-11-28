package com.uoai.asr

import android.content.ComponentCallbacks2
import com.uoai.asr.runtime.MockAsrRuntime
import com.uoai.core.resources.ComputeCoexistencePolicy
import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.core.resources.DeviceMemoryInfo
import com.uoai.core.resources.DeviceResourceMonitor
import com.uoai.core.resources.ThermalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AsrModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
    }

    private fun writeFakeGgml(file: File, sizeBytes: Int = 1_100_000): File {
        val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(GgmlModelValidator.GGML_MAGIC_LE) // magic "ggml"
            putInt(51865) // n_vocab
            putInt(1500)  // n_audio_ctx
            putInt(512)   // n_audio_state
            putInt(8)     // n_audio_head
            putInt(6)     // n_audio_layer
            putInt(448)   // n_text_ctx
            putInt(512)   // n_text_state
            putInt(8)     // n_text_head
            putInt(6)     // n_text_layer
            putInt(80)    // n_mels
            putInt(1)     // ftype
        }.array()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes.toLong())
            raf.seek(0)
            raf.write(header)
        }
        return file
    }

    private class FakeMonitor(
        var availMb: Long = 2048,
        var totalMb: Long = 8192,
        var lowMem: Boolean = false,
        var thermal: ThermalLevel = ThermalLevel.NOMINAL,
    ) : DeviceResourceMonitor {
        override fun memoryInfo() = DeviceMemoryInfo(availMb, totalMb, lowMem)
        override fun thermalLevel() = thermal
        override fun availableCpuCores() = 8
    }

    @Test fun validatesGgmlHeaderAndRejectsCorruptFiles() {
        val good = writeFakeGgml(tmp.newFile("good.bin"))
        assertTrue(GgmlModelValidator.validateHeader(good).valid)

        val badMagic = tmp.newFile("bad_magic.bin").apply { writeBytes(ByteArray(1_100_000) { 0x42 }) }
        assertFalse(GgmlModelValidator.validateHeader(badMagic).valid)

        val truncated = tmp.newFile("tiny.bin").apply { writeBytes(ByteArray(100)) }
        assertFalse(GgmlModelValidator.validateHeader(truncated).valid)
    }

    @Test fun catalogResolvesAllThreeProfilesAndAliases() {
        assertEquals(AsrProfile.FAST, AsrModelCatalog.resolveOrNull("fast")?.profile)
        assertEquals(AsrProfile.FAST, AsrModelCatalog.resolveOrNull("whisper-tiny-q5")?.profile)
        assertEquals(AsrProfile.BALANCED, AsrModelCatalog.resolveOrNull("balanced")?.profile)
        assertEquals(AsrProfile.BALANCED, AsrModelCatalog.resolveOrNull("whisper-base-q5")?.profile)
        assertEquals(AsrProfile.HIGH_ACCURACY, AsrModelCatalog.resolveOrNull("high_accuracy")?.profile)
        assertEquals(AsrProfile.HIGH_ACCURACY, AsrModelCatalog.resolveOrNull("whisper-small-q5")?.profile)
        assertTrue(AsrModelCatalog.BALANCED_PRESET.recommended)
        assertNull(AsrModelCatalog.resolveOrNull("unknown-model"))
    }

    @Test fun profileSwitchingEnforcesSingleLoadedModelInvariant() = runBlocking {
        val repo = FileAsrModelRepository(tmp.newFolder("asr_models"))
        val settings = FileAsrSettingsRepository(File(tmp.newFolder("cfg"), "asr_settings.json"))
        val rt = MockAsrRuntime()
        val mgr = AsrModelManager(repo, rt, settings, scope, verifySha256OnDownload = false)

        mgr.installLocalFile(AsrProfile.FAST, writeFakeGgml(tmp.newFile("f.bin")))
        mgr.installLocalFile(AsrProfile.BALANCED, writeFakeGgml(tmp.newFile("b.bin")))
        mgr.installLocalFile(AsrProfile.HIGH_ACCURACY, writeFakeGgml(tmp.newFile("s.bin")))

        // Default is BALANCED; lazy load loads only BALANCED
        assertEquals(AsrModelState.INSTALLED, mgr.status.value.state)
        assertTrue(mgr.ensureLoaded())
        assertEquals(AsrModelState.READY, mgr.status.value.state)
        assertEquals(AsrProfile.BALANCED, mgr.status.value.loadedProfile)
        assertEquals(1, rt.loadCount)
        assertEquals(0, rt.unloadCount)

        // Switching to HIGH_ACCURACY unloads BALANCED first and loads HIGH_ACCURACY
        mgr.selectProfile(AsrProfile.HIGH_ACCURACY, loadIfWasLoaded = true)
        assertEquals(AsrProfile.HIGH_ACCURACY, mgr.status.value.loadedProfile)
        assertEquals(2, rt.loadCount)
        assertEquals(1, rt.unloadCount)

        // Switching to FAST unloads HIGH_ACCURACY first
        mgr.selectProfile(AsrProfile.FAST, loadIfWasLoaded = true)
        assertEquals(AsrProfile.FAST, mgr.status.value.loadedProfile)
        assertEquals(3, rt.loadCount)
        assertEquals(2, rt.unloadCount)

        // Unload returns cleanly to INSTALLED
        mgr.unload()
        assertEquals(AsrModelState.INSTALLED, mgr.status.value.state)
        assertNull(mgr.status.value.loadedProfile)
        assertFalse(rt.isLoaded)
    }

    @Test fun ramGuardPreventsOomAndTrimMemoryUnloadsIdleModel() = runBlocking {
        val repo = FileAsrModelRepository(tmp.newFolder("asr_models2"))
        val settings = FileAsrSettingsRepository(File(tmp.newFolder("cfg2"), "asr_settings.json"))
        val monitor = FakeMonitor(availMb = 200, totalMb = 4096)
        val coord = ComputeResourceCoordinator(monitor) { ComputeCoexistencePolicy.SMART_THROTTLE }
        val rt = MockAsrRuntime()
        val mgr = AsrModelManager(repo, rt, settings, scope, resourceCoordinator = coord, verifySha256OnDownload = false)

        mgr.installLocalFile(AsrProfile.BALANCED, writeFakeGgml(tmp.newFile("b2.bin")))

        // 200 MB free < 450 MB required for Base -> fails cleanly with AsrInsufficientMemoryException
        try {
            mgr.load(AsrProfile.BALANCED)
            fail("Expected AsrInsufficientMemoryException")
        } catch (_: AsrInsufficientMemoryException) {
            assertEquals(AsrModelState.ERROR, mgr.status.value.state)
            assertFalse(rt.isLoaded)
        }

        // Now free up RAM -> load succeeds
        monitor.availMb = 2048
        mgr.load(AsrProfile.BALANCED)
        assertEquals(AsrModelState.READY, mgr.status.value.state)
        assertTrue(rt.isLoaded)

        // Trim memory while idle -> auto-unloads
        @Suppress("DEPRECATION")
        coord.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        delay(150)
        assertFalse(rt.isLoaded)
        assertEquals(AsrModelState.INSTALLED, mgr.status.value.state)
    }

    @Test fun persistsModelsAndSettingsAcrossRestart() = runBlocking {
        val modelsDir = tmp.newFolder("persist_models")
        val cfgFile = File(tmp.newFolder("persist_cfg"), "asr_settings.json")
        val repo1 = FileAsrModelRepository(modelsDir)
        val settings1 = FileAsrSettingsRepository(cfgFile)
        val mgr1 = AsrModelManager(repo1, MockAsrRuntime(), settings1, scope, verifySha256OnDownload = false)

        mgr1.installLocalFile(AsrProfile.FAST, writeFakeGgml(tmp.newFile("persist_fast.bin")))
        settings1.update { it.copy(selectedProfile = AsrProfile.FAST, defaultLanguage = "hi") }

        // Recreate fresh instances simulating process death + restart
        val repo2 = FileAsrModelRepository(modelsDir)
        val settings2 = FileAsrSettingsRepository(cfgFile)
        val mgr2 = AsrModelManager(repo2, MockAsrRuntime(), settings2, scope, verifySha256OnDownload = false)

        assertEquals(AsrProfile.FAST, settings2.settings.value.selectedProfile)
        assertEquals("hi", settings2.settings.value.defaultLanguage)
        assertEquals(AsrModelState.INSTALLED, mgr2.status.value.state)
        assertTrue(mgr2.status.value.installedProfiles.containsKey(AsrProfile.FAST))
    }
}

