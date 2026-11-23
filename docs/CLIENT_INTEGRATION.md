# Client Integration & Build Guide

## 1. Prerequisites & Toolchain
- **JDK 17** is required for Gradle 8.14 / AGP 8.9.1:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  ```
- **Android SDK:** `compileSdk = 35`, `minSdk = 26`.

## 2. Building the Project, SDK AAR, and Multiple Clients
```bash
# Run all unit tests across all modules
./gradlew testDebugUnitTest

# Assemble the Provider APK, Sample Client APK, and reusable SDK AAR
./gradlew :provider-app:assembleDebug :sample-client:assembleDebug :sdk:assembleDebug

# Assemble a second independent client APK with a distinct applicationId
./gradlew :sample-client:assembleDebug -PclientAppId=com.uoai.sample2
```

Artifacts produced:
- Provider APK: `provider-app/build/outputs/apk/debug/provider-app-debug.apk`
- Client SDK AAR: `sdk/build/outputs/aar/sdk-debug.aar` (~90 KB, contains zero model weights)
- Sample Client APK: `sample-client/build/outputs/apk/debug/sample-client-debug.apk` (~2.5 MB, contains zero model weights)

## 3. Integrating the Kotlin SDK (`:sdk`)

### AndroidManifest.xml
Declare package visibility for the provider and loopback cleartext traffic:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<queries>
    <package android:name="com.uoai.provider" />
</queries>
```

### Registration & Approval Flow
```kotlin
val ai = UniversalAIClient(context)

// 1. One-time registration via OS-verified startActivityForResult
if (!ai.isRegistered) {
    startActivityForResult(ai.registrationIntent(), REQ_REGISTER)
}

// 2. In onActivityResult: stores the token encrypted via Android Keystore
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQ_REGISTER) {
        when (val res = ai.handleRegistrationResult(resultCode, data)) {
            RegistrationResult.Approved -> { /* Ready to connect */ }
            is RegistrationResult.Denied -> { /* Handle rejection: res.code, res.message */ }
        }
    }
}
```

### Non-Streaming & Streaming Inference
```kotlin
// Verify provider health & token validity
val health = ai.connect()

// Non-streaming generation
val response = ai.generate(
    prompt = "Explain neural networks in one sentence.",
    model = "gemma-3",
    maxTokens = 128
)
println(response.text)

// Streaming generation (cancelling the Flow collector automatically cancels the provider request)
ai.generateStream(
    prompt = "Explain deep learning.",
    maxTokens = 256
).collect { token ->
    print(token)
}
```

### Client-Owned & Provider-Owned Tools
```kotlin
// Requesting a client-owned tool call
val docTool = ClientToolSpec(
    name = "read_selected_document",
    description = "Read the currently open note in the client app",
    parameters = buildJsonObject { put("type", "object") }
)
val result = ai.generate(
    prompt = "Summarize my currently selected document.",
    tools = listOf(docTool)
)
if (result.toolCalls.isNotEmpty()) {
    // Execute the function inside the client app, then send the role="tool" message back via ai.chat(...)
}

// Allowing provider-owned safe tools (e.g. calculator, device_info)
val calcAnswer = ai.generate(
    prompt = "What is (12.5 * 4) + 3?",
    useProviderTools = true
)
```
