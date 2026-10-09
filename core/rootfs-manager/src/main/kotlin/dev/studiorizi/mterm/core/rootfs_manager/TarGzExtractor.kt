package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure-JVM `.tar.gz` extractor for rootfs archives (no extra dependencies).
 *
 * Parses USTAR/POSIX ustar headers manually over [GZIPInputStream]; every
 * entry passes through [TarSafety] (absolute/traversal rejection plus a
 * post-extract symlink-escape check). Supported: directories, regular files
 * (mode exec bits + mtime restored best-effort), symlinks and hardlinks
 * (best-effort, skipped when the filesystem refuses). Device nodes, fifos
 * and sockets are skipped (unprivileged installs cannot create them, and a
 * debootstrap minbase must not contain any).
 */
object TarGzExtractor {

    const val BLOCK = 512

    data class ExtractStats(val entries: Int, val bytes: Long, val skipped: Int)

    suspend fun extract(
        archive: File,
        base: File,
        onProgress: (entries: Int) -> Unit = {},
    ): Result<ExtractStats> = withContext(Dispatchers.IO) {
        try {
            if (!base.isDirectory && !base.mkdirs()) {
                return@withContext Result.failure(
                    IllegalStateException("cannot create $base"),
                )
            }
            archive.inputStream().buffered().use { raw ->
                // Probe gzip magic before committing to the format.
                val magic = ByteArray(2)
                val read = raw.read(magic)
                if (read < 2 || magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("not a gzip archive: ${archive.name}"),
                    )
                }
                val rewind = object : InputStream() {
                    var pos = 0
                    override fun read(): Int =
                        if (pos < read) magic[pos++].toInt() and 0xFF else raw.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (pos < read) {
                            val n = minOf(len, read - pos)
                            magic.copyInto(b, off, pos, pos + n)
                            pos += n
                            return n
                        }
                        return raw.read(b, off, len)
                    }
                }
                extractTar(GZIPInputStream(rewind), base, onProgress)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractTar(
        input: InputStream,
        base: File,
        onProgress: (Int) -> Unit,
    ): Result<ExtractStats> {
        var entries = 0
        var bytes = 0L
        var skipped = 0
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readFully(input, header)) break // clean EOF at block boundary
            if (header.all { it == 0.toByte() }) break // end-of-archive marker
            val name = headerString(header, 0, 100).trimEnd('\u0000')
            val prefix = headerString(header, 345, 155).trimEnd('\u0000')
            val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
            val size = headerOctal(header, 124, 12)
            val mode = headerOctal(header, 100, 8)
            val mtime = headerOctal(header, 136, 12) * 1000L
            val type = header[156].toInt().toChar()
            val linkName = headerString(header, 157, 100).trimEnd('\u0000')
            val dataBlocks = ((size + BLOCK - 1) / BLOCK).toInt()
            val target = TarSafety.resolveUnder(base, fullName).getOrElse {
                skipBytes(input, dataBlocks.toLong() * BLOCK)
                skipped++
                return@getOrElse null
            } ?: continue
            when (type) {
                '5' -> {
                    target.mkdirs()
                }
                '0', '\u0000' -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out ->
                        var remaining = size
                        val buf = ByteArray(32 * 1024)
                        while (remaining > 0) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            val got = input.read(buf, 0, want)
                            if (got < 0) {
                                return Result.failure(
                                    IllegalStateException("truncated entry: $fullName"),
                                )
                            }
                            out.write(buf, 0, got)
                            remaining -= got
                            bytes += got
                        }
                    }
                    skipBytes(input, (dataBlocks * BLOCK - size))
                    if (mode and 0b001001001L != 0L) target.setExecutable(true, false)
                    if (mtime > 0) {
                        runCatching {
                            Files.getFileAttributeView(
                                target.toPath(),
                                BasicFileAttributeView::class.java,
                            )?.setTimes(FileTime.fromMillis(mtime), null, null)
                        }
                    }
                }
                '2' -> {
                    skipBytes(input, dataBlocks.toLong() * BLOCK)
                    target.parentFile?.mkdirs()
                    runCatching {
                        val linkTarget = java.nio.file.Paths.get(linkName)
                        Files.createSymbolicLink(target.toPath(), linkTarget)
                        // Post-check: the resolved link must stay under base.
                        val resolved = target.parentFile.toPath()
                            .resolve(linkTarget).normalize()
                        val basePath = base.toPath().toAbsolutePath().normalize()
                        if (!resolved.startsWith(basePath)) {
                            Files.deleteIfExists(target.toPath())
                            skipped++
                        }
                    }.onFailure { skipped++ }
                }
                '1' -> {
                    skipBytes(input, dataBlocks.toLong() * BLOCK)
                    val linkTarget = TarSafety.resolveUnder(base, linkName).getOrNull()
                    if (linkTarget == null || !linkTarget.exists()) {
                        skipped++
                    } else {
                        target.parentFile?.mkdirs()
                        runCatching {
                            Files.createLink(target.toPath(), linkTarget.toPath())
                        }.onFailure { skipped++ }
                    }
                }
                else -> {
                    // Devices, fifos, sockets, vendor extensions: skip payload.
                    skipBytes(input, dataBlocks.toLong() * BLOCK)
                    skipped++
                }
            }
            entries++
            if (entries % 500 == 0) onProgress(entries)
        }
        onProgress(entries)
        return Result.success(ExtractStats(entries, bytes, skipped))
    }

    private fun headerString(header: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && header[end] != 0.toByte()) end++
        return String(header, off, end - off, Charsets.UTF_8)
    }

    private fun headerOctal(header: ByteArray, off: Int, len: Int): Long {
        val s = headerString(header, off, len).trim().trimEnd('\u0000').trim()
        if (s.isEmpty()) return 0L
        // Base-256 (binary) extension: leading 0x80 bit.
        if (header[off] == 0x80.toByte()) {
            var value = 0L
            for (i in off + 1 until off + len) {
                value = (value shl 8) or (header[i].toLong() and 0xFF)
            }
            return value
        }
        return s.toLongOrNull(8) ?: 0L
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val got = input.read(buf, off, buf.size - off)
            if (got < 0) return off > 0
            off += got
        }
        return true
    }

    private fun skipBytes(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val got = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (got < 0) return
            remaining -= got
        }
    }
}
