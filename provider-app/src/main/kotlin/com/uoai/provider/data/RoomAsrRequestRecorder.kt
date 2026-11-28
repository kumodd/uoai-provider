package com.uoai.provider.data

import com.uoai.asr.AsrRequestRecord
import com.uoai.asr.AsrRequestRecorder
import com.uoai.core.database.InferenceRequestDao
import com.uoai.core.database.InferenceRequestEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Persists ASR request lifecycle metadata into the shared `inference_requests` table.
 * Deliberately records metadata only (audio duration, size, language) — never audio bytes or transcript text.
 */
class RoomAsrRequestRecorder(
    private val dao: InferenceRequestDao,
    scope: CoroutineScope,
) : AsrRequestRecorder {
    private val updates = Channel<AsrRequestRecord>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (r in updates) runCatching {
                val lang = r.language?.replace("\"", "") ?: "auto"
                val dur = String.format(Locale.US, "%.2f", r.durationSec)
                dao.upsert(
                    InferenceRequestEntity(
                        id = r.requestId,
                        clientId = r.clientId,
                        modelId = r.modelId,
                        requestStatus = r.status.name,
                        requestJson = """{"type":"asr","duration_sec":$dur,"source_bytes":${r.sourceBytes},"language":"$lang"}""",
                        createdAt = r.createdAt,
                        startedAt = r.startedAt,
                        completedAt = r.completedAt,
                        errorMessage = r.error,
                    ),
                )
            }
        }
    }

    override fun onUpdate(record: AsrRequestRecord) {
        updates.trySend(record)
    }
}

