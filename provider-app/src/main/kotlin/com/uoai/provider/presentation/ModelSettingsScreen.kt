package com.uoai.provider.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.uoai.inference.*
import com.uoai.provider.ui.components.*
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import java.util.Locale
import kotlin.math.roundToInt

private enum class ModelSourceFilter(val label: String) {
    GEMMA_4("Gemma 4 (${ModelCatalog.byFamily(ModelFamily.GEMMA_4).size})"),
    GEMMA_3("Gemma 3 (${ModelCatalog.byFamily(ModelFamily.GEMMA_3).size})"),
    CUSTOM("Custom"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelSettingsTab(
    state: DashboardUiState,
    onUpdateContextWindow: (Int) -> Unit,
    onUpdateMaxOutputTokens: (Int) -> Unit,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateTopP: (Float) -> Unit,
    onUpdateTopK: (Int) -> Unit,
    onUpdateBackend: (AcceleratorBackend) -> Unit,
    onUpdateHuggingFaceSource: (String, String) -> Unit,
    onResetSettings: () -> Unit,
    onLoad: () -> Unit,
    onReload: () -> Unit,
    onUnload: () -> Unit,
    onDeleteModel: () -> Unit,
    onSelectLocalModel: (String) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sc = ProviderThemeDefaults.statusColors
    val s = state.model.state
    val busy = s in setOf(ModelState.DOWNLOADING, ModelState.VERIFYING, ModelState.LOADING, ModelState.UNLOADING, ModelState.GENERATING)
    val cfg = state.settings

    var showCustomTokenDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var installExpanded by remember(state.modelInstalled) { mutableStateOf(!state.modelInstalled) }
    var sourceFilter by remember { mutableStateOf(ModelSourceFilter.GEMMA_4) }
    var hfTokenInput by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var showOptionalToken by remember { mutableStateOf(false) }
    var customRepo by remember(cfg.huggingFaceRepo) { mutableStateOf(cfg.huggingFaceRepo) }
    var customFile by remember(cfg.huggingFaceFile) { mutableStateOf(cfg.huggingFaceFile) }

    val requiresToken = ModelCatalog.requiresHfToken(cfg.huggingFaceRepo, cfg.huggingFaceFile)
    val selectedPreset = ModelCatalog.findPreset(cfg.huggingFaceRepo, cfg.huggingFaceFile)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Model Lifecycle Card
        val badgeColor = when {
            s == ModelState.READY || s == ModelState.GENERATING -> sc.ready
            s == ModelState.ERROR -> sc.error
            busy -> sc.warning
            else -> sc.neutral
        }
        val stateLabel = if (s == ModelState.NOT_INSTALLED && state.modelInstalled) "INSTALLED" else s.name
        SectionCard(
            title = "Runtime & Model Instance",
            icon = Icons.Rounded.SmartToy,
            subtitle = state.model.model?.let {
                "${it.name} · ${it.version ?: it.format?.uppercase() ?: "BUNDLE"} · ${formatFileSize(it.fileSizeBytes)}"
            } ?: "No local model installed",
            trailing = { StatusPill(stateLabel, badgeColor) },
        ) {
            state.model.loadedConfig?.let { lc ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Active Context: ${formatTokenShort(lc.contextWindowTokens)}") },
                        leadingIcon = { Icon(Icons.Rounded.Memory, null, Modifier.size(16.dp)) },
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text("Backend: ${lc.preferredBackend}") },
                    )
                }
            }

            state.model.loadProgress?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            } ?: if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else Unit

            state.model.errorMessage?.let { err ->
                Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(err, color = sc.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }

            if (state.requiresReload) {
                Surface(color = sc.warningContainer, shape = MaterialTheme.shapes.small) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Context/backend changed. Reload model to apply (${formatTokenShort(cfg.contextWindowTokens)}).",
                            color = sc.warning,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onReload, enabled = !busy) { Text("Apply & Reload") }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s == ModelState.READY || s == ModelState.GENERATING) {
                    Button(
                        onClick = onReload,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reload")
                    }
                    OutlinedButton(
                        onClick = onUnload,
                        enabled = s == ModelState.READY,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Unload")
                    }
                } else {
                    Button(
                        onClick = onLoad,
                        enabled = !busy && state.modelInstalled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (s == ModelState.LOADING) "Loading…" else "Load (${formatTokenShort(cfg.contextWindowTokens)})")
                    }
                }
                if (state.modelInstalled) {
                    IconButton(onClick = { showDeleteConfirm = true }, enabled = !busy) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete model", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // 2. Context Window & Max Output Tokens (up to 32K)
        SectionCard(
            title = "Context Window & Token Limits",
            icon = Icons.Rounded.Tune,
            subtitle = "Configure prompt + completion window up to 32K (32,768 tokens)",
            trailing = {
                TextButton(onClick = { showCustomTokenDialog = true }) { Text("Exact") }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Context Window (KV Cache)", style = MaterialTheme.typography.titleSmall)
                StatusPill(formatTokenFull(cfg.contextWindowTokens), MaterialTheme.colorScheme.primary)
            }

            TokenPresetRow(
                selected = cfg.contextWindowTokens,
                presets = InferenceSettings.CONTEXT_PRESETS,
                onSelect = onUpdateContextWindow,
            )

            Slider(
                value = cfg.contextWindowTokens.toFloat(),
                onValueChange = { raw ->
                    val snapped = ((raw / 512f).roundToInt() * 512)
                        .coerceIn(InferenceSettings.MIN_CONTEXT_TOKENS, InferenceSettings.MAX_TOKENS_32K)
                    onUpdateContextWindow(snapped)
                },
                valueRange = InferenceSettings.MIN_CONTEXT_TOKENS.toFloat()..InferenceSettings.MAX_TOKENS_32K.toFloat(),
            )

            if (cfg.contextWindowTokens > 4096) {
                Surface(color = sc.warningContainer, shape = MaterialTheme.shapes.small) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Rounded.Info, null, tint = sc.warning, modifier = Modifier.size(18.dp))
                        Text(
                            "High context (${formatTokenShort(cfg.contextWindowTokens)}) needs extra RAM (device currently has ${state.memory.deviceAvailMb} MB free). Gemma 4 supports up to 32K natively; some MediaPipe .task bundles cap KV cache at 2K–4K.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sc.warning,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Max Output Tokens (Default)", style = MaterialTheme.typography.titleSmall)
                StatusPill(formatTokenFull(cfg.maxOutputTokens), MaterialTheme.colorScheme.secondary)
            }

            TokenPresetRow(
                selected = cfg.maxOutputTokens,
                presets = InferenceSettings.OUTPUT_PRESETS,
                onSelect = onUpdateMaxOutputTokens,
            )

            Slider(
                value = cfg.maxOutputTokens.toFloat(),
                onValueChange = { raw ->
                    val snapped = ((raw / 256f).roundToInt() * 256)
                        .coerceIn(InferenceSettings.MIN_OUTPUT_TOKENS, InferenceSettings.MAX_TOKENS_32K)
                    onUpdateMaxOutputTokens(snapped)
                },
                valueRange = InferenceSettings.MIN_OUTPUT_TOKENS.toFloat()..InferenceSettings.MAX_TOKENS_32K.toFloat(),
            )
            Text(
                "Max output is synced so it never exceeds Context Window; selecting 16K or 32K expands Context Window automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 3. Sampling & Accelerator Backend
        SectionCard(
            title = "Sampling & Compute Backend",
            icon = Icons.Rounded.Speed,
            subtitle = "Defaults applied to Playground and API calls when omitted",
            trailing = {
                TextButton(onClick = onResetSettings) { Text("Reset") }
            },
        ) {
            Text("Preferred Accelerator", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val backends = AcceleratorBackend.entries
                backends.forEachIndexed { idx, backend ->
                    SegmentedButton(
                        selected = cfg.preferredBackend == backend,
                        onClick = { onUpdateBackend(backend) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = backends.size),
                    ) {
                        Text(backend.name, maxLines = 1)
                    }
                }
            }

            SliderSettingRow(
                label = "Temperature",
                valueLabel = String.format(Locale.US, "%.2f", cfg.temperature),
                value = cfg.temperature,
                range = 0f..2f,
                onValueChange = { onUpdateTemperature((it * 20f).roundToInt() / 20f) },
                helper = "Lower = deterministic, higher = more creative",
            )
            SliderSettingRow(
                label = "Top-P (Nucleus)",
                valueLabel = String.format(Locale.US, "%.2f", cfg.topP),
                value = cfg.topP,
                range = 0.05f..1.0f,
                onValueChange = { onUpdateTopP((it * 20f).roundToInt() / 20f) },
            )
            SliderSettingRow(
                label = "Top-K",
                valueLabel = cfg.topK.toString(),
                value = cfg.topK.toFloat(),
                range = 1f..InferenceSettings.MAX_TOP_K.toFloat(),
                onValueChange = { onUpdateTopK(it.roundToInt().coerceIn(1, InferenceSettings.MAX_TOP_K)) },
            )
        }

        // 4. Gemma 4 & Gemma 3 Model Catalog / Installer
        SectionCard(
            title = "Install or Switch Model",
            icon = Icons.Rounded.CloudDownload,
            subtitle = "Gemma 4 (LiteRT-LM, no token needed) & Gemma 3 · ${state.memory.availableStorageMb} MB free",
            trailing = {
                IconButton(onClick = { installExpanded = !installExpanded }) {
                    Icon(if (installExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Toggle install section")
                }
            },
        ) {
            if (state.localModels.isNotEmpty()) {
                Text("Downloaded Local Models", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.localModels.forEach { local ->
                        val active = state.model.model?.name == local.name && state.model.model?.format == local.format
                        FilterChip(
                            selected = active,
                            enabled = !busy,
                            onClick = { onSelectLocalModel("${local.name}.${local.format}") },
                            label = { Text("${local.name} (${formatFileSize(local.fileSizeBytes)})") },
                        )
                    }
                }
            }

            AnimatedVisibility(visible = installExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val tabs = ModelSourceFilter.entries
                        tabs.forEachIndexed { idx, tab ->
                            SegmentedButton(
                                selected = sourceFilter == tab,
                                onClick = { sourceFilter = tab },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                            ) {
                                Text(tab.label, maxLines = 1)
                            }
                        }
                    }

                    when (sourceFilter) {
                        ModelSourceFilter.GEMMA_4 -> {
                            ModelPresetList(
                                presets = ModelCatalog.byFamily(ModelFamily.GEMMA_4),
                                selectedRepo = cfg.huggingFaceRepo,
                                selectedFile = cfg.huggingFaceFile,
                                availStorageMb = state.memory.availableStorageMb,
                                totalRamMb = state.memory.deviceTotalMb,
                                onSelect = { p -> onUpdateHuggingFaceSource(p.repo, p.fileName) },
                            )
                        }
                        ModelSourceFilter.GEMMA_3 -> {
                            ModelPresetList(
                                presets = ModelCatalog.byFamily(ModelFamily.GEMMA_3),
                                selectedRepo = cfg.huggingFaceRepo,
                                selectedFile = cfg.huggingFaceFile,
                                availStorageMb = state.memory.availableStorageMb,
                                totalRamMb = state.memory.deviceTotalMb,
                                onSelect = { p -> onUpdateHuggingFaceSource(p.repo, p.fileName) },
                            )
                        }
                        ModelSourceFilter.CUSTOM -> {
                            OutlinedTextField(
                                value = customRepo,
                                onValueChange = { customRepo = it; onUpdateHuggingFaceSource(it, customFile) },
                                label = { Text("Hugging Face repo") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = customFile,
                                onValueChange = { customFile = it; onUpdateHuggingFaceSource(customRepo, it) },
                                label = { Text("Model file name (.litertlm or .task)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (!requiresToken) {
                        Surface(color = sc.readyContainer, shape = MaterialTheme.shapes.small) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Apache-2.0 open weights — ready to download without a Hugging Face token.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = sc.ready,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { showOptionalToken = !showOptionalToken }) {
                                    Text(if (showOptionalToken) "Hide Token" else "HF Token")
                                }
                            }
                        }
                    }

                    if (requiresToken || showOptionalToken) {
                        OutlinedTextField(
                            value = hfTokenInput,
                            onValueChange = { hfTokenInput = it },
                            label = {
                                Text(
                                    if (state.hasToken) "Hugging Face token (saved in Keystore)"
                                    else if (requiresToken) "Hugging Face token (required for Gemma 3)"
                                    else "Hugging Face token (optional)",
                                )
                            },
                            placeholder = { Text("hf_...") },
                            singleLine = true,
                            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Icon(
                                        if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = "Toggle token visibility",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onSaveToken(hfTokenInput); hfTokenInput = "" },
                                enabled = hfTokenInput.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) { Text("Save Token") }
                            if (state.hasToken) {
                                TextButton(onClick = onClearToken) { Text("Clear Token") }
                            }
                        }
                    }

                    selectedPreset?.let { p ->
                        val sizeMb = p.approxSizeBytes / 1_048_576L
                        val lowStorage = state.memory.availableStorageMb in 1 until (sizeMb + 64)
                        val lowRam = state.memory.deviceTotalMb in 1 until (p.minRecommendedRamGb * 1024L - 512L)
                        if (lowStorage || lowRam) {
                            Surface(color = sc.warningContainer, shape = MaterialTheme.shapes.small) {
                                Text(
                                    buildString {
                                        if (lowStorage) append("Storage warning: needs ~$sizeMb MB, device has ${state.memory.availableStorageMb} MB free. ")
                                        if (lowRam) append("RAM advisory: recommends ${p.minRecommendedRamGb} GB+ RAM (device has ${state.memory.deviceTotalMb / 1024} GB).")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = sc.warning,
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onDownload,
                            enabled = !busy && (!requiresToken || state.hasToken),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download")
                        }
                        OutlinedButton(
                            onClick = onImport,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import File")
                        }
                    }
                }
            }
        }
    }

    if (showCustomTokenDialog) {
        CustomTokenDialog(
            initialCtx = cfg.contextWindowTokens,
            initialOut = cfg.maxOutputTokens,
            onDismiss = { showCustomTokenDialog = false },
            onApply = { ctx, out ->
                onUpdateContextWindow(ctx)
                onUpdateMaxOutputTokens(out)
                showCustomTokenDialog = false
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete active model?") },
            text = { Text("This will unload the model from memory and remove the active bundle from app storage.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDeleteModel() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelPresetList(
    presets: List<ModelVariantPreset>,
    selectedRepo: String,
    selectedFile: String,
    availStorageMb: Long,
    totalRamMb: Long,
    onSelect: (ModelVariantPreset) -> Unit,
) {
    val sc = ProviderThemeDefaults.statusColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { preset ->
            val selected = preset.repo.equals(selectedRepo, ignoreCase = true) &&
                preset.fileName.equals(selectedFile, ignoreCase = true)
            val border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            val container = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            else MaterialTheme.colorScheme.surfaceContainerLow

            OutlinedCard(
                onClick = { onSelect(preset) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.outlinedCardColors(containerColor = container),
                border = border,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            preset.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusPill(
                            text = if (preset.gated) "HF Token" else "No HF Token",
                            dotColor = if (preset.gated) sc.warning else sc.ready,
                        )
                    }
                    Text(
                        preset.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(preset.tier, MaterialTheme.colorScheme.primary)
                        StatusPill(preset.runtime.label, MaterialTheme.colorScheme.secondary)
                        StatusPill("${preset.minRecommendedRamGb}GB+ RAM", sc.neutral)
                    }
                    val sizeMb = preset.approxSizeBytes / 1_048_576L
                    if (availStorageMb in 1 until (sizeMb + 64) || totalRamMb in 1 until (preset.minRecommendedRamGb * 1024L - 512L)) {
                        Text(
                            "Needs ~${formatFileSize(preset.approxSizeBytes)} disk · ${preset.minRecommendedRamGb} GB+ RAM",
                            style = MaterialTheme.typography.labelSmall,
                            color = sc.warning,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomTokenDialog(
    initialCtx: Int,
    initialOut: Int,
    onDismiss: () -> Unit,
    onApply: (Int, Int) -> Unit,
) {
    var ctxText by remember { mutableStateOf(initialCtx.toString()) }
    var outText by remember { mutableStateOf(initialOut.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exact Token Limits (up to 32,768)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ctxText,
                    onValueChange = { if (it.all(Char::isDigit)) ctxText = it },
                    label = { Text("Context Window (512–32768)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = outText,
                    onValueChange = { if (it.all(Char::isDigit)) outText = it },
                    label = { Text("Max Output Tokens (64–32768)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val c = ctxText.toIntOrNull() ?: initialCtx
                val o = outText.toIntOrNull() ?: initialOut
                onApply(c, o)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
