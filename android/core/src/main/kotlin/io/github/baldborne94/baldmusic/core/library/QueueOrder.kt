package io.github.baldborne94.baldmusic.core.library

/**
 * L'ordine della coda quando tocchi un brano in mezzo a una raccolta o a una playlist: da lì
 * alla fine e poi dall'inizio fino a quello prima. Così la raccolta suona tutta prima che
 * «Continua ad ascoltare» passi ad altro, anche se parti dall'ultimo brano. Gli album restano
 * in ordine dal brano toccato (come un disco).
 */
object QueueOrder {

    /** Gli indici della coda partendo da [start] e ricominciando dall'inizio. */
    fun fromHere(size: Int, start: Int): List<Int> {
        if (start <= 0 || start >= size) return (0 until size).toList()
        return (start until size) + (0 until start)
    }

    /** Le tue raccolte, playlist, preferiti, più ascoltati e mix: sì; album, artisti, cartelle: no. */
    fun wrapsAround(containerId: String?): Boolean {
        val id = MediaId.parse(containerId ?: return false) ?: return false
        return id.source == PersonalSource.KEY && id.kind in WRAPPING
    }

    private val WRAPPING = setOf(PersonalSource.PLAYLIST, PersonalSource.LIKED, PersonalSource.TOP, PersonalSource.MIX)
}
