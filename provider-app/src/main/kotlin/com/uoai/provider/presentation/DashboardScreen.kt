package com.uoai.provider.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uoai.inference.ModelState
import com.uoai.inference.RequestStatus
import com.uoai.provider.ui.components.*
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import java.text.DateFormat
import java.util.Date

@Composable
fun OverviewTab(
    state: DashboardUiState,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onOpenModelTab: () -> Unit,
    onOpenClientsTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sc = ProviderThemeDefaults.statusColors
    val clipboard = LocalClipboardManager.current
    val modelState = state.model.state
    val busy = modelState in setOf(
        ModelState.DOWNLOADING, ModelState.VERIFYING, ModelState.LOADING, ModelState.UNLOADING, ModelState.GENERATING,
    )
    val activeCtx = state.model.loadedConfig?.contextWindowTokens ?: state.settings.contextWindowTokens
    val activeBackend = state.model.loadedConfig?.preferredBackend ?: state.settings.preferredBackend

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero Provider Control Card
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Local AI Endpoint", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "http://127.0.0.1:8765/v1",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString("http://127.0.0.1:8765/v1")) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy endpoint", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    StatusPill(
                        text = if (state.apiServerRunning) "SERVER LIVE" else "STOPPED",
                        dotColor = if (state.apiServerRunning) sc.ready else sc.neutral,
                    )
                }

                state.serverError?.let {
                    Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(it, color = sc.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val btnPad = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    if (!state.apiServerRunning) {
                        Button(
                            onClick = onStartServer,
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = btnPad,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start API", maxLines = 1, softWrap = false)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onStopServer,
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = btnPad,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = sc.errorContainer,
                                contentColor = sc.error,
                            ),
                        ) {
                            Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop API", maxLines = 1, softWrap = false)
                        }
                    }

                    if (modelState == ModelState.READY || modelState == ModelState.GENERATING) {
                        OutlinedButton(
                            onClick = onUnloadModel,
                            enabled = modelState == ModelState.READY,
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = btnPad,
                        ) {
                            Icon(Icons.Rounded.Eject, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Unload", maxLines = 1, softWrap = false)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onLoadModel,
                            enabled = !busy && state.modelInstalled,
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = btnPad,
                        ) {
                            Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (modelState == ModelState.LOADING) "Loading…" else "Load Model",
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }

        // 2x2 Telemetry Grid
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                icon = Icons.Rounded.Tune,
                label = "Context Window",
                value = formatTokenShort(activeCtx),
                subtitle = "Out cap ${formatTokenShort(state.settings.maxOutputTokens)} · $activeBackend",
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = onOpenModelTab,
            )
            MetricTile(
                icon = Icons.Rounded.SdStorage,
                label = "Memory & RAM",
                value = "${state.memory.nativeHeapMb} MB",
                subtitle = "${state.memory.deviceAvailMb} MB free / ${state.memory.deviceTotalMb} MB",
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                icon = Icons.Rounded.Apps,
                label = "Clients",
                value = "${state.registeredClients} active",
                subtitle = if (state.pendingClients > 0) "${state.pendingClients} awaiting approval" else "No pending approvals",
                accent = if (state.pendingClients > 0) sc.warning else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
                onClick = onOpenClientsTab,
            )
            MetricTile(
                icon = Icons.Rounded.Queue,
                label = "Inference Queue",
                value = if (state.running != null) "1 running" else "Idle",
                subtitle = "${state.queued} queued in scheduler",
                accent = if (state.running != null) sc.ready else sc.neutral,
                modifier = Modifier.weight(1f),
            )
        }

        // Active Model Card
        val modelBadgeColor = when {
            modelState == ModelState.READY || modelState == ModelState.GENERATING -> sc.ready
            modelState == ModelState.ERROR -> sc.error
            busy -> sc.warning
            else -> sc.neutral
        }
        val modelLabel = when {
            modelState == ModelState.NOT_INSTALLED && state.modelInstalled -> "INSTALLED"
            else -> modelState.name
        }
        SectionCard(
            title = state.model.model?.name ?: "No Model Installed",
            icon = Icons.Rounded.SmartToy,
            subtitle = state.model.model?.let {
                "${it.format?.uppercase() ?: "TASK"} · ${it.quantization?.uppercase() ?: "INT4"} · ${formatFileSize(it.fileSizeBytes)}"
            } ?: "Open the Model tab to download or import Gemma 3",
            trailing = { StatusPill(modelLabel, modelBadgeColor) },
        ) {
            state.model.loadProgress?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()}% completed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.requiresReload) {
                Surface(color = sc.warningContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        "Settings updated (${formatTokenShort(state.settings.contextWindowTokens)} context). Open Model tab to Apply & Reload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = sc.warning,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            state.model.errorMessage?.let { err ->
                Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(err, style = MaterialTheme.typography.bodySmall, color = sc.error, modifier = Modifier.padding(10.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenModelTab) {
                    Text("Configure Model & 32K Limits")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp))
                }
            }
        }

        // Recent Requests Card
        SectionCard(
            title = "Recent Inference Requests",
            icon = Icons.Rounded.History,
            subtitle = "Metadata only · client prompts are never persisted",
        ) {
            if (state.recent.isEmpty()) {
                Text(
                    "No requests processed yet in this session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.recent.forEach { rec ->
                        val color = when (rec.status) {
                            RequestStatus.COMPLETED -> sc.ready
                            RequestStatus.RUNNING, RequestStatus.QUEUED -> sc.warning
                            RequestStatus.FAILED -> sc.error
                            RequestStatus.CANCELLED -> sc.neutral
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "Client ${rec.clientId.take(8)} · …${rec.requestId.takeLast(8)}",
                                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(rec.createdAt))
                                    Text(
                                        rec.error?.let { "$time · $it" } ?: "$time · ${rec.modelId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (rec.status == RequestStatus.FAILED) sc.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                StatusPill(rec.status.name, color)
                            }
                        }
                    }
                }
            }
        }
    }
}
