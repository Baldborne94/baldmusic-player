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
        ALBUMS -> client.albumList("alphabeticalByName", 500).filter { it.isReal }.map(::albumNode)
        ARTISTS -> client.artists().map(::artistNode)
        PLAYLISTS -> client.playlists().map(::playlistNode)
        STARRED -> client.starred().song.map { songNode(it, id) }
        RANDOM -> {
            val songs = if (id.id == null) client.randomSongs(100).also { lastRandom = it } else lastRandom
            songs.map { songNode(it, stableRandomId()) }
        }
        ALBUM -> client.album(requireNotNull(id.id)).song.map { songNode(it, id.copy(parent = null)) }
        ARTIST -> {
            val (real, singles) = client.artist(requireNotNull(id.id)).album.sortedByDescending { it.year ?: 0 }.partition { it.isReal }
            real.map(::albumNode) + listOfNotNull(
                singles.takeIf { it.isNotEmpty() }?.let { s ->
                    LibraryNode(MediaId(KEY, ARTIST_SINGLES, id.id).toString(), "Singoli e brani sparsi", Kind.SECTION, subtitle = count(s.size), artwork = s.first().coverArt?.let { "$KEY/$it" })
                },
            )
        }
        ARTIST_SINGLES -> songsOf(client.artist(requireNotNull(id.id)).album.filterNot { it.isReal })
            .sortedWith(compareBy({ it.year ?: 0 }, { it.title.lowercase() }))
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

    /** Album con almeno due brani; il resto sono singoli o tag sbagliati. */
    private val Album.isReal get() = (songCount ?: 2) > 1

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
        year = a.year,
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
