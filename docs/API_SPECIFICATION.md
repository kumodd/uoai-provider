# Universal Offline AI Provider — API Documentation & Android Client Implementation Guide

**API Version:** `v1`  
**Base URL:** `http://127.0.0.1:8765/v1`  
**Provider Package:** `com.uoai.provider`  
**Registration Intent Action:** `com.uoai.provider.action.REGISTER`  

**Universal Offline AI Provider** exposes a centralized, single-instance on-device Gemma 3 or Gemma 4 LLM over a loopback-only HTTP + Server-Sent Events (SSE) API. Any independently installed Android application can integrate with this API—either via the zero-config Kotlin SDK (`sdk-release.aar`, ~90 KB) or directly via HTTP/SSE—without bundling model weights or an inference runtime.

---

## Table of Contents
1. [Overview & Integration Lifecycle](#1-overview--integration-lifecycle)
2. [Android Client App Setup (Manifest & Network Security)](#2-android-client-app-setup-manifest--network-security)
3. [OS-Verified Registration & Authentication Protocol](#3-os-verified-registration--authentication-protocol)
4. [Complete HTTP & SSE API Reference](#4-complete-http--sse-api-reference)
5. [Tool Calling Specification (Client-Owned & Provider-Owned)](#5-tool-calling-specification-client-owned--provider-owned)
6. [Error Codes & Client Recovery Playbook](#6-error-codes--client-recovery-playbook)
7. [Implementation Guide A — Using the Kotlin Android SDK (`:sdk` AAR)](#7-implementation-guide-a--using-the-kotlin-android-sdk-sdk-aar)
8. [Implementation Guide B — Direct HTTP/SSE Integration (Without the SDK)](#8-implementation-guide-b--direct-httpsse-integration-without-the-sdk)
9. [Production Best Practices, 32K Context Management & ADB Debugging](#9-production-best-practices-32k-context-management--adb-debugging)

---

## 1. Overview & Integration Lifecycle

### Key Principles
- **Offline & On-Device:** All communication stays on `127.0.0.1:8765` (loopback only; never exposed on `0.0.0.0`). Zero internet access is required once the provider has installed the model.
- **One Model, Multiple Clients:** All registered client apps share one loaded Gemma 3 instance managed by a centralized priority queue (`DefaultInferenceEngine`).
- **Loopback Is Not Authentication:** Every API endpoint except `GET /v1/health` requires a per-client `Authorization: Bearer uoai_<token>` header obtained through OS-verified Android Activity registration.

### Integration Lifecycle
```text
Client Android App                              Universal Offline AI Provider
------------------                              -----------------------------
1. Check package installed (com.uoai.provider)
2. Launch Intent("com.uoai.provider.action.REGISTER")
   via startActivityForResult ----------------> 3. Reads OS callingPackage +
                                                   GET_SIGNING_CERTIFICATES SHA-256
                                                4. User approves capabilities & token cap
5. Receives RESULT_OK + token                  <-- Issues uoai_<token> (stores SHA-256 hash)
   Saves token in Android Keystore
6. GET /v1/health (unauthenticated) ----------> Returns model_state, model_loaded
7. GET /v1/capabilities (Bearer token) -------> Returns max_tokens_limit (32768),
                                                   context_window_tokens, provider_tools
8. POST /v1/chat/completions (Bearer) --------> Queues & executes on shared Gemma 3
   Receives JSON or SSE stream  <-------------- Streams data: {...} chunks + [DONE]
9. POST /v1/cancel (Bearer, optional) --------> Cancels only this client's request_id
```

### Endpoint Summary & Hard Limits

| Method | Path | Auth Required | Purpose |
|---|---|---|---|
| `GET` | `/v1/health` | No | Provider & model readiness (`model_state`, `model_loaded`) |
| `GET` | `/v1/models` | Yes | Installed model metadata & active `context_window` |
| `GET` | `/v1/capabilities` | Yes | Supported features, token limits, `context_window_tokens`, enabled `provider_tools` |
| `POST` | `/v1/chat/completions` | Yes | OpenAI-compatible chat completion (`stream: false` JSON or `stream: true` SSE) |
| `POST` | `/v1/cancel` | Yes | Cancels a queued or running request owned by the caller |

| Limit / Policy | Value | Notes |
|---|---|---|
| Bind Address | `127.0.0.1:8765` | Loopback only |
| `max_request_bytes` | `2,097,152` (2 MiB) | Configurable in `ApiConfig`; HTTP `413` if exceeded |
| `max_tokens_limit` | `32,768` (32K) | Effective output tokens = `min(request.max_tokens ?: provider_default, client.maxTokens, 32768)` |
| `request_timeout` | `300,000 ms` (300 s) | HTTP `504` (or SSE error event mid-stream) if exceeded |
| Scheduler Queue | `16` global / `4` per client | Sequential single-worker execution; HTTP `429` (`queue_full`) if full |

---

## 2. Android Client App Setup (Manifest & Network Security)

### 2.1 `AndroidManifest.xml`
Client apps targeting Android 11+ (API 30+) must declare `<queries>` visibility for `com.uoai.provider`, plus `INTERNET` permission for loopback HTTP:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Required for localhost HTTP calls to 127.0.0.1:8765 -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Required on Android 11+ (API 30+) to detect & launch the provider -->
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

### 2.2 Loopback-Only Cleartext Security Config (Recommended)
Instead of enabling `android:usesCleartextTraffic="true"` app-wide, allow cleartext HTTP **only** for `127.0.0.1` in `res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

### 2.3 Standard Client Configuration Format (FR-07)
Client apps using JSON configuration or the SDK `ClientConfig` class follow this schema:

```json
{
  "provider": "universal.offline.ai",
  "connection": "http",
  "baseUrl": "http://127.0.0.1:8765/v1",
  "model": "gemma-3",
  "features": [
    "chat",
    "streaming",
    "tool-calling"
  ],
  "providerPackage": "com.uoai.provider",
  "requestTimeoutMs": 300000
}
```

---

## 3. OS-Verified Registration & Authentication Protocol

A package name in an HTTP header or Intent extra is **never** trusted as identity. Instead, registration is anchored in Android's Activity caller verification:

1. Your app launches the provider's exported `RegistrationActivity` with `startActivityForResult` (or `ActivityResultContracts.StartActivityForResult`).
   - **Action:** `com.uoai.provider.action.REGISTER`
   - **Target Package:** `com.uoai.provider`
   - **Do NOT use `FLAG_ACTIVITY_NEW_TASK`** — Android clears `getCallingPackage()` when launched across tasks.
2. The provider reads `Activity.getCallingPackage()` from the OS and extracts the APK's SHA-256 signing certificate digest via `PackageManager.GET_SIGNING_CERTIFICATES` (multi-signer APKs are rejected).
3. **Certificate Pinning:** If the package name was previously registered with a different signing certificate, registration immediately fails with `certificate_mismatch` and writes a `DENIED` audit entry.
4. **First-time / Re-approval UI:** The user sees your app name, package name, SHA-256 certificate digest, capability switches (`Chat completions`, `Streaming responses`, `Tool calling`), and an initial **Max Token Cap** (`2K`–`32K`).
5. **Re-registration when already `APPROVED`:** If your app already has `APPROVED` status and the same signing certificate, `RegistrationActivity` skips the prompt, rotates the credential (revoking previous tokens for that client), and immediately returns a fresh token.

### Activity Result Contract

| Result Code | Extras Returned | Meaning |
|---|---|---|
| `Activity.RESULT_OK` | `token: String` (`"uoai_..."`), `client_id: String` (UUID) | Approved. Persist `token` encrypted in Android Keystore. |
| `Activity.RESULT_CANCELED` | `error_code: String`, `error: String` (may be null if user pressed Back) | Denied or failed. See error codes below. |

| `error_code` | Cause |
|---|---|
| `no_caller` | Launched without `startActivityForResult` (or with `FLAG_ACTIVITY_NEW_TASK`) |
| `identity` | OS failed to resolve package or single signing certificate |
| `certificate_mismatch` | Package name matches an existing client, but APK signing certificate changed |
| `disabled` | Client exists in provider but is currently `DISABLED` by the user |
| `rejected` | User tapped **Reject** on the approval screen |
| `cancelled` | User pressed Back / dismissed the dialog (or no error extras returned) |

### HTTP Authentication Header
Pass the issued token on every authenticated endpoint:
```http
Authorization: Bearer uoai_<token>
```
The provider stores only the `SHA-256` hex digest of the token in Room (`client_credentials.token_hash`). Revoking or disabling a client in the provider invalidates its token immediately.

---

## 4. Complete HTTP & SSE API Reference

### 4.1 `GET /v1/health` (Unauthenticated)
Reports live provider status and model lifecycle state. Safe to poll before connecting or before sending prompts.

**Response (`200 OK`):**
```json
{
  "status": "ok",
  "model_state": "READY",
  "model_loaded": true,
  "api_version": "v1"
}
```
When the model is not loaded or failed, `status` is `"degraded"` and `model_loaded` is `false`:
```json
{
  "status": "degraded",
  "model_state": "ERROR",
  "model_loaded": false,
  "model_error": "Not enough memory to load the model (32768 context tokens)",
  "api_version": "v1"
}
```

All possible `model_state` values (FR-01):
`NOT_INSTALLED` · `DOWNLOADING` · `VERIFYING` · `LOADING` · `READY` · `GENERATING` · `UNLOADING` · `ERROR`

> Note: When a model file is installed on disk but not yet loaded into memory, `model_state` is `NOT_INSTALLED` with `model_loaded: false` and `GET /v1/models` returns the installed model entry with `ready: false`.

---

### 4.2 `GET /v1/models` (Authenticated)
Lists the installed/active model managed by the provider and its configured/active context window.

**Response (`200 OK`):**
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
Supported model IDs derived from installed bundles:
- **Gemma 4 (LiteRT-LM):** `gemma-4-e2b`, `gemma-4-e4b`, `gemma-4-12b`, `gemma-4-26b-a4b`, `gemma-4-31b` (also accepts family alias `"gemma-4"`, `"auto"`, or `"default"` when a Gemma 4 bundle is active)
- **Gemma 3 (MediaPipe / LiteRT-LM):** `gemma-3` (also accepts `"auto"` or `"default"`)

If no model is installed yet, `data` is `[]`.

---

### 4.3 `GET /v1/capabilities` (Authenticated)
Returns active server limits, context window, default generation budget, and currently enabled provider-owned tools. Call this on startup to size your prompt history and request parameters dynamically.

**Response (`200 OK`):**
```json
{
  "features": [
    "chat",
    "streaming",
    "cancellation",
    "tool-calling"
  ],
  "max_request_bytes": 2097152,
  "max_tokens_limit": 32768,
  "concurrent_generations": 1,
  "embeddings": false,
  "provider_tools": [
    "calculator",
    "device_info"
  ],
  "context_window_tokens": 32768,
  "default_max_tokens": 2048
}
```

---

### 4.4 `POST /v1/chat/completions` (Authenticated)
Executes chat inference on the single shared Gemma 4 / Gemma 3 instance via the priority scheduler.

#### Request Fields
| Field | Type | Required | Default | Constraints & Notes |
|---|---|---|---|---|
| `model` | `string` | No | Active model ID | Accepts `"auto"`, `"default"`, the active model's `id` (`gemma-4-e2b`, `gemma-4-e4b`, `gemma-4-12b`, `gemma-4-26b-a4b`, `gemma-4-31b`, `gemma-3`), `name`, or family alias (`"gemma-4"` / `"gemma-3"`); unknown models return HTTP `404 model_not_found` |
| `messages` | `array` | **Yes** | — | Non-empty list; must include at least one `"user"` or `"tool"` role message |
| `temperature` | `float` | No | Provider setting (`0.7`) | Must be in `0.0 .. 2.0` |
| `max_tokens` | `int` | No | Provider setting (`2048`) | Validated `1 .. 32768`; effective cap = `min(max_tokens, client_permission.maxTokens, 32768)` |
| `top_p` | `float` | No | Provider setting (`0.95`) | Nucleus sampling; clamped by runtime to `0.05 .. 1.0` |
| `top_k` | `int` | No | Provider setting (`40`) | Clamped by runtime to `1 .. 128` |
| `stream` | `boolean` | No | `false` | Requires `streaming` permission on the client |
| `tools` | `array` | No | `null` | Client-owned tool specs; requires `toolCalling` permission (`stream` must be `false`) |
| `use_provider_tools` | `boolean` | No | `false` | Enables provider-owned safe tools (`PluginRegistry`); requires `toolCalling` permission (`stream` must be `false`) |

#### Message Object Schema
```json
{
  "role": "system | user | assistant | tool",
  "content": "Message text",
  "tool_call_id": "optional id when role == tool",
  "name": "optional tool name when role == tool"
}
```
> **Gemma Prompt Formatting:** Gemma instruction-tuned models do not have a native `system` role. The provider automatically folds all `system` messages (and any tool schemas) into the first `<start_of_turn>user` block.

#### Non-Streaming Request & Response (`stream: false`)
Every `200 OK` response includes header `X-Request-Id: chatcmpl-<uuid>` matching `id`.

**Request:**
```json
{
  "model": "gemma-3",
  "messages": [
    { "role": "system", "content": "Be concise." },
    { "role": "user", "content": "What is the capital of France?" }
  ],
  "temperature": 0.7,
  "max_tokens": 128,
  "stream": false
}
```

**Response (`200 OK`, `application/json`):**
```json
{
  "id": "chatcmpl-d425bd9b-a9a2-44f1-9cb1-53fb58590ddb",
  "object": "chat.completion",
  "created": 1795105164,
  "model": "gemma-3",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Paris"
      },
      "finish_reason": "stop"
    }
  ]
}
```

#### Streaming Request & Response (`stream: true`)
Returns `Content-Type: text/event-stream` and `Cache-Control: no-cache`, with `X-Request-Id` header set before streaming begins (save `X-Request-Id` immediately so you can call `/v1/cancel` if needed).

If the model is not ready before headers are flushed, the server responds with HTTP `503` JSON (`model_not_ready`).

**SSE Event Stream (`200 OK`, `text/event-stream`):**
```text
data: {"id":"chatcmpl-8b26e9d9-...","object":"chat.completion.chunk","created":1795105166,"model":"gemma-3","choices":[{"index":0,"delta":{"role":"assistant"}}]}

data: {"id":"chatcmpl-8b26e9d9-...","object":"chat.completion.chunk","created":1795105166,"model":"gemma-3","choices":[{"index":0,"delta":{"content":"Neural"}}]}

data: {"id":"chatcmpl-8b26e9d9-...","object":"chat.completion.chunk","created":1795105166,"model":"gemma-3","choices":[{"index":0,"delta":{"content":" networks"}}]}

data: {"id":"chatcmpl-8b26e9d9-...","object":"chat.completion.chunk","created":1795105166,"model":"gemma-3","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

**Mid-Stream Cancellation or Error:**
- If cancelled while streaming via `/v1/cancel`: emits a chunk with `"finish_reason":"cancelled"` followed by `data: [DONE]`, or terminates cleanly on socket disconnect.
- If an error occurs after headers are committed (e.g., `request_timeout` or `inference_failed`): emits a final JSON event with an `error` object:
```text
data: {"error":{"message":"Inference timed out","type":"timeout","code":"request_timeout","request_id":"chatcmpl-..."}}
```

---

### 4.5 `POST /v1/cancel` (Authenticated)
Cancels a queued or actively running request. Ownership is strictly isolated: one client can never cancel another client's request (unknown and foreign IDs both return `cancelled: false`).

**Request:**
```json
{
  "request_id": "chatcmpl-8b26e9d9-1b6b-41e9-aa85-fc8bdb7b163f"
}
```

**Response (`200 OK`):**
```json
{
  "request_id": "chatcmpl-8b26e9d9-1b6b-41e9-aa85-fc8bdb7b163f",
  "cancelled": true
}
```

---

## 5. Tool Calling Specification (Client-Owned & Provider-Owned)

- **Permission Requirement:** The calling client must have `toolCalling = true` enabled in the provider (`403 permission_denied` otherwise).
- **Mode:** Tool-calling interception runs in **non-streaming mode** (`stream: false`).

### 5.1 Client-Owned Tools (`tools` array — FR-08A)
Your app supplies tool definitions; the provider never touches your app's private data. If the model decides to invoke a client tool, the provider returns `finish_reason: "tool_calls"` and `message.tool_calls`. Your app executes the tool locally and sends the result back as a `role: "tool"` message.

**Turn 1 — Request with client tool schema:**
```json
{
  "model": "gemma-3",
  "messages": [
    { "role": "user", "content": "Summarize the currently open note." }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "read_selected_note",
        "description": "Returns the title and text of the note currently open in the editor.",
        "parameters": {
          "type": "object",
          "properties": {
            "include_metadata": { "type": "boolean" }
          }
        }
      }
    }
  ],
  "stream": false
}
```

**Turn 1 — Response (`finish_reason: "tool_calls"`):**
```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1795106000,
  "model": "gemma-3",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "",
        "tool_calls": [
          {
            "id": "call_a1b2c3d4",
            "type": "function",
            "function": {
              "name": "read_selected_note",
              "arguments": "{\"include_metadata\":true}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ]
}
```

**Turn 2 — Return tool output to provider (`role: "tool"`):**
```json
{
  "model": "gemma-3",
  "messages": [
    { "role": "user", "content": "Summarize the currently open note." },
    { "role": "assistant", "content": "<tool_call>{\"name\":\"read_selected_note\",\"arguments\":{\"include_metadata\":true}}</tool_call>" },
    { "role": "tool", "tool_call_id": "call_a1b2c3d4", "name": "read_selected_note", "content": "Title: Architecture Meeting\nNotes: Decide on local API port 8765 and Room schema." }
  ],
  "stream": false
}
```

### 5.2 Provider-Owned Safe Tools (`use_provider_tools: true` — FR-08B)
When `use_provider_tools: true` is sent (and `toolCalling` is granted), the provider injects its enabled predefined tools (`PluginRegistry`), executes any matching tool call locally on-device, logs it in `audit_logs`, runs the follow-up turn automatically, and returns the final `assistant` answer.

Built-in tools (`builtin.utilities`):
- `calculator` — Safe deterministic arithmetic (`+`, `-`, `*`, `/`, parentheses). Input: `{"expression": "(12.5 * 4) + 3"}`.
- `device_info` — Non-sensitive offline metadata (`manufacturer`, `model`, `sdk_int`, `local_time`, `offline_provider`). Input: `{}`.

---

## 6. Error Codes & Client Recovery Playbook

All non-2xx responses use a standard JSON envelope:
```json
{
  "error": {
    "message": "Model is not ready (state=NOT_INSTALLED)",
    "type": "model_unavailable",
    "code": "model_not_ready"
  }
}
```

| HTTP | `error.type` | `error.code` | SDK Exception | Recommended Client Action |
|---|---|---|---|---|
| **400** | `invalid_request_error` | `invalid_json`, `missing_messages`, `invalid_role`, `missing_user_message`, `invalid_temperature`, `invalid_max_tokens`, `invalid_request` | `ApiException` | Fix malformed JSON or out-of-range parameters (`temperature` `0..2`, `max_tokens` `1..32768`). |
| **401** | `authentication_error` | `missing_token`, `invalid_token` | `NotRegisteredException` | Clear cached token and launch `registrationIntent()` to request/refresh approval. |
| **403** | `permission_error` | `permission_denied` | `PermissionDeniedException` | Feature (`chat`, `streaming`, or `tool_calling`) is disabled for your app in Provider → Clients; inform the user. |
| **404** | `invalid_request_error` | `model_not_found` | `ApiException` | Query `GET /v1/models` and pass the active `id` (or `"auto"` / omit `model`). |
| **409** | `request_cancelled` | `request_cancelled` | `RequestCancelledException` | Request was cancelled cleanly; treat as user/caller abort, not a crash. |
| **413** | `invalid_request_error` | `request_too_large` | `ApiException` | Trim conversation history below `capabilities().maxRequestBytes` (2 MiB). |
| **429** | `rate_limit_error` | `queue_full` | `ApiException` | Too many concurrent/queued requests (16 global / 4 per client). Back off briefly and retry. |
| **500** | `server_error` | `internal_error`, `inference_failed` | `ApiException` | Display diagnostic message and allow retry. |
| **503** | `model_unavailable` | `model_not_ready` | `ModelNotReadyException` | Check `GET /v1/health`. Prompt user to open **Universal Offline AI Provider** and load/install the model. |
| **504** | `timeout` | `request_timeout` | `ApiException` | Generation exceeded 300 s; lower `max_tokens` or shorten prompt. |
| *(Socket)* | — | — | `ProviderNotRunningException` | Provider service is stopped; prompt user to open the provider app and tap **Start API**. |
| *(Package)* | — | — | `ProviderNotInstalledException` | `com.uoai.provider` is not installed on the device. |

---

## 7. Implementation Guide A — Using the Kotlin Android SDK (`:sdk` AAR)

The `:sdk` module compiles to `sdk/build/outputs/aar/sdk-release.aar` (and `sdk-debug.aar`, ~90 KB) with **zero LLM weights or native libraries**.

### Zero-Config AAR Behavior
`sdk-release.aar` includes:
- **Embedded R8/ProGuard rules (`consumer-rules.pro`):** Consuming apps using `isMinifyEnabled = true` keep SDK public APIs and `kotlinx.serialization` DTO serializers automatically.
- **Auto-merged manifest & loopback security:** `android.permission.INTERNET`, `<queries><package android:name="com.uoai.provider" /></queries>`, and `android:networkSecurityConfig="@xml/uoai_network_security_config"` (loopback-only cleartext for `127.0.0.1` / `localhost`) are merged automatically from the AAR. *(If your app defines its own `networkSecurityConfig`, add `tools:replace="android:networkSecurityConfig"` on `<application>` and whitelist `127.0.0.1`/`localhost`.)*
- **Model-agnostic default:** `ClientConfig.model` defaults to `"auto"`, routing requests to whichever Gemma 3 or Gemma 4 model is currently loaded on the provider.

### 7.1 Gradle Setup

If building inside this repository:
```kotlin
dependencies {
    implementation(project(":sdk"))
}
```
If integrating the compiled AAR into a standalone Android Studio project, copy `sdk-release.aar` into `app/libs/` and declare the SDK's transitive Kotlin dependencies:
```kotlin
dependencies {
    implementation(files("libs/sdk-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
```

### 7.2 Complete Jetpack Compose + ViewModel Integration

```kotlin
package com.example.myclient

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uoai.sdk.*
import com.uoai.sdk.exceptions.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AiUiState(
    val registered: Boolean = false,
    val status: String = "Not connected",
    val output: String = "",
    val streaming: Boolean = false,
)

class ClientAiViewModel(private val ai: UniversalAIClient) : ViewModel() {
    private val _ui = MutableStateFlow(AiUiState(registered = ai.isRegistered))
    val ui = _ui.asStateFlow()
    private var streamJob: Job? = null

    fun onRegistrationActivityResult(resultCode: Int, data: Intent?) {
        when (val res = ai.handleRegistrationResult(resultCode, data)) {
            RegistrationResult.Approved -> {
                _ui.update { it.copy(registered = true, status = "Approved! Connecting...") }
                connect()
            }
            is RegistrationResult.Denied -> {
                _ui.update { it.copy(status = "Denied (${res.code}): ${res.message}") }
            }
        }
    }

    fun connect() = viewModelScope.launch {
        try {
            val health = ai.connect() // verifies health + token via /models
            val caps = ai.capabilities()
            _ui.update {
                it.copy(
                    status = "Ready: state=${health.modelState}, ctx=${caps.contextWindowTokens}, maxOut=${caps.maxTokensLimit}",
                )
            }
        } catch (e: UniversalAIException) {
            handleError(e)
        }
    }

    fun generateOnce(prompt: String) = viewModelScope.launch {
        _ui.update { it.copy(output = "Generating...", streaming = true) }
        try {
            val res = ai.generate(
                prompt = prompt,
                system = "Be concise and accurate.",
                temperature = 0.7f,
                maxTokens = 512,
            )
            _ui.update { it.copy(output = res.text, streaming = false) }
        } catch (e: UniversalAIException) {
            handleError(e)
        }
    }

    fun streamPrompt(prompt: String) {
        streamJob?.cancel()
        _ui.update { it.copy(output = "", streaming = true) }
        streamJob = viewModelScope.launch {
            ai.generateStream(prompt = prompt, maxTokens = 1024)
                .catch { e ->
                    if (e is UniversalAIException) handleError(e)
                    else _ui.update { it.copy(status = "Error: ${e.message}", streaming = false) }
                }
                .collect { chunk ->
                    _ui.update { it.copy(output = it.output + chunk) }
                }
            _ui.update { it.copy(streaming = false) }
        }
    }

    fun cancelStream() {
        // Cancelling the Flow collector automatically disconnects & invokes POST /v1/cancel
        streamJob?.cancel()
        _ui.update { it.copy(streaming = false, status = "Cancelled") }
    }

    fun runWithClientTool(prompt: String, localDocReader: () -> String) = viewModelScope.launch {
        try {
            val docTool = ClientToolSpec(
                name = "read_active_document",
                description = "Reads the currently selected document in this app.",
                parameters = buildJsonObject { put("type", "object") },
            )
            val turn1 = ai.chat(
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                tools = listOf(docTool),
            )
            val call = turn1.toolCalls.firstOrNull()
            if (call == null) {
                _ui.update { it.copy(output = turn1.text) }
                return@launch
            }
            val toolResult = localDocReader()
            val turn2 = ai.chat(
                messages = listOf(
                    ChatMessage(role = "user", content = prompt),
                    ChatMessage(role = "assistant", content = "<tool_call>{\"name\":\"${call.name}\",\"arguments\":${call.argumentsJson}}</tool_call>"),
                    ChatMessage(role = "tool", content = toolResult, toolCallId = call.id, name = call.name),
                ),
            )
            _ui.update { it.copy(output = turn2.text) }
        } catch (e: UniversalAIException) {
            handleError(e)
        }
    }

    private fun handleError(e: UniversalAIException) {
        val msg = when (e) {
            is ProviderNotInstalledException -> "Install Universal Offline AI Provider first."
            is ProviderNotRunningException -> "Open Universal Offline AI Provider and tap Start API."
            is NotRegisteredException -> {
                ai.clearCredentials()
                _ui.update { it.copy(registered = false) }
                "Not registered or token revoked. Tap Register."
            }
            is ModelNotReadyException -> "Provider model is not loaded yet (${e.message})."
            is PermissionDeniedException -> "Permission denied in provider: ${e.message}"
            is RequestCancelledException -> "Request was cancelled."
            is ApiException -> "API error ${e.httpStatus} (${e.code}): ${e.message}"
            else -> e.message ?: "Unknown AI error"
        }
        _ui.update { it.copy(status = msg, streaming = false) }
    }
}

class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ai = UniversalAIClient(applicationContext)
        setContent {
            val vm = remember { ClientAiViewModel(ai) }
            val state by vm.ui.collectAsState()
            val regLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                vm.onRegistrationActivityResult(result.resultCode, result.data)
            }

            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.status)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching { ai.registrationIntent() }
                                .onSuccess { regLauncher.launch(it) }
                                .onFailure { /* ProviderNotInstalledException */ }
                        }) { Text("Register") }
                        Button(onClick = { vm.connect() }, enabled = state.registered) { Text("Connect") }
                        Button(onClick = { vm.streamPrompt("Explain offline AI in 2 sentences.") }) { Text("Stream") }
                        if (state.streaming) {
                            OutlinedButton(onClick = { vm.cancelStream() }) { Text("Cancel") }
                        }
                    }
                    Text(state.output)
                }
            }
        }
    }
}
```

### 7.3 SDK Public API Reference

| Class / Method | Signature / Purpose |
|---|---|
| `UniversalAIClient(context, config)` | Main client entry point (`config` defaults to `ClientConfig(model = "auto")`). Holds an Android Keystore-backed `TokenStore`. |
| `isRegistered: Boolean` | `true` if a credential is currently cached locally (verify live state via `checkConnection()` or `connect()`). |
| `isProviderInstalled(): Boolean` | Checks whether `config.providerPackage` (`com.uoai.provider`) is installed. |
| `registrationIntent(): Intent` | Builds the Intent for `startActivityForResult` (never add `FLAG_ACTIVITY_NEW_TASK`). Throws `ProviderNotInstalledException` if missing. |
| `openProviderIntent(): Intent?` | Returns a launch Intent (`FLAG_ACTIVITY_NEW_TASK`) to open Universal Offline AI Provider when its server or model is not ready. |
| `handleRegistrationResult(resultCode, data)` | Parses Activity result; stores token in Keystore on approval and returns `RegistrationResult.Approved` or `.Denied`. |
| `clearCredentials()` | Deletes the cached token from Keystore-encrypted preferences. |
| `suspend fun checkConnection(): ConnectionState` | Single-call preflight checking install status, registration, server reachability, token validity (clears stale tokens), and model readiness. Returns `ConnectionState` (`ProviderNotInstalled`, `NeedsRegistration`, `ProviderNotRunning`, `ModelNotReady`, `Connected`, `Error`). |
| `fun startChat(...): ChatSession` | Creates a stateful multi-turn `ChatSession` that tracks local conversation history, prepends optional `systemPrompt`, trims to `maxHistoryMessages`, and rolls back/preserves turns safely on errors or cancellation. |
| `suspend fun connect(): ProviderHealth` | Calls `/health` then `/models` to confirm server reachability, model readiness, and token validity. |
| `suspend fun health(): ProviderHealth` | Unauthenticated readiness check (`isReady`, `modelState`, `modelError`). |
| `suspend fun models(): List<ModelInfo>` | Returns installed models with `ready` and `contextWindow`. |
| `suspend fun capabilities(): Capabilities` | Returns `maxRequestBytes`, `maxTokensLimit`, `contextWindowTokens`, `defaultMaxTokens`, `providerTools`. |
| `suspend fun generate(...)` | Single-prompt non-streaming helper (`GenerationResult(text, requestId, toolCalls)`). |
| `suspend fun chat(...)` | Multi-turn / tool-calling non-streaming completion (`List<ChatMessage>`). |
| `fun generateStream(...)` | Cold `Flow<String>` streaming chunks for a single prompt; cancelling collection cancels server execution. |
| `fun chatStream(...)` | Cold `Flow<String>` streaming chunks for multi-turn `List<ChatMessage>`. |
| `suspend fun cancel(requestId): Boolean` | Explicitly cancels an in-flight request by `X-Request-Id`. |

---

## 8. Implementation Guide B — Direct HTTP/SSE Integration (Without the SDK)

If your Android app (or cross-platform Flutter/React Native bridge) prefers direct networking with **OkHttp**, **Ktor Client**, or `HttpURLConnection`, implement these three steps:

### 8.1 Launch Registration Intent & Store Token
Launch `Intent("com.uoai.provider.action.REGISTER").setPackage("com.uoai.provider")` with `ActivityResultContracts.StartActivityForResult()`. On `Activity.RESULT_OK`, read `data?.getStringExtra("token")` and store it in EncryptedSharedPreferences / Android Keystore (never in plaintext logs).

### 8.2 Non-Streaming Request (OkHttp Example)
```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .build()

val payload = """
    {
      "model": "gemma-3",
      "messages": [
        {"role": "system", "content": "Answer concisely."},
        {"role": "user", "content": "List 3 benefits of on-device AI."}
      ],
      "temperature": 0.7,
      "max_tokens": 512,
      "stream": false
    }
""".trimIndent()

val request = Request.Builder()
    .url("http://127.0.0.1:8765/v1/chat/completions")
    .header("Authorization", "Bearer $token")
    .post(payload.toRequestBody("application/json".toMediaType()))
    .build()

client.newCall(request).execute().use { response ->
    val requestId = response.header("X-Request-Id")
    val body = response.body?.string().orEmpty()
    if (!response.isSuccessful) {
        // Parse {"error":{"message","type","code"}}
        error("Provider HTTP ${response.code}: $body")
    }
    // Parse choices[0].message.content (or choices[0].message.tool_calls when finish_reason == "tool_calls")
}
```

### 8.3 SSE Streaming Line Parser
For `"stream": true`:
1. Set header `Accept: text/event-stream` and save `response.header("X-Request-Id")` immediately.
2. Read the response body line by line:
   - Ignore empty lines and lines not starting with `data:`.
   - Strip `"data:"` and trim whitespace.
   - If `"data"` equals `"[DONE]"`, stop cleanly.
   - Otherwise parse the JSON object:
     - If `"error"` key exists, abort and handle the error payload.
     - Extract `choices[0].delta.content` (if present and non-null) and append to UI.
     - If `choices[0].finish_reason == "cancelled"`, handle cancellation.
3. To cancel early: send `POST http://127.0.0.1:8765/v1/cancel` with `{"request_id":"<X-Request-Id>"}` (and close the SSE socket).

---

## 9. Production Best Practices, 32K Context Management & ADB Debugging

### 9.1 Context Window & Token Budgeting (Up to 32K)
- Always inspect `GET /v1/capabilities` after connecting:
  - `context_window_tokens` reflects the active KV cache configured on the provider (between `512` and `32,768`).
  - `max_tokens_limit` is `32,768`, while `default_max_tokens` is the provider's configured default output limit.
  - Keep client conversation history trimmed so `input_tokens + requested max_tokens <= context_window_tokens`.
- Keep conversation history inside your client app; the provider persists request metadata only, never prompt text.

### 9.2 Handling Provider Lifecycle (`ProviderService`)
- `ProviderService` is intentionally **non-sticky** (`START_NOT_STICKY`): if the user stops the API or Android terminates the background process under extreme memory pressure, your next request throws `ProviderNotRunningException` (`ConnectException`).
- Offer a one-tap recovery button in your client app to open the provider app so the user can tap **Start API** / **Load Model**:
  ```kotlin
  packageManager.getLaunchIntentForPackage("com.uoai.provider")?.let { startActivity(it) }
  ```

### 9.3 Debugging Over ADB (Developer Workstation)
Forward port `8765` from a connected Android device to your workstation:
```bash
adb forward tcp:8765 tcp:8765

# 1. Unauthenticated health check
curl -s http://127.0.0.1:8765/v1/health

# 2. Check models & capabilities (requires an approved client's Bearer token)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/v1/models
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/v1/capabilities

# 3. Streaming SSE test
curl -s -N -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"model":"gemma-3","messages":[{"role":"user","content":"Hello!"}],"stream":true,"max_tokens":128}' \
  http://127.0.0.1:8765/v1/chat/completions
```

### 9.4 Client App Pre-Release Checklist
- [ ] `<uses-permission android:name="android.permission.INTERNET" />` and `<queries><package android:name="com.uoai.provider" /></queries>` added to `AndroidManifest.xml`.
- [ ] `network_security_config.xml` restricts cleartext traffic strictly to `127.0.0.1` / `localhost`.
- [ ] Registration launched via `startActivityForResult` / `ActivityResultContracts.StartActivityForResult` (never with `FLAG_ACTIVITY_NEW_TASK`).
- [ ] Token stored in Android Keystore (automatic when using `:sdk`) and never logged.
- [ ] Client handles `ProviderNotInstalledException`, `ProviderNotRunningException`, `NotRegisteredException`, `ModelNotReadyException`, and `PermissionDeniedException` gracefully.
- [ ] Client APK verified to contain **zero** `.task`, `.litertlm`, `.bin` model weights or LLM runtimes (`unzip -l app.apk`).
