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
    /** OpenSubsonic (Navidrome): il ReplayGain letto dai tag del file. */
    val replayGain: SongReplayGain? = null,
)

@Serializable
data class SongReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
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
    // OpenSubsonic (Navidrome): tipo di uscita e anno originale, dai tag di Picard.
    val releaseTypes: List<String> = emptyList(),
    val originalReleaseDate: ItemDate? = null,
    /** OpenSubsonic: i titoli dei dischi ("Disco 2: The Two Towers"). */
    val discTitles: List<DiscTitle> = emptyList(),
) {
    /** L'anno della prima uscita, se Navidrome lo sa; altrimenti quello dell'edizione. */
    val shownYear: Int? get() = originalReleaseDate?.year?.takeIf { it > 0 } ?: year

    /**
     * Un album vero, da mettere tra gli album: almeno due brani e non un singolo.
     * Il resto (singoli, o un brano solo di un disco) sta tra i brani sparsi.
     */
    val isReal: Boolean get() = (songCount ?: 2) > 1 && releaseTypes.none { it.equals("single", ignoreCase = true) }
}

@Serializable
data class DiscTitle(val disc: Int = 0, val title: String = "")

@Serializable
data class ItemDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val album: List<Album> = emptyList(),
    /** OpenSubsonic: nome per l'ordinamento (es. "Beatles, The"), se il tag c'è. */
    val sortName: String? = null,
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
