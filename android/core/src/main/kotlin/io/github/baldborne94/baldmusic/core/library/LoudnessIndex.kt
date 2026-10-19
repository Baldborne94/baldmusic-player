package io.github.baldborne94.baldmusic.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.log10
import kotlin.math.pow

/**
 * Il volume misurato dall'app per i file senza ReplayGain nei tag (vedi [LoudnessMeter]),
 * per non rimisurarli: un file si rimisura solo se è nuovo o cambiato. Anche un file
 * che non si è potuto misurare ha la sua voce (vuota), così non lo si riprova sempre.
 */
class LoudnessIndex private constructor(private val entries: MutableMap<Long, Entry>) {

    @Serializable
    data class Entry(val modified: Long, val size: Long, val lufs: Float? = null, val peak: Float? = null)

    @Serializable
    private data class Stored(val version: Int, val entries: Map<Long, Entry> = emptyMap())

    constructor() : this(HashMap())

    private fun entry(stamp: FileStamp) = entries[stamp.id]?.takeIf { it.modified == stamp.modified && it.size == stamp.size }

    /** La misura del file, se è già stata fatta e il file non è cambiato. */
    @Synchronized
    fun measure(stamp: FileStamp): Measure? = entry(stamp)?.let { e -> e.lufs?.let { Measure(it, e.peak ?: 1f) } }

    /** I file ancora da misurare tra [files]. */
    @Synchronized
    fun pending(files: List<FileStamp>): List<FileStamp> = files.filter { entry(it) == null }

    @Synchronized
    fun put(stamp: FileStamp, measure: Measure?) {
        entries[stamp.id] = Entry(stamp.modified, stamp.size, measure?.lufs, measure?.peak)
    }

    /** Toglie i file che non ci sono più; true se ne ha tolto qualcuno. */
    @Synchronized
    fun retain(ids: Set<Long>): Boolean = entries.keys.retainAll(ids)

    @Synchronized
    fun toJson(): String = json.encodeToString(Stored.serializer(), Stored(VERSION, HashMap(entries)))

    companion object {
        /** Da aumentare quando cambia come si misura: le misure si rifanno da capo. */
        // 2: i file mono si misurano come doppio mono (prima risultavano 3 dB più piani).
        const val VERSION = 2

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun fromJson(text: String?): LoudnessIndex {
            val stored = text?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            return if (stored == null || stored.version != VERSION) LoudnessIndex() else LoudnessIndex(HashMap(stored.entries))
        }

        /**
         * Il ReplayGain misurato ai brani che nei tag non l'hanno. Per l'album si mettono
         * insieme i brani misurati dello stesso disco (la loudness media pesata sulla
         * durata, il picco più alto): in modalità Album dentro un disco restano le
         * differenze volute.
         */
        fun applyTo(tracks: List<LocalTrack>, measure: (LocalTrack) -> Measure?): List<LocalTrack> {
            val measured = HashMap<Long, Measure>()
            tracks.forEach { t -> if (t.replayGain == null) measure(t)?.let { measured[t.id] = it } }
            if (measured.isEmpty()) return tracks
            val albums = tracks.filter { it.id in measured && it.album != null }.groupBy { it.albumId }.filterValues { it.size > 1 }
            val albumGain = HashMap<Long, Pair<Float, Float>>()
            albums.values.forEach { list ->
                val weight = list.sumOf { it.durationMs.coerceAtLeast(1).toDouble() }
                val energy = list.sumOf { it.durationMs.coerceAtLeast(1) * 10.0.pow(measured.getValue(it.id).lufs / 10.0) } / weight
                val lufs = (10 * log10(energy)).toFloat()
                val peak = list.maxOf { measured.getValue(it.id).peak }
                list.forEach { albumGain[it.id] = (LoudnessMeter.REFERENCE_LUFS - lufs) to peak }
            }
            return tracks.map { t ->
                val m = measured[t.id] ?: return@map t
                val album = albumGain[t.id]
                t.copy(replayGain = LoudnessMeter.replayGain(m).copy(albumGain = album?.first, albumPeak = album?.second))
            }
        }
    }
}
