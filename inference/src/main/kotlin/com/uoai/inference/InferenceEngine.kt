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
)

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
