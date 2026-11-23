package com.uoai.provider

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uoai.provider.presentation.ClientsTab
import com.uoai.provider.presentation.DashboardViewModel
import com.uoai.provider.presentation.ModelSettingsTab
import com.uoai.provider.presentation.OverviewTab
import com.uoai.provider.presentation.PlaygroundTab
import com.uoai.provider.presentation.PluginsTab
import com.uoai.provider.ui.components.StatusPill
import com.uoai.provider.ui.theme.ProviderTheme
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import dagger.hilt.android.AndroidEntryPoint

private enum class ProviderTab(val label: String, val title: String, val icon: ImageVector) {
    OVERVIEW("Overview", "UOAI Provider", Icons.Rounded.Dashboard),
    MODEL("Model", "Model & 32K Settings", Icons.Rounded.Tune),
    TEST("Test", "Playground", Icons.Rounded.Terminal),
    CLIENTS("Clients", "Client Applications", Icons.Rounded.Apps),
    PLUGINS("Plugins", "Plugins & Audit", Icons.Rounded.Extension),
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
private fun ProviderMainShell(vm: DashboardViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sc = ProviderThemeDefaults.statusColors
    var currentTab by rememberSaveable { mutableStateOf(ProviderTab.OVERVIEW) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
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
                onStartServer = vm::startServer,
                onStopServer = vm::stopServer,
                onLoadModel = vm::load,
                onUnloadModel = vm::unload,
                onOpenModelTab = { currentTab = ProviderTab.MODEL },
                onOpenClientsTab = { currentTab = ProviderTab.CLIENTS },
                modifier = contentModifier,
            )
            ProviderTab.MODEL -> ModelSettingsTab(
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
                modifier = contentModifier,
            )
            ProviderTab.TEST -> PlaygroundTab(
                state = state,
                onLoadModel = vm::load,
                onRunTest = vm::runTest,
                onCancelTest = vm::cancelTest,
                onClearTest = vm::clearTest,
                modifier = contentModifier,
            )
            ProviderTab.CLIENTS -> ClientsTab(modifier = contentModifier)
            ProviderTab.PLUGINS -> PluginsTab(modifier = contentModifier)
        }
    }
}
