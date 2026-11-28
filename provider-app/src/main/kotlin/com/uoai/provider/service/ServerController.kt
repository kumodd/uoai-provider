package com.uoai.provider.service

import com.uoai.asr.AsrEngine
import com.uoai.asr.AsrModelManager
import com.uoai.asr.AsrSettingsRepository
import com.uoai.asr.audio.AudioDecoder
import com.uoai.asr.engines.HinglishModelManager
import com.uoai.asr.engines.SpeechEngineRegistry
import com.uoai.clients.ClientRegistry
import com.uoai.core.database.AppDatabase
import com.uoai.core.database.AuditLogEntity
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceSettingsRepository
import com.uoai.plugins.PluginRegistry
import com.uoai.providerapi.AsrTempFileStore
import com.uoai.providerapi.KtorLocalHttpServer
import com.uoai.providerapi.external.ExternalAudioProviderClient
import com.uoai.providerapi.external.ExternalAudioProviderRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide owner of the API server instance. The foreground service starts/stops it. */
@Singleton
class ServerController @Inject constructor(
    engine: InferenceEngine,
    registry: ClientRegistry,
    plugins: PluginRegistry,
    settingsRepo: InferenceSettingsRepository,
    asrEngine: AsrEngine,
    asrModelManager: AsrModelManager,
    asrSettingsRepo: AsrSettingsRepository,
    audioDecoder: AudioDecoder,
    asrTempStore: AsrTempFileStore,
    speechEngineRegistry: SpeechEngineRegistry,
    hinglishModelManager: HinglishModelManager,
    externalAudioProviderRegistry: ExternalAudioProviderRegistry,
    externalAudioProviderClient: ExternalAudioProviderClient,
    db: AppDatabase,
) {
    val server = KtorLocalHttpServer(
        inferenceEngine = engine,
        registry = registry,
        plugins = plugins,
        settingsProvider = { settingsRepo.settings.value },
        asrEngine = asrEngine,
        asrModelManager = asrModelManager,
        asrSettingsProvider = { asrSettingsRepo.settings.value },
        audioDecoder = audioDecoder,
        asrTempStore = asrTempStore,
        speechEngineRegistry = speechEngineRegistry,
        hinglishModelManager = hinglishModelManager,
        externalProviderRegistry = externalAudioProviderRegistry,
        externalProviderClient = externalAudioProviderClient,
        asrAuditLogger = { clientId, action, resource, status, details ->
            runCatching {
                db.auditDao().insert(
                    AuditLogEntity(
                        clientId = clientId,
                        action = action,
                        resource = resource,
                        status = status,
                        detailsJson = details?.let { """{"detail":"${it.replace("\"", "'")}"}""" },
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        },
    )
}
