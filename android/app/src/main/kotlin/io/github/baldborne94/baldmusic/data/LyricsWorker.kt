package io.github.baldborne94.baldmusic.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.lyrics.LyricsPrefetch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Scarica in anticipo i testi, per averli senza rete (in macchina, in metro): una volta al
 * giorno, in carica e col Wi‑Fi, nell'ordine di [LyricsPrefetch]. I testi già salvati si
 * saltano; se Android lo ferma o cade la rete, riprende la volta dopo. Niente se i testi
 * da internet sono spenti nelle Impostazioni.
 */
class LyricsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (!app.settings.lyricsOnline) return Result.success()
        val phone = app.local.allTracks()
        // I brani del telefono con i tag di adesso (anche quelli riconosciuti), come li cerca il lettore.
        val current = phone.associateBy { LocalCatalog.ref(it).id }
        for (ref in LyricsPrefetch.order(app.userData.value, phone.map(LocalCatalog::ref))) {
            if (isStopped) return Result.retry()
            val local = current[ref.id]
            // Un brano del telefono che non c'è più non si cerca.
            if (local == null && ref.id.startsWith("${LocalCatalog.KEY}/")) continue
            val artist = local?.artist ?: ref.artist
            val title = local?.title ?: ref.title
            if (app.lyrics.known(artist, title)) continue
            if (!app.lyrics.hasInternet()) return Result.retry()
            runCatching { app.lyrics.get(artist, title, local?.album ?: ref.album, local?.durationMs ?: ref.durationMs, online = true) }
            // LRCLIB è gratuito: con calma.
            delay(PAUSE_MS)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "testi-in-anticipo"
        private const val NAME_NOW = "testi-in-anticipo-adesso"
        private const val PAUSE_MS = 400L

        /** Una volta al giorno, in carica e col Wi‑Fi (non a consumo). */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<LyricsWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** "Scarica adesso" dalle Impostazioni: subito, con qualunque rete. */
        fun now(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<LyricsWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME_NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** Se sta scaricando adesso, per le Impostazioni. */
        fun running(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME_NOW).map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
    }
}
