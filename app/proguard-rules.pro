# sidespot ProGuard rules

# Keep JNI bridge methods (called from native code)
-keep class com.sidespot.bridge.NativeBridge { *; }

# Keep audio callback (called from native code via JNI)
-keep class com.sidespot.audio.AudioCallback { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.sidespot.bridge.**$$serializer { *; }
-keepclassmembers class com.sidespot.bridge.** {
    *** Companion;
}
-keepclasseswithmembers class com.sidespot.bridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink (via security-crypto) references error-prone annotations at compile time only
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Strip debug/verbose logs from release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
