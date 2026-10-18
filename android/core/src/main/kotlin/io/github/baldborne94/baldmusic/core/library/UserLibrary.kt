package io.github.baldborne94.baldmusic.core.library

import kotlinx.serialization.Serializable

// La musica "tua": brani che ti piacciono, playlist, cronologia. Vive sul
// telefono (un file JSON) e vale per entrambe le sorgenti: un brano del server e
// uno del telefono possono stare nella stessa playlist.
//
// Qui solo dati e regole pure; il salvataggio su file lo fa l'app.

/** Un brano ricordato: l'ID senza contenitore più quanto serve a mostrarlo. */
@Serializable
data class TrackRef(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artwork: String? = null,
    val durationMs: Long? = null,
)

/** Un album, una playlist o un artista ascoltato di recente. */
@Serializable
data class ContainerRef(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
    val kind: String,
)

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val tracks: List<TrackRef> = emptyList(),
    val createdAt: Long = 0,
)

@Serializable
data class PlayCount(val track: TrackRef, val count: Int, val lastPlayed: Long)

@Serializable
data class UserLibrary(
    /** Dal più recente. */
    val liked: List<TrackRef> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    /** Dal più recente, senza doppioni. */
    val recent: List<ContainerRef> = emptyList(),
    val plays: Map<String, PlayCount> = emptyMap(),
    /** Ultime ricerche, dalla più recente. */
    val searches: List<String> = emptyList(),
) {
    fun isLiked(trackId: String) = liked.any { it.id == baseId(trackId) }

    fun toggleLike(track: TrackRef): UserLibrary {
        val id = baseId(track.id)
        return if (liked.any { it.id == id }) copy(liked = liked.filterNot { it.id == id })
        else copy(liked = listOf(track.copy(id = id)) + liked)
    }

    /** Un ascolto: conta il brano e mette in cima il contenitore da cui veniva. */
    fun recordPlay(track: TrackRef, container: ContainerRef?, now: Long): UserLibrary {
        val id = baseId(track.id)
        val previous = plays[id]
        val count = PlayCount(track.copy(id = id), (previous?.count ?: 0) + 1, now)
        val recent = if (container == null) recent
        else (listOf(container) + recent.filterNot { it.id == container.id }).take(MAX_RECENT)
        return copy(plays = trimPlays(plays + (id to count)), recent = recent)
    }

    /** I brani più ascoltati, a parità di conteggio il più recente prima. */
    fun topTracks(limit: Int = 50): List<TrackRef> =
        plays.values.sortedWith(compareByDescending<PlayCount> { it.count }.thenByDescending { it.lastPlayed })
            .take(limit).map { it.track }

    fun createPlaylist(id: String, name: String, now: Long, first: List<TrackRef> = emptyList()) =
        copy(playlists = playlists + UserPlaylist(id, name.trim().ifEmpty { "La mia playlist" }, first.map { it.copy(id = baseId(it.id)) }, now))

    fun renamePlaylist(id: String, name: String) =
        copy(playlists = playlists.map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it })

    fun deletePlaylist(id: String) = copy(
        playlists = playlists.filterNot { it.id == id },
        recent = recent.filterNot { it.id == MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, id).toString() },
    )

    /** Aggiunge in fondo; un brano già presente non viene duplicato. */
    fun addToPlaylist(id: String, tracks: List<TrackRef>) = copy(
        playlists = playlists.map { pl ->
            if (pl.id != id) pl
            else {
                val present = pl.tracks.map { it.id }.toSet()
                pl.copy(tracks = pl.tracks + tracks.map { it.copy(id = baseId(it.id)) }.filter { it.id !in present }.distinctBy { it.id })
            }
        },
    )

    fun removeFromPlaylist(id: String, trackId: String) = copy(
        playlists = playlists.map { pl -> if (pl.id == id) pl.copy(tracks = pl.tracks.filterNot { it.id == baseId(trackId) }) else pl },
    )

    fun addSearch(query: String): UserLibrary {
        val q = query.trim()
        if (q.length < 2) return this
        return copy(searches = (listOf(q) + searches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES))
    }

    fun clearSearches() = copy(searches = emptyList())

    private fun trimPlays(map: Map<String, PlayCount>): Map<String, PlayCount> =
        if (map.size <= MAX_PLAYS) map
        else map.values.sortedByDescending { it.lastPlayed }.take(MAX_PLAYS).associateBy { it.track.id }

    companion object {
        const val MAX_RECENT = 20
        const val MAX_SEARCHES = 10
        const val MAX_PLAYS = 2000

        /** L'ID di un brano senza il contenitore da cui è stato scelto. */
        fun baseId(trackId: String): String = MediaId.parse(trackId)?.copy(parent = null)?.toString() ?: trackId
    }
}
