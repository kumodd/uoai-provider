package com.uoai.asr

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Isolated file-backed repository under `filesDir/asr_models/` with manifest persistence (`asr_manifest.json`).
 * Discovers both manifest-tracked models and pre-existing valid GGML files matching known presets.
 */
class FileAsrModelRepository(private val dir: File) : AsrModelRepository {
    @Serializable
    private data class Manifest(
        val catalogVersion: Int = AsrModelCatalog.CATALOG_VERSION,
        val models: Map<String, InstalledAsrModel> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val manifestFile get() = File(dir, "asr_manifest.json")

    init {
        dir.mkdirs()
    }

    override fun modelsDir(): File = dir

    override fun targetFile(preset: AsrModelPreset): File = File(dir, preset.fileName)

    @Synchronized
    override fun listInstalled(): Map<AsrProfile, InstalledAsrModel> {
        val manifest = readManifest()
        val result = linkedMapOf<AsrProfile, InstalledAsrModel>()
        var dirty = false

        for (preset in AsrModelCatalog.ALL_PRESETS) {
            val file = targetFile(preset)
            val tracked = manifest.models[preset.profile.name]
            if (file.isFile && GgmlModelValidator.validateHeader(file, minSizeBytes = 1_000_000L).valid) {
                if (tracked != null && tracked.fileName == file.name && tracked.fileSizeBytes == file.length()) {
                    result[preset.profile] = tracked
                } else {
                    val discovered = InstalledAsrModel(
                        id = preset.id,
                        profile = preset.profile,
                        fileName = file.name,
                        fileSizeBytes = file.length(),
                        sha256 = tracked?.sha256 ?: preset.sha256,
                        catalogVersion = AsrModelCatalog.CATALOG_VERSION,
                        installedAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                    result[preset.profile] = discovered
                    dirty = true
                }
            } else if (tracked != null) {
                dirty = true
            }
        }

        if (dirty) {
            writeManifest(Manifest(AsrModelCatalog.CATALOG_VERSION, result.mapKeys { it.key.name }))
        }
        return result
    }

    override fun fileFor(profile: AsrProfile): File? {
        val preset = AsrModelCatalog.forProfile(profile)
        val file = targetFile(preset)
        return file.takeIf { it.isFile }
    }

    @Synchronized
    override fun recordInstalled(entry: InstalledAsrModel) {
        val cur = listInstalled().toMutableMap()
        cur[entry.profile] = entry
        writeManifest(Manifest(AsrModelCatalog.CATALOG_VERSION, cur.mapKeys { it.key.name }))
    }

    @Synchronized
    override fun delete(profile: AsrProfile): Boolean {
        val preset = AsrModelCatalog.forProfile(profile)
        val file = targetFile(preset)
        val part = File(dir, preset.fileName + ".part")
        part.delete()
        val removed = !file.exists() || file.delete()
        val cur = readManifest().models.toMutableMap()
        cur.remove(profile.name)
        writeManifest(Manifest(AsrModelCatalog.CATALOG_VERSION, cur))
        return removed
    }

    override fun totalStorageBytes(): Long = listInstalled().values.sumOf { it.fileSizeBytes }

    private fun readManifest(): Manifest {
        if (!manifestFile.isFile) return Manifest()
        return runCatching { json.decodeFromString<Manifest>(manifestFile.readText()) }.getOrDefault(Manifest())
    }

    private fun writeManifest(manifest: Manifest) {
        dir.mkdirs()
        manifestFile.writeText(json.encodeToString(manifest))
    }
}

