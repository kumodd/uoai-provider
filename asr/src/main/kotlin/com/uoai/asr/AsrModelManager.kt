package com.uoai.asr

import com.uoai.asr.runtime.AsrRuntime
import com.uoai.core.download.ResumableFileDownloader
import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.core.resources.LoadGuardResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class AsrInsufficientMemoryException(message: String) : Exception(message)

/**
 * Owns discovery, resumable download, integrity verification, installation, lazy loading,
 * profile switching, idle auto-unload, and memory-pressure eviction for the single shared ASR model.
 *
 * Strict invariant: at most ONE Whisper model (`Fast`, `Balanced`, or `High Accuracy`) is loaded
 * in native memory at any time.
 */
class AsrModelManager(
    private val repository: AsrModelRepository,
    private val runtime: AsrRuntime,
    private val settingsRepository: AsrSettingsRepository,
    private val scope: CoroutineScope,
    private val downloader: ResumableFileDownloader = ResumableFileDownloader(),
    private val resourceCoordinator: ComputeResourceCoordinator? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val verifySha256OnDownload: Boolean = true,
) {
    private val runtimeLock = Mutex()
    private val downloadLock = Mutex()
    @Volatile private var activeDownloadJob: Job? = null
    @Volatile private var idleUnloadJob: Job? = null

    private val _status = MutableStateFlow(buildInitialStatus())
    val status: StateFlow<AsrModelStatus> = _status.asStateFlow()

    init {
        resourceCoordinator?.registerTrimListener {
            scope.launch { unloadIfIdle() }
        }
    }

    private fun buildInitialStatus(): AsrModelStatus {
        val selected = settingsRepository.settings.value.selectedProfile
        val installed = repository.listInstalled()
        val initialState = if (installed.containsKey(selected)) AsrModelState.INSTALLED else AsrModelState.NOT_INSTALLED
        return AsrModelStatus(
            state = initialState,
            selectedProfile = selected,
            installedProfiles = installed,
        )
    }

    fun refreshInstalled() {
        val installed = repository.listInstalled()
        val selected = settingsRepository.settings.value.selectedProfile
        _status.update { cur ->
            val nextState = when {
                cur.state == AsrModelState.READY || cur.state == AsrModelState.TRANSCRIBING ||
                    cur.state == AsrModelState.DOWNLOADING || cur.state == AsrModelState.VERIFYING ||
                    cur.state == AsrModelState.LOADING || cur.state == AsrModelState.UNLOADING -> cur.state
                installed.containsKey(selected) -> AsrModelState.INSTALLED
                else -> AsrModelState.NOT_INSTALLED
            }
            cur.copy(state = nextState, selectedProfile = selected, installedProfiles = installed)
        }
    }

    /** Downloads and verifies a profile without unloading an already-loaded different profile. */
    suspend fun downloadProfile(profile: AsrProfile) = downloadLock.withLock {
        val preset = AsrModelCatalog.forProfile(profile)
        val target = repository.targetFile(preset)
        val previousState = _status.value.state
        val wasLoaded = runtime.isLoaded
        activeDownloadJob = currentCoroutineContext()[Job]

        try {
            _status.update {
                it.copy(
                    state = if (wasLoaded) it.state else AsrModelState.DOWNLOADING,
                    downloadingProfile = profile,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }
            downloader.download(
                urlString = preset.downloadUrl,
                target = target,
                bearerToken = null,
            ) { downloaded, total ->
                val p = total?.takeIf { it > 0 }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) }
                _status.update { it.copy(downloadProgress = p) }
            }

            _status.update {
                it.copy(
                    state = if (wasLoaded) it.state else AsrModelState.VERIFYING,
                    downloadProgress = 1f,
                )
            }

            val check = GgmlModelValidator.verifyFull(
                file = target,
                expectedSha256 = if (verifySha256OnDownload) preset.sha256 else null,
                expectedSizeBytes = if (verifySha256OnDownload) preset.exactSizeBytes else null,
            )
            if (!check.valid) {
                target.delete()
                _status.update {
                    it.copy(
                        state = if (wasLoaded) previousState else AsrModelState.ERROR,
                        downloadingProfile = null,
                        downloadProgress = null,
                        errorMessage = "Verification failed for ${preset.displayName}: ${check.reason}",
                    )
                }
                return@withLock
            }

            val entry = InstalledAsrModel(
                id = preset.id,
                profile = profile,
                fileName = preset.fileName,
                fileSizeBytes = target.length(),
                sha256 = preset.sha256,
                catalogVersion = AsrModelCatalog.CATALOG_VERSION,
                installedAt = clock(),
            )
            repository.recordInstalled(entry)
            val installed = repository.listInstalled()
            val selected = settingsRepository.settings.value.selectedProfile

            _status.update { cur ->
                val nextState = when {
                    runtime.isLoaded -> cur.state
                    installed.containsKey(selected) -> AsrModelState.INSTALLED
                    else -> AsrModelState.NOT_INSTALLED
                }
                cur.copy(
                    state = nextState,
                    downloadingProfile = null,
                    downloadProgress = null,
                    errorMessage = null,
                    installedProfiles = installed,
                )
            }
        } catch (e: CancellationException) {
            val installed = repository.listInstalled()
            val selected = settingsRepository.settings.value.selectedProfile
            _status.update { cur ->
                val nextState = when {
                    runtime.isLoaded -> cur.state
                    installed.containsKey(selected) -> AsrModelState.INSTALLED
                    else -> AsrModelState.NOT_INSTALLED
                }
                cur.copy(
                    state = nextState,
                    downloadingProfile = null,
                    downloadProgress = null,
                    installedProfiles = installed,
                )
            }
            throw e
        } catch (e: Exception) {
            _status.update { cur ->
                cur.copy(
                    state = if (runtime.isLoaded) cur.state else AsrModelState.ERROR,
                    downloadingProfile = null,
                    downloadProgress = null,
                    errorMessage = e.message ?: "Download failed for ${preset.displayName}",
                )
            }
        } finally {
            activeDownloadJob = null
        }
    }

    fun cancelDownload(): Boolean {
        val job = activeDownloadJob ?: return false
        job.cancel(CancellationException("Download cancelled by user"))
        return true
    }

    /** Installs a local GGML `.bin` file directly (used by unit tests or offline import). */
    suspend fun installLocalFile(profile: AsrProfile, sourceFile: File, verifySha256: Boolean = false) = runtimeLock.withLock {
        val preset = AsrModelCatalog.forProfile(profile)
        val target = repository.targetFile(preset)
        if (sourceFile.absolutePath != target.absolutePath) {
            target.parentFile?.mkdirs()
            sourceFile.copyTo(target, overwrite = true)
        }
        val check = GgmlModelValidator.verifyFull(
            file = target,
            expectedSha256 = if (verifySha256) preset.sha256 else null,
            expectedSizeBytes = if (verifySha256) preset.exactSizeBytes else null,
        )
        if (!check.valid) {
            target.delete()
            throw IllegalArgumentException("Invalid ASR model file: ${check.reason}")
        }
        repository.recordInstalled(
            InstalledAsrModel(
                id = preset.id,
                profile = profile,
                fileName = preset.fileName,
                fileSizeBytes = target.length(),
                sha256 = preset.sha256,
                installedAt = clock(),
            ),
        )
        refreshInstalled()
    }

    /**
     * Selects [profile] as the active ASR profile.
     * If a different profile is currently loaded, unloads it first and optionally loads [profile]
     * when it is installed and [loadIfWasLoaded] is true.
     */
    suspend fun selectProfile(profile: AsrProfile, loadIfWasLoaded: Boolean = true) = runtimeLock.withLock {
        val wasLoaded = runtime.isLoaded
        val prevLoaded = _status.value.loadedProfile
        guardNotTranscribing()

        settingsRepository.update { it.copy(selectedProfile = profile) }
        val installed = repository.listInstalled()

        if (wasLoaded && prevLoaded == profile) {
            _status.update { it.copy(selectedProfile = profile, installedProfiles = installed) }
            return@withLock
        }

        if (wasLoaded) {
            unloadInternal()
        }

        if (installed.containsKey(profile) && wasLoaded && loadIfWasLoaded) {
            loadInternal(profile)
        } else {
            _status.update {
                it.copy(
                    state = if (installed.containsKey(profile)) AsrModelState.INSTALLED else AsrModelState.NOT_INSTALLED,
                    selectedProfile = profile,
                    loadedProfile = null,
                    errorMessage = null,
                    installedProfiles = installed,
                )
            }
        }
    }

    /** Loads [profile] (or the currently selected profile). Unloads any other loaded profile first. */
    suspend fun load(profile: AsrProfile? = null): Long = runtimeLock.withLock {
        val targetProfile = profile ?: settingsRepository.settings.value.selectedProfile
        if (runtime.isLoaded && _status.value.loadedProfile == targetProfile) {
            resetIdleTimer()
            return@withLock 0L
        }
        guardNotTranscribing()
        if (profile != null && profile != settingsRepository.settings.value.selectedProfile) {
            settingsRepository.update { it.copy(selectedProfile = profile) }
        }
        if (runtime.isLoaded) {
            unloadInternal()
        }
        loadInternal(targetProfile)
    }

    /** Lazy-load entry point invoked by [DefaultAsrEngine] when `autoLoadOnRequest` is enabled. */
    suspend fun ensureLoaded(): Boolean {
        if (runtime.isLoaded && _status.value.loadedProfile == settingsRepository.settings.value.selectedProfile) {
            resetIdleTimer()
            return true
        }
        if (!settingsRepository.settings.value.autoLoadOnRequest) return false
        val selected = settingsRepository.settings.value.selectedProfile
        if (!repository.listInstalled().containsKey(selected)) return false
        load(selected)
        return runtime.isLoaded
    }

    suspend fun unload() = runtimeLock.withLock {
        guardNotTranscribing()
        unloadInternal()
    }

    /** Safe unload called by idle watchdog or Android `onTrimMemory` (skips if busy). */
    suspend fun unloadIfIdle() {
        if (!runtime.isLoaded) return
        if (_status.value.state == AsrModelState.TRANSCRIBING) return
        if (!runtimeLock.tryLock()) return
        try {
            if (runtime.isLoaded && _status.value.state == AsrModelState.READY) {
                unloadInternal()
            }
        } finally {
            runtimeLock.unlock()
        }
    }

    suspend fun deleteProfile(profile: AsrProfile) = runtimeLock.withLock {
        if (_status.value.loadedProfile == profile && runtime.isLoaded) {
            guardNotTranscribing()
            unloadInternal()
        }
        repository.delete(profile)
        val installed = repository.listInstalled()
        val selected = settingsRepository.settings.value.selectedProfile
        _status.update { cur ->
            val nextState = when {
                runtime.isLoaded -> cur.state
                installed.containsKey(selected) -> AsrModelState.INSTALLED
                else -> AsrModelState.NOT_INSTALLED
            }
            cur.copy(
                state = nextState,
                installedProfiles = installed,
                errorMessage = if (cur.state == AsrModelState.ERROR) null else cur.errorMessage,
            )
        }
    }

    internal fun markTranscribing(active: Boolean) {
        if (active) {
            idleUnloadJob?.cancel()
            idleUnloadJob = null
        } else {
            resetIdleTimer()
        }
        _status.update { cur ->
            when {
                active && cur.state == AsrModelState.READY -> cur.copy(state = AsrModelState.TRANSCRIBING)
                !active && cur.state == AsrModelState.TRANSCRIBING -> cur.copy(state = AsrModelState.READY)
                else -> cur
            }
        }
    }

    private suspend fun loadInternal(profile: AsrProfile): Long {
        val preset = AsrModelCatalog.forProfile(profile)
        val file = repository.fileFor(profile)
        if (file == null || !file.isFile) {
            _status.update {
                it.copy(
                    state = AsrModelState.NOT_INSTALLED,
                    selectedProfile = profile,
                    loadedProfile = null,
                    errorMessage = "${preset.displayName} is not downloaded yet.",
                )
            }
            throw IllegalStateException("${preset.displayName} is not installed")
        }

        resourceCoordinator?.let { coord ->
            val guard = coord.canLoadAsr(preset.minRequiredAvailRamMb)
            if (guard is LoadGuardResult.InsufficientMemory) {
                _status.update {
                    it.copy(
                        state = AsrModelState.ERROR,
                        selectedProfile = profile,
                        loadedProfile = null,
                        errorMessage = guard.message,
                    )
                }
                throw AsrInsufficientMemoryException(guard.message)
            }
        }

        val startMs = clock()
        try {
            _status.update {
                it.copy(
                    state = AsrModelState.VERIFYING,
                    selectedProfile = profile,
                    errorMessage = null,
                )
            }
            val header = GgmlModelValidator.validateHeader(file, minSizeBytes = 1_000_000L)
            if (!header.valid) {
                throw IllegalStateException("Corrupt model file (${header.reason}); please re-download.")
            }
            _status.update { it.copy(state = AsrModelState.LOADING) }
            runtime.load(file.absolutePath)
            val elapsed = (clock() - startMs).coerceAtLeast(1L)
            _status.update {
                it.copy(
                    state = AsrModelState.READY,
                    selectedProfile = profile,
                    loadedProfile = profile,
                    lastLoadTimeMs = elapsed,
                    errorMessage = null,
                    installedProfiles = repository.listInstalled(),
                )
            }
            resetIdleTimer()
            return elapsed
        } catch (e: OutOfMemoryError) {
            val msg = "Not enough memory to load ${preset.displayName} (~${preset.approxRamMb} MB RAM needed)"
            _status.update { it.copy(state = AsrModelState.ERROR, loadedProfile = null, errorMessage = msg) }
            throw AsrInsufficientMemoryException(msg)
        } catch (e: Throwable) {
            _status.update {
                it.copy(
                    state = AsrModelState.ERROR,
                    loadedProfile = null,
                    errorMessage = "Load failed: ${e.message ?: e::class.simpleName}",
                )
            }
            throw e
        }
    }

    private suspend fun unloadInternal() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        val selected = settingsRepository.settings.value.selectedProfile
        val installed = repository.listInstalled()
        if (!runtime.isLoaded) {
            _status.update {
                it.copy(
                    state = if (installed.containsKey(selected)) AsrModelState.INSTALLED else AsrModelState.NOT_INSTALLED,
                    loadedProfile = null,
                    installedProfiles = installed,
                )
            }
            return
        }
        _status.update { it.copy(state = AsrModelState.UNLOADING, errorMessage = null) }
        try {
            runtime.unload()
            _status.update {
                it.copy(
                    state = if (installed.containsKey(selected)) AsrModelState.INSTALLED else AsrModelState.NOT_INSTALLED,
                    loadedProfile = null,
                    installedProfiles = installed,
                )
            }
        } catch (e: Throwable) {
            _status.update {
                it.copy(
                    state = AsrModelState.ERROR,
                    errorMessage = "Unload failed: ${e.message}",
                )
            }
            throw e
        }
    }

    private fun resetIdleTimer() {
        idleUnloadJob?.cancel()
        val timeoutSec = settingsRepository.settings.value.idleUnloadTimeoutSec
        if (timeoutSec <= 0 || !runtime.isLoaded) return
        idleUnloadJob = scope.launch {
            delay(timeoutSec * 1000L)
            unloadIfIdle()
        }
    }

    private fun guardNotTranscribing() {
        check(_status.value.state != AsrModelState.TRANSCRIBING) {
            "ASR engine is currently transcribing; cancel or wait for active requests first"
        }
    }
}

