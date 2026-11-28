package com.uoai.asr.engines

import com.uoai.asr.AsrEngine
import com.uoai.asr.AsrModelManager
import com.uoai.asr.AsrRequest
import com.uoai.asr.audio.DecodedAudio
import com.uoai.asr.audio.LiveAudioChunker
import com.uoai.asr.audio.WavPcmDecoder
import com.uoai.asr.runtime.CactusSpeechBackend
import java.util.UUID

/** Unified transcription output returned by any [SpeechEngineProvider]. */
data class SpeechEngineResult(
    val engineKind: AsrEngineKind,
    val engineId: String,
    val engineDisplayName: String,
    val transcript: HinglishTranscript,
    val language: String?,
    val languageConfidence: Float = 0f,
    val audioDurationSec: Double,
    val inferenceDurationMs: Long,
    val loadTimeMs: Long = 0L,
) {
    val text: String get() = transcript.canonicalText
    val rtf: Double get() = if (audioDurationSec > 0.01) (inferenceDurationMs / 1000.0) / audioDurationSec else 0.0
}

/**
 * Common provider abstraction across all offline ASR engines (Whisper, AksharSTT v4,
 * AksharSTT Lite v4, Shruti). Supports both `ShortArray` PCM16 (with automatic resampling to
 * 16 kHz) and zero-copy `FloatArray` 16 kHz mono PCM from the shared audio pipeline.
 */
interface SpeechEngineProvider {
    val id: String
    val kind: AsrEngineKind
    val displayName: String
    val isInitialized: Boolean

    fun isModelInstalled(): Boolean
    suspend fun initialize(): Long
    suspend fun transcribe(audio: ShortArray, sampleRate: Int = DecodedAudio.TARGET_SAMPLE_RATE, language: String? = null): SpeechEngineResult
    suspend fun transcribeFloat(pcm16kMono: FloatArray, language: String? = null): SpeechEngineResult
    suspend fun release()
}

/**
 * Adapter wrapping UOAI's existing `whisper.cpp` [AsrModelManager] + [AsrEngine] without modifying
 * Whisper's core scheduling, API, or native behavior.
 */
class ExistingWhisperEngine(
    private val modelManager: AsrModelManager,
    private val asrEngine: AsrEngine,
) : SpeechEngineProvider {
    override val id: String = AsrEngineKind.WHISPER.id
    override val kind: AsrEngineKind = AsrEngineKind.WHISPER
    override val displayName: String
        get() = "Whisper (${modelManager.status.value.activePreset.displayName})"
    override val isInitialized: Boolean
        get() = modelManager.status.value.isLoaded

    override fun isModelInstalled(): Boolean = modelManager.status.value.isSelectedInstalled

    override suspend fun initialize(): Long = modelManager.load()

    override suspend fun transcribe(audio: ShortArray, sampleRate: Int, language: String?): SpeechEngineResult {
        val pcm16k = shortPcmTo16kFloat(audio, sampleRate)
        return transcribeFloat(pcm16k, language)
    }

    override suspend fun transcribeFloat(pcm16kMono: FloatArray, language: String?): SpeechEngineResult {
        val loadMs = if (!isInitialized) initialize() else 0L
        val durSec = pcm16kMono.size.toDouble() / DecodedAudio.TARGET_SAMPLE_RATE
        val req = AsrRequest(
            requestId = "cmp-whisper-" + UUID.randomUUID(),
            clientId = "dashboard",
            requestedModel = "auto",
            audio = DecodedAudio(pcm16kMono, DecodedAudio.TARGET_SAMPLE_RATE, durSec),
            language = language,
            prompt = null,
            timestamps = true,
            sourceBytes = pcm16kMono.size * 4L,
        )
        val res = asrEngine.transcribe(req)
        return SpeechEngineResult(
            engineKind = kind,
            engineId = id,
            engineDisplayName = displayName,
            transcript = HinglishScriptConverter.convert(res.text),
            language = res.language,
            languageConfidence = res.languageConfidence,
            audioDurationSec = res.durationSec,
            inferenceDurationMs = res.elapsedMs,
            loadTimeMs = loadMs,
        )
    }

    override suspend fun release() {
        modelManager.unload()
    }
}

/**
 * Shared base adapter for Cactus 4.0.0 `.cact` Hinglish ASR engines ([AksharEngine] & [ShrutiEngine]).
 * Automatically splits audio > 28 s at low-energy boundaries via [LiveAudioChunker.splitDecodedAudio]
 * and maps language hints cleanly (`en` for English, `hi` for Hindi/Hinglish/auto).
 */
abstract class BaseCactusHinglishEngine(
    final override val kind: AsrEngineKind,
    private val modelManager: HinglishModelManager,
    private val cactusBackend: CactusSpeechBackend,
) : SpeechEngineProvider {
    private val spec: HinglishModelSpec = requireNotNull(HinglishModelCatalog.specFor(kind)) {
        "Missing HinglishModelSpec for $kind"
    }

    override val id: String = spec.id
    override val displayName: String = spec.displayName
    override val isInitialized: Boolean
        get() = modelManager.status.value.isLoaded(kind)

    override fun isModelInstalled(): Boolean = modelManager.isInstalled(kind)

    override suspend fun initialize(): Long = modelManager.ensureLoaded(kind)

    override suspend fun transcribe(audio: ShortArray, sampleRate: Int, language: String?): SpeechEngineResult {
        val pcm16k = shortPcmTo16kFloat(audio, sampleRate)
        return transcribeFloat(pcm16k, language)
    }

    override suspend fun transcribeFloat(pcm16kMono: FloatArray, language: String?): SpeechEngineResult {
        val loadMs = modelManager.ensureLoaded(kind)
        val durSec = pcm16kMono.size.toDouble() / DecodedAudio.TARGET_SAMPLE_RATE
        if (pcm16kMono.isEmpty()) {
            return SpeechEngineResult(
                engineKind = kind,
                engineId = id,
                engineDisplayName = displayName,
                transcript = HinglishTranscript("", "", ""),
                language = spec.defaultLanguage,
                audioDurationSec = 0.0,
                inferenceDurationMs = 0L,
                loadTimeMs = loadMs,
            )
        }

        val effectiveLang = resolveCactusLanguage(language, spec)
        val startMs = System.currentTimeMillis()
        val combinedText = StringBuilder()
        var detectedLang: String? = effectiveLang

        if (durSec <= MAX_SINGLE_PASS_SEC) {
            val out = cactusBackend.transcribe(pcm16kMono, language = effectiveLang)
            combinedText.append(out.text.trim())
            detectedLang = out.language ?: effectiveLang
        } else {
            val chunks = LiveAudioChunker.splitDecodedAudio(
                decoded = DecodedAudio(pcm16kMono, DecodedAudio.TARGET_SAMPLE_RATE, durSec),
                maxChunkSec = 25,
                minChunkSec = 3,
            )
            for (chunk in chunks) {
                val out = cactusBackend.transcribe(chunk.samples, language = effectiveLang)
                val piece = out.text.trim()
                if (piece.isNotEmpty()) {
                    if (combinedText.isNotEmpty()) combinedText.append(' ')
                    combinedText.append(piece)
                }
                if (!out.language.isNullOrBlank()) detectedLang = out.language
            }
        }

        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val canonical = combinedText.toString().trim()
        return SpeechEngineResult(
            engineKind = kind,
            engineId = id,
            engineDisplayName = displayName,
            transcript = HinglishScriptConverter.convert(canonical),
            language = detectedLang,
            languageConfidence = 1.0f,
            audioDurationSec = durSec,
            inferenceDurationMs = elapsedMs,
            loadTimeMs = loadMs,
        )
    }

    override suspend fun release() {
        modelManager.release()
    }

    private fun resolveCactusLanguage(requested: String?, spec: HinglishModelSpec): String {
        val norm = requested?.trim()?.lowercase().orEmpty()
        return when {
            norm == "en" -> "en"
            norm == "hi" || norm == "en,hi" || norm == "hi,en" -> "hi"
            norm in spec.supportedLanguages -> norm
            else -> spec.defaultLanguage // "hi" handles Hindi + Hinglish code-switching natively in Akshar & Shruti
        }
    }

    private companion object {
        const val MAX_SINGLE_PASS_SEC = 28.0
    }
}

/** AksharSTT v4 / AksharSTT Lite v4 offline Hinglish ASR engine (ByteKnot Labs, Apache-2.0). */
class AksharEngine(
    kind: AsrEngineKind = AsrEngineKind.AKSHAR_V4,
    modelManager: HinglishModelManager,
    cactusBackend: CactusSpeechBackend,
) : BaseCactusHinglishEngine(kind, modelManager, cactusBackend) {
    init {
        require(kind == AsrEngineKind.AKSHAR_V4 || kind == AsrEngineKind.AKSHAR_LITE_V4) {
            "AksharEngine supports AKSHAR_V4 or AKSHAR_LITE_V4, got $kind"
        }
    }
}

/** Shruti Hindi/Hinglish offline ASR engine (Udgam Labs, Apache-2.0). */
class ShrutiEngine(
    modelManager: HinglishModelManager,
    cactusBackend: CactusSpeechBackend,
) : BaseCactusHinglishEngine(AsrEngineKind.SHRUTI_HI, modelManager, cactusBackend)

/** Central registry dispatching across all offline ASR engine adapters. */
class SpeechEngineRegistry(
    val whisper: ExistingWhisperEngine,
    val aksharV4: AksharEngine,
    val aksharLiteV4: AksharEngine,
    val shrutiHi: ShrutiEngine,
) {
    fun get(kind: AsrEngineKind): SpeechEngineProvider = when (kind) {
        AsrEngineKind.WHISPER -> whisper
        AsrEngineKind.AKSHAR_V4 -> aksharV4
        AsrEngineKind.AKSHAR_LITE_V4 -> aksharLiteV4
        AsrEngineKind.SHRUTI_HI -> shrutiHi
    }

    fun allEngines(): List<SpeechEngineProvider> = listOf(whisper, aksharV4, aksharLiteV4, shrutiHi)

    fun installedEngines(): List<SpeechEngineProvider> = allEngines().filter { it.isModelInstalled() }
}

internal fun shortPcmTo16kFloat(audio: ShortArray, sampleRate: Int): FloatArray {
    if (audio.isEmpty()) return FloatArray(0)
    val floats = FloatArray(audio.size) { i -> audio[i] / 32768f }
    return if (sampleRate == DecodedAudio.TARGET_SAMPLE_RATE) {
        floats
    } else {
        WavPcmDecoder.resampleLinear(floats, sampleRate, DecodedAudio.TARGET_SAMPLE_RATE)
    }
}
