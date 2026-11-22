package com.fastshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CH_SERVICE = "fs_service"
    const val CH_MSG = "fs_msg"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "FastShare background", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, "Messages aur files", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun show(ctx: Context, title: String, body: String) {
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100_000).toInt(), n)
        } catch (_: SecurityException) {
            // Notification permission not granted; nothing to do.
        }
    }
}

/** Keeps the receiver alive in the background (foreground service with a small ongoing notification). */
class FastShareService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val n: Notification = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FastShare chal raha hai")
            .setContentText("Nearby devices se file aur message le sakte ho")
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Hub.start(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
