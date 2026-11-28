package com.uoai.provider.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uoai.asr.AsrModelCatalog
import com.uoai.asr.AsrModelState
import com.uoai.asr.AsrProfile
import com.uoai.asr.AsrProfileSummary
import com.uoai.asr.AsrSettings
import com.uoai.asr.engines.AsrEngineKind
import com.uoai.asr.engines.AsrOutputScript
import com.uoai.asr.engines.HinglishModelCatalog
import com.uoai.asr.engines.HinglishModelSpec
import com.uoai.core.resources.ComputeCoexistencePolicy
import com.uoai.provider.ui.components.SectionCard
import com.uoai.provider.ui.components.StatusPill
import com.uoai.provider.ui.components.formatFileSize
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import com.uoai.providerapi.external.ExternalProviderType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeechSettingsScreen(
    state: SpeechSettingsUiState,
    onDownloadProfile: (AsrProfile) -> Unit,
    onCancelDownload: () -> Unit,
    onSelectProfile: (AsrProfile) -> Unit,
    onLoadModel: (AsrProfile?) -> Unit,
    onUnloadModel: () -> Unit,
    onDeleteProfile: (AsrProfile) -> Unit,
    onSelectEngine: (AsrEngineKind) -> Unit = {},
    onUpdateOutputScript: (AsrOutputScript) -> Unit = {},
    onDownloadHinglishModel: (AsrEngineKind) -> Unit = {},
    onCancelHinglishDownload: () -> Unit = {},
    onLoadHinglishModel: (AsrEngineKind) -> Unit = {},
    onUnloadHinglishModel: () -> Unit = {},
    onDeleteHinglishModel: (AsrEngineKind) -> Unit = {},
    onUpsertExternalProvider: (id: String, name: String, type: ExternalProviderType, baseUrl: String, defaultModel: String, apiKey: String?) -> Unit = { _, _, _, _, _, _ -> },
    onToggleExternalProvider: (id: String, enabled: Boolean) -> Unit = { _, _ -> },
    onDeleteExternalProvider: (id: String) -> Unit = {},
    onUpdateLanguage: (String) -> Unit,
    onUpdateTranslateToEnglish: (Boolean) -> Unit,
    onUpdateAutoLoad: (Boolean) -> Unit,
    onUpdateIdleUnloadTimeout: (Int) -> Unit,
    onUpdateCoexistencePolicy: (ComputeCoexistencePolicy) -> Unit,
    onUpdateMaxThreads: (Int) -> Unit,
    onClearTempCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sc = ProviderThemeDefaults.statusColors
    val st = state.status
    val cfg = state.settings
    val busy = st.state in setOf(
        AsrModelState.VERIFYING, AsrModelState.LOADING, AsrModelState.UNLOADING, AsrModelState.TRANSCRIBING,
    )
    val downloading = st.downloadingProfile != null
    val activePreset = st.activePreset

    var deleteTarget by remember { mutableStateOf<AsrProfile?>(null) }
    var deleteHinglishTarget by remember { mutableStateOf<AsrEngineKind?>(null) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAddExternalDialog by remember { mutableStateOf(false) }
    var externalExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Clean Active ASR Hero Card
        val badgeColor = when (st.state) {
            AsrModelState.READY, AsrModelState.TRANSCRIBING -> sc.ready
            AsrModelState.ERROR -> sc.error
            AsrModelState.DOWNLOADING, AsrModelState.VERIFYING, AsrModelState.LOADING, AsrModelState.UNLOADING -> sc.warning
            AsrModelState.INSTALLED, AsrModelState.NOT_INSTALLED -> sc.neutral
        }
        val stateLabel = if (st.state == AsrModelState.INSTALLED) "READY TO LOAD" else st.state.name

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
                            text = activePreset.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${activePreset.profile.displayName} · ${formatFileSize(activePreset.exactSizeBytes)} · ~${activePreset.approxRamMb} MB RAM · Lang: ${cfg.defaultLanguage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(stateLabel, badgeColor)
                }

                if (downloading) {
                    val dlPreset = st.downloadingProfile?.let { AsrModelCatalog.forProfile(it) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Downloading ${dlPreset?.displayName ?: "model"}…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = onCancelDownload,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) { Text("Cancel") }
                        }
                        val progress = st.downloadProgress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                st.errorMessage?.let { err ->
                    Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(err, color = sc.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    if (st.isSelectedInstalled) onLoadModel(st.selectedProfile)
                                    else onDownloadProfile(st.selectedProfile)
                                },
                            ) { Text("Retry") }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        st.isLoaded -> {
                            OutlinedButton(
                                onClick = onUnloadModel,
                                enabled = st.state == AsrModelState.READY,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Eject, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Unload Model")
                            }
                        }
                        st.isSelectedInstalled -> {
                            Button(
                                onClick = { onLoadModel(st.selectedProfile) },
                                enabled = !busy && !downloading,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (st.state == AsrModelState.LOADING) "Loading…" else "Load Model")
                            }
                        }
                        else -> {
                            Button(
                                onClick = { onDownloadProfile(st.selectedProfile) },
                                enabled = !busy && !downloading,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Download ${activePreset.displayName}")
                            }
                        }
                    }
                }
            }
        }

        // 2. Specialized Offline Hinglish Engines (AksharSTT v4, AksharSTT Lite v4, Shruti)
        val hSt = state.hinglish
        SectionCard(
            title = "Hinglish ASR Engines",
            icon = Icons.Rounded.RecordVoiceOver,
            subtitle = "Offline Hindi-English code-switching · Cactus 4.0.0 (Apache-2.0)",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default Output Script", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AsrOutputScript.entries.forEach { script ->
                        FilterChip(
                            selected = cfg.outputScript == script,
                            onClick = { onUpdateOutputScript(script) },
                            label = { Text(script.shortLabel, maxLines = 1) },
                        )
                    }
                }
                Text(
                    text = cfg.outputScript.example,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                hSt.errorMessage?.let { err ->
                    Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            err,
                            color = sc.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                HinglishModelCatalog.ALL_CACTUS_MODELS.forEach { spec ->
                    HinglishEngineRow(
                        spec = spec,
                        selected = cfg.selectedEngine == spec.kind,
                        installed = hSt.isInstalled(spec.kind),
                        loaded = hSt.isLoaded(spec.kind),
                        loading = hSt.loadingKind == spec.kind,
                        downloading = hSt.downloadingKind == spec.kind,
                        verifying = hSt.verifyingKind == spec.kind,
                        anyDownloading = hSt.downloadingKind != null || hSt.verifyingKind != null,
                        downloadProgress = if (hSt.downloadingKind == spec.kind) hSt.downloadProgress else null,
                        onSelect = { onSelectEngine(spec.kind) },
                        onDownload = { onDownloadHinglishModel(spec.kind) },
                        onCancelDownload = onCancelHinglishDownload,
                        onLoad = { onLoadHinglishModel(spec.kind) },
                        onUnload = onUnloadHinglishModel,
                        onRequestDelete = { deleteHinglishTarget = spec.kind },
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Maya Transcribe (Future): ${HinglishModelCatalog.MAYA_TRANSCRIBE_COMPATIBILITY_NOTE}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }

        // 3. Existing Whisper Recognition Quality Profiles (Fast / Balanced / High Accuracy)
        SectionCard(
            title = "Whisper Quality Profiles",
            icon = Icons.Rounded.GraphicEq,
            subtitle = "Existing whisper.cpp Q5_1 · 99 languages",
            trailing = {
                if (cfg.selectedEngine != AsrEngineKind.WHISPER) {
                    TextButton(onClick = { onSelectEngine(AsrEngineKind.WHISPER) }) { Text("Use Whisper") }
                } else {
                    StatusPill("ACTIVE", sc.ready)
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                st.profileSummaries().forEach { summary ->
                    CompactProfileRow(
                        summary = summary,
                        busy = busy,
                        anyDownloading = downloading,
                        onSelect = {
                            onSelectEngine(AsrEngineKind.WHISPER)
                            onSelectProfile(summary.preset.profile)
                        },
                        onDownload = { onDownloadProfile(summary.preset.profile) },
                        onCancelDownload = onCancelDownload,
                        onRequestDelete = { deleteTarget = summary.preset.profile },
                    )
                }
            }
        }

        // 3. Language Card (Clean & compact)
        SectionCard(
            title = "Language",
            icon = Icons.Rounded.Translate,
            trailing = {
                TextButton(onClick = { showLanguageDialog = true }) { Text("More") }
            },
        ) {
            val quickLangs = listOf(
                "auto" to "Auto (Lock)",
                "en,hi" to "EN + HI",
                "en" to "English",
                "hi" to "Hindi",
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                quickLangs.forEach { (code, label) ->
                    FilterChip(
                        selected = cfg.defaultLanguage == code,
                        onClick = { onUpdateLanguage(code) },
                        label = { Text(label) },
                    )
                }
                if (quickLangs.none { it.first == cfg.defaultLanguage }) {
                    val label = AsrModelCatalog.displayNameForLanguage(cfg.defaultLanguage)
                    FilterChip(
                        selected = true,
                        onClick = { showLanguageDialog = true },
                        label = { Text(label) },
                    )
                }
            }
            CompactSwitchRow(
                title = "Translate speech to English",
                checked = cfg.translateToEnglish,
                onCheckedChange = onUpdateTranslateToEnglish,
            )
        }

        // 4. Collapsible Advanced & Storage Card
        val idleLabel = if (cfg.idleUnloadTimeoutSec == 0) "Idle off" else "Idle ${cfg.idleUnloadTimeoutSec / 60}m"
        val policyLabel = if (cfg.coexistencePolicy == ComputeCoexistencePolicy.SMART_THROTTLE) "Smart" else "Serial"
        SectionCard(
            title = "Advanced & Storage",
            icon = Icons.Rounded.Tune,
            subtitle = "${if (cfg.autoLoadOnRequest) "Auto-load" else "Manual"} · $idleLabel · $policyLabel · ${formatFileSize(st.totalInstalledBytes)} used",
            trailing = {
                IconButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Icon(
                        if (advancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Toggle advanced speech settings",
                    )
                }
            },
        ) {
            AnimatedVisibility(visible = advancedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactSwitchRow(
                        title = "Auto-load on first API request",
                        subtitle = "Loads the selected profile automatically when a client app sends audio",
                        checked = cfg.autoLoadOnRequest,
                        onCheckedChange = onUpdateAutoLoad,
                    )

                    Text("Idle Auto-Unload", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val presets = AsrSettings.IDLE_UNLOAD_PRESETS_SEC
                        presets.forEachIndexed { idx, sec ->
                            SegmentedButton(
                                selected = cfg.idleUnloadTimeoutSec == sec,
                                onClick = { onUpdateIdleUnloadTimeout(sec) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = presets.size),
                            ) {
                                Text(if (sec == 0) "Off" else "${sec / 60}m", maxLines = 1)
                            }
                        }
                    }

                    Text("LLM Coexistence Policy", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val policies = ComputeCoexistencePolicy.entries
                        policies.forEachIndexed { idx, policy ->
                            SegmentedButton(
                                selected = cfg.coexistencePolicy == policy,
                                onClick = { onUpdateCoexistencePolicy(policy) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = policies.size),
                            ) {
                                Text(
                                    if (policy == ComputeCoexistencePolicy.SMART_THROTTLE) "Smart Throttle" else "Strict Serial",
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    Text("CPU Threads (${if (cfg.maxThreads == 0) "Auto · ${state.resources.recommendedAsrThreads}" else cfg.maxThreads})", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(0, 2, 4, 6)
                        options.forEachIndexed { idx, t ->
                            SegmentedButton(
                                selected = cfg.maxThreads == t,
                                onClick = { onUpdateMaxThreads(t) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
                            ) {
                                Text(if (t == 0) "Auto" else "$t", maxLines = 1)
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val totalAsrBytes = st.totalInstalledBytes + state.hinglish.totalStorageBytes()
                            Text(
                                "Storage: ${formatFileSize(totalAsrBytes)} (${st.installedProfiles.size}/3 Whisper · ${state.hinglish.installed.size}/3 Hinglish)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${state.availableStorageMb} MB free · Temp cache: ${if (state.tempCacheBytes == 0L) "0 B" else formatFileSize(state.tempCacheBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.tempCacheBytes > 0L) {
                            TextButton(onClick = onClearTempCache) { Text("Clear Cache") }
                        }
                    }
                }
            }
        }

        // 5. Collapsible External Inference & Developer API Routing
        SectionCard(
            title = "External Inference Providers",
            icon = Icons.Rounded.Hub,
            subtitle = buildString {
                append(if (state.gemma4AudioReady) "Gemma 4 direct audio active (recommended)" else "Load Gemma 4 E2B/E4B for direct audio")
                append(" · ${state.externalProviders.count { it.config.enabled }} external")
            },
            trailing = {
                IconButton(onClick = { externalExpanded = !externalExpanded }) {
                    Icon(
                        if (externalExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Toggle external inference providers",
                    )
                }
            },
        ) {
            AnimatedVisibility(visible = externalExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "External apps can call /v1/audio/transcriptions using local Gemma 4 (provider=gemma4), Whisper (provider=whisper), Akshar/Shruti (provider=cactus), or any configured external endpoint below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.externalProviderError?.let { err ->
                        Surface(color = sc.errorContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = sc.error,
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                            )
                        }
                    }

                    if (state.externalProviders.isEmpty()) {
                        Text(
                            "No external providers configured yet. Add one here or via POST /v1/audio/providers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.externalProviders.forEach { item ->
                                val p = item.config
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = MaterialTheme.shapes.small,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                "${p.displayName} (${p.id})",
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                "${p.defaultModel} · ${p.type.apiId}" + if (item.hasApiKey) " · Key saved" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                p.baseUrl,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Switch(
                                            checked = p.enabled,
                                            onCheckedChange = { onToggleExternalProvider(p.id, it) },
                                        )
                                        IconButton(onClick = { onDeleteExternalProvider(p.id) }) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = "Delete provider",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showAddExternalDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add External Provider")
                    }
                }
            }
        }
    }

    if (showAddExternalDialog) {
        AddExternalProviderDialog(
            onDismiss = { showAddExternalDialog = false },
            onSave = { id, name, type, baseUrl, model, key ->
                onUpsertExternalProvider(id, name, type, baseUrl, model, key)
                showAddExternalDialog = false
            },
        )
    }

    deleteTarget?.let { target ->
        val p = AsrModelCatalog.forProfile(target)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${p.displayName}?") },
            text = { Text("Removes ${p.fileName} (${formatFileSize(p.exactSizeBytes)}) from local storage.") },
            confirmButton = {
                Button(
                    onClick = { deleteTarget = null; onDeleteProfile(target) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteHinglishTarget?.let { target ->
        val spec = HinglishModelCatalog.specFor(target)
        AlertDialog(
            onDismissRequest = { deleteHinglishTarget = null },
            title = { Text("Delete ${spec?.displayName ?: target.displayName}?") },
            text = { Text("Removes ${spec?.fileName ?: target.id} (${formatFileSize(spec?.exactSizeBytes ?: 0L)}) from local storage.") },
            confirmButton = {
                Button(
                    onClick = { deleteHinglishTarget = null; onDeleteHinglishModel(target) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteHinglishTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showLanguageDialog) {
        AsrLanguagePickerDialog(
            selectedCode = cfg.defaultLanguage,
            onSelect = { code ->
                onUpdateLanguage(code)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

@Composable
private fun HinglishEngineRow(
    spec: HinglishModelSpec,
    selected: Boolean,
    installed: Boolean,
    loaded: Boolean,
    loading: Boolean,
    downloading: Boolean,
    verifying: Boolean,
    anyDownloading: Boolean,
    downloadProgress: Float?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val sc = ProviderThemeDefaults.statusColors
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surface

    Surface(
        color = bgColor,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading) { onSelect() },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = if (!loading) onSelect else null,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(spec.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${spec.publisher} · ${formatFileSize(spec.exactSizeBytes)} · ~${spec.approxRamMb} MB RAM · ${spec.license}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                when {
                    downloading -> TextButton(onClick = onCancelDownload) { Text("Cancel") }
                    verifying -> StatusPill("VERIFYING", sc.warning)
                    loading -> StatusPill("LOADING", sc.warning)
                    loaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("LOADED", sc.ready)
                        IconButton(onClick = onUnload, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Eject, contentDescription = "Unload ${spec.displayName}", modifier = Modifier.size(18.dp))
                        }
                    }
                    installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = onLoad,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) { Text("Load", style = MaterialTheme.typography.labelMedium) }
                        IconButton(onClick = onRequestDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete ${spec.displayName}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    else -> FilledTonalButton(
                        onClick = onDownload,
                        enabled = !anyDownloading,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Get", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (downloading) {
                if (downloadProgress != null) {
                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (verifying || loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CompactProfileRow(
    summary: AsrProfileSummary,
    busy: Boolean,
    anyDownloading: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val sc = ProviderThemeDefaults.statusColors
    val p = summary.preset
    val selected = summary.selected

    Surface(
        onClick = { if (!busy && !summary.downloading) onSelect() },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = p.profile.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        )
                        if (p.recommended) {
                            Text(
                                text = "· Recommended",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = "${p.displayName} · ${formatFileSize(summary.installedSizeBytes ?: p.exactSizeBytes)} · ~${p.approxRamMb} MB RAM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Single concise right-side control
                when {
                    summary.downloading -> {
                        TextButton(
                            onClick = onCancelDownload,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("Cancel") }
                    }
                    !summary.installed -> {
                        FilledTonalButton(
                            onClick = onDownload,
                            enabled = !anyDownloading && !busy,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Rounded.CloudDownload, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Get", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (summary.loaded) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "Loaded",
                                    tint = sc.ready,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            IconButton(
                                onClick = onRequestDelete,
                                enabled = !busy,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete ${p.displayName}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (summary.downloading) {
                summary.downloadProgress?.let { prog ->
                    LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CompactSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun AsrLanguagePickerDialog(
    selectedCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val filtered = remember(query, showAll) {
        val q = query.trim().lowercase()
        val source = if (showAll || q.isNotEmpty()) AsrModelCatalog.ALL_LANGUAGES else AsrModelCatalog.COMMON_LANGUAGES
        if (q.isEmpty()) source
        else source.filter { (code, name) ->
            code.contains(q) || name.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search 99 languages or ISO code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (showAll) "Showing all ${AsrModelCatalog.ALL_LANGUAGES.size} options"
                            else "Common + Indian languages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { showAll = !showAll },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(if (showAll) "Show Common" else "Show All 99")
                        }
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.first }) { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(code) }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("$label ($code)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (code == selectedCode) {
                                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddExternalProviderDialog(
    onDismiss: () -> Unit,
    onSave: (id: String, name: String, type: ExternalProviderType, baseUrl: String, defaultModel: String, apiKey: String?) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ExternalProviderType.OPENAI_COMPATIBLE_CHAT_AUDIO) }
    var baseUrl by remember { mutableStateOf("https://") }
    var defaultModel by remember { mutableStateOf("gemma-4-e4b") }
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add External Inference Provider") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.lowercase().filter { c -> c.isLetterOrDigit() || c in "._-" } },
                    label = { Text("Provider ID (e.g. remote-gemma4)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Protocol", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ExternalProviderType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = {
                                Text(
                                    when (t) {
                                        ExternalProviderType.OPENAI_COMPATIBLE_AUDIO -> "OpenAI Audio"
                                        ExternalProviderType.OPENAI_COMPATIBLE_CHAT_AUDIO -> "OpenAI Multimodal"
                                        ExternalProviderType.GEMINI_MULTIMODAL -> "Gemini / Gemma API"
                                    },
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    label = { Text("Default Model (e.g. gemma-4-e4b, whisper-1)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key (stored in Android Keystore, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        id.trim(),
                        displayName.trim(),
                        type,
                        baseUrl.trim(),
                        defaultModel.trim(),
                        apiKey.trim().takeIf { it.isNotEmpty() },
                    )
                },
                enabled = id.length >= 2 && baseUrl.startsWith("http") && defaultModel.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
