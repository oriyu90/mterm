package dev.studiorizi.mterm.modern.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import dev.studiorizi.mterm.core.process_supervisor.ProcessSupervisor
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.modern.MTermApp
import dev.studiorizi.mterm.modern.R

/**
 * Owns session lifetime across Activity recreation (plan section 10.1).
 * Started only from an explicit user action (New Session button).
 * Returns to background and stops itself once no sessions remain.
 */
class TerminalService : Service() {

    private val binder = LocalBinder()

    private lateinit var manager: SessionManager
    private lateinit var supervisor: ProcessSupervisor
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        val app = application as MTermApp
        manager = app.sessionManager
        supervisor = app.supervisor
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun manager(): SessionManager = manager

    fun supervisor(): ProcessSupervisor = supervisor

    /** Refresh the foreground state; stop when the last session exits. */
    fun refreshLifecycle() {
        if (manager.sessions.value.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            notificationManager.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_sessions_running),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val count = manager.sessions.value.size
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_sessions_running))
            .setContentText(count.toString())
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    inner class LocalBinder : Binder() {
        fun service(): TerminalService = this@TerminalService
    }

    companion object {
        const val CHANNEL_ID = "terminal_sessions"
        const val NOTIF_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, TerminalService::class.java)
            context.startForegroundService(intent)
        }
    }
}
