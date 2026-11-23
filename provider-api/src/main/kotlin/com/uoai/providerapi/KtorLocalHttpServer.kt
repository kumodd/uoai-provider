package com.uoai.providerapi

import com.uoai.clients.ClientRegistry
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceSettings
import com.uoai.plugins.PluginRegistry
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Loopback-only Ktor server. The bind address is fixed to 127.0.0.1 and is deliberately not configurable. */
class KtorLocalHttpServer(
    private val inferenceEngine: InferenceEngine,
    private val registry: ClientRegistry,
    private val config: ApiConfig = ApiConfig(),
    private val plugins: PluginRegistry? = null,
    private val settingsProvider: () -> InferenceSettings = { InferenceSettings() },
) : LocalHttpServer {
    private val lock = Mutex()
    private var server: EmbeddedServer<*, *>? = null
    private val _running = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    val lastError: StateFlow<String?> = _error.asStateFlow()

    override suspend fun start() = lock.withLock {
        if (server != null) return@withLock
        withContext(Dispatchers.IO) {
            try {
                val s = embeddedServer(CIO, port = port, host = host) {
                    providerApi(inferenceEngine, registry, config, plugins, settingsProvider)
                }
                s.start(wait = false)
                server = s; _running.value = true; _error.value = null
            } catch (e: Exception) {
                _error.value = "Server failed to start: ${e.message}"; _running.value = false
            }
        }
    }

    override suspend fun stop() = lock.withLock {
        val s = server ?: return@withLock
        server = null
        withContext(Dispatchers.IO) { runCatching { s.stop(500, 2000) } }
        _running.value = false
    }
}
