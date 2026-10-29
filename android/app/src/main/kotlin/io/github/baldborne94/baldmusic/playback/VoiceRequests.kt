@file:OptIn(UnstableApi::class)

package io.github.baldborne94.baldmusic.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.LibraryNode
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.VoiceQuery

/**
 * Le richieste a voce («Ok Google, metti … su BaldMusic»), dall'auto o dall'Assistente, e le
 * puntate chieste per intero: cosa suonare ([VoiceQuery], [io.github.baldborne94.baldmusic.core.podcast.PodcastSource]).
 */
internal class VoiceRequests(private val context: Context) {
    private val app get() = context.app
    private val tree get() = app.tree

    private fun List<LibraryNode>.toItems() = map { it.toMediaItem(context) }

    /** Una puntata sola, da dove eri arrivato. */
    fun episode(node: LibraryNode) =
        MediaItemsWithStartPosition(listOf(node.toMediaItem(context)), 0, app.podcasts.resumeAt(node.mediaId).takeIf { it > 0 } ?: C.TIME_UNSET)

    suspend fun play(query: String): MediaItemsWithStartPosition {
        fun all(items: List<MediaItem>) = MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
        if (query.isBlank()) {
            // "Metti un po' di musica": mix casuale dalla prima sorgente disponibile.
            val first = tree.rootNodes().firstOrNull() ?: return all(emptyList())
            val mix = tree.children(first.mediaId).firstOrNull { it.title.startsWith("Mix") } ?: return all(emptyList())
            return all(tree.playableIn(mix.mediaId).toItems())
        }
        // "Il podcast Non Aprite Quella Podcast", "l'ultima puntata di …": una puntata, da dove eri.
        if (app.podcasts.hasAny) VoiceQuery.podcast(query)?.let { ask ->
            app.podcastSource.forVoice(ask.name, ask.latest)?.let { return episode(it) }
        }
        val (words, _) = VoiceQuery.parse(query)
        val results = tree.search(words)
        // Una raccolta, un artista o un album detti per nome si suonano per intero (un podcast: una puntata).
        VoiceQuery.pick(query, results)?.let { picked ->
            MediaId.parse(picked.mediaId)?.let { app.podcastSource.toPlay(it) }?.let { return episode(it) }
            return all(tree.playableIn(picked.mediaId).toItems())
        }
        val tracks = results.filter { it.playable }
        if (tracks.isNotEmpty()) {
            val items = tracks.mapNotNull { tree.resolve(it) }.toItems()
            // La prima trovata è una puntata: riparte da dove eri.
            return MediaItemsWithStartPosition(items, 0, items.firstOrNull()?.let { app.podcasts.resumeAt(it.mediaId) }?.takeIf { it > 0 } ?: C.TIME_UNSET)
        }
        val container = results.firstOrNull { it.kind == LibraryNode.Kind.PLAYLIST || it.kind == LibraryNode.Kind.ARTIST || it.kind == LibraryNode.Kind.ALBUM }
        return all(container?.let { tree.playableIn(it.mediaId).toItems() }.orEmpty())
    }
}
