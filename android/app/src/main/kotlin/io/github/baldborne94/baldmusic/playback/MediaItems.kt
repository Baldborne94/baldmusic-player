@file:OptIn(UnstableApi::class)

package io.github.baldborne94.baldmusic.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import io.github.baldborne94.baldmusic.artwork.ArtworkProvider
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary

/** Chiave negli extra: tipo di nodo, per scegliere griglia o lista nell'app. */
const val EXTRA_KIND = "io.github.baldborne94.baldmusic.KIND"

const val EXTRA_ALBUM_ID = "io.github.baldborne94.baldmusic.ALBUM_ID"
const val EXTRA_ARTIST_ID = "io.github.baldborne94.baldmusic.ARTIST_ID"

/** ReplayGain del brano negli extra (dB e picchi lineari), per il volume uguale tra i brani. */
private const val EXTRA_RG_TRACK = "io.github.baldborne94.baldmusic.RG_TRACK"
private const val EXTRA_RG_ALBUM = "io.github.baldborne94.baldmusic.RG_ALBUM"
private const val EXTRA_RG_TRACK_PEAK = "io.github.baldborne94.baldmusic.RG_TRACK_PEAK"
private const val EXTRA_RG_ALBUM_PEAK = "io.github.baldborne94.baldmusic.RG_ALBUM_PEAK"

/** Chiave nelle RequestMetadata: "riproduci in ordine casuale partendo da un brano a caso". */
const val EXTRA_SHUFFLE = "io.github.baldborne94.baldmusic.SHUFFLE"

fun LibraryNode.toMediaItem(context: Context): MediaItem {
    val style = if (gridChildren) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    val extras = Bundle().apply {
        putString(EXTRA_KIND, kind.name)
        albumId?.let { putString(EXTRA_ALBUM_ID, it) }
        artistId?.let { putString(EXTRA_ARTIST_ID, it) }
        // Intestazione nell'elenco (lettera, disco, tipo di uscita): Android Auto la disegna da solo.
        group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        replayGain?.let { rg ->
            rg.trackGain?.let { putFloat(EXTRA_RG_TRACK, it) }
            rg.albumGain?.let { putFloat(EXTRA_RG_ALBUM, it) }
            rg.trackPeak?.let { putFloat(EXTRA_RG_TRACK_PEAK, it) }
            rg.albumPeak?.let { putFloat(EXTRA_RG_ALBUM_PEAK, it) }
        }
        // Come Android Auto deve disegnare i figli di questo nodo.
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setDisplayTitle(title)
        .setSubtitle(subtitle)
        .setArtist(artist ?: subtitle.takeIf { kind == Kind.TRACK })
        .setAlbumTitle(album)
        .setArtworkUri(artwork?.let { ArtworkProvider.uri(context, it) })
        .setTrackNumber(trackNumber)
        .setDiscNumber(discNumber)
        .setRecordingYear(year)
        .setDurationMs(durationMs)
        .setIsBrowsable(browsable)
        .setIsPlayable(playable)
        .setMediaType(
            when (kind) {
                Kind.SOURCE, Kind.SECTION, Kind.FOLDER, Kind.MESSAGE -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                Kind.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
                Kind.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
                Kind.PLAYLIST -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                Kind.TRACK -> MediaMetadata.MEDIA_TYPE_MUSIC
            },
        )
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(metadata)
        .apply { streamUri?.let { setUri(it) } }
        .build()
}

/** Il ReplayGain messo negli extra da [toMediaItem]; null se il brano non ne ha. */
val MediaItem.replayGain: ReplayGain?
    get() {
        val e = mediaMetadata.extras ?: return null
        fun f(key: String) = if (e.containsKey(key)) e.getFloat(key) else null
        val rg = ReplayGain(f(EXTRA_RG_TRACK), f(EXTRA_RG_ALBUM), f(EXTRA_RG_TRACK_PEAK), f(EXTRA_RG_ALBUM_PEAK))
        return rg.takeIf { it.trackGain != null || it.albumGain != null }
    }

/** L'intestazione sotto cui sta nell'elenco, se c'è (vedi [LibraryNode.group]). */
val MediaItem.group: String?
    get() = mediaMetadata.extras?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)

val MediaItem.kind: Kind?
    get() = mediaMetadata.extras?.getString(EXTRA_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }

/** "content://<pkg>.artwork/local/42" → "local/42", la chiave usata da [LibraryNode.artwork]. */
fun artworkKey(uri: android.net.Uri?): String? =
    uri?.pathSegments?.takeIf { it.size == 2 }?.joinToString("/")

/** Il brano come riferimento da ricordare (mi piace, playlist, cronologia). */
fun MediaItem.toTrackRef() = TrackRef(
    id = UserLibrary.baseId(mediaId),
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString(),
    album = mediaMetadata.albumTitle?.toString(),
    artwork = artworkKey(mediaMetadata.artworkUri),
    durationMs = mediaMetadata.durationMs,
)

val MediaItem.albumId: String? get() = mediaMetadata.extras?.getString(EXTRA_ALBUM_ID)
val MediaItem.artistId: String? get() = mediaMetadata.extras?.getString(EXTRA_ARTIST_ID)
