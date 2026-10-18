package io.github.baldborne94.baldmusic.core.library

import java.text.Normalizer
import java.util.Locale

/** Come si confrontano e si separano i nomi degli artisti scritti nei tag. */
object ArtistNames {

    /** L'artista "Various Artists" di MusicBrainz: chi lo ha come artista dell'album ha una raccolta. */
    const val VARIOUS_MBID = "89ad4ac3-39f7-470e-963a-56509c546377"

    private val variousNames = setOf("various artists", "various", "artisti vari", "autori vari", "va", "v.a.", "aa.vv.", "aa vv", "aavv")

    /** Due nomi sono lo stesso artista se coincidono a meno di maiuscole, spazi, accenti e apostrofi. */
    fun key(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .replace('’', '\'').replace('‘', '\'').replace('´', '\'').replace('`', '\'')
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    fun isVarious(name: String?) = name != null && key(name) in variousNames

    private val featuring = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)
    private val spacedSeparators = Regex("\\s*;\\s*|\\s+/\\s+")
    private val inTitle = Regex("[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+([^)\\]]+)[)\\]]", RegexOption.IGNORE_CASE)

    /**
     * Gli artisti contenuti in un nome come "A feat. B", "A; B", "A / B" o "A/B".
     * Si separa solo dove non ci sono dubbi: "&", "e" e la virgola restano (Simon &
     * Garfunkel, Earth, Wind & Fire). La barra senza spazi separa solo se il nome intero
     * non è noto come artista ([known], es. "AC/DC" come artista di un album) e se i
     * pezzi non sono sigle di una o due lettere.
     */
    fun split(name: String, known: Set<String> = emptySet()): List<String> {
        if (key(name) in known) return listOf(name.trim())
        return name.split(featuring).flatMap { it.split(spacedSeparators) }.flatMap { part ->
            val pieces = part.split('/').map { it.trim() }
            if (pieces.size > 1 && key(part) !in known && pieces.all { it.length > 2 }) pieces else listOf(part.trim())
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::key)
    }

    /** Gli ospiti scritti nel titolo: "Let Me Go (feat. Chad Kroeger)" → Chad Kroeger. */
    fun featuredInTitle(title: String): List<String> = inTitle.findAll(title)
        .flatMap { m -> m.groupValues[1].split(Regex("\\s*(?:,|&|\\band\\b|\\be\\b)\\s*")) }
        .map { it.trim() }.filter { it.isNotEmpty() }.toList()
}
