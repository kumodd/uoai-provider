# UOAI Provider (com.uoai.provider) Release R8 / ProGuard Rules

# Serialization attributes & annotations
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Preserve Kotlinx Serialization companion serializers across all UOAI modules
-keepclassmembers class com.uoai.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.uoai.**$$serializer { *; }

# Keep public & serialized models across DTO, inference, asr, plugins, client management, and core
-keep class com.uoai.providerapi.dto.** { *; }
-keep class com.uoai.inference.** { *; }
-keep class com.uoai.asr.** { *; }
-keep class com.uoai.core.resources.** { *; }
-keep class com.uoai.plugins.models.** { *; }
-keep class com.uoai.clients.** { *; }
-keep class com.uoai.core.database.** { *; }

# Keep Room DB implementations and Hilt/Service/Activity entry points intact
-keep class * extends androidx.room.RoomDatabase
-keep class com.uoai.provider.registration.RegistrationActivity { *; }
-keep class com.uoai.provider.service.ProviderService { *; }

# Keep JNI native methods and LiteRT-LM inference runtime adapters
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.protobuf.** { *; }

# Keep Ktor CIO HTTP/SSE server internals reflected by coroutines / serialization
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }

# Benign optional JVM / server logging warnings on Android
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn io.ktor.**
-dontwarn kotlinx.atomicfu.**
-dontwarn com.google.errorprone.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.**
-dontwarn java.lang.management.**
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn sun.misc.Unsafe

# Protobuf compile-time annotations
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
