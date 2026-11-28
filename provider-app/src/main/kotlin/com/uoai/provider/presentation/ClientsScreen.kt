package com.uoai.provider.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uoai.clients.ClientInfo
import com.uoai.clients.ClientPermissions
import com.uoai.clients.ClientStatus
import com.uoai.provider.ui.components.StatusPill
import com.uoai.provider.ui.components.TokenPresetRow
import com.uoai.provider.ui.components.formatTokenShort
import com.uoai.provider.ui.theme.ProviderThemeDefaults
import java.text.DateFormat
import java.util.Date

private enum class ClientFilter(val label: String) {
    ALL("All"), APPROVED("Approved"), PENDING("Pending"), REVOKED("Revoked"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClientsTab(
    modifier: Modifier = Modifier,
    vm: ClientsViewModel = hiltViewModel(),
) {
    val rows by vm.clients.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(ClientFilter.ALL) }
    var revokeTarget by remember { mutableStateOf<ClientInfo?>(null) }

    val filtered = remember(rows, filter) {
        when (filter) {
            ClientFilter.ALL -> rows
            ClientFilter.APPROVED -> rows.filter { it.client.status == ClientStatus.APPROVED || it.client.status == ClientStatus.DISABLED }
            ClientFilter.PENDING -> rows.filter { it.client.status == ClientStatus.PENDING }
            ClientFilter.REVOKED -> rows.filter { it.client.status == ClientStatus.REVOKED || it.client.status == ClientStatus.REJECTED }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClientFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                )
            }
        }

        if (filtered.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.DevicesOther, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    Text("No matching client applications", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Client apps using the UOAI SDK will appear here once they request registration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(filtered, key = { it.client.id }) { row ->
                    ClientCard(row, vm, onRequestRevoke = { revokeTarget = it })
                }
            }
        }
    }

    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke ${target.appName}?") },
            text = { Text("All issued bearer tokens for ${target.packageName} will be invalidated immediately.") },
            confirmButton = {
                Button(
                    onClick = { vm.revoke(target); revokeTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Revoke Access") }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("Cancel") } },
        )
    }
}

private fun fmtTime(t: Long?) = t?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "never"

@Composable
private fun ClientCard(
    row: ClientRow,
    vm: ClientsViewModel,
    onRequestRevoke: (ClientInfo) -> Unit,
) {
    val sc = ProviderThemeDefaults.statusColors
    val clipboard = LocalClipboardManager.current
    val c = row.client
    val statusColor = when (c.status) {
        ClientStatus.APPROVED -> sc.ready
        ClientStatus.PENDING -> sc.warning
        ClientStatus.REVOKED, ClientStatus.REJECTED -> sc.error
        ClientStatus.DISABLED -> sc.neutral
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                c.appName.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Column {
                        Text(c.appName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            c.packageName,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(c.status.name, statusColor)
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "SHA-256: ${c.certDigest.take(26)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(c.certDigest)) },
                            modifier = Modifier.size(22.dp),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, "Copy digest", modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        "Registered: ${fmtTime(c.createdAt)} · Last seen: ${fmtTime(c.lastConnectedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Requests: ${row.total} total · ${row.failed} failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (c.status == ClientStatus.APPROVED || c.status == ClientStatus.DISABLED) {
                val p = c.permissions
                PermissionToggleRow("Chat Completions", p.chat) { vm.permissions(c, p.copy(chat = it)) }
                PermissionToggleRow("Streaming (SSE)", p.streaming) { vm.permissions(c, p.copy(streaming = it)) }
                PermissionToggleRow("Tool Calling", p.toolCalling) { vm.permissions(c, p.copy(toolCalling = it)) }
                PermissionToggleRow("Speech Recognition (ASR)", p.speechToText) { vm.permissions(c, p.copy(speechToText = it)) }
                PermissionToggleRow("ASR Model Load / Unload", p.asrModelControl) { vm.permissions(c, p.copy(asrModelControl = it)) }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Max Output Token Cap (${formatTokenShort(p.maxTokens)})", style = MaterialTheme.typography.labelLarge)
                TokenPresetRow(
                    selected = p.maxTokens,
                    presets = ClientPermissions.TOKEN_PRESETS,
                    onSelect = { vm.permissions(c, p.copy(maxTokens = it)) },
                )

                Text("Queue Priority", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    (0..3).forEach { prio ->
                        SegmentedButton(
                            selected = p.priority == prio,
                            onClick = { vm.permissions(c, p.copy(priority = prio)) },
                            shape = SegmentedButtonDefaults.itemShape(index = prio, count = 4),
                        ) {
                            Text("P$prio")
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (c.status) {
                    ClientStatus.PENDING, ClientStatus.REJECTED, ClientStatus.REVOKED -> {
                        Button(onClick = { vm.approve(c) }, modifier = Modifier.weight(1f)) { Text("Approve") }
                        if (c.status == ClientStatus.PENDING) {
                            OutlinedButton(onClick = { vm.reject(c) }, modifier = Modifier.weight(1f)) { Text("Reject") }
                        }
                    }
                    ClientStatus.APPROVED -> {
                        OutlinedButton(onClick = { vm.setEnabled(c, false) }, modifier = Modifier.weight(1f)) { Text("Disable") }
                        Button(
                            onClick = { onRequestRevoke(c) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Revoke") }
                    }
                    ClientStatus.DISABLED -> {
                        Button(onClick = { vm.setEnabled(c, true) }, modifier = Modifier.weight(1f)) { Text("Enable") }
                        OutlinedButton(onClick = { onRequestRevoke(c) }, modifier = Modifier.weight(1f)) { Text("Revoke") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
