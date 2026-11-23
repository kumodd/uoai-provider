package com.uoai.provider.data

import com.uoai.core.database.InferenceRequestDao
import com.uoai.core.database.InferenceRequestEntity
import com.uoai.inference.InferenceRequest
import com.uoai.inference.RequestRecord
import com.uoai.inference.RequestRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Persists request lifecycle in order via a single writer; stores metadata only, never prompt text. */
class RoomRequestRecorder(private val dao: InferenceRequestDao, scope: CoroutineScope) : RequestRecorder {
    private val updates = Channel<Pair<RequestRecord, InferenceRequest>>(Channel.UNLIMITED)
    init {
        scope.launch(Dispatchers.IO) {
            for ((r, req) in updates) runCatching {
                dao.upsert(InferenceRequestEntity(
                    r.requestId, r.clientId, r.modelId, r.status.name,
                    """{"messages":${req.messages.size},"temperature":${req.params.temperature},"max_tokens":${req.params.maxTokens},"priority":${req.priority}}""",
                    r.createdAt, r.startedAt, r.completedAt, r.error,
                ))
            }
        }
    }
    override fun onUpdate(record: RequestRecord, request: InferenceRequest) { updates.trySend(record to request) }
}
