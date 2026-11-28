package com.uoai.asr.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Production `whisper.cpp` runtime adapter owning one `whisper_context*` (mmap-backed weights)
 * and one reusable `whisper_state*` serialized strictly via [nativeMutex].
 *
 * Cooperative cancellation hooks into `whisper_full_params.abort_callback` and
 * `encoder_begin_callback` so cancelling a request exits native execution rapidly without leaks.
 */
class WhisperCppRuntimeAdapter : AsrRuntime {
    private val nativeMutex = Mutex()
    private val ctxPtr = AtomicLong(0L)
    private val statePtr = AtomicLong(0L)

    @Volatile
    override var loadedModelPath: String? = null
        private set

    override val isLoaded: Boolean
        get() = ctxPtr.get() != 0L && statePtr.get() != 0L

    override suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        nativeMutex.withLock {
            check(ctxPtr.get() == 0L) { "ASR model already loaded" }
            val file = File(modelPath)
            require(file.isFile) { "ASR model file not found: $modelPath" }

            WhisperNative.ensureLibraryLoaded()
            val ctx = WhisperNative.nativeInitContext(file.absolutePath)
            if (ctx == 0L) {
                throw IllegalStateException("whisper.cpp failed to initialize model '${file.name}'")
            }
            val state = WhisperNative.nativeInitState(ctx)
            if (state == 0L) {
                WhisperNative.nativeFreeContext(ctx)
                throw OutOfMemoryError("whisper.cpp failed to allocate inference state for '${file.name}'")
            }
            ctxPtr.set(ctx)
            statePtr.set(state)
            loadedModelPath = file.absolutePath
        }
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        nativeMutex.withLock {
            val s = statePtr.getAndSet(0L)
            val c = ctxPtr.getAndSet(0L)
            loadedModelPath = null
            if (s != 0L) WhisperNative.nativeFreeState(s)
            if (c != 0L) WhisperNative.nativeFreeContext(c)
        }
    }

    override suspend fun transcribe(
        pcm16kMono: FloatArray,
        params: AsrDecodeParams,
        abortSignal: AsrAbortSignal,
        onSegment: ((AsrSegment) -> Unit)?,
    ): AsrTranscriptionOutput = withContext(Dispatchers.IO) {
        if (abortSignal.isAborted) throw CancellationException("ASR request aborted before start")
        nativeMutex.withLock {
            val ctx = ctxPtr.get()
            val state = statePtr.get()
            check(ctx != 0L && state != 0L) { "ASR model is not loaded" }

            val nativeAbortPtr = WhisperNative.nativeCreateAbortToken()
            if (nativeAbortPtr == 0L) throw IllegalStateException("Failed to allocate native abort token")

            // Bridge Kotlin abort signal to native atomic flag (poll-free when wrapped by DefaultAsrEngine,
            // plus immediate check before entering native code).
            val bridgedAbort = if (abortSignal is NativeBridgeableAbortSignal) {
                abortSignal.attachNativeToken(nativeAbortPtr)
                true
            } else {
                false
            }
            if (abortSignal.isAborted) {
                WhisperNative.nativeSetAbort(nativeAbortPtr)
            }

            try {
                val listener = onSegment?.let { cb ->
                    object : NativeSegmentListener {
                        override fun onSegment(index: Int, startMs: Long, endMs: Long, text: String, noSpeechProb: Float) {
                            val cleaned = TranscriptSanitizer.cleanSegment(text, noSpeechProb)
                            if (cleaned.isNotEmpty()) {
                                cb(
                                    AsrSegment(
                                        id = index,
                                        startSec = startMs / 1000.0,
                                        endSec = endMs / 1000.0,
                                        text = cleaned,
                                        noSpeechProb = noSpeechProb,
                                    ),
                                )
                            }
                        }
                    }
                }

                val rc = WhisperNative.nativeFullWithState(
                    ctxPtr = ctx,
                    statePtr = state,
                    audioSamples = pcm16kMono,
                    language = params.language,
                    translate = params.translate,
                    initialPrompt = params.initialPrompt,
                    nThreads = params.threads,
                    temperature = params.temperature,
                    noTimestamps = !params.timestamps,
                    singleSegment = params.singleSegment,
                    abortTokenPtr = nativeAbortPtr,
                    segmentListener = listener,
                )

                if (rc == WhisperNative.RC_ABORTED || abortSignal.isAborted) {
                    throw CancellationException("ASR transcription cancelled")
                }
                if (rc != 0) {
                    throw IllegalStateException("whisper.cpp transcription failed with code $rc")
                }

                val count = WhisperNative.nativeSegmentCount(state)
                val segments = ArrayList<AsrSegment>(count)
                var prevNorm = ""
                for (i in 0 until count) {
                    val rawText = WhisperNative.nativeSegmentText(state, i)
                    val noSpeech = WhisperNative.nativeSegmentNoSpeechProb(state, i)
                    val segText = TranscriptSanitizer.cleanSegment(rawText, noSpeech)
                    if (segText.isEmpty()) continue
                    val norm = segText.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
                    if (norm.isNotEmpty() && norm == prevNorm) continue
                    prevNorm = norm
                    val startMs = WhisperNative.nativeSegmentStartMs(state, i)
                    val endMs = WhisperNative.nativeSegmentEndMs(state, i)
                    segments += AsrSegment(
                        id = segments.size,
                        startSec = startMs / 1000.0,
                        endSec = endMs / 1000.0,
                        text = segText,
                        noSpeechProb = noSpeech,
                    )
                }

                val cleanFullText = TranscriptSanitizer.cleanJoinedSegments(segments)
                val fallbackLang = params.language
                    .substringBefore(',')
                    .trim()
                    .takeIf { it.isNotEmpty() && it != "auto" } ?: "en"
                val detectedLang = WhisperNative.nativeDetectedLanguage(state).ifBlank { fallbackLang }
                val langProb = WhisperNative.nativeDetectedLanguageProb(state).coerceIn(0f, 1f)

                AsrTranscriptionOutput(
                    text = cleanFullText,
                    language = detectedLang,
                    segments = segments,
                    languageConfidence = langProb,
                )
            } finally {
                if (bridgedAbort && abortSignal is NativeBridgeableAbortSignal) {
                    abortSignal.detachNativeToken()
                }
                WhisperNative.nativeFreeAbortToken(nativeAbortPtr)
            }
        }
    }
}

/**
 * Abort signal that forwards cancellation instantaneously to an attached native `AbortToken*`
 * while guaranteeing safe pointer lifetime via synchronization.
 */
class NativeBridgeableAbortSignal : AsrAbortSignal {
    private val lock = Any()
    @Volatile private var aborted = false
    private var nativePtr: Long = 0L

    override val isAborted: Boolean get() = aborted

    fun attachNativeToken(ptr: Long) = synchronized(lock) {
        nativePtr = ptr
        if (aborted && ptr != 0L) {
            WhisperNative.nativeSetAbort(ptr)
        }
    }

    fun detachNativeToken() = synchronized(lock) {
        nativePtr = 0L
    }

    override fun abort() = synchronized(lock) {
        aborted = true
        if (nativePtr != 0L) {
            WhisperNative.nativeSetAbort(nativePtr)
        }
    }
}

