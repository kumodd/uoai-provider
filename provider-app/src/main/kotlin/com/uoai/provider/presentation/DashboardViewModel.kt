package com.uoai.provider.presentation

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Debug
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uoai.clients.ClientRepository
import com.uoai.clients.ClientStatus
import com.uoai.core.common.DispatcherProvider
import com.uoai.core.security.SecretVault
import com.uoai.inference.AcceleratorBackend
import com.uoai.inference.ChatMessage
import com.uoai.inference.HuggingFaceSource
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceRequest
import com.uoai.inference.InferenceSettings
import com.uoai.inference.InferenceSettingsRepository
import com.uoai.inference.ModelInfo
import com.uoai.inference.ModelManager
import com.uoai.inference.ModelState
import com.uoai.inference.ModelStatus
import com.uoai.inference.RequestRecord
import com.uoai.inference.runtime.GenerationParams
import com.uoai.provider.service.ProviderService
import com.uoai.provider.service.ServerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Live memory snapshot for the provider process and device RAM. */
data class MemoryTelemetry(
    val jvmUsedMb: Long = 0,
    val nativeHeapMb: Long = 0,
    val deviceAvailMb: Long = 0,
    val deviceTotalMb: Long = 0,
    val availableStorageMb: Long = 0,
)

private data class LocalDashboardState(
    val hasToken: Boolean = false,
    val testOutput: String = "",
    val testRunning: Boolean = false,
    val testElapsedMs: Long? = null,
    val testChunks: Int = 0,
    val testTokensPerSec: Float? = null,
)

data class DashboardUiState(
    val apiServerRunning: Boolean = false,
    val model: ModelStatus = ModelStatus(),
    val modelInstalled: Boolean = false,
    val localModels: List<ModelInfo> = emptyList(),
    val settings: InferenceSettings = InferenceSettings(),
    val requiresReload: Boolean = false,
    val memory: MemoryTelemetry = MemoryTelemetry(),
    val hasToken: Boolean = false,
    val serverError: String? = null,
    val recent: List<RequestRecord> = emptyList(),
    val queued: Int = 0,
    val running: String? = null,
    val testOutput: String = "",
    val testRunning: Boolean = false,
    val testElapsedMs: Long? = null,
    val testChunks: Int = 0,
    val testTokensPerSec: Float? = null,
    val registeredClients: Int = 0,
    val pendingClients: Int = 0,
    val pendingRequests: Int = 0,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: ModelManager,
    private val engine: InferenceEngine,
    private val settingsRepo: InferenceSettingsRepository,
    private val vault: SecretVault,
    private val serverController: ServerController,
    private val clientRepo: ClientRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val local = MutableStateFlow(LocalDashboardState(hasToken = vault.get(TOKEN) != null))
    private var testJob: Job? = null
    private var testId: String? = null

    private val memoryFlow: Flow<MemoryTelemetry> = flow {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        while (currentCoroutineContext().isActive) {
            val rt = Runtime.getRuntime()
            val jvmUsed = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
            val nativeHeap = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            emit(
                MemoryTelemetry(
                    jvmUsedMb = jvmUsed,
                    nativeHeapMb = nativeHeap,
                    deviceAvailMb = info.availMem / BYTES_PER_MB,
                    deviceTotalMb = info.totalMem / BYTES_PER_MB,
                    availableStorageMb = context.filesDir.usableSpace / BYTES_PER_MB,
                ),
            )
            delay(2_000)
        }
    }

    val state: StateFlow<DashboardUiState> = combine(
        combine(local, manager.status, settingsRepo.settings) { l, m, cfg -> Triple(l, m, cfg) },
        combine(serverController.server.running, serverController.server.lastError, engine.queueState) { r, e, q ->
            Triple(r, e, q)
        },
        clientRepo.observeClients(),
        memoryFlow,
    ) { (l, m, cfg), (running, err, q), clients, mem ->
        val approvedCount = clients.count { it.status == ClientStatus.APPROVED }
        val pendingCount = clients.count { it.status == ClientStatus.PENDING }
        val needsReload = m.state == ModelState.READY && m.loadedConfig != null && m.loadedConfig != cfg.toLoadConfig()
        DashboardUiState(
            apiServerRunning = running,
            model = m,
            modelInstalled = manager.isInstalled,
            localModels = manager.listLocalModels(),
            settings = cfg,
            requiresReload = needsReload,
            memory = mem,
            hasToken = l.hasToken,
            serverError = err,
            recent = q.recent.take(10),
            queued = q.queued,
            running = q.runningRequestId,
            testOutput = l.testOutput,
            testRunning = l.testRunning,
            testElapsedMs = l.testElapsedMs,
            testChunks = l.testChunks,
            testTokensPerSec = l.testTokensPerSec,
            registeredClients = approvedCount,
            pendingClients = pendingCount,
            pendingRequests = q.queued,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun saveToken(token: String) {
        if (token.isBlank()) vault.remove(TOKEN) else vault.put(TOKEN, token.trim())
        local.update { it.copy(hasToken = token.isNotBlank()) }
    }

    fun clearToken() {
        vault.remove(TOKEN)
        local.update { it.copy(hasToken = false) }
    }

    fun updateContextWindow(tokens: Int) {
        settingsRepo.update { it.withContextWindow(tokens) }
    }

    fun updateMaxOutputTokens(tokens: Int) {
        settingsRepo.update { it.withMaxOutputTokens(tokens) }
    }

    fun updateTemperature(value: Float) {
        settingsRepo.update { it.copy(temperature = value) }
    }

    fun updateTopP(value: Float) {
        settingsRepo.update { it.copy(topP = value) }
    }

    fun updateTopK(value: Int) {
        settingsRepo.update { it.copy(topK = value) }
    }

    fun updateBackend(backend: AcceleratorBackend) {
        settingsRepo.update { it.copy(preferredBackend = backend) }
    }

    fun updateHuggingFaceSource(repo: String, fileName: String) {
        settingsRepo.update { it.copy(huggingFaceRepo = repo, huggingFaceFile = fileName) }
    }

    fun resetSettings() {
        settingsRepo.reset()
    }

    fun download() {
        val cfg = settingsRepo.settings.value
        viewModelScope.launch(dispatchers.io) {
            manager.downloadFromHuggingFace(
                HuggingFaceSource(repo = cfg.huggingFaceRepo, fileName = cfg.huggingFaceFile),
                vault.get(TOKEN),
            )
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "model.task"
            val input = context.contentResolver.openInputStream(uri) ?: return@launch
            manager.importModel(name, input)
        }
    }

    fun startServer() = ProviderService.start(context)
    fun stopServer() = ProviderService.stop(context)

    fun load() {
        val cfg = settingsRepo.settings.value.toLoadConfig()
        viewModelScope.launch(dispatchers.io) { manager.load(cfg) }
    }

    fun reloadModel() {
        val cfg = settingsRepo.settings.value.toLoadConfig()
        viewModelScope.launch(dispatchers.io) { manager.reload(cfg) }
    }

    fun unload() {
        viewModelScope.launch(dispatchers.io) { manager.unload() }
    }

    fun deleteModel() {
        viewModelScope.launch(dispatchers.io) { manager.delete() }
    }

    fun selectLocalModel(fileName: String) {
        viewModelScope.launch(dispatchers.io) { manager.selectModel(fileName) }
    }

    fun runTest(
        prompt: String,
        systemPrompt: String? = null,
        maxTokensOverride: Int? = null,
        temperatureOverride: Float? = null,
    ) {
        testJob?.cancel()
        val cfg = settingsRepo.settings.value
        val id = UUID.randomUUID().toString().also { testId = it }
        val messages = buildList {
            if (!systemPrompt.isNullOrBlank()) add(ChatMessage("system", systemPrompt.trim()))
            add(ChatMessage("user", prompt))
        }
        val params = GenerationParams(
            temperature = temperatureOverride ?: cfg.temperature,
            maxTokens = (maxTokensOverride ?: cfg.maxOutputTokens).coerceIn(1, InferenceSettings.MAX_TOKENS_32K),
            topP = cfg.topP,
            topK = cfg.topK,
        )
        val startMs = System.currentTimeMillis()
        local.update {
            it.copy(
                testOutput = "",
                testRunning = true,
                testElapsedMs = 0L,
                testChunks = 0,
                testTokensPerSec = null,
            )
        }
        testJob = viewModelScope.launch(dispatchers.io) {
            var chunks = 0
            val activeId = manager.installedInfo()?.id ?: "gemma-4"
            engine.stream(InferenceRequest(id, "dashboard", activeId, messages, params))
                .catch { e ->
                    local.update { it.copy(testOutput = it.testOutput + "\n[error] ${e.message}") }
                }
                .collect { token ->
                    chunks++
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val tps = chunks * 1000f / elapsed
                    local.update {
                        it.copy(
                            testOutput = it.testOutput + token,
                            testElapsedMs = elapsed,
                            testChunks = chunks,
                            testTokensPerSec = tps,
                        )
                    }
                }
            val finalElapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            local.update {
                it.copy(
                    testRunning = false,
                    testElapsedMs = finalElapsed,
                    testTokensPerSec = if (chunks > 0) chunks * 1000f / finalElapsed else it.testTokensPerSec,
                )
            }
        }
    }

    fun cancelTest() {
        testId?.let(engine::cancel)
        local.update { it.copy(testRunning = false) }
    }

    fun clearTest() {
        local.update {
            it.copy(
                testOutput = "",
                testElapsedMs = null,
                testChunks = 0,
                testTokensPerSec = null,
            )
        }
    }

    private companion object {
        const val TOKEN = "hf_token"
        const val BYTES_PER_MB = 1_048_576L
    }
}
