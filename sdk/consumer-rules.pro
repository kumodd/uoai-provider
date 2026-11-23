-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod

-keep class com.uoai.sdk.** { *; }

-keepclassmembers class com.uoai.sdk.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.uoai.sdk.**$$serializer { *; }
