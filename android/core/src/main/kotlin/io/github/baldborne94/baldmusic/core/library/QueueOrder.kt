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

    /**
     * I brani aggiunti tra la coda di prima ([old]) e quella di adesso ([new]): gli indici nuovi,
     * se [new] è [old] con un blocco in più in un punto solo; null altrimenti (coda nuova, brani
     * tolti o spostati).
     */
    fun insertion(old: List<String>, new: List<String>): IntRange? {
        val added = new.size - old.size
        if (added <= 0 || old.isEmpty()) return null
        var at = 0
        while (at < old.size && old[at] == new[at]) at++
        for (i in at until old.size) if (old[i] != new[i + added]) return null
        return at until at + added
    }

    /**
     * Col casuale il lettore mette i brani aggiunti in punti a caso dell'ordine. Qui vanno dove li
     * hai chiesti: subito dopo quello in corso («Riproduci dopo», aggiunti in [current] + 1) o in
     * fondo («Aggiungi in coda»). [order] è l'ordine del casuale di adesso, già con i brani nuovi.
     */
    fun shuffleAfterInsert(order: List<Int>, inserted: IntRange, current: Int): List<Int> {
        val rest = order.filter { it !in inserted }
        val added = inserted.toList()
        if (inserted.first != current + 1) return rest + added
        val at = rest.indexOf(current)
        if (at < 0) return rest + added
        return rest.subList(0, at + 1) + added + rest.subList(at + 1, rest.size)
    }
}
