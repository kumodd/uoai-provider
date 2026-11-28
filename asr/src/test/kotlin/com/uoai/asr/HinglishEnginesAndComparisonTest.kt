package com.uoai.asr

import com.uoai.asr.engines.AksharEngine
import com.uoai.asr.engines.AsrComparisonRunner
import com.uoai.asr.engines.AsrEngineKind
import com.uoai.asr.engines.CactModelValidator
import com.uoai.asr.engines.ExistingWhisperEngine
import com.uoai.asr.engines.HinglishModelCatalog
import com.uoai.asr.engines.HinglishModelManager
import com.uoai.asr.engines.ShrutiEngine
import com.uoai.asr.engines.SpeechEngineRegistry
import com.uoai.asr.engines.TextErrorMetrics
import com.uoai.asr.runtime.CactusSpeechBackend
import com.uoai.asr.runtime.CactusTranscribeOutput
import com.uoai.asr.runtime.MockAsrRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class HinglishEnginesAndComparisonTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun createValidFakeCact(dir: File, name: String, sizeBytes: Int = 1_100_000): File {
        val f = File(dir, name)
        val buf = ByteArray(sizeBytes)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, CactModelValidator.CACT_MAGIC_LE)
        bb.putInt(4, 12) // record count
        bb.putInt(8, 16) // codebook size
        f.writeBytes(buf)
        return f
    }

    private fun createValidFakeGgml(dir: File, name: String): File {
        val f = File(dir, name)
        val buf = ByteArray(1_100_000)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, GgmlModelValidator.GGML_MAGIC_LE)
        bb.putInt(4, 51865)
        bb.putInt(8, 1500)
        bb.putInt(12, 512)
        bb.putInt(16, 8)
        bb.putInt(20, 6)
        bb.putInt(24, 448)
        bb.putInt(28, 512)
        bb.putInt(32, 8)
        bb.putInt(36, 6)
        bb.putInt(40, 80)
        f.writeBytes(buf)
        return f
    }

    private class FakeCactusBackend(
        private val failLoad: Boolean = false,
        private val responseByFileName: Map<String, String> = mapOf(
            "akshar-v4.cact" to "भाई कल meeting है, तुम presentation भेज देना।",
            "akshar-lite-v4.cact" to "भाई कल meeting है तुम presentation भेज देना",
            "shruti.cact" to "भाई कल meeting है तुम presentation भेज देना",
        ),
    ) : CactusSpeechBackend {
        override var loadedModelPath: String? = null
            private set
        override val isSpeechLoaded: Boolean get() = loadedModelPath != null
        var loadCallCount = 0
        var transcribeCallCount = 0
        val receivedLanguages = mutableListOf<String?>()

        override suspend fun loadModel(modelPath: String): Long {
            loadCallCount++
            if (failLoad) throw IllegalStateException("Simulated native load failure")
            loadedModelPath = modelPath
            return 42L
        }

        override suspend fun transcribe(pcm16kMono: FloatArray, language: String?, keywords: String?): CactusTranscribeOutput {
            check(loadedModelPath != null) { "Not loaded" }
            transcribeCallCount++
            receivedLanguages += language
            val fileName = File(loadedModelPath!!).name
            return CactusTranscribeOutput(
                text = responseByFileName[fileName] ?: "hello",
                language = language ?: "hi",
                elapsedMs = 35L,
            )
        }

        override suspend fun reset() {
            loadedModelPath = null
        }
    }

    @Test
    fun cactValidatorRejectsCorruptAndTruncatedAndAcceptsValidHeader() {
        val valid = createValidFakeCact(tmp.root, "ok.cact")
        assertTrue(CactModelValidator.validateHeader(valid).valid)

        val badMagic = File(tmp.root, "bad.cact").apply { writeBytes(ByteArray(1_100_000) { 0x42 }) }
        assertFalse(CactModelValidator.validateHeader(badMagic).valid)

        val truncated = File(tmp.root, "short.cact").apply { writeBytes(ByteArray(64)) }
        assertFalse(CactModelValidator.validateHeader(truncated).valid)

        val missing = File(tmp.root, "missing.cact")
        assertFalse(CactModelValidator.validateHeader(missing).valid)
    }

    @Test
    fun hinglishModelManagerHandlesMissingCorruptInstallSwitchAndDelete() = runBlocking {
        val dir = tmp.newFolder("hinglish")
        val backend = FakeCactusBackend()
        val mgr = HinglishModelManager(dir, backend, scope, verifySha256OnDownload = false)

        // Missing model fails gracefully
        val missingErr = runCatching { mgr.ensureLoaded(AsrEngineKind.AKSHAR_V4) }.exceptionOrNull()
        assertNotNull(missingErr)
        assertTrue(missingErr!!.message!!.contains("not downloaded"))

        // Corrupt file rejected on install
        val badFile = File(tmp.root, "corrupt.cact").apply { writeBytes(ByteArray(1_100_000)) }
        val corruptErr = runCatching { mgr.installLocalFile(AsrEngineKind.AKSHAR_V4, badFile) }.exceptionOrNull()
        assertTrue(corruptErr is IllegalArgumentException)

        // Install both AksharSTT v4 and Shruti Hindi
        val aksharFile = createValidFakeCact(tmp.root, "src_akshar.cact")
        val shrutiFile = createValidFakeCact(tmp.root, "src_shruti.cact")
        mgr.installLocalFile(AsrEngineKind.AKSHAR_V4, aksharFile)
        mgr.installLocalFile(AsrEngineKind.SHRUTI_HI, shrutiFile)

        assertTrue(mgr.isInstalled(AsrEngineKind.AKSHAR_V4))
        assertTrue(mgr.isInstalled(AsrEngineKind.SHRUTI_HI))
        assertFalse(mgr.isInstalled(AsrEngineKind.AKSHAR_LITE_V4))

        // Lazy load + cached reuse (second ensureLoaded returns 0 ms without reloading)
        val firstLoad = mgr.ensureLoaded(AsrEngineKind.AKSHAR_V4)
        assertEquals(42L, firstLoad)
        assertEquals(0L, mgr.ensureLoaded(AsrEngineKind.AKSHAR_V4))
        assertEquals(1, backend.loadCallCount)

        // Switching to Shruti reloads Cactus slot cleanly
        mgr.ensureLoaded(AsrEngineKind.SHRUTI_HI)
        assertEquals(2, backend.loadCallCount)
        assertEquals(AsrEngineKind.SHRUTI_HI, mgr.status.value.loadedKind)

        // Deleting cleans up .cact and any interrupted .part file
        val partFile = File(dir, HinglishModelCatalog.SHRUTI_HI.fileName + ".part").apply { writeBytes(ByteArray(128)) }
        assertTrue(partFile.exists())
        assertTrue(mgr.delete(AsrEngineKind.SHRUTI_HI))
        assertFalse(partFile.exists())
        assertFalse(mgr.isInstalled(AsrEngineKind.SHRUTI_HI))
        assertNull(mgr.status.value.loadedKind)
    }

    @Test
    fun aksharAndShrutiEnginesSupportResamplingAndLongRecordings() = runBlocking {
        val dir = tmp.newFolder("hinglish_long")
        val backend = FakeCactusBackend()
        val mgr = HinglishModelManager(dir, backend, scope, verifySha256OnDownload = false)
        mgr.installLocalFile(AsrEngineKind.AKSHAR_V4, createValidFakeCact(tmp.root, "ak.cact"))
        mgr.installLocalFile(AsrEngineKind.SHRUTI_HI, createValidFakeCact(tmp.root, "sh.cact"))

        val akshar = AksharEngine(AsrEngineKind.AKSHAR_V4, mgr, backend)
        val shruti = ShrutiEngine(mgr, backend)

        // 1. Test 48 kHz ShortArray resampling into AksharSTT v4
        val pcm48k = ShortArray(48_000) { i -> (8000 * sin(2.0 * PI * 440.0 * i / 48_000.0)).toInt().toShort() }
        val res1 = akshar.transcribe(pcm48k, sampleRate = 48_000, language = "en,hi")
        assertEquals("hi", backend.receivedLanguages.last())
        assertEquals("Bhai kal meeting hai, tum presentation bhej dena.", res1.transcript.romanHinglish)
        assertEquals("भाई कल meeting है, तुम presentation भेज देना।", res1.transcript.devanagariHinglish)

        // 2. Test long 40 s recording (> 28 s single-pass limit) splits cleanly across chunks
        val longAudio = FloatArray(40 * 16_000) { i -> (0.25 * sin(2.0 * PI * 300.0 * i / 16_000.0)).toFloat() }
        val beforeCalls = backend.transcribeCallCount
        val resLong = shruti.transcribeFloat(longAudio, language = "hi")
        assertTrue("Expected multi-chunk decode for 40s audio", backend.transcribeCallCount - beforeCalls >= 2)
        assertTrue(resLong.text.isNotBlank())
    }

    @Test
    fun comparisonRunnerBenchmarksWhisperAksharShrutiAndComputesWerCer() = runBlocking {
        // Set up Whisper (existing) + Hinglish engines (Akshar installed, Shruti installed, Lite missing)
        val whisperRepo = FileAsrModelRepository(tmp.newFolder("whisper"))
        val settingsRepo = FileAsrSettingsRepository(File(tmp.root, "asr_settings.json"))
        val whisperRt = MockAsrRuntime(segmentDelayMs = 10L, segmentCount = 1)
        val whisperMgr = AsrModelManager(whisperRepo, whisperRt, settingsRepo, scope, verifySha256OnDownload = false)
        whisperMgr.installLocalFile(AsrProfile.BALANCED, createValidFakeGgml(tmp.root, "base.bin"), verifySha256 = false)
        val whisperEngine = DefaultAsrEngine(whisperMgr, whisperRt, settingsRepo, scope)

        val cactusBackend = FakeCactusBackend()
        val hinglishMgr = HinglishModelManager(tmp.newFolder("hinglish_cmp"), cactusBackend, scope, verifySha256OnDownload = false)
        hinglishMgr.installLocalFile(AsrEngineKind.AKSHAR_V4, createValidFakeCact(tmp.root, "ak2.cact"))
        hinglishMgr.installLocalFile(AsrEngineKind.SHRUTI_HI, createValidFakeCact(tmp.root, "sh2.cact"))

        val registry = SpeechEngineRegistry(
            whisper = ExistingWhisperEngine(whisperMgr, whisperEngine),
            aksharV4 = AksharEngine(AsrEngineKind.AKSHAR_V4, hinglishMgr, cactusBackend),
            aksharLiteV4 = AksharEngine(AsrEngineKind.AKSHAR_LITE_V4, hinglishMgr, cactusBackend),
            shrutiHi = ShrutiEngine(hinglishMgr, cactusBackend),
        )
        val runner = AsrComparisonRunner(registry, memoryReaderMb = { 180L })

        val sampleAudio = FloatArray(16_000 * 2) { 0.2f }
        val results = runner.compare(
            pcm16kMono = sampleAudio,
            engines = listOf(AsrEngineKind.WHISPER, AsrEngineKind.AKSHAR_V4, AsrEngineKind.AKSHAR_LITE_V4, AsrEngineKind.SHRUTI_HI),
            language = "en,hi",
        )

        assertEquals(4, results.size)
        // Whisper, Akshar v4, Shruti succeeded; Lite reported cleanly as unavailable
        assertTrue(results[0].available && results[0].error == null)
        assertTrue(results[1].available && results[1].error == null)
        assertFalse(results[2].available)
        assertNotNull(results[2].error)
        assertTrue(results[3].available && results[3].error == null)

        // Manual ground-truth WER/CER check
        val gtRoman = "Bhai kal meeting hai, tum presentation bhej dena."
        val perfect = TextErrorMetrics.compute(gtRoman, results[1].transcript!!.romanHinglish)
        assertEquals(0.0, perfect.werPercent, 0.001)
        assertEquals(0.0, perfect.cerPercent, 0.001)

        val withSubstitution = TextErrorMetrics.compute(gtRoman, "Bhai kal meeting hai, hum presentation bhej dena.")
        assertEquals(1, withSubstitution.wordEdits)
        assertEquals(12.5, withSubstitution.werPercent, 0.01)
    }
}
