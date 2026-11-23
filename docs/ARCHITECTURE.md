# Architecture — Universal Offline AI Provider

## 1. High-Level Overview

```
+-------------------------------------------------------------------------+
|                    Universal Offline AI Provider App                    |
|                                                                         |
|  +--------------------+   +------------------+   +-------------------+  |
|  | Jetpack Compose UI |   | Registration     |   | ProviderService   |  |
|  | 5-Tab M3 Console:  |   | Activity         |   | (Foreground Svc)  |  |
|  | Overview / Model / |   | - OS Package &   |   |         |         |  |
|  | Test / Clients /   |   |   Cert SHA-256   |   |         v         |  |
|  | Plugins            |   |                  |   | Ktor CIO Server   |  |
|  +---------+----------+   +--------+---------+   | 127.0.0.1:8765    |  |
|            v                       v             +---------+---------+  |
|  +---------------------------------------------------------+---------+  |
|  | ClientRepository & PluginManager (Room DB + Audit Trail)          |  |
|  +---------------------------------------------------------+---------+  |
|                                                            |            |
|                                                            v            |
|  +-------------------------------------------------------------------+  |
|  | DefaultInferenceEngine (PriorityQueue Scheduler, 1 active worker) |  |
|  +---------------------------------+---------------------------------+  |
|                                    |                                    |
|                                    v                                    |
|  +-------------------------------------------------------------------+  |
|  | ModelManager -> CompositeInferenceRuntime                          |  |
|  |   ├── LiteRtLmRuntimeAdapter (LiteRT-LM 0.19.1, .litertlm, Gemma 4)|  |
|  |   └── GemmaRuntimeAdapter    (MediaPipe 0.10.27, .task, Gemma 3)   |  |
|  | Single Shared Gemma 4 / Gemma 3 Instance in Native Heap            |  |
|  +-------------------------------------------------------------------+  |
+-------------------------------------------------------------------------+
                   ^                                     ^
                   | HTTP/SSE (127.0.0.1:8765)           | HTTP/SSE (127.0.0.1:8765)
                   | Bearer uoai_<token>                 | Bearer uoai_<token>
        +----------+-----------+              +----------+-----------+
        | Client App 1         |              | Client App 2         |
        | (com.uoai.sample)    |              | (com.uoai.sample2)   |
        | +------------------+ |              | +------------------+ |
        | | :sdk (90 KB AAR) | |              | | :sdk (90 KB AAR) | |
        | +------------------+ |              | +------------------+ |
        | No weights / runtime |              | No weights / runtime |
        +----------------------+              +----------------------+
```

## 2. Module Boundaries

| Module | Responsibilities | Dependencies |
|---|---|---|
| `:provider-app` | 5-tab Material 3 Compose UI (`OverviewTab`, `ModelSettingsTab`, `PlaygroundTab`, `ClientsTab`, `PluginsTab`), `RegistrationActivity`, `ProviderService`, Hilt DI wiring (`AppModule`), `RoomRequestRecorder` | `:core`, `:inference`, `:provider-api`, `:client-management`, `:plugins`, `:android-integration` |
| `:core` | `DispatcherProvider`, `SecretVault` (Android Keystore AES-256-GCM), Room `AppDatabase` and all 8 entities/DAOs | AndroidX Room, Kotlin Coroutines, Serialization |
| `:inference` | `ModelState`, `ModelManager` (`load`/`reload`/`selectModel`), `ModelCatalog` (12 Gemma 4 + 5 Gemma 3 presets), `InferenceSettings` / `FileInferenceSettingsRepository` (`inference_settings.json`, context window 512–32K, max output up to 32K, backend Auto/CPU/GPU/NPU), `ModelDownloader` (resumable HF downloader + disk-space check), `ModelIntegrity`, `InferenceEngine`, `DefaultInferenceEngine` (priority scheduler), `InferenceRuntime`, `CompositeInferenceRuntime`, `LiteRtLmRuntimeAdapter` (`litertlm-android` 0.19.1), `GemmaRuntimeAdapter` (MediaPipe `tasks-genai` 0.10.27, `maxTopK = 128`), `MockInferenceRuntime` | `:core`, LiteRT-LM, MediaPipe GenAI |
| `:provider-api` | `LocalHttpServer`, `KtorLocalHttpServer` (bound strictly to `127.0.0.1:8765`), `ProviderApi` routes, OpenAI-compatible DTOs, `ToolCallingSupport` | `:core`, `:inference`, `:client-management`, `:plugins`, Ktor Server CIO |
| `:client-management` | `ClientRegistry`, `ClientRepository`, `CallerIdentityResolver` (OS package + signing cert SHA-256 verification), token hashing (`SHA-256`), per-client permissions & audit logging | `:core` |
| `:plugins` | `PluginRegistry`, `PluginManager`, `SafeToolHandler`, predefined offline tools (`SafeCalculatorHandler`, `DeviceInfoHandler`) | `:core` |
| `:android-integration` | Optional automation subsystem kept separate from core inference: `ApprovedIntentExecutor` and `OptInAccessibilityCapability` | `:core` |
| `:sdk` | Standalone client library (`sdk-debug.aar`, 90 KB): `UniversalAIClient`, `ApiClient`, `TokenStore` (Keystore-backed), `ClientConfig`, typed exceptions | Kotlin Coroutines & Serialization only (zero LLM or provider dependencies) |
| `:sample-client` | Independent Android client application (`com.uoai.sample`, and configurable via `-PclientAppId=com.uoai.sample2`) demonstrating registration, non-streaming, streaming, and cancellation | `:sdk` only |

Architectural rules enforced:
- `:inference` has **no** dependency on Jetpack Compose, Ktor, or Room.
- `:provider-api` never invokes `GemmaRuntimeAdapter` directly; it communicates only with `InferenceEngine`, `ClientRegistry`, and `PluginRegistry`.
- `:sdk` and `:sample-client` contain zero model weights and zero native LLM libraries.

## 3. Room Database Schema (8 Entities)
Defined in `core/src/main/kotlin/com/uoai/core/database/Entities.kt`:
1. `registered_clients` (`id`, `package_name`, `app_name`, `signing_certificate_digest`, `status`, `permissions_json`, `approved_at`, `last_connected_at`, `created_at`)
2. `client_credentials` (`id`, `client_id`, `token_hash`, `created_at`, `revoked_at`) — **raw tokens are never stored**
3. `inference_requests` (`id`, `client_id`, `model_id`, `request_status`, `request_json` [metadata only, no raw prompt content], `created_at`, `started_at`, `completed_at`, `error_message`)
4. `models` (`id`, `model_name`, `model_version`, `model_path`, `model_format`, `quantization`, `file_size`, `installation_status`, `created_at`)
5. `plugins` (`id`, `name`, `version`, `description`, `adapter_type`, `config_json`, `enabled`, `created_at`, `updated_at`)
6. `plugin_tools` (`id`, `plugin_id`, `name`, `description`, `input_schema`, `execution_config`, `enabled`)
7. `permissions` (`id`, `client_id`, `plugin_id`, `capability`, `granted`, `requires_confirmation`)
8. `audit_logs` (`id`, `client_id`, `action`, `resource`, `status`, `details_json`, `created_at`)

## 4. Security Architecture
- **Loopback Binding:** `KtorLocalHttpServer` hard-codes `host = "127.0.0.1"` and `port = 8765`.
- **OS-Verified Caller Identity:** `RegistrationActivity` is launched via `startActivityForResult`. It reads `callingPackage` from the Android OS and verifies the package's `GET_SIGNING_CERTIFICATES` SHA-256 digest via `CallerIdentityResolver`. A package name in an intent extra or HTTP header is never trusted.
- **Certificate Pinning per Package:** If an already-registered package name presents a different signing certificate digest, registration is denied (`CertificateMismatch`) and recorded in `audit_logs`.
- **Credential Protection:** Issued tokens (`uoai_<256-bit base64url>`) are returned once to the approved client, stored by the client in Android Keystore-encrypted SharedPreferences (`TokenStore`), and stored by the provider only as a `SHA-256` hex digest in `client_credentials.token_hash`.
- **Per-Client Policy Enforcement:** Each client has `ClientPermissions` (`chat`, `streaming`, `toolCalling`, `maxTokens`, `priority`) enforced on every `/v1/chat/completions` call.
- **No Arbitrary Plugin Code Execution:** `PluginManager` only executes compiled `SafeToolHandler` implementations registered in code and enabled in Room.
