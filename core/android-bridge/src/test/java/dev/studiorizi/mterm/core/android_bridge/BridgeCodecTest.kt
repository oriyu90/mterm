package dev.studiorizi.mterm.core.android_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BridgeCodecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundtrip() {
        val req = BridgeRequest(
            id = "req-1",
            method = BridgeMethods.CLIPBOARD_COPY,
            params = mapOf("text" to "hello", "sensitive" to "true"),
        )
        val decoded = BridgeCodec.decodeFrame(BridgeCodec.encode(req))
        assertEquals(req, decoded)
    }

    @Test
    fun oversizedRejected() {
        val oversized = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(BridgeCodec.MAX_PAYLOAD_BYTES + 1)
            .array()
        try {
            BridgeCodec.decodeFrame(oversized)
            fail("expected oversized frame to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun malformedRejected() {
        // Declared length matches but payload is not JSON.
        val payload = "not-json{{{".toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(4 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .put(payload)
            .array()
        try {
            BridgeCodec.decodeFrame(frame)
            fail("expected malformed frame to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // Truncated frame: length prefix claims more than present.
        val truncated = ByteBuffer.allocate(4 + 2)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(100)
            .put(byteArrayOf(0x7B, 0x7D))
            .array()
        try {
            BridgeCodec.decodeFrame(truncated)
            fail("expected truncated frame to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun wrongMethodRejected() {
        assertFalse(BridgeCodec.isValidMethod("bogus.method"))
        assertTrue(BridgeCodec.isValidMethod(BridgeMethods.OPEN_URL))
        try {
            BridgeCodec.encode(BridgeRequest(id = "x", method = "bogus.method"))
            fail("expected encode with wrong method to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // Raw frame carrying an unknown method must also be rejected on decode.
        val payload = """{"v":1,"id":"y","method":"bogus.method","params":{}}""".toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(4 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .put(payload)
            .array()
        try {
            BridgeCodec.decodeFrame(frame)
            fail("expected decode with wrong method to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun pathPolicy() {
        val policy = BridgePathPolicy(listOf(tmp.root))
        assertTrue(policy.checkGuestPath("docs/note.txt").isSuccess)
        assertTrue(policy.checkGuestPath("../evil.sh").isFailure)
        assertTrue(policy.checkGuestPath("a/b\u0000c").isFailure)
        assertTrue(policy.checkGuestPath("/etc/passwd").isFailure)
        assertTrue(policy.checkGuestPath("").isFailure)
    }
}
