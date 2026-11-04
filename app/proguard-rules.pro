# R8/ProGuard rules for HAM. Minification is currently disabled for release
# builds; these rules exist so that enabling it is safe. Most libraries ship
# consumer rules in their AARs (Ktor, kotlinx-serialization, Tink), so this
# file only pins the reflection surfaces this app relies on directly.

# --- kotlinx.serialization ---
# Generated serializers and companion serializer() lookups for this app's
# @Serializable models (gateway payloads, token store records).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.unsupportedpastels.hermesandroid.**$$serializer { *; }
-keepclassmembers class com.unsupportedpastels.hermesandroid.** {
    *** Companion;
}
-keepclasseswithmembers class com.unsupportedpastels.hermesandroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor (CIO engine) ---
# The client engine is discovered via ServiceLoader; keep the container so a
# minified build can still resolve it at runtime.
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
-keepclassmembers class io.ktor.** {
    volatile <fields>;
}

# --- JSch (SSH transport) ---
# JSch instantiates its ciphers, compressors, MACs and key-exchange classes by
# name at runtime, so R8 cannot see them: a minified build lost jzlib and the
# GCM cipher classes, and every SSH handshake then failed at negotiation while
# the identical unminified build worked. Keep the whole library rather than a
# subset — the reflective surface spans jce, jzlib and bc, and the package is
# small next to its own consumers.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# --- Tink (AndroidKeystore AEAD token store) ---
# Tink registers key managers reflectively over protobuf-lite messages.
-keep class com.google.crypto.tink.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Kotlin coroutines ship their own consumer rules; nothing app-specific needed.
