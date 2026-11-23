package com.uoai.provider.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.uoai.inference.ModelState
import com.uoai.provider.ui.components.*
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaygroundTab(
    state: DashboardUiState,
    onLoadModel: () -> Unit,
    onRunTest: (prompt: String, systemPrompt: String?, maxTokensOverride: Int?, tempOverride: Float?) -> Unit,
    onCancelTest: () -> Unit,
    onClearTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sc = ProviderThemeDefaults.statusColors
    val clipboard = LocalClipboardManager.current
    val ready = state.model.state == ModelState.READY
    val modelBusy = state.model.state in setOf(ModelState.LOADING, ModelState.DOWNLOADING, ModelState.VERIFYING, ModelState.UNLOADING)

    var showSystem by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf("You are a concise, accurate offline assistant.") }
    var prompt by remember { mutableStateOf("Explain machine learning in two clear sentences.") }
    var selectedMaxTokens by remember { mutableStateOf<Int?>(null) }

    val activeCtx = state.model.loadedConfig?.contextWindowTokens ?: state.settings.contextWindowTokens
    val effectiveMaxOut = selectedMaxTokens ?: state.settings.maxOutputTokens

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Readiness strip
        val statusText = when {
            ready -> "READY · ${formatTokenShort(activeCtx)}"
            state.model.state == ModelState.NOT_INSTALLED && state.modelInstalled -> "INSTALLED"
            else -> state.model.state.name
        }
        SectionCard(
            title = "Local Inference Playground",
            icon = Icons.Rounded.Terminal,
            subtitle = "Runs directly on the shared InferenceEngine queue",
            trailing = {
                StatusPill(
                    text = statusText,
                    dotColor = if (ready) sc.ready else sc.warning,
                )
            },
        ) {
            if (!ready && !state.testRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.modelInstalled) "Model is installed but not loaded." else "Install a Gemma 3 model in the Model tab first.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.modelInstalled) {
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = onLoadModel, enabled = !modelBusy) { Text("Load Model") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Max Output Override", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showSystem = !showSystem }) {
                    Text(if (showSystem) "Hide System Prompt" else "Add System Prompt")
                }
            }

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

            AnimatedVisibility(visible = showSystem) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System Prompt (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onRunTest(prompt, if (showSystem) systemPrompt else null, effectiveMaxOut, null)
                    },
                    enabled = ready && prompt.isNotBlank() && !state.testRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run Generation")
                }
                if (state.testRunning) {
                    FilledTonalButton(
                        onClick = onCancelTest,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = sc.errorContainer, contentColor = sc.error),
                    ) {
                        Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                } else if (state.testOutput.isNotEmpty()) {
                    OutlinedButton(onClick = onClearTest) { Text("Clear") }
                }
            }
        }

        // Output card
        SectionCard(
            title = "Generated Output",
            icon = Icons.Rounded.AutoAwesome,
            subtitle = buildString {
                if (state.testRunning) append("Streaming tokens…")
                else if (state.testElapsedMs != null) append(String.format(Locale.US, "Completed in %.2f s", state.testElapsedMs / 1000f))
                else append("Output will appear here")
                if (state.testChunks > 0) {
                    append(" · ${state.testChunks} chunks")
                    state.testTokensPerSec?.let { append(String.format(Locale.US, " (%.1f chunks/s)", it)) }
                }
            },
            trailing = {
                if (state.testOutput.isNotEmpty()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(state.testOutput)) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy output")
                    }
                }
            },
        ) {
            if (state.testRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = state.testOutput.ifEmpty { "Ready. Enter a prompt above and tap Run Generation." },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (state.testOutput.isEmpty()) FontFamily.Default else FontFamily.Default,
                    ),
                    color = if (state.testOutput.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

