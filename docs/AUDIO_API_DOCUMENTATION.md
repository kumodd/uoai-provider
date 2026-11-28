# Universal Offline AI Provider — Audio & External Inference API Documentation

**API Version:** `v1`  
**Base URL:** `http://127.0.0.1:8765/v1` (loopback only)  
**Provider Package:** `com.uoai.provider`  
**Registration Intent:** `com.uoai.provider.action.REGISTER`  
**Preferred Transcription Engine:** **Direct Gemma 4 Multimodal (`provider=gemma4`)**

Universal Offline AI Provider exposes a unified audio transcription and speech-to-text API for Android client apps and developer tooling. It supports:
1. **Direct On-Device Gemma 4 Multimodal Transcription (`gemma4`) — Preferred:** Passes raw audio directly into Gemma 4 E2B / E4B's native Conformer audio encoder via LiteRT-LM for high-accuracy multilingual, Hinglish, and regional dialect transcription.
2. **Dedicated On-Device Whisper ASR (`whisper`):** 99-language offline `whisper.cpp` (`tiny`, `base`, `small` Q5_1).
3. **Specialized On-Device Hinglish Cactus ASR (`cactus`):** Lightweight 4-bit `akshar-v4`, `akshar-lite-v4`, and `shruti-hi` models.
4. **Configurable External Inference Providers:** Pluggable HTTP routing to external OpenAI-compatible audio endpoints, OpenAI-compatible multimodal chat servers (e.g., remote Gemma 4 / vLLM / LiteLLM), and Google Gemini / Gemma API endpoints — without replacing or disrupting local models.

---

## Table of Contents
1. [Available Endpoints](#1-available-endpoints)
2. [Authentication & Permissions](#2-authentication--permissions)
3. [Supported Audio Input Formats & Limits](#3-supported-audio-input-formats--limits)
4. [Model & Provider Selection](#4-model--provider-selection)
5. [Endpoint Reference (Request & Response Formats)](#5-endpoint-reference-request--response-formats)
6. [Example API Calls (`curl`, Kotlin SDK, OkHttp, Python)](#6-example-api-calls-curl-kotlin-sdk-okhttp-python)
7. [Error Codes & Troubleshooting](#7-error-codes--troubleshooting)

---

## 1. Available Endpoints

| Method | Path | Auth | Required Permission | Purpose |
|---|---|---|---|---|
| `GET` | `/v1/health` | None | — | Provider & Gemma 4 LLM readiness |
| `GET` | `/v1/capabilities` | Bearer | Any approved client | Server limits, features (`gemma4-audio`, `asr`, `external-inference`), active providers |
| `GET` | `/v1/audio/status` | Bearer | Any approved client | Live ASR state, `gemma4_audio_ready`, preferred provider, queue depth, limits |
| `GET` | `/v1/audio/models` | Bearer | Any approved client | List all available audio models across Gemma 4, Whisper, Cactus, and external providers |
| `GET` | `/v1/audio/providers` | Bearer | Any approved client | List built-in local providers and configured external inference providers |
| `POST` | `/v1/audio/providers` | Bearer | `asr_model_control` | Register or update an external inference provider (API key encrypted in Android Keystore) |
| `DELETE` | `/v1/audio/providers/{id}` | Bearer | `asr_model_control` | Remove a configured external inference provider |
| `POST` | `/v1/audio/transcriptions` | Bearer | `speech_to_text` | Transcribe or translate audio (`json`, `verbose_json`, or plain `text`) |
| `POST` | `/v1/audio/stream` | Bearer | `speech_to_text` + `streaming` | Server-Sent Events (SSE) streaming transcription |
| `POST` | `/v1/audio/models/load` | Bearer | `asr_model_control` | Preload a Whisper or Cactus audio model into RAM |
| `POST` | `/v1/audio/models/unload` | Bearer | `asr_model_control` | Unload active local Whisper/Cactus audio models from RAM |
| `POST` | `/v1/cancel` | Bearer | Request owner | Cancel an in-flight audio or LLM request by `X-Request-Id` |

---

## 2. Authentication & Permissions

### 2.1 Obtaining a Client Bearer Token (Android OS-Verified Registration)
Every `/v1/audio/*` endpoint requires a per-app Bearer token:

```http
Authorization: Bearer uoai_<token>
```

1. Launch `Intent("com.uoai.provider.action.REGISTER").setPackage("com.uoai.provider")` using `startActivityForResult` / `ActivityResultContracts.StartActivityForResult` (**never** pass `FLAG_ACTIVITY_NEW_TASK`).
2. The provider verifies the calling APK's package name and single SHA-256 signing certificate via the Android OS.
3. The user approves requested permissions on-device:
   - **`speech_to_text`** — required for `POST /v1/audio/transcriptions` and `POST /v1/audio/stream`.
   - **`streaming`** — additionally required for `POST /v1/audio/stream`.
   - **`asr_model_control`** — required for `POST /v1/audio/models/load`, `POST /v1/audio/models/unload`, `POST /v1/audio/providers`, and `DELETE /v1/audio/providers/{id}`.
4. On `Activity.RESULT_OK`, persist `data.getStringExtra("token")` securely in Android Keystore (handled automatically by `:sdk`).

### 2.2 External Provider Upstream Credentials
When registering an external inference provider via `POST /v1/audio/providers` (or in the Provider app under **Models -> Speech -> External Inference Providers**):
- Upstream `api_key` values are encrypted with AES-256-GCM in Android Keystore (`SecretVault`) and **never returned in API responses** (`GET /v1/audio/providers` returns only `"has_api_key": true/false`).
- Default auth headers by provider type:
  - `openai_compatible_audio` / `openai_compatible_chat_audio`: `Authorization: Bearer <api_key>`
  - `gemini_multimodal`: `x-goog-api-key: <api_key>`
  - Both `api_key_header` and `api_key_prefix` (plus `custom_headers`) are customizable per provider.

---

## 3. Supported Audio Input Formats & Limits

The provider accepts audio via either:
- **`multipart/form-data`** with binary file part `file` and form fields (`model`, `provider`, `language`, `prompt`, `script`, `response_format`, `temperature`, `translate`, `timestamps`, `sample_rate`, `channels`), **OR**
- **Raw binary request body** (`Content-Type: audio/wav`, `audio/pcm`, `audio/mpeg`, `audio/mp4`, etc.) with parameters passed as URL query parameters.

### Supported Input Formats
All uploads are decoded internally to 16 kHz mono PCM (and WAV when sent to Gemma 4 or external providers):

| Format | Extensions | MIME Types | Notes |
|---|---|---|---|
| **WAV** | `.wav` | `audio/wav`, `audio/x-wav` | PCM 8/16/24/32-bit int & IEEE float32, auto-resampled to 16 kHz mono |
| **Raw PCM** | `.pcm`, `.raw` | `audio/pcm`, `audio/L16` | Signed 16-bit LE; pass `sample_rate` (default `16000`) & `channels` (default `1`) |
| **MP3** | `.mp3` | `audio/mpeg` | Decoded via Android `MediaCodec` |
| **MP4 / M4A / AAC** | `.m4a`, `.mp4`, `.aac` | `audio/mp4`, `audio/m4a`, `audio/aac` | Decoded via Android `MediaCodec` |
| **OGG / Opus** | `.ogg`, `.opus` | `audio/ogg`, `audio/opus` | Decoded via Android `MediaCodec` |
| **FLAC** | `.flac` | `audio/flac` | Decoded via Android `MediaCodec` |
| **WebM / AMR** | `.webm`, `.amr` | `audio/webm`, `audio/amr` | Decoded via Android `MediaCodec` |

### Limits
- **Max upload size:** `26,214,400` bytes (25 MiB) — returns HTTP `413` (`audio_too_large`) if exceeded.
- **Max audio duration:** `600` seconds (10 minutes) — returns HTTP `400` (`audio_too_long`) if exceeded.
- **Request timeout:** `180,000 ms` (180 s) default (`timeout_ms` configurable per external provider up to `300,000 ms`).
- **Temporary file hygiene:** Uploaded audio temp files are deleted immediately after decoding (including on error/cancellation).

---

## 4. Model & Provider Selection

Callers can select the transcription backend via `provider` and/or `model` parameters.

### 4.1 Built-In Local Providers (100% Offline)

| `provider` | Accepted `model` Values | Engine & Capabilities |
|---|---|---|
| **`gemma4`** *(Recommended)* | `gemma-4`, `gemma-4-e2b`, `gemma-4-e4b`, `gemma-4-audio`, `llm-direct` | **Direct Gemma 4 Multimodal** via LiteRT-LM Conformer audio encoder. Best Hinglish, dialect, punctuation, and multilingual fidelity. Auto-chunks audio > 15 s. |
| **`whisper`** | `whisper-1` *(OpenAI alias)*, `whisper-tiny-q5` (`fast`), `whisper-base-q5` (`balanced`), `whisper-small-q5` (`accurate`) | Dedicated `whisper.cpp v1.9.6` on-device ASR (99 languages + timestamps + `translate=true`). |
| **`cactus`** | `akshar-v4`, `akshar-lite-v4`, `shruti-hi` | Lightweight 4-bit/2-bit Hindi/Hinglish Cactus Whistle models (~20–30 MB disk, ~64–80 MB RAM). |

### 4.2 External Inference Provider Types

| `type` | Upstream Endpoint Called | Compatible Backends |
|---|---|---|
| `openai_compatible_chat_audio` | `POST {base_url}/chat/completions` (`input_audio` WAV) | Self-hosted Gemma 4 (vLLM, llama.cpp server), LiteLLM, OpenRouter, OpenAI audio-preview models |
| `openai_compatible_audio` | `POST {base_url}/audio/transcriptions` (multipart WAV) | OpenAI Whisper (`whisper-1`, `gpt-4o-transcribe`), Groq Whisper, Faster-Whisper servers, LocalAI |
| `gemini_multimodal` | `POST {base_url}/models/{model}:generateContent` (`inline_data` WAV) | Google Gemini / Gemma API (`gemini-2.5-flash`, Gemma multimodal endpoints) |

### 4.3 Resolution Order & Backward Compatibility
1. **Explicit `provider` parameter** always takes precedence (`gemma4`, `whisper`, `cactus`, or a registered external provider `id`).
2. **Explicit `model` parameter** (when `provider` is omitted or `"auto"`):
   - `gemma-4*` / `llm-direct` -> `gemma4`
   - `akshar-v4` / `akshar-lite-v4` / `shruti-hi` -> `cactus`
   - `whisper*` / `fast` / `balanced` / `accurate` -> `whisper`
   - Matches any enabled external provider's `default_model` or `supported_models` -> that external provider
3. **Both omitted or `"auto"`:**
   - Preserves backward compatibility with existing Whisper clients when a Whisper model is loaded or installed with auto-load enabled.
   - Automatically falls back to **local direct Gemma 4** when Whisper is not loaded/installed and multimodal Gemma 4 is ready.

### 4.4 Hinglish Script Output (`script` parameter)
Optional `script` parameter works across **all** providers (Gemma 4, Whisper, Cactus, and External):
- `canonical` *(default)* — unmodified verbatim model transcript.
- `devanagari` (`devanagari_hinglish`) — Devanagari Hindi + preserved English words.
- `roman` (`roman_hinglish`) — natural Roman Hinglish (e.g., `"Bhai kal meeting hai, tum presentation bhej dena."`).
- `both` — Devanagari line followed by Roman line.

JSON responses also include `roman_hinglish` and `devanagari_hinglish` fields whenever `script` is passed or Devanagari text is detected.

---

## 5. Endpoint Reference (Request & Response Formats)

### 5.1 `POST /v1/audio/transcriptions`
**Headers:** `Authorization: Bearer uoai_<token>`  
**Response Header:** `X-Request-Id: asr-<uuid>`

**Parameters (Multipart Form Fields OR Query Params for raw audio):**
- `file` *(multipart only, required)* — Audio binary.
- `provider` *(optional, default `"auto"`)* — `"gemma4"`, `"whisper"`, `"cactus"`, or external provider `id`.
- `model` *(optional, default `"auto"`)* — Model ID (e.g. `"gemma-4"`, `"gemma-4-e2b"`, `"whisper-1"`, `"akshar-v4"`, `"gemini-2.5-flash"`).
- `language` *(optional)* — `"auto"`, `"en,hi"` (Hinglish constrained), ISO-639-1 code (`"en"`, `"hi"`, `"mr"`, `"ta"`, etc.).
- `prompt` *(optional)* — Domain vocabulary or context hint.
- `script` *(optional)* — `"canonical"`, `"devanagari"`, `"roman"`, `"both"`.
- `response_format` *(optional, default `"json"`)* — `"json"`, `"verbose_json"`, or `"text"`.
- `temperature` *(optional, default `0.0`)* — Decoding temperature (`0.0`–`1.0`).
- `translate` *(optional, default `false`)* — `true` to translate speech into English.
- `timestamps` *(optional, default `true`)* — Include segment timestamps.
- `sample_rate` / `channels` *(raw PCM only)* — Default `16000` Hz, `1` channel.

**Response (`200 OK`, `response_format=json`):**
```json
{
  "id": "asr-9e7c3114-58d2-4d30-a1a7-01a2b3c4d5e6",
  "text": "भाई कल meeting है, तुम presentation भेज देना।",
  "model": "gemma-4-e2b",
  "language": "en,hi",
  "provider": "gemma4",
  "roman_hinglish": "Bhai kal meeting hai, tum presentation bhej dena.",
  "devanagari_hinglish": "भाई कल meeting है, तुम presentation भेज देना।"
}
```

**Response (`200 OK`, `response_format=verbose_json`):**
```json
{
  "id": "asr-9e7c3114-58d2-4d30-a1a7-01a2b3c4d5e6",
  "task": "transcribe",
  "model": "gemma-4-e2b",
  "language": "en,hi",
  "duration": 4.2,
  "text": "Bhai kal meeting hai, tum presentation bhej dena.",
  "segments": [
    {
      "id": 0,
      "start": 0.0,
      "end": 4.2,
      "text": "भाई कल meeting है, तुम presentation भेज देना।"
    }
  ],
  "elapsed_ms": 1420,
  "provider": "gemma4",
  "roman_hinglish": "Bhai kal meeting hai, tum presentation bhej dena.",
  "devanagari_hinglish": "भाई कल meeting है, तुम presentation भेज देना।"
}
```

---

### 5.2 `POST /v1/audio/stream` (SSE)
Accepts the same upload formats and parameters as `/v1/audio/transcriptions` and responds with `Content-Type: text/event-stream`:

```text
data: {"id":"asr-123","object":"audio.transcription.start","model":"gemma-4-e2b","language":"en,hi","provider":"gemma4"}

data: {"id":"asr-123","object":"audio.transcription.segment","index":0,"start":0.0,"end":4.2,"text":"Bhai kal meeting hai.","language":"en,hi","is_final":true,"provider":"gemma4"}

data: {"id":"asr-123","object":"audio.transcription.done","model":"gemma-4-e2b","language":"en,hi","duration":4.2,"text":"Bhai kal meeting hai.","finish_reason":"stop","provider":"gemma4"}

data: [DONE]
```

---

### 5.3 `GET /v1/audio/providers`
Lists built-in local providers (`gemma4`, `whisper`, `cactus`) plus any configured external inference providers.

```json
{
  "object": "list",
  "preferred_provider": "gemma4",
  "data": [
    {
      "id": "gemma4",
      "name": "Gemma 4 Direct Multimodal (On-Device)",
      "kind": "local_multimodal_llm",
      "builtin": true,
      "enabled": true,
      "ready": true,
      "recommended": true,
      "default_model": "gemma-4-e2b",
      "models": ["gemma-4-e2b", "gemma-4", "gemma-4-audio"],
      "supports_streaming": true,
      "note": "Recommended: direct multimodal audio-to-text with dialect & Hinglish awareness."
    },
    {
      "id": "whisper",
      "name": "Whisper (whisper.cpp On-Device)",
      "kind": "local_asr",
      "builtin": true,
      "enabled": true,
      "ready": true,
      "recommended": false,
      "default_model": "whisper-base-q5",
      "models": ["whisper-tiny-q5", "whisper-base-q5", "whisper-small-q5", "whisper-1"],
      "supports_streaming": true
    },
    {
      "id": "remote-gemma4",
      "name": "Self-Hosted Gemma 4 Server",
      "kind": "openai_compatible_chat_audio",
      "builtin": false,
      "enabled": true,
      "ready": true,
      "recommended": false,
      "base_url": "https://inference.example.com/v1",
      "default_model": "gemma-4-e4b",
      "models": ["gemma-4-e4b"],
      "has_api_key": true,
      "supports_streaming": true
    }
  ]
}
```

---

### 5.4 `POST /v1/audio/providers` (Requires `asr_model_control`)
Registers or updates an external inference provider. Calling again with an existing `id` updates the config (omit `api_key` to keep the existing Keystore secret intact).

**Request Body:**
```json
{
  "id": "remote-gemma4",
  "display_name": "Self-Hosted Gemma 4 Server",
  "type": "openai_compatible_chat_audio",
  "base_url": "https://inference.example.com/v1",
  "default_model": "gemma-4-e4b",
  "supported_models": ["gemma-4-e4b", "gemma-4-e2b"],
  "api_key": "sk-your-upstream-key",
  "enabled": true,
  "timeout_ms": 120000
}
```

**Response (`200 OK`):** Returns the saved `AudioProviderDto` with `"has_api_key": true` (raw key is never echoed).

### 5.5 `DELETE /v1/audio/providers/{id}` (Requires `asr_model_control`)
Deletes an external inference provider and wipes its Keystore secret. Built-in IDs (`gemma4`, `whisper`, `cactus`) return HTTP `400` (`reserved_provider`).

```json
{
  "id": "remote-gemma4",
  "deleted": true
}
```

---

### 5.6 `GET /v1/audio/models` & `GET /v1/audio/status`
- `GET /v1/audio/models` lists all Whisper profiles, active local Gemma 4 direct audio model, Hinglish Cactus models (`akshar-v4`, `akshar-lite-v4`, `shruti-hi`), and all models advertised by enabled external providers (each tagged with `provider` and `engine_type`).
- `GET /v1/audio/status` reports `gemma4_audio_ready`, `preferred_provider`, `external_providers_count`, active Whisper state, queue depth, and hard upload limits.

---

### 5.7 `POST /v1/audio/models/load` & `POST /v1/audio/models/unload` (Requires `asr_model_control`)
- `POST /v1/audio/models/load` with `{"model": "balanced"}` (or `"fast"`, `"accurate"`, `"akshar-v4"`, `"akshar-lite-v4"`, `"shruti-hi"`) preloads a dedicated local ASR model into RAM.
- `POST /v1/audio/models/unload` with `{"force": false}` unloads local Whisper and Cactus models from RAM.

---

## 6. Example API Calls (`curl`, Kotlin SDK, OkHttp, Python)

### 6.1 ADB Port Forwarding + `curl` Examples

```bash
adb forward tcp:8765 tcp:8765
export TOKEN="uoai_your_registered_client_token"

# 1. Preferred: Direct On-Device Gemma 4 Multimodal Transcription (Hinglish -> Roman + Devanagari)
curl -s -X POST http://127.0.0.1:8765/v1/audio/transcriptions \
  -H "Authorization: Bearer $TOKEN" \
  -F "provider=gemma4" \
  -F "model=gemma-4" \
  -F "language=en,hi" \
  -F "script=roman" \
  -F "response_format=verbose_json" \
  -F "file=@sample.wav;type=audio/wav"

# 2. On-Device Whisper (OpenAI-compatible raw WAV fast path)
curl -s -X POST "http://127.0.0.1:8765/v1/audio/transcriptions?provider=whisper&model=whisper-1&language=en" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: audio/wav" \
  --data-binary @sample.wav

# 3. On-Device Hinglish Cactus ASR (AksharSTT v4 or Shruti)
curl -s -X POST "http://127.0.0.1:8765/v1/audio/transcriptions?model=akshar-v4&script=both&language=hi" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: audio/wav" \
  --data-binary @sample.wav

# 4. Stream Gemma 4 Direct Audio Transcription over SSE
curl -s -N -X POST "http://127.0.0.1:8765/v1/audio/stream?provider=gemma4&language=en,hi" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: audio/wav" \
  --data-binary @sample.wav

# 5. Register an External Inference Provider (e.g., Remote Gemma 4 or OpenAI/Gemini)
curl -s -X POST http://127.0.0.1:8765/v1/audio/providers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "gemini-cloud",
    "display_name": "Gemini Multimodal",
    "type": "gemini_multimodal",
    "base_url": "https://generativelanguage.googleapis.com/v1beta",
    "default_model": "gemini-2.5-flash",
    "api_key": "YOUR_GEMINI_API_KEY"
  }'

# 6. Transcribe via the Configured External Provider
curl -s -X POST "http://127.0.0.1:8765/v1/audio/transcriptions?provider=gemini-cloud&language=en,hi" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: audio/wav" \
  --data-binary @sample.wav
```

### 6.2 Android Kotlin SDK (`:sdk` / `sdk-release.aar`)

```kotlin
val ai = UniversalAIClient(applicationContext)

// 1. Inspect available providers (local Gemma 4, Whisper, Cactus, and external providers)
val providers = ai.audioProviders()
val gemma4Ready = providers.firstOrNull { it.id == "gemma4" }?.ready == true

// 2. Preferred: Direct on-device Gemma 4 multimodal transcription
val gemmaResult = ai.transcribe(
    audioFile = recordedWavFile,
    provider = "gemma4",
    model = "gemma-4",
    language = "en,hi",
    script = "roman", // "canonical" | "devanagari" | "roman" | "both"
)
println("Roman: ${gemmaResult.romanHinglish}")
println("Devanagari: ${gemmaResult.devanagariHinglish}")

// 3. Dedicated local Whisper or Hinglish Cactus ASR (existing workflows unchanged)
val whisperResult = ai.transcribe(audioFile = recordedWavFile, model = "whisper-1", language = "en")
val aksharResult = ai.transcribe(audioFile = recordedWavFile, model = "akshar-v4", language = "hi")

// 4. Register and invoke an external inference provider (requires asr_model_control permission)
ai.registerExternalAudioProvider(
    ExternalAudioProviderRegistration(
        id = "remote-gemma4",
        displayName = "Team Gemma 4 Server",
        type = "openai_compatible_chat_audio",
        baseUrl = "https://inference.example.com/v1",
        defaultModel = "gemma-4-e4b",
        apiKey = "sk-...",
    ),
)
val externalResult = ai.transcribe(
    audioFile = recordedWavFile,
    provider = "remote-gemma4",
    language = "en,hi",
)

// 5. Streaming SSE transcription with automatic cancellation on coroutine cancel
ai.transcribeStream(
    audioFile = recordedWavFile,
    provider = "gemma4",
    language = "en,hi",
).collect { event ->
    when (event) {
        is TranscriptionStreamEvent.Started -> Unit
        is TranscriptionStreamEvent.Segment -> appendToUi(event.segment.text)
        is TranscriptionStreamEvent.Completed -> finishUi(event.text)
    }
}
```

### 6.3 Direct HTTP Integration (Python Example)

```python
import requests

BASE = "http://127.0.0.1:8765/v1"
HEADERS = {"Authorization": "Bearer uoai_your_token"}

with open("meeting.wav", "rb") as f:
    resp = requests.post(
        f"{BASE}/audio/transcriptions",
        headers=HEADERS,
        files={"file": ("meeting.wav", f, "audio/wav")},
        data={
            "provider": "gemma4",
            "model": "gemma-4",
            "language": "en,hi",
            "script": "canonical",
            "response_format": "verbose_json",
        },
        timeout=180,
    )
resp.raise_for_status()
data = resp.json()
print("Provider:", data["provider"], "Model:", data["model"])
print("Transcript:", data["text"])
print("Roman Hinglish:", data.get("roman_hinglish"))
```

---

## 7. Error Codes & Troubleshooting

All errors use the standard JSON envelope:
```json
{
  "error": {
    "message": "Human-readable explanation",
    "type": "invalid_request_error",
    "code": "gemma4_audio_unsupported"
  }
}
```

| HTTP | `code` | Cause & Fix |
|---|---|---|
| `400` | `missing_audio_file` | Missing `file` multipart field or empty raw request body |
| `400` | `unsupported_audio_format` / `corrupt_audio` | Invalid or unreadable audio container/codec |
| `400` | `audio_too_long` | Audio exceeds `max_audio_duration_sec` (600 s) |
| `400` | `gemma4_audio_unsupported` | Active Gemma 4 bundle has no audio encoder (load Gemma 4 E2B or E4B `.litertlm`) |
| `400` | `provider_disabled` | Requested external provider is currently toggled off |
| `400` | `invalid_provider_type` / `invalid_provider_config` | Invalid `type`, `id`, or `base_url` in `POST /v1/audio/providers` |
| `400` | `reserved_provider` | Attempted to overwrite or delete a built-in provider (`gemma4`, `whisper`, `cactus`) |
| `401` | `missing_token` / `invalid_token` | Missing, malformed, or revoked Bearer token |
| `403` | `permission_denied` | Client lacks `speech_to_text`, `streaming`, or `asr_model_control` permission |
| `404` | `provider_not_found` / `model_not_found` | Requested external provider or ASR model does not exist / is not downloaded |
| `409` | `model_mismatch` / `asr_busy` / `request_cancelled` | Requested Whisper profile differs from active profile, engine busy, or request cancelled |
| `413` | `audio_too_large` | Upload exceeds `max_upload_bytes` (25 MiB) |
| `429` | `queue_full` | Global or per-client transcription queue is full |
| `502` | `external_provider_unreachable` / `external_provider_http_*` | External upstream endpoint failed or returned non-2xx HTTP status |
| `503` | `model_not_ready` / `asr_model_not_ready` / `insufficient_memory` / `thermal_throttled` | Selected local model is not loaded or device resource guard triggered |
| `504` | `request_timeout` / `external_provider_timeout` | Local inference or external upstream request exceeded timeout |
