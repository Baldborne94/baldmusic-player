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

/** Chiave negli extra: tipo di nodo, per scegliere griglia o lista nell'app. */
const val EXTRA_KIND = "io.github.baldborne94.baldmusic.KIND"

/** Chiave nelle RequestMetadata: "riproduci in ordine casuale partendo da un brano a caso". */
const val EXTRA_SHUFFLE = "io.github.baldborne94.baldmusic.SHUFFLE"

fun LibraryNode.toMediaItem(context: Context): MediaItem {
    val style = if (gridChildren) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    val extras = Bundle().apply {
        putString(EXTRA_KIND, kind.name)
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
                Kind.SOURCE, Kind.SECTION, Kind.MESSAGE -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
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

val MediaItem.kind: Kind?
    get() = mediaMetadata.extras?.getString(EXTRA_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }
