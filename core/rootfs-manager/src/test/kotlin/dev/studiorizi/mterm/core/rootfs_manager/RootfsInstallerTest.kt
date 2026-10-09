package dev.studiorizi.mterm.core.rootfs_manager

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RootfsInstallerTest {

    private lateinit var filesDir: File
    private var server: HttpServer? = null
    private lateinit var publicKey: ByteArray
    private lateinit var privateKey: java.security.PrivateKey

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("mterm-installer-test").toFile()
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        privateKey = kp.private
        // X.509 SPKI is a 12-byte prefix + the 32-byte raw key.
        publicKey = kp.public.encoded.takeLast(32).toByteArray()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        filesDir.deleteRecursively()
    }

    private fun ustar(name: String, data: ByteArray): ByteArray {
        val header = ByteArray(512)
        fun put(off: Int, len: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            bytes.copyInto(header, off, 0, minOf(bytes.size, len))
        }
        fun octal(off: Int, len: Int, value: Long) {
            put(off, len, value.toString(8).padStart(len - 1, '0') + "\u0000")
        }
        put(0, 100, name)
        octal(100, 8, 0b110100100)
        octal(124, 12, data.size.toLong())
        octal(136, 12, 1_700_000_000L)
        header[156] = '0'.code.toByte()
        put(257, 6, "ustar")
        put(263, 2, "00")
        var checksum = 0
        for (i in 0 until 512) {
            checksum += if (i in 148 until 156) ' '.code else header[i].toInt() and 0xFF
        }
        put(148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")
        val body = ByteArray(((data.size + 511) / 512) * 512)
        data.copyInto(body)
        return header + body
    }

    private fun gzip(vararg blocks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for (block in blocks) gz.write(block)
            gz.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun signedManifestJson(archive: ByteArray, version: String): String {
        val unsigned = RootfsManifest(
            schema = 1,
            id = "debian-trixie-arm64",
            version = version,
            arch = "arm64",
            sha256 = sha256Hex(archive),
            size = archive.size.toLong(),
            minAppVersion = 10000,
            createdAt = "2026-10-10T00:00:00Z",
        )
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(privateKey)
            update(ManifestVerifier.canonicalBytes(unsigned))
        }.sign()
        val signed = unsigned.copy(signature = "ed25519:" + Base64.getEncoder().encodeToString(sig))
        return Json.encodeToString(signed)
    }

    private fun serve(manifestJson: String, archive: ByteArray): Pair<String, String> {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/manifest.json") { exchange ->
            val bytes = manifestJson.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        http.createContext("/rootfs.tar.gz") { exchange ->
            exchange.sendResponseHeaders(200, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
            exchange.close()
        }
        http.start()
        server = http
        val base = "http://127.0.0.1:${http.address.port}"
        return "$base/manifest.json" to "$base/rootfs.tar.gz"
    }

    @Test
    fun install_fullPipelineReachesReady() = runTest {
        val archive = gzip(ustar("etc/hostname", "mterm-test\n".toByteArray()))
        val (manifestUrl, archiveUrl) = serve(signedManifestJson(archive, "13.7-r1"), archive)
        val installer = RootfsInstaller(filesDir, publicKey)
        val states = mutableListOf<InstallState>()
        val installed = installer.install(
            RootfsChannel.STABLE,
            manifestUrl,
            onState = { state, _, _ -> states.add(state) },
            archiveUrlOverride = archiveUrl,
        ).getOrThrow()
        assertEquals(InstallState.READY, installed.state)
        assertEquals("mterm-test\n", File(installed.rootfsDir, "etc/hostname").readText())
        assertTrue(File(installed.rootfsDir, "etc/resolv.conf").isFile)
        assertTrue(File(installed.rootfsDir, "home/user").isDirectory)
        assertTrue(states.contains(InstallState.DOWNLOADING))
        assertTrue(states.contains(InstallState.READY))
        // current.json round-trips.
        val current = installer.currentInstall()
        assertEquals(InstallState.READY, current?.state)
        assertEquals("13.7-r1", current?.version)
    }

    @Test
    fun install_readyIsNoOp() = runTest {
        val archive = gzip(ustar("etc/hostname", "x\n".toByteArray()))
        val (manifestUrl, archiveUrl) = serve(signedManifestJson(archive, "13.7-r1"), archive)
        val installer = RootfsInstaller(filesDir, publicKey)
        installer.install(
            RootfsChannel.STABLE,
            manifestUrl,
            archiveUrlOverride = archiveUrl,
        ).getOrThrow()
        val states = mutableListOf<InstallState>()
        val second = installer.install(
            RootfsChannel.STABLE,
            manifestUrl,
            onState = { state, _, _ -> states.add(state) },
            archiveUrlOverride = archiveUrl,
        ).getOrThrow()
        assertEquals(InstallState.READY, second.state)
        assertTrue(states.isEmpty())
    }

    @Test
    fun install_badSignatureFails() = runTest {
        val archive = gzip(ustar("etc/hostname", "x\n".toByteArray()))
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val unsigned = RootfsManifest(
            schema = 1, id = "debian-trixie-arm64", version = "13.7-r1", arch = "arm64",
            sha256 = sha256Hex(archive), size = archive.size.toLong(),
            minAppVersion = 10000, createdAt = "2026-10-10T00:00:00Z",
        )
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(other.private)
            update(ManifestVerifier.canonicalBytes(unsigned))
        }.sign()
        val json = Json.encodeToString(
            unsigned.copy(signature = "ed25519:" + Base64.getEncoder().encodeToString(sig)),
        )
        val (manifestUrl, archiveUrl) = serve(json, archive)
        val result = RootfsInstaller(filesDir, publicKey)
            .install(
                RootfsChannel.STABLE,
                manifestUrl,
                archiveUrlOverride = archiveUrl,
            )
        assertTrue(result.isFailure)
    }

    @Test
    fun install_shaMismatchFailsAndCleansArchive() = runTest {
        val archive = gzip(ustar("etc/hostname", "x\n".toByteArray()))
        val unsigned = RootfsManifest(
            schema = 1, id = "debian-trixie-arm64", version = "13.7-r1", arch = "arm64",
            sha256 = "00".repeat(32), size = archive.size.toLong(),
            minAppVersion = 10000, createdAt = "2026-10-10T00:00:00Z",
        )
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(privateKey)
            update(ManifestVerifier.canonicalBytes(unsigned))
        }.sign()
        val json = Json.encodeToString(
            unsigned.copy(signature = "ed25519:" + Base64.getEncoder().encodeToString(sig)),
        )
        val (manifestUrl, archiveUrl) = serve(json, archive)
        val result = RootfsInstaller(filesDir, publicKey)
            .install(
                RootfsChannel.STABLE,
                manifestUrl,
                archiveUrlOverride = archiveUrl,
            )
        assertTrue(result.isFailure)
        assertTrue(!File(filesDir, "linux/cache/debian-trixie-arm64-13.7-r1.tar.gz").exists())
    }

    @Test
    fun install_carriesOverPreviousHome() = runTest {
        // Seed a READY v1 install with user data.
        val v1root = File(filesDir, "linux/distributions/debian/13.7-r0/rootfs")
        File(v1root, "home/user").mkdirs()
        File(v1root, "home/user/keep.txt").writeText("precious")
        File(filesDir, "linux").mkdirs()
        File(filesDir, "linux/current.json").writeText(
            """{"channel":"STABLE","version":"13.7-r0","state":"READY"}""",
        )
        val archive = gzip(ustar("etc/hostname", "v2\n".toByteArray()))
        val (manifestUrl, archiveUrl) = serve(signedManifestJson(archive, "13.7-r1"), archive)
        val installed = RootfsInstaller(filesDir, publicKey)
            .install(
                RootfsChannel.STABLE,
                manifestUrl,
                archiveUrlOverride = archiveUrl,
            ).getOrThrow()
        assertEquals("precious", File(installed.rootfsDir, "home/user/keep.txt").readText())
        assertEquals("v2\n", File(installed.rootfsDir, "etc/hostname").readText())
    }
}
