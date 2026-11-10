package ru.qmods.anixq.mobile

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * "Звук в фоне": while an episode plays with this on, the player lives in a media session —
 * the system shows the lock-screen / notification controls and keeps the sound going with the screen off.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = current ?: run { stopSelf(); return }
        session = MediaSession.Builder(this, p).build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away: stop the sound too.
        session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release(); session = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: Player? = null

        /** Called while the player screen is on screen (a service can't be started from the background). */
        fun attach(context: Context, player: Player) {
            current = player
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        }

        fun detach(context: Context) {
            current = null
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
