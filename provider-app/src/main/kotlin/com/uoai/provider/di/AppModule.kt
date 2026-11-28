package com.uoai.provider.di

import android.content.Context
import androidx.room.Room
import com.uoai.asr.AsrEngine
import com.uoai.asr.AsrModelManager
import com.uoai.asr.AsrModelRepository
import com.uoai.asr.AsrSettingsRepository
import com.uoai.asr.DefaultAsrEngine
import com.uoai.asr.FileAsrModelRepository
import com.uoai.asr.FileAsrSettingsRepository
import com.uoai.asr.audio.AudioDecoder
import com.uoai.asr.audio.DefaultAudioDecoder
import com.uoai.asr.engines.AksharEngine
import com.uoai.asr.engines.AsrComparisonRunner
import com.uoai.asr.engines.AsrEngineKind
import com.uoai.asr.engines.ExistingWhisperEngine
import com.uoai.asr.engines.HinglishModelManager
import com.uoai.asr.engines.ShrutiEngine
import com.uoai.asr.engines.SpeechEngineRegistry
import com.uoai.asr.runtime.AsrRuntime
import com.uoai.asr.runtime.CactusRuntimeAdapter
import com.uoai.asr.runtime.CactusSpeechBackend
import com.uoai.asr.runtime.WhisperCppRuntimeAdapter
import com.uoai.clients.ClientRegistry
import com.uoai.clients.ClientRepository
import com.uoai.core.common.DefaultDispatcherProvider
import com.uoai.core.common.DispatcherProvider
import com.uoai.core.database.AppDatabase
import com.uoai.core.resources.AndroidDeviceResourceMonitor
import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.core.resources.DeviceResourceMonitor
import com.uoai.core.security.SecretVault
import com.uoai.inference.DefaultInferenceEngine
import com.uoai.inference.FileInferenceSettingsRepository
import com.uoai.inference.FileModelRepository
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceSettingsRepository
import com.uoai.inference.ModelManager
import com.uoai.inference.ModelRepository
import com.uoai.inference.runtime.InferenceRuntime
import com.uoai.inference.runtime.LiteRtLmRuntimeAdapter
import com.uoai.plugins.PluginManager
import com.uoai.plugins.PluginRegistry
import com.uoai.provider.data.RoomAsrRequestRecorder
import com.uoai.provider.data.RoomRequestRecorder
import com.uoai.providerapi.AsrTempFileStore
import com.uoai.providerapi.external.ExternalAudioProviderClient
import com.uoai.providerapi.external.ExternalAudioProviderRegistry
import com.uoai.providerapi.external.ExternalSecretStore
import com.uoai.providerapi.external.HttpExternalAudioProviderClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier annotation class AppScope

/** Everything here is a singleton: one LLM runtime, one ASR runtime, one coordinator, one database per process. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider
    @Provides @Singleton @AppScope fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Provides @Singleton fun vault(@ApplicationContext c: Context) = SecretVault(c)
    @Provides @Singleton fun db(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "uoai.db").build()
    @Provides @Singleton fun clientRepo(db: AppDatabase) = ClientRepository(db.clientDao(), db.credentialDao(), db.auditDao())
    @Provides @Singleton fun registry(r: ClientRepository): ClientRegistry = r
    @Provides @Singleton fun pluginRegistry(db: AppDatabase): PluginRegistry = PluginManager(db.pluginDao(), db.auditDao())

    // --- Resource Coordination ---
    @Provides @Singleton fun deviceResourceMonitor(@ApplicationContext c: Context): DeviceResourceMonitor =
        AndroidDeviceResourceMonitor(c)

    @Provides @Singleton fun resourceCoordinator(
        monitor: DeviceResourceMonitor,
        asrSettingsRepo: AsrSettingsRepository,
    ): ComputeResourceCoordinator = ComputeResourceCoordinator(
        monitor = monitor,
        policyProvider = { asrSettingsRepo.settings.value.coexistencePolicy },
    )

    // --- Gemma 4 LiteRT-LM Subsystem (direct multimodal audio + text) ---
    @Provides @Singleton fun runtime(@ApplicationContext c: Context): InferenceRuntime =
        LiteRtLmRuntimeAdapter(c)
    @Provides @Singleton fun repo(@ApplicationContext c: Context): ModelRepository =
        FileModelRepository(File(c.filesDir, "models"))
    @Provides @Singleton fun settingsRepo(@ApplicationContext c: Context): InferenceSettingsRepository =
        FileInferenceSettingsRepository(File(c.filesDir, "inference_settings.json"))
    @Provides @Singleton fun manager(
        repo: ModelRepository,
        rt: InferenceRuntime,
        settingsRepo: InferenceSettingsRepository,
    ) = ModelManager(repo, rt, settingsRepository = settingsRepo)
    @Provides @Singleton
    fun engine(
        m: ModelManager,
        rt: InferenceRuntime,
        db: AppDatabase,
        @AppScope scope: CoroutineScope,
        coordinator: ComputeResourceCoordinator,
    ): InferenceEngine = DefaultInferenceEngine(
        manager = m,
        runtime = rt,
        scope = scope,
        recorder = RoomRequestRecorder(db.requestDao(), scope),
        resourceCoordinator = coordinator,
    )

    // --- Shared Local ASR Subsystem (:asr) ---
    @Provides @Singleton fun asrRuntime(): AsrRuntime = WhisperCppRuntimeAdapter()
    @Provides @Singleton fun asrModelRepo(@ApplicationContext c: Context): AsrModelRepository =
        FileAsrModelRepository(File(c.filesDir, "asr_models"))
    @Provides @Singleton fun asrSettingsRepo(@ApplicationContext c: Context): AsrSettingsRepository =
        FileAsrSettingsRepository(File(c.filesDir, "asr_settings.json"))
    @Provides @Singleton fun audioDecoder(): AudioDecoder = DefaultAudioDecoder()
    @Provides @Singleton fun asrTempStore(@ApplicationContext c: Context): AsrTempFileStore =
        AsrTempFileStore(File(c.cacheDir, "asr_uploads"))
    @Provides @Singleton fun asrModelManager(
        repo: AsrModelRepository,
        rt: AsrRuntime,
        settingsRepo: AsrSettingsRepository,
        @AppScope scope: CoroutineScope,
        coordinator: ComputeResourceCoordinator,
    ): AsrModelManager = AsrModelManager(
        repository = repo,
        runtime = rt,
        settingsRepository = settingsRepo,
        scope = scope,
        resourceCoordinator = coordinator,
    )
    @Provides @Singleton fun asrEngine(
        mgr: AsrModelManager,
        rt: AsrRuntime,
        settingsRepo: AsrSettingsRepository,
        db: AppDatabase,
        @AppScope scope: CoroutineScope,
        coordinator: ComputeResourceCoordinator,
    ): AsrEngine = DefaultAsrEngine(
        manager = mgr,
        runtime = rt,
        settingsRepository = settingsRepo,
        scope = scope,
        recorder = RoomAsrRequestRecorder(db.requestDao(), scope),
        resourceCoordinator = coordinator,
    )

    // --- Specialized Offline Hinglish ASR Engines (Cactus 4.0.0: AksharSTT v4, Lite v4, Shruti) ---
    @Provides @Singleton fun cactusSpeechBackend(): CactusSpeechBackend = CactusRuntimeAdapter()

    @Provides @Singleton fun hinglishModelManager(
        @ApplicationContext c: Context,
        cactusBackend: CactusSpeechBackend,
        @AppScope scope: CoroutineScope,
        coordinator: ComputeResourceCoordinator,
    ): HinglishModelManager = HinglishModelManager(
        modelsDir = File(c.filesDir, "asr_models/hinglish"),
        cactusBackend = cactusBackend,
        scope = scope,
        resourceCoordinator = coordinator,
    )

    @Provides @Singleton fun speechEngineRegistry(
        asrModelManager: AsrModelManager,
        asrEngine: AsrEngine,
        hinglishModelManager: HinglishModelManager,
        cactusBackend: CactusSpeechBackend,
    ): SpeechEngineRegistry = SpeechEngineRegistry(
        whisper = ExistingWhisperEngine(asrModelManager, asrEngine),
        aksharV4 = AksharEngine(AsrEngineKind.AKSHAR_V4, hinglishModelManager, cactusBackend),
        aksharLiteV4 = AksharEngine(AsrEngineKind.AKSHAR_LITE_V4, hinglishModelManager, cactusBackend),
        shrutiHi = ShrutiEngine(hinglishModelManager, cactusBackend),
    )

    @Provides @Singleton fun asrComparisonRunner(registry: SpeechEngineRegistry): AsrComparisonRunner =
        AsrComparisonRunner(registry)

    // --- Configurable External Audio Inference Providers ---
    @Provides @Singleton fun externalAudioProviderRegistry(
        @ApplicationContext c: Context,
        vault: SecretVault,
    ): ExternalAudioProviderRegistry = ExternalAudioProviderRegistry(
        storageFile = File(c.filesDir, "external_audio_providers.json"),
        secretStore = object : ExternalSecretStore {
            override fun get(providerId: String): String? = vault.get("ext_audio_key_$providerId")
            override fun set(providerId: String, apiKey: String?) {
                val key = "ext_audio_key_$providerId"
                if (apiKey.isNullOrBlank()) vault.remove(key) else vault.put(key, apiKey.trim())
            }
        },
    )

    @Provides @Singleton fun externalAudioProviderClient(): ExternalAudioProviderClient =
        HttpExternalAudioProviderClient()
}
