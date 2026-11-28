package com.uoai.asr

import com.uoai.asr.engines.AsrEngineKind
import com.uoai.asr.engines.AsrOutputScript
import com.uoai.core.resources.ComputeCoexistencePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Persisted provider-wide Speech Recognition settings. */
@Serializable
data class AsrSettings(
    val selectedProfile: AsrProfile = AsrProfile.BALANCED,
    val selectedEngine: AsrEngineKind = AsrEngineKind.WHISPER,
    val outputScript: AsrOutputScript = AsrOutputScript.DEVANAGARI_HINGLISH,
    val defaultLanguage: String = "auto",
    val translateToEnglish: Boolean = false,
    val autoLoadOnRequest: Boolean = true,
    /** Idle auto-unload delay in seconds; 0 disables auto-unload. */
    val idleUnloadTimeoutSec: Int = DEFAULT_IDLE_UNLOAD_SEC,
    /** 0 = automatic based on CPU cores, thermals, and LLM coexistence. */
    val maxThreads: Int = 0,
    val coexistencePolicy: ComputeCoexistencePolicy = ComputeCoexistencePolicy.SMART_THROTTLE,
    val timestampsEnabled: Boolean = true,
) {
    fun sanitized(): AsrSettings {
        val lang = AsrModelCatalog.normalizeLanguageSpec(defaultLanguage)
        return copy(
            defaultLanguage = lang,
            idleUnloadTimeoutSec = idleUnloadTimeoutSec.coerceIn(0, 3600),
            maxThreads = maxThreads.coerceIn(0, 8),
        )
    }

    companion object {
        const val DEFAULT_IDLE_UNLOAD_SEC = 300
        val IDLE_UNLOAD_PRESETS_SEC = listOf(0, 120, 300, 900)
    }
}

interface AsrSettingsRepository {
    val settings: StateFlow<AsrSettings>
    fun update(transform: (AsrSettings) -> AsrSettings): AsrSettings
    fun reset(): AsrSettings
}

class FileAsrSettingsRepository(private val file: File) : AsrSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val _settings = MutableStateFlow(readInitial())
    override val settings: StateFlow<AsrSettings> = _settings.asStateFlow()

    private fun readInitial(): AsrSettings {
        if (!file.isFile) return AsrSettings()
        return runCatching { json.decodeFromString<AsrSettings>(file.readText()).sanitized() }
            .getOrDefault(AsrSettings())
    }

    @Synchronized
    override fun update(transform: (AsrSettings) -> AsrSettings): AsrSettings {
        val next = transform(_settings.value).sanitized()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(next))
        _settings.value = next
        return next
    }

    @Synchronized
    override fun reset(): AsrSettings = update { AsrSettings() }
}

