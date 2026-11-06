package ru.qmods.anixq.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store

/** Keeps the phone-remote channel open while AnixQ is in the background, so "Смотреть на ТВ" works any time. */
class RemoteService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(applicationContext)
        // startForeground first, always: stopping before it would crash the app on Android 8+.
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("remote", "Пульт с телефона", NotificationManager.IMPORTANCE_MIN))
            val n = Notification.Builder(this, "remote").setContentTitle("AnixQ ждёт команды с телефона")
                .setSmallIcon(R.mipmap.ic_launcher).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        }
        if (Store.githubToken == null) { stopForegroundCompat(); stopSelf(); return START_NOT_STICKY }
        Remote.start(applicationContext)
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true) }

    companion object {
        fun start(context: Context) {
            if (Store.githubToken == null) return
            val i = Intent(context, RemoteService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i) }
        }
    }
}

/** After a reboot the TV is reachable from the phone without opening the app first. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Store.init(context.applicationContext); RemoteService.start(context) }
    }
}
