package io.github.baldborne94.baldmusic.core.library

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * I brani da far sentire dopo uno scelto, con il suo mood e il suo stile (la «radio» del brano,
 * anche per «Continua ad ascoltare»). Sul telefono l'indizio migliore sono le tue raccolte: le
 * hai fatte tu per stile («Heavy Workout», «Atmosfera», «Anime e cartoni»). Poi gli artisti che
 * stanno nelle stesse raccolte, il genere scritto nel file, l'energia (dal volume del brano,
 * ReplayGain o misurato) e il decennio. Niente rete, niente servizi esterni.
 */
object MoodRadio {

    /** Quello che serve di un brano. [gainDb] è il guadagno di ReplayGain: più basso = brano più forte. */
    data class Song(
        val id: Long,
        val artist: String?,
        val raccolta: String?,
        val genres: Set<String> = emptySet(),
        val decade: Int? = null,
        val gainDb: Float? = null,
    )

    /** Al massimo così tanti brani nella radio (il brano scelto compreso). */
    const val SIZE = 50
    /** Dello stesso artista del brano scelto, al massimo: uno ogni tre. */
    private const val SAME_ARTIST = 15

    /**
     * La radio di [seed]: lui per primo, poi brani simili (due) e dello stesso artista (uno),
     * a turno, senza due brani di fila dello stesso artista quando si può evitare.
     */
    fun pick(seed: Song, all: List<Song>, random: Random = Random.Default): List<Long> {
        val affinity = Affinity(all)
        val sameArtist = all.filter { it.id != seed.id && seed.artist != null && it.artist == seed.artist }
            .map { it to score(seed, it, affinity) + random.nextDouble() }
            .sortedByDescending { it.second }.take(SAME_ARTIST).map { it.first }
        val others = all.filter { it.id != seed.id && (seed.artist == null || it.artist != seed.artist) }
            .map { it to score(seed, it, affinity) }
        // I simili davvero; se sono pochi (libreria piccola, niente raccolte) si completa coi più vicini.
        val similar = others.filter { it.second > 0 }.map { it.first to it.second + random.nextDouble() * 1.5 }
            .sortedByDescending { it.second }.map { it.first }
        val pool = if (similar.size >= 10) similar
        else similar + others.filter { it.second <= 0 }.map { it.first to it.second + random.nextDouble() }
            .sortedByDescending { it.second }.map { it.first }.take(30 - similar.size)

        val out = mutableListOf(seed)
        val a = ArrayDeque(sameArtist)
        val r = ArrayDeque(pool)
        while ((a.isNotEmpty() || r.isNotEmpty()) && out.size < SIZE) {
            repeat(2) { takeVaried(r, out.last().artist)?.let(out::add) }
            a.removeFirstOrNull()?.let(out::add)
        }
        return out.distinctBy { it.id }.take(SIZE).map { it.id }
    }

    /** Il prossimo dei simili, saltando (tra i primi tre) quelli dello stesso artista dell'ultimo. */
    private fun takeVaried(queue: ArrayDeque<Song>, lastArtist: String?): Song? {
        if (queue.isEmpty()) return null
        val i = (0 until min(3, queue.size)).firstOrNull { queue[it].artist == null || queue[it].artist != lastArtist } ?: 0
        return queue.removeAt(i)
    }

    /** Quanto [c] somiglia a [seed]: sopra zero è simile. */
    fun score(seed: Song, c: Song, affinity: Affinity): Double {
        var s = 0.0
        // La stessa raccolta: il segno più forte, l'hai scelto tu.
        if (seed.raccolta != null && c.raccolta == seed.raccolta) s += 4
        else if (seed.raccolta != null && c.raccolta != null) s += 3 * affinity.between(seed.raccolta, c.raccolta)
        // Un artista che metti nelle stesse raccolte dell'artista del brano.
        if (affinity.related(seed.artist, c.artist)) s += 2
        if (seed.genres.isNotEmpty() && c.genres.isNotEmpty()) s += if (seed.genres.any { it in c.genres }) 3 else -2
        if (seed.decade != null && c.decade == seed.decade) s += 1
        // Energia: un brano molto più forte o più tranquillo rompe il mood.
        if (seed.gainDb != null && c.gainDb != null) {
            val d = abs(seed.gainDb - c.gainDb)
            if (d > 8) s -= 3 else if (d > 5) s -= 1.5
        }
        return s
    }

    /** Raccolte e artisti che vanno insieme, da come hai ordinato la musica. */
    class Affinity(all: List<Song>) {
        private val artistsOf = HashMap<String, MutableSet<String>>()
        private val raccolteOf = HashMap<String, MutableSet<String>>()
        private val cache = HashMap<Pair<String, String>, Double>()

        init {
            all.forEach { s ->
                val r = s.raccolta ?: return@forEach
                val a = s.artist ?: return@forEach
                artistsOf.getOrPut(r) { HashSet() } += a
                raccolteOf.getOrPut(a) { HashSet() } += r
            }
        }

        /** Da 0 a 1: quanti artisti hanno in comune due raccolte, rispetto alla più piccola. */
        fun between(a: String, b: String): Double = cache.getOrPut(if (a < b) a to b else b to a) {
            val x = artistsOf[a].orEmpty()
            val y = artistsOf[b].orEmpty()
            if (x.isEmpty() || y.isEmpty()) 0.0 else x.count { it in y }.toDouble() / min(x.size, y.size)
        }

        /** I due artisti stanno insieme in almeno una raccolta. */
        fun related(a: String?, b: String?): Boolean {
            if (a == null || b == null || a == b) return false
            val x = raccolteOf[a] ?: return false
            return raccolteOf[b]?.any { it in x } == true
        }
    }

    /** Il genere scritto nel file, in parole confrontabili ("Rock; Alternative Metal" → rock, alternative metal). */
    fun genres(text: String?): Set<String> =
        text?.split(';', '/', ',', '|')?.map { ArtistNames.key(it) }?.filter { it.isNotEmpty() && it != "other" && it != "unknown" }?.toSet().orEmpty()
}
