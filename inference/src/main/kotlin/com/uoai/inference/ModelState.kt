package com.uoai.inference

/** Lifecycle states of the single shared model instance (FR-01). */
enum class ModelState {
    NOT_INSTALLED, DOWNLOADING, VERIFYING, LOADING, READY, GENERATING, UNLOADING, ERROR
}

data class ModelInfo(
    val id: String,
    val name: String,
    val version: String?,
    val format: String?,
    val quantization: String?,
    val fileSizeBytes: Long?,
)

/** Observable snapshot of the model manager. */
data class ModelStatus(
    val state: ModelState = ModelState.NOT_INSTALLED,
    val model: ModelInfo? = null,
    val loadProgress: Float? = null,
    val errorMessage: String? = null,
    val loadedConfig: RuntimeLoadConfig? = null,
)
