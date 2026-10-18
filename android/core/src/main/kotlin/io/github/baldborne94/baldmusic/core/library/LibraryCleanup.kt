package io.github.baldborne94.baldmusic.core.library

import kotlin.math.abs

/** Lo stesso brano in più file: [keep] resta, [others] si possono buttare. */
data class DuplicateGroup(val keep: LocalTrack, val others: List<LocalTrack>)

/**
 * Pulizia della musica sul telefono. Le cartelle sono playlist scaricate, quindi lo
 * stesso brano sta spesso in più cartelle, e certi programmi scaricano l'anteprima di
 * 30 secondi al posto della canzone. Qui solo le regole per trovarli; buttare i file
 * (nel cestino) e aggiornare le raccolte lo fa l'app.
 */
object LibraryCleanup {

    /** Durata di un'anteprima: i servizi di streaming le fanno da 30 secondi. */
    private val PREVIEW_MS = 25_000L..35_000L

    /** Due copie dello stesso brano durano uguale, a meno di qualche secondo di silenzio. */
    private const val SAME_LENGTH_MS = 3_000L

    /** Brani che durano quanto un'anteprima: quasi sempre non sono la canzone intera. */
    fun previews(tracks: List<LocalTrack>): List<LocalTrack> =
        tracks.filter { it.durationMs in PREVIEW_MS }.sortedWith(compareBy({ it.folder.lowercase() }, { it.title.lowercase() }))

    /**
     * Lo stesso brano in più file: stesso titolo e stesso artista (senza badare a
     * maiuscole e accenti) e la stessa durata. Le anteprime non contano: non sono
     * copie della canzone, sono un'altra cosa (vedi [previews]).
     */
    fun duplicates(tracks: List<LocalTrack>): List<DuplicateGroup> =
        tracks.filter { it.durationMs !in PREVIEW_MS && it.durationMs > 0 }
            .groupBy { ArtistNames.key(it.artist.orEmpty()) to ArtistNames.key(it.title) }
            .values
            .flatMap(::byLength)
            .filter { it.size > 1 }
            .map { copies ->
                val keep = copies.maxWith(betterCopy)
                DuplicateGroup(keep, copies.filter { it.id != keep.id }.sortedBy { it.folder.lowercase() })
            }
            .sortedWith(compareBy({ it.keep.artist?.lowercase() }, { it.keep.title.lowercase() }))

    /** Divide i brani con lo stesso nome in gruppi di durata simile (una versione live non è un doppione). */
    private fun byLength(sameName: List<LocalTrack>): List<List<LocalTrack>> {
        val groups = mutableListOf<MutableList<LocalTrack>>()
        sameName.sortedBy { it.durationMs }.forEach { t ->
            val last = groups.lastOrNull()
            if (last != null && abs(t.durationMs - last.first().durationMs) <= SAME_LENGTH_MS) last += t else groups += mutableListOf(t)
        }
        return groups
    }

    /**
     * Quale copia tenere: quella coi tag più completi (riconosciuta da MusicBrainz,
     * con l'artista dell'album, con l'anno), poi quella arrivata per prima.
     */
    private val betterCopy: Comparator<LocalTrack> = compareBy<LocalTrack>(
        { it.albumMbid != null || it.releaseGroupMbid != null },
        { it.albumArtist != null },
        { it.shownYear > 0 },
        { it.album != null },
    ).thenByDescending { it.dateAdded }.thenByDescending { it.id }
}
