package ru.qmods.anixq.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.qmods.anixq.tv.data.Nearby
import ru.qmods.anixq.tv.data.Remote
import ru.qmods.anixq.tv.data.Store

/** The phone side of "phone next to the TV": Bluetooth beacon → offer to continue there; calls → pause the TV. */
object NearTv {
    private const val CHANNEL = "near_tv"
    private const val NOTE_ID = 7301
    const val ACTION_SEEN = "ru.qmods.anixq.mobile.NEAR_TV_SEEN"
    const val ACTION_PLAY = "ru.qmods.anixq.mobile.NEAR_TV_PLAY"

    private fun scanIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 1, Intent(context, NearTvReceiver::class.java).setAction(ACTION_SEEN), flags)
    }

    /** Start or stop the background watch to match the setting (and the current pairing). */
    fun sync(context: Context) {
        val ctx = context.applicationContext
        if (Store.nearbyTv && Store.remoteChannel.isNotEmpty()) Nearby.startScan(ctx, scanIntent(ctx)) else Nearby.stopScan(ctx, scanIntent(ctx))
    }

    /** The beacon of our TV was heard. */
    fun seen(context: Context) {
        if (!Store.nearbyTv) return
        val now = System.currentTimeMillis()
        if (now - Store.nearbyNotifiedAt < 45 * 60_000) return
        val e = Nearby.candidate() ?: return
        // Already on the TV — nothing to offer.
        Remote.tvState.value?.let { s -> if (s.optInt("episodeId") == e.episodeId && s.optString("state") != "idle") return }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Store.nearbyNotifiedAt = now

        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Досмотреть на ТВ", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Когда телефон рядом с приставкой, предложит продолжить серию на телевизоре"
            })
        val play = PendingIntent.getBroadcast(context, 2, Intent(context, NearTvReceiver::class.java).setAction(ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val min = (e.pos / 60).toInt()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Досмотреть на ТВ?")
            .setContentText("${e.title.title} · ${e.ep} серия, с ${min} мин")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(play)
            .addAction(android.R.drawable.ic_media_play, "Включить на ТВ", play)
            .setAutoCancel(true)
            .setTimeoutAfter(20 * 60_000)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTE_ID, n) }
    }

    /** Send the paused episode to the TV, from exactly where it stopped. */
    suspend fun playOnTv(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTE_ID)
        val e = Store.continueList.firstOrNull() ?: return
        Remote.isTv = false
        Remote.start(context.applicationContext)
        Remote.playOnTv(e.title, e.episodeId, e.teamId, e.pos)
        // The receiver's process may be cut soon: give the channel a moment to deliver.
        withTimeoutOrNull(8_000) { while (!Remote.online.value) delay(200) }
        delay(1_500)
    }

    // MARK: calls

    fun callState(context: Context, state: String?) {
        if (!Store.callPause || Store.remoteChannel.isEmpty()) return
        Remote.isTv = false
        Remote.start(context.applicationContext)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING, TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (Store.callPausedAt == 0L) {
                    // Only pause what is actually playing (if we know); "pause" on a paused TV does nothing anyway.
                    val s = Remote.tvState.value
                    if (s == null || s.optString("state") == "playing") { Remote.send("pause"); Store.callPausedAt = System.currentTimeMillis() }
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val at = Store.callPausedAt
                Store.callPausedAt = 0
                // Resume only what we paused, and not after an hour-long call.
                if (at > 0 && System.currentTimeMillis() - at < 60 * 60_000) Remote.send("resume")
            }
        }
    }
}

class NearTvReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context.applicationContext)
        when (intent.action) {
            NearTv.ACTION_SEEN -> {
                // A scan error comes with no results: nothing to do.
                if (intent.hasExtra("android.bluetooth.le.extra.ERROR_CODE")) return
                NearTv.seen(context)
            }
            NearTv.ACTION_PLAY -> {
                val done = goAsync()
                CoroutineScope(Dispatchers.IO).launch { try { NearTv.playOnTv(context) } finally { done.finish() } }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> NearTv.sync(context)
        }
    }
}

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        Store.init(context.applicationContext)
        if (!Store.callPause) return
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NearTv.callState(context, intent.getStringExtra(TelephonyManager.EXTRA_STATE))
                withTimeoutOrNull(6_000) { while (!Remote.online.value) delay(200) }
                delay(1_000)
            } finally { done.finish() }
        }
    }
}
