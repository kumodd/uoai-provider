package com.uoai.asr

import kotlinx.serialization.Serializable

/** Explicit lifecycle states for the single shared ASR model instance. */
enum class AsrModelState {
    NOT_INSTALLED,
    INSTALLED,
    DOWNLOADING,
    VERIFYING,
    LOADING,
    READY,
    TRANSCRIBING,
    UNLOADING,
    ERROR,
}

@Serializable
data class InstalledAsrModel(
    val id: String,
    val profile: AsrProfile,
    val fileName: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val catalogVersion: Int = AsrModelCatalog.CATALOG_VERSION,
    val installedAt: Long,
)

/** Per-profile UI & API summary combining static preset metadata with live disk/runtime state. */
data class AsrProfileSummary(
    val preset: AsrModelPreset,
    val installed: Boolean,
    val selected: Boolean,
    val loaded: Boolean,
    val downloading: Boolean,
    val downloadProgress: Float?,
    val installedSizeBytes: Long?,
)

/** Observable snapshot published by [AsrModelManager]. */
data class AsrModelStatus(
    val state: AsrModelState = AsrModelState.NOT_INSTALLED,
    val selectedProfile: AsrProfile = AsrProfile.BALANCED,
    val loadedProfile: AsrProfile? = null,
    val downloadingProfile: AsrProfile? = null,
    val downloadProgress: Float? = null,
    val errorMessage: String? = null,
    val lastLoadTimeMs: Long? = null,
    val installedProfiles: Map<AsrProfile, InstalledAsrModel> = emptyMap(),
) {
    val activePreset: AsrModelPreset get() = AsrModelCatalog.forProfile(selectedProfile)
    val isSelectedInstalled: Boolean get() = installedProfiles.containsKey(selectedProfile)
    val isLoaded: Boolean get() = loadedProfile != null && (state == AsrModelState.READY || state == AsrModelState.TRANSCRIBING)
    val totalInstalledBytes: Long get() = installedProfiles.values.sumOf { it.fileSizeBytes }

    fun profileSummaries(): List<AsrProfileSummary> = AsrModelCatalog.ALL_PRESETS.map { preset ->
        val inst = installedProfiles[preset.profile]
        AsrProfileSummary(
            preset = preset,
            installed = inst != null,
            selected = selectedProfile == preset.profile,
            loaded = loadedProfile == preset.profile && isLoaded,
            downloading = downloadingProfile == preset.profile && (state == AsrModelState.DOWNLOADING || state == AsrModelState.VERIFYING),
            downloadProgress = if (downloadingProfile == preset.profile) downloadProgress else null,
            installedSizeBytes = inst?.fileSizeBytes,
        )
    }
}

