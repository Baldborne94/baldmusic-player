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

    /** Stesso brano: stesso artista e titolo (senza maiuscole e accenti) e durata quasi uguale. */
    fun sameSong(a: LocalTrack, b: LocalTrack) =
        ArtistNames.key(a.artist.orEmpty()) == ArtistNames.key(b.artist.orEmpty()) &&
            ArtistNames.key(a.title) == ArtistNames.key(b.title) &&
            abs(a.durationMs - b.durationMs) <= SAME_LENGTH_MS

    /**
     * Brani ripescati dal cestino: tornano al loro posto nella raccolta della loro cartella.
     * Se lì c'è la copia che li aveva sostituiti (un doppione da un'altra cartella, messo
     * dalla pulizia), il brano riprende la sua posizione e la copia esce dalla raccolta.
     * Se invece la raccolta non ha sostituti, ci pensa l'allineamento con le cartelle.
     * [all] sono tutti i brani del telefono, ripescati compresi.
     */
    fun restoreInto(lib: UserLibrary, restored: List<LocalTrack>, all: List<LocalTrack>): UserLibrary {
        val byId = all.associateBy { LocalCatalog.ref(it).id }
        var out = lib
        restored.forEach { t ->
            val folder = LocalCatalog.collectionFolder(t.folder) ?: return@forEach
            val ref = LocalCatalog.ref(t)
            out = out.copy(
                playlists = out.playlists.map { pl ->
                    if (pl.folder != folder) return@map pl
                    val substitute = pl.tracks.firstOrNull { r ->
                        val s = byId[r.id]
                        r.id != ref.id && s != null && LocalCatalog.collectionFolder(s.folder) != folder && sameSong(s, t)
                    } ?: return@map pl
                    val tracks = pl.tracks.filter { it.id != ref.id }.toMutableList()
                    tracks[tracks.indexOfFirst { it.id == substitute.id }] = ref
                    pl.copy(tracks = tracks, removed = pl.removed - ref.id)
                },
            )
        }
        return out
    }

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
