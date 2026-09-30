package dev.studiorizi.mterm.core.process_supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessSupervisorTest {

    private fun proc(pid: Int) = SupervisedProcess(
        pid = pid,
        pgid = pid,
        backend = "proot",
        startTime = pid.toLong(),
    )

    @Test
    fun `register and snapshot`() {
        val sup = ProcessSupervisor()
        sup.register(proc(1))
        sup.register(proc(2))
        assertEquals(2, sup.childCount())
        assertEquals(setOf(1, 2), sup.snapshot().map { it.pid }.toSet())
    }

    @Test
    fun `unregister removes entry`() {
        val sup = ProcessSupervisor()
        sup.register(proc(1))
        sup.register(proc(2))
        sup.unregister(1)
        assertEquals(1, sup.childCount())
        assertEquals(listOf(2), sup.snapshot().map { it.pid })
    }

    @Test
    fun `unregister unknown pid is no-op`() {
        val sup = ProcessSupervisor()
        sup.register(proc(1))
        sup.unregister(999)
        assertEquals(1, sup.childCount())
    }

    @Test
    fun `reregister same pid replaces`() {
        val sup = ProcessSupervisor()
        sup.register(proc(1))
        sup.register(proc(1).copy(backend = "chroot"))
        assertEquals(1, sup.childCount())
        assertEquals("chroot", sup.snapshot().single().backend)
    }

    @Test
    fun `below warning threshold is normal`() {
        val sup = ProcessSupervisor()
        repeat(ProcessSupervisor.WARNING_THRESHOLD - 1) { sup.register(proc(it)) }
        assertEquals(RiskLevel.NORMAL, sup.riskLevel())
        assertFalse(sup.shouldWarn())
    }

    @Test
    fun `warning at 24`() {
        val sup = ProcessSupervisor()
        repeat(ProcessSupervisor.WARNING_THRESHOLD) { sup.register(proc(it)) }
        assertEquals(RiskLevel.WARNING, sup.riskLevel())
        assertTrue(sup.shouldWarn())
    }

    @Test
    fun `still warning at 31`() {
        val sup = ProcessSupervisor()
        repeat(ProcessSupervisor.HIGH_RISK_THRESHOLD - 1) { sup.register(proc(it)) }
        assertEquals(RiskLevel.WARNING, sup.riskLevel())
    }

    @Test
    fun `high risk at 32`() {
        val sup = ProcessSupervisor()
        repeat(ProcessSupervisor.HIGH_RISK_THRESHOLD) { sup.register(proc(it)) }
        assertEquals(RiskLevel.HIGH_RISK, sup.riskLevel())
        assertTrue(sup.shouldWarn())
    }

    @Test
    fun `risk drops after unregister`() {
        val sup = ProcessSupervisor()
        repeat(ProcessSupervisor.HIGH_RISK_THRESHOLD) { sup.register(proc(it)) }
        repeat(ProcessSupervisor.HIGH_RISK_THRESHOLD) { sup.unregister(it) }
        assertEquals(RiskLevel.NORMAL, sup.riskLevel())
        assertFalse(sup.shouldWarn())
    }
}
