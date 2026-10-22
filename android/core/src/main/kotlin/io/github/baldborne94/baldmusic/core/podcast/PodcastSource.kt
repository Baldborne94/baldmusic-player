package io.github.baldborne94.baldmusic.core.podcast

import io.github.baldborne94.baldmusic.core.library.ArtistNames
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.MusicSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Podcast»: i podcast che segui (anche quelli privati di Patreon), come una sorgente in più,
 * nell'app e in Android Auto. Un podcast è un contenitore, le puntate sono brani con il loro
 * indirizzo; si sceglie una puntata e suona quella, da dove eri rimasto.
 */
class PodcastSource(
    /** I podcast letti dai feed (dalla copia salvata: l'app li aggiorna da sola). */
    private val shows: suspend () -> List<Podcast>,
    private val library: () -> PodcastLibrary,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * I podcast per riprodurre una puntata: aggiornati se vecchi, perché nei feed privati
     * (Patreon) gli indirizzi dell'audio scadono.
     */
    private val forPlayback: suspend () -> List<Podcast> = shows,
) : MusicSource {

    override val key = KEY
    override val title = "Podcast"
    // Il punto di ripresa cambia mentre ascolti: niente cache nell'albero.
    override val cacheable get() = false

    override suspend fun sections(): List<LibraryNode> = shows().map(::showNode)

    /** Le puntate dalla più recente, divise per mese ([EpisodeLabels]). */
    override suspend fun children(id: MediaId): List<LibraryNode> {
        if (id.kind != SHOW) return emptyList()
        val show = shows().firstOrNull { it.id == id.id } ?: return emptyList()
        val progress = library().progress
        val tags = EpisodeLabels.tags(show.episodes)
        return show.episodes.map { e ->
            episodeNode(show, e, progress[keyOf(show, e)], tags).copy(group = EpisodeLabels.month(e.publishedAt, zone))
        }
    }

    override suspend fun node(id: MediaId): LibraryNode? =
        if (id.kind == SHOW) shows().firstOrNull { it.id == id.id }?.let(::showNode) else null

    override suspend fun track(id: MediaId): LibraryNode? {
        val (showId, episodeKey) = split(id.id ?: return null) ?: return null
        val show = forPlayback().firstOrNull { it.id == showId } ?: return null
        val episode = show.episodes.firstOrNull { it.key == episodeKey } ?: return null
        return episodeNode(show, episode, library().progress[keyOf(show, episode)], EpisodeLabels.tags(show.episodes))
    }

    override suspend fun search(query: String): List<LibraryNode> {
        val q = ArtistNames.key(query)
        if (q.isEmpty()) return emptyList()
        val progress = library().progress
        return shows().flatMap { show ->
            val showHit = if (ArtistNames.key(show.title).contains(q)) listOf(showNode(show)) else emptyList()
            val tags = EpisodeLabels.tags(show.episodes)
            showHit + show.episodes.filter { ArtistNames.key(it.title).contains(q) }.take(20)
                .map { episodeNode(show, it, progress[keyOf(show, it)], tags) }
        }
    }

    private fun showNode(show: Podcast) = LibraryNode(
        mediaId = MediaId(KEY, SHOW, show.id).toString(),
        title = show.title,
        kind = Kind.ALBUM,
        subtitle = show.author ?: episodes(show.episodes.size),
        artist = show.author,
        artwork = show.image?.let { "$KEY/${show.id}" },
    )

    private fun episodeNode(show: Podcast, e: Episode, p: EpisodeProgress?, tags: Set<String>): LibraryNode {
        val (title, tag) = EpisodeLabels.split(e.title, tags)
        return LibraryNode(
            // Senza contenitore: si ascolta la puntata scelta, non tutte quelle dopo.
            mediaId = MediaId.track(KEY, keyOf(show, e), null).toString(),
            title = title,
            kind = Kind.TRACK,
            subtitle = listOfNotNull(tag, subtitle(e, p).takeIf { it.isNotEmpty() }).joinToString(" · "),
            artist = show.title,
            album = show.title,
            artwork = show.image?.let { "$KEY/${show.id}" },
            durationMs = e.durationMs ?: p?.durationMs,
            streamUri = e.audioUrl,
            // "Vai all'album" porta al podcast; e niente dissolvenza tra puntate dello stesso podcast.
            albumId = MediaId(KEY, SHOW, show.id).toString(),
        )
    }

    /** "12 ott 2026 · 54 min · mancano 20 min" / "· ascoltata". */
    fun subtitle(e: Episode, p: EpisodeProgress?): String = listOfNotNull(
        e.publishedAt?.let { DATE.withZone(zone).format(Instant.ofEpochMilli(it)) },
        (e.durationMs ?: p?.durationMs)?.let(::minutes),
        when {
            p == null -> null
            p.finished -> "ascoltata"
            p.positionMs >= PodcastLibrary.START_MS -> (e.durationMs ?: p.durationMs)?.let { "mancano ${minutes(it - p.positionMs)}" } ?: "iniziata"
            else -> null
        },
    ).joinToString(" · ")

    companion object {
        const val KEY = "podcast"
        const val SHOW = "show"

        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALIAN)

        /** La chiave di una puntata, la stessa del punto di ripresa: "<podcast>~<puntata>". */
        fun keyOf(show: Podcast, e: Episode) = "${show.id}~${e.key}"

        /** La chiave della puntata di un ID di brano ("podcast/track/<podcast>~<puntata>"), o null. */
        fun episodeKey(mediaId: String): String? =
            MediaId.parse(mediaId)?.takeIf { it.source == KEY && it.isTrack }?.id?.takeIf { split(it) != null }

        private fun split(id: String): Pair<String, String>? {
            val show = id.substringBefore('~', "")
            val episode = id.substringAfter('~', "")
            return if (show.isEmpty() || episode.isEmpty()) null else show to episode
        }

        fun minutes(ms: Long): String {
            val total = ((ms + 30_000) / 60_000).coerceAtLeast(1)
            return if (total < 60) "$total min" else "${total / 60} h" + (total % 60).let { if (it > 0) " $it min" else "" }
        }

        private fun episodes(n: Int) = if (n == 1) "1 puntata" else "$n puntate"
    }
}
