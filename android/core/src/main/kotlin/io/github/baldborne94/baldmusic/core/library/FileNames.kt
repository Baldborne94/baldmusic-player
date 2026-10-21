package io.github.baldborne94.baldmusic.core.library

/**
 * Il numero di traccia scritto nel nome del file ("03 - Titolo.mp3", "1-03 Titolo.flac"),
 * come ripiego per i file senza il tag: molti brani scaricati sul telefono sono numerati
 * solo così. Si sbaglia meglio per difetto: un numero che fa parte del titolo ("99
 * Luftballons") o dell'artista ("883 - Nord sud ovest est", "50 Cent - …") non vale.
 */
object FileNames {
    data class Number(val track: Int, val disc: Int = 0)

    /** "1-03 Titolo", "2.05 - Titolo", "CD1 03 Titolo": disco e traccia. */
    private val discTrack = Regex("^(?:cd|disc|disco)?\\s*([1-9])[-.](\\d{2})(?!\\d)(?:\\s*[-._)]\\s*|\\s+)(.*)$", RegexOption.IGNORE_CASE)
    private val cdTrack = Regex("^(?:cd|disc|disco)\\s*([1-9])\\s*[-._ ]\\s*(\\d{1,3})(?!\\d)(?:\\s*[-._)]\\s*|\\s+)(.*)$", RegexOption.IGNORE_CASE)

    /** "03 - Titolo", "03. Titolo", "(03) Titolo", "3_Titolo", "03 Titolo", "3 Titolo". */
    private val trackOnly = Regex("^[\\[(]?(\\d{1,3})(?!\\d)[\\])]?(\\s*[-._]\\s*|\\s+)(.*)$")

    /**
     * Il numero nel nome [fileName], o null. [title] e [artists] sono quelli del brano:
     * se il nome comincia con uno dei due, il numero è loro. Senza un titolo vero (Android
     * usa il nome del file quando il tag manca) vale solo un numero sicuro: con lo zero
     * davanti o seguito da un separatore ("03 Titolo", "3 - Titolo"), non "3 Titolo".
     */
    fun number(fileName: String, title: String?, artists: List<String?> = emptyList()): Number? {
        val stem = fileName.substringAfterLast('/').let { if ('.' in it) it.substringBeforeLast('.') else it }.trim()
        val name = norm(stem)
        val realTitle = title?.let(::norm)?.takeIf { it.isNotEmpty() && it != name }
        if (realTitle != null && name.startsWith(realTitle)) return null
        if (artists.any { a -> a?.let(::norm)?.takeIf { it.isNotEmpty() }?.let(name::startsWith) == true }) return null

        (discTrack.find(stem) ?: cdTrack.find(stem))?.let { m ->
            val track = m.groupValues[2].toInt()
            if (track in 1..MAX && hasText(m.groupValues[3])) return Number(track, disc = m.groupValues[1].toInt())
        }
        val m = trackOnly.find(stem) ?: return null
        val digits = m.groupValues[1]
        val track = digits.toInt()
        if (track !in 1..MAX || !hasText(m.groupValues[3])) return null
        val sure = digits.length > 1 && digits[0] == '0' || m.groupValues[2].isNotBlank() || stem[0] == '(' || stem[0] == '['
        return if (sure || realTitle != null) Number(track) else null
    }

    /** Oltre non è un numero di traccia (è un anno, un nome o l'indice di una playlist enorme). */
    private const val MAX = 199

    private fun hasText(rest: String) = rest.any { it.isLetter() }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
