package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import kotlinx.serialization.Serializable

/** Distribution channel for rootfs releases. Only STABLE ships in the MVP. */
enum class RootfsChannel {
    STABLE,
}

/**
 * Installer state machine (plan section 9.3):
 * NOT_INSTALLED -> DOWNLOADING -> VERIFYING -> EXTRACTING -> INITIALIZING -> READY.
 * Any failure transitions to FAILED; on failure the staging directory is
 * deleted and an existing READY install is never touched.
 */
enum class InstallState {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    INITIALIZING,
    READY,
    FAILED,
}

data class RootfsInstall(
    val channel: RootfsChannel,
    val version: String,
    val rootfsDir: File,
    val state: InstallState,
)

sealed interface VerificationResult {
    data class Ok(val sha256Hex: String) : VerificationResult
    data class Failed(val reason: String) : VerificationResult
}

/** Signed release descriptor; [signature] covers [ManifestVerifier.canonicalBytes]. */
@Serializable
data class RootfsManifest(
    val schema: Int,
    val id: String,
    val version: String,
    val arch: String,
    val sha256: String,
    val size: Long,
    val minAppVersion: Int,
    val createdAt: String,
    val signature: String = "",
    /**
     * RSA-2048 PKCS#1 v1.5 signature over the SAME canonical bytes, for
     * API < 33 devices where platform Ed25519 is unavailable. May be empty
     * on old manifests (then only Ed25519-capable runtimes accept them).
     */
    val signatureRsa: String = "",
)
