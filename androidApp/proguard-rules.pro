# ProGuard rules for LiveSort Android App

# Keep model classes for serialization
-keep class com.livesort.shared.model.** { *; }
-keep class com.livesort.shared.audio.** { *; }

# TarsosDSP
-keep class be.tarsos.dsp.** { *; }
-dontwarn be.tarsos.dsp.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-keep class androidx.compose.** { *; }
