package dev.studiorizi.mterm.core.rootfs_manager

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GNU LongLink ('L') regression test: node-tar emits 100-byte truncated
 * header names with the real path in a preceding LongLink entry. The
 * extractor must resolve (and strip) the LongLink name, never the
 * truncated header name.
 */
class NodeTarballReproTest {
    private fun header(name: String, type: Char, size: Int): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, len: Int, v: String) {
            val b = v.toByteArray(Charsets.UTF_8)
            b.copyInto(h, off, 0, minOf(b.size, len))
        }
        fun oct(off: Int, len: Int, v: Long) {
            put(off, len, v.toString(8).padStart(len - 1, '0') + "\u0000")
        }
        put(0, 100, name)
        oct(100, 8, 0b110100100)
        oct(124, 12, size.toLong())
        oct(136, 12, 1700000000L)
        h[156] = type.code.toByte()
        put(257, 6, "ustar")
        put(263, 2, "00")
        var c = 0
        for (i in 0 until 512) c += if (i in 148 until 156) 32 else h[i].toInt() and 0xFF
        put(148, 8, c.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }

    private fun buildTar(): ByteArray {
        val longName = "d1/d2/d3/d4/d5/d6/d7/d8/file.txt"
        val full = "top/$longName"
        val payload = (full + "\u0000").toByteArray()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            gz.write(header("././@LongLink", 'L', payload.size))
            val pad = ByteArray(((payload.size + 511) / 512) * 512)
            payload.copyInto(pad)
            gz.write(pad)
            val data = "hello-longlink\n".toByteArray()
            gz.write(header("x".repeat(100), '0', data.size))
            val body = ByteArray(((data.size + 511) / 512) * 512)
            data.copyInto(body)
            gz.write(body)
            gz.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    @Test
    fun longlink_resolvesFullName() = runTest {
        val dir = createTempDir("ll-test")
        try {
            val f = File(dir, "a.tar.gz")
            f.writeBytes(buildTar())
            val stats = TarExtractor.extract(f, File(dir, "root")).getOrThrow()
            assertEquals(
                "hello-longlink\n",
                File(dir, "root/top/d1/d2/d3/d4/d5/d6/d7/d8/file.txt").readText(),
            )
            assertTrue(stats.entries >= 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun longlink_resolvesFullNameWithStrip() = runTest {
        val dir = createTempDir("ll-test-strip")
        try {
            val f = File(dir, "a.tar.gz")
            f.writeBytes(buildTar())
            TarExtractor.extract(f, File(dir, "root"), stripComponents = 1).getOrThrow()
            // "top/" stripped: file lands at d1/.../file.txt.
            assertEquals(
                "hello-longlink\n",
                File(dir, "root/d1/d2/d3/d4/d5/d6/d7/d8/file.txt").readText(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun realNodeTarball_extractsFully() = runTest {
        val archive = File("/tmp/node-test.tar.xz")
        if (!archive.isFile) return@runTest
        val base = createTempDir("node-repro")
        try {
            val stats = TarExtractor.extract(archive, base, stripComponents = 1).getOrThrow()
            assertTrue(
                "expected thousands of entries, got ${stats.entries}",
                stats.entries > 4000,
            )
            assertTrue(File(base, "bin/node").isFile)
            assertTrue(
                File(base, "lib/node_modules/npm/node_modules/graceful-fs").exists(),
            )
        } finally {
            base.deleteRecursively()
        }
    }
}
