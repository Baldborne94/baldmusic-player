package io.github.baldborne94.baldmusic.core.podcast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Un podcast seguito: l'indirizzo del feed (per Patreon contiene la chiave personale). */
@Serializable
data class Subscription(val id: String, val url: String, val addedAt: Long = 0)

/** Dove eri arrivato in una puntata. */
@Serializable
data class EpisodeProgress(val positionMs: Long, val durationMs: Long? = null, val finished: Boolean = false, val at: Long = 0)

/**
 * I podcast che segui e dove eri arrivato in ogni puntata. Regole pure: l'app salva solo il
 * JSON (in un file dell'app, mai nel backup in Download: gli indirizzi privati valgono come
 * una password).
 */
@Serializable
data class PodcastLibrary(
    val feeds: List<Subscription> = emptyList(),
    /** Per puntata: "<podcast>~<puntata>", come nell'ID del brano. */
    val progress: Map<String, EpisodeProgress> = emptyMap(),
    /** Puntate da tenere sul telefono perché chieste a mano («Scarica»), finché non le ascolti. */
    val downloads: Set<String> = emptySet(),
    /** Quante delle ultime puntate non ascoltate di ogni podcast tenere sul telefono da sole (0 = nessuna). */
    val autoDownload: Int = DEFAULT_AUTO_DOWNLOAD,
) {
    fun follow(url: String, now: Long): PodcastLibrary {
        val id = PodcastFeed.idOf(url)
        if (feeds.any { it.id == id }) return this
        return copy(feeds = feeds + Subscription(id, url, now))
    }

    /** Smetti di seguire un podcast: anche i punti di ripresa delle sue puntate se ne vanno. */
    fun unfollow(id: String) = copy(
        feeds = feeds.filterNot { it.id == id },
        progress = progress.filterKeys { !it.startsWith("$id~") },
        downloads = downloads.filterTo(HashSet()) { !it.startsWith("$id~") },
    )

    /** «Scarica» o «Togli dal telefono» su una puntata. */
    fun wantDownload(key: String, on: Boolean) = copy(downloads = if (on) downloads + key else downloads - key)

    fun withAutoDownload(count: Int) = copy(autoDownload = count.coerceIn(0, MAX_AUTO_DOWNLOAD))

    /**
     * «Segna come ascoltata» (va tra le ascoltate, riparte dall'inizio, esce da «Da ascoltare»
     * e dal telefono) o «Segna come da ascoltare» (come nuova).
     */
    fun mark(key: String, finished: Boolean, now: Long): PodcastLibrary =
        if (finished) copy(progress = progress + (key to EpisodeProgress(0, progress[key]?.durationMs, finished = true, at = now)), downloads = downloads - key)
        else copy(progress = progress - key)

    /**
     * Il punto di una puntata mentre la ascolti. Finita quando manca meno di un minuto (o è al
     * 98%): la prossima volta riparte dall'inizio e nell'elenco è «Ascoltata».
     */
    fun record(key: String, positionMs: Long, durationMs: Long?, now: Long): PodcastLibrary {
        if (positionMs < 0) return this
        val length = durationMs?.takeIf { it > 0 } ?: progress[key]?.durationMs
        val finished = length != null && (positionMs >= length - END_MS || positionMs >= length * 0.98)
        // Se era già finita e la riascolti dall'inizio, finita resta finché non la superi di nuovo.
        val wasFinished = progress[key]?.finished == true && positionMs < START_MS
        val entry = EpisodeProgress(positionMs, length, finished || wasFinished, now)
        val kept = (progress + (key to entry)).let { all ->
            if (all.size <= MAX_PROGRESS) all else all.entries.sortedByDescending { it.value.at }.take(MAX_PROGRESS).associate { it.key to it.value }
        }
        // Ascoltata: non serve più tenerla sul telefono, anche se l'avevi scaricata a mano.
        return copy(progress = kept, downloads = if (entry.finished) downloads - key else downloads)
    }

    /** Da dove ripartire: il punto salvato, qualche secondo prima; dall'inizio se finita o appena cominciata. */
    fun resumeAt(key: String): Long {
        val p = progress[key] ?: return 0
        if (p.finished || p.positionMs < START_MS) return 0
        return (p.positionMs - REWIND_MS).coerceAtLeast(0)
    }

    companion object {
        /** Meno di così dalla fine: puntata finita. */
        const val END_MS = 60_000L
        /** Prima di così non vale la pena riprendere: si riparte dall'inizio. */
        const val START_MS = 15_000L
        /** Si riprende un po' prima di dove ci si era fermati, per ritrovare il filo. */
        const val REWIND_MS = 5_000L
        const val MAX_PROGRESS = 2_000
        const val DEFAULT_AUTO_DOWNLOAD = 3
        const val MAX_AUTO_DOWNLOAD = 10

        /** Le scelte per «Scarica da sole le ultime puntate». */
        val AUTO_DOWNLOAD_CHOICES = listOf(0, 1, 3, 5)

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): PodcastLibrary =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: PodcastLibrary()

        fun encode(lib: PodcastLibrary): String = json.encodeToString(serializer(), lib)
    }
}
