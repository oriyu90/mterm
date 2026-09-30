package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestVerifierTest {

    @Test
    fun sha256OfAbc_matchesKnownHash() {
        val dir = Files.createTempDirectory("manifest-test").toFile()
        try {
            val file = File(dir, "abc.txt")
            file.writeBytes("abc".toByteArray(Charsets.US_ASCII))
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ManifestVerifier.sha256Hex(file),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifySha256_acceptsMatch_rejectsMismatch() {
        val dir = Files.createTempDirectory("manifest-test").toFile()
        try {
            val file = File(dir, "abc.txt")
            file.writeBytes("abc".toByteArray(Charsets.US_ASCII))
            assertTrue(
                ManifestVerifier.verifySha256(
                    file,
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ),
            )
            assertFalse(
                ManifestVerifier.verifySha256(
                    file,
                    "aa7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ),
            )
            assertFalse(ManifestVerifier.verifySha256(file, "short"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyEd25519_failClosedOnGarbage() {
        val key = ByteArray(32) { 0x01 }
        assertFalse(ManifestVerifier.verifyEd25519("data".toByteArray(), "!!!not-base64!!!", key))
        assertFalse(ManifestVerifier.verifyEd25519("data".toByteArray(), "aGVsbG8=", key))
        assertFalse(ManifestVerifier.verifyEd25519("data".toByteArray(), "aGVsbG8=", ByteArray(16)))
    }

    @Test
    fun canonicalBytes_excludesSignatureAndSortsKeys() {
        val manifest = RootfsManifest(
            schema = 1,
            id = "debian-trixie-arm64",
            version = "13.7-r1",
            arch = "arm64",
            sha256 = "deadbeef",
            size = 42L,
            minAppVersion = 10000,
            createdAt = "2026-09-29T00:00:00Z",
            signature = "ed25519:should-not-appear",
        )
        val text = ManifestVerifier.canonicalBytes(manifest).toString(Charsets.UTF_8)
        assertFalse(text.contains("signature"))
        assertFalse(text.contains("should-not-appear"))
        val obj = Json.parseToJsonElement(text).jsonObject
        val keys = obj.keys.toList()
        assertEquals(keys.sorted(), keys)
        assertEquals("13.7-r1", obj.getValue("version").toString().trim('"'))
    }
}
