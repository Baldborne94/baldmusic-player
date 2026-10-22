package io.github.baldborne94.baldmusic.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.baldborne94.baldmusic.R
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.podcast.Episode
import io.github.baldborne94.baldmusic.core.podcast.Podcast
import io.github.baldborne94.baldmusic.core.podcast.PodcastFeed
import io.github.baldborne94.baldmusic.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Guarda ogni 2 ore, con la rete, se i podcast che segui hanno puntate nuove: così arrivano
 * nell'app (e in Android Auto) anche se non la apri, e una notifica te lo dice.
 */
class PodcastWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val podcasts = applicationContext.app.podcasts
        if (!podcasts.hasAny) return Result.success()
        podcasts.refresh(maxAgeMs = RECHECK_MS)
        return Result.success()
    }

    companion object {
        private const val NAME = "podcast-puntate-nuove"
        private const val CHANNEL = "podcast"
        /** Un feed letto da meno di così (aprendo l'app) non si rilegge. */
        private const val RECHECK_MS = 30 * 60_000L

        /** Ogni 2 ore con la rete, finché segui almeno un podcast. */
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!context.app.podcasts.hasAny) {
                work.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<PodcastWorker>(2, TimeUnit.HOURS).setConstraints(constraints).build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** La notifica delle puntate nuove di [show]; toccandola si apre l'app. Niente se le notifiche sono spente. */
        fun announce(context: Context, show: Podcast, episodes: List<Episode>) {
            val manager = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Nuove puntate dei podcast", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val (title, text) = PodcastFeed.notice(show, episodes)
            val open = PendingIntent.getActivity(
                context, show.id.hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            // Una notifica per podcast: una puntata nuova sostituisce quella di prima.
            runCatching { manager.notify("podcast-${show.id}", 1, notification) }
        }
    }
}
