package com.uoai.asr

import com.uoai.asr.audio.DecodedAudio
import com.uoai.asr.runtime.AsrSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class AsrRequest(
    val requestId: String,
    val clientId: String,
    val requestedModel: String? = null,
    val audio: DecodedAudio,
    val language: String? = null,
    val translate: Boolean = false,
    val prompt: String? = null,
    val temperature: Float = 0.0f,
    val timestamps: Boolean = true,
    val singleSegment: Boolean = false,
    /** Higher priority runs first; equal priority uses FIFO with anti-starvation age boost. */
    val priority: Int = 0,
    val sourceBytes: Long = audio.samples.size * 4L,
)

data class AsrResult(
    val requestId: String,
    val modelId: String,
    val profile: AsrProfile,
    val text: String,
    val language: String,
    val durationSec: Double,
    val segments: List<AsrSegment>,
    val elapsedMs: Long,
    val languageConfidence: Float = 0f,
)

sealed interface AsrStreamEvent {
    data class Started(val requestId: String, val modelId: String, val language: String) : AsrStreamEvent
    data class Segment(val requestId: String, val segment: AsrSegment, val language: String) : AsrStreamEvent
    data class Completed(val result: AsrResult) : AsrStreamEvent
}

enum class AsrRequestStatus { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED }

data class AsrRequestRecord(
    val requestId: String,
    val clientId: String,
    val modelId: String,
    val status: AsrRequestStatus,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val durationSec: Double,
    val sourceBytes: Long,
    val language: String?,
    val error: String? = null,
)

data class AsrQueueSnapshot(
    val queued: Int = 0,
    val runningRequestId: String? = null,
    val recent: List<AsrRequestRecord> = emptyList(),
)

class AsrRequestCancelledException(val requestId: String) : Exception("ASR request $requestId was cancelled")
class AsrModelNotReadyException(message: String) : Exception(message)
class AsrModelMismatchException(message: String) : Exception(message)
class AsrQueueFullException(message: String) : Exception(message)
class AsrThermalThrottledException(message: String) : Exception(message)

/** Metadata-only persistence hook (never receives audio bytes or transcript text). */
fun interface AsrRequestRecorder {
    fun onUpdate(record: AsrRequestRecord)
    companion object { val None = AsrRequestRecorder { } }
}

/** Single entry point used by `:provider-api` and Playground UI. */
interface AsrEngine {
    val modelStatus: StateFlow<AsrModelStatus>
    val queueState: StateFlow<AsrQueueSnapshot>
    fun stream(request: AsrRequest): Flow<AsrStreamEvent>
    suspend fun transcribe(request: AsrRequest): AsrResult
    fun cancel(requestId: String): Boolean
    fun cancelAllForClient(clientId: String): Int
}

