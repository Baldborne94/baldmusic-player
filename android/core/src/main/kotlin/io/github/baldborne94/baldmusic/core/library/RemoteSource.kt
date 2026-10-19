package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.subsonic.Album
import io.github.baldborne94.baldmusic.core.subsonic.Artist
import io.github.baldborne94.baldmusic.core.subsonic.Playlist
import io.github.baldborne94.baldmusic.core.subsonic.Song
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** La libreria sul server Navidrome. */
class RemoteSource(private val client: SubsonicClient) : MusicSource {

    override val key = KEY
    override val title = "Server"

    /** Il mix casuale cambia a ogni richiesta: si tiene l'ultimo per ricostruire la coda. */
    @Volatile private var lastRandom: List<Song> = emptyList()

    override suspend fun sections() = listOf(
        section(RECENT, "Aggiunti di recente", grid = true),
        section(ALBUMS, "Album", grid = true),
        section(ARTISTS, "Artisti"),
        section(PLAYLISTS, "Playlist", grid = true),
        section(STARRED, "Preferiti"),
        section(RANDOM, "Mix casuale"),
    )

    private fun section(kind: String, title: String, grid: Boolean = false) =
        LibraryNode(MediaId(KEY, kind).toString(), title, Kind.SECTION, gridChildren = grid)

    override suspend fun children(id: MediaId): List<LibraryNode> = when (id.kind) {
        // Un "album" con un solo brano è quasi sempre un tag sbagliato o un singolo:
        // non va tra gli album, si elenca come brano (vedi singlesOf).
        RECENT -> {
            val (real, singles) = client.albumList("newest", 60).partition { it.isReal }
            real.map(::albumNode) + songsOf(singles.take(30)).map { songNode(it, MediaId(KEY, RECENT)) }
        }
        // Per lettera, con le stesse regole del telefono: in Android Auto ogni lettera è un'intestazione.
        ALBUMS -> client.albumList("alphabeticalByName", 500).filter { it.isReal }.sortedBy { ArtistNames.sortKey(it.name) }
            .map { albumNode(it).copy(group = ArtistNames.letter(it.name)) }
        ARTISTS -> client.artists().sortedBy { ArtistNames.sortKey(it.orderName) }
            .map { artistNode(it).copy(group = ArtistNames.letter(it.orderName)) }
        PLAYLISTS -> client.playlists().map(::playlistNode)
        STARRED -> client.starred().song.map { songNode(it, id) }
        RANDOM -> {
            val songs = if (id.id == null) client.randomSongs(100).also { lastRandom = it } else lastRandom
            songs.map { songNode(it, stableRandomId()) }
        }
        ALBUM -> {
            val album = client.album(requireNotNull(id.id))
            // Su più dischi: "Disco 1", "Disco 2" (col titolo del disco se Navidrome lo sa).
            val multiDisc = album.song.mapNotNull { it.discNumber?.takeIf { d -> d > 0 } }.distinct().size > 1
            album.song.map { s ->
                val disc = s.discNumber?.takeIf { it > 0 }
                songNode(s, id.copy(parent = null)).copy(
                    group = if (multiDisc && disc != null) LocalCatalog.discLabel(disc, album.discTitles.firstOrNull { it.disc == disc }?.title) else null,
                )
            }
        }
        ARTIST -> {
            val (real, singles) = client.artist(requireNotNull(id.id)).album.partition { it.isReal }
            if (real.isEmpty()) {
                // Senza album, i brani si vedono subito invece che dentro una sezione (come sul telefono).
                looseSongs(singles).map { songNode(it, id.copy(parent = null)) }
            } else {
                // Discografia per tipo (Album, EP, Dal vivo, Colonne sonore…), ognuno dal più recente.
                Discography.grouped(real, { it.releaseTypes }, { it.shownYear ?: 0 }, { it.name }).map { (kind, a) -> albumNode(a).copy(group = kind.title) } + listOfNotNull(
                    singles.takeIf { it.isNotEmpty() }?.let { s ->
                        LibraryNode(
                            // I brani, non i dischi: un singolo ha spesso anche il lato B.
                            MediaId(KEY, ARTIST_SINGLES, id.id).toString(), "Singoli e brani sparsi", Kind.SECTION,
                            subtitle = count(s.sumOf { (it.songCount ?: 1).coerceAtLeast(1) }), artwork = s.first().coverArt?.let { "$KEY/$it" }, group = LocalCatalog.OTHER_GROUP,
                        )
                    },
                )
            }
        }
        ARTIST_SINGLES -> looseSongs(client.artist(requireNotNull(id.id)).album.filterNot { it.isReal })
            .map { songNode(it, id.copy(parent = null)) }
        PLAYLIST -> client.playlist(requireNotNull(id.id)).entry.map { songNode(it, id.copy(parent = null)) }
        else -> emptyList()
    }

    override suspend fun track(id: MediaId): LibraryNode? {
        val songId = id.id ?: return null
        val song = lastRandom.firstOrNull { it.id == songId } ?: runCatching { client.song(songId) }.getOrNull() ?: return null
        return songNode(song, id.parent)
    }

    override suspend fun search(query: String): List<LibraryNode> {
        val r = client.search(query)
        return r.artist.map(::artistNode) + r.album.map(::albumNode) + r.song.map { songNode(it, null) }
    }

    /** I brani di singoli e album da un brano, per anno e titolo. */
    private suspend fun looseSongs(albums: List<Album>) =
        songsOf(albums).sortedWith(compareBy({ it.year ?: 0 }, { it.title.lowercase() }))

    /** Il nome con cui ordinare: Navidrome manda "sortName" vuoto a chi non ha il tag. */
    private val Artist.orderName get() = sortName?.takeIf { it.isNotBlank() } ?: name

    /** I brani di tanti piccoli album, pochi alla volta per non intasare il server. */
    private suspend fun songsOf(albums: List<Album>): List<Song> = albums.chunked(6).flatMap { chunk ->
        coroutineScope { chunk.map { a -> async { runCatching { client.album(a.id).song }.getOrDefault(emptyList()) } }.awaitAll() }
    }.flatten()

    private fun count(n: Int) = if (n == 1) "1 brano" else "$n brani"

    /** Il mix già pescato: chi lo riapre trova gli stessi brani della coda in corso. */
    fun stableRandomId() = MediaId(KEY, RANDOM, "last")

    private fun albumNode(a: Album) = LibraryNode(
        mediaId = MediaId(KEY, ALBUM, a.id).toString(),
        title = a.name,
        kind = Kind.ALBUM,
        subtitle = a.artist,
        artist = a.artist,
        artwork = a.coverArt?.let { "$KEY/$it" },
        year = a.shownYear,
    )

    private fun artistNode(a: Artist) = LibraryNode(
        mediaId = MediaId(KEY, ARTIST, a.id).toString(),
        title = a.name,
        kind = Kind.ARTIST,
        subtitle = a.albumCount?.let { if (it == 1) "1 album" else "$it album" },
        artwork = (a.coverArt ?: "ar-${a.id}").let { "$KEY/$it" },
        gridChildren = true,
    )

    private fun playlistNode(p: Playlist) = LibraryNode(
        mediaId = MediaId(KEY, PLAYLIST, p.id).toString(),
        title = p.name,
        kind = Kind.PLAYLIST,
        subtitle = p.songCount?.let { if (it == 1) "1 brano" else "$it brani" },
        artwork = p.coverArt?.let { "$KEY/$it" },
    )

    private fun songNode(s: Song, parent: MediaId?) = LibraryNode(
        mediaId = MediaId.track(KEY, s.id, parent).toString(),
        title = s.title,
        kind = Kind.TRACK,
        subtitle = s.artist,
        artist = s.artist,
        album = s.album,
        artwork = s.coverArt?.let { "$KEY/$it" },
        durationMs = s.duration?.let { it * 1000L },
        trackNumber = s.track,
        discNumber = s.discNumber,
        year = s.year,
        streamUri = client.streamUrl(s.id),
        albumId = s.albumId?.let { MediaId(KEY, ALBUM, it).toString() },
        artistId = s.artistId?.let { MediaId(KEY, ARTIST, it).toString() },
        replayGain = s.replayGain?.let { ReplayGain(it.trackGain, it.albumGain, it.trackPeak, it.albumPeak) },
    )

    companion object {
        const val KEY = "remote"
        const val RECENT = "recent"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val STARRED = "starred"
        const val RANDOM = "random"
        const val ALBUM = "album"
        const val ARTIST = "artist"
        const val ARTIST_SINGLES = "artistsingles"
        const val PLAYLIST = "playlist"
    }
}
