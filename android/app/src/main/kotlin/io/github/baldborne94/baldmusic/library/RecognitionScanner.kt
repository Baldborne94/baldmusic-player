package io.github.baldborne94.baldmusic.library

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.baldborne94.baldmusic.core.acoustid.AcoustIdClient
import io.github.baldborne94.baldmusic.core.acoustid.Chromaprint
import io.github.baldborne94.baldmusic.core.acoustid.Lookup
import io.github.baldborne94.baldmusic.core.acoustid.Recognition
import io.github.baldborne94.baldmusic.core.acoustid.RecognitionIndex
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Riconosce i brani dal suono: decodifica i primi due minuti di ogni file ([AudioDecoder]),
 * ne calcola l'impronta ([Chromaprint]) e chiede ad AcoustID di che registrazione si tratta
 * ([Recognition] sceglie il disco). Il risultato va in `riconoscimenti.json`; i file non si
 * toccano mai, e i tag proposti valgono solo quando l'utente li applica.
 */
class RecognitionScanner(private val context: Context, private val http: OkHttpClient) {

    private val file = File(context.filesDir, "riconoscimenti.json")

    val index: RecognitionIndex by lazy { RecognitionIndex.fromJson(runCatching { file.readText() }.getOrNull()) }

    /** Com'è andato un giro: quanti file fatti e, se si è fermato per un problema, quale. */
    data class Run(val done: Int, val problem: String? = null, val badKey: Boolean = false)

    /**
     * Riconosce i file di [files] che mancano, uno alla volta, finché [stop] non dice di
     * fermarsi. Un problema di rete ferma il giro (si riprende dopo); una chiave sbagliata
     * pure, e lo dice.
     */
    suspend fun recognize(
        files: List<Pair<FileStamp, LocalTrack>>,
        apiKey: String,
        uriOf: (Long) -> Uri,
        folderOf: (Long) -> String,
        stop: () -> Boolean,
    ): Run {
        val client = AcoustIdClient(http, apiKey)
        val pending = index.pending(files.map { it.first }).toSet()
        var done = 0
        var problem: String? = null
        var badKey = false
        var lastCall = 0L
        for ((stamp, track) in files) {
            if (stamp !in pending) continue
            if (stop()) break
            val print = runCatching { fingerprint(uriOf(stamp.id), stop) }.onFailure { Log.w(TAG, "Impronta non calcolata: ${stamp.id}", it) }.getOrNull()
            if (stop()) break
            if (print == null) {
                // File che non si sa decodificare (o troppo corto): non si riprova a ogni giro.
                index.put(stamp, null)
            } else {
                // AcoustID: al massimo tre richieste al secondo.
                val wait = 340 - (System.currentTimeMillis() - lastCall)
                if (wait > 0) delay(wait)
                lastCall = System.currentTimeMillis()
                val lookup = try {
                    // La durata è quella di tutto il brano, come la manda fpcalc (l'impronta è dei primi due minuti).
                    client.lookup(print.first, ((track.durationMs / 1000).toInt()).coerceAtLeast(print.second))
                } catch (e: IOException) {
                    problem = "Niente connessione con AcoustID: si riprova più tardi."
                    break
                }
                when (lookup) {
                    is Lookup.Found -> index.put(stamp, Recognition.propose(lookup.results, track.durationMs, track.title))
                    is Lookup.Failed -> {
                        if (lookup.badKey) {
                            badKey = true
                            problem = "La chiave di AcoustID non vale: controllala."
                            break
                        }
                        // Impronta rifiutata o errore su questo file: lo si salta.
                        index.put(stamp, null)
                    }
                }
            }
            done++
            if (done % 10 == 0) save()
        }
        if (done > 0) {
            index.harmonize(folderOf)
            save()
        }
        return Run(done, problem, badKey)
    }

    /** Impronta compressa e durata in secondi dei primi [Chromaprint.MAX_SECONDS] secondi; null se il file non si legge. */
    private fun fingerprint(uri: Uri, stop: () -> Boolean): Pair<String, Int>? {
        var printer: Chromaprint? = null
        var consumed = 0L
        var rate = 0
        var buffer = ShortArray(0)
        val outcome = AudioDecoder.decode(context, uri, stop) { sampleRate, channels ->
            rate = sampleRate
            val p = Chromaprint(sampleRate, channels).also { printer = it }
            val limit = sampleRate.toLong() * Chromaprint.MAX_SECONDS
            object : PcmSink {
                override fun shorts(data: ShortArray, frames: Int): Boolean {
                    val take = minOf(frames.toLong(), limit - consumed).toInt()
                    if (take > 0) p.consume(data, 0, take * channels)
                    consumed += take
                    return consumed < limit
                }

                override fun floats(data: FloatArray, frames: Int): Boolean {
                    val take = minOf(frames.toLong(), limit - consumed).toInt()
                    val n = take * channels
                    if (buffer.size < n) buffer = ShortArray(n)
                    for (i in 0 until n) buffer[i] = (data[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    if (take > 0) p.consume(buffer, 0, n)
                    consumed += take
                    return consumed < limit
                }
            }
        }
        val p = printer ?: return null
        if (outcome != AudioDecoder.Outcome.DONE || rate <= 0) return null
        val seconds = (consumed / rate).toInt()
        // Meno di qualche secondo: l'impronta non dice niente.
        if (seconds < MIN_SECONDS) return null
        val raw = p.finish()
        if (raw.isEmpty()) return null
        return Chromaprint.encode(raw) to seconds
    }

    /** Toglie dall'indice i file che non ci sono più. */
    fun retain(ids: Set<Long>) {
        if (index.retain(ids)) save()
    }

    fun save() {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(index.toJson())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Indice dei riconoscimenti non salvato", it) }
    }

    private companion object {
        const val TAG = "RecognitionScanner"
        const val MIN_SECONDS = 10
    }
}
