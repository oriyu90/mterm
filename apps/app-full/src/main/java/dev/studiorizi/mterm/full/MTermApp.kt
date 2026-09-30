package dev.studiorizi.mterm.full

import android.app.Application

/**
 * Full Sideload MVP application.
 *
 * Minimal onCreate: heavy work (Room, DataStore reads, rootfs checks) stays
 * lazy in the owning screens/services so cold start stays under budget.
 * Edge-to-edge is applied per-Activity (see MainActivity).
 */
class MTermApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Intentionally minimal: no eager DB/root/bridge init here.
    }
}
