package dev.studiorizi.mterm.full.backend

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.Process
import androidx.core.app.NotificationCompat
import dev.studiorizi.mterm.core.android_bridge.BridgeCodec
import dev.studiorizi.mterm.core.android_bridge.BridgeMethods
import dev.studiorizi.mterm.core.android_bridge.BridgeRequest
import dev.studiorizi.mterm.core.android_bridge.BridgeResponse
import dev.studiorizi.mterm.core.linux_core.RootfsPathMapper
import dev.studiorizi.mterm.full.R
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android side of the guest bridge (UDS, abstract namespace).
 *
 * Listens on `@mterm-bridge-<uid>` (guest env `MTERM_BRIDGE_SOCK`, see
 * [ProotArgv] wiring); abstract namespace needs no filesystem permissions,
 * works on all API levels, and vanishes with the process. One method per
 * connection; frames are [BridgeCodec] length-prefixed JSON both ways.
 *
 * Owned by `TerminalService` (start on create, stop on destroy). Failures
 * are per-request errors, never crashes. `open.*` never launches activities
 * from the background: the user taps a notification instead.
 */
class BridgeServer(
    private val appContext: Context,
    private val rootfsDir: File,
    private val bridgeDir: File,
    private val mirrorDir: File,
    private val appVersion: String,
    private val debianReady: () -> Boolean,
) {
    private val mapper = RootfsPathMapper(rootfsDir, bridgeDir, mirrorDir)
    private var server: LocalServerSocket? = null
    private var loop: Thread? = null
    private val running = AtomicBoolean(false)

    companion object {
        /** Abstract-namespace socket name (guest env carries `@`-prefixed form). */
        fun socketName(uid: Int = Process.myUid()): String = "mterm-bridge-$uid"

        fun guestEnvSock(uid: Int = Process.myUid()): String = "@" + socketName(uid)

        const val CHANNEL_ID = "bridge"
    }

    fun isRunning(): Boolean = running.get()

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        return try {
            val sock = LocalServerSocket(socketName())
            server = sock
            loop = thread(name = "bridge-server", isDaemon = true) { acceptLoop(sock) }
            true
        } catch (_: Exception) {
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
    }

    private fun acceptLoop(sock: LocalServerSocket) {
        while (running.get()) {
            val conn = try {
                sock.accept()
            } catch (_: IOException) {
                break
            } ?: break

            try {
                conn.soTimeout = 10_000
                handle(conn)
            } catch (_: Exception) {
            } finally {
                try {
                    conn.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun handle(conn: LocalSocket) {
        val input = conn.inputStream
        val req = try {
            BridgeCodec.decodeFrame(readFrame(input))
        } catch (e: Exception) {
            writeReply(conn, BridgeResponse("", false, error = "malformed frame: ${e.message}".take(200)))
            return
        }
        val res = try {
            dispatch(req)
        } catch (e: Exception) {
            BridgeResponse(req.id, false, error = "${e.message}".take(300))
        }
        try {
            writeReply(conn, res)
        } catch (_: Exception) {
        }
    }

    private fun readFrame(input: java.io.InputStream): ByteArray {
        val header = ByteArray(4)
        readFully(input, header, 4)
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length !in 1..BridgeCodec.MAX_PAYLOAD_BYTES) {
            throw IllegalArgumentException("bad length: $length")
        }
        val body = ByteArray(length)
        readFully(input, body, length)
        return header + body
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("truncated frame")
            off += n
        }
    }

    private fun writeReply(conn: LocalSocket, res: BridgeResponse) {
        conn.outputStream.write(BridgeCodec.encodeResponse(res))
        conn.outputStream.flush()
    }

    internal fun dispatch(req: BridgeRequest): BridgeResponse {
        return when (req.method) {
            BridgeMethods.APP_INFO -> BridgeResponse(
                req.id,
                true,
                result = "mterm-full/$appVersion debian=${if (debianReady()) "ready" else "missing"}",
            )
            BridgeMethods.NOTIFICATION_SHOW -> {
                val title = (req.params["title"] ?: "mterm").take(120)
                val body = (req.params["body"] ?: "").take(500)
                postNotification(title, body, null, req.id.hashCode())
                BridgeResponse(req.id, true, result = "posted")
            }
            BridgeMethods.CLIPBOARD_COPY -> {
                val text = (req.params["text"] ?: "").take(256 * 1024)
                val cm = appContext.getSystemService(ClipboardManager::class.java)
                    ?: return BridgeResponse(req.id, false, error = "no clipboard service")
                cm.setPrimaryClip(ClipData.newPlainText("mterm", text))
                BridgeResponse(req.id, true, result = "copied ${text.length}")
            }
            BridgeMethods.CLIPBOARD_PASTE -> {
                val cm = appContext.getSystemService(ClipboardManager::class.java)
                    ?: return BridgeResponse(req.id, false, error = "no clipboard service")
                val text = try {
                    cm.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString() ?: ""
                } catch (_: SecurityException) {
                    return BridgeResponse(req.id, false, error = "clipboard denied")
                }
                BridgeResponse(req.id, true, result = text.take(256 * 1024))
            }
            BridgeMethods.OPEN_URL -> {
                val url = (req.params["url"] ?: "").take(2000)
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return BridgeResponse(req.id, false, error = "only http(s) allowed")
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                postNotification(
                    appContext.getString(R.string.bridge_open_url),
                    url.take(200),
                    PendingIntent.getActivity(
                        appContext,
                        req.id.hashCode(),
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                    req.id.hashCode(),
                )
                BridgeResponse(req.id, true, result = "notified")
            }
            BridgeMethods.OPEN_PATH -> {
                val guest = (req.params["path"] ?: "").take(1024)
                val host = mapper.sharedToHost(guest).getOrElse {
                    return BridgeResponse(req.id, false, error = "${it.message}".take(200))
                }
                if (!host.isFile) {
                    return BridgeResponse(req.id, false, error = "not a shared file: $guest")
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    appContext.packageName + ".provider",
                    host,
                )
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                postNotification(
                    host.name.take(120),
                    guest.take(200),
                    PendingIntent.getActivity(
                        appContext,
                        req.id.hashCode(),
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                    req.id.hashCode(),
                )
                BridgeResponse(req.id, true, result = "notified")
            }
            else -> BridgeResponse(req.id, false, error = "unknown method")
        }
    }

    private fun postNotification(title: String, body: String, action: PendingIntent?, id: Int) {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.bridge_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
        if (action != null) builder.setContentIntent(action)
        try {
            nm.notify(id, builder.build())
        } catch (_: SecurityException) {
            // Notifications disabled: fail silently, reply already sent.
        }
    }

    /**
     * One-shot client: connects, runs `app.info`, returns the result string
     * or null. Used by Diagnostics to report live bridge status.
     */
    suspend fun ping(): String? = withContext(Dispatchers.IO) {
        try {
            val sock = LocalSocket()
            try {
                // No connect timeout: AF_UNIX connect returns immediately
                // (or refuses); the timed connect() overload throws
                // UnsupportedOperationException on this platform. Reads below
                // are still bounded by soTimeout.
                sock.connect(
                    android.net.LocalSocketAddress(
                        socketName(),
                        android.net.LocalSocketAddress.Namespace.ABSTRACT,
                    ),
                )
                sock.soTimeout = 5000
                val req = BridgeRequest(id = "ping", method = BridgeMethods.APP_INFO)
                sock.outputStream.write(BridgeCodec.encode(req))
                sock.outputStream.flush()
                val header = ByteArray(4)
                var off = 0
                while (off < 4) {
                    val n = sock.inputStream.read(header, off, 4 - off)
                    if (n < 0) return@withContext null
                    off += n
                }
                val length = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                if (length !in 1..BridgeCodec.MAX_PAYLOAD_BYTES) return@withContext null
                val body = ByteArray(length)
                off = 0
                while (off < length) {
                    val n = sock.inputStream.read(body, off, length - off)
                    if (n < 0) return@withContext null
                    off += n
                }
                val res = BridgeCodec.decodeResponse(header + body)
                if (res.ok) res.result else null
            } finally {
                try {
                    sock.close()
                } catch (_: IOException) {
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
