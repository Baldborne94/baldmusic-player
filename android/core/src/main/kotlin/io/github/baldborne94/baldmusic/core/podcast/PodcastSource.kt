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
    /** L'indirizzo del file sul telefono di una puntata scaricata ([PodcastDownloads]), o null. */
    private val downloaded: (String) -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) : MusicSource {

    override val key = KEY
    override val title = "Podcast"
    // Il punto di ripresa cambia mentre ascolti: niente cache nell'albero.
    override val cacheable get() = false

    /** «Da ascoltare» (se c'è qualcosa) e poi i podcast. */
    override suspend fun sections(): List<LibraryNode> {
        val all = shows()
        val next = upNext(all)
        val head = if (next.isEmpty()) emptyList() else listOf(
            LibraryNode(
                mediaId = MediaId(KEY, UPNEXT).toString(),
                title = "Da ascoltare",
                kind = Kind.SECTION,
                subtitle = episodes(next.size),
                artwork = next.first().first.image?.let { "$KEY/${next.first().first.id}" },
            ),
        )
        return head + all.map(::showNode)
    }

    /**
     * Le puntate da ascoltare: prima quelle iniziate e non finite (dall'ultima ascoltata), poi
     * quelle uscite da poco e mai cominciate (dalla più recente), poi, finché non ce ne sono
     * almeno [UPNEXT_FILL], quelle dall'archivio ([archive]): così in Home ci sono sempre le
     * prossime, senza cercarle nella Libreria.
     */
    fun upNext(all: List<Podcast>): List<Pair<Podcast, Episode>> = upNextGroups(all).map { it.first }

    /** Come [upNext], con l'intestazione di ognuna: «Da finire», «Nuove», «Dall'archivio». */
    fun upNextGroups(all: List<Podcast>): List<Pair<Pair<Podcast, Episode>, String>> {
        val progress = library().progress
        fun p(show: Podcast, e: Episode) = progress[keyOf(show, e)]
        fun started(show: Podcast, e: Episode) = p(show, e)?.let { !it.finished && it.positionMs >= PodcastLibrary.START_MS } == true
        // Mai cominciata davvero: niente punto, o aperta per pochi secondi.
        fun untouched(show: Podcast, e: Episode) = p(show, e)?.let { !it.finished && it.positionMs < PodcastLibrary.START_MS } ?: true
        val episodes = all.flatMap { show -> show.episodes.map { show to it } }
        val doing = episodes.filter { (show, e) -> started(show, e) }.sortedByDescending { (show, e) -> p(show, e)!!.at }
        val fresh = episodes.filter { (show, e) -> untouched(show, e) && (e.publishedAt ?: 0) >= now() - PodcastFeed.NEW_FOR_MS }
            .sortedByDescending { it.second.publishedAt }
        val taken = (doing + fresh).mapTo(HashSet()) { keyOf(it.first, it.second) }
        val missing = (UPNEXT_FILL - taken.size).coerceAtLeast(0)
        val older = archive(all, missing) { show, e -> keyOf(show, e) !in taken && untouched(show, e) }
        return (doing.map { it to "Da finire" } + fresh.map { it to "Nuove" } + older.map { it to "Dall'archivio" }).take(UPNEXT_MAX)
    }

    /**
     * Le prossime [count] dall'archivio, a turno tra i podcast. Per ognuno: se hai finito una
     * puntata, quelle uscite dopo (dalla più vicina, stai andando avanti) e poi quelle prima
     * (dalla più recente); se non ne hai finite, dalla più recente.
     */
    private fun archive(all: List<Podcast>, count: Int, open: (Podcast, Episode) -> Boolean): List<Pair<Podcast, Episode>> {
        if (count <= 0) return emptyList()
        val progress = library().progress
        val perShow = all.map { show ->
            val candidates = show.episodes.filter { open(show, it) }
            val lastDone = show.episodes.filter { progress[keyOf(show, it)]?.finished == true }.maxByOrNull { progress[keyOf(show, it)]!!.at }
            val at = lastDone?.publishedAt
            val ordered = if (at == null) candidates.sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }
            else candidates.filter { (it.publishedAt ?: Long.MIN_VALUE) > at }.sortedBy { it.publishedAt } +
                candidates.filter { (it.publishedAt ?: Long.MIN_VALUE) <= at }.sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }
            ArrayDeque(ordered.map { show to it })
        }
        val out = ArrayList<Pair<Podcast, Episode>>()
        while (out.size < count && perShow.any { it.isNotEmpty() }) perShow.forEach { q -> if (out.size < count) q.removeFirstOrNull()?.let(out::add) }
        return out
    }

    /**
     * La puntata da suonare quando chiedi un podcast intero (con la voce, o «Riproduci» sul
     * podcast o su «Da ascoltare»): la prima di «Da ascoltare» (quella lasciata a metà, poi una
     * uscita da poco), altrimenti la più recente non ascoltata. Null [showId]: tra tutti i podcast.
     */
    fun nextEpisode(all: List<Podcast>, showId: String?): Pair<Podcast, Episode>? {
        val shows = if (showId == null) all else all.filter { it.id == showId }
        upNext(shows).firstOrNull()?.let { return it }
        val progress = library().progress
        return shows.flatMap { show -> show.episodes.map { show to it } }
            .filter { (show, e) -> progress[keyOf(show, e)]?.finished != true }
            .maxByOrNull { it.second.publishedAt ?: Long.MIN_VALUE }
    }

    /**
     * La puntata da far partire quando finisce [finishedKey] («puntata dopo puntata»): quella dello
     * stesso podcast uscita subito dopo e non ancora sentita, così si va avanti in ordine di uscita;
     * se era la più recente, la prima di «Da ascoltare» ([upNext]). Mai la stessa.
     */
    fun nextAfter(all: List<Podcast>, finishedKey: String): Pair<Podcast, Episode>? {
        val progress = library().progress
        fun open(show: Podcast, e: Episode) = keyOf(show, e) != finishedKey && progress[keyOf(show, e)]?.finished != true
        val show = all.firstOrNull { finishedKey.startsWith("${it.id}~") }
        val done = show?.episodes?.firstOrNull { keyOf(show, it) == finishedKey }
        val at = done?.publishedAt
        if (show != null && at != null) {
            show.episodes.filter { open(show, it) && (it.publishedAt ?: Long.MIN_VALUE) > at }
                .minByOrNull { it.publishedAt!! }?.let { return show to it }
        }
        return upNext(all).firstOrNull { (s, e) -> open(s, e) }
    }

    /** [nextAfter] pronta da suonare (indirizzo dell'audio fresco, o il file sul telefono). */
    suspend fun nextAfter(finishedMediaId: String): LibraryNode? {
        val key = episodeKey(finishedMediaId) ?: return null
        val (show, e) = nextAfter(forPlayback(), key) ?: return null
        return track(MediaId.track(KEY, keyOf(show, e), null))
    }

    /**
     * Cosa suonare per un contenitore dei podcast ([SHOW] o [UPNEXT]): una puntata sola
     * ([nextEpisode]), non tutto l'archivio dalla più recente. Null: non è un contenitore dei podcast.
     */
    suspend fun toPlay(id: MediaId): LibraryNode? {
        if (id.source != KEY || (id.kind != SHOW && id.kind != UPNEXT)) return null
        val (show, e) = nextEpisode(shows(), if (id.kind == SHOW) id.id else null) ?: return null
        return track(MediaId.track(KEY, keyOf(show, e), null))
    }

    /**
     * Una richiesta vocale che parla di podcast ([io.github.baldborne94.baldmusic.core.library.VoiceQuery.podcast]):
     * [name] vuoto = tra tutti; altrimenti il podcast con quel nome (uguale, o che lo contiene).
     * [latest] («l'ultima puntata»): la più recente uscita, anche se ne hai una a metà; se no
     * [nextEpisode]. Null: nessun podcast con quel nome.
     */
    suspend fun forVoice(name: String, latest: Boolean = false): LibraryNode? {
        val all = shows()
        val key = ArtistNames.key(name)
        val show = if (key.isEmpty()) null else
            all.firstOrNull { ArtistNames.key(it.title) == key } ?: all.firstOrNull { ArtistNames.key(it.title).contains(key) } ?: return null
        val pick = if (latest) {
            (if (show == null) all else listOf(show)).flatMap { s -> s.episodes.map { s to it } }.maxByOrNull { it.second.publishedAt ?: Long.MIN_VALUE }
        } else {
            nextEpisode(all, show?.id)
        }
        val (s, e) = pick ?: return null
        return track(MediaId.track(KEY, keyOf(s, e), null))
    }

    /** Le puntate dalla più recente, divise per mese ([EpisodeLabels]). */
    override suspend fun children(id: MediaId): List<LibraryNode> {
        if (id.kind == UPNEXT) {
            val progress = library().progress
            return upNextGroups(shows()).map { (pair, group) ->
                val (show, e) = pair
                episodeNode(show, e, progress[keyOf(show, e)], EpisodeLabels.tags(show.episodes)).copy(group = group)
            }
        }
        if (id.kind != SHOW) return emptyList()
        val show = shows().firstOrNull { it.id == id.id } ?: return emptyList()
        val progress = library().progress
        val tags = EpisodeLabels.tags(show.episodes)
        return show.episodes.map { e ->
            episodeNode(show, e, progress[keyOf(show, e)], tags).copy(group = EpisodeLabels.month(e.publishedAt, zone))
        }
    }

    override suspend fun node(id: MediaId): LibraryNode? = when (id.kind) {
        SHOW -> shows().firstOrNull { it.id == id.id }?.let(::showNode)
        UPNEXT -> LibraryNode(MediaId(KEY, UPNEXT).toString(), "Da ascoltare", Kind.SECTION)
        else -> null
    }

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
        // Scaricata: suona dal telefono, anche senza rete.
        val local = downloaded(keyOf(show, e))
        return LibraryNode(
            // Senza contenitore: si ascolta la puntata scelta, non tutte quelle dopo.
            mediaId = MediaId.track(KEY, keyOf(show, e), null).toString(),
            title = title,
            kind = Kind.TRACK,
            subtitle = listOfNotNull(tag, subtitle(e, p).takeIf { it.isNotEmpty() }, local?.let { "scaricata" }).joinToString(" · "),
            artist = show.title,
            album = show.title,
            artwork = show.image?.let { "$KEY/${show.id}" },
            durationMs = e.durationMs ?: p?.durationMs,
            streamUri = local ?: e.audioUrl,
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
        const val UPNEXT = "upnext"

        /** Quante puntate al massimo in «Da ascoltare». */
        const val UPNEXT_MAX = 20
        /** Almeno così tante (quelle che si vedono in Home), riempiendo dall'archivio. */
        const val UPNEXT_FILL = 5

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
