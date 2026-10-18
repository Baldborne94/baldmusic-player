package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.subsonic.Album
import io.github.baldborne94.baldmusic.core.subsonic.Artist
import io.github.baldborne94.baldmusic.core.subsonic.Playlist
import io.github.baldborne94.baldmusic.core.subsonic.Song
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient

/** La libreria sul server Navidrome. */
class RemoteSource(
    private val client: SubsonicClient,
    /** Kbps massimi in streaming; 0 = file originale. */
    private val maxBitRate: () -> Int = { 0 },
) : MusicSource {

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
        RECENT -> client.albumList("newest", 60).map(::albumNode)
        ALBUMS -> client.albumList("alphabeticalByName", 500).map(::albumNode)
        ARTISTS -> client.artists().map(::artistNode)
        PLAYLISTS -> client.playlists().map(::playlistNode)
        STARRED -> client.starred().song.map { songNode(it, id) }
        RANDOM -> {
            val songs = if (id.id == null) client.randomSongs(100).also { lastRandom = it } else lastRandom
            songs.map { songNode(it, stableRandomId()) }
        }
        ALBUM -> client.album(requireNotNull(id.id)).song.map { songNode(it, id.copy(parent = null)) }
        ARTIST -> client.artist(requireNotNull(id.id)).album.sortedByDescending { it.year ?: 0 }.map(::albumNode)
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
        streamUri = client.streamUrl(s.id, maxBitRate()),
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
        const val PLAYLIST = "playlist"
    }
}
