package com.uoai.asr.runtime

import com.uoai.asr.engines.CactModelValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Shared thread-safe runtime owner for Cactus 4.0.0 `.cact` speech models (`libcactus_jni.so`).
 *
 * Cactus maintains one process-wide speech model slot (`CACTUS_SPEECH`). This coordinator
 * serializes loads and transcriptions via [nativeMutex] and tracks [loadedModelPath] so
 * switching between AksharSTT v4, AksharSTT Lite v4, and Shruti reloads cleanly without
 * ever leaking or corrupting native state.
 */
interface CactusSpeechBackend {
    val loadedModelPath: String?
    val isSpeechLoaded: Boolean
    suspend fun loadModel(modelPath: String): Long
    suspend fun transcribe(pcm16kMono: FloatArray, language: String? = null, keywords: String? = null): CactusTranscribeOutput
    suspend fun reset()
}

data class CactusTranscribeOutput(
    val text: String,
    val language: String?,
    val ttftMs: Double? = null,
    val decodeTps: Double? = null,
    val elapsedMs: Long = 0L,
)

class CactusRuntimeAdapter : CactusSpeechBackend {
    private val nativeMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    override var loadedModelPath: String? = null
        private set

    override val isSpeechLoaded: Boolean
        get() = loadedModelPath != null

    override suspend fun loadModel(modelPath: String): Long = withContext(Dispatchers.IO) {
        nativeMutex.withLock {
            val file = File(modelPath)
            if (loadedModelPath == file.absolutePath && isNativeSpeechReady()) {
                return@withLock 0L
            }
            val headerCheck = CactModelValidator.validateHeader(file, minSizeBytes = 1_000_000L)
            if (!headerCheck.valid) {
                throw IllegalStateException("Invalid .cact model '${file.name}': ${headerCheck.reason}")
            }

            CactusNative.ensureLibraryLoaded()
            val t0 = System.currentTimeMillis()
            val err = CactusNative.nativeLoadFromFile(file.absolutePath)
            if (err != null) {
                loadedModelPath = null
                throw IllegalStateException("Cactus failed to load '${file.name}': $err")
            }
            val mask = CactusNative.nativeLoadedModelsMask()
            if ((mask and CactusNative.MASK_SPEECH) == 0) {
                loadedModelPath = null
                throw IllegalStateException("Loaded '${file.name}' but Cactus speech slot is not active (mask=$mask)")
            }
            loadedModelPath = file.absolutePath
            (System.currentTimeMillis() - t0).coerceAtLeast(1L)
        }
    }

    override suspend fun transcribe(
        pcm16kMono: FloatArray,
        language: String?,
        keywords: String?,
    ): CactusTranscribeOutput = withContext(Dispatchers.IO) {
        if (pcm16kMono.isEmpty()) {
            return@withContext CactusTranscribeOutput(text = "", language = language, elapsedMs = 0L)
        }
        nativeMutex.withLock {
            check(loadedModelPath != null) { "No Cactus speech model is loaded" }
            val t0 = System.currentTimeMillis()
            val raw = CactusNative.nativeTranscribe(
                audioSamples = pcm16kMono,
                language = language?.takeIf { it.isNotBlank() && it != "auto" },
                keywords = keywords?.takeIf { it.isNotBlank() },
                wordTimestamps = false,
            )
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            if (raw.startsWith(CactusNative.ERROR_PREFIX)) {
                throw IllegalStateException(raw.removePrefix(CactusNative.ERROR_PREFIX))
            }
            parseCactusJson(raw, fallbackLang = language, elapsedMs = elapsed)
        }
    }

    override suspend fun reset() = withContext(Dispatchers.IO) {
        nativeMutex.withLock {
            if (loadedModelPath != null) {
                runCatching { CactusNative.nativeReset() }
            }
        }
    }

    private fun isNativeSpeechReady(): Boolean = runCatching {
        CactusNative.ensureLibraryLoaded()
        (CactusNative.nativeLoadedModelsMask() and CactusNative.MASK_SPEECH) != 0
    }.getOrDefault(false)

    internal fun parseCactusJson(raw: String, fallbackLang: String?, elapsedMs: Long): CactusTranscribeOutput {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return CactusTranscribeOutput(text = "", language = fallbackLang, elapsedMs = elapsedMs)
        }
        return runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val err = obj["error"]?.jsonPrimitive?.contentOrNull
            if (!err.isNullOrBlank()) {
                throw IllegalStateException("Cactus transcription error: $err")
            }
            val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val lang = obj["language"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallbackLang
            val ttft = obj["ttft_ms"]?.jsonPrimitive?.doubleOrNull
            val tps = obj["decode_tps"]?.jsonPrimitive?.doubleOrNull
            CactusTranscribeOutput(
                text = text,
                language = lang,
                ttftMs = ttft,
                decodeTps = tps,
                elapsedMs = elapsedMs,
            )
        }.getOrElse { e ->
            if (e is IllegalStateException) throw e
            // Graceful fallback if engine ever emits plain text
            CactusTranscribeOutput(text = trimmed, language = fallbackLang, elapsedMs = elapsedMs)
        }
    }
}
