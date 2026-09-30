package dev.studiorizi.mterm.full.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.studiorizi.mterm.core.linux_chroot.ChrootBackend
import dev.studiorizi.mterm.core.linux_proot.ProotBackend
import dev.studiorizi.mterm.core.process_supervisor.ProcessSupervisor
import dev.studiorizi.mterm.core.rootfs_manager.RootfsManager
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.full.MainActivity
import dev.studiorizi.mterm.full.R
import dev.studiorizi.mterm.full.backend.AndroidShellBackend
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns session lifetime across Activity recreation (plan section 10.1).
 *
 * The UI binds to read [SessionManager.sessions] and issues use-case calls
 * only; it never touches su/mount/proot or PTY fds directly. The service
 * runs in the foreground while sessions exist and stops itself when the
 * session map becomes empty.
 *
 * Started from explicit user action only ("New session"); never from the
 * background. Legacy Full (targetSdk 28) uses a plain foreground service;
 * the modern target declares specialUse instead (see plan section 18.2).
 */
class TerminalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var sessionManager: SessionManager
        private set
    lateinit var supervisor: ProcessSupervisor
        private set

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getManager(): SessionManager = sessionManager
        fun getSupervisor(): ProcessSupervisor = supervisor
        fun getService(): TerminalService = this@TerminalService
    }

    override fun onCreate() {
        super.onCreate()
        supervisor = ProcessSupervisor()
        sessionManager = buildSessionManager()
        ensureChannel()
        // Stop the service once the last session exits.
        scope.launch {
            sessionManager.sessions.collectLatest { sessions ->
                updateNotification(sessions.size)
                if (sessions.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
            }
            ACTION_STOP_ALL -> {
                scope.launch {
                    sessionManager.stopAll()
                    stopForegroundCompat()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        // Re-create only via explicit user action; do not auto-restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildSessionManager(): SessionManager {
        val filesDir: File = filesDir
        val debianDir = File(filesDir, "linux/distributions/debian/current/rootfs")
        val bridgeDir = File(filesDir, "shared/bridge")
        val mirrorDir = File(filesDir, "shared/mirror")
        val prootBin = File(filesDir, "bin/proot")
        return SessionManager(
            mapOf(
                SessionMode.ANDROID_SHELL to AndroidShellBackend(),
                SessionMode.DEBIAN_PROOT to ProotBackend(
                    rootfsDir = debianDir,
                    bridgeDir = bridgeDir,
                    mirrorDir = mirrorDir,
                    prootBin = prootBin,
                ),
                SessionMode.DEBIAN_CHROOT to ChrootBackend(rootfsDir = debianDir),
            ),
        )
    }

    @Suppress("unused")
    private fun rootfsState(): String {
        // Placeholder for the installer screen; real state comes from
        // RootfsManager.ensureInstalled() on the device runtime.
        val manager = RootfsManager(filesDir, ByteArray(32))
        return manager.stagingDir().absolutePath
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.fgs_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.fgs_channel_desc)
                },
            )
        }
    }

    private fun startForegroundCompat() {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(sessionManager.sessions.value.size))
    }

    private fun updateNotification(count: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun buildNotification(count: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (count == 0) {
            getString(R.string.fgs_content_empty)
        } else {
            getString(R.string.fgs_content_title, count)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_view,
                getString(R.string.fgs_action_open),
                openIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.fgs_action_stop),
                stopIntent,
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.studiorizi.mterm.full.action.START"
        const val ACTION_STOP_ALL = "dev.studiorizi.mterm.full.action.STOP_ALL"

        private const val CHANNEL_ID = "terminal_sessions"
        private const val NOTIFICATION_ID = 1001

        /** Start from explicit user action only. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TerminalService::class.java).setAction(ACTION_START),
            )
        }

        fun stopAll(context: Context) {
            context.startService(
                Intent(context, TerminalService::class.java).setAction(ACTION_STOP_ALL),
            )
        }
    }
}
