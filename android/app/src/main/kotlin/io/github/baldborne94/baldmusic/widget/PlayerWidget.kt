package io.github.baldborne94.baldmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import io.github.baldborne94.baldmusic.R
import io.github.baldborne94.baldmusic.ui.MainActivity

/**
 * Il widget della schermata home: copertina, titolo e i tre comandi.
 * I tasti mandano "tasti multimediali" a Media3, che avvia il servizio se serve.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, last)
    }

    data class State(val title: String?, val artist: String?, val playing: Boolean, val art: Bitmap?)

    companion object {
        @Volatile private var last = State(null, null, false, null)

        fun update(context: Context, state: State) {
            last = state
            render(context, state)
        }

        private fun render(context: Context, state: State) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_player).apply {
                setTextViewText(R.id.widget_title, state.title ?: context.getString(R.string.app_name))
                setTextViewText(R.id.widget_artist, state.artist ?: context.getString(R.string.widget_idle))
                if (state.art != null) setImageViewBitmap(R.id.widget_art, state.art)
                else setImageViewResource(R.id.widget_art, R.drawable.ic_launcher_foreground)
                setImageViewResource(R.id.widget_play, if (state.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
                setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                setOnClickPendingIntent(R.id.widget_prev, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            manager.updateAppWidget(ids, views)
        }

        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Copertina piccola per il widget (i RemoteViews hanno un limite di memoria). */
        fun loadArt(context: Context, uri: Uri?): Bitmap? {
            uri ?: return null
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 256) sample *= 2
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
    }
}
