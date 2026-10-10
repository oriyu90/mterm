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

# sshj: reflective factory loading (SecurityProvider, transport/cipher/mac
# factories via ServiceLoader-ish config) must survive R8.
-keep class com.hierynomus.sshj.** { *; }
-keep class net.schmizz.sshj.** { *; }
-keep class net.i2p.crypto.** { *; }
-keep class com.hierynomus.asn.** { *; }

# sshj's GSSAPI/Kerberos auth references JAAS (javax.security, org.ietf.jgss),
# absent on Android. We never negotiate gssapi-with-mic (password/publickey
# only); silence R8 about the unreachable path.
-dontwarn javax.security.**
-dontwarn org.ietf.jgss.**
# eddsa's EdDSAEngine references JDK-internal sun.security.x509 (AGP-suggested).
# RSA/ECDSA servers verify fine; ssh-ed25519 host keys may fail verification
# on Android (surfaced as a normal connect failure, never a crash).
-dontwarn sun.security.x509.X509Key
