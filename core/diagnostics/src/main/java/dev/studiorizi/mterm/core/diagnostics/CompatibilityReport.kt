package dev.studiorizi.mterm.core.diagnostics

import kotlinx.serialization.Serializable

/** Diagnostics schema per plan section 18.4. */
@Serializable
data class CompatibilityReport(
    val androidApi: Int,
    val manufacturer: String,
    val model: String,
    val abi: String,
    val pageSize: Int,
    val appTargetSdk: Int,
    val buildVariant: String,
    val pty: String,
    val appDataExec: String,
    val proot: String,
    val debian: String,
    val nestedExec: String,
    val nodeVersion: String? = null,
    val processCount: Int,
    val rootSu: Boolean,
    val storageGrants: Int,
    val bridge: String,
)

/**
 * Builds [CompatibilityReport]s from caller-supplied values.
 *
 * Intentionally pure: Android APIs (Build.*, ActivityManager, …) must be
 * read by the caller and passed in as params, keeping this testable on JVM.
 */
object DiagnosticsCollector {
    fun collect(
        androidApi: Int,
        manufacturer: String,
        model: String,
        abi: String,
        pageSize: Int,
        appTargetSdk: Int,
        buildVariant: String,
        pty: String,
        appDataExec: String,
        proot: String,
        debian: String,
        nestedExec: String,
        nodeVersion: String?,
        processCount: Int,
        rootSu: Boolean,
        storageGrants: Int,
        bridge: String,
    ): CompatibilityReport = CompatibilityReport(
        androidApi = androidApi,
        manufacturer = manufacturer,
        model = model,
        abi = abi,
        pageSize = pageSize,
        appTargetSdk = appTargetSdk,
        buildVariant = buildVariant,
        pty = pty,
        appDataExec = appDataExec,
        proot = proot,
        debian = debian,
        nestedExec = nestedExec,
        nodeVersion = nodeVersion,
        processCount = processCount,
        rootSu = rootSu,
        storageGrants = storageGrants,
        bridge = bridge,
    )

    fun pageSizeOk(page: Int): Boolean = page == 4096 || page == 16384
}
