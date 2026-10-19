package io.github.baldborne94.baldmusic.core.library

/** Una voce di classifica: nome, ascolti e (per brani e album) una copertina. */
data class Ranked(val name: String, val plays: Int, val artwork: String? = null, val subtitle: String? = null)

/** Le tue statistiche, calcolate dagli ascolti salvati sul telefono. */
data class ListeningStats(
    val totalPlays: Int,
    val minutes: Long,
    val tracks: Int,
    val artists: Int,
    val topArtists: List<Ranked>,
    val topTracks: List<Ranked>,
    val topAlbums: List<Ranked>,
    /** Da quando si contano gli ascolti (il più vecchio ultimo-ascolto noto). */
    val since: Long?,
) {
    companion object {
        fun from(lib: UserLibrary, limit: Int = 5): ListeningStats {
            val plays = lib.plays.values
            fun <K> rank(key: (PlayCount) -> K?, name: (K, List<PlayCount>) -> Ranked) =
                plays.filter { key(it) != null }.groupBy { key(it)!! }
                    .map { (k, v) -> name(k, v) }
                    .sortedByDescending { it.plays }
                    .take(limit)
            return ListeningStats(
                totalPlays = plays.sumOf { it.count },
                minutes = plays.sumOf { it.count.toLong() * (it.track.durationMs ?: 0L) } / 60_000,
                tracks = plays.size,
                // Lo stesso artista scritto in modi diversi ("Guns N' Roses", "guns n’ roses") è uno solo.
                artists = plays.mapNotNull { it.track.artist?.let(ArtistNames::key) }.distinct().size,
                topArtists = rank({ p -> p.track.artist?.let(ArtistNames::key) }) { _, v ->
                    // Il nome scritto negli ascolti più numerosi.
                    val name = v.groupBy { it.track.artist!!.trim() }.maxBy { (_, l) -> l.sumOf { it.count } }.key
                    Ranked(name, v.sumOf { it.count }, v.firstNotNullOfOrNull { it.track.artwork })
                },
                topTracks = plays.sortedByDescending { it.count }.take(limit).map { Ranked(it.track.title, it.count, it.track.artwork, it.track.artist) },
                topAlbums = rank({ it.track.album }) { k, v -> Ranked(k, v.sumOf { it.count }, v.firstNotNullOfOrNull { it.track.artwork }, v.first().track.artist) },
                since = plays.minOfOrNull { it.lastPlayed },
            )
        }
    }
}
