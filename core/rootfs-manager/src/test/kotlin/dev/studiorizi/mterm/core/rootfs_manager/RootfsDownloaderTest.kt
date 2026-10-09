package dev.studiorizi.mterm.core.rootfs_manager

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RootfsDownloaderTest {

    private lateinit var dir: File
    private var server: HttpServer? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mterm-dl-test").toFile()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(
        payload: ByteArray,
        range: Boolean = true,
        onRequest: ((headers: Map<String, List<String>>) -> Unit)? = null,
    ): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/file") { exchange ->
            try {
                onRequest?.invoke(exchange.requestHeaders.toMap())
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (range && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val start = rangeHeader.removePrefix("bytes=").removeSuffix("-").toLong()
                    val slice = payload.copyOfRange(start.toInt(), payload.size)
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes $start-${payload.size - 1}/${payload.size}",
                    )
                    exchange.sendResponseHeaders(206, slice.size.toLong())
                    exchange.responseBody.use { it.write(slice) }
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
            } finally {
                exchange.close()
            }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}/file"
    }

    @Test
    fun download_fullAndReportsProgress() = runTest {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val url = serve(payload)
        val seen = mutableListOf<Pair<Long, Long>>()
        val dest = File(dir, "out.bin")
        val result = RootfsDownloader.download(url, dest, payload.size.toLong()) { done, total ->
            seen.add(done to total)
        }.getOrThrow()
        assertEquals(dest, result)
        assertTrue(dest.readBytes().contentEquals(payload))
        assertTrue(seen.isNotEmpty())
        assertEquals(payload.size.toLong(), seen.last().first)
        // No leftover .part after promotion.
        assertTrue(!File(dir, "out.bin.part").exists())
    }

    @Test
    fun download_resumesPartialFile() = runTest {
        val payload = ByteArray(100_000) { (it % 97).toByte() }
        var ranged = false
        val url = serve(payload) { headers ->
            if (headers.containsKey("Range")) ranged = true
        }
        // Pre-seed the first half as a .part file.
        File(dir, "out.bin.part").writeBytes(payload.copyOfRange(0, 50_000))
        val dest = File(dir, "out.bin")
        RootfsDownloader.download(url, dest, payload.size.toLong()).getOrThrow()
        assertTrue(ranged)
        assertTrue(dest.readBytes().contentEquals(payload))
    }

    @Test
    fun download_sizeMismatchFails() = runTest {
        val payload = ByteArray(10_000) { 7 }
        val url = serve(payload)
        val result = RootfsDownloader.download(url, File(dir, "out.bin"), 99_999L)
        assertTrue(result.isFailure)
    }

    @Test
    fun download_httpErrorFails() = runTest {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        http.start()
        server = http
        val result = RootfsDownloader.download(
            "http://127.0.0.1:${http.address.port}/missing",
            File(dir, "out.bin"),
            -1L,
        )
        assertTrue(result.isFailure)
    }
}
