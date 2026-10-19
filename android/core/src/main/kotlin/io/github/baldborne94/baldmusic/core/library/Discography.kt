package io.github.baldborne94.baldmusic.core.library

/** Le sezioni della discografia di un artista, in quest'ordine. */
enum class ReleaseKind(val title: String) {
    ALBUM("Album"),
    EP("EP"),
    LIVE("Dal vivo"),
    SOUNDTRACK("Colonne sonore"),
    COMPILATION("Compilation"),
    OTHER("Altro"),
}

/**
 * La discografia divisa per tipo di uscita, dal tipo di MusicBrainz che Picard scrive
 * nei tag (e che Navidrome passa all'app). Senza tag, un disco è un album.
 */
object Discography {

    private val other = setOf("remix", "dj-mix", "mixtape", "mixtape/street", "demo", "interview", "audiobook", "audio drama", "spokenword", "broadcast")

    /** Il tipo di un disco: il tipo secondario conta più di quello primario ("album; live" è dal vivo). */
    fun kindOf(types: List<String>): ReleaseKind {
        val t = types.map { it.trim().lowercase() }.toSet()
        return when {
            "live" in t -> ReleaseKind.LIVE
            "soundtrack" in t -> ReleaseKind.SOUNDTRACK
            "compilation" in t -> ReleaseKind.COMPILATION
            "ep" in t -> ReleaseKind.EP
            t.any { it in other } -> ReleaseKind.OTHER
            else -> ReleaseKind.ALBUM
        }
    }

    /** I dischi per tipo, ognuno dal più recente (a parità di anno, per titolo). */
    fun <T> grouped(items: List<T>, types: (T) -> List<String>, year: (T) -> Int, title: (T) -> String): List<Pair<ReleaseKind, T>> =
        items.sortedWith(compareBy<T> { kindOf(types(it)).ordinal }.thenByDescending(year).thenBy { title(it).lowercase() })
            .map { kindOf(types(it)) to it }
}
