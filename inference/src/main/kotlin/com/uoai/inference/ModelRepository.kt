package com.uoai.inference

import java.io.File

/** Where model files live and what metadata is known about them. File-backed for Phase 2 (Room in Phase 4). */
interface ModelRepository {
    fun modelsDir(): File
    fun installed(): ModelInfo?
    fun installedFile(): File?
    fun save(info: ModelInfo, file: File)
    fun clear()

    /** Returns all valid local Gemma 4 `.litertlm` bundles found in [modelsDir]. */
    fun listLocalModels(): List<ModelInfo> = listOfNotNull(installed())

    /** Sets the active local model by filename or stem and returns its metadata, or null if missing. */
    fun selectInstalled(nameOrFile: String): ModelInfo? = null
}
