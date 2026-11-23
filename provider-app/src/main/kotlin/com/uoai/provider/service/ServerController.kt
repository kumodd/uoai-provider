package com.uoai.provider.service

import com.uoai.clients.ClientRegistry
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceSettingsRepository
import com.uoai.plugins.PluginRegistry
import com.uoai.providerapi.KtorLocalHttpServer
import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide owner of the API server instance. The foreground service starts/stops it. */
@Singleton
class ServerController @Inject constructor(
    engine: InferenceEngine,
    registry: ClientRegistry,
    plugins: PluginRegistry,
    settingsRepo: InferenceSettingsRepository,
) {
    val server = KtorLocalHttpServer(
        inferenceEngine = engine,
        registry = registry,
        plugins = plugins,
        settingsProvider = { settingsRepo.settings.value },
    )
}
