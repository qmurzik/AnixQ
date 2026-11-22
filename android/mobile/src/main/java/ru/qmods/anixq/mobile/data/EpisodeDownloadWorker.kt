package ru.qmods.anixq.mobile.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

class EpisodeDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        Downloads.init(applicationContext)
        val path = inputData.getString("path") ?: return Result.failure()
        return Downloads.downloadStream(inputData.getInt("episode", 0), path, runAttemptCount) { item ->
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            val channel = "offline_episodes"
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(channel, "Загрузки серий", NotificationManager.IMPORTANCE_LOW))
            }
            val notification = NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(item.title.title)
                .setContentText("Скачивается серия ${item.episode.number}")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            setForeground(ForegroundInfo(0x41000000 or (id.hashCode() and 0x00ffffff), notification, type))
        }
    }
}
