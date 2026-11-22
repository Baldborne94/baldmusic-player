package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind

/**
 * Cosa suonare per una richiesta vocale ("Ok Google, metti Movie Soundtracks su
 * BaldMusic"). Le parole che dicono il tipo ("la raccolta", "la playlist", "l'album")
 * si tolgono e fanno preferire quel tipo.
 */
object VoiceQuery {

    private val kinds = listOf(
        Kind.PLAYLIST to Regex("^(la |le )?(raccolta|raccolte|playlist)\\s+", RegexOption.IGNORE_CASE),
        Kind.ALBUM to Regex("^(l'|l’|il |lo )?(album|disco)\\s+", RegexOption.IGNORE_CASE),
        Kind.ARTIST to Regex("^(l'|l’)?(artista)\\s+", RegexOption.IGNORE_CASE),
        Kind.TRACK to Regex("^(la |il |una |un )?(canzone|brano|pezzo|traccia)\\s+", RegexOption.IGNORE_CASE),
    )

    /**
     * Una richiesta a voce o dalla ricerca dell'Assistente arriva senza ID, con solo le parole
     * da cercare: restituisce cosa cercare ("" = «metti un po' di musica», anche quando
     * l'Assistente non manda parole), null se è una richiesta normale (un ID da suonare).
     * Prima una richiesta senza parole (null) finiva cercata come ID vuoto e non suonava niente.
     */
    fun searchOf(mediaId: String, query: String?): String? = if (mediaId.isEmpty()) withoutAppName(query.orEmpty()) else null

    /**
     * Il nome dell'app che l'Assistente lascia nella richiesta quando non lo riconosce: in
     * macchina «Ok Google, metti Linkin Park su BaldMusic» arrivava come «Linkin Park su bald
     * music» (o «su Bud music») e la ricerca, con quelle parole in più, non trovava niente.
     * Si toglie in fondo «su/con/in/da/on» + una parola corta + «music», attaccata o no; anche
     * un «metti / riproduci / suona / fammi sentire» rimasto davanti.
     */
    private val appName = Regex(
        """\s+(?:su|sull'app|sull’app|nell'app|nell’app|con|in|da|on)\s+(?:l'app\s+|l’app\s+)?\p{L}{2,7}\s*-?\s*music[ae]?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val verb = Regex("""^(?:metti|mettimi|riproduci|suona|fammi sentire|fai partire|ascolta)\s+""", RegexOption.IGNORE_CASE)

    fun withoutAppName(query: String): String = query.trim().replace(appName, "").replace(verb, "").trim()

    /** Le parole che non dicono cosa cercare («Metal mix», «musica di Vasco», «canzoni dei Queen»). */
    private val filler = Regex(
        """(?<!\p{L})(?:mix|musica|canzoni|brani|pezzi|qualcosa|un po['’])(?!\p{L})(?:\s+(?:di|dei|degli|delle|del|della|dello)(?!\p{L}))?""",
        RegexOption.IGNORE_CASE,
    )

    /** Cosa dire in macchina quando una richiesta non trova niente ([search] = le parole cercate, null se non era una ricerca). */
    fun notFound(search: String?): String =
        if (search.isNullOrBlank()) "Niente da suonare" else "Non ho trovato «${search.trim()}» su BaldMusic"

    /** «Metti musica», «un po' di musica», niente: nessun nome, parte un mix. */
    fun anyMusic(query: String): Boolean = filler.replace(query, " ").isBlank()

    /**
     * Le ricerche da provare una dopo l'altra finché una trova qualcosa: la richiesta com'è,
     * poi senza le parole di contorno («Metal mix» → «Metal», così si trova la raccolta Metal).
     */
    fun attempts(query: String): List<String> {
        val q = query.trim()
        val simpler = filler.replace(q, " ").replace(Regex("\\s+"), " ").trim()
        return listOf(q, simpler).filter { it.isNotEmpty() }.distinct()
    }

    /** La richiesta senza le parole sul tipo, e il tipo chiesto se c'era. */
    fun parse(query: String): Pair<String, Kind?> {
        val q = query.trim()
        kinds.forEach { (kind, regex) -> regex.find(q)?.let { return q.substring(it.range.last + 1).trim() to kind } }
        return q to null
    }

    /** Un brano chiesto per titolo e, se detto, artista («Numb dei Linkin Park»). */
    data class SongAsk(val title: String, val artist: String?)

    /** Quante divisioni titolo/artista provare al massimo: ognuna è una ricerca. */
    const val MAX_SONG_ASKS = 5

    /** Le parole tra titolo e artista: «Numb dei Linkin Park», «Albachiara di Vasco», «Numb by …», «Numb - …». */
    private val byArtist = Regex("""\s+(?:di|dei|degli|del|della|dello|delle|by)\s+|\s+[-–]\s+""", RegexOption.IGNORE_CASE)

    /**
     * Le divisioni in titolo e artista da provare quando la richiesta intera non trova niente.
     * Prima l'artista nominava solo un artista («Linkin Park» funzionava), ma «Numb dei Linkin Park»
     * cercava la frase intera dentro titoli e artisti e non trovava niente. In ordine: titolo e
     * artista che l'Assistente manda a parte ([title], [artist]), poi le parole in mezzo («di»,
     * «dei», «by», «-», dall'ultima), poi senza parole in mezzo l'artista nelle ultime 2, 1 o 3 parole.
     */
    fun songAsks(query: String, title: String? = null, artist: String? = null): List<SongAsk> {
        val out = mutableListOf<SongAsk>()
        if (!title.isNullOrBlank()) out += SongAsk(title.trim(), artist?.trim()?.ifBlank { null })
        val q = parse(withoutAppName(query)).first
        byArtist.findAll(q).toList().asReversed().forEach { m ->
            val t = q.substring(0, m.range.first).trim()
            val a = q.substring(m.range.last + 1).trim()
            if (t.isNotEmpty() && a.isNotEmpty()) out += SongAsk(t, a)
        }
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (k in listOf(2, 1, 3)) {
            if (words.size > k) out += SongAsk(words.dropLast(k).joinToString(" "), words.takeLast(k).joinToString(" "))
        }
        return out.distinct().take(MAX_SONG_ASKS)
    }

    /** «I Queen», «the Beatles», «l'Orchestra»: l'articolo davanti all'artista non conta. */
    private val article = Regex("""^(?:i|gli|le|la|il|lo|the)\s+|^l'""")

    private fun artistKey(name: String) = ArtistNames.key(name).replace(article, "")

    /** Il brano è di [asked] (anche «A feat. B» è di A e di B). */
    private fun artistMatches(asked: String, artist: String?): Boolean {
        val a = artistKey(asked)
        val b = artist?.let(::artistKey).orEmpty()
        return a.isNotEmpty() && b.isNotEmpty() && (b.contains(a) || a.contains(b))
    }

    /** I brani trovati che sono [ask]: titolo che lo contiene e artista giusto, i migliori prima. */
    fun songsFor(ask: SongAsk, results: List<LibraryNode>): List<LibraryNode> {
        val title = ArtistNames.key(ask.title)
        if (title.isEmpty()) return emptyList()
        val tracks = results.filter { n ->
            n.playable && ArtistNames.key(n.title).contains(title) && (ask.artist == null || artistMatches(ask.artist, n.artist ?: n.subtitle))
        }
        return rankSongs(ask.title, tracks)
    }

    /**
     * I brani col titolo uguale a [title] prima, poi quelli che cominciano così, poi gli altri
     * («Numb» prima di «Numb (Live)» e di «Numb Little Bug»); a parità, l'ordine della ricerca.
     */
    fun rankSongs(title: String, tracks: List<LibraryNode>): List<LibraryNode> {
        val t = ArtistNames.key(title)
        return tracks.sortedBy { n ->
            val k = ArtistNames.key(n.title)
            when {
                k == t -> 0
                k.startsWith(t) -> 1
                else -> 2
            }
        }
    }

    private val podcast = Regex(
        "^(?:(?:il|un|i|un altro|l'ultimo|l’ultimo) )?podcast(?: di| del| della)?(?:\\s+|$)|" +
            "^(?:(?:la|una|l'ultima|l’ultima|la nuova|la prossima) )?puntata(?: del podcast| di| del| della)?(?:\\s+|$)",
        RegexOption.IGNORE_CASE,
    )

    /** Una richiesta di podcast: il nome (vuoto se non l'ha detto) e se ha chiesto proprio l'ultima uscita. */
    data class PodcastAsk(val name: String, val latest: Boolean)

    private val latest = Regex("^(?:l'ultim[oa]|l’ultim[oa]|la nuova) ", RegexOption.IGNORE_CASE)

    /**
     * Una richiesta che parla di podcast ("il podcast Non Aprite Quella Podcast", "l'ultima
     * puntata di NAQP", "un podcast"). Null: non parla di podcast (ma il nome di un podcast
     * detto da solo lo trova [pick]).
     */
    fun podcast(query: String): PodcastAsk? {
        val q = query.trim()
        val m = podcast.find(q) ?: return null
        return PodcastAsk(q.substring(m.range.last + 1).trim(), latest.containsMatchIn(q))
    }

    /**
     * Il contenitore da suonare per intero, se la richiesta ne nomina uno: lo stesso nome
     * esatto (prima raccolte e playlist, poi artisti, poi album) o, se è stato detto il tipo,
     * il primo di quel tipo che contiene le parole. Null: si suonano i brani trovati.
     */
    fun pick(query: String, results: List<LibraryNode>): LibraryNode? {
        val (what, kind) = parse(query)
        val key = ArtistNames.key(what)
        if (key.isEmpty()) return null
        val order = listOf(Kind.PLAYLIST, Kind.ARTIST, Kind.ALBUM)
        val containers = results.filter { it.kind in order && (kind == null || it.kind == kind) }
        containers.filter { ArtistNames.key(it.title) == key }.minByOrNull { order.indexOf(it.kind) }?.let { return it }
        if (kind != null) return containers.firstOrNull { ArtistNames.key(it.title).contains(key) }
        return null
    }
}
