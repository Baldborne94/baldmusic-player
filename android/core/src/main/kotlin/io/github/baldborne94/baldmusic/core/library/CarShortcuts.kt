package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.library.LibraryNode.Kind

/**
 * Scorciatoie solo per Android Auto, dove guidando si tocca poco e si scorre ancora meno:
 * «Riproduci in casuale» come prima voce di ogni raccolta e playlist (anche quelle del
 * server), e i mix «Per la macchina» e «Riscopri» in cima alla prima scheda, che partono con
 * un tocco. L'app ha già i suoi tasti, quindi qui non servono (le aggiunge il servizio solo
 * per chi non è l'app).
 */
object CarShortcuts {
    private const val SHUFFLE = "casuale:"
    private const val PLAY = "tutto:"

    /** L'ID di una scorciatoia: suona tutto [containerId], mescolato o in ordine. */
    fun id(containerId: String, shuffled: Boolean) = (if (shuffled) SHUFFLE else PLAY) + containerId

    /** Il contenitore di una scorciatoia e se va mescolato; null se [mediaId] non è una scorciatoia. */
    fun parse(mediaId: String): Pair<String, Boolean>? = when {
        mediaId.startsWith(SHUFFLE) -> mediaId.removePrefix(SHUFFLE).takeIf { it.isNotEmpty() }?.let { it to true }
        mediaId.startsWith(PLAY) -> mediaId.removePrefix(PLAY).takeIf { it.isNotEmpty() }?.let { it to false }
        else -> null
    }

    /** Raccolte e playlist (tue o del server): quelle che in auto hanno «Riproduci in casuale». */
    fun shufflable(parentId: String): Boolean {
        val id = MediaId.parse(parentId) ?: return false
        return id.id != null && id.kind == PersonalSource.PLAYLIST && (id.source == PersonalSource.KEY || id.source == RemoteSource.KEY)
    }

    /**
     * I figli di [parentId] come li vede l'auto: in una raccolta o playlist con almeno due brani
     * prima «Riproduci in casuale»; nella prima scheda («La tua musica», [mixes] = i suoi mix)
     * prima «Per la macchina» e «Riscopri», se ci sono.
     */
    fun forCar(parentId: String, children: List<LibraryNode>, mixes: List<LibraryNode> = emptyList()): List<LibraryNode> {
        if (parentId == PersonalSource.KEY) {
            val wanted = listOf(PersonalSource.DRIVE, PersonalSource.REDISCOVER).map { MediaId(PersonalSource.KEY, PersonalSource.MIX, it).toString() }
            val shortcuts = wanted.mapNotNull { id -> mixes.firstOrNull { it.mediaId == id } }.map { mix ->
                LibraryNode(id(mix.mediaId, shuffled = false), mix.title, Kind.TRACK, subtitle = mix.subtitle, artwork = mix.artwork)
            }
            return shortcuts + children
        }
        val tracks = children.count { it.playable }
        if (!shufflable(parentId) || tracks < 2) return children
        val first = children.firstOrNull { it.playable && it.artwork != null }
        val shuffle = LibraryNode(id(parentId, shuffled = true), "Riproduci in casuale", Kind.TRACK, subtitle = "$tracks brani", artwork = first?.artwork)
        return listOf(shuffle) + children
    }
}
