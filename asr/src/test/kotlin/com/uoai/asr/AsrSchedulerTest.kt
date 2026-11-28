package com.uoai.asr

import com.uoai.asr.audio.AudioTooLongException
import com.uoai.asr.audio.DecodedAudio
import com.uoai.asr.audio.DefaultAudioDecoder
import com.uoai.asr.audio.WavPcmDecoder
import com.uoai.asr.runtime.MockAsrRuntime
import com.uoai.core.resources.ComputeCoexistencePolicy
import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.core.resources.DeviceMemoryInfo
import com.uoai.core.resources.DeviceResourceMonitor
import com.uoai.core.resources.ThermalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

class AsrSchedulerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
    }

    private fun writeFakeGgml(file: File): File {
        val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(GgmlModelValidator.GGML_MAGIC_LE)
            putInt(51865); putInt(1500); putInt(512); putInt(8); putInt(6)
            putInt(448); putInt(512); putInt(8); putInt(6); putInt(80); putInt(1)
        }.array()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(1_100_000L)
            raf.seek(0)
            raf.write(header)
        }
        return file
    }

    private class FakeMonitor(
        var thermal: ThermalLevel = ThermalLevel.NOMINAL,
    ) : DeviceResourceMonitor {
        override fun memoryInfo() = DeviceMemoryInfo(4096, 8192, false)
        override fun thermalLevel() = thermal
        override fun availableCpuCores() = 8
    }

    private fun buildEngine(
        delayMs: Long = 60L,
        limits: AsrQueueLimits = AsrQueueLimits(),
        monitor: FakeMonitor = FakeMonitor(),
        policy: ComputeCoexistencePolicy = ComputeCoexistencePolicy.SMART_THROTTLE,
        onRecord: (AsrRequestRecord) -> Unit = {},
    ): Triple<DefaultAsrEngine, MockAsrRuntime, ComputeResourceCoordinator> {
        val repo = FileAsrModelRepository(tmp.newFolder())
        val settings = FileAsrSettingsRepository(File(tmp.newFolder(), "asr_settings.json"))
        settings.update { it.copy(coexistencePolicy = policy) }
        val coord = ComputeResourceCoordinator(monitor) { settings.settings.value.coexistencePolicy }
        val rt = MockAsrRuntime(segmentDelayMs = delayMs, segmentCount = 3)
        val mgr = AsrModelManager(repo, rt, settings, scope, resourceCoordinator = coord, verifySha256OnDownload = false)
        runBlocking {
            mgr.installLocalFile(AsrProfile.BALANCED, writeFakeGgml(tmp.newFile()))
            mgr.load(AsrProfile.BALANCED)
        }
        val engine = DefaultAsrEngine(mgr, rt, settings, scope, recorder = onRecord, limits = limits, resourceCoordinator = coord)
        return Triple(engine, rt, coord)
    }

    private fun asrReq(id: String, client: String = "c1", prio: Int = 0, lang: String? = "en") =
        AsrRequest(
            requestId = id,
            clientId = client,
            requestedModel = "auto",
            audio = DecodedAudio(FloatArray(16_000) { 0.1f }),
            language = lang,
            priority = prio,
        )

    @Test fun serializesMultiAppRequestsInPriorityOrder() = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val (engine, _, _) = buildEngine(delayMs = 50L) { rec ->
            if (rec.status == AsrRequestStatus.RUNNING) started.add(rec.requestId)
        }

        val first = async { engine.transcribe(asrReq("asr-1", "appA", prio = 0)) }
        delay(40)
        val low = async { engine.transcribe(asrReq("asr-low", "appA", prio = 0)) }
        delay(10)
        val high = async { engine.transcribe(asrReq("asr-high", "appB", prio = 3, lang = "hi")) }

        val results = listOf(first, low, high).awaitAll()
        assertEquals(listOf("asr-1", "asr-high", "asr-low"), started.toList())
        assertEquals("hi", results[2].language)
        assertTrue(results[2].text.contains("नमस्ते"))
    }

    @Test fun cancelsQueuedAndRunningRequestsCleanly() = runBlocking {
        val records = Collections.synchronizedList(mutableListOf<AsrRequestRecord>())
        val (engine, _, _) = buildEngine(delayMs = 120L) { records.add(it) }

        val running = async { runCatching { engine.transcribe(asrReq("asr-run", "appA")) } }
        delay(60)
        val queued = async { runCatching { engine.transcribe(asrReq("asr-q", "appB")) } }
        val next = async { engine.transcribe(asrReq("asr-next", "appC")) }
        delay(30)

        assertTrue(engine.cancel("asr-q"))
        assertTrue(queued.await().exceptionOrNull() is AsrRequestCancelledException)

        assertTrue(engine.cancel("asr-run"))
        assertTrue(running.await().exceptionOrNull() is AsrRequestCancelledException)

        assertTrue(next.await().text.contains("hello offline world"))
        val settled = withTimeout(1_000L) { engine.modelStatus.first { it.state == AsrModelState.READY } }
        assertEquals(AsrModelState.READY, settled.state)
    }

    @Test fun collectorDisconnectCancelsRunningStream() = runBlocking {
        val records = Collections.synchronizedList(mutableListOf<AsrRequestRecord>())
        val (engine, _, _) = buildEngine(delayMs = 120L) { records.add(it) }

        val job = launch { engine.stream(asrReq("asr-gone", "appA")).toList() }
        delay(90)
        job.cancelAndJoin()
        delay(150)

        assertTrue(records.any { it.requestId == "asr-gone" && it.status == AsrRequestStatus.CANCELLED })
        assertEquals(0, engine.queueState.value.queued)
    }

    @Test fun enforcesQueueLimitsAndThermalGuard() = runBlocking {
        val monitor = FakeMonitor(thermal = ThermalLevel.NOMINAL)
        val (engine, _, _) = buildEngine(
            delayMs = 100L,
            limits = AsrQueueLimits(maxQueued = 2, maxPendingPerClient = 2),
            monitor = monitor,
        )

        val jobs = (1..2).map { i -> launch { runCatching { engine.transcribe(asrReq("asr-$i", "same")) } } }
        delay(50)
        try {
            engine.transcribe(asrReq("asr-3", "same"))
            fail("Expected AsrQueueFullException")
        } catch (_: AsrQueueFullException) {}
        jobs.forEachIndexed { idx, _ -> engine.cancel("asr-${idx + 1}") }
        jobs.forEach { it.join() }

        monitor.thermal = ThermalLevel.CRITICAL
        try {
            engine.transcribe(asrReq("asr-hot", "other"))
            fail("Expected AsrThermalThrottledException")
        } catch (_: AsrThermalThrottledException) {}
    }

    @Test fun throttlesWhisperThreadsWhenLlmIsGeneratingOnGpu() = runBlocking {
        val (engine, rt, coord) = buildEngine(delayMs = 20L, policy = ComputeCoexistencePolicy.SMART_THROTTLE)

        // Baseline (LLM idle on 8-core device -> 4 threads)
        engine.transcribe(asrReq("asr-idle"))
        assertEquals(4, rt.lastParams?.threads)

        // While LLM is generating on GPU -> SMART_THROTTLE caps Whisper to 2 threads and allows concurrent run
        coord.withLlmExecution(onCpu = false) {
            engine.transcribe(asrReq("asr-concurrent"))
        }
        assertEquals(2, rt.lastParams?.threads)
    }

    @Test fun decodesStereo44kWavAndRawPcmAndRejectsOversizeDuration() {
        // Generate valid 0.5 s 44.1 kHz stereo 16-bit WAV
        val wavFile = tmp.newFile("sample.wav")
        val srcRate = 44_100
        val channels = 2
        val frames = srcRate / 2
        val pcmData = ByteArray(frames * channels * 2)
        val bb = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (16_000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / srcRate)).toInt().toShort()
            bb.putShort(v)
            bb.putShort(v)
        }
        wavFile.writeBytes(buildWavHeader(pcmData.size, srcRate, channels, 16) + pcmData)

        val decoder = DefaultAudioDecoder()
        val decoded = decoder.decode(wavFile, contentType = "audio/wav", fileName = "sample.wav")
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(8_000, decoded.samples.size)
        assertEquals(0.5, decoded.durationSec, 0.02)

        // Raw PCM 16 kHz mono fast path
        val pcmFile = tmp.newFile("raw.pcm")
        pcmFile.writeBytes(ByteArray(32_000)) // 1.0 s of 16-bit mono
        val decodedPcm = decoder.decode(pcmFile, contentType = "audio/pcm", fileName = "raw.pcm")
        assertEquals(16_000, decodedPcm.samples.size)

        // Duration guard
        try {
            WavPcmDecoder.decodeRawPcm16Le(pcmFile, sampleRate = 16_000, channels = 1, maxDurationSec = 0)
            fail("Expected AudioTooLongException")
        } catch (_: AudioTooLongException) {}
    }

    private fun buildWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val out = ByteArrayOutputStream(44)
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)
        out.write(bb.array())
        return out.toByteArray()
    }
}

