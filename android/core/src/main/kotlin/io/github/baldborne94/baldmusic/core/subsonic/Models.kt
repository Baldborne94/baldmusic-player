package io.github.baldborne94.baldmusic.core.subsonic

import kotlinx.serialization.Serializable

// Solo i campi che usiamo: il parser ignora il resto.

@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val starred: String? = null,
)

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val year: Int? = null,
    val song: List<Song> = emptyList(),
)

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val album: List<Album> = emptyList(),
)

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val songCount: Int? = null,
    val coverArt: String? = null,
    val entry: List<Song> = emptyList(),
)

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
internal data class ArtistIndex(val artist: List<Artist> = emptyList())

@Serializable
internal data class Artists(val index: List<ArtistIndex> = emptyList())

@Serializable
internal data class AlbumList(val album: List<Album> = emptyList())

@Serializable
internal data class SongList(val song: List<Song> = emptyList())

@Serializable
internal data class Playlists(val playlist: List<Playlist> = emptyList())

@Serializable
internal data class ApiError(val code: Int = 0, val message: String = "")

@Serializable
internal data class Envelope(
    val status: String = "",
    val error: ApiError? = null,
    val album: Album? = null,
    val artist: Artist? = null,
    val artists: Artists? = null,
    val albumList2: AlbumList? = null,
    val randomSongs: SongList? = null,
    val playlists: Playlists? = null,
    val playlist: Playlist? = null,
    val searchResult3: SearchResult? = null,
    val starred2: SearchResult? = null,
    val song: Song? = null,
)

@Serializable
internal data class Root(
    @kotlinx.serialization.SerialName("subsonic-response") val response: Envelope? = null,
)
