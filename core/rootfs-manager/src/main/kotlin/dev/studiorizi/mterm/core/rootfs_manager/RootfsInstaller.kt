package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Full rootfs install pipeline (pure JVM):
 * fetch manifest -> verify Ed25519 -> download archive (resume) ->
 * verify SHA-256 -> extract to staging -> initialize (resolv.conf,
 * home skeleton) -> atomic promote -> publish current.json.
 *
 * Failures delete the staging dir and never touch an existing READY
 * install. Updates never overwrite the existing guest `/home` tree:
 * promotion renames staging aside only when no READY install exists;
 * otherwise the previous home is carried over before the swap.
 */
class RootfsInstaller(
    private val filesDir: File,
    private val publicKey: ByteArray,
) {
    private val manager = RootfsManager(filesDir, publicKey)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /**
         * Release asset URL convention: tag `rootfs-<version>` carrying
         * `debian-trixie-arm64.tar.gz`. The manifest itself carries no URL so
         * mirrors stay possible by overriding this one function.
         */
        fun archiveUrlFor(manifest: RootfsManifest): String =
            "https://github.com/oriyu90/mterm/releases/download/" +
                "rootfs-${manifest.version}/debian-trixie-arm64.tar.gz"

        fun manifestUrl(): String =
            "https://raw.githubusercontent.com/oriyu90/mterm/main/" +
                "distribution/manifests/debian-trixie-arm64.json"
    }

    suspend fun currentInstall(): RootfsInstall? = manager.currentInstall()

    fun stagingDir(): File = manager.stagingDir()

    /**
     * Runs the full install for [channel]: fetch manifest from [manifestUrl]
     * (Ed25519-verified) -> download the release archive with resume ->
     * verify SHA-256 -> extract to staging -> initialize -> atomic promote.
     * The archive URL follows [archiveUrlFor] from the verified manifest, so
     * callers never construct download URLs by hand.
     */
    suspend fun install(
        channel: RootfsChannel,
        manifestUrl: String,
        onState: (InstallState, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
        // Test hook: bypass the release-asset URL convention with a local server.
        archiveUrlOverride: String? = null,
    ): Result<RootfsInstall> = withContext(Dispatchers.IO) {
        try {
            val manifest = fetchManifest(manifestUrl).getOrElse {
                return@withContext Result.failure(it)
            }
            if (manifest.id != RootfsManager.EXPECTED_ID || manifest.arch != RootfsManager.EXPECTED_ARCH) {
                return@withContext Result.failure(
                    IllegalStateException("unexpected manifest id/arch: ${manifest.id}/${manifest.arch}"),
                )
            }
            val current = manager.currentInstall()
            if (current != null && current.channel == channel &&
                current.state == InstallState.READY && current.version == manifest.version
            ) {
                // Already on this exact version: nothing to do.
                return@withContext Result.success(current)
            }
            val staging = manager.stagingDir()
            val target = manager.targetDir(manifest.version)
            val archive = File(filesDir, "linux/cache/${manifest.id}-${manifest.version}.tar.gz")
            val archiveUrl = archiveUrlOverride ?: archiveUrlFor(manifest)
            onState(InstallState.DOWNLOADING, 0, manifest.size)
            RootfsDownloader.download(
                archiveUrl,
                archive,
                manifest.size,
                onProgress = { done, total -> onState(InstallState.DOWNLOADING, done, total) },
            ).getOrElse { return@withContext Result.failure(it) }
            onState(InstallState.VERIFYING, manifest.size, manifest.size)
            if (!ManifestVerifier.verifySha256(archive, manifest.sha256)) {
                archive.delete()
                return@withContext Result.failure(
                    IllegalStateException("archive SHA-256 mismatch"),
                )
            }
            onState(InstallState.EXTRACTING, 0, manifest.size)
            staging.deleteRecursively()
            TarExtractor.extract(archive, staging, onProgress = { entries ->
                onState(InstallState.EXTRACTING, entries.toLong(), -1)
            }).getOrElse {
                staging.deleteRecursively()
                return@withContext Result.failure(it)
            }
            onState(InstallState.INITIALIZING, 0, -1)
            initialize(staging)
            // Carry over the previous home tree on updates, never overwrite.
            val previousHome = current?.let { File(it.rootfsDir, "home") }
            if (previousHome != null && previousHome.isDirectory) {
                copyHome(previousHome, File(staging, "home"))
            }
            if (target.exists()) target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                staging.deleteRecursively()
                return@withContext Result.failure(
                    IllegalStateException("cannot promote staging to $target"),
                )
            }
            archive.delete()
            writeCurrent(channel, manifest.version, InstallState.READY)
            onState(InstallState.READY, manifest.size, manifest.size)
            Result.success(RootfsInstall(channel, manifest.version, target, InstallState.READY))
        } catch (e: Exception) {
            runCatching { manager.stagingDir().deleteRecursively() }
            Result.failure(e)
        }
    }

    private suspend fun fetchManifest(url: String): Result<RootfsManifest> =
        withContext(Dispatchers.IO) {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = RootfsDownloader.CONNECT_TIMEOUT_MS
                    readTimeout = RootfsDownloader.READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "mterm-rootfs/1")
                }
                try {
                    connection.connect()
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        return@withContext Result.failure(
                            IllegalStateException("manifest HTTP ${connection.responseCode}"),
                        )
                    }
                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val manifest = json.decodeFromString(
                        RootfsManifest.serializer(),
                        text,
                    )
                    val edKey = publicKey.takeIf { it.size == 32 }
                    val rsaKey = RootfsKeys.rsaPublicDer()
                    val ok = edKey != null && rsaKey != null && ManifestVerifier.verifyManifest(
                        ManifestVerifier.canonicalBytes(manifest),
                        manifest.signature,
                        edKey,
                        manifest.signatureRsa,
                        rsaKey,
                    )
                    if (!ok) {
                        return@withContext Result.failure(
                            IllegalStateException("manifest signature invalid"),
                        )
                    }
                    Result.success(manifest)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Guest first-boot shape: DNS + user home + bind mountpoints (idempotent). */
    private fun initialize(rootfs: File) {
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(rootfs, "home/user").mkdirs()
        // proot binds host /dev//proc//sys over these; ensure the
        // mountpoints exist even when the tarball lacks them.
        File(rootfs, "dev").mkdirs()
        File(rootfs, "proc").mkdirs()
        File(rootfs, "sys").mkdirs()
    }

    /** Copies previous guest home into the fresh tree (additive, no deletes). */
    private fun copyHome(from: File, to: File) {
        from.walkTopDown().forEach { src ->
            if (src == from) return@forEach
            val rel = src.relativeTo(from)
            val dst = File(to, rel.path)
            when {
                src.isDirectory -> dst.mkdirs()
                src.isFile -> {
                    dst.parentFile?.mkdirs()
                    if (!dst.exists()) src.copyTo(dst)
                }
            }
        }
    }

    private fun writeCurrent(channel: RootfsChannel, version: String, state: InstallState) {
        val file = File(filesDir, "linux/current.json")
        file.parentFile?.mkdirs()
        file.writeText(
            """{"channel":"${channel.name}","version":"$version","state":"${state.name}"}""",
            Charsets.UTF_8,
        )
    }
}
