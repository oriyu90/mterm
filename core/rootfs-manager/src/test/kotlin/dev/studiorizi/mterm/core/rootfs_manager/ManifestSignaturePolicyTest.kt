package dev.studiorizi.mterm.core.rootfs_manager

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dual-signature trust policy (Ed25519 on API 33+, RSA-2048 below):
 * a broken Ed25519 can never be masked by RSA, and pre-33 runtimes
 * require RSA. Keys are generated in-test; only the policy is exercised.
 */
class ManifestSignaturePolicyTest {

    private fun manifest() = RootfsManifest(
        schema = 1, id = "debian-trixie-arm64", version = "13.7-r1", arch = "arm64",
        sha256 = "ab".repeat(32), size = 145102145, minAppVersion = 10000,
        createdAt = "2026-10-10T00:00:00Z",
    )

    private fun edKeys(): Triple<ByteArray, ByteArray, ByteArray> {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pubRaw = kp.public.encoded.takeLast(32).toByteArray()
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(kp.private)
            update(ManifestVerifier.canonicalBytes(manifest()))
        }.sign()
        return Triple(pubRaw, sig, kp.public.encoded)
    }

    private fun rsaKeys(): Triple<ByteArray, ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private)
            update(ManifestVerifier.canonicalBytes(manifest()))
        }.sign()
        assertTrue(sig.size == 256)
        return Triple(kp.public.encoded, sig, kp.public.encoded)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun edCapable_validEdPassesRsaIgnored() {
        val (edPub, edSig, _) = edKeys()
        val (rsaPub, _, _) = rsaKeys()
        assertTrue(
            ManifestVerifier.verifyManifest(
                ManifestVerifier.canonicalBytes(manifest()),
                "ed25519:" + b64(edSig), edPub,
                "rsa:AAAA", rsaPub, // garbage RSA must not matter
                edSupported = true,
            ),
        )
    }

    @Test
    fun edCapable_brokenEdNeverMaskedByRsa() {
        val (edPub, _, _) = edKeys()
        val (rsaPub, rsaSig, _) = rsaKeys()
        assertFalse(
            ManifestVerifier.verifyManifest(
                ManifestVerifier.canonicalBytes(manifest()),
                "ed25519:" + b64(ByteArray(64) { 1 }), edPub,
                "rsa:" + b64(rsaSig), rsaPub,
                edSupported = true,
            ),
        )
    }

    @Test
    fun legacy_validRsaPasses() {
        val (edPub, _, _) = edKeys()
        val (rsaPub, rsaSig, _) = rsaKeys()
        assertTrue(
            ManifestVerifier.verifyManifest(
                ManifestVerifier.canonicalBytes(manifest()),
                "ed25519:AAAA", edPub,
                b64(rsaSig), rsaPub, // prefix optional
                edSupported = false,
            ),
        )
    }

    @Test
    fun legacy_brokenRsaFails() {
        val (edPub, _, _) = edKeys()
        val (rsaPub, _, _) = rsaKeys()
        assertFalse(
            ManifestVerifier.verifyManifest(
                ManifestVerifier.canonicalBytes(manifest()),
                "ed25519:AAAA", edPub,
                "rsa:" + b64(ByteArray(256) { 2 }), rsaPub,
                edSupported = false,
            ),
        )
    }

    @Test
    fun legacy_emptyRsaFails() {
        val (edPub, _, _) = edKeys()
        val (rsaPub, _, _) = rsaKeys()
        assertFalse(
            ManifestVerifier.verifyManifest(
                ManifestVerifier.canonicalBytes(manifest()),
                "ed25519:AAAA", edPub,
                "", rsaPub,
                edSupported = false,
            ),
        )
    }

    @Test
    fun verifyRsa_rejectsWrongSize() {
        val (rsaPub, _, _) = rsaKeys()
        assertFalse(
            ManifestVerifier.verifyRsa(
                ManifestVerifier.canonicalBytes(manifest()),
                b64(ByteArray(64)),
                rsaPub,
            ),
        )
    }

    @Test
    fun embeddedKeys_decode() {
        assertTrue(RootfsKeys.publicKeyRaw32()?.size == 32)
        assertTrue((RootfsKeys.rsaPublicDer()?.size ?: 0) > 0)
    }
}
