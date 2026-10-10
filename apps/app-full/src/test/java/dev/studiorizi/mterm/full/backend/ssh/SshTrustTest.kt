package dev.studiorizi.mterm.full.backend.ssh

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

class SshTrustTest {
    @Test
    fun `timeout yields DENY and clears pending`() {
        val before = SshTrust.pending.value.size
        val decision = SshTrust.awaitDecision("h", 22, "fp", 50)
        assertEquals(SshTrust.Decision.DENY, decision)
        assertEquals(before, SshTrust.pending.value.size)
    }

    @Test
    fun `decide delivers to waiter`() {
        var got: SshTrust.Decision? = null
        val t = thread {
            got = SshTrust.awaitDecision("h2", 2222, "fp2", 5000)
        }
        // Wait for the request to appear, then approve.
        var req: SshTrust.Request? = null
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && req == null) {
            req = SshTrust.pending.value.firstOrNull { it.host == "h2" }
            if (req == null) Thread.sleep(20)
        }
        val id = req?.id ?: throw AssertionError("no trust request posted")
        SshTrust.decide(id, SshTrust.Decision.ALWAYS)
        t.join(4000)
        assertEquals(SshTrust.Decision.ALWAYS, got)
    }
}
