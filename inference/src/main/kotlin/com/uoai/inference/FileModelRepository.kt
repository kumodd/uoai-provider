package com.uoai.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class FileModelRepository(private val dir: File) : ModelRepository {
    @Serializable private data class Meta(val info: SerializableInfo, val fileName: String)
    @Serializable private data class SerializableInfo(
        val id: String, val name: String, val version: String?, val format: String?,
        val quantization: String?, val fileSizeBytes: Long?, val sha256: String? = null,
    )

    private val metaFile get() = File(dir, "model.json")
    init { dir.mkdirs() }

    override fun modelsDir() = dir

    private fun meta(): Meta? = runCatching { Json.decodeFromString<Meta>(metaFile.readText()) }.getOrNull()

    override fun installed(): ModelInfo? = installedFile()?.let {
        meta()?.info?.let { i -> ModelInfo(i.id, i.name, i.version, i.format, i.quantization, i.fileSizeBytes) }
    }

    override fun installedFile(): File? = meta()?.let { File(dir, it.fileName) }?.takeIf { it.isFile }

    override fun save(info: ModelInfo, file: File) {
        val m = Meta(SerializableInfo(info.id, info.name, info.version, info.format, info.quantization, info.fileSizeBytes), file.name)
        metaFile.writeText(Json.encodeToString(m))
    }

    override fun listLocalModels(): List<ModelInfo> {
        val files = dir.listFiles()?.filter {
            it.isFile &&
                it.extension.equals("litertlm", ignoreCase = true) &&
                ModelIntegrity.looksLikeModel(it)
        }.orEmpty().sortedBy { it.name.lowercase() }
        return files.map { buildInfo(it) }
    }

    override fun selectInstalled(nameOrFile: String): ModelInfo? {
        val target = dir.listFiles()?.firstOrNull { f ->
            f.isFile && (f.name == nameOrFile || f.nameWithoutExtension == nameOrFile) &&
                ModelIntegrity.looksLikeModel(f)
        } ?: return null
        val info = buildInfo(target)
        save(info, target)
        return info
    }

    override fun clear() {
        installedFile()?.delete()
        metaFile.delete()
    }

    companion object {
        fun buildInfo(file: File): ModelInfo {
            val ext = file.extension
            val name = file.name
            val quant = Regex("(int4|int8|q4|q8|f32|e2b|e4b|12b|26b|31b)", RegexOption.IGNORE_CASE)
                .find(name)?.value
                ?: "QAT-INT4"
            return ModelInfo(
                id = ModelCatalog.inferModelId(name),
                name = name.removeSuffix(".$ext"),
                version = "LiteRT-LM",
                format = ext,
                quantization = quant,
                fileSizeBytes = file.length(),
            )
        }
    }
}
