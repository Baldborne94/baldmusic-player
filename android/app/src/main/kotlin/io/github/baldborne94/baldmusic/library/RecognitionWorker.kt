package io.github.baldborne94.baldmusic.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.baldborne94.baldmusic.app
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Riconosce in sottofondo i brani mai passati da Picard (vedi [RecognitionScanner]). Di
 * solito solo in carica e con la rete; "Riconosci adesso" lo fa partire subito. Se Android
 * lo ferma riprende dopo dal punto in cui era; con la chiave sbagliata si ferma e basta.
 */
class RecognitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val run = app.local.recognize { isStopped }
        app.settings.recognitionProblem = run.problem
        // Le proposte nuove si guardano nella schermata: la libreria cambia solo quando le applichi.
        return when {
            run.badKey -> Result.failure()
            run.problem != null -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val NAME = "riconosci-brani"

        /** Mette in coda il riconoscimento: in carica, oppure [now] subito; sempre con la rete. */
        fun schedule(context: Context, now: Boolean = false) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).apply {
                if (!now) setRequiresCharging(true).setRequiresBatteryNotLow(true)
            }.build()
            val request = OneTimeWorkRequestBuilder<RecognitionWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        /** Se sta lavorando adesso, per la schermata. */
        fun running(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
    }
}
