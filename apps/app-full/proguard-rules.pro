# app-full ProGuard rules (Full Sideload MVP).
# Keep JNI entry points and reflective serialization/Room code.

# pty-native JNI bridge: called by name from native code.
-keep class dev.studiorizi.mterm.core.pty_native.PtyNative { *; }

# kotlinx.serialization: keep generated serializers.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class kotlinx.serialization.** { *; }
-keepclasseswithmembernames class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room: keep entities/DAOs used via :core:data.
-keep class dev.studiorizi.mterm.core.data.** { *; }

# Service and entry points referenced from the manifest.
-keep class dev.studiorizi.mterm.full.service.TerminalService { *; }
-keep class dev.studiorizi.mterm.full.MainActivity { *; }
-keep class dev.studiorizi.mterm.full.MTermApp { *; }
