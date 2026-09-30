package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SHA-256 + Ed25519 verification for rootfs archives and manifests.
 * Pure JVM: java.security only. All failures are fail-closed (return false).
 */
object ManifestVerifier {

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /** Constant-time comparison of hex digests (normalised to lower-case). */
    fun verifySha256(file: File, expectedHex: String): Boolean {
        return try {
            val actual = sha256Hex(file).lowercase()
            val expected = expectedHex.lowercase()
            MessageDigest.isEqual(
                actual.toByteArray(Charsets.US_ASCII),
                expected.toByteArray(Charsets.US_ASCII),
            ) && actual.length == expected.length
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifies an Ed25519 signature over the canonical manifest bytes.
     *
     * @param manifestBytes bytes from [canonicalBytes].
     * @param signatureB64 base64 signature, with or without the "ed25519:" prefix
     *   used in manifests.
     * @param publicKeyRaw32 32-byte raw Ed25519 public key (app-embedded).
     * @return false on any error, including a missing "Ed25519" algorithm
     *   (fail closed; never passes when the check cannot run).
     */
    fun verifyEd25519(
        manifestBytes: ByteArray,
        signatureB64: String,
        publicKeyRaw32: ByteArray,
    ): Boolean {
        return try {
            if (publicKeyRaw32.size != 32) return false
            val raw = signatureB64.removePrefix("ed25519:")
            val sigBytes = try {
                java.util.Base64.getDecoder().decode(raw)
            } catch (e: IllegalArgumentException) {
                return false
            }
            if (sigBytes.size != 64) return false
            val publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(ed25519Spki(publicKeyRaw32)))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(manifestBytes)
            verifier.verify(sigBytes)
        } catch (e: java.security.NoSuchAlgorithmException) {
            // Ed25519 unavailable on this runtime: fail closed, do NOT pass.
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Canonical bytes covered by the manifest signature: JSON object with
     * sorted keys and the "signature" field excluded.
     */
    fun canonicalBytes(manifest: RootfsManifest): ByteArray {
        val obj: JsonObject = buildJsonObject {
            put("arch", manifest.arch)
            put("createdAt", manifest.createdAt)
            put("id", manifest.id)
            put("minAppVersion", manifest.minAppVersion)
            put("schema", manifest.schema)
            put("sha256", manifest.sha256)
            put("size", manifest.size)
            put("version", manifest.version)
        }
        return Json.encodeToString(obj).toByteArray(Charsets.UTF_8)
    }

    /** Wraps a raw 32-byte Ed25519 key in an X.509 SPKI DER header. */
    private fun ed25519Spki(raw32: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        return prefix + raw32
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val digits = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0F]
        }
        return chars.concatToString()
    }
}
