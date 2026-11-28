-keep class com.uoai.asr.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.uoai.asr.** {
    native <methods>;
}
-keepclassmembers class com.uoai.asr.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.uoai.asr.**$$serializer { *; }

