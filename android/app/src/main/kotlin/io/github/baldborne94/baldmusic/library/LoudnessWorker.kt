package io.github.baldborne94.baldmusic.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.baldborne94.baldmusic.app

/**
 * Misura in sottofondo il volume dei brani del telefono senza ReplayGain. Di solito
 * parte solo quando il telefono è in carica e la batteria non è scarica; se Android
 * lo ferma (fine della carica, tempo scaduto) riprende dopo dal punto in cui era.
 */
class LoudnessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val (done, left) = app.local.measureLoudness { isStopped }
        // Volumi nuovi: la libreria si rilegge, nell'app e in Android Auto.
        if (done > 0) app.settings.touch()
        return if (left > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "misura-volume"

        /** Mette in coda la misura: in carica, oppure [now] subito (dalle Impostazioni). */
        fun schedule(context: Context, now: Boolean = false) {
            val constraints = if (now) Constraints.NONE else Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
            val request = OneTimeWorkRequestBuilder<LoudnessWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
