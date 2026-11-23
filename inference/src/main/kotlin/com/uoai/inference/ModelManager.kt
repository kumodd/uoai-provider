package com.uoai.inference

import com.uoai.inference.runtime.InferenceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream

/**
 * Owns the lifecycle of the single shared model instance (FR-01).
 * All state transitions happen under [lock], which also prevents duplicate loads.
 */
class ModelManager(
    private val repository: ModelRepository,
    private val runtime: InferenceRuntime,
    private val downloader: ModelDownloader = ModelDownloader(),
    private val settingsRepository: InferenceSettingsRepository? = null,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    /**
     * The spec's state set has no "installed but not loaded" state, so that condition is represented as
     * NOT_INSTALLED with a non-null [ModelStatus.model]. Use [isInstalled] for the distinction.
     */
    private fun initialStatus() = ModelStatus(ModelState.NOT_INSTALLED, repository.installed())

    val isInstalled: Boolean get() = repository.installedFile() != null
    fun installedInfo(): ModelInfo? = repository.installed()
    fun listLocalModels(): List<ModelInfo> = repository.listLocalModels()

    private fun set(state: ModelState, error: String? = null, progress: Float? = null) =
        _status.update {
            it.copy(
                state = state,
                errorMessage = error,
                loadProgress = progress,
                model = repository.installed() ?: it.model,
                loadedConfig = runtime.loadedConfig,
            )
        }

    suspend fun downloadFromHuggingFace(source: HuggingFaceSource, token: String?, expectedSha256: String? = null) = lock.withLock {
        guardIdle()
        val target = File(repository.modelsDir(), source.fileName)
        try {
            set(ModelState.DOWNLOADING, progress = 0f)
            downloader.download(source, token, target) { d, t ->
                _status.update { it.copy(loadProgress = t?.let { total -> d.toFloat() / total }) }
            }
            finishInstall(target, source.fileName, expectedSha256)
        } catch (e: CancellationException) {
            set(ModelState.NOT_INSTALLED); throw e
        } catch (e: Exception) {
            set(ModelState.ERROR, e.message ?: "Download failed")
        }
    }

    suspend fun importModel(name: String, input: InputStream) = lock.withLock {
        guardIdle()
        val target = File(repository.modelsDir(), name)
        try {
            set(ModelState.DOWNLOADING, progress = null)
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            finishInstall(target, name, null)
        } catch (e: Exception) {
            target.delete(); set(ModelState.ERROR, e.message ?: "Import failed")
        }
    }

    private fun finishInstall(file: File, name: String, expectedSha: String?) {
        set(ModelState.VERIFYING)
        if (!ModelIntegrity.looksLikeModel(file)) { file.delete(); set(ModelState.ERROR, "File is not a valid model bundle"); return }
        if (expectedSha != null && !ModelIntegrity.sha256(file).equals(expectedSha, ignoreCase = true)) {
            file.delete(); set(ModelState.ERROR, "Checksum mismatch; file deleted"); return
        }
        val info = FileModelRepository.buildInfo(file)
        repository.save(info, file)
        set(ModelState.NOT_INSTALLED) // installed, awaiting load
    }

    /** Switches the active installed model (unloading the previous runtime first if loaded). */
    suspend fun selectModel(nameOrFile: String) = lock.withLock {
        guardIdle()
        if (runtime.isLoaded) {
            set(ModelState.UNLOADING)
            runCatching { runtime.unload() }.onFailure {
                set(ModelState.ERROR, "Unload failed: ${it.message}")
                return@withLock
            }
        }
        val selected = repository.selectInstalled(nameOrFile)
            ?: return@withLock set(ModelState.ERROR, "Model '$nameOrFile' not found in local storage")
        _status.value = ModelStatus(ModelState.NOT_INSTALLED, selected, loadedConfig = null)
    }

    suspend fun load(config: RuntimeLoadConfig? = null) = lock.withLock {
        if (runtime.isLoaded) return@withLock // never load twice
        loadInternal(config)
    }

    /** Unloads if loaded, then loads again using [config] or the latest saved settings. */
    suspend fun reload(config: RuntimeLoadConfig? = null) = lock.withLock {
        guardIdle()
        if (runtime.isLoaded) {
            set(ModelState.UNLOADING)
            runCatching { runtime.unload() }.onFailure {
                set(ModelState.ERROR, "Unload failed: ${it.message}")
                return@withLock
            }
        }
        loadInternal(config)
    }

    private suspend fun loadInternal(config: RuntimeLoadConfig?) {
        val file = repository.installedFile() ?: return set(ModelState.ERROR, "No model installed")
        val effective = config ?: settingsRepository?.settings?.value?.toLoadConfig() ?: RuntimeLoadConfig()
        try {
            set(ModelState.VERIFYING)
            if (!ModelIntegrity.looksLikeModel(file)) error("Model file missing or truncated")
            set(ModelState.LOADING, progress = null)
            runtime.load(file.absolutePath, effective)
            set(ModelState.READY)
        } catch (e: OutOfMemoryError) {
            set(ModelState.ERROR, "Not enough memory to load the model (${effective.contextWindowTokens} context tokens)")
        } catch (e: Throwable) {
            set(ModelState.ERROR, "Load failed: ${e.message}")
        }
    }

    suspend fun unload() = lock.withLock {
        if (!runtime.isLoaded) { set(ModelState.NOT_INSTALLED); return@withLock }
        set(ModelState.UNLOADING)
        runCatching { runtime.unload() }.onFailure { set(ModelState.ERROR, "Unload failed: ${it.message}"); return@withLock }
        set(ModelState.NOT_INSTALLED)
    }

    suspend fun delete() = lock.withLock {
        if (runtime.isLoaded) runtime.unload()
        repository.clear(); _status.value = ModelStatus()
    }

    /** Used by the engine to flip READY <-> GENERATING. */
    internal fun markGenerating(active: Boolean) =
        _status.update { if (it.state == ModelState.READY && active) it.copy(state = ModelState.GENERATING)
            else if (it.state == ModelState.GENERATING && !active) it.copy(state = ModelState.READY) else it }

    private fun guardIdle() {
        val s = _status.value.state
        check(s != ModelState.DOWNLOADING && s != ModelState.LOADING && s != ModelState.GENERATING) { "Busy: $s" }
    }
}
