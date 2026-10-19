package io.github.baldborne94.baldmusic.core.library

// "Salute della libreria": quali file del telefono hanno i tag da sistemare, cartella
// per cartella, e perché un brano è finito tra i brani sparsi. Non corregge niente:
// la cura vera è passare i file da Picard (guida in server/README.md); qui si dice
// quali file passare.

/** Perché un brano non sta tra gli album. */
enum class LooseReason(val label: String) {
    NO_ALBUM("Senza album"),
    ALONE("Unico brano che hai di quel disco"),
    SINGLE("Singolo"),
}

/** Per [LibraryHealth]: il perché degli sparsi e quanti brani dello stesso disco hai. */
data class AlbumFact(val loose: LooseReason?, val albumSize: Int)

/** Un difetto nei tag di un file, dal più grave. */
enum class TagProblem(val label: String, val hint: String) {
    ALBUM_PER_TRACK(
        "Album diverso per ogni brano",
        "Nella stessa cartella quasi ogni brano ha un album suo (spesso il titolo della canzone): nessun album si forma.",
    ),
    NO_ALBUM("Senza album", "Il file non dice di che disco è."),
    ALONE("Unico brano del suo disco", "Hai un brano solo di quell'album: finisce tra i brani sparsi."),
    NO_TRACK_NUMBER("Senza numero di traccia", "Dentro l'album l'ordine non è quello del disco."),
    SAME_TRACK_NUMBER("Numero di traccia doppio", "Due brani dello stesso disco con lo stesso numero: forse due edizioni mischiate."),
    NO_YEAR("Anno mancante o 1970", "Senza un anno vero il brano non entra nei decenni."),
    NOT_TAGGED("Mai passato da Picard", "Niente ID MusicBrainz: i tag sono quelli del programma da cui è stato scaricato."),
}

data class TrackHealth(val track: LocalTrack, val problems: List<TagProblem>, val loose: LooseReason?) {
    /** "Mai passato da Picard" da solo non è un difetto che si vede: il brano sta bene dov'è. */
    val needsFix get() = problems.any { it != TagProblem.NOT_TAGGED }
}

/** Una cartella (la raccolta, o la cartella del file se è sciolto) con i suoi brani da sistemare. */
data class FolderHealth(val folder: String, val name: String, val total: Int, val tracks: List<TrackHealth>) {
    val ok get() = total - tracks.size
}

data class HealthReport(
    val total: Int,
    /** Brani con almeno un difetto che si vede nell'app. */
    val toFix: Int,
    /** Brani senza ID MusicBrainz: mai passati da Picard. */
    val notTagged: Int,
    /** Brani tra i "singoli e brani sparsi". */
    val loose: Int,
    val counts: Map<TagProblem, Int>,
    /** Dalla cartella con più brani da sistemare. */
    val folders: List<FolderHealth>,
)

object LibraryHealth {

    /** Una cartella dove quasi ogni brano ha un album diverso: almeno 4 brani, 3 su 4 con un album suo. */
    private const val SPLIT_MIN_TRACKS = 4
    private const val SPLIT_SHARE = 0.75

    fun check(tracks: List<LocalTrack>): HealthReport {
        val facts = LocalCatalog.albumFacts(tracks)
        val folderOf = { t: LocalTrack -> LocalCatalog.collectionFolder(t.folder) ?: t.folder }

        // Cartelle in cui l'album cambia da un brano all'altro.
        val split = tracks.groupBy(folderOf).filter { (_, list) ->
            val albums = list.mapNotNull { it.album?.let(ArtistNames::key) }
            albums.size >= SPLIT_MIN_TRACKS && albums.groupingBy { it }.eachCount().count { it.value == 1 } >= albums.size * SPLIT_SHARE
        }.keys

        // Stesso disco, stesso numero: chiave dell'album di Android + disco + traccia.
        val sameNumber = tracks.filter { it.track > 0 && it.album != null }
            .groupBy { Triple(it.albumId, it.disc, it.track) }
            .filter { it.value.size > 1 }
            .values.flatten().mapTo(HashSet()) { it.id }

        val health = tracks.map { t ->
            val fact = facts[t.id]
            val problems = buildList {
                val inSplit = folderOf(t) in split && (fact?.albumSize ?: 0) <= 1 && t.album != null
                if (inSplit) add(TagProblem.ALBUM_PER_TRACK)
                if (t.album.isNullOrBlank()) add(TagProblem.NO_ALBUM)
                if (!inSplit && fact?.loose == LooseReason.ALONE) add(TagProblem.ALONE)
                // Il numero di traccia conta solo dentro un album vero.
                if (fact?.loose == null && t.track <= 0) add(TagProblem.NO_TRACK_NUMBER)
                if (t.id in sameNumber) add(TagProblem.SAME_TRACK_NUMBER)
                if (t.shownYear <= 0 || t.shownYear == 1970) add(TagProblem.NO_YEAR)
                if (t.albumMbid == null && t.releaseGroupMbid == null && t.artistMbids.isEmpty()) add(TagProblem.NOT_TAGGED)
            }
            TrackHealth(t, problems, fact?.loose)
        }

        val folders = health.groupBy { folderOf(it.track) }.map { (folder, list) ->
            val bad = list.filter { it.needsFix }
                .sortedWith(compareBy({ it.problems.first().ordinal }, { it.track.title.lowercase() }))
            FolderHealth(folder, folder.substringAfterLast('/').ifBlank { folder }, list.size, bad)
        }.filter { it.tracks.isNotEmpty() }
            .sortedWith(compareByDescending<FolderHealth> { it.tracks.size }.thenBy { it.name.lowercase() })

        return HealthReport(
            total = tracks.size,
            toFix = health.count { it.needsFix },
            notTagged = health.count { TagProblem.NOT_TAGGED in it.problems },
            loose = health.count { it.loose != null },
            counts = TagProblem.entries.associateWith { p -> health.count { p in it.problems } }.filterValues { it > 0 },
            folders = folders,
        )
    }
}
