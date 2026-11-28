package com.uoai.asr.engines

import com.uoai.asr.runtime.CactusSpeechBackend
import com.uoai.core.download.ResumableFileDownloader
import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.core.resources.LoadGuardResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class InstalledHinglishModel(
    val id: String,
    val kind: AsrEngineKind,
    val fileName: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val catalogVersion: Int = HinglishModelCatalog.CATALOG_VERSION,
    val installedAt: Long,
)

data class HinglishModelsStatus(
    val installed: Map<AsrEngineKind, InstalledHinglishModel> = emptyMap(),
    val loadedKind: AsrEngineKind? = null,
    val loadingKind: AsrEngineKind? = null,
    val downloadingKind: AsrEngineKind? = null,
    val downloadProgress: Float? = null,
    val verifyingKind: AsrEngineKind? = null,
    val lastLoadTimeMs: Map<AsrEngineKind, Long> = emptyMap(),
    val errorMessage: String? = null,
) {
    fun isInstalled(kind: AsrEngineKind): Boolean = installed.containsKey(kind)
    fun isLoaded(kind: AsrEngineKind): Boolean = loadedKind == kind
    fun totalStorageBytes(): Long = installed.values.sumOf { it.fileSizeBytes }
}

/**
 * Manages app-private storage (`filesDir/asr_models/hinglish/`), resumable downloading with
 * free-space checks and SHA-256 verification, header validation, lazy Cactus loading, and deletion
 * for AksharSTT v4, AksharSTT Lite v4, and Shruti `.cact` models.
 */
class HinglishModelManager(
    private val modelsDir: File,
    private val cactusBackend: CactusSpeechBackend,
    private val scope: CoroutineScope,
    private val downloader: ResumableFileDownloader = ResumableFileDownloader(),
    private val resourceCoordinator: ComputeResourceCoordinator? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val verifySha256OnDownload: Boolean = true,
) {
    @Serializable
    private data class Manifest(
        val catalogVersion: Int = HinglishModelCatalog.CATALOG_VERSION,
        val models: Map<String, InstalledHinglishModel> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val manifestFile get() = File(modelsDir, "hinglish_manifest.json")
    private val downloadLock = Mutex()
    private val runtimeLock = Mutex()
    @Volatile private var activeDownloadJob: Job? = null

    private val _status = MutableStateFlow(HinglishModelsStatus(installed = discoverInstalled()))
    val status: StateFlow<HinglishModelsStatus> = _status.asStateFlow()

    init {
        modelsDir.mkdirs()
        resourceCoordinator?.registerTrimListener {
            scope.launch { resetActiveIfIdle() }
        }
    }

    fun targetFile(spec: HinglishModelSpec): File = File(modelsDir, spec.fileName)

    fun fileFor(kind: AsrEngineKind): File? {
        val spec = HinglishModelCatalog.specFor(kind) ?: return null
        val file = targetFile(spec)
        return file.takeIf { it.isFile && CactModelValidator.validateHeader(it).valid }
    }

    fun isInstalled(kind: AsrEngineKind): Boolean = fileFor(kind) != null

    fun refreshInstalled() {
        val discovered = discoverInstalled()
        _status.update { it.copy(installed = discovered) }
    }

    /**
     * Downloads and verifies a Hinglish `.cact` model.
     * - Skips download if an identical valid model is already installed.
     * - Checks free disk space (`exactSizeBytes + 50 MB`) before starting.
     * - Supports interrupted download recovery via HTTP Range `.part` files.
     * - Validates `.cact` magic header + exact SHA-256 digest before registration.
     */
    suspend fun download(kind: AsrEngineKind) = downloadLock.withLock {
        val spec = HinglishModelCatalog.specFor(kind)
            ?: throw IllegalArgumentException("$kind is not a downloadable Cactus Hinglish model")
        val target = targetFile(spec)

        // Avoid re-downloading an already-installed valid model
        if (target.isFile && CactModelValidator.validateHeader(target, minSizeBytes = spec.exactSizeBytes).valid &&
            target.length() == spec.exactSizeBytes
        ) {
            recordInstalled(spec, target)
            _status.update {
                it.copy(
                    installed = discoverInstalled(),
                    downloadingKind = null,
                    downloadProgress = null,
                    verifyingKind = null,
                    errorMessage = null,
                )
            }
            return@withLock
        }

        // Check available storage before starting download
        modelsDir.mkdirs()
        val requiredBytes = spec.exactSizeBytes + STORAGE_SAFETY_HEADROOM_BYTES
        val usableBytes = modelsDir.usableSpace
        if (usableBytes > 0L && usableBytes < requiredBytes) {
            val needMb = requiredBytes / 1_048_576L
            val haveMb = usableBytes / 1_048_576L
            val msg = "Insufficient storage for ${spec.displayName}: need ~${needMb} MB free, have ${haveMb} MB."
            _status.update { it.copy(errorMessage = msg) }
            return@withLock
        }

        activeDownloadJob = currentCoroutineContext()[Job]
        try {
            _status.update {
                it.copy(
                    downloadingKind = kind,
                    downloadProgress = 0f,
                    verifyingKind = null,
                    errorMessage = null,
                )
            }
            downloader.download(
                urlString = spec.downloadUrl,
                target = target,
                bearerToken = null,
            ) { downloaded, total ->
                val p = total?.takeIf { it > 0 }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) }
                _status.update { it.copy(downloadProgress = p) }
            }

            _status.update {
                it.copy(
                    downloadingKind = null,
                    downloadProgress = 1f,
                    verifyingKind = kind,
                )
            }

            val check = CactModelValidator.verifyFull(
                file = target,
                expectedSha256 = if (verifySha256OnDownload) spec.sha256 else null,
                expectedSizeBytes = if (verifySha256OnDownload) spec.exactSizeBytes else null,
            )
            if (!check.valid) {
                target.delete()
                _status.update {
                    it.copy(
                        downloadingKind = null,
                        downloadProgress = null,
                        verifyingKind = null,
                        errorMessage = "Verification failed for ${spec.displayName}: ${check.reason}",
                    )
                }
                return@withLock
            }

            recordInstalled(spec, target)
            _status.update {
                it.copy(
                    installed = discoverInstalled(),
                    downloadingKind = null,
                    downloadProgress = null,
                    verifyingKind = null,
                    errorMessage = null,
                )
            }
        } catch (e: CancellationException) {
            _status.update {
                it.copy(
                    downloadingKind = null,
                    downloadProgress = null,
                    verifyingKind = null,
                    installed = discoverInstalled(),
                )
            }
            throw e
        } catch (e: Exception) {
            _status.update {
                it.copy(
                    downloadingKind = null,
                    downloadProgress = null,
                    verifyingKind = null,
                    errorMessage = e.message ?: "Download failed for ${spec.displayName}",
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

    /** Installs a local `.cact` file (used by tests or offline sideloading). */
    suspend fun installLocalFile(kind: AsrEngineKind, sourceFile: File, verifySha256: Boolean = false) = runtimeLock.withLock {
        val spec = HinglishModelCatalog.specFor(kind)
            ?: throw IllegalArgumentException("Unsupported Hinglish model kind: $kind")
        val target = targetFile(spec)
        if (sourceFile.absolutePath != target.absolutePath) {
            target.parentFile?.mkdirs()
            sourceFile.copyTo(target, overwrite = true)
        }
        val check = CactModelValidator.verifyFull(
            file = target,
            expectedSha256 = if (verifySha256) spec.sha256 else null,
            expectedSizeBytes = if (verifySha256) spec.exactSizeBytes else null,
        )
        if (!check.valid) {
            target.delete()
            throw IllegalArgumentException("Invalid .cact file for ${spec.displayName}: ${check.reason}")
        }
        recordInstalled(spec, target)
        _status.update { it.copy(installed = discoverInstalled(), errorMessage = null) }
    }

    /**
     * Lazily loads [kind] into the Cactus native runtime.
     * Returns initialization time in milliseconds (0 ms if already loaded).
     */
    suspend fun ensureLoaded(kind: AsrEngineKind): Long = runtimeLock.withLock {
        val spec = HinglishModelCatalog.specFor(kind)
            ?: throw IllegalArgumentException("Not a Cactus Hinglish model: $kind")
        val file = targetFile(spec)
        if (!file.isFile) {
            val msg = "${spec.displayName} is not downloaded yet. Download it in Models -> Speech (ASR)."
            _status.update { it.copy(errorMessage = msg) }
            throw IllegalStateException(msg)
        }

        if (_status.value.loadedKind == kind && cactusBackend.loadedModelPath == file.absolutePath) {
            return@withLock 0L
        }

        resourceCoordinator?.let { coord ->
            val guard = coord.canLoadAsr(spec.minRequiredAvailRamMb)
            if (guard is LoadGuardResult.InsufficientMemory) {
                _status.update { it.copy(errorMessage = guard.message) }
                throw IllegalStateException(guard.message)
            }
        }

        val header = CactModelValidator.validateHeader(file, minSizeBytes = 1_000_000L)
        if (!header.valid) {
            val msg = "${spec.displayName} appears corrupted (${header.reason}). Delete and re-download."
            _status.update { it.copy(errorMessage = msg) }
            throw IllegalStateException(msg)
        }

        _status.update { it.copy(loadingKind = kind, errorMessage = null) }
        try {
            val loadMs = cactusBackend.loadModel(file.absolutePath)
            _status.update { st ->
                st.copy(
                    loadedKind = kind,
                    loadingKind = null,
                    lastLoadTimeMs = st.lastLoadTimeMs + (kind to loadMs),
                    errorMessage = null,
                )
            }
            loadMs
        } catch (e: Throwable) {
            _status.update {
                it.copy(
                    loadingKind = null,
                    loadedKind = null,
                    errorMessage = "Failed to load ${spec.displayName}: ${e.message ?: e::class.simpleName}",
                )
            }
            throw e
        }
    }

    suspend fun release() = runtimeLock.withLock {
        runCatching { cactusBackend.reset() }
        _status.update { it.copy(loadedKind = null, loadingKind = null) }
    }

    suspend fun delete(kind: AsrEngineKind): Boolean = runtimeLock.withLock {
        val spec = HinglishModelCatalog.specFor(kind) ?: return@withLock false
        if (_status.value.loadedKind == kind) {
            runCatching { cactusBackend.reset() }
        }
        val file = targetFile(spec)
        File(modelsDir, spec.fileName + ".part").delete()
        val deleted = !file.exists() || file.delete()
        val manifest = readManifest().models.toMutableMap()
        manifest.remove(kind.name)
        writeManifest(Manifest(HinglishModelCatalog.CATALOG_VERSION, manifest))
        _status.update { st ->
            st.copy(
                installed = discoverInstalled(),
                loadedKind = if (st.loadedKind == kind) null else st.loadedKind,
                errorMessage = null,
            )
        }
        deleted
    }

    private suspend fun resetActiveIfIdle() {
        if (!runtimeLock.tryLock()) return
        try {
            runCatching { cactusBackend.reset() }
        } finally {
            runtimeLock.unlock()
        }
    }

    @Synchronized
    private fun discoverInstalled(): Map<AsrEngineKind, InstalledHinglishModel> {
        val manifest = readManifest()
        val result = linkedMapOf<AsrEngineKind, InstalledHinglishModel>()
        var dirty = false
        for (spec in HinglishModelCatalog.ALL_CACTUS_MODELS) {
            val file = targetFile(spec)
            val tracked = manifest.models[spec.kind.name]
            if (file.isFile && CactModelValidator.validateHeader(file, minSizeBytes = 1_000_000L).valid) {
                if (tracked != null && tracked.fileName == file.name && tracked.fileSizeBytes == file.length()) {
                    result[spec.kind] = tracked
                } else {
                    result[spec.kind] = InstalledHinglishModel(
                        id = spec.id,
                        kind = spec.kind,
                        fileName = file.name,
                        fileSizeBytes = file.length(),
                        sha256 = tracked?.sha256 ?: spec.sha256,
                        catalogVersion = HinglishModelCatalog.CATALOG_VERSION,
                        installedAt = file.lastModified().takeIf { it > 0 } ?: clock(),
                    )
                    dirty = true
                }
            } else if (tracked != null) {
                dirty = true
            }
        }
        if (dirty) {
            writeManifest(Manifest(HinglishModelCatalog.CATALOG_VERSION, result.mapKeys { it.key.name }))
        }
        return result
    }

    @Synchronized
    private fun recordInstalled(spec: HinglishModelSpec, file: File) {
        val cur = readManifest().models.toMutableMap()
        cur[spec.kind.name] = InstalledHinglishModel(
            id = spec.id,
            kind = spec.kind,
            fileName = file.name,
            fileSizeBytes = file.length(),
            sha256 = spec.sha256,
            catalogVersion = HinglishModelCatalog.CATALOG_VERSION,
            installedAt = clock(),
        )
        writeManifest(Manifest(HinglishModelCatalog.CATALOG_VERSION, cur))
    }

    private fun readManifest(): Manifest {
        if (!manifestFile.isFile) return Manifest()
        return runCatching { json.decodeFromString<Manifest>(manifestFile.readText()) }.getOrDefault(Manifest())
    }

    private fun writeManifest(manifest: Manifest) {
        modelsDir.mkdirs()
        manifestFile.writeText(json.encodeToString(manifest))
    }

    private companion object {
        const val STORAGE_SAFETY_HEADROOM_BYTES = 50L * 1024L * 1024L // 50 MB buffer
    }
}
