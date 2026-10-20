package io.github.baldborne94.baldmusic.library

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.LoudnessIndex
import io.github.baldborne94.baldmusic.core.library.LoudnessMeter
import io.github.baldborne94.baldmusic.core.library.Measure
import java.io.File

/**
 * Misura il volume dei file senza ReplayGain nei tag: decodifica il brano con i codec
 * del telefono (gli stessi che lo suonano) e passa il PCM a [LoudnessMeter]. Una volta
 * per file, poi solo se cambia. È lavoro pesante: lo fa [LoudnessWorker], quando il
 * telefono è in carica (o subito, se lo chiedi dalle Impostazioni).
 */
class LoudnessScanner(private val context: Context) {

    private val file = File(context.filesDir, "volume.json")

    val index: LoudnessIndex by lazy { LoudnessIndex.fromJson(runCatching { file.readText() }.getOrNull()) }

    /**
     * Misura i file di [files] che mancano, uno alla volta, finché [stop] non dice di
     * fermarsi. Restituisce quanti ne ha misurati.
     */
    fun measure(files: List<FileStamp>, uriOf: (Long) -> Uri, stop: () -> Boolean): Int {
        val pending = index.pending(files)
        var done = 0
        for (stamp in pending) {
            if (stop()) break
            val measured = runCatching { decode(uriOf(stamp.id), stop) }.onFailure { Log.w(TAG, "Volume non misurato: ${stamp.id}", it) }.getOrNull()
            // Fermato a metà (carica staccata, tempo finito): il file resta da misurare, non "non misurabile".
            if (stop()) break
            index.put(stamp, measured)
            done++
            // Si salva ogni tanto: se il lavoro si ferma a metà non si riparte da zero.
            if (done % 10 == 0) save()
        }
        if (done > 0) save()
        return done
    }

    /** Toglie dall'indice i file che non ci sono più. */
    fun retain(ids: Set<Long>) {
        if (index.retain(ids)) save()
    }

    private fun decode(uri: Uri, stop: () -> Boolean): Measure? {
        var meter: LoudnessMeter? = null
        val outcome = AudioDecoder.decode(context, uri, stop) { rate, channels ->
            val m = LoudnessMeter(rate, channels).also { meter = it }
            object : PcmSink {
                override fun shorts(data: ShortArray, frames: Int): Boolean { m.addShorts(data, frames); return true }
                override fun floats(data: FloatArray, frames: Int): Boolean { m.addFloats(data, frames); return true }
            }
        }
        // Formato che non si sa leggere: non si misura, il brano suona com'è.
        return if (outcome == AudioDecoder.Outcome.DONE) meter?.result() else null
    }

    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(index.toJson())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Indice del volume non salvato", it) }
    }

    private companion object {
        const val TAG = "LoudnessScanner"
    }
}
