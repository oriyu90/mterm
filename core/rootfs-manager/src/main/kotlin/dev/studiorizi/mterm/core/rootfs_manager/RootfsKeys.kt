package dev.studiorizi.mterm.core.rootfs_manager

/**
 * Embedded Ed25519 public key for rootfs manifest verification.
 *
 * The matching private key lives ONLY in the private common-rules-document
 * repository (`keystores/mterm-rootfs-ed25519.private.pem`) and is never
 * committed here. Release manifests are signed with `scripts/sign-rootfs.py`;
 * the app verifies with [ManifestVerifier.verifyEd25519] + this key.
 */
object RootfsKeys {
    /** Raw 32-byte Ed25519 public key, base64-encoded. */
    const val PUBLIC_KEY_BASE64 = "F314WtQCxHj5vdKsq2y/zMGtnY2eZV9zxxw5hLYwuVM="

    /** Decoded 32-byte public key, or null when the constant is malformed. */
    fun publicKeyRaw32(): ByteArray? = try {
        val raw = java.util.Base64.getDecoder().decode(PUBLIC_KEY_BASE64)
        if (raw.size == 32) raw else null
    } catch (_: IllegalArgumentException) {
        null
    }
}
