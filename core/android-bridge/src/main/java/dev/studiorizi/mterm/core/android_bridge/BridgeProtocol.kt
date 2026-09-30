package dev.studiorizi.mterm.core.android_bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bridge protocol: length-prefixed JSON frames.
 * Frame = 4-byte big-endian payload length + UTF-8 JSON of [BridgeRequest].
 */
@Serializable
data class BridgeRequest(
    val v: Int = 1,
    val id: String,
    val method: String,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
data class BridgeResponse(
    val id: String,
    val ok: Boolean,
    val result: String? = null,
    val error: String? = null,
)

object BridgeMethods {
    const val OPEN_URL = "open.url"
    const val OPEN_PATH = "open.path"
    const val CLIPBOARD_COPY = "clipboard.copy"
    const val CLIPBOARD_PASTE = "clipboard.paste"
    const val NOTIFICATION_SHOW = "notification.show"
    const val APP_INFO = "app.info"

    val ALL: Set<String> = setOf(
        OPEN_URL,
        OPEN_PATH,
        CLIPBOARD_COPY,
        CLIPBOARD_PASTE,
        NOTIFICATION_SHOW,
        APP_INFO,
    )
}

object BridgeCodec {
    const val MAX_PAYLOAD_BYTES = 1024 * 1024 // 1 MiB

    private val json = Json { ignoreUnknownKeys = false }

    fun encode(req: BridgeRequest): ByteArray {
        require(isValidMethod(req.method)) { "unknown bridge method: ${req.method}" }
        val payload = json.encodeToString(BridgeRequest.serializer(), req).toByteArray(Charsets.UTF_8)
        require(payload.size in 1..MAX_PAYLOAD_BYTES) { "payload size out of range: ${payload.size}" }
        val buf = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    fun decodeFrame(bytes: ByteArray): BridgeRequest {
        require(bytes.size >= 4) { "frame too short: ${bytes.size}" }
        val length = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        require(length in 1..MAX_PAYLOAD_BYTES) { "invalid length prefix: $length" }
        require(bytes.size - 4 == length) { "length prefix mismatch: declared=$length actual=${bytes.size - 4}" }
        val payload = bytes.copyOfRange(4, bytes.size).toString(Charsets.UTF_8)
        val req = try {
            json.decodeFromString(BridgeRequest.serializer(), payload)
        } catch (e: Exception) {
            throw IllegalArgumentException("malformed bridge frame", e)
        }
        require(isValidMethod(req.method)) { "unknown bridge method: ${req.method}" }
        return req
    }

    fun isValidMethod(m: String): Boolean = m in BridgeMethods.ALL
}
