package dev.studiorizi.mterm.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTuneTest {

    private fun caps() = DeviceCaps(
        networkTransport = "WIFI",
        networkMetered = false,
        networkValidated = true,
        freeBytes = 8L * 1024 * 1024 * 1024,
        totalBytes = 64L * 1024 * 1024 * 1024,
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        lowRamDevice = false,
        safGrants = 1,
        pageSize = 4096,
        apiLevel = 36,
    )

    @Test
    fun `healthy device keeps safe defaults with mirror on`() {
        val d = AutoTune.decide(caps())
        assertTrue(d.wifiOnlyDownload)
        assertTrue(d.autoMirrorSync)
        assertEquals(10_000, d.scrollbackLines)
        assertTrue(d.noteKeys.contains("net_ok"))
    }

    @Test
    fun `mirror stays off without SAF grants`() {
        val d = AutoTune.decide(caps().copy(safGrants = 0))
        assertFalse(d.autoMirrorSync)
        assertTrue(d.noteKeys.contains("no_saf_tree"))
    }

    @Test
    fun `low ram downscales scrollback`() {
        val d = AutoTune.decide(caps().copy(totalRamBytes = 1_500_000_000L))
        assertEquals(1_000, d.scrollbackLines)
        assertTrue(d.noteKeys.contains("low_ram"))
    }

    @Test
    fun `large ram never upscales scrollback`() {
        val d = AutoTune.decide(caps().copy(totalRamBytes = 16L * 1024 * 1024 * 1024))
        assertEquals(10_000, d.scrollbackLines)
    }

    @Test
    fun `metered and offline notes surface`() {
        assertTrue(AutoTune.decide(caps().copy(networkMetered = true)).noteKeys.contains("metered"))
        assertTrue(AutoTune.decide(caps().copy(networkTransport = "NONE")).noteKeys.contains("offline"))
    }

    @Test
    fun `low storage and odd page size warn`() {
        val d = AutoTune.decide(caps().copy(freeBytes = 500_000_000L, pageSize = 8192))
        assertTrue(d.noteKeys.contains("low_storage"))
        assertTrue(d.noteKeys.contains("page_size_warn"))
    }
}
