# Universal Offline AI Provider (`com.uoai.provider`) — External Developer Integration Guide

Integrate local, **100% offline on-device Gemma 3 / Gemma 4 LLM inference** into any independent Android app—without bundling model weights or an inference runtime. Your app sends prompts over a localhost loopback API (`127.0.0.1:8765`) after a one-time, OS-verified user approval.

---

## Handover Package & Key Constants

Include these deliverables when sharing this guide with an external team:
1. **This document** (`EXTERNAL_DEVELOPER_HANDOVER.md`)
2. **`sdk-release.aar`** (~112 KB, `minSdk 26`, R8/ProGuard-ready, zero model weights) — *optional if using Path B (Direct HTTP/SSE)*
3. **Provider APK / Install Link** for `com.uoai.provider` *(fill in your Play Store / GitHub Releases / MDM URL before sending)*

| Property | Value |
|---|---|
| **Provider Package ID** | `com.uoai.provider` |
| **Registration Intent Action** | `com.uoai.provider.action.REGISTER` |
| **Loopback Base URL** | `http://127.0.0.1:8765/v1` (loopback only; never `0.0.0.0`) |
| **Authentication Header** | `Authorization: Bearer uoai_<token>` (all `/v1/*` except `/v1/health`) |
| **Recommended `model` Value** | `"auto"` (routes automatically to whichever Gemma 3 or Gemma 4 model is loaded) |
| **Max Request Size** | `2,097,152` bytes (2 MiB; HTTP `413` if exceeded) |
| **Context & Output Token Cap** | Up to `32,768` tokens (`effective = min(request.max_tokens, client_cap, 32768)`) |
| **Generation Timeout** | `300,000 ms` (300 s; HTTP `504` or mid-stream `error` event) |
| **Concurrency & Queue** | `1` active generation shared across apps; queue `16` global / `4` per client |
| **Supported Capabilities** | `chat`, `streaming` (SSE), `cancellation`, `tool-calling` (`embeddings = false`) |

---

## 1. How Connection & Trust Work

Two Android apps cannot directly share process memory. Instead, **Universal Offline AI Provider** pairs **Android OS Caller Verification** with **Loopback HTTP/SSE**:

```text
Your Client App                                    Universal Offline AI Provider
---------------                                    -----------------------------
1. Check package installed (com.uoai.provider)
2. startActivityForResult(REGISTER Intent)  -----> OS verifies callingPackage + single APK
   (Never pass FLAG_ACTIVITY_NEW_TASK!)            signing cert (SHA-256). User approves caps.
   Receives uoai_<token> once               <----- Returns token via RESULT_OK; stores SHA-256 hash
   Encrypts token in Android Keystore              in Room (client_credentials table).
3. GET /v1/health  (unauthenticated)        -----> Checks Ktor server + loaded model state.
   GET /v1/models & /v1/capabilities        -----> Validates Bearer token; returns model & 32K ctx.
4. POST /v1/chat/completions (Bearer)       -----> Queues onto single shared Gemma 3 / 4 instance.
   Receives JSON or SSE stream              <----- Streams data: {...} chunks + data: [DONE]
5. POST /v1/cancel (Bearer, optional)       -----> Cancels only requests owned by your client.
```

> **Important Lifecycle Rule:** The provider's foreground service (`ProviderService`) is intentionally **non-sticky**. If the user stops the API or Android reclaims background processes, subsequent calls fail to connect cleanly—always offer a one-tap **Open Provider** recovery action rather than crashing.

---

## 2. Path A — Recommended Kotlin SDK Integration (`sdk-release.aar`)

The lightweight AAR handles Keystore encryption (`AES/GCM/NoPadding`), Bearer token headers, registration Intent handling, connection preflight, SSE parsing, cancellation on coroutine abort, and multi-turn history management.

### 2.1 Gradle Setup (`app/build.gradle.kts`)

Copy `sdk-release.aar` into `app/libs/` and add **3 dependency lines** (`minSdk >= 26`, Java/Kotlin target 17):

```kotlin
dependencies {
    implementation(files("libs/sdk-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
```

#### What `sdk-release.aar` Configures Automatically
You need **zero** UOAI entries in `AndroidManifest.xml` and **zero** extra ProGuard rules:
- **Auto-merged Manifest & Security:** Embeds `android.permission.INTERNET`, `<queries><package android:name="com.uoai.provider" /></queries>`, and a loopback-only `android:networkSecurityConfig="@xml/uoai_network_security_config"` (`127.0.0.1` and `localhost` only—no wide `usesCleartextTraffic="true"`).
  > *Already have your own `networkSecurityConfig`?* Add `tools:replace="android:networkSecurityConfig"` on `<application>` in your manifest and whitelist `<domain includeSubdomains="false">127.0.0.1</domain>` + `localhost` inside your XML.
- **R8 / ProGuard Consumer Rules:** Embedded `consumer-rules.pro` preserves `com.uoai.sdk.**` and `kotlinx.serialization` serializers when `isMinifyEnabled = true`.

#### Critical Signing Setup (`certificate_mismatch` Prevention)
The provider pins your `packageName` to its single-signer APK SHA-256 certificate on first registration. Give your `debug` and `release` APKs separate package names so switching builds on a test phone never triggers `certificate_mismatch`:

```kotlin
android {
    defaultConfig {
        applicationId = "com.yourcompany.yourapp"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}
```
*(Also ensure your release APK uses a single signing key—multi-signer APKs are rejected.)*

---

### 2.2 Complete Drop-In Integration (ViewModel + Compose / Activity)

1. Create `val ai = UniversalAIClient(applicationContext)` (zero config needed; defaults to `ClientConfig(model = "auto")`).
2. Call `ai.checkConnection()` on startup and on `ON_RESUME` (when returning from the provider app).
3. Use `ai.startChat(...)` to stream multi-turn responses with automatic sliding history and rollback on failures.

```kotlin
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uoai.sdk.ConnectionState
import com.uoai.sdk.RegistrationResult
import com.uoai.sdk.UniversalAIClient
import com.uoai.sdk.exceptions.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AiUiState(
    val connection: ConnectionState? = null,
    val output: String = "",
    val streaming: Boolean = false,
    val notice: String? = null,
)

class AiViewModel(app: Application) : AndroidViewModel(app) {
    private val ai = UniversalAIClient(app) // defaults to http://127.0.0.1:8765/v1, model = "auto"
    private val session = ai.startChat(systemPrompt = "You are a helpful, concise assistant.")

    private val _ui = MutableStateFlow(AiUiState())
    val ui: StateFlow<AiUiState> = _ui.asStateFlow()
    private var streamJob: Job? = null

    init { refreshConnection() }

    fun refreshConnection() = viewModelScope.launch {
        _ui.update { it.copy(connection = ai.checkConnection()) }
    }

    fun onRegistrationResult(resultCode: Int, data: Intent?) {
        when (val res = ai.handleRegistrationResult(resultCode, data)) {
            RegistrationResult.Approved -> {
                _ui.update { it.copy(notice = null) }
                refreshConnection()
            }
            is RegistrationResult.Denied -> {
                _ui.update { it.copy(notice = "Denied (${res.code}): ${res.message}") }
            }
        }
    }

    fun send(prompt: String) {
        if (prompt.isBlank() || _ui.value.streaming) return
        _ui.update { it.copy(output = "", streaming = true, notice = null) }
        streamJob = viewModelScope.launch {
            try {
                session.sendStream(prompt).collect { token ->
                    _ui.update { it.copy(output = it.output + token) }
                }
                _ui.update { it.copy(streaming = false) }
            } catch (_: CancellationException) {
                _ui.update { it.copy(streaming = false, notice = "Stopped") }
            } catch (_: RequestCancelledException) {
                _ui.update { it.copy(streaming = false, notice = "Cancelled") }
            } catch (e: NotRegisteredException) {
                ai.clearCredentials()
                _ui.update { it.copy(streaming = false, notice = e.message) }
                refreshConnection()
            } catch (e: UniversalAIException) {
                _ui.update { it.copy(streaming = false, notice = e.message) }
                refreshConnection()
            }
        }
    }

    fun stop() { streamJob?.cancel() } // automatically calls POST /v1/cancel
}

class AiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AiViewModel = viewModel()
            val ui by vm.ui.collectAsStateWithLifecycle()
            val regLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { r -> vm.onRegistrationResult(r.resultCode, r.data) }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val obs = LifecycleEventObserver { _, ev ->
                    if (ev == Lifecycle.Event.ON_RESUME) vm.refreshConnection()
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }

            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val conn = ui.connection) {
                    ConnectionState.ProviderNotInstalled ->
                        Text("Please install Universal Offline AI Provider (com.uoai.provider).")
                    is ConnectionState.NeedsRegistration ->
                        Button(onClick = { regLauncher.launch(conn.registrationIntent) }) { Text("Approve Provider Access") }
                    is ConnectionState.ProviderNotRunning ->
                        Button(onClick = { conn.launchProviderIntent?.let(::startActivity) }) { Text("Open Provider & Start API") }
                    is ConnectionState.ModelNotReady ->
                        Button(onClick = { conn.launchProviderIntent?.let(::startActivity) }) { Text("Load Model (${conn.modelState})") }
                    is ConnectionState.Error ->
                        Button(onClick = vm::refreshConnection) { Text("Retry: ${conn.message}") }
                    is ConnectionState.Connected -> {
                        Text("Ready: ${conn.activeModel?.name} (ctx=${conn.capabilities.contextWindowTokens})")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.send("Summarize on-device AI in 3 bullets.") }, enabled = !ui.streaming) { Text("Ask AI") }
                            if (ui.streaming) OutlinedButton(onClick = vm::stop) { Text("Stop") }
                        }
                    }
                    null -> CircularProgressIndicator()
                }
                ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(ui.output)
            }
        }
    }
}
```

### 2.3 SDK Public API Summary

| Symbol | Purpose |
|---|---|
| `UniversalAIClient(context, config)` | Entry point (`ClientConfig(model = "auto")` by default). Uses Android Keystore-encrypted `TokenStore`. |
| `suspend fun checkConnection(): ConnectionState` | One-call check returning `ProviderNotInstalled`, `NeedsRegistration`, `ProviderNotRunning`, `ModelNotReady`, `Connected`, or `Error`. Automatically clears stale revoked tokens. |
| `fun isProviderInstalled(): Boolean` | Returns `true` if `com.uoai.provider` is installed on the device. |
| `fun registrationIntent(): Intent` | Intent for `startActivityForResult`. Never add `FLAG_ACTIVITY_NEW_TASK`. |
| `fun handleRegistrationResult(code, data)` | Saves issued token into Keystore and returns `RegistrationResult.Approved` or `.Denied`. |
| `fun openProviderIntent(): Intent?` | Launch Intent (`FLAG_ACTIVITY_NEW_TASK`) to open Universal Offline AI Provider for recovery. |
| `fun startChat(...): ChatSession` | Stateful multi-turn session (`send()`, `sendStream()`, `restoreHistory()`, `clear()`, `messages`). |
| `suspend fun connect() / health() / models() / capabilities()` | Low-level inspection methods (`ProviderHealth`, `List<ModelInfo>`, `Capabilities`). |
| `suspend fun generate(...) / chat(...)` | One-shot or multi-turn non-streaming completions (supports `tools` and `useProviderTools`). |
| `fun generateStream(...) / chatStream(...)` | Cold `Flow<String>` SSE stream; cancelling collection calls `POST /v1/cancel`. |
| `suspend fun cancel(requestId): Boolean` | Explicitly cancels an in-flight request by `X-Request-Id`. |
| `fun clearCredentials()` | Deletes the cached token from Keystore-backed preferences. |

---

## 3. Path B — Direct HTTP/SSE Integration (Without the AAR)

Use this path for Java-only apps, custom HTTP stacks (OkHttp/Ktor), Flutter method channels, or React Native native modules.

### 3.1 Required Manifest & Loopback Security Config

Add to `AndroidManifest.xml` (on Android 11+/API 30+, `<queries>` is mandatory):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <queries>
        <package android:name="com.uoai.provider" />
    </queries>

    <application
        android:networkSecurityConfig="@xml/network_security_config"
        ...>
        ...
    </application>
</manifest>
```

Create `res/xml/network_security_config.xml` (cleartext restricted strictly to loopback):

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

### 3.2 Registration Intent Protocol

Launch with `startActivityForResult` or `ActivityResultContracts.StartActivityForResult()` (do **not** set `FLAG_ACTIVITY_NEW_TASK` or Android clears the OS caller identity):

```kotlin
val intent = Intent("com.uoai.provider.action.REGISTER").setPackage("com.uoai.provider")
registrationLauncher.launch(intent)
```

| Result Code | Extras | Meaning & Required Action |
|---|---|---|
| `Activity.RESULT_OK` | `token: String` (`"uoai_..."`), `client_id: String` | Approved. Persist `token` in Android Keystore / `EncryptedSharedPreferences`. If already `APPROVED` with matching cert, provider silently rotates and returns a fresh token. |
| `Activity.RESULT_CANCELED` | `error_code: String`, `error: String` | Denied or aborted. Codes: `no_caller`, `identity`, `certificate_mismatch`, `disabled`, `rejected`, `cancelled`. |

---

### 3.3 HTTP & SSE Endpoint Reference (`http://127.0.0.1:8765/v1`)

#### 1. `GET /v1/health` *(Unauthenticated)*
```json
{
  "status": "ok",
  "model_state": "READY",
  "model_loaded": true,
  "api_version": "v1"
}
```
Possible `model_state` values: `NOT_INSTALLED`, `DOWNLOADING`, `VERIFYING`, `LOADING`, `READY`, `GENERATING`, `UNLOADING`, `ERROR`. When unavailable, `status` is `"degraded"`, `model_loaded` is `false`, and optional `model_error` explains why.

#### 2. `GET /v1/models` *(Authenticated)*
```json
{
  "object": "list",
  "data": [
    {
      "id": "gemma-4-e2b",
      "object": "model",
      "owned_by": "universal-offline-ai",
      "name": "gemma-4-E2B-it",
      "format": "litertlm",
      "quantization": "e2b",
      "size_bytes": 2588100000,
      "ready": true,
      "context_window": 32768
    }
  ]
}
```

#### 3. `GET /v1/capabilities` *(Authenticated)*
Call on startup to inspect dynamic limits and enabled provider tools:
```json
{
  "features": ["chat", "streaming", "cancellation", "tool-calling"],
  "max_request_bytes": 2097152,
  "max_tokens_limit": 32768,
  "concurrent_generations": 1,
  "embeddings": false,
  "provider_tools": ["calculator", "device_info"],
  "context_window_tokens": 32768,
  "default_max_tokens": 2048
}
```

#### 4. `POST /v1/chat/completions` *(Authenticated)*

| Field | Type | Required | Notes |
|---|---|---|---|
| `model` | `string` | No | Use `"auto"` (recommended) or active `id` / `"default"` / `"gemma-4"` / `"gemma-3"`. Unknown models return `404`. |
| `messages` | `array` | **Yes** | Objects with `role` (`"system" \| "user" \| "assistant" \| "tool"`), `content`, and optional `tool_call_id`, `name`. Must include at least one `"user"` or `"tool"` message. |
| `temperature` | `float` | No | `0.0 .. 2.0` (provider default if omitted). |
| `max_tokens` | `int` | No | `1 .. 32768` (clamped by client permission & provider cap). |
| `top_p` | `float` | No | Nucleus sampling (clamped to `0.05 .. 1.0`). |
| `top_k` | `int` | No | Top-k sampling (clamped to `1 .. 128`). |
| `stream` | `boolean` | No | `false` (JSON) or `true` (SSE). Requires `streaming` permission. |
| `tools` | `array` | No | Client-owned function schemas (`stream` must be `false`). Requires `toolCalling` permission. |
| `use_provider_tools` | `boolean` | No | Injects provider-owned tools (`stream` must be `false`). Requires `toolCalling` permission. |

Every `200 OK` response returns header `X-Request-Id: chatcmpl-<uuid>` (store it immediately for cancellation).

**Non-Streaming (`stream: false`) Response:**
```json
{
  "id": "chatcmpl-d425bd9b-a9a2-44f1-9cb1-53fb58590ddb",
  "object": "chat.completion",
  "created": 1795105164,
  "model": "gemma-4-e2b",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "Paris" },
      "finish_reason": "stop"
    }
  ]
}
```

**Streaming (`stream: true`, header `Accept: text/event-stream`) SSE Events:**
```text
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":1795105166,"model":"gemma-4-e2b","choices":[{"index":0,"delta":{"role":"assistant"}}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":1795105166,"model":"gemma-4-e2b","choices":[{"index":0,"delta":{"content":"Hello"}}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":1795105166,"model":"gemma-4-e2b","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```
SSE parsing rules: skip blank lines and lines not starting with `data:`, trim prefix `data:`, stop at `[DONE]`, abort if `"error"` JSON key appears, append `choices[0].delta.content`, and handle `finish_reason == "cancelled"`.

#### 5. `POST /v1/cancel` *(Authenticated)*
```json
// Request
{ "request_id": "chatcmpl-8b26e9d9-1b6b-41e9-aa85-fc8bdb7b163f" }

// Response
{ "request_id": "chatcmpl-8b26e9d9-1b6b-41e9-aa85-fc8bdb7b163f", "cancelled": true }
```
*(Client-isolated: cancelling another app's or unknown request ID returns `cancelled: false`.)*

### 3.4 Minimal OkHttp Snippet
```kotlin
val http = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .build()

val req = Request.Builder()
    .url("http://127.0.0.1:8765/v1/chat/completions")
    .header("Authorization", "Bearer $token")
    .post("""{"model":"auto","messages":[{"role":"user","content":"Hi"}],"stream":false}"""
        .toRequestBody("application/json".toMediaType()))
    .build()

http.newCall(req).execute().use { resp ->
    val requestId = resp.header("X-Request-Id")
    val body = resp.body?.string().orEmpty()
    if (!resp.isSuccessful) error("HTTP ${resp.code}: $body")
    // Parse choices[0].message.content
}
```

---

## 4. Tool Calling (`tools` & `use_provider_tools`)

Requires `toolCalling` permission enabled in the provider and **`stream: false`**.

- **Client-Owned Tools (`tools`):** Supply OpenAI-style `{"type":"function","function":{"name","description","parameters":{...}}}` specs. When triggered, the provider returns `finish_reason: "tool_calls"` and `message.tool_calls` (`id`, `function.name`, JSON string `function.arguments`). Your app runs the local function and sends a follow-up turn containing:
  1. Original `user` message
  2. `assistant` message with `"content": "<tool_call>{\"name\":\"...\",\"arguments\":{...}}</tool_call>"`
  3. `tool` message with `{"role":"tool","tool_call_id":"...","name":"...","content":"<result>"}`
- **Provider-Owned Safe Tools (`use_provider_tools: true`):** Automatically executes enabled provider tools (`calculator`, `device_info`) and returns the final `assistant` answer directly.

---

## 5. Error Codes & Recovery Playbook

All non-2xx HTTP responses follow `{"error":{"message":"...","type":"...","code":"..."}}`:

| HTTP / Failure | `error.code` | SDK Exception | Client UI Recovery |
|---|---|---|---|
| *(Package missing)* | — | `ProviderNotInstalledException` | Show **"Install Universal Offline AI Provider"** (`com.uoai.provider`) |
| *(Socket refused)* | — | `ProviderNotRunningException` | Prompt user to tap **Open Provider** and start the API server |
| **400** | `invalid_json`, `missing_messages`, `invalid_role`, `missing_user_message`, `invalid_temperature`, `invalid_max_tokens`, `invalid_request` | `ApiException` | Fix payload (`temperature` `0..2`, `max_tokens` `1..32768`, ≥1 user/tool msg) |
| **401** | `missing_token`, `invalid_token` | `NotRegisteredException` | Clear saved token and relaunch registration Intent |
| **403** | `permission_denied` | `PermissionDeniedException` | Tell user to enable `chat`, `streaming`, or `tool_calling` in Provider → Clients |
| **404** | `model_not_found` | `ApiException` | Pass `"model": "auto"` or query `GET /v1/models` |
| **409** | `request_cancelled` | `RequestCancelledException` | Clean user abort |
| **413** | `request_too_large` | `ApiException` | Trim message history below `2 MiB` |
| **429** | `queue_full` | `ApiException` | Queue limit hit (`16` global / `4` per client); back off briefly & retry |
| **500** | `internal_error`, `inference_failed` | `ApiException` | Display error & offer retry |
| **503** | `model_not_ready` | `ModelNotReadyException` | Prompt user to **Open Provider** and load a model |
| **504** | `request_timeout` | `ApiException` | Exceeded `300 s`; reduce prompt size or `max_tokens` |

---

## 6. Context Budgeting, Model Support & Scope Boundaries

- **Supported Models:** Works seamlessly across Gemma 3 (`.task` / `.litertlm`) and all Gemma 4 tiers (`E2B`, `E4B`, `12B`, `26B-A4B`, `31B`) when you pass `"model": "auto"`.
- **Up to 32K Context Window:** Always read `GET /v1/capabilities` on connect (`context_window_tokens`, `default_max_tokens`, `max_tokens_limit`) and keep your local history bounded so `prompt_tokens + max_tokens <= context_window_tokens`.
- **Privacy Guarantee:** Your app owns conversation storage; the provider does **not** persist prompt text or model responses in its database and never requires internet access after model download.
- **What Is Out of Scope for the Provider:** UOAI Provider exposes **text chat & tool calling only**. Embeddings (`embeddings: false`), Speech-to-Text / audio capture, multimodal vision inputs, and app-specific privacy/consent flows must be handled inside your own client app before calling `/v1/chat/completions`.

---

## 7. Testing, ADB Debugging & Pre-Release Checklist

### Inspecting Live API From Your Workstation
```bash
adb forward tcp:8765 tcp:8765
curl -s http://127.0.0.1:8765/v1/health
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/v1/capabilities
```

### Pre-Release Checklist
- [ ] Pass `"model": "auto"` and read `GET /v1/capabilities` dynamically instead of hardcoding model IDs or context sizes.
- [ ] Use distinct debug vs. release `applicationId` (`applicationIdSuffix = ".debug"`) and sign release APKs with a **single** signing certificate.
- [ ] Launch `com.uoai.provider.action.REGISTER` with `startActivityForResult` (never `FLAG_ACTIVITY_NEW_TASK`).
- [ ] Re-check provider state on `ON_RESUME` so returning from the provider app auto-connects immediately.
- [ ] Test an R8-minified release build (`isMinifyEnabled = true`) end-to-end against `com.uoai.provider` on a real device.
- [ ] Confirm your final APK contains **zero** `.task`, `.litertlm`, or `.bin` weights (`unzip -l app-release.apk`).
