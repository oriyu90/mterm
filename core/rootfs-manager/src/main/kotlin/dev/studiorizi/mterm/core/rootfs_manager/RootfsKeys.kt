package dev.studiorizi.mterm.core.rootfs_manager

/**
 * Embedded rootfs manifest verification keys (public halves only).
 *
 * The matching private keys live ONLY in the private common-rules-document
 * repository (`keystores/mterm-rootfs-ed25519.private.pem`,
 * `keystores/mterm-rootfs-rsa2048.private.pem`) and are never committed
 * here. Release manifests carry both signatures via `scripts/sign-rootfs.py`;
 * the app verifies with [ManifestVerifier.verifyManifest], which requires
 * Ed25519 on API 33+ and RSA-2048 below (platform Ed25519 needs API 33).
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

    /** DER SubjectPublicKeyInfo RSA-2048 public key, base64-encoded. */
    const val RSA_PUBLIC_DER_BASE64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAq6fhtD1iVd3cV4V9B6opCA3hxf8i0zGmT+X9wHLeIL3TIOWwFHFi8aDPQZ5kXEU+2GjbYNSkWybPwR6yZb7iD/qmFZwpfn4r/T+Tix5xvc82l/1gcsHtQja02RDjQQV5D0ZD3FwfdGKBif7FqLNZghGi/gQ13G1mOxCg1UxDkyElXd3VdC93CdiMYj3CoVY/mgZS2DFjNLfx+68AlcAJQioL9KVZxS5MwDwQWGwT2HMG2yqGV86v4rDuc9c0dWxwQMjMmtfNyGO3zlVu6NGjurzCOEXhdb7FpX07pAChB9w5QZfvq69FMagyH65c7iibbvkjGoiu2T1pLOxxcY8MlwIDAQAB"

    /** Decoded RSA public key bytes, or null when malformed. */
    fun rsaPublicDer(): ByteArray? = try {
        java.util.Base64.getDecoder().decode(RSA_PUBLIC_DER_BASE64).takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null
    }
}
