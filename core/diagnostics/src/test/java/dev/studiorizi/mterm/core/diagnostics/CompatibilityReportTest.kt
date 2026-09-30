package dev.studiorizi.mterm.core.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityReportTest {
    private fun sample() = DiagnosticsCollector.collect(
        androidApi = 36,
        manufacturer = "Google",
        model = "Pixel 9",
        abi = "arm64-v8a",
        pageSize = 16384,
        appTargetSdk = 28,
        buildVariant = "full",
        pty = "PASS",
        appDataExec = "PASS",
        proot = "PASS",
        debian = "13.7-r1",
        nestedExec = "PASS",
        nodeVersion = "24.0.0",
        processCount = 8,
        rootSu = false,
        storageGrants = 1,
        bridge = "PASS",
    )

    @Test
    fun collectPassthrough() {
        val report = sample()
        assertEquals(36, report.androidApi)
        assertEquals("Google", report.manufacturer)
        assertEquals("13.7-r1", report.debian)
        assertEquals("24.0.0", report.nodeVersion)
    }

    @Test
    fun serializes() {
        val encoded = Json.encodeToString(CompatibilityReport.serializer(), sample())
        val decoded = Json.decodeFromString(CompatibilityReport.serializer(), encoded)
        assertEquals(sample(), decoded)
    }

    @Test
    fun pageSizeOk() {
        assertTrue(DiagnosticsCollector.pageSizeOk(4096))
        assertTrue(DiagnosticsCollector.pageSizeOk(16384))
        assertFalse(DiagnosticsCollector.pageSizeOk(8192))
    }
}
