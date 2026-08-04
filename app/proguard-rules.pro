# Default ProGuard rules for the app module.
# See https://developer.android.com/studio/build/shrink-code

# Keep our Kotlin metadata so reflection (Hilt, Room) works in release builds.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ONNX Runtime (JNI)
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# whisper.cpp JNI bindings
-keep class com.karaokei.whisper.** { *; }
-keepclasseswithmembernames class com.karaokei.whisper.** {
    native <methods>;
}
