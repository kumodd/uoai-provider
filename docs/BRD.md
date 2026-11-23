# Business Requirements Document (BRD) — Universal Offline AI Provider

## 1. Executive Summary
**Universal Offline AI Provider** is an on-device Android AI infrastructure application that loads and manages a single shared Gemma 3 model (`litert-community/Gemma3-1B-IT` int4 `.task` bundle) and exposes its inference capabilities over a loopback-only (`127.0.0.1:8765`) OpenAI-compatible HTTP + SSE API.

**Core Principle:** *One model instance, one provider application, multiple independent client applications.*

## 2. Requirements Traceability Matrix

| Requirement | Summary | Implementation Location | Status |
|---|---|---|---|
| **FR-01** | Model Management (Download/Import, Integrity, Load/Unload, 8 lifecycle states) | `ModelManager.kt`, `ModelDownloader.kt`, `ModelIntegrity.kt`, `ModelState.kt` | Verified on device |
| **FR-02** | Local AI API Server (`127.0.0.1:8765`, `/v1/health`, `/v1/models`, `/v1/capabilities`, `/v1/chat/completions`, `/v1/cancel`) | `KtorLocalHttpServer.kt`, `ProviderApi.kt` | Verified on device |
| **FR-03** | Shared Inference Engine (Single Gemma 3 instance across all clients) | `DefaultInferenceEngine.kt`, `GemmaRuntimeAdapter.kt` | Verified on device |
| **FR-04** | Inference Queue & Priority Scheduling (FIFO + priority, per-client & global limits, cancellation) | `DefaultInferenceEngine.kt` (`PriorityQueue`, worker coroutine) | Verified (6 unit tests + 2-client device test) |
| **FR-05** | Client Application Registration (OS `getCallingPackage()` + SHA-256 signing certificate verification, Room-stored SHA-256 token hashes) | `RegistrationActivity.kt`, `CallerIdentityResolver.kt`, `ClientRepository.kt` | Verified on device |
| **FR-06** | Reusable Kotlin Android Client SDK (AAR) | `:sdk` (`UniversalAIClient.kt`, `ApiClient.kt`, `TokenStore.kt`) | Verified (`sdk-debug.aar`, 90 KB) |
| **FR-07** | Client Configuration Format | `ClientConfig` in `sdk/Models.kt` | Verified |
| **FR-08** | Tool Calling & Plugin Architecture (Client-owned `tools` & provider-owned `PluginManager` with safe predefined tools) | `ToolCallingSupport.kt`, `PluginManager.kt`, `BuiltinTools.kt` | Verified |
| **FR-09** | Jetpack Compose Provider Dashboard | `DashboardScreen.kt`, `DashboardViewModel.kt` | Verified on device |
| **FR-10** | Dedicated Client Management Screen | `ClientsScreen.kt`, `ClientsViewModel.kt` | Verified on device |
| **FR-11** | Optional Android Automation Subsystem (Separated from core inference; allow-listed intents + opt-in Accessibility boundary) | `:android-integration` (`ApprovedIntentExecutor.kt`, `OptInAccessibilityCapability.kt`) | Verified |
| **Section 7** | Room Database with all 8 required tables | `:core` (`Entities.kt`, `Daos.kt`, `AppDatabase.kt`) | Verified |
| **Section 8** | Android Service Lifecycle (`ProviderService`, non-sticky foreground service, stale request cleanup on startup) | `ProviderService.kt`, `ProviderApplication.kt` | Verified on device |
