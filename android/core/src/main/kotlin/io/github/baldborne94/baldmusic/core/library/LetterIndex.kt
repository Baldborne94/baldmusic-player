package io.github.baldborne94.baldmusic.core.library

/**
 * Le lettere a lato degli elenchi lunghi (artisti, album, brani): toccando o scorrendo
 * sulla colonna si salta all'intestazione di quella lettera. Le intestazioni sono quelle
 * dei gruppi ([LibraryNode.group]): una prima di ogni elemento il cui gruppo è diverso
 * da quello di prima, come le disegnano l'app e Android Auto.
 */
object LetterIndex {
    /** Sotto questa lunghezza si scorre a mano senza fatica. */
    const val MIN_ITEMS = 40

    /** Una lettera della colonna e la posizione della sua intestazione nell'elenco disegnato. */
    data class Entry(val letter: String, val position: Int)

    /**
     * Le lettere dell'elenco con i gruppi [groups], nell'ordine dell'elenco, con la posizione
     * della loro intestazione: [before] elementi stanno sopra l'elenco, e ogni cambio di
     * gruppo aggiunge un'intestazione. Vuoto se l'elenco è corto o non è diviso per lettera.
     */
    fun entries(groups: List<String?>, before: Int = 0, minItems: Int = MIN_ITEMS): List<Entry> {
        if (groups.size < minItems) return emptyList()
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        var headers = 0
        groups.forEachIndexed { i, group ->
            if (group == null || (i > 0 && groups[i - 1] == group)) return@forEachIndexed
            if (isLetter(group) && seen.add(group)) out += Entry(group, before + i + headers)
            headers++
        }
        return if (out.size >= 2) out else emptyList()
    }

    /** Quante intestazioni dei gruppi [groups] si disegnano: una per ogni cambio di gruppo. */
    fun headers(groups: List<String?>): Int = groups.indices.count { i -> groups[i] != null && (i == 0 || groups[i - 1] != groups[i]) }

    /** La lettera sotto il dito, a [fraction] (0 in cima, 1 in fondo) dell'altezza della colonna. */
    fun at(entries: List<Entry>, fraction: Float): Entry? {
        if (entries.isEmpty()) return null
        val i = (fraction.coerceIn(0f, 1f) * entries.size).toInt().coerceAtMost(entries.lastIndex)
        return entries[i]
    }

    private fun isLetter(group: String) = group.length == 1 && (group[0] in 'A'..'Z' || group[0] == '#')
}
