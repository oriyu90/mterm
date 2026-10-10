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
 * Pure-JVM tar extractor for archives (`.tar.gz` via JDK, `.tar.xz` via the
 * vendored xz library; format auto-detected by magic bytes).
 *
 * Parses USTAR/POSIX ustar headers manually over [GZIPInputStream]; every
 * entry passes through [TarSafety] (absolute/traversal rejection plus a
 * post-extract symlink-escape check). Supported: directories, regular files
 * (mode exec bits + mtime restored best-effort), symlinks and hardlinks
 * (best-effort, skipped when the filesystem refuses). Device nodes, fifos
 * and sockets are skipped (unprivileged installs cannot create them, and a
 * debootstrap minbase must not contain any).
 */
object TarExtractor {

    const val BLOCK = 512

    data class ExtractStats(val entries: Int, val bytes: Long, val skipped: Int)

    suspend fun extract(
        archive: File,
        base: File,
        onProgress: (entries: Int) -> Unit = {},
        stripComponents: Int = 0,
    ): Result<ExtractStats> = withContext(Dispatchers.IO) {
        try {
            if (!base.isDirectory && !base.mkdirs()) {
                return@withContext Result.failure(
                    IllegalStateException("cannot create $base"),
                )
            }
            // Probe magic (gzip 1F 8B, xz FD 37 7A 58 5A 00) before
            // committing to a decoder.
            val magic = ByteArray(6)
            archive.inputStream().buffered().use { probe ->
                var filled = 0
                while (filled < magic.size) {
                    val got = probe.read(magic, filled, magic.size - filled)
                    if (got < 0) break
                    filled += got
                }
                if (filled < 2) {
                    return@withContext Result.failure(
                        IllegalArgumentException("empty archive: ${archive.name}"),
                    )
                }
            }
            val isGzip = magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
            val isXz = isXzMagic(magic)
            if (!isGzip && !isXz) {
                return@withContext Result.failure(
                    IllegalArgumentException("unknown archive format: ${archive.name}"),
                )
            }
            archive.inputStream().buffered().use { raw ->
                val decoded: InputStream = if (isGzip) {
                    GZIPInputStream(raw)
                } else {
                    org.tukaani.xz.XZInputStream(raw)
                }
                extractTar(decoded, base, onProgress, stripComponents)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isXzMagic(magic: ByteArray): Boolean =
        magic.size >= 6 &&
            magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
            magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() &&
            magic[4] == 0x5A.toByte() && magic[5] == 0x00.toByte()

    private fun extractTar(
        input: InputStream,
        base: File,
        onProgress: (Int) -> Unit,
        stripComponents: Int = 0,
    ): Result<ExtractStats> {
        var entries = 0
        var bytes = 0L
        var skipped = 0
        var pendingLongName: String? = null
        var pendingPaxPath: String? = null
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
            // GNU LongLink ('L'): payload is the full path of the NEXT entry.
            // PAX extended header ('x' global 'g'): payload holds key=value
            // records; only `path` matters here (node-tar emits truncated
            // 100-byte names with the real path in PAX records).
            if (type == 'L' || type == 'x' || type == 'g') {
                val payload = ByteArray(size.toInt())
                var filled = 0
                while (filled < payload.size) {
                    val got = input.read(payload, filled, payload.size - filled)
                    if (got < 0) {
                        return Result.failure(
                            IllegalStateException("truncated extended header"),
                        )
                    }
                    filled += got
                }
                skipBytes(input, (dataBlocks * BLOCK - size))
                if (type == 'L') {
                    pendingLongName = String(payload, Charsets.UTF_8).trimEnd('\u0000', '\n')
                } else {
                    parsePaxRecords(payload)?.let { pendingPaxPath = it }
                }
                continue
            }
            val rawName = pendingLongName ?: pendingPaxPath ?: fullName
            pendingLongName = null
            pendingPaxPath = null
            // Strip leading components (e.g. node tarball top-level dir).
            // NOTE: strip applies to rawName (LongLink/PAX-resolved), never
            // to the possibly-truncated header name.
            val stripped = if (stripComponents > 0) {
                val parts = rawName.split('/').filter { it.isNotEmpty() }
                if (parts.size <= stripComponents) {
                    skipBytes(input, dataBlocks.toLong() * BLOCK)
                    skipped++
                    continue
                }
                parts.drop(stripComponents).joinToString("/")
            } else {
                rawName
            }
            val target = TarSafety.resolveUnder(base, stripped).getOrElse {
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
                                    IllegalStateException("truncated entry: $rawName"),
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

    /**
     * Parses PAX extended-header records (`<len> <key>=<value>\n`) and
     * returns the `path` override, or null.
     */
    private fun parsePaxRecords(payload: ByteArray): String? {
        var path: String? = null
        var pos = 0
        val text = String(payload, Charsets.UTF_8)
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos)
            if (nl < 0) break
            val line = text.substring(pos, nl)
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(line.indexOf(' ') + 1, eq).trim()
                if (key == "path") path = line.substring(eq + 1)
            }
            pos = nl + 1
        }
        return path?.takeIf { it.isNotEmpty() }
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
