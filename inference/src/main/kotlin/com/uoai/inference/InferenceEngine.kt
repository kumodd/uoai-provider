package com.uoai.inference

import com.uoai.inference.runtime.GenerationParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class ChatMessage(val role: String, val content: String)

data class InferenceRequest(
    val requestId: String,
    val clientId: String,
    val modelId: String,
    val messages: List<ChatMessage>,
    val params: GenerationParams = GenerationParams(),
    /** Higher runs first; equal priorities are FIFO. */
    val priority: Int = 0,
    /** Optional 16 kHz mono WAV audio clip attached to the final user turn (multimodal Gemma 4). */
    val audioWavBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InferenceRequest) return false
        return requestId == other.requestId &&
            clientId == other.clientId &&
            modelId == other.modelId &&
            messages == other.messages &&
            params == other.params &&
            priority == other.priority &&
            audioWavBytes.contentEquals(other.audioWavBytes)
    }

    override fun hashCode(): Int {
        var result = requestId.hashCode()
        result = 31 * result + clientId.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + messages.hashCode()
        result = 31 * result + params.hashCode()
        result = 31 * result + priority
        result = 31 * result + (audioWavBytes?.contentHashCode() ?: 0)
        return result
    }
}

enum class RequestStatus { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED }

data class RequestRecord(
    val requestId: String,
    val clientId: String,
    val modelId: String,
    val status: RequestStatus,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val error: String? = null,
)

data class QueueSnapshot(
    val queued: Int = 0,
    val runningRequestId: String? = null,
    val recent: List<RequestRecord> = emptyList(),
)

class RequestCancelledException(val requestId: String) : Exception("Request $requestId was cancelled")
class ModelNotReadyException(message: String) : Exception(message)
class QueueFullException(message: String) : Exception(message)

/** Receives every status change so the app can persist it (inference never touches Room). */
fun interface RequestRecorder {
    fun onUpdate(record: RequestRecord, request: InferenceRequest)
    companion object { val None = RequestRecorder { _, _ -> } }
}

/** Single entry point used by the API layer. */
interface InferenceEngine {
    val modelStatus: StateFlow<ModelStatus>
    val queueState: StateFlow<QueueSnapshot>
    fun stream(request: InferenceRequest): Flow<String>
    suspend fun generate(request: InferenceRequest): String
    fun cancel(requestId: String): Boolean
}
