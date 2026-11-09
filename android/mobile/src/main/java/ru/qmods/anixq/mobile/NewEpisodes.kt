package ru.qmods.anixq.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Bookmark
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title
import java.util.concurrent.TimeUnit

/**
 * "Вышла новая серия": every few hours, in the background, the titles you're watching are checked;
 * a new episode gives a notification that opens the title. Also refreshes the "Продолжить" widget.
 */
class NewEpisodesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Store.devicePrefix = "and"; Store.deviceName = "Android"
        Store.init(ctx)
        ContinueWidget.refresh(ctx)
        NewEpisodes.check(ctx)
        return Result.success()
    }
}

object NewEpisodes {
    private const val CHANNEL = "new-episodes"

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<NewEpisodesWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("new-episodes", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Titles worth checking: "Смотрю" plus anything watched in the last month. */
    private fun candidates(): List<Title> {
        val now = System.currentTimeMillis() / 1000.0
        val recent = Store.history.filter { now - it.at < 30 * 86_400 }.map { it.title }
        return (Store.titles(Bookmark.WATCHING) + recent).distinctBy { it.id }.take(40)
    }

    suspend fun check(context: Context) {
        val prefs = context.getSharedPreferences("episodes-known", Context.MODE_PRIVATE)
        for (t in candidates()) {
            val eps = runCatching { Api.episodes(t.slug) }.getOrNull() ?: continue
            val key = "t${t.id}"
            val known = prefs.getInt(key, -1)
            prefs.edit().putInt(key, eps.size).apply()
            // First look at a title only records the count (no "new" spam for old episodes).
            if (known < 0 || eps.size <= known) continue
            val last = eps.last()
            notify(context, t, if (eps.size - known == 1) "Вышла серия ${last.number}" else "Вышли серии ${eps[known].number}–${last.number}")
        }
    }

    private fun notify(context: Context, t: Title, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Новые серии", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Когда выходит серия того, что вы смотрите"
            })
        }
        val open = PendingIntent.getActivity(context, t.id,
            Intent(Intent.ACTION_VIEW, Uri.parse("anixq://title/${t.slug}"), context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t.title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(t.id, n) }
    }
}
