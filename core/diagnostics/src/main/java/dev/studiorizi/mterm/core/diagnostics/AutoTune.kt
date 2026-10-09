package dev.studiorizi.mterm.core.diagnostics

/**
 * Device capability snapshot for auto-tuning.
 *
 * Collected on the app layer ([collectCaps] equivalent) with API guards;
 * this module only holds the data shape plus the pure [decide] rules so the
 * policy stays unit-testable on plain JVM without Android.
 */
data class DeviceCaps(
    /** Active transport: WIFI, CELLULAR, ETHERNET, VPN, OTHER, or NONE. */
    val networkTransport: String = "NONE",
    val networkMetered: Boolean = true,
    val networkValidated: Boolean = false,
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val totalRamBytes: Long = 0L,
    val lowRamDevice: Boolean = false,
    val safGrants: Int = 0,
    val pageSize: Int = 4096,
    val apiLevel: Int = 29,
)

/** Result of [AutoTune.decide]: safe-side defaults plus human-readable notes. */
data class TuneDecision(
    val wifiOnlyDownload: Boolean,
    val autoMirrorSync: Boolean,
    val scrollbackLines: Int,
    /** Ordered note lines for the report UI (already localized by caller). */
    val noteKeys: List<String>,
)

/**
 * Pure auto-tune policy.
 *
 * Principles: never loosen a safe default automatically (wifi-only stays on),
 * only downscale resource usage on constrained devices (never upscale), and
 * explain everything through notes instead of silent changes.
 */
object AutoTune {

    const val MIN_FREE_BYTES_FOR_ROOTFS = 2L * 1024 * 1024 * 1024
    const val LOW_RAM_BYTES = 2L * 1024 * 1024 * 1024
    const val SMALL_RAM_BYTES = 4L * 1024 * 1024 * 1024

    fun decide(caps: DeviceCaps): TuneDecision {
        val notes = mutableListOf<String>()
        // Rootfs archives are large: keep Wi-Fi-only always (safe side).
        val wifiOnly = true
        if (caps.networkTransport == "NONE") {
            notes.add("offline")
        } else {
            notes.add(if (caps.networkValidated) "net_ok" else "net_unvalidated")
            if (caps.networkMetered) notes.add("metered")
        }
        // Mirror sync is only useful once the user granted a SAF tree.
        val mirror = caps.safGrants > 0
        if (!mirror) notes.add("no_saf_tree")
        // Scrollback: downscale on constrained devices, never upscale.
        val scrollback = when {
            caps.lowRamDevice || caps.totalRamBytes in 1..LOW_RAM_BYTES -> {
                notes.add("low_ram")
                1_000
            }
            caps.totalRamBytes in 1..SMALL_RAM_BYTES -> 10_000
            else -> 10_000
        }
        if (caps.freeBytes in 1 until MIN_FREE_BYTES_FOR_ROOTFS) {
            notes.add("low_storage")
        }
        if (!DiagnosticsCollector.pageSizeOk(caps.pageSize)) {
            notes.add("page_size_warn")
        }
        return TuneDecision(
            wifiOnlyDownload = wifiOnly,
            autoMirrorSync = mirror,
            scrollbackLines = scrollback,
            noteKeys = notes,
        )
    }
}

/**
 * WCAG-style relative-luminance contrast ratio for theme validation.
 * Pure math on sRGB bytes; usable from JVM unit tests.
 */
object Contrast {

    /** Contrast ratio in 1..21 for opaque [fg] over opaque [bg] (ARGB ints). */
    fun ratio(fg: Int, bg: Int): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(bits: Int): Double {
            val c = bits / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
