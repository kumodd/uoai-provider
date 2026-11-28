package com.uoai.provider.registration

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.uoai.clients.*
import com.uoai.provider.ui.components.TokenPresetRow
import com.uoai.provider.ui.components.formatTokenShort
import com.uoai.provider.ui.theme.ProviderTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RegState {
    data object Loading : RegState
    data class Ask(val client: ClientInfo) : RegState
    data class Done(val result: Intent?, val ok: Boolean) : RegState
}

@HiltViewModel
class RegistrationViewModel @Inject constructor(private val repo: ClientRepository) : ViewModel() {
    private val _state = MutableStateFlow<RegState>(RegState.Loading)
    val state = _state.asStateFlow()

    fun start(callerPackage: String?, ctx: android.content.Context) = viewModelScope.launch {
        if (callerPackage == null) return@launch fail("no_caller", "Must be started with startActivityForResult")
        val id = CallerIdentityResolver.resolve(ctx, callerPackage) ?: return@launch fail("identity", "Could not verify caller identity")
        when (val o = repo.registerOrGet(id.packageName, id.appName, id.certDigest)) {
            is RegistrationOutcome.CertificateMismatch -> fail("certificate_mismatch", "Signing certificate does not match the registered app")
            is RegistrationOutcome.New -> _state.value = RegState.Ask(o.client)
            is RegistrationOutcome.Existing -> when (o.client.status) {
                ClientStatus.APPROVED -> grant(o.client)
                ClientStatus.DISABLED -> fail("disabled", "Access for this app is disabled in the provider")
                else -> _state.value = RegState.Ask(o.client)
            }
        }
    }

    fun approve(c: ClientInfo, perms: ClientPermissions) = viewModelScope.launch {
        repo.approve(c.id, perms)
        grant(c)
    }

    fun reject(c: ClientInfo) = viewModelScope.launch { repo.reject(c.id); fail("rejected", "The user rejected this app") }

    private suspend fun grant(c: ClientInfo) {
        val token = repo.issueToken(c.id)
        _state.value = RegState.Done(Intent().putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_CLIENT_ID, c.id), true)
    }

    private fun fail(code: String, msg: String) {
        _state.value = RegState.Done(Intent().putExtra(EXTRA_ERROR_CODE, code).putExtra(EXTRA_ERROR, msg), false)
    }

    companion object {
        const val EXTRA_TOKEN = "token"; const val EXTRA_CLIENT_ID = "client_id"
        const val EXTRA_ERROR = "error"; const val EXTRA_ERROR_CODE = "error_code"
    }
}

/**
 * Approval screen. Exported so client apps can launch it with startActivityForResult; the caller's
 * identity comes from the OS (getCallingPackage + signing certificate), never from an intent extra.
 */
@AndroidEntryPoint
class RegistrationActivity : ComponentActivity() {
    private val vm: RegistrationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (savedInstanceState == null) vm.start(callingPackage, this)
        setContent {
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(s) {
                (s as? RegState.Done)?.let { setResult(if (it.ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, it.result); finish() }
            }
            ProviderTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        when (val st = s) {
                            is RegState.Ask -> AskContent(st.client, vm::approve, vm::reject)
                            else -> CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AskContent(
    c: ClientInfo,
    onApprove: (ClientInfo, ClientPermissions) -> Unit,
    onReject: (ClientInfo) -> Unit,
) {
    var chat by remember { mutableStateOf(true) }
    var streaming by remember { mutableStateOf(true) }
    var tools by remember { mutableStateOf(false) }
    var speechToText by remember { mutableStateOf(true) }
    var maxTokens by remember { mutableStateOf(4096) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp))
                }
                Column {
                    Text("Allow AI Provider Access?", style = MaterialTheme.typography.titleLarge)
                    Text("OS-verified application identity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.appName, style = MaterialTheme.typography.titleMedium)
                    Text(c.packageName, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Text("Signing Certificate SHA-256:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(c.certDigest.take(47) + "…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Chat completions", style = MaterialTheme.typography.bodyMedium)
                Switch(chat, { chat = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Streaming responses (SSE)", style = MaterialTheme.typography.bodyMedium)
                Switch(streaming, { streaming = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Tool calling (client tools)", style = MaterialTheme.typography.bodyMedium)
                Switch(tools, { tools = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Speech recognition (ASR)", style = MaterialTheme.typography.bodyMedium)
                Switch(speechToText, { speechToText = it })
            }

            Text("Max Token Cap (${formatTokenShort(maxTokens)})", style = MaterialTheme.typography.labelLarge)
            TokenPresetRow(
                selected = maxTokens,
                presets = listOf(2048, 4096, 8192, 16384, 32768),
                onSelect = { maxTokens = it },
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onReject(c) }, modifier = Modifier.weight(1f)) { Text("Reject") }
                Button(
                    onClick = {
                        onApprove(
                            c,
                            ClientPermissions(
                                chat = chat,
                                streaming = streaming,
                                toolCalling = tools,
                                maxTokens = maxTokens,
                                speechToText = speechToText,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Approve") }
            }
        }
    }
}
