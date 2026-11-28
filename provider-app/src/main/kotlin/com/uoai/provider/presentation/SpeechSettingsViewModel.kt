package com.uoai.provider.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uoai.asr.AsrEngine
import com.uoai.asr.AsrModelCatalog
import com.uoai.asr.AsrModelManager
import com.uoai.asr.AsrModelStatus
import com.uoai.asr.AsrProfile
import com.uoai.asr.AsrQueueSnapshot
import com.uoai.asr.AsrRequest
import com.uoai.asr.AsrSettings
import com.uoai.asr.AsrSettingsRepository
import com.uoai.asr.audio.AudioChunk
import com.uoai.asr.audio.AudioDecoder
import com.uoai.asr.audio.ChunkQueueMetrics
import com.uoai.asr.audio.ChunkedAudioConfig
import com.uoai.asr.audio.DecodedAudio
import com.uoai.asr.audio.LiveAudioChunker
import com.uoai.asr.audio.OrderedChunkProcessor
import com.uoai.asr.audio.TranscriptContinuity
import com.uoai.asr.audio.WavPcmDecoder
import com.uoai.asr.engines.AsrComparisonRunner
import com.uoai.asr.engines.AsrEngineKind
import com.uoai.asr.engines.AsrOutputScript
import com.uoai.asr.engines.EngineComparisonEntry
import com.uoai.asr.engines.HinglishModelManager
import com.uoai.asr.engines.HinglishModelsStatus
import com.uoai.asr.engines.HinglishScriptConverter
import com.uoai.asr.engines.HinglishTranscript
import com.uoai.asr.engines.SpeechEngineRegistry
import com.uoai.core.common.DispatcherProvider
import com.uoai.core.resources.ComputeCoexistencePolicy
import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.core.resources.ResourceSnapshot
import com.uoai.inference.ChatMessage
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceRequest
import com.uoai.inference.InferenceSettings
import com.uoai.inference.InferenceSettingsRepository
import com.uoai.inference.ModelManager
import com.uoai.inference.ModelState
import com.uoai.inference.runtime.GenerationParams
import com.uoai.providerapi.AsrTempFileStore
import com.uoai.providerapi.external.ExternalAudioProviderConfig
import com.uoai.providerapi.external.ExternalAudioProviderRegistry
import com.uoai.providerapi.external.ExternalProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.sqrt

enum class VoiceCaptureEngine(val label: String) {
    WHISPER_ASR("Whisper"),
    AKSHAR_V4("Akshar v4"),
    AKSHAR_LITE_V4("Akshar Lite"),
    SHRUTI_HI("Shruti"),
    LLM_DIRECT("LLM"),
    COMPARE("Compare"),
    ;

    fun toAsrEngineKindOrNull(): AsrEngineKind? = when (this) {
        WHISPER_ASR -> AsrEngineKind.WHISPER
        AKSHAR_V4 -> AsrEngineKind.AKSHAR_V4
        AKSHAR_LITE_V4 -> AsrEngineKind.AKSHAR_LITE_V4
        SHRUTI_HI -> AsrEngineKind.SHRUTI_HI
        LLM_DIRECT, COMPARE -> null
    }

    companion object {
        fun fromKind(kind: AsrEngineKind): VoiceCaptureEngine = when (kind) {
            AsrEngineKind.WHISPER -> WHISPER_ASR
            AsrEngineKind.AKSHAR_V4 -> AKSHAR_V4
            AsrEngineKind.AKSHAR_LITE_V4 -> AKSHAR_LITE_V4
            AsrEngineKind.SHRUTI_HI -> SHRUTI_HI
        }
    }
}

/**
 * One immutable, finalized transcript chunk appended in strict FIFO order.
 * Holds canonical, Roman Hinglish, and Devanagari Hinglish representations so switching
 * output script updates the UI instantaneously without re-transcribing.
 */
@Immutable
data class TranscriptChunkItem(
    val sequenceId: Long,
    val transcript: HinglishTranscript,
    val language: String?,
    val audioDurationSec: Double,
    val transcribeElapsedMs: Long,
    val engine: VoiceCaptureEngine,
) {
    val text: String get() = transcript.canonicalText
}

/**
 * High-frequency live microphone and queue telemetry separated into its own StateFlow so
 * 10 Hz amplitude/timer ticks never trigger recomposition of committed transcript chunks.
 */
@Immutable
data class LiveRecordingMeter(
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val bufferedAudioKb: Int = 0,
    val queuedChunks: Int = 0,
    val processingChunkSeq: Long? = null,
    val completedChunks: Int = 0,
    val freedAudioKb: Int = 0,
)

@Immutable
data class AsrPlaygroundState(
    val captureEngine: VoiceCaptureEngine = VoiceCaptureEngine.WHISPER_ASR,
    val recording: Boolean = false,
    val running: Boolean = false,
    val selectedFileName: String? = null,
    /** Append-only list of completed transcript chunks keyed by monotonic [TranscriptChunkItem.sequenceId]. */
    val chunks: List<TranscriptChunkItem> = emptyList(),
    /** Live token stream for ONLY the current in-flight LLM chunk (shown below committed chunks). */
    val streamingChunkText: String = "",
    val detectedLanguage: String? = null,
    /** Non-null when Auto (Lock) mode has locked onto a confident language for the session. */
    val lockedLanguage: String? = null,
    val totalAudioDurationSec: Double = 0.0,
    val totalTranscribeMs: Long = 0L,
    val totalFreedAudioKb: Int = 0,
    val error: String? = null,
    val autoEnhance: Boolean = false,
    val enhanceRunning: Boolean = false,
    val enhancedOutput: String = "",
    val enhanceElapsedMs: Long? = null,
    val enhanceError: String? = null,
    // --- Multi-Engine Comparison Mode ---
    val compareSelectedEngines: Set<AsrEngineKind> = setOf(
        AsrEngineKind.WHISPER,
        AsrEngineKind.AKSHAR_V4,
        AsrEngineKind.SHRUTI_HI,
    ),
    val compareRunning: Boolean = false,
    val compareResults: List<EngineComparisonEntry> = emptyList(),
    val compareGroundTruth: String = "",
    val compareAudioDurationSec: Double? = null,
) {
    fun fullTranscriptFor(script: AsrOutputScript): String =
        chunks.joinToString(" ") { it.transcript.forScript(script) }.trim()

    val fullCanonicalTranscript: String
        get() = chunks.joinToString(" ") { it.transcript.canonicalText }.trim()
}

data class ExternalProviderUiItem(
    val config: ExternalAudioProviderConfig,
    val hasApiKey: Boolean,
)

data class SpeechSettingsUiState(
    val status: AsrModelStatus = AsrModelStatus(),
    val hinglish: HinglishModelsStatus = HinglishModelsStatus(),
    val settings: AsrSettings = AsrSettings(),
    val queue: AsrQueueSnapshot = AsrQueueSnapshot(),
    val resources: ResourceSnapshot = ResourceSnapshot(),
    val availableStorageMb: Long = 0L,
    val tempCacheBytes: Long = 0L,
    val externalProviders: List<ExternalProviderUiItem> = emptyList(),
    val externalProviderError: String? = null,
    val gemma4AudioReady: Boolean = false,
    val playground: AsrPlaygroundState = AsrPlaygroundState(),
)

@HiltViewModel
class SpeechSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: AsrModelManager,
    private val asrEngine: AsrEngine,
    private val hinglishModelManager: HinglishModelManager,
    private val engineRegistry: SpeechEngineRegistry,
    private val comparisonRunner: AsrComparisonRunner,
    private val externalProviderRegistry: ExternalAudioProviderRegistry,
    private val settingsRepo: AsrSettingsRepository,
    private val audioDecoder: AudioDecoder,
    private val tempStore: AsrTempFileStore,
    private val resourceCoordinator: ComputeResourceCoordinator,
    private val llmEngine: InferenceEngine,
    private val llmModelManager: ModelManager,
    private val llmSettingsRepo: InferenceSettingsRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val playgroundState = MutableStateFlow(AsrPlaygroundState())
    private val _liveMeter = MutableStateFlow(LiveRecordingMeter())
    private val storageBump = MutableStateFlow(0L)
    private val externalProviderError = MutableStateFlow<String?>(null)

    /** Dedicated high-frequency flow for recording amplitude, elapsed clock, and queue depth. */
    val liveMeter: StateFlow<LiveRecordingMeter> = _liveMeter.asStateFlow()

    private var fileJob: Job? = null
    private var recordJob: Job? = null
    private var compareJob: Job? = null
    private var enhanceJob: Job? = null

    @Volatile private var activeChunkProcessor: OrderedChunkProcessor? = null
    @Volatile private var activeAsrRequestId: String? = null
    @Volatile private var activeLlmAudioId: String? = null
    @Volatile private var activeEnhanceId: String? = null
    @Volatile private var pendingRecordLanguage: String? = null
    @Volatile private var sessionLockedLanguage: String? = null
    @Volatile private var lastAutoLangCandidate: String? = null
    @Volatile private var finalizeOnStop = false
    @Volatile private var activeChunkerEvictedBytes = 0L

    private val recordActive = AtomicBoolean(false)

    val state: StateFlow<SpeechSettingsUiState> = combine(
        combine(modelManager.status, hinglishModelManager.status) { w, h -> w to h },
        combine(settingsRepo.settings, externalProviderRegistry.providers, externalProviderError) { cfg, ext, extErr ->
            Triple(cfg, ext, extErr)
        },
        combine(asrEngine.queueState, llmModelManager.status) { q, llm -> q to llm },
        resourceCoordinator.snapshot,
        combine(playgroundState, storageBump) { pg, _ -> pg },
    ) { (st, hSt), (cfg, extList, extErr), (q, llmSt), res, pg ->
        val gemmaAudioReady = (llmSt.state == ModelState.READY || llmSt.state == ModelState.GENERATING) && llmSt.supportsAudioInput
        SpeechSettingsUiState(
            status = st,
            hinglish = hSt,
            settings = cfg,
            queue = q,
            resources = res,
            availableStorageMb = context.filesDir.usableSpace / 1_048_576L,
            tempCacheBytes = tempStore.totalCacheBytes(),
            externalProviders = extList.map { ExternalProviderUiItem(it, externalProviderRegistry.hasApiKey(it.id)) },
            externalProviderError = extErr,
            gemma4AudioReady = gemmaAudioReady,
            playground = pg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeechSettingsUiState())

    // ---- External Audio Inference Provider Actions ----

    fun upsertExternalProvider(
        id: String,
        displayName: String,
        type: ExternalProviderType,
        baseUrl: String,
        defaultModel: String,
        apiKey: String?,
    ) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                externalProviderRegistry.upsert(
                    config = ExternalAudioProviderConfig(
                        id = id,
                        displayName = displayName.ifBlank { id },
                        type = type,
                        baseUrl = baseUrl,
                        defaultModel = defaultModel,
                        apiKeyHeader = type.defaultAuthHeader(),
                        apiKeyPrefix = type.defaultAuthPrefix(),
                        enabled = true,
                    ),
                    apiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() },
                    updateApiKey = !apiKey.isNullOrBlank(),
                )
            }.onSuccess {
                externalProviderError.value = null
            }.onFailure { err ->
                externalProviderError.value = err.message ?: "Could not save external provider"
            }
        }
    }

    fun setExternalProviderEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            externalProviderRegistry.setEnabled(id, enabled)
            externalProviderError.value = null
        }
    }

    fun deleteExternalProvider(id: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { externalProviderRegistry.remove(id) }
                .onFailure { externalProviderError.value = it.message }
                .onSuccess { externalProviderError.value = null }
        }
    }

    // ---- Existing Whisper Model Actions (unchanged) ----

    fun downloadProfile(profile: AsrProfile) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { modelManager.downloadProfile(profile) }
            storageBump.update { it + 1 }
        }
    }

    fun cancelDownload() {
        modelManager.cancelDownload()
        storageBump.update { it + 1 }
    }

    fun selectProfile(profile: AsrProfile) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { modelManager.selectProfile(profile, loadIfWasLoaded = true) }
            storageBump.update { it + 1 }
        }
    }

    fun loadModel(profile: AsrProfile? = null) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { modelManager.load(profile) }
            storageBump.update { it + 1 }
        }
    }

    fun unloadModel() {
        viewModelScope.launch(dispatchers.io) {
            runCatching { modelManager.unload() }
            storageBump.update { it + 1 }
        }
    }

    fun deleteProfile(profile: AsrProfile) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { modelManager.deleteProfile(profile) }
            storageBump.update { it + 1 }
        }
    }

    // ---- Hinglish Engine & Output Script Management (AksharSTT v4, Lite v4, Shruti) ----

    fun selectAsrEngine(kind: AsrEngineKind) {
        settingsRepo.update { it.copy(selectedEngine = kind) }
    }

    fun updateOutputScript(script: AsrOutputScript) {
        settingsRepo.update { it.copy(outputScript = script) }
    }

    fun downloadHinglishModel(kind: AsrEngineKind) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { hinglishModelManager.download(kind) }
            storageBump.update { it + 1 }
        }
    }

    fun cancelHinglishDownload() {
        hinglishModelManager.cancelDownload()
        storageBump.update { it + 1 }
    }

    fun loadHinglishModel(kind: AsrEngineKind) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { hinglishModelManager.ensureLoaded(kind) }
            storageBump.update { it + 1 }
        }
    }

    fun unloadHinglishModel() {
        viewModelScope.launch(dispatchers.io) {
            runCatching { hinglishModelManager.release() }
            storageBump.update { it + 1 }
        }
    }

    fun deleteHinglishModel(kind: AsrEngineKind) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { hinglishModelManager.delete(kind) }
            storageBump.update { it + 1 }
        }
    }

    // ---- Shared ASR Settings ----

    fun updateLanguage(code: String) {
        settingsRepo.update { it.copy(defaultLanguage = code) }
    }

    fun updateTranslateToEnglish(enabled: Boolean) {
        settingsRepo.update { it.copy(translateToEnglish = enabled) }
    }

    fun updateAutoLoad(enabled: Boolean) {
        settingsRepo.update { it.copy(autoLoadOnRequest = enabled) }
    }

    fun updateIdleUnloadTimeout(seconds: Int) {
        settingsRepo.update { it.copy(idleUnloadTimeoutSec = seconds) }
    }

    fun updateCoexistencePolicy(policy: ComputeCoexistencePolicy) {
        settingsRepo.update { it.copy(coexistencePolicy = policy) }
        resourceCoordinator.refresh()
    }

    fun updateMaxThreads(threads: Int) {
        settingsRepo.update { it.copy(maxThreads = threads) }
        resourceCoordinator.refresh()
    }

    fun clearTempAudioCache() {
        viewModelScope.launch(dispatchers.io) {
            tempStore.sweepAll()
            storageBump.update { it + 1 }
        }
    }

    fun setAutoEnhance(enabled: Boolean) {
        playgroundState.update { it.copy(autoEnhance = enabled) }
    }

    fun unlockSessionLanguage() {
        sessionLockedLanguage = null
        lastAutoLangCandidate = null
        playgroundState.update { it.copy(lockedLanguage = null) }
    }

    fun toggleCompareEngine(kind: AsrEngineKind) {
        playgroundState.update { st ->
            val next = st.compareSelectedEngines.toMutableSet()
            if (kind in next && next.size > 1) next.remove(kind) else next.add(kind)
            st.copy(compareSelectedEngines = next)
        }
    }

    fun updateCompareGroundTruth(text: String) {
        playgroundState.update { it.copy(compareGroundTruth = text) }
    }

    fun reportMicPermissionDenied() {
        playgroundState.update {
            it.copy(error = "Microphone permission is required to record audio in the Playground.")
        }
    }

    /**
     * Captures 16 kHz mono PCM16 from the shared microphone.
     * - For single-engine live modes (`WHISPER_ASR`, `AKSHAR_V4`, `AKSHAR_LITE_V4`, `SHRUTI_HI`, `LLM_DIRECT`):
     *   streams audio through [LiveAudioChunker] -> [OrderedChunkProcessor] FIFO queue with O(1) RAM eviction.
     * - For [VoiceCaptureEngine.COMPARE]: captures ONE shared audio recording (no duplicate mic) and feeds
     *   identical PCM audio sequentially to all selected engines on Stop.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        languageOverride: String? = null,
        engine: VoiceCaptureEngine = VoiceCaptureEngine.WHISPER_ASR,
    ) {
        if (recordActive.get()) return
        cancelAudioTest()
        cancelEnhancement()

        if (engine == VoiceCaptureEngine.COMPARE) {
            startCompareRecording(languageOverride)
            return
        }

        pendingRecordLanguage = languageOverride
        sessionLockedLanguage = null
        lastAutoLangCandidate = null
        finalizeOnStop = false
        activeChunkerEvictedBytes = 0L

        val sampleRate = DecodedAudio.TARGET_SAMPLE_RATE
        val recorder = createMicRecorder(sampleRate) ?: return

        val chunker = LiveAudioChunker(chunkConfigFor(engine, sampleRate))
        recordActive.set(true)
        val startedAt = System.currentTimeMillis()

        _liveMeter.value = LiveRecordingMeter()
        playgroundState.update {
            it.copy(
                captureEngine = engine,
                recording = true,
                running = true,
                selectedFileName = "Live microphone (${engine.label})",
                chunks = emptyList(),
                streamingChunkText = "",
                detectedLanguage = null,
                lockedLanguage = null,
                totalAudioDurationSec = 0.0,
                totalTranscribeMs = 0L,
                totalFreedAudioKb = 0,
                error = null,
                enhancedOutput = "",
                enhanceElapsedMs = null,
                enhanceError = null,
            )
        }

        val engineReadyGuard = AtomicBoolean(false)
        val processor = createOrderedProcessor(
            engine = engine,
            onQueueMetrics = { m -> publishQueueMetrics(m, chunker.retainedBytes, chunker.evictedBytesTotal) },
            beforeFirstChunk = {
                if (engineReadyGuard.compareAndSet(false, true)) {
                    prepareEngineForSession(engine)
                } else {
                    null
                }
            },
        )
        activeChunkProcessor = processor

        recordJob = viewModelScope.launch(dispatchers.io) {
            val warmUpJob = launch {
                if (engineReadyGuard.compareAndSet(false, true)) {
                    val err = prepareEngineForSession(engine)
                    if (err != null) {
                        playgroundState.update { it.copy(error = err) }
                        finalizeOnStop = false
                        recordActive.set(false)
                    }
                }
            }

            try {
                recorder.startRecording()
                val shortBuf = ShortArray(1600) // 100 ms per read at 16 kHz
                val maxSamples = MAX_LIVE_RECORD_SECONDS.toLong() * sampleRate
                while (recordActive.get()) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        val frame = FloatArray(read) { i -> shortBuf[i] / 32768f }
                        val frameRes = chunker.appendFrame(frame)
                        activeChunkerEvictedBytes = frameRes.evictedBytesTotal

                        frameRes.emittedChunk?.let { chunk -> processor.enqueue(chunk) }

                        val qSnap = processor.snapshot()
                        _liveMeter.value = LiveRecordingMeter(
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            amplitude = frameRes.normalizedLevel,
                            bufferedAudioKb = bytesToKb(frameRes.retainedBytes + qSnap.queuedBytes),
                            queuedChunks = qSnap.queuedCount,
                            processingChunkSeq = qSnap.processingSequenceId,
                            completedChunks = qSnap.completedCount,
                            freedAudioKb = bytesToKb(qSnap.freedBytes + idleSilenceEvicted(frameRes.evictedBytesTotal, qSnap)),
                        )

                        if (frameRes.totalSamples >= maxSamples) {
                            finalizeOnStop = true
                            recordActive.set(false)
                            break
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (e: Throwable) {
                playgroundState.update { it.copy(error = "Recording error: ${e.message}") }
                finalizeOnStop = false
            } finally {
                recordActive.set(false)
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                warmUpJob.join()

                playgroundState.update { it.copy(recording = false) }
                _liveMeter.update { it.copy(amplitude = 0f) }

                if (!finalizeOnStop) {
                    cancelActiveInferenceRequests()
                    processor.cancelAndPurge()
                    chunker.reset()
                    if (activeChunkProcessor === processor) activeChunkProcessor = null
                    _liveMeter.value = LiveRecordingMeter()
                    playgroundState.update { it.copy(running = false, streamingChunkText = "") }
                } else {
                    chunker.flushFinalChunk()?.let { finalChunk -> processor.enqueue(finalChunk) }
                    activeChunkerEvictedBytes = chunker.evictedBytesTotal
                    processor.finishAndDrain()

                    val finalSnap = processor.snapshot()
                    val freedKb = bytesToKb(finalSnap.freedBytes + idleSilenceEvicted(chunker.evictedBytesTotal, finalSnap))
                    if (activeChunkProcessor === processor) activeChunkProcessor = null
                    _liveMeter.update {
                        it.copy(
                            bufferedAudioKb = 0,
                            queuedChunks = 0,
                            processingChunkSeq = null,
                            completedChunks = finalSnap.completedCount,
                            freedAudioKb = freedKb,
                        )
                    }
                    playgroundState.update {
                        it.copy(
                            running = false,
                            streamingChunkText = "",
                            totalFreedAudioKb = freedKb,
                        )
                    }

                    val st = playgroundState.value
                    if (st.chunks.isEmpty() && st.error == null && chunker.totalSamples < sampleRate / 5) {
                        playgroundState.update {
                            it.copy(error = "Recording was too short (< 0.2 s). Try speaking a bit longer.")
                        }
                    } else if (engine != VoiceCaptureEngine.LLM_DIRECT && st.autoEnhance && st.fullCanonicalTranscript.isNotBlank()) {
                        enhanceTranscript(st.fullCanonicalTranscript, st.detectedLanguage)
                    }
                }
            }
        }
    }

    /** Captures ONE shared microphone clip (<= 60 s) and benchmarks all selected ASR engines on identical PCM. */
    @SuppressLint("MissingPermission")
    private fun startCompareRecording(languageOverride: String?) {
        pendingRecordLanguage = languageOverride
        finalizeOnStop = false
        val sampleRate = DecodedAudio.TARGET_SAMPLE_RATE
        val recorder = createMicRecorder(sampleRate) ?: return

        recordActive.set(true)
        val startedAt = System.currentTimeMillis()
        _liveMeter.value = LiveRecordingMeter()
        playgroundState.update {
            it.copy(
                captureEngine = VoiceCaptureEngine.COMPARE,
                recording = true,
                running = true,
                compareRunning = false,
                compareResults = emptyList(),
                compareAudioDurationSec = null,
                error = null,
            )
        }

        recordJob = viewModelScope.launch(dispatchers.io) {
            val captured = ArrayList<FloatArray>()
            var totalSamples = 0
            val maxCompareSamples = MAX_COMPARE_RECORD_SECONDS * sampleRate
            try {
                recorder.startRecording()
                val shortBuf = ShortArray(1600)
                while (recordActive.get()) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        var sumSq = 0.0
                        val chunk = FloatArray(read) { i ->
                            val f = shortBuf[i] / 32768f
                            sumSq += f * f
                            f
                        }
                        captured.add(chunk)
                        totalSamples += read
                        val rms = sqrt(sumSq / read).toFloat()
                        _liveMeter.value = LiveRecordingMeter(
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            amplitude = (rms * 4.5f).coerceIn(0f, 1f),
                            bufferedAudioKb = bytesToKb(totalSamples.toLong() * 4L),
                        )
                        if (totalSamples >= maxCompareSamples) {
                            finalizeOnStop = true
                            recordActive.set(false)
                            break
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (e: Throwable) {
                playgroundState.update { it.copy(error = "Recording error: ${e.message}") }
                finalizeOnStop = false
            } finally {
                recordActive.set(false)
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                playgroundState.update { it.copy(recording = false) }
                _liveMeter.update { it.copy(amplitude = 0f) }

                if (!finalizeOnStop || totalSamples < sampleRate / 4) {
                    captured.clear()
                    _liveMeter.value = LiveRecordingMeter()
                    playgroundState.update {
                        it.copy(
                            running = false,
                            compareRunning = false,
                            error = if (finalizeOnStop) "Recording too short (< 0.25 s) for comparison." else null,
                        )
                    }
                } else {
                    val pcm = FloatArray(totalSamples)
                    var off = 0
                    for (c in captured) {
                        c.copyInto(pcm, off)
                        off += c.size
                    }
                    captured.clear()
                    runComparisonOnAudio(pcm, pendingRecordLanguage)
                }
            }
        }
    }

    /** Runs identical decoded audio file sequentially across all selected engines in Compare mode. */
    fun runCompareAudioFile(uri: Uri, languageOverride: String? = null) {
        if (recordActive.get()) discardRecording()
        cancelAudioTest()
        cancelEnhancement()

        val displayName = resolveDisplayName(uri)
        val mime = context.contentResolver.getType(uri)
        playgroundState.update {
            it.copy(
                captureEngine = VoiceCaptureEngine.COMPARE,
                recording = false,
                running = true,
                compareRunning = true,
                compareResults = emptyList(),
                compareAudioDurationSec = null,
                selectedFileName = displayName,
                error = null,
            )
        }

        compareJob = viewModelScope.launch(dispatchers.io) {
            val tmp = File(context.cacheDir, "asr_uploads/cmp_${UUID.randomUUID()}.tmp")
            try {
                tmp.parentFile?.mkdirs()
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open selected audio file")
                input.use { src -> FileOutputStream(tmp).use { dst -> src.copyTo(dst) } }
                val decoded = audioDecoder.decode(
                    file = tmp,
                    contentType = mime,
                    fileName = displayName,
                    maxDurationSec = 180,
                )
                tmp.delete()
                runComparisonOnAudio(decoded.samples, languageOverride)
            } catch (_: CancellationException) {
                playgroundState.update { it.copy(running = false, compareRunning = false) }
            } catch (e: Throwable) {
                playgroundState.update {
                    it.copy(running = false, compareRunning = false, error = e.message ?: "Comparison failed")
                }
            } finally {
                runCatching { tmp.delete() }
            }
        }
    }

    private suspend fun runComparisonOnAudio(pcm16kMono: FloatArray, languageOverride: String?) {
        val selected = playgroundState.value.compareSelectedEngines
            .sortedBy { it.ordinal }
            .ifEmpty { listOf(AsrEngineKind.WHISPER, AsrEngineKind.AKSHAR_V4, AsrEngineKind.SHRUTI_HI) }
        val durSec = pcm16kMono.size.toDouble() / DecodedAudio.TARGET_SAMPLE_RATE
        val lang = AsrModelCatalog.normalizeLanguageSpec(languageOverride ?: settingsRepo.settings.value.defaultLanguage)

        playgroundState.update {
            it.copy(
                running = true,
                compareRunning = true,
                compareResults = emptyList(),
                compareAudioDurationSec = durSec,
                error = null,
            )
        }

        try {
            comparisonRunner.compare(
                pcm16kMono = pcm16kMono,
                engines = selected,
                language = lang,
            ) { entry ->
                playgroundState.update { st ->
                    st.copy(compareResults = st.compareResults + entry)
                }
            }
        } finally {
            _liveMeter.update { it.copy(bufferedAudioKb = 0) }
            playgroundState.update { it.copy(running = false, compareRunning = false) }
            storageBump.update { it + 1 }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createMicRecorder(sampleRate: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            playgroundState.update { it.copy(error = "Device does not support 16 kHz mono AudioRecord") }
            return null
        }
        val bufferBytes = maxOf(minBuf * 2, sampleRate / 5 * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: SecurityException) {
            playgroundState.update { it.copy(error = "Microphone permission denied: ${e.message}") }
            return null
        } catch (e: Exception) {
            playgroundState.update { it.copy(error = "Could not initialize microphone: ${e.message}") }
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            playgroundState.update { it.copy(error = "AudioRecord failed to initialize on this device") }
            return null
        }
        return recorder
    }

    fun stopRecordingAndTranscribe(languageOverride: String? = null) {
        if (!recordActive.get()) return
        if (languageOverride != null) pendingRecordLanguage = languageOverride
        finalizeOnStop = true
        recordActive.set(false)
    }

    fun discardRecording() {
        finalizeOnStop = false
        recordActive.set(false)
        cancelActiveInferenceRequests()
        activeChunkProcessor?.cancelAndPurge()
        activeChunkProcessor = null
        recordJob?.cancel()
        compareJob?.cancel()
        _liveMeter.value = LiveRecordingMeter()
        playgroundState.update {
            it.copy(
                recording = false,
                running = false,
                compareRunning = false,
                chunks = emptyList(),
                streamingChunkText = "",
                totalAudioDurationSec = 0.0,
                totalTranscribeMs = 0L,
                totalFreedAudioKb = 0,
            )
        }
    }

    /** Transcribes a picked audio file chunk-by-chunk using the specified [engine]. */
    fun runAudioFile(uri: Uri, languageOverride: String? = null, engine: VoiceCaptureEngine = VoiceCaptureEngine.WHISPER_ASR) {
        if (engine == VoiceCaptureEngine.COMPARE) {
            runCompareAudioFile(uri, languageOverride)
            return
        }
        runChunkedAudioFile(uri, languageOverride, engine)
    }

    fun runAudioTest(uri: Uri, languageOverride: String? = null) {
        runChunkedAudioFile(uri, languageOverride, VoiceCaptureEngine.WHISPER_ASR)
    }

    fun runLlmAudioTest(uri: Uri, languageOverride: String? = null) {
        runChunkedAudioFile(uri, languageOverride, VoiceCaptureEngine.LLM_DIRECT)
    }

    private fun runChunkedAudioFile(
        uri: Uri,
        languageOverride: String?,
        engine: VoiceCaptureEngine,
    ) {
        if (recordActive.get()) discardRecording()
        cancelAudioTest()
        cancelEnhancement()

        pendingRecordLanguage = languageOverride
        sessionLockedLanguage = null
        lastAutoLangCandidate = null
        activeChunkerEvictedBytes = 0L
        val displayName = resolveDisplayName(uri)
        val mime = context.contentResolver.getType(uri)

        _liveMeter.value = LiveRecordingMeter()
        playgroundState.update {
            it.copy(
                captureEngine = engine,
                recording = false,
                running = true,
                selectedFileName = "$displayName (${engine.label})",
                chunks = emptyList(),
                streamingChunkText = "",
                detectedLanguage = null,
                lockedLanguage = null,
                totalAudioDurationSec = 0.0,
                totalTranscribeMs = 0L,
                totalFreedAudioKb = 0,
                error = null,
                enhancedOutput = "",
                enhanceElapsedMs = null,
                enhanceError = null,
            )
        }

        fileJob = viewModelScope.launch(dispatchers.io) {
            val tmp = File(context.cacheDir, "asr_uploads/pg_${UUID.randomUUID()}.tmp")
            var processor: OrderedChunkProcessor? = null
            try {
                val readyErr = prepareEngineForSession(engine)
                if (readyErr != null) {
                    playgroundState.update { it.copy(running = false, error = readyErr) }
                    return@launch
                }

                tmp.parentFile?.mkdirs()
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open selected audio file")
                input.use { src -> FileOutputStream(tmp).use { dst -> src.copyTo(dst) } }

                val decoded = audioDecoder.decode(
                    file = tmp,
                    contentType = mime,
                    fileName = displayName,
                    maxDurationSec = 600,
                )
                tmp.delete()

                val maxChunkSec = when (engine) {
                    VoiceCaptureEngine.LLM_DIRECT -> LLM_FILE_CHUNK_SEC
                    VoiceCaptureEngine.AKSHAR_V4, VoiceCaptureEngine.AKSHAR_LITE_V4, VoiceCaptureEngine.SHRUTI_HI -> CACTUS_FILE_CHUNK_SEC
                    else -> WHISPER_FILE_CHUNK_SEC
                }
                val audioChunks = LiveAudioChunker.splitDecodedAudio(
                    decoded = decoded,
                    maxChunkSec = maxChunkSec,
                    minChunkSec = 2,
                )
                if (audioChunks.isEmpty()) {
                    playgroundState.update { it.copy(running = false, error = "No voiced speech detected in selected audio file.") }
                    return@launch
                }

                processor = createOrderedProcessor(
                    engine = engine,
                    onQueueMetrics = { m -> publishQueueMetrics(m, 0L, 0L) },
                    beforeFirstChunk = { null },
                )
                activeChunkProcessor = processor
                audioChunks.forEach { processor.enqueue(it) }
                processor.finishAndDrain()

                val finalSnap = processor.snapshot()
                val freedKb = bytesToKb(finalSnap.freedBytes)
                _liveMeter.update {
                    it.copy(
                        bufferedAudioKb = 0,
                        queuedChunks = 0,
                        processingChunkSeq = null,
                        completedChunks = finalSnap.completedCount,
                        freedAudioKb = freedKb,
                    )
                }
                playgroundState.update {
                    it.copy(
                        running = false,
                        streamingChunkText = "",
                        totalFreedAudioKb = freedKb,
                    )
                }

                val st = playgroundState.value
                if (engine != VoiceCaptureEngine.LLM_DIRECT && st.autoEnhance && st.fullCanonicalTranscript.isNotBlank()) {
                    enhanceTranscript(st.fullCanonicalTranscript, st.detectedLanguage)
                }
            } catch (_: CancellationException) {
                processor?.cancelAndPurge()
                playgroundState.update { it.copy(running = false, streamingChunkText = "") }
            } catch (e: Throwable) {
                processor?.cancelAndPurge()
                playgroundState.update { it.copy(running = false, streamingChunkText = "", error = e.message ?: "Audio transcription failed") }
            } finally {
                if (activeChunkProcessor === processor) activeChunkProcessor = null
                runCatching { tmp.delete() }
                storageBump.update { it + 1 }
            }
        }
    }

    private fun createOrderedProcessor(
        engine: VoiceCaptureEngine,
        onQueueMetrics: (ChunkQueueMetrics) -> Unit,
        beforeFirstChunk: suspend () -> String?,
    ): OrderedChunkProcessor {
        return OrderedChunkProcessor(
            scope = viewModelScope,
            dispatcher = dispatchers.io,
            onMetrics = onQueueMetrics,
            onError = { chunk, err ->
                playgroundState.update {
                    it.copy(
                        streamingChunkText = "",
                        error = "Chunk #${chunk.sequenceId + 1} failed: ${err.message ?: err::class.simpleName}",
                    )
                }
            },
        ) { chunk ->
            val initErr = beforeFirstChunk()
            if (initErr != null) {
                playgroundState.update { it.copy(error = initErr) }
                return@OrderedChunkProcessor
            }

            val requestedLang = AsrModelCatalog.normalizeLanguageSpec(
                pendingRecordLanguage ?: settingsRepo.settings.value.defaultLanguage,
            )
            val effectiveLang = if (requestedLang == "auto" && sessionLockedLanguage != null) {
                sessionLockedLanguage!!
            } else {
                requestedLang
            }
            val chunkStartMs = System.currentTimeMillis()
            val prevText = playgroundState.value.chunks.lastOrNull()?.transcript?.canonicalText

            val rawText: String
            val detectedLang: String?
            when (engine) {
                VoiceCaptureEngine.WHISPER_ASR -> {
                    val (t, lang, conf) = transcribeChunkWithWhisper(chunk, effectiveLang)
                    rawText = t
                    detectedLang = lang
                    if (requestedLang == "auto" && sessionLockedLanguage == null &&
                        lang != null && lang != "auto" && lang in AsrModelCatalog.SUPPORTED_LANGUAGE_CODES
                    ) {
                        val confident = (conf >= 0.55f && chunk.durationSec >= 2.2) ||
                            conf >= 0.72f ||
                            lang == lastAutoLangCandidate
                        lastAutoLangCandidate = lang
                        if (confident && t.isNotBlank()) {
                            sessionLockedLanguage = lang
                            playgroundState.update { it.copy(lockedLanguage = lang) }
                        }
                    }
                }
                VoiceCaptureEngine.AKSHAR_V4,
                VoiceCaptureEngine.AKSHAR_LITE_V4,
                VoiceCaptureEngine.SHRUTI_HI,
                -> {
                    val kind = requireNotNull(engine.toAsrEngineKindOrNull())
                    val res = engineRegistry.get(kind).transcribeFloat(chunk.samples, effectiveLang)
                    rawText = res.text
                    detectedLang = res.language
                }
                VoiceCaptureEngine.LLM_DIRECT -> {
                    val (t, lang) = transcribeChunkWithLlm(chunk, effectiveLang, prevText)
                    rawText = t
                    detectedLang = lang
                }
                VoiceCaptureEngine.COMPARE -> {
                    rawText = ""
                    detectedLang = null
                }
            }

            val elapsedMs = (System.currentTimeMillis() - chunkStartMs).coerceAtLeast(1L)
            val cleanText = TranscriptContinuity.prepareForAppend(prevText, rawText)

            if (cleanText.isNotBlank()) {
                val converted = HinglishScriptConverter.convert(cleanText)
                val item = TranscriptChunkItem(
                    sequenceId = chunk.sequenceId,
                    transcript = converted,
                    language = detectedLang,
                    audioDurationSec = chunk.durationSec,
                    transcribeElapsedMs = elapsedMs,
                    engine = engine,
                )
                playgroundState.update { st ->
                    st.copy(
                        chunks = st.chunks + item,
                        streamingChunkText = "",
                        detectedLanguage = detectedLang ?: st.detectedLanguage,
                        totalAudioDurationSec = st.totalAudioDurationSec + chunk.durationSec,
                        totalTranscribeMs = st.totalTranscribeMs + elapsedMs,
                        error = null,
                    )
                }
            } else {
                playgroundState.update { st ->
                    st.copy(
                        streamingChunkText = "",
                        totalAudioDurationSec = st.totalAudioDurationSec + chunk.durationSec,
                        totalTranscribeMs = st.totalTranscribeMs + elapsedMs,
                    )
                }
            }
        }
    }

    private suspend fun transcribeChunkWithWhisper(
        chunk: AudioChunk,
        language: String,
    ): Triple<String, String?, Float> {
        val reqId = "asr-chunk-${chunk.sequenceId}-" + UUID.randomUUID()
        activeAsrRequestId = reqId
        try {
            val req = AsrRequest(
                requestId = reqId,
                clientId = "dashboard",
                requestedModel = "auto",
                audio = DecodedAudio(chunk.samples, chunk.sampleRate, chunk.durationSec),
                language = language,
                prompt = null,
                timestamps = true,
                singleSegment = false,
                sourceBytes = chunk.sizeBytes,
            )
            val res = asrEngine.transcribe(req)
            return Triple(res.text.trim(), res.language, res.languageConfidence)
        } finally {
            if (activeAsrRequestId == reqId) activeAsrRequestId = null
        }
    }

    private suspend fun transcribeChunkWithLlm(
        chunk: AudioChunk,
        language: String,
        previousChunkText: String?,
    ): Pair<String, String?> {
        val wavBytes = WavPcmDecoder.encodeWavPcm16(chunk.samples, chunk.sampleRate)
        val priorTail = previousChunkText?.takeLast(120)?.trim()?.takeIf { it.isNotEmpty() }
        val text = transcribeWavWithLlm(
            wavBytes = wavBytes,
            languageHint = language,
            priorContext = priorTail,
        ) { partial ->
            playgroundState.update { it.copy(streamingChunkText = partial) }
        }
        val langLabel = language.takeIf { it != "auto" } ?: "auto (LLM)"
        return text to langLabel
    }

    private suspend fun prepareEngineForSession(engine: VoiceCaptureEngine): String? = when (engine) {
        VoiceCaptureEngine.WHISPER_ASR -> {
            val loaded = runCatching { modelManager.ensureLoaded() }.getOrDefault(false)
            if (!loaded && !modelManager.status.value.isLoaded) {
                modelManager.status.value.errorMessage
                    ?: "Whisper ASR model is not loaded. Install or load one in Models -> Speech (ASR)."
            } else {
                null
            }
        }
        VoiceCaptureEngine.AKSHAR_V4,
        VoiceCaptureEngine.AKSHAR_LITE_V4,
        VoiceCaptureEngine.SHRUTI_HI,
        -> {
            val kind = requireNotNull(engine.toAsrEngineKindOrNull())
            runCatching { hinglishModelManager.ensureLoaded(kind) }
                .fold(
                    onSuccess = { null },
                    onFailure = { it.message ?: "Failed to load ${kind.displayName}" },
                )
        }
        VoiceCaptureEngine.LLM_DIRECT -> ensureAudioCapableLlmReady()
        VoiceCaptureEngine.COMPARE -> null
    }

    private fun publishQueueMetrics(
        m: ChunkQueueMetrics,
        chunkerRetainedBytes: Long,
        chunkerEvictedBytes: Long,
    ) {
        val freedKb = bytesToKb(m.freedBytes + idleSilenceEvicted(chunkerEvictedBytes, m))
        _liveMeter.update {
            it.copy(
                bufferedAudioKb = bytesToKb(chunkerRetainedBytes + m.queuedBytes),
                queuedChunks = m.queuedCount,
                processingChunkSeq = m.processingSequenceId,
                completedChunks = m.completedCount,
                freedAudioKb = freedKb,
            )
        }
    }

    private fun idleSilenceEvicted(chunkerEvictedBytes: Long, m: ChunkQueueMetrics): Long =
        (chunkerEvictedBytes - m.freedBytes - m.queuedBytes).coerceAtLeast(0L)

    private suspend fun ensureAudioCapableLlmReady(): String? {
        var st = llmModelManager.status.value
        if (st.state == ModelState.NOT_INSTALLED && llmModelManager.isInstalled) {
            llmModelManager.load()
            st = llmModelManager.status.value
        }
        if (st.state != ModelState.READY && st.state != ModelState.GENERATING) {
            return if (!llmModelManager.isInstalled) {
                "No LLM installed. Download Gemma 4 E2B/E4B (.litertlm) in Models -> LLM."
            } else {
                st.errorMessage ?: "LLM model is not ready (state=${st.state}). Load Gemma 4 first."
            }
        }
        if (!st.supportsAudioInput) {
            return "Loaded LLM (${st.model?.name ?: "current model"}) has no native audio encoder. Load a multimodal Gemma 4 E2B/E4B (.litertlm) model."
        }
        return null
    }

    private suspend fun transcribeWavWithLlm(
        wavBytes: ByteArray,
        languageHint: String,
        priorContext: String?,
        onPartial: (String) -> Unit,
    ): String {
        val id = "llm-asr-" + UUID.randomUUID()
        activeLlmAudioId = id
        val cfg = llmSettingsRepo.settings.value
        val activeLlmId = llmModelManager.installedInfo()?.id ?: "gemma-4"

        val langDirective = when (val norm = AsrModelCatalog.normalizeLanguageSpec(languageHint)) {
            "auto" -> "Auto-detect the speaker's exact language, regional dialect, and code-switching directly from the audio."
            "en,hi" -> "Allowed languages ONLY: English and Hindi / Hinglish. Match the speaker's exact dialect and script (Devanagari for Hindi, Latin for English/Hinglish); NEVER output Urdu, Chinese, or other unrelated scripts."
            "en" -> "Primary language: English (keep the speaker's exact regional dialect/expressions)."
            "hi" -> "Primary language: Hindi / Hinglish (preserve Devanagari or Hinglish matching the speaker; do not switch to Urdu or unrelated scripts)."
            else -> "Primary language: ${AsrModelCatalog.displayNameForLanguage(norm)} ($norm). Transcribe strictly in this language and natural script."
        }
        val systemInstruction = """
            You are a precise multilingual automatic speech recognition (ASR) engine.
            Transcribe ONLY the spoken audio in this clip verbatim with clean punctuation — NEVER summarize, answer, comment on, or translate the speech.

            Rules:
            1. Understand the speaker's exact language, regional dialect, accent, and code-switching (e.g. Hinglish, conversational Hindi, Bhojpuri, Marathi, Punjabi, Tamil, Indian English, US/UK English, or mixed dialects).
            2. Write the transcript strictly in that SAME detected dialect, natural vocabulary, and natural script (Devanagari for pure Hindi/regional dialects, natural Hinglish/Latin for heavily code-switched Hinglish, or the native script of the spoken language).
            3. Keep 100% of spoken words, names, and numbers intact with clean punctuation.
            4. If the clip contains only silence or noise with no intelligible speech, output nothing.
            5. Output ONLY the new transcript text — no labels, no timestamps, no explanations.
            $langDirective
        """.trimIndent()

        val userPrompt = if (!priorContext.isNullOrBlank()) {
            "Previous chunk ended with (context only — DO NOT repeat it): \"$priorContext\"\nTranscribe ONLY this new audio chunk verbatim in the speaker's dialect."
        } else {
            "Transcribe this audio chunk verbatim in the speaker's exact language and dialect."
        }

        val messages = listOf(
            ChatMessage("system", systemInstruction),
            ChatMessage("user", userPrompt),
        )
        val params = GenerationParams(
            temperature = minOf(cfg.temperature, 0.15f),
            maxTokens = minOf(cfg.maxOutputTokens, 1024).coerceIn(128, InferenceSettings.MAX_TOKENS_32K),
            topP = cfg.topP,
            topK = cfg.topK,
        )

        val sb = StringBuilder()
        try {
            llmEngine.stream(
                InferenceRequest(
                    requestId = id,
                    clientId = "dashboard",
                    modelId = activeLlmId,
                    messages = messages,
                    params = params,
                    audioWavBytes = wavBytes,
                ),
            ).collect { chunk ->
                sb.append(chunk)
                onPartial(sb.toString().trim())
            }
            return sb.toString().trim()
        } finally {
            if (activeLlmAudioId == id) activeLlmAudioId = null
        }
    }

    private fun resolveDisplayName(uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: "audio_sample"

    fun enhanceTranscript(transcriptOverride: String? = null, detectedLangOverride: String? = null) {
        val text = (transcriptOverride ?: playgroundState.value.fullCanonicalTranscript).trim()
        if (text.isBlank()) return

        val llmState = llmModelManager.status.value.state
        if (llmState != ModelState.READY && llmState != ModelState.GENERATING) {
            playgroundState.update {
                it.copy(
                    enhanceRunning = false,
                    enhanceError = "LLM model is not loaded (state=$llmState). Load a Gemma model to enhance transcription.",
                )
            }
            return
        }

        enhanceJob?.cancel()
        val id = "asr-enh-" + UUID.randomUUID()
        activeEnhanceId = id
        val cfg = llmSettingsRepo.settings.value
        val activeLlmId = llmModelManager.installedInfo()?.id ?: "gemma-4"
        val langHint = (detectedLangOverride ?: playgroundState.value.detectedLanguage ?: "auto")

        val systemInstruction = """
            You are an expert multilingual speech-to-text editor and dialect specialist.
            Your ONLY job is to ENHANCE the raw ASR transcription — NEVER summarize, shorten, drop details, or translate to a different language or dialect.

            Strict rules:
            1. Detect the speaker's exact language, regional dialect, register, and code-switching style from the transcript (for example: Hinglish, conversational Hindi, Bhojpuri, Marathi, Punjabi, Tamil, Indian English, British/US English, or mixed dialects).
            2. Produce the enhanced transcript strictly in that SAME detected dialect, natural vocabulary, and appropriate script:
               - Preserve regional dialect words, idioms, and natural Hinglish / code-switched phrasing rather than converting to stiff formal English or textbook language.
               - Match the natural script for the detected dialect (e.g., Devanagari for Hindi/Marathi/Bhojpuri when spoken in pure Hindi/regional dialect, or natural Hinglish/Latin when the speaker mixes English heavily).
            3. Correct speech recognition mistakes (phonetic errors, wrong homophones, broken words), add clean punctuation, capitalization, and natural paragraph spacing, and remove accidental stutter repetitions only.
            4. Keep 100% of the speaker's facts, names, numbers, and complete meaning intact.
            5. Output ONLY the enhanced transcript — no intro, no commentary, no labels.
        """.trimIndent()

        val messages = listOf(
            ChatMessage("system", systemInstruction),
            ChatMessage("user", "ASR language hint: $langHint\n\nRaw transcript:\n$text"),
        )
        val estimatedOutTokens = maxOf(512, text.split(Regex("\\s+")).size * 3)
        val params = GenerationParams(
            temperature = minOf(cfg.temperature, 0.25f),
            maxTokens = minOf(cfg.maxOutputTokens, estimatedOutTokens).coerceIn(128, InferenceSettings.MAX_TOKENS_32K),
            topP = cfg.topP,
            topK = cfg.topK,
        )

        val startMs = System.currentTimeMillis()
        playgroundState.update {
            it.copy(
                enhanceRunning = true,
                enhancedOutput = "",
                enhanceElapsedMs = 0L,
                enhanceError = null,
            )
        }

        enhanceJob = viewModelScope.launch(dispatchers.io) {
            llmEngine.stream(InferenceRequest(id, "dashboard", activeLlmId, messages, params))
                .catch { e ->
                    playgroundState.update {
                        it.copy(
                            enhanceRunning = false,
                            enhanceError = e.message ?: "LLM enhancement failed",
                        )
                    }
                }
                .collect { chunk ->
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    playgroundState.update {
                        it.copy(
                            enhancedOutput = it.enhancedOutput + chunk,
                            enhanceElapsedMs = elapsed,
                        )
                    }
                }
            val finalElapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            playgroundState.update {
                it.copy(enhanceRunning = false, enhanceElapsedMs = finalElapsed)
            }
        }
    }

    fun cancelEnhancement() {
        activeEnhanceId?.let(llmEngine::cancel)
        enhanceJob?.cancel()
        playgroundState.update { it.copy(enhanceRunning = false) }
    }

    fun cancelAudioTest() {
        cancelActiveInferenceRequests()
        activeChunkProcessor?.cancelAndPurge()
        activeChunkProcessor = null
        fileJob?.cancel()
        compareJob?.cancel()
        playgroundState.update { it.copy(running = false, compareRunning = false, streamingChunkText = "") }
        _liveMeter.update { it.copy(queuedChunks = 0, processingChunkSeq = null, bufferedAudioKb = 0) }
    }

    fun clearAudioTest() {
        if (recordActive.get()) discardRecording()
        cancelAudioTest()
        cancelEnhancement()
        _liveMeter.value = LiveRecordingMeter()
        val keepAuto = playgroundState.value.autoEnhance
        val keepCompareSet = playgroundState.value.compareSelectedEngines
        playgroundState.value = AsrPlaygroundState(
            autoEnhance = keepAuto,
            compareSelectedEngines = keepCompareSet,
        )
    }

    override fun onCleared() {
        discardRecording()
        cancelAudioTest()
        cancelEnhancement()
        super.onCleared()
    }

    private fun cancelActiveInferenceRequests() {
        activeAsrRequestId?.let(asrEngine::cancel)
        activeLlmAudioId?.let(llmEngine::cancel)
    }

    private fun chunkConfigFor(engine: VoiceCaptureEngine, sampleRate: Int): ChunkedAudioConfig = when (engine) {
        VoiceCaptureEngine.WHISPER_ASR -> ChunkedAudioConfig(
            sampleRate = sampleRate,
            minChunkMs = 3_000L,
            maxChunkMs = 10_000L,
            pauseCommitMs = 850L,
            preRollMs = 250L,
            silenceTrimStepMs = 200L,
            cutSearchTailMs = 1_500L,
            speechRmsThreshold = 0.022f,
            minVoicedPeak = 0.045f,
            minVoicedFrames50ms = 3,
        )
        VoiceCaptureEngine.AKSHAR_V4,
        VoiceCaptureEngine.AKSHAR_LITE_V4,
        VoiceCaptureEngine.SHRUTI_HI,
        -> ChunkedAudioConfig(
            sampleRate = sampleRate,
            // Cactus 4-bit Hinglish models decode much faster than Whisper; 2 s - 8 s chunks keep live latency snappy.
            minChunkMs = 2_000L,
            maxChunkMs = 8_000L,
            pauseCommitMs = 650L,
            preRollMs = 250L,
            silenceTrimStepMs = 200L,
            cutSearchTailMs = 1_200L,
            speechRmsThreshold = 0.022f,
            minVoicedPeak = 0.045f,
            minVoicedFrames50ms = 3,
        )
        VoiceCaptureEngine.LLM_DIRECT,
        VoiceCaptureEngine.COMPARE,
        -> ChunkedAudioConfig(
            sampleRate = sampleRate,
            minChunkMs = 2_500L,
            maxChunkMs = 14_000L,
            pauseCommitMs = 950L,
            preRollMs = 250L,
            silenceTrimStepMs = 200L,
            cutSearchTailMs = 1_500L,
            speechRmsThreshold = 0.022f,
            minVoicedPeak = 0.045f,
            minVoicedFrames50ms = 3,
        )
    }

    private fun bytesToKb(bytes: Long): Int =
        if (bytes <= 0L) 0 else ((bytes + 1023L) / 1024L).toInt()

    private companion object {
        const val MAX_LIVE_RECORD_SECONDS = 1800
        const val MAX_COMPARE_RECORD_SECONDS = 60
        const val WHISPER_FILE_CHUNK_SEC = 10
        const val CACTUS_FILE_CHUNK_SEC = 18
        const val LLM_FILE_CHUNK_SEC = 15
    }
}
