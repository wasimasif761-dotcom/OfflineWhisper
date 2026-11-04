# ProGuard & R8 rules for OfflineWhisper

# Preserve native JNI methods and WhisperLib companion class
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.whispercpp.whisper.** { *; }
-keepclassmembers class com.whispercpp.whisper.** { *; }

# Preserve OkHttp & Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep data models
-keep class com.example.model.** { *; }
-keep class com.example.ui.** { *; }
