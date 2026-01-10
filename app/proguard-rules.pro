# ClawdAR ProGuard Rules

# Keep Mercury SDK classes
-keep class com.ffalcon.mercury.android.sdk.** { *; }
-keep class com.tct.gesturedetectorwithsound.** { *; }

# Keep JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep ClawdAR protocol classes
-keep class com.clawdbot.ar.protocol.** { *; }
-keep class com.clawdbot.ar.**Params { *; }
