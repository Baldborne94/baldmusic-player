package io.github.baldborne94.baldmusic.core.podcast

/**
 * Le stagioni di un podcast: dai tag `itunes:season`/`itunes:episode` del feed, altrimenti dal
 * titolo («S15 E08 Il Caso Cinturrino», «S15E08», «Stagione 3 – Episodio 4», «Season 2 Episode 5»,
 * «3x04»). Un podcast senza almeno due stagioni resta un elenco unico.
 */
object PodcastSeasons {
    data class Season(
        /** null = «Altre puntate»: speciali, trailer, puntate senza stagione. */
        val number: Int?,
        /** In ordine di puntata (E01, E02…); a parità di numero, la più vecchia prima. */
        val episodes: List<Episode>,
    )

    private val patterns = listOf(
        Regex("""\bS(\d{1,2})\s*[-.·]?\s*E(?:P|p)?\.?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:stagione|season)\s*(\d{1,2})\b\D{0,12}?\b(?:episodio|puntata|episode|ep)\.?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:stagione|season)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{1,2})x(\d{2,3})\b"""),
    )

    /** Stagione e numero della puntata; numero null se non si sa. */
    fun of(e: Episode): Pair<Int, Int?>? {
        e.season?.let { return it to e.number }
        patterns.forEach { p ->
            val m = p.find(e.title) ?: return@forEach
            val season = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            return season to m.groupValues.getOrNull(2)?.toIntOrNull()
        }
        return null
    }

    /**
     * Le puntate divise per stagione, dalla più recente; in fondo quelle senza stagione.
     * Vuoto se le stagioni sono meno di due: allora si mostra l'elenco com'è.
     */
    fun split(episodes: List<Episode>): List<Season> {
        val bySeason = episodes.groupBy { of(it)?.first }
        if (bySeason.keys.count { it != null } < 2) return emptyList()
        val seasons = bySeason.filterKeys { it != null }.toSortedMap(compareByDescending { it }).map { (n, list) ->
            Season(n, list.sortedWith(compareBy<Episode>({ of(it)?.second ?: Int.MAX_VALUE }, { it.publishedAt ?: Long.MAX_VALUE })))
        }
        val others = bySeason[null].orEmpty().sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }
        return seasons + listOfNotNull(others.takeIf { it.isNotEmpty() }?.let { Season(null, it) })
    }
}
