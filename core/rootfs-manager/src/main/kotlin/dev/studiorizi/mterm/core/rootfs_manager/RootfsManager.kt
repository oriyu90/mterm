package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal rootfs install bookkeeping (pure JVM, no network).
 *
 * State machine: NOT_INSTALLED -> DOWNLOADING -> VERIFYING -> EXTRACTING ->
 * INITIALIZING -> READY (-> FAILED on error). Extraction always targets
 * [stagingDir]; only a fully verified install is promoted to [targetDir].
 * The caller deletes the staging dir on failure, and updates must never
 * overwrite the existing /home tree.
 *
 * @param filesDir app-private files dir (e.g. context.filesDir).
 * @param publicKey 32-byte raw Ed25519 manifest public key (app-embedded).
 */
class RootfsManager(
    private val filesDir: File,
    @Suppress("unused") private val publicKey: ByteArray,
) {
    @Serializable
    private data class CurrentMetadata(
        val channel: String,
        val version: String,
        val state: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun currentFile(): File = File(filesDir, "linux/current.json")

    /** Reads filesDir/linux/current.json; null when absent or corrupt. */
    suspend fun currentInstall(): RootfsInstall? = withContext(Dispatchers.IO) {
        val file = currentFile()
        if (!file.isFile) return@withContext null
        try {
            val meta = json.decodeFromString(
                CurrentMetadata.serializer(),
                file.readText(Charsets.UTF_8),
            )
            val channel = try {
                RootfsChannel.valueOf(meta.channel)
            } catch (e: IllegalArgumentException) {
                return@withContext null
            }
            val state = try {
                InstallState.valueOf(meta.state)
            } catch (e: IllegalArgumentException) {
                return@withContext null
            }
            RootfsInstall(channel, meta.version, targetDir(meta.version), state)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the READY install for [channel], or a NOT_INSTALLED placeholder
     * when nothing usable is installed. Download/verify/extract stages run
     * elsewhere and publish via current.json only on success.
     */
    suspend fun ensureInstalled(channel: RootfsChannel): RootfsInstall {
        val current = currentInstall()
        if (current != null && current.channel == channel && current.state == InstallState.READY) {
            return current
        }
        val version = current?.version ?: ""
        val dir = if (current != null) current.rootfsDir else targetDir(PENDING_VERSION)
        return RootfsInstall(channel, version, dir, InstallState.NOT_INSTALLED)
    }

    /** Staging dir for unverified extraction; caller cleans it on failure. */
    fun stagingDir(): File = File(filesDir, "linux/staging")

    /** Final install location: files/linux/distributions/debian/<version>/rootfs. */
    fun targetDir(version: String): File =
        File(filesDir, "linux/distributions/debian/$version/rootfs")

    companion object {
        const val PENDING_VERSION = "pending"

        /** Manifest id/arch the installer accepts (fixed per plan 9.1). */
        const val EXPECTED_ID = "debian-trixie-arm64"
        const val EXPECTED_ARCH = "arm64"

        /**
         * Resolves the live rootfs dir without coroutines (for service
         * startup): current.json's version when present, otherwise the
         * legacy `current/rootfs` path. Never throws.
         */
        fun activeRootfsDir(filesDir: File): File {
            val version = try {
                val text = File(filesDir, "linux/current.json").takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8) ?: return legacyRootfsDir(filesDir)
                Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() && '/' !in it && it != "." && it != ".." }
                    ?: return legacyRootfsDir(filesDir)
            } catch (e: Exception) {
                return legacyRootfsDir(filesDir)
            }
            return File(filesDir, "linux/distributions/debian/$version/rootfs")
        }

        private fun legacyRootfsDir(filesDir: File): File =
            File(filesDir, "linux/distributions/debian/current/rootfs")
    }
}
