package com.uoai.provider

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uoai.provider.presentation.ClientsTab
import com.uoai.provider.presentation.DashboardViewModel
import com.uoai.provider.presentation.ModelSettingsTab
import com.uoai.provider.presentation.OverviewTab
import com.uoai.provider.presentation.PlaygroundTab
import com.uoai.provider.presentation.PluginsTab
import com.uoai.provider.presentation.SpeechSettingsScreen
import com.uoai.provider.presentation.SpeechSettingsViewModel
import com.uoai.provider.presentation.VoiceCaptureEngine
import com.uoai.provider.ui.components.StatusPill
import com.uoai.provider.ui.theme.ProviderTheme
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import dagger.hilt.android.AndroidEntryPoint

private enum class ProviderTab(val label: String, val title: String, val icon: ImageVector) {
    OVERVIEW("Overview", "UOAI Provider", Icons.Rounded.Dashboard),
    MODEL("Models", "Models & Speech", Icons.Rounded.Tune),
    TEST("Test", "Playground", Icons.Rounded.Terminal),
    CLIENTS("Clients", "Client Applications", Icons.Rounded.Apps),
    PLUGINS("Plugins", "Plugins & Audit", Icons.Rounded.Extension),
}

private enum class ModelsSubTab(val label: String, val icon: ImageVector) {
    LLM("LLM", Icons.Rounded.AutoAwesome),
    SPEECH("Speech (ASR)", Icons.Rounded.GraphicEq),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ProviderTheme {
                ProviderMainShell()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderMainShell(
    vm: DashboardViewModel = hiltViewModel(),
    asrVm: SpeechSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val asrState by asrVm.state.collectAsStateWithLifecycle()
    val sc = ProviderThemeDefaults.statusColors
    var currentTab by rememberSaveable { mutableStateOf(ProviderTab.OVERVIEW) }
    var modelsSubTab by rememberSaveable { mutableStateOf(ModelsSubTab.LLM) }
    var pendingAsrLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCaptureEngine by rememberSaveable { mutableStateOf(VoiceCaptureEngine.WHISPER_ASR) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { asrVm.runAudioFile(it, pendingAsrLanguage, pendingCaptureEngine) }
    }
    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            asrVm.startRecording(pendingAsrLanguage, pendingCaptureEngine)
        } else {
            asrVm.reportMicPermissionDenied()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(currentTab.title, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    StatusPill(
                        text = if (state.apiServerRunning) "127.0.0.1:8765" else "Stopped",
                        dotColor = if (state.apiServerRunning) sc.ready else sc.neutral,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                ProviderTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            if (tab == ProviderTab.CLIENTS && state.pendingClients > 0) {
                                BadgedBox(badge = { Badge { Text(state.pendingClients.toString()) } }) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (currentTab) {
            ProviderTab.OVERVIEW -> OverviewTab(
                state = state,
                asrState = asrState,
                onStartServer = vm::startServer,
                onStopServer = vm::stopServer,
                onLoadModel = vm::load,
                onUnloadModel = vm::unload,
                onLoadAsrModel = { asrVm.loadModel(null) },
                onUnloadAsrModel = asrVm::unloadModel,
                onOpenModelTab = {
                    modelsSubTab = ModelsSubTab.LLM
                    currentTab = ProviderTab.MODEL
                },
                onOpenSpeechTab = {
                    modelsSubTab = ModelsSubTab.SPEECH
                    currentTab = ProviderTab.MODEL
                },
                onOpenClientsTab = { currentTab = ProviderTab.CLIENTS },
                modifier = contentModifier,
            )
            ProviderTab.MODEL -> Column(modifier = contentModifier) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    val tabs = ModelsSubTab.entries
                    tabs.forEachIndexed { idx, sub ->
                        SegmentedButton(
                            selected = modelsSubTab == sub,
                            onClick = { modelsSubTab = sub },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                            icon = {
                                SegmentedButtonDefaults.Icon(active = modelsSubTab == sub) {
                                    Icon(sub.icon, contentDescription = null, modifier = Modifier.padding(1.dp))
                                }
                            },
                        ) {
                            Text(sub.label, maxLines = 1)
                        }
                    }
                }
                when (modelsSubTab) {
                    ModelsSubTab.LLM -> ModelSettingsTab(
                        state = state,
                        onUpdateContextWindow = vm::updateContextWindow,
                        onUpdateMaxOutputTokens = vm::updateMaxOutputTokens,
                        onUpdateTemperature = vm::updateTemperature,
                        onUpdateTopP = vm::updateTopP,
                        onUpdateTopK = vm::updateTopK,
                        onUpdateBackend = vm::updateBackend,
                        onUpdateHuggingFaceSource = vm::updateHuggingFaceSource,
                        onResetSettings = vm::resetSettings,
                        onLoad = vm::load,
                        onReload = vm::reloadModel,
                        onUnload = vm::unload,
                        onDeleteModel = vm::deleteModel,
                        onSelectLocalModel = vm::selectLocalModel,
                        onSaveToken = vm::saveToken,
                        onClearToken = vm::clearToken,
                        onDownload = vm::download,
                        onImport = { picker.launch(arrayOf("*/*")) },
                    )
                    ModelsSubTab.SPEECH -> SpeechSettingsScreen(
                        state = asrState,
                        onDownloadProfile = asrVm::downloadProfile,
                        onCancelDownload = asrVm::cancelDownload,
                        onSelectProfile = asrVm::selectProfile,
                        onLoadModel = asrVm::loadModel,
                        onUnloadModel = asrVm::unloadModel,
                        onDeleteProfile = asrVm::deleteProfile,
                        onSelectEngine = asrVm::selectAsrEngine,
                        onUpdateOutputScript = asrVm::updateOutputScript,
                        onDownloadHinglishModel = asrVm::downloadHinglishModel,
                        onCancelHinglishDownload = asrVm::cancelHinglishDownload,
                        onLoadHinglishModel = asrVm::loadHinglishModel,
                        onUnloadHinglishModel = asrVm::unloadHinglishModel,
                        onDeleteHinglishModel = asrVm::deleteHinglishModel,
                        onUpsertExternalProvider = asrVm::upsertExternalProvider,
                        onToggleExternalProvider = asrVm::setExternalProviderEnabled,
                        onDeleteExternalProvider = asrVm::deleteExternalProvider,
                        onUpdateLanguage = asrVm::updateLanguage,
                        onUpdateTranslateToEnglish = asrVm::updateTranslateToEnglish,
                        onUpdateAutoLoad = asrVm::updateAutoLoad,
                        onUpdateIdleUnloadTimeout = asrVm::updateIdleUnloadTimeout,
                        onUpdateCoexistencePolicy = asrVm::updateCoexistencePolicy,
                        onUpdateMaxThreads = asrVm::updateMaxThreads,
                        onClearTempCache = asrVm::clearTempAudioCache,
                    )
                }
            }
            ProviderTab.TEST -> PlaygroundTab(
                state = state,
                asrState = asrState,
                liveMeterFlow = asrVm.liveMeter,
                onLoadModel = vm::load,
                onRunTest = vm::runTest,
                onCancelTest = vm::cancelTest,
                onClearTest = vm::clearTest,
                onLoadAsrModel = { asrVm.loadModel(null) },
                onDownloadHinglishModel = asrVm::downloadHinglishModel,
                onCancelHinglishDownload = asrVm::cancelHinglishDownload,
                onUpdateOutputScript = asrVm::updateOutputScript,
                onStartEngineRecording = { lang, engine ->
                    pendingAsrLanguage = lang
                    pendingCaptureEngine = engine
                    val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        asrVm.startRecording(lang, engine)
                    } else {
                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopRecordingAndTranscribe = { lang -> asrVm.stopRecordingAndTranscribe(lang) },
                onDiscardRecording = asrVm::discardRecording,
                onPickAudioForEngine = { lang, engine ->
                    pendingAsrLanguage = lang
                    pendingCaptureEngine = engine
                    audioPicker.launch(arrayOf("audio/*", "*/*"))
                },
                onCancelAsrTest = asrVm::cancelAudioTest,
                onClearAsrTest = asrVm::clearAudioTest,
                onUnlockAsrLanguage = asrVm::unlockSessionLanguage,
                onToggleAutoEnhance = asrVm::setAutoEnhance,
                onEnhanceTranscript = { asrVm.enhanceTranscript() },
                onCancelEnhance = asrVm::cancelEnhancement,
                onToggleCompareEngine = asrVm::toggleCompareEngine,
                onUpdateCompareGroundTruth = asrVm::updateCompareGroundTruth,
                modifier = contentModifier,
            )
            ProviderTab.CLIENTS -> ClientsTab(modifier = contentModifier)
            ProviderTab.PLUGINS -> PluginsTab(modifier = contentModifier)
        }
    }
}
