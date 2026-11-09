package ru.qmods.anixq.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.RemoteViews
import coil.Coil
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.qmods.anixq.tv.data.Store

/** Home-screen widget: the episode you stopped at (on any device) — one tap continues it. */
class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                Store.devicePrefix = "and"; Store.deviceName = "Android"
                Store.init(context.applicationContext)
                render(context, manager, ids)
            } finally { pending.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraw all placed widgets (after watching, on sync). */
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { runCatching { render(context, m, ids) } }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val e = Store.continueList.firstOrNull()
            val v = RemoteViews(context.packageName, R.layout.widget_continue)
            val tap = PendingIntent.getActivity(context, 7,
                Intent(Intent.ACTION_VIEW, Uri.parse(if (e != null) "anixq://continue" else "anixq://home"), context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.root, tap)
            v.setOnClickPendingIntent(R.id.play, tap)
            if (e == null) {
                v.setTextViewText(R.id.title, "Что посмотрим?")
                v.setTextViewText(R.id.sub, "Начните серию — она появится здесь")
                v.setProgressBar(R.id.progress, 1000, 0, false)
            } else {
                val done = e.progress > 0.92
                v.setTextViewText(R.id.label, if (done) "Следующая серия" else "Продолжить просмотр")
                v.setTextViewText(R.id.title, e.title.title)
                val left = ((e.dur - e.pos) / 60).toInt().coerceAtLeast(1)
                v.setTextViewText(R.id.sub, if (done) "После серии ${e.ep}" else "Серия ${e.ep} · осталось $left мин")
                v.setProgressBar(R.id.progress, 1000, if (done) 1000 else (e.progress * 1000).toInt(), false)
                cover(context, e.title.thumb)?.let { v.setImageViewBitmap(R.id.cover, it) }
            }
            manager.updateAppWidget(ids, v)
        }

        private suspend fun cover(context: Context, url: String?): Bitmap? {
            if (url == null) return null
            val r = Coil.imageLoader(context).execute(ImageRequest.Builder(context).data(url).size(Size(192, 276)).allowHardware(false).build())
            return (r.drawable as? BitmapDrawable)?.bitmap
        }
    }
}
