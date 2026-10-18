package io.github.baldborne94.baldmusic.core.library

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Indirizzo di ogni nodo della libreria, condiviso da app e Android Auto.
 *
 *   root
 *   local                          radice di una sorgente
 *   local/albums                   sezione
 *   remote/album/<id>              contenitore
 *   remote/track/<id>|<parent>     brano, col contenitore da cui è stato scelto
 *
 * Il contenitore serve a ricostruire la coda: toccando il terzo brano di un
 * album si ascolta l'album dal terzo brano, non un brano isolato.
 */
data class MediaId(val source: String, val kind: String? = null, val id: String? = null, val parent: MediaId? = null) {

    val isTrack get() = kind == TRACK

    fun withParent(parent: MediaId?) = copy(parent = parent)

    override fun toString(): String {
        val base = listOfNotNull(source, kind, id?.let(::enc)).joinToString("/")
        return if (parent != null) "$base|$parent" else base
    }

    companion object {
        const val ROOT = "root"
        const val TRACK = "track"

        fun parse(raw: String): MediaId? {
            if (raw.isBlank() || raw == ROOT) return null
            val head = raw.substringBefore('|')
            val parent = raw.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let(::parse)
            val parts = head.split('/', limit = 3)
            return MediaId(
                source = parts[0],
                kind = parts.getOrNull(1),
                id = parts.getOrNull(2)?.let(::dec),
                parent = parent,
            )
        }

        fun track(source: String, id: String, parent: MediaId?) = MediaId(source, TRACK, id, parent?.copy(parent = null))

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
