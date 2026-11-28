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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uoai.inference.*
import com.uoai.provider.ui.components.*
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import java.util.Locale
import kotlin.math.roundToInt

private enum class ModelCatalogFilter(val label: String) {
    RECOMMENDED("Recommended"),
    GEMMA_4("All Gemma 4"),
    CUSTOM("Custom"),
}

private val RECOMMENDED_PRESET_IDS = setOf(
    "gemma-4-e2b-std",
    "gemma-4-e2b-gpu",
    "gemma-4-e4b-std",
    "gemma-4-e4b-gpu",
)

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
    var catalogExpanded by remember(state.modelInstalled) { mutableStateOf(!state.modelInstalled) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ModelCatalogFilter.RECOMMENDED) }
    var hfTokenInput by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var showOptionalToken by remember { mutableStateOf(false) }
    var customRepo by remember(cfg.huggingFaceRepo) { mutableStateOf(cfg.huggingFaceRepo) }
    var customFile by remember(cfg.huggingFaceFile) { mutableStateOf(cfg.huggingFaceFile) }

    val requiresToken = ModelCatalog.requiresHfToken(cfg.huggingFaceRepo, cfg.huggingFaceFile)
    val selectedPreset = ModelCatalog.findPreset(cfg.huggingFaceRepo, cfg.huggingFaceFile)
    val activeCtx = state.model.loadedConfig?.contextWindowTokens ?: cfg.contextWindowTokens
    val activeBackend = state.model.loadedConfig?.preferredBackend ?: cfg.preferredBackend

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Clean Hero Card: Active LLM Status & Primary Action
        val badgeColor = when {
            s == ModelState.READY || s == ModelState.GENERATING -> sc.ready
            s == ModelState.ERROR -> sc.error
            busy -> sc.warning
            else -> sc.neutral
        }
        val stateLabel = if (s == ModelState.NOT_INSTALLED && state.modelInstalled) "READY TO LOAD" else s.name

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
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
                        Text(
                            text = state.model.model?.name ?: "No LLM Installed",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.model.model?.let {
                                "${it.version ?: it.format?.uppercase() ?: "Bundle"} · ${formatFileSize(it.fileSizeBytes)} · ${formatTokenShort(activeCtx)} ctx · $activeBackend"
                            } ?: "Download a Gemma 4 model below to get started",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(stateLabel, badgeColor)
                }

                state.model.loadProgress?.let { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("${(p * 100).toInt()}% downloaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } ?: if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else Unit

                state.model.errorMessage?.let { err ->
                    Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(err, color = sc.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(10.dp))
                    }
                }

                if (state.requiresReload) {
                    Surface(color = sc.warningContainer, shape = MaterialTheme.shapes.small) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Reload to apply ${formatTokenShort(cfg.contextWindowTokens)} / ${cfg.preferredBackend}.",
                                color = sc.warning,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onReload, enabled = !busy) { Text("Apply") }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s == ModelState.READY || s == ModelState.GENERATING) {
                        FilledTonalButton(
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
                    } else if (state.modelInstalled) {
                        Button(
                            onClick = onLoad,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (s == ModelState.LOADING) "Loading…" else "Load Model")
                        }
                    } else {
                        Button(
                            onClick = { catalogExpanded = true },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Choose Model to Download")
                        }
                    }

                    if (state.modelInstalled) {
                        IconButton(onClick = { showDeleteConfirm = true }, enabled = !busy) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete model", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // 2. Installed Local Models (Compact one-tap switcher, shown only when >= 1 installed)
        if (state.localModels.isNotEmpty()) {
            SectionCard(
                title = "Installed Models (${state.localModels.size})",
                icon = Icons.Rounded.Folder,
                subtitle = "${state.memory.availableStorageMb} MB device storage free",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.localModels.forEach { local ->
                        val active = state.model.model?.name == local.name && state.model.model?.format == local.format
                        Surface(
                            onClick = { if (!active && !busy) onSelectLocalModel("${local.name}.${local.format}") },
                            color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(
                                width = if (active) 1.5.dp else 1.dp,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        local.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${local.version ?: local.format?.uppercase() ?: "Model"} · ${formatFileSize(local.fileSizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (active) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                } else {
                                    Text("Switch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Download / Import Catalog (Clean, scannable, collapsible when a model is already installed)
        SectionCard(
            title = "Download or Import",
            icon = Icons.Rounded.CloudDownload,
            subtitle = if (catalogExpanded) "Gemma 4 LiteRT-LM open weights (no token required) · ${state.memory.availableStorageMb} MB free"
            else "Tap to browse Gemma 4 variants or import a local .litertlm file",
            trailing = {
                IconButton(onClick = { catalogExpanded = !catalogExpanded }) {
                    Icon(
                        if (catalogExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Toggle catalog",
                    )
                }
            },
        ) {
            AnimatedVisibility(visible = catalogExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ModelCatalogFilter.entries.forEach { tab ->
                            FilterChip(
                                selected = filter == tab,
                                onClick = { filter = tab },
                                label = { Text(tab.label) },
                            )
                        }
                    }

                    when (filter) {
                        ModelCatalogFilter.CUSTOM -> {
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
                                label = { Text("Gemma 4 model file (.litertlm)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            val presets = when (filter) {
                                ModelCatalogFilter.RECOMMENDED -> ModelCatalog.ALL_PRESETS.filter { it.id in RECOMMENDED_PRESET_IDS }
                                ModelCatalogFilter.GEMMA_4 -> ModelCatalog.byFamily(ModelFamily.GEMMA_4)
                                ModelCatalogFilter.CUSTOM -> emptyList()
                            }
                            CompactPresetList(
                                presets = presets,
                                selectedRepo = cfg.huggingFaceRepo,
                                selectedFile = cfg.huggingFaceFile,
                                onSelect = { p -> onUpdateHuggingFaceSource(p.repo, p.fileName) },
                            )
                        }
                    }

                    if (requiresToken || showOptionalToken) {
                        OutlinedTextField(
                            value = hfTokenInput,
                            onValueChange = { hfTokenInput = it },
                            label = {
                                Text(
                                    if (state.hasToken) "HF token (saved in Keystore)"
                                    else if (requiresToken) "Hugging Face token (required for gated repo)"
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
                                        contentDescription = "Toggle visibility",
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
                                TextButton(onClick = onClearToken) { Text("Clear") }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Open weights — no token needed",
                                style = MaterialTheme.typography.bodySmall,
                                color = sc.ready,
                            )
                            TextButton(
                                onClick = { showOptionalToken = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) { Text("HF Token") }
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
                                        if (lowStorage) append("Needs ~$sizeMb MB free (${state.memory.availableStorageMb} MB available). ")
                                        if (lowRam) append("Recommends ${p.minRecommendedRamGb} GB+ RAM.")
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
                            Text("Import")
                        }
                    }
                }
            }
        }

        // 4. Collapsible Advanced Tuning (Context, Output Cap, Backend, Sampling)
        SectionCard(
            title = "Advanced Tuning",
            icon = Icons.Rounded.Tune,
            subtitle = "${formatTokenShort(cfg.contextWindowTokens)} ctx · ${formatTokenShort(cfg.maxOutputTokens)} out · ${cfg.preferredBackend} · T=${String.format(Locale.US, "%.2f", cfg.temperature)}",
            trailing = {
                IconButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Icon(
                        if (advancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Toggle advanced tuning",
                    )
                }
            },
        ) {
            AnimatedVisibility(visible = advancedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Accelerator Backend", style = MaterialTheme.typography.labelLarge)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Context Window (${formatTokenShort(cfg.contextWindowTokens)})", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { showCustomTokenDialog = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("Exact")
                        }
                    }
                    TokenPresetRow(
                        selected = cfg.contextWindowTokens,
                        presets = InferenceSettings.CONTEXT_PRESETS,
                        onSelect = onUpdateContextWindow,
                    )

                    Text("Max Output Tokens (${formatTokenShort(cfg.maxOutputTokens)})", style = MaterialTheme.typography.labelLarge)
                    TokenPresetRow(
                        selected = cfg.maxOutputTokens,
                        presets = InferenceSettings.OUTPUT_PRESETS,
                        onSelect = onUpdateMaxOutputTokens,
                    )

                    if (cfg.contextWindowTokens > 4096) {
                        Text(
                            "High context (${formatTokenShort(cfg.contextWindowTokens)}) uses extra RAM (${state.memory.deviceAvailMb} MB free).",
                            style = MaterialTheme.typography.bodySmall,
                            color = sc.warning,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    SliderSettingRow(
                        label = "Temperature",
                        valueLabel = String.format(Locale.US, "%.2f", cfg.temperature),
                        value = cfg.temperature,
                        range = 0f..2f,
                        onValueChange = { onUpdateTemperature((it * 20f).roundToInt() / 20f) },
                    )
                    SliderSettingRow(
                        label = "Top-P",
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

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onResetSettings) { Text("Reset Defaults") }
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
private fun CompactPresetList(
    presets: List<ModelVariantPreset>,
    selectedRepo: String,
    selectedFile: String,
    onSelect: (ModelVariantPreset) -> Unit,
) {
    val sc = ProviderThemeDefaults.statusColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { preset ->
            val selected = preset.repo.equals(selectedRepo, ignoreCase = true) &&
                preset.fileName.equals(selectedFile, ignoreCase = true)
            Surface(
                onClick = { onSelect(preset) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${formatFileSize(preset.approxSizeBytes)} · ${preset.minRecommendedRamGb} GB+ RAM · ${preset.runtime.label}" +
                                if (preset.gated) " · Token req." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (preset.gated) sc.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
