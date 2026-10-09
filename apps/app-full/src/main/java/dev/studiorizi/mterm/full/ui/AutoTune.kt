package dev.studiorizi.mterm.full.ui

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import dev.studiorizi.mterm.core.diagnostics.DeviceCaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects on-device capabilities for auto-tuning.
 *
 * Every probe is isolated in try/catch with API guards: a missing service or
 * a denied query degrades that field to its safe default instead of crashing.
 * No PII is read (only transports, byte counts, grant counts, page size).
 */
suspend fun collectCaps(context: Context): DeviceCaps = withContext(Dispatchers.IO) {
    var transport = "NONE"
    var metered = true
    var validated = false
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm.activeNetwork
                val caps = net?.let { cm.getNetworkCapabilities(it) }
                if (caps != null) {
                    transport = when {
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        else -> "OTHER"
                    }
                    metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                if (info != null && info.isConnected) {
                    transport = info.typeName?.uppercase() ?: "OTHER"
                    metered = cm.isActiveNetworkMetered
                    validated = true
                }
            }
        }
    } catch (_: Throwable) {
        // Keep safe defaults.
    }
    var freeBytes = 0L
    var totalBytes = 0L
    try {
        val dir = context.filesDir
        freeBytes = dir.usableSpace.coerceAtLeast(0L)
        totalBytes = dir.totalSpace.coerceAtLeast(0L)
    } catch (_: Throwable) {
        // Keep zeros.
    }
    var totalRam = 0L
    var lowRam = false
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            totalRam = info.totalMem.coerceAtLeast(0L)
            lowRam = info.lowMemory
        }
    } catch (_: Throwable) {
        // Keep zeros.
    }
    var grants = 0
    try {
        grants = context.contentResolver.persistedUriPermissions.size
    } catch (_: Throwable) {
        // Keep zero.
    }
    var pageSize = 4096
    try {
        pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).toInt()
    } catch (_: Throwable) {
        // Keep default.
    }
    DeviceCaps(
        networkTransport = transport,
        networkMetered = metered,
        networkValidated = validated,
        freeBytes = freeBytes,
        totalBytes = totalBytes,
        totalRamBytes = totalRam,
        lowRamDevice = lowRam,
        safGrants = grants,
        pageSize = pageSize,
        apiLevel = Build.VERSION.SDK_INT,
    )
}
