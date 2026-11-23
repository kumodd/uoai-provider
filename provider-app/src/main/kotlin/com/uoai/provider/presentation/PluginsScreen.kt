package com.uoai.provider.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uoai.provider.ui.components.SectionCard
import com.uoai.provider.ui.components.StatusPill
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import java.text.DateFormat
import java.util.Date

@Composable
fun PluginsTab(
    modifier: Modifier = Modifier,
    vm: PluginsViewModel = hiltViewModel(),
) {
    val sc = ProviderThemeDefaults.statusColors
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val logs by vm.auditLogs.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(plugins, key = { it.id }) { p ->
            SectionCard(
                title = "${p.name} (v${p.version})",
                icon = Icons.Rounded.Extension,
                subtitle = "${p.id} · Predefined safe tools",
                trailing = {
                    Switch(checked = p.enabled, onCheckedChange = { vm.setPluginEnabled(p.id, it) })
                },
            ) {
                if (p.description.isNotBlank()) {
                    Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        text = if (p.enabled) "ENABLED" else "DISABLED",
                        dotColor = if (p.enabled) sc.ready else sc.neutral,
                    )
                    StatusPill(text = p.adapterType, dotColor = MaterialTheme.colorScheme.primary)
                }
                p.tools.forEach { t ->
                    var showSchema by remember { mutableStateOf(false) }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace))
                                    Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(checked = t.enabled, onCheckedChange = { vm.setToolEnabled(t.id, it) })
                            }
                            TextButton(
                                onClick = { showSchema = !showSchema },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) {
                                Text(if (showSchema) "Hide Input Schema" else "Inspect Input Schema")
                            }
                            AnimatedVisibility(visible = showSchema) {
                                Text(
                                    t.inputSchemaJson,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Security & Audit Trail",
                icon = Icons.Rounded.VerifiedUser,
                subtitle = "Latest ${logs.size} privileged actions",
            ) {
                if (logs.isEmpty()) {
                    Text("No audit events recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        logs.forEach { l ->
                            val badgeColor = when {
                                l.status.contains("OK") || l.status == "APPROVED" -> sc.ready
                                l.status.contains("DENIED") || l.status == "REVOKED" || l.status == "ERROR" -> sc.error
                                l.status == "PENDING" -> sc.warning
                                else -> sc.neutral
                            }
                            val ts = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(l.createdAt))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(l.action, style = MaterialTheme.typography.labelLarge)
                                        Text(
                                            "$ts · ${l.resource ?: "-"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    StatusPill(l.status, badgeColor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
