package com.uoai.providerapi.external

import com.uoai.asr.AsrModelCatalog
import com.uoai.asr.AsrSegment
import com.uoai.asr.AsrStreamEvent
import com.uoai.asr.audio.DecodedAudio
import com.uoai.asr.audio.LiveAudioChunker
import com.uoai.asr.audio.TranscriptContinuity
import com.uoai.asr.audio.WavPcmDecoder
import com.uoai.inference.ChatMessage
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceRequest
import com.uoai.inference.InferenceSettings
import com.uoai.inference.ModelNotReadyException
import com.uoai.inference.ModelState
import com.uoai.inference.runtime.GenerationParams
import com.uoai.providerapi.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class Gemma4AudioResult(
    val requestId: String,
    val modelId: String,
    val text: String,
    val language: String,
    val durationSec: Double,
    val segments: List<AsrSegment>,
    val elapsedMs: Long,
)

/**
 * Exposes UOAI Provider's proven direct multimodal Gemma 4 audio transcription workflow
 * (`InferenceEngine` + `InferenceRequest.audioWavBytes`) to external API callers without
 * modifying the existing in-app workflow or existing dedicated ASR models.
 */
object Gemma4AudioTranscriber {
    private const val MAX_CHUNK_SEC = 15

    fun isReady(engine: InferenceEngine): Boolean {
        val st = engine.modelStatus.value
        return (st.state == ModelState.READY || st.state == ModelState.GENERATING) && st.supportsAudioInput
    }

    fun requireAudioReady(engine: InferenceEngine): String {
        val st = engine.modelStatus.value
        if (st.state != ModelState.READY && st.state != ModelState.GENERATING) {
            throw ModelNotReadyException(
                "Gemma 4 model is not loaded (state=${st.state}). Load a multimodal Gemma 4 E2B/E4B (.litertlm) model in UOAI Provider.",
            )
        }
        if (!st.supportsAudioInput) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "invalid_request_error",
                "gemma4_audio_unsupported",
                "Active Gemma 4 model '${st.model?.name ?: "current"}' has no active audio encoder. Load a multimodal Gemma 4 E2B or E4B (.litertlm) model.",
            )
        }
        return st.model?.id ?: "gemma-4"
    }

    suspend fun transcribe(
        engine: InferenceEngine,
        requestId: String,
        clientId: String,
        decoded: DecodedAudio,
        language: String?,
        prompt: String?,
        temperature: Float,
        translate: Boolean,
        priority: Int,
        settings: InferenceSettings,
    ): Gemma4AudioResult {
        val activeModelId = requireAudioReady(engine)
        val startedAt = System.currentTimeMillis()
        val normLang = AsrModelCatalog.normalizeLanguageSpec(language ?: "auto")

        val chunks = splitIntoChunks(decoded)
        val segments = ArrayList<AsrSegment>(chunks.size)
        val combined = StringBuilder()
        var cursorSec = 0.0

        chunks.forEachIndexed { idx, chunk ->
            val wav = WavPcmDecoder.encodeWavPcm16(chunk.samples, chunk.sampleRate)
            val priorTail = combined.toString().takeLast(120).trim().takeIf { it.isNotEmpty() }
            val messages = buildMessages(normLang, prompt, priorTail, translate)
            val params = GenerationParams(
                temperature = temperature.coerceIn(0f, 0.35f),
                maxTokens = minOf(settings.maxOutputTokens, 1024).coerceIn(128, InferenceSettings.MAX_TOKENS_32K),
                topP = settings.topP,
                topK = settings.topK,
            )
            val subId = if (chunks.size == 1) requestId else "$requestId-c$idx"
            val raw = engine.generate(
                InferenceRequest(
                    requestId = subId,
                    clientId = clientId,
                    modelId = activeModelId,
                    messages = messages,
                    params = params,
                    priority = priority,
                    audioWavBytes = wav,
                ),
            ).trim()

            val clean = TranscriptContinuity.prepareForAppend(combined.toString().takeIf { it.isNotBlank() }, raw)
            val segStart = cursorSec
            val segEnd = (cursorSec + chunk.durationSec).coerceAtMost(decoded.durationSec)
            cursorSec = segEnd
            if (clean.isNotBlank()) {
                if (combined.isNotEmpty()) combined.append(' ')
                combined.append(clean)
                segments += AsrSegment(
                    id = segments.size,
                    startSec = segStart,
                    endSec = segEnd,
                    text = clean,
                )
            }
        }

        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        return Gemma4AudioResult(
            requestId = requestId,
            modelId = activeModelId,
            text = combined.toString().trim(),
            language = normLang,
            durationSec = decoded.durationSec,
            segments = segments,
            elapsedMs = elapsed,
        )
    }

    fun transcribeStream(
        engine: InferenceEngine,
        requestId: String,
        clientId: String,
        decoded: DecodedAudio,
        language: String?,
        prompt: String?,
        temperature: Float,
        translate: Boolean,
        priority: Int,
        settings: InferenceSettings,
    ): Flow<AsrStreamEvent> = flow {
        val activeModelId = requireAudioReady(engine)
        val startedAt = System.currentTimeMillis()
        val normLang = AsrModelCatalog.normalizeLanguageSpec(language ?: "auto")

        emit(AsrStreamEvent.Started(requestId, activeModelId, normLang))

        val chunks = splitIntoChunks(decoded)
        val segments = ArrayList<AsrSegment>(chunks.size)
        val combined = StringBuilder()
        var cursorSec = 0.0

        chunks.forEachIndexed { idx, chunk ->
            val wav = WavPcmDecoder.encodeWavPcm16(chunk.samples, chunk.sampleRate)
            val priorTail = combined.toString().takeLast(120).trim().takeIf { it.isNotEmpty() }
            val messages = buildMessages(normLang, prompt, priorTail, translate)
            val params = GenerationParams(
                temperature = temperature.coerceIn(0f, 0.35f),
                maxTokens = minOf(settings.maxOutputTokens, 1024).coerceIn(128, InferenceSettings.MAX_TOKENS_32K),
                topP = settings.topP,
                topK = settings.topK,
            )
            val subId = if (chunks.size == 1) requestId else "$requestId-c$idx"
            val chunkText = StringBuilder()
            engine.stream(
                InferenceRequest(
                    requestId = subId,
                    clientId = clientId,
                    modelId = activeModelId,
                    messages = messages,
                    params = params,
                    priority = priority,
                    audioWavBytes = wav,
                ),
            ).collect { token ->
                chunkText.append(token)
            }

            val clean = TranscriptContinuity.prepareForAppend(
                combined.toString().takeIf { it.isNotBlank() },
                chunkText.toString().trim(),
            )
            val segStart = cursorSec
            val segEnd = (cursorSec + chunk.durationSec).coerceAtMost(decoded.durationSec)
            cursorSec = segEnd
            if (clean.isNotBlank()) {
                if (combined.isNotEmpty()) combined.append(' ')
                combined.append(clean)
                val seg = AsrSegment(
                    id = segments.size,
                    startSec = segStart,
                    endSec = segEnd,
                    text = clean,
                )
                segments += seg
                emit(AsrStreamEvent.Segment(requestId, seg, normLang))
            }
        }

        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        emit(
            AsrStreamEvent.Completed(
                com.uoai.asr.AsrResult(
                    requestId = requestId,
                    modelId = activeModelId,
                    profile = com.uoai.asr.AsrProfile.BALANCED,
                    text = combined.toString().trim(),
                    language = normLang,
                    durationSec = decoded.durationSec,
                    segments = segments,
                    elapsedMs = elapsed,
                ),
            ),
        )
    }

    internal fun buildSystemInstruction(languageHint: String?, translate: Boolean): String {
        val norm = AsrModelCatalog.normalizeLanguageSpec(languageHint ?: "auto")
        val langDirective = when {
            translate -> "Translate the spoken speech accurately into natural English."
            norm == "auto" -> "Auto-detect the speaker's exact language, regional dialect, and code-switching directly from the audio."
            norm == "en,hi" -> "Allowed languages ONLY: English and Hindi / Hinglish. Match the speaker's exact dialect and script (Devanagari for Hindi, Latin for English/Hinglish); NEVER output Urdu, Chinese, or unrelated scripts."
            norm == "en" -> "Primary language: English (keep the speaker's exact regional dialect/expressions)."
            norm == "hi" -> "Primary language: Hindi / Hinglish (preserve Devanagari or Hinglish matching the speaker; do not switch to Urdu or unrelated scripts)."
            else -> "Primary language: ${AsrModelCatalog.displayNameForLanguage(norm)} ($norm). Transcribe strictly in this language and natural script."
        }
        val taskLine = if (translate) {
            "Translate the spoken audio in this clip into natural English — NEVER summarize, answer, or add commentary."
        } else {
            "Transcribe ONLY the spoken audio in this clip verbatim with clean punctuation — NEVER summarize, answer, comment on, or translate the speech."
        }
        return """
            You are a precise multilingual automatic speech recognition (ASR) engine.
            $taskLine

            Rules:
            1. Understand the speaker's exact language, regional dialect, accent, and code-switching (e.g. Hinglish, conversational Hindi, Bhojpuri, Marathi, Punjabi, Tamil, Indian English, US/UK English, or mixed dialects).
            2. Write the transcript strictly in that SAME detected dialect, natural vocabulary, and natural script.
            3. Keep 100% of spoken words, names, and numbers intact with clean punctuation.
            4. If the clip contains only silence or noise with no intelligible speech, output nothing.
            5. Output ONLY the transcript text — no labels, no timestamps, no explanations.
            $langDirective
        """.trimIndent()
    }

    internal fun buildUserPrompt(userHint: String?, translate: Boolean, priorContext: String? = null): String =
        buildString {
            if (!priorContext.isNullOrBlank()) {
                append("Previous chunk ended with (context only — DO NOT repeat it): \"$priorContext\"\n")
            }
            if (!userHint.isNullOrBlank()) {
                append("Domain / vocabulary hint: ${userHint.trim()}\n")
            }
            if (translate) {
                append("Translate this audio clip into English.")
            } else {
                append("Transcribe this audio clip verbatim in the speaker's exact language and dialect.")
            }
        }

    private fun buildMessages(
        normLang: String,
        userHint: String?,
        priorContext: String?,
        translate: Boolean,
    ): List<ChatMessage> = listOf(
        ChatMessage("system", buildSystemInstruction(normLang, translate)),
        ChatMessage("user", buildUserPrompt(userHint, translate, priorContext)),
    )

    private fun splitIntoChunks(decoded: DecodedAudio): List<com.uoai.asr.audio.AudioChunk> {
        val chunked = LiveAudioChunker.splitDecodedAudio(
            decoded = decoded,
            maxChunkSec = MAX_CHUNK_SEC,
            minChunkSec = 2,
        )
        if (chunked.isNotEmpty()) return chunked
        // Even if clip is quiet/short, send the single clip to Gemma 4 so short audio clips work cleanly.
        return listOf(
            com.uoai.asr.audio.AudioChunk(
                sequenceId = 0,
                samples = decoded.samples,
                sampleRate = decoded.sampleRate,
                durationSec = decoded.durationSec,
            ),
        )
    }
}
