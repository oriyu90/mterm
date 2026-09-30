package dev.studiorizi.mterm.core.process_supervisor

import java.util.concurrent.ConcurrentHashMap

/** Phantom-process pressure level. Soft-limit warnings only; no hard cap. */
enum class RiskLevel {
    NORMAL,
    WARNING,
    HIGH_RISK,
}

data class SupervisedProcess(
    val pid: Int,
    val pgid: Int,
    val backend: String,
    val startTime: Long,
)

/**
 * Tracks live child processes for the phantom-process soft limit.
 * Thread-safe; register/unregister are non-suspending so native
 * reaper callbacks can use them without a coroutine scope.
 */
class ProcessSupervisor {
    private val procs = ConcurrentHashMap<Int, SupervisedProcess>()

    fun register(p: SupervisedProcess) {
        procs[p.pid] = p
    }

    fun unregister(pid: Int) {
        procs.remove(pid)
    }

    fun snapshot(): List<SupervisedProcess> = procs.values.toList()

    fun childCount(): Int = procs.size

    fun riskLevel(): RiskLevel = when {
        childCount() >= HIGH_RISK_THRESHOLD -> RiskLevel.HIGH_RISK
        childCount() >= WARNING_THRESHOLD -> RiskLevel.WARNING
        else -> RiskLevel.NORMAL
    }

    fun shouldWarn(): Boolean = riskLevel() != RiskLevel.NORMAL

    companion object {
        const val WARNING_THRESHOLD = 24
        const val HIGH_RISK_THRESHOLD = 32
    }
}
