package com.michele.teslawatch.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.michele.teslawatch.R

/**
 * Keeps the app in the foreground while a command and its confirmation are in progress.
 *
 * Wear OS destroys the network sockets of background apps a few seconds after they leave the screen
 * (seen on a Galaxy Watch8: "Destroyed live tcp sockets" 10 s after a tile tap). A command to a
 * sleeping car can take much longer, so without this its answer, and the lock confirmation, were lost.
 */
object KeepAlive {
    private var busy = 0
    @Volatile internal var onIdle: (() -> Unit)? = null

    val isBusy: Boolean @Synchronized get() = busy > 0

    fun begin(context: Context) {
        val first = synchronized(this) { busy++ == 0 }
        if (first) {
            try {
                context.startForegroundService(Intent(context, CommandService::class.java))
            } catch (_: Exception) {
                // Not allowed right now (app fully in background): the command still runs, it may just lose its answer
            }
        }
    }

    fun end() {
        val idle = synchronized(this) {
            if (busy > 0) busy--
            busy == 0
        }
        if (idle) onIdle?.invoke()
    }
}

/** Short foreground service (at most 3 minutes) with a quiet "sending" notification. */
class CommandService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.command_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(getString(R.string.command_running))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Stop as soon as nothing is running any more (possibly already now). stopSelf(startId) is
        // ignored if a newer start arrived meanwhile, so a fresh command is never cut short.
        KeepAlive.onIdle = { stopSelf(startId) }
        if (!KeepAlive.isBusy) stopSelf(startId)
        return START_NOT_STICKY
    }

    /** The system's limit for short services: stop gracefully. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        KeepAlive.onIdle = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "commands"
        const val NOTIFICATION_ID = 1
    }
}
