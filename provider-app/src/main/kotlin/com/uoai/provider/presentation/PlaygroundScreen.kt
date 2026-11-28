package com.uoai.provider.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uoai.asr.AsrModelCatalog
import com.uoai.asr.AsrModelState
import com.uoai.asr.engines.AsrEngineKind
import com.uoai.asr.engines.AsrOutputScript
import com.uoai.asr.engines.EngineComparisonEntry
import com.uoai.asr.engines.HinglishModelCatalog
import com.uoai.asr.engines.HinglishScriptConverter
import com.uoai.asr.engines.HinglishTranscript
import com.uoai.asr.engines.TextErrorMetrics
import com.uoai.inference.ModelState
import com.uoai.provider.ui.components.*
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

private enum class PlaygroundMode(val label: String, val icon: ImageVector) {
    LLM("LLM Chat", Icons.Rounded.AutoAwesome),
    ASR("Voice & Speech", Icons.Rounded.Mic),
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlaygroundTab(
    state: DashboardUiState,
    asrState: SpeechSettingsUiState = SpeechSettingsUiState(),
    liveMeterFlow: StateFlow<LiveRecordingMeter> = remember { MutableStateFlow(LiveRecordingMeter()) },
    onLoadModel: () -> Unit,
    onRunTest: (prompt: String, systemPrompt: String?, maxTokensOverride: Int?, tempOverride: Float?) -> Unit,
    onCancelTest: () -> Unit,
    onClearTest: () -> Unit,
    onLoadAsrModel: () -> Unit = {},
    onDownloadHinglishModel: (AsrEngineKind) -> Unit = {},
    onCancelHinglishDownload: () -> Unit = {},
    onUpdateOutputScript: (AsrOutputScript) -> Unit = {},
    onStartEngineRecording: (language: String, engine: VoiceCaptureEngine) -> Unit = { _, _ -> },
    onStopRecordingAndTranscribe: (language: String) -> Unit = {},
    onDiscardRecording: () -> Unit = {},
    onPickAudioForEngine: (language: String, engine: VoiceCaptureEngine) -> Unit = { _, _ -> },
    onCancelAsrTest: () -> Unit = {},
    onClearAsrTest: () -> Unit = {},
    onUnlockAsrLanguage: () -> Unit = {},
    onToggleAutoEnhance: (Boolean) -> Unit = {},
    onEnhanceTranscript: () -> Unit = {},
    onCancelEnhance: () -> Unit = {},
    onToggleCompareEngine: (AsrEngineKind) -> Unit = {},
    onUpdateCompareGroundTruth: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sc = ProviderThemeDefaults.statusColors
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    var mode by remember { mutableStateOf(PlaygroundMode.LLM) }
    var asrLang by remember { mutableStateOf("en,hi") }

    var showLlmOptions by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf("You are a concise, accurate offline assistant.") }
    var useSystemPrompt by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("Explain on-device AI in 3 concise bullet points.") }
    var selectedMaxTokens by remember { mutableStateOf<Int?>(null) }

    val llmReady = state.model.state == ModelState.READY || state.model.state == ModelState.GENERATING
    val llmCanRun = state.model.state == ModelState.READY
    val llmBusy = state.model.state in setOf(ModelState.LOADING, ModelState.DOWNLOADING, ModelState.VERIFYING, ModelState.UNLOADING)
    val activeCtx = state.model.loadedConfig?.contextWindowTokens ?: state.settings.contextWindowTokens
    val effectiveMaxOut = selectedMaxTokens ?: state.settings.maxOutputTokens

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val modes = PlaygroundMode.entries
            modes.forEachIndexed { idx, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { mode = m },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = modes.size),
                    icon = {
                        SegmentedButtonDefaults.Icon(active = mode == m) {
                            Icon(m.icon, contentDescription = null, modifier = Modifier.padding(1.dp))
                        }
                    },
                ) {
                    Text(m.label, maxLines = 1)
                }
            }
        }

        if (mode == PlaygroundMode.ASR) {
            VoicePlaygroundContent(
                state = state,
                asrState = asrState,
                liveMeterFlow = liveMeterFlow,
                asrLang = asrLang,
                llmReady = llmReady,
                llmBusy = llmBusy,
                onSelectLanguage = { asrLang = it },
                onLoadModel = onLoadModel,
                onLoadAsrModel = onLoadAsrModel,
                onDownloadHinglishModel = onDownloadHinglishModel,
                onCancelHinglishDownload = onCancelHinglishDownload,
                onUpdateOutputScript = onUpdateOutputScript,
                onStartEngineRecording = onStartEngineRecording,
                onStopRecordingAndTranscribe = onStopRecordingAndTranscribe,
                onDiscardRecording = onDiscardRecording,
                onPickAudioForEngine = onPickAudioForEngine,
                onCancelAsrTest = onCancelAsrTest,
                onClearAsrTest = onClearAsrTest,
                onUnlockAsrLanguage = onUnlockAsrLanguage,
                onToggleAutoEnhance = onToggleAutoEnhance,
                onEnhanceTranscript = onEnhanceTranscript,
                onCancelEnhance = onCancelEnhance,
                onToggleCompareEngine = onToggleCompareEngine,
                onUpdateCompareGroundTruth = onUpdateCompareGroundTruth,
                onCopy = { clipboard.setText(AnnotatedString(it)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            return@Column
        }

        // ---- LLM Playground (Clean 2-card layout + collapsible options + Markdown output) ----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = state.model.model?.name ?: "No LLM Installed",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (llmReady) "Ready · ${formatTokenShort(activeCtx)} ctx · max ${formatTokenShort(effectiveMaxOut)}"
                            else if (state.modelInstalled) "Installed — tap Load to test"
                            else "Download a model in Models -> LLM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (llmReady) {
                        StatusPill("READY", sc.ready)
                    } else if (state.modelInstalled) {
                        FilledTonalButton(
                            onClick = onLoadModel,
                            enabled = !llmBusy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text(if (llmBusy) "Loading…" else "Load", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        StatusPill("UNINSTALLED", sc.neutral)
                    }
                }
            }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("Ask anything offline…") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { showLlmOptions = !showLlmOptions },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(
                                if (showLlmOptions) Icons.Rounded.ExpandLess else Icons.Rounded.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showLlmOptions) "Hide Options" else "Options")
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.testRunning) {
                                FilledTonalButton(
                                    onClick = onCancelTest,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = sc.errorContainer,
                                        contentColor = sc.error,
                                    ),
                                ) {
                                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Stop")
                                }
                            } else {
                                if (state.testOutput.isNotEmpty()) {
                                    IconButton(onClick = onClearTest) {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear output")
                                    }
                                }
                                Button(
                                    onClick = {
                                        onRunTest(prompt, if (useSystemPrompt) systemPrompt else null, effectiveMaxOut, null)
                                    },
                                    enabled = llmCanRun && prompt.isNotBlank(),
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Run")
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = showLlmOptions) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("System Prompt", style = MaterialTheme.typography.labelLarge)
                                Switch(checked = useSystemPrompt, onCheckedChange = { useSystemPrompt = it })
                            }
                            AnimatedVisibility(visible = useSystemPrompt) {
                                OutlinedTextField(
                                    value = systemPrompt,
                                    onValueChange = { systemPrompt = it },
                                    label = { Text("System instruction") },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text("Max Output Override", style = MaterialTheme.typography.labelLarge)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(
                                    selected = selectedMaxTokens == null,
                                    onClick = { selectedMaxTokens = null },
                                    label = { Text("Default (${formatTokenShort(state.settings.maxOutputTokens)})") },
                                )
                                listOf(256, 1024, 4096, 8192, 32768).forEach { cap ->
                                    FilterChip(
                                        selected = selectedMaxTokens == cap,
                                        onClick = { selectedMaxTokens = cap },
                                        label = { Text(formatTokenShort(cap)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.testRunning || state.testOutput.isNotEmpty()) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Response", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = buildString {
                                        if (state.testRunning) append("Streaming…")
                                        else if (state.testElapsedMs != null) append(String.format(Locale.US, "%.2fs", state.testElapsedMs / 1000f))
                                        if (state.testChunks > 0) {
                                            append(" · ${state.testChunks} chunks")
                                            state.testTokensPerSec?.let { append(String.format(Locale.US, " (%.1f/s)", it)) }
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.testOutput.isNotEmpty()) {
                                IconButton(onClick = { clipboard.setText(AnnotatedString(state.testOutput)) }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy response")
                                }
                            }
                        }

                        if (state.testRunning) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        if (state.testOutput.isNotEmpty()) {
                            MarkdownText(
                                markdown = state.testOutput,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePlaygroundContent(
    state: DashboardUiState,
    asrState: SpeechSettingsUiState,
    liveMeterFlow: StateFlow<LiveRecordingMeter>,
    asrLang: String,
    llmReady: Boolean,
    llmBusy: Boolean,
    onSelectLanguage: (String) -> Unit,
    onLoadModel: () -> Unit,
    onLoadAsrModel: () -> Unit,
    onDownloadHinglishModel: (AsrEngineKind) -> Unit,
    onCancelHinglishDownload: () -> Unit,
    onUpdateOutputScript: (AsrOutputScript) -> Unit,
    onStartEngineRecording: (String, VoiceCaptureEngine) -> Unit,
    onStopRecordingAndTranscribe: (String) -> Unit,
    onDiscardRecording: () -> Unit,
    onPickAudioForEngine: (String, VoiceCaptureEngine) -> Unit,
    onCancelAsrTest: () -> Unit,
    onClearAsrTest: () -> Unit,
    onUnlockAsrLanguage: () -> Unit,
    onToggleAutoEnhance: (Boolean) -> Unit,
    onEnhanceTranscript: () -> Unit,
    onCancelEnhance: () -> Unit,
    onToggleCompareEngine: (AsrEngineKind) -> Unit,
    onUpdateCompareGroundTruth: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sc = ProviderThemeDefaults.statusColors
    val asrSt = asrState.status
    val hSt = asrState.hinglish
    val pg = asrState.playground
    val outputScript = asrState.settings.outputScript

    var selectedEngine by remember(asrState.settings.selectedEngine) {
        mutableStateOf(VoiceCaptureEngine.fromKind(asrState.settings.selectedEngine))
    }
    var showLanguagePicker by remember { mutableStateOf(false) }

    val whisperReady = asrSt.isLoaded || (asrSt.isSelectedInstalled && asrState.settings.autoLoadOnRequest)
    val llmAudioAvailable = llmReady && state.model.supportsAudioInput
    val llmCanTranscribe = (llmReady || state.modelInstalled) && !llmBusy
    val isCompareMode = selectedEngine == VoiceCaptureEngine.COMPARE
    val isLlmCapture = pg.captureEngine == VoiceCaptureEngine.LLM_DIRECT
    val isCactusSelected = selectedEngine in setOf(
        VoiceCaptureEngine.AKSHAR_V4,
        VoiceCaptureEngine.AKSHAR_LITE_V4,
        VoiceCaptureEngine.SHRUTI_HI,
    )
    val selectedCactusKind = selectedEngine.toAsrEngineKindOrNull()?.takeIf { it != AsrEngineKind.WHISPER }
    val selectedCactusSpec = selectedCactusKind?.let { HinglishModelCatalog.specFor(it) }
    val selectedCactusInstalled = selectedCactusKind?.let { hSt.isInstalled(it) } == true
    val selectedEngineReady = when (selectedEngine) {
        VoiceCaptureEngine.WHISPER_ASR -> whisperReady
        VoiceCaptureEngine.AKSHAR_V4, VoiceCaptureEngine.AKSHAR_LITE_V4, VoiceCaptureEngine.SHRUTI_HI -> selectedCactusInstalled
        VoiceCaptureEngine.COMPARE -> whisperReady || hSt.installed.isNotEmpty()
        VoiceCaptureEngine.LLM_DIRECT -> llmCanTranscribe
    }

    val hasSingleResults = !isCompareMode && (
        pg.recording || pg.running || pg.chunks.isNotEmpty() || pg.streamingChunkText.isNotEmpty() ||
            pg.error != null || pg.enhanceRunning || pg.enhancedOutput.isNotEmpty() || pg.enhanceError != null
        )
    val hasCompareResults = isCompareMode && (pg.compareRunning || pg.compareResults.isNotEmpty() || pg.error != null)

    if (showLanguagePicker) {
        AsrLanguagePickerDialog(
            selectedCode = asrLang,
            onSelect = { code ->
                onSelectLanguage(code)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var hasUnreadBelow by remember { mutableStateOf(false) }

    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= total - 2 || !listState.canScrollForward
        }
    }

    LaunchedEffect(pg.chunks.size) {
        if (pg.chunks.isEmpty() || isCompareMode) {
            hasUnreadBelow = false
            return@LaunchedEffect
        }
        if (isNearBottom && !listState.isScrollInProgress) {
            val targetIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetIdx)
        } else if (!isNearBottom) {
            hasUnreadBelow = true
        }
    }

    LaunchedEffect(isNearBottom) {
        if (isNearBottom) hasUnreadBelow = false
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 1. Compact Engine & LLM Readiness Strip
            item(key = "readiness", contentType = "readiness") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = when (selectedEngine) {
                                        VoiceCaptureEngine.WHISPER_ASR -> asrSt.activePreset.displayName
                                        VoiceCaptureEngine.COMPARE -> "Compare Mode (${pg.compareSelectedEngines.size} engines)"
                                        else -> selectedCactusSpec?.displayName ?: selectedEngine.label
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        pg.recording && !isLlmCapture -> "Recording · ${pg.captureEngine.label} active"
                                        isCompareMode -> "Runs identical audio sequentially across selected engines"
                                        selectedCactusKind != null -> when {
                                            hSt.isLoaded(selectedCactusKind) -> "Cactus 4.0 · Loaded (~${selectedCactusSpec?.approxRamMb ?: 80} MB RAM)"
                                            selectedCactusInstalled -> "Installed · auto-loads on capture (~${selectedCactusSpec?.approxRamMb ?: 80} MB RAM)"
                                            hSt.downloadingKind == selectedCactusKind -> "Downloading ${selectedCactusSpec?.displayName}…"
                                            else -> "Not downloaded (${formatFileSize(selectedCactusSpec?.exactSizeBytes ?: 0L)})"
                                        }
                                        asrSt.isLoaded -> "${asrSt.activePreset.profile.displayName} · Loaded & ready"
                                        asrSt.isSelectedInstalled && asrState.settings.autoLoadOnRequest -> "Installed · auto-loads on capture"
                                        asrSt.isSelectedInstalled -> "Installed — tap Load to test"
                                        else -> "Download in Models -> Speech (ASR)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            when {
                                pg.recording && !isLlmCapture -> StatusPill("LIVE", sc.error)
                                isCompareMode -> StatusPill("COMPARE", sc.ready)
                                selectedCactusKind != null -> when {
                                    hSt.isLoaded(selectedCactusKind) -> StatusPill("READY", sc.ready)
                                    hSt.downloadingKind == selectedCactusKind -> TextButton(onClick = onCancelHinglishDownload) { Text("Cancel") }
                                    hSt.verifyingKind == selectedCactusKind || hSt.loadingKind == selectedCactusKind -> StatusPill("BUSY", sc.warning)
                                    selectedCactusInstalled -> StatusPill("INSTALLED", sc.ready)
                                    else -> FilledTonalButton(
                                        onClick = { onDownloadHinglishModel(selectedCactusKind) },
                                        enabled = hSt.downloadingKind == null,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp),
                                    ) { Text("Get", style = MaterialTheme.typography.labelLarge) }
                                }
                                asrSt.isLoaded -> StatusPill("READY", sc.ready)
                                asrSt.isSelectedInstalled -> FilledTonalButton(
                                    onClick = onLoadAsrModel,
                                    enabled = asrSt.state != AsrModelState.LOADING,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text(if (asrSt.state == AsrModelState.LOADING) "Loading…" else "Load", style = MaterialTheme.typography.labelLarge)
                                }
                                else -> StatusPill("UNINSTALLED", sc.neutral)
                            }
                        }

                        if (selectedCactusKind != null && hSt.downloadingKind == selectedCactusKind) {
                            val prog = hSt.downloadProgress
                            if (prog != null) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = state.model.model?.name ?: "No LLM Installed",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        pg.recording && isLlmCapture -> "Chunked FIFO queue · direct LLM audio"
                                        llmAudioAvailable -> "Native audio ready · direct LLM transcription"
                                        llmReady -> "Text-only LLM loaded (enhances ASR)"
                                        state.modelInstalled -> "Installed · auto-loads on LLM Transcribe"
                                        else -> "Install Gemma 4 in Models -> LLM"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            when {
                                pg.recording && isLlmCapture -> StatusPill("LLM LIVE", sc.error)
                                llmAudioAvailable -> StatusPill("AUDIO READY", sc.ready)
                                llmReady -> StatusPill("TEXT ONLY", sc.neutral)
                                state.modelInstalled -> FilledTonalButton(
                                    onClick = onLoadModel,
                                    enabled = !llmBusy,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text(if (llmBusy) "Loading…" else "Load", style = MaterialTheme.typography.labelLarge)
                                }
                                else -> StatusPill("UNINSTALLED", sc.neutral)
                            }
                        }
                    }
                }
            }

            // 2. Capture Controls Card (Engine selector + Script selector + Language + Actions)
            item(key = "capture", contentType = "capture") {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Engine Selector Row
                        Text("ASR Engine", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                VoiceCaptureEngine.WHISPER_ASR,
                                VoiceCaptureEngine.AKSHAR_V4,
                                VoiceCaptureEngine.AKSHAR_LITE_V4,
                                VoiceCaptureEngine.SHRUTI_HI,
                                VoiceCaptureEngine.COMPARE,
                            ).forEach { eng ->
                                val installedDot = when (eng) {
                                    VoiceCaptureEngine.WHISPER_ASR -> asrSt.isSelectedInstalled
                                    VoiceCaptureEngine.AKSHAR_V4 -> hSt.isInstalled(AsrEngineKind.AKSHAR_V4)
                                    VoiceCaptureEngine.AKSHAR_LITE_V4 -> hSt.isInstalled(AsrEngineKind.AKSHAR_LITE_V4)
                                    VoiceCaptureEngine.SHRUTI_HI -> hSt.isInstalled(AsrEngineKind.SHRUTI_HI)
                                    VoiceCaptureEngine.COMPARE -> true
                                    VoiceCaptureEngine.LLM_DIRECT -> llmCanTranscribe
                                }
                                FilterChip(
                                    selected = selectedEngine == eng,
                                    enabled = !pg.recording && !pg.running,
                                    onClick = { selectedEngine = eng },
                                    leadingIcon = if (eng == VoiceCaptureEngine.COMPARE) {
                                        { Icon(Icons.Rounded.CompareArrows, null, Modifier.size(16.dp)) }
                                    } else if (installedDot && eng != VoiceCaptureEngine.WHISPER_ASR) {
                                        { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp)) }
                                    } else null,
                                    label = { Text(eng.label, maxLines = 1) },
                                )
                            }
                        }

                        // Output Script Selector Row (instant switching without re-transcribing)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Script:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AsrOutputScript.entries.forEach { script ->
                                FilterChip(
                                    selected = outputScript == script,
                                    onClick = { onUpdateOutputScript(script) },
                                    label = { Text(script.shortLabel, maxLines = 1) },
                                )
                            }
                        }

                        // Compare Mode engine checkboxes
                        if (isCompareMode) {
                            Text("Engines to Compare", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AsrEngineKind.entries.forEach { kind ->
                                    val checked = kind in pg.compareSelectedEngines
                                    val isAvail = if (kind == AsrEngineKind.WHISPER) asrSt.isSelectedInstalled else hSt.isInstalled(kind)
                                    FilterChip(
                                        selected = checked,
                                        enabled = !pg.recording && !pg.running,
                                        onClick = { onToggleCompareEngine(kind) },
                                        leadingIcon = {
                                            Icon(
                                                if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        label = {
                                            Text(
                                                if (isAvail) kind.shortLabel else "${kind.shortLabel} (get)",
                                                maxLines = 1,
                                            )
                                        },
                                    )
                                }
                            }
                        } else {
                            // Language Chips
                            val quickLangs = if (isCactusSelected) {
                                listOf("hi" to "Hinglish / HI", "en" to "English")
                            } else {
                                listOf(
                                    "en,hi" to "EN + HI",
                                    "auto" to "Auto (Lock)",
                                    "en" to "English",
                                    "hi" to "Hindi",
                                )
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                quickLangs.forEach { (code, label) ->
                                    FilterChip(
                                        selected = asrLang == code || (isCactusSelected && code == "hi" && asrLang != "en"),
                                        enabled = !pg.recording && !pg.running,
                                        onClick = { onSelectLanguage(code) },
                                        label = { Text(label, maxLines = 1) },
                                    )
                                }
                                if (!isCactusSelected) {
                                    val isCustomLang = quickLangs.none { it.first == asrLang }
                                    FilterChip(
                                        selected = isCustomLang,
                                        enabled = !pg.recording && !pg.running,
                                        onClick = { showLanguagePicker = true },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Translate, null, Modifier.size(16.dp))
                                        },
                                        label = {
                                            Text(
                                                if (isCustomLang) AsrModelCatalog.displayNameForLanguage(asrLang) else "More…",
                                                maxLines = 1,
                                            )
                                        },
                                    )
                                }
                                FilterChip(
                                    selected = pg.autoEnhance,
                                    onClick = { onToggleAutoEnhance(!pg.autoEnhance) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(16.dp))
                                    },
                                    label = { Text("Auto-enhance", maxLines = 1) },
                                )
                            }
                        }

                        if (pg.recording) {
                            LiveRecordingBanner(
                                liveMeterFlow = liveMeterFlow,
                                activeEngine = pg.captureEngine,
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onStopRecordingAndTranscribe(asrLang) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (pg.captureEngine == VoiceCaptureEngine.COMPARE) "Stop & Run Compare" else "Finish & Drain Queue",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(onClick = onDiscardRecording) {
                                    Text("Discard")
                                }
                            }
                        } else {
                            // Download prompt inline if user selected an uninstalled Hinglish engine
                            if (selectedCactusKind != null && !selectedCactusInstalled && selectedCactusSpec != null) {
                                Button(
                                    onClick = { onDownloadHinglishModel(selectedCactusKind) },
                                    enabled = hSt.downloadingKind == null && hSt.verifyingKind == null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Download ${selectedCactusSpec.displayName} (${formatFileSize(selectedCactusSpec.exactSizeBytes)})",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                // Primary ASR / Compare action row
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onStartEngineRecording(asrLang, selectedEngine) },
                                        enabled = selectedEngineReady && !pg.running,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            if (isCompareMode) Icons.Rounded.CompareArrows else Icons.Rounded.Mic,
                                            null,
                                            Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (isCompareMode) "Record & Compare" else "Live ${selectedEngine.label}",
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onPickAudioForEngine(asrLang, selectedEngine) },
                                        enabled = selectedEngineReady && !pg.running,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    ) {
                                        Icon(Icons.Rounded.AudioFile, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("File", maxLines = 1, softWrap = false)
                                    }
                                }
                            }

                            // Preserved Gemma 4 Direct LLM Transcribe Row
                            if (!isCompareMode) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = { onStartEngineRecording(asrLang, VoiceCaptureEngine.LLM_DIRECT) },
                                        enabled = llmCanTranscribe && !pg.running,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("LLM Transcribe", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    }
                                    OutlinedButton(
                                        onClick = { onPickAudioForEngine(asrLang, VoiceCaptureEngine.LLM_DIRECT) },
                                        enabled = llmCanTranscribe && !pg.running,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    ) {
                                        Icon(Icons.Rounded.AudioFile, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("File", maxLines = 1, softWrap = false)
                                    }
                                }
                            }

                            if (pg.running) {
                                FilledTonalButton(
                                    onClick = onCancelAsrTest,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = sc.errorContainer,
                                        contentColor = sc.error,
                                    ),
                                ) {
                                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (pg.compareRunning) "Cancel Comparison" else "Cancel Queue")
                                }
                            }
                        }
                    }
                }
            }

            // 3A. Multi-Engine Comparison Results & Manual Ground-Truth Evaluation
            if (hasCompareResults) {
                item(key = "compare_header", contentType = "compare_header") {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Engine Comparison", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = if (pg.compareRunning) {
                                            "Benchmarking ${pg.compareResults.size}/${pg.compareSelectedEngines.size} engines on identical audio…"
                                        } else {
                                            val dur = pg.compareAudioDurationSec?.let { String.format(Locale.US, "%.1fs audio · ", it) } ?: ""
                                            "${dur}${pg.compareResults.size} engines evaluated · Script: ${outputScript.shortLabel}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!pg.compareRunning) {
                                    IconButton(onClick = onClearAsrTest) {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear comparison")
                                    }
                                }
                            }
                            if (pg.compareRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            pg.error?.let { err ->
                                Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                                    Text(err, color = sc.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(10.dp))
                                }
                            }
                            OutlinedTextField(
                                value = pg.compareGroundTruth,
                                onValueChange = onUpdateCompareGroundTruth,
                                label = { Text("Manual Ground Truth (optional · computes WER / CER)") },
                                placeholder = { Text("Enter or edit the exact reference transcript…") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                items(
                    items = pg.compareResults,
                    key = { "cmp_${it.kind.name}" },
                    contentType = { "compare_entry" },
                ) { entry ->
                    ComparisonEntryCard(
                        entry = entry,
                        outputScript = outputScript,
                        groundTruth = pg.compareGroundTruth,
                        onCopy = onCopy,
                        onSeedGroundTruth = onUpdateCompareGroundTruth,
                    )
                }
            }

            // 3B. Incremental Single-Engine Chunked Transcript Feed
            if (hasSingleResults) {
                item(key = "transcript_header", contentType = "transcript_header") {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        when {
                                            isLlmCapture && (pg.recording || pg.running) -> "Live LLM Transcript"
                                            isLlmCapture -> "LLM Direct Transcript"
                                            pg.recording || pg.running -> "Live ${pg.captureEngine.label} Transcript"
                                            else -> "${pg.captureEngine.label} Transcript · ${outputScript.shortLabel}"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    LiveQueueSubtitle(
                                        liveMeterFlow = liveMeterFlow,
                                        pg = pg,
                                        llmName = state.model.model?.name ?: "LLM",
                                    )
                                }
                                Row {
                                    if (pg.chunks.isNotEmpty()) {
                                        IconButton(onClick = { onCopy(pg.fullTranscriptFor(outputScript)) }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy transcript")
                                        }
                                    }
                                    if (!pg.recording && !pg.running && !pg.enhanceRunning) {
                                        IconButton(onClick = onClearAsrTest) {
                                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear results")
                                        }
                                    }
                                }
                            }

                            LiveQueueProgressBar(liveMeterFlow = liveMeterFlow, running = pg.running)

                            pg.lockedLanguage?.let { locked ->
                                InputChip(
                                    selected = true,
                                    onClick = onUnlockAsrLanguage,
                                    leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)) },
                                    trailingIcon = { Icon(Icons.Rounded.Close, "Unlock language", Modifier.size(16.dp)) },
                                    label = {
                                        Text(
                                            "Auto-locked: ${AsrModelCatalog.displayNameForLanguage(locked)} ($locked) · tap to unlock",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }

                            pg.error?.let { err ->
                                Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                                    Text(err, color = sc.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(10.dp))
                                }
                            }

                            if (pg.chunks.isEmpty() && pg.streamingChunkText.isEmpty() && (pg.recording || pg.running)) {
                                Text(
                                    text = "Start speaking — completed chunks append in order without rewriting earlier text…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                items(
                    items = pg.chunks,
                    key = { it.sequenceId },
                    contentType = { "transcript_chunk" },
                ) { chunk ->
                    TranscriptChunkRow(chunk = chunk, outputScript = outputScript)
                }

                if (pg.streamingChunkText.isNotEmpty()) {
                    item(key = "streaming_chunk", contentType = "streaming_chunk") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Transcribing next chunk…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = pg.streamingChunkText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if ((!isLlmCapture && pg.chunks.isNotEmpty() && !pg.recording && !pg.running) ||
                    pg.enhanceRunning || pg.enhancedOutput.isNotEmpty() || pg.enhanceError != null
                ) {
                    item(key = "enhance_section", contentType = "enhance_section") {
                        EnhanceTranscriptCard(
                            pg = pg,
                            isLlmMode = isLlmCapture,
                            llmReady = llmReady,
                            llmBusy = llmBusy,
                            modelInstalled = state.modelInstalled,
                            llmName = state.model.model?.name ?: "LLM",
                            onEnhanceTranscript = onEnhanceTranscript,
                            onLoadModel = onLoadModel,
                            onCancelEnhance = onCancelEnhance,
                            onCopy = onCopy,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = hasUnreadBelow && !isCompareMode,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    hasUnreadBelow = false
                    coroutineScope.launch {
                        val targetIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        listState.animateScrollToItem(targetIdx)
                    }
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New text", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LiveRecordingBanner(
    liveMeterFlow: StateFlow<LiveRecordingMeter>,
    activeEngine: VoiceCaptureEngine,
) {
    val sc = ProviderThemeDefaults.statusColors
    val meter by liveMeterFlow.collectAsStateWithLifecycle()
    val totalSec = meter.elapsedMs / 1000L

    Surface(
        color = sc.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (activeEngine == VoiceCaptureEngine.LLM_DIRECT) Icons.Rounded.AutoAwesome else Icons.Rounded.GraphicEq,
                        null,
                        tint = sc.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L),
                        style = MaterialTheme.typography.titleSmall,
                        color = sc.error,
                    )
                    Text("· ${activeEngine.label}", style = MaterialTheme.typography.labelMedium, color = sc.error)
                }
                Text(
                    text = when {
                        activeEngine == VoiceCaptureEngine.COMPARE -> "Capturing shared audio…"
                        meter.processingChunkSeq != null -> "Chunk #${meter.processingChunkSeq!! + 1} · Q:${meter.queuedChunks}"
                        meter.queuedChunks > 0 -> "Queued: ${meter.queuedChunks}"
                        else -> "Listening…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = sc.error,
                )
            }
            LinearProgressIndicator(
                progress = { meter.amplitude.coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = sc.error,
            )
            Text(
                text = if (activeEngine == VoiceCaptureEngine.COMPARE) {
                    "Shared audio buffer: ${meter.bufferedAudioKb} KB (1 mic capture for all engines)"
                } else {
                    "Audio RAM: ${meter.bufferedAudioKb} KB · Evicted: ${meter.freedAudioKb} KB · Done: ${meter.completedChunks}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = sc.error.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun LiveQueueSubtitle(
    liveMeterFlow: StateFlow<LiveRecordingMeter>,
    pg: AsrPlaygroundState,
    llmName: String,
) {
    val meter by liveMeterFlow.collectAsStateWithLifecycle()
    val text = buildString {
        when {
            pg.recording -> {
                if (meter.processingChunkSeq != null) {
                    append("Processing chunk #${meter.processingChunkSeq!! + 1}")
                    if (meter.queuedChunks > 0) append(" · ${meter.queuedChunks} queued")
                } else if (meter.queuedChunks > 0) {
                    append("${meter.queuedChunks} chunk(s) queued")
                } else {
                    append("Appending chunks in order · existing text locked")
                }
                pg.detectedLanguage?.let { append(" · $it") }
            }
            pg.running -> {
                if (meter.processingChunkSeq != null) {
                    append("Draining queue: chunk #${meter.processingChunkSeq!! + 1}")
                    if (meter.queuedChunks > 0) append(" (${meter.queuedChunks} left)")
                } else {
                    append(
                        if (pg.captureEngine == VoiceCaptureEngine.LLM_DIRECT) "Transcribing with $llmName…"
                        else "Finalizing queued chunks…",
                    )
                }
            }
            else -> {
                append("${pg.chunks.size} chunk${if (pg.chunks.size == 1) "" else "s"}")
                if (pg.totalAudioDurationSec > 0.0) {
                    append(String.format(Locale.US, " · %.1fs audio", pg.totalAudioDurationSec))
                    if (pg.totalTranscribeMs > 0L) {
                        val rtf = (pg.totalTranscribeMs / 1000.0) / pg.totalAudioDurationSec
                        append(String.format(Locale.US, " (%.2fx)", rtf))
                    }
                }
                pg.detectedLanguage?.let { append(" · $it") }
                if (pg.totalFreedAudioKb > 0) append(" · freed ${pg.totalFreedAudioKb} KB")
            }
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LiveQueueProgressBar(
    liveMeterFlow: StateFlow<LiveRecordingMeter>,
    running: Boolean,
) {
    val meter by liveMeterFlow.collectAsStateWithLifecycle()
    val active = running && (meter.processingChunkSeq != null || meter.queuedChunks > 0)
    if (active) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HinglishTranscriptBody(
    transcript: HinglishTranscript,
    outputScript: AsrOutputScript,
) {
    SelectionContainer {
        when (outputScript) {
            AsrOutputScript.DEVANAGARI_HINGLISH -> Text(
                text = transcript.devanagariHinglish,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AsrOutputScript.ROMAN_HINGLISH -> Text(
                text = transcript.romanHinglish,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AsrOutputScript.BOTH -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = transcript.devanagariHinglish,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (transcript.romanHinglish.isNotBlank() && transcript.romanHinglish != transcript.devanagariHinglish) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = transcript.romanHinglish,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptChunkRow(
    chunk: TranscriptChunkItem,
    outputScript: AsrOutputScript,
) {
    val alpha = remember(chunk.sequenceId) { Animatable(0.35f) }
    LaunchedEffect(chunk.sequenceId) {
        alpha.animateTo(1f, animationSpec = tween(durationMillis = 180))
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha.value },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "#${chunk.sequenceId + 1} · ${chunk.engine.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1fs audio · %.2fs",
                        chunk.audioDurationSec,
                        chunk.transcribeElapsedMs / 1000f,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HinglishTranscriptBody(transcript = chunk.transcript, outputScript = outputScript)
        }
    }
}

@Composable
private fun ComparisonEntryCard(
    entry: EngineComparisonEntry,
    outputScript: AsrOutputScript,
    groundTruth: String,
    onCopy: (String) -> Unit,
    onSeedGroundTruth: (String) -> Unit,
) {
    val sc = ProviderThemeDefaults.statusColors
    val displayText = entry.transcript?.forScript(outputScript).orEmpty()
    val metrics = remember(groundTruth, displayText, outputScript, entry.transcript) {
        val ref = groundTruth.trim()
        val hyp = entry.transcript?.let { t ->
            when (outputScript) {
                AsrOutputScript.ROMAN_HINGLISH -> t.romanHinglish
                AsrOutputScript.DEVANAGARI_HINGLISH -> t.devanagariHinglish
                AsrOutputScript.BOTH -> if (HinglishScriptConverter.containsDevanagari(ref)) t.devanagariHinglish else t.romanHinglish
            }
        }
        if (ref.isNotEmpty() && hyp != null) TextErrorMetrics.compute(ref, hyp) else null
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
                    val meta = buildString {
                        entry.loadTimeMs?.let { append("Load ${it}ms") }
                        entry.inferenceDurationMs?.let {
                            if (isNotEmpty()) append(" · ")
                            append("Decode ${it}ms")
                        }
                        entry.rtf?.let { append(String.format(Locale.US, " (%.2fx)", it)) }
                        entry.peakRssMb?.let { append(" · RSS ${it}MB") }
                        entry.detectedLanguage?.let { append(" · $it") }
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    !entry.available -> StatusPill("MISSING", sc.neutral)
                    entry.error != null -> StatusPill("ERROR", sc.error)
                    metrics != null -> StatusPill(
                        String.format(Locale.US, "WER %.1f%%", metrics.werPercent),
                        if (metrics.werPercent <= 15.0) sc.ready else sc.warning,
                    )
                    else -> StatusPill("DONE", sc.ready)
                }
            }

            if (metrics != null) {
                Text(
                    text = String.format(
                        Locale.US,
                        "vs Ground Truth · WER: %.1f%% (%d/%d words) · CER: %.1f%%",
                        metrics.werPercent,
                        metrics.wordEdits,
                        metrics.refWordCount,
                        metrics.cerPercent,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            entry.error?.let { err ->
                Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(err, color = sc.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(8.dp))
                }
            }

            entry.transcript?.let { tr ->
                if (tr.canonicalText.isNotEmpty()) {
                    HinglishTranscriptBody(transcript = tr, outputScript = outputScript)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                val seed = when (outputScript) {
                                    AsrOutputScript.ROMAN_HINGLISH -> tr.romanHinglish
                                    AsrOutputScript.DEVANAGARI_HINGLISH, AsrOutputScript.BOTH -> tr.devanagariHinglish
                                }
                                onSeedGroundTruth(seed)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text("Use as Ground Truth") }
                        IconButton(onClick = { onCopy(displayText) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy result", modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    Text("(empty transcript)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EnhanceTranscriptCard(
    pg: AsrPlaygroundState,
    isLlmMode: Boolean,
    llmReady: Boolean,
    llmBusy: Boolean,
    modelInstalled: Boolean,
    llmName: String,
    onEnhanceTranscript: () -> Unit,
    onLoadModel: () -> Unit,
    onCancelEnhance: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val sc = ProviderThemeDefaults.statusColors
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isLlmMode && pg.chunks.isNotEmpty() && !pg.recording && !pg.running) {
                if (llmReady) {
                    FilledTonalButton(
                        onClick = onEnhanceTranscript,
                        enabled = !pg.enhanceRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (pg.enhanceRunning) "Enhancing…" else "Enhance Transcript with LLM")
                    }
                } else if (modelInstalled) {
                    OutlinedButton(
                        onClick = onLoadModel,
                        enabled = !llmBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Memory, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (llmBusy) "Loading LLM…" else "Load LLM to Enhance")
                    }
                }
            }

            if (pg.enhanceRunning || pg.enhancedOutput.isNotEmpty() || pg.enhanceError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Enhanced Transcript (Dialect-Aware)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (pg.enhanceRunning) "Refining dialect & punctuation with $llmName…"
                            else pg.enhanceElapsedMs?.let { String.format(Locale.US, "Enhanced in %.2fs · preserves dialect & full meaning", it / 1000f) } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pg.enhanceRunning) {
                        TextButton(onClick = onCancelEnhance) { Text("Stop") }
                    } else if (pg.enhancedOutput.isNotEmpty()) {
                        IconButton(onClick = { onCopy(pg.enhancedOutput) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy enhanced transcript")
                        }
                    }
                }

                if (pg.enhanceRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                pg.enhanceError?.let { err ->
                    Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(err, color = sc.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (!llmReady && modelInstalled) {
                                TextButton(onClick = onLoadModel, enabled = !llmBusy) { Text("Load LLM") }
                            }
                        }
                    }
                }

                if (pg.enhancedOutput.isNotEmpty()) {
                    MarkdownText(
                        markdown = pg.enhancedOutput,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
