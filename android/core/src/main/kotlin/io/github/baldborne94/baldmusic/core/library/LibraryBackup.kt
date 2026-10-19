package io.github.baldborne94.baldmusic.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

// Backup della tua musica: preferiti, playlist, raccolte, ascolti e ricerche in un file
// che sopravvive alla disinstallazione e si porta su un telefono nuovo. I brani del
// telefono hanno l'ID di MediaStore, che su un altro telefono (o dopo aver ricopiato i
// file) cambia: per questo il backup ricorda per ognuno cartella, titolo, artista e
// durata, e al ripristino lo ritrova tra i file che ci sono.

/** Come riconoscere un file del telefono quando il suo ID non vale più. */
@Serializable
data class FileKey(val folder: String, val title: String, val artist: String? = null, val durationMs: Long = 0)

@Serializable
data class Backup(
    val app: String = APP,
    val format: Int = FORMAT,
    val createdAt: Long = 0,
    val library: UserLibrary = UserLibrary(),
    /** Per ID di brano del telefono. */
    val files: Map<String, FileKey> = emptyMap(),
) {
    companion object {
        const val APP = "BaldMusic"
        const val FORMAT = 1
    }
}

/** Cosa farebbe il ripristino: la libreria pronta e quanti brani del telefono si ritrovano. */
data class RestorePlan(
    val library: UserLibrary,
    /** Brani del telefono nel backup. */
    val localTracks: Int,
    val found: Int,
    /** Quelli che non ci sono più: escono da preferiti e playlist. */
    val missing: List<TrackRef>,
)

object LibraryBackup {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Stessa durata, a meno di qualche secondo (un file ricodificato o ritagliato). */
    private const val SAME_LENGTH_MS = 3_000

    private const val LOCAL_TRACK = "${LocalCatalog.KEY}/${MediaId.TRACK}/"

    private fun isLocal(id: String) = id.startsWith(LOCAL_TRACK)

    /** Tutti i brani di cui la libreria si ricorda. */
    private fun refs(lib: UserLibrary): List<TrackRef> =
        lib.liked + lib.playlists.flatMap { it.tracks } + lib.plays.values.map { it.track }

    /** I brani tolti a mano dalle raccolte: non stanno nella libreria, ma vanno ritrovati anche loro. */
    private fun removed(lib: UserLibrary): List<String> = lib.playlists.flatMap { it.removed }

    fun create(lib: UserLibrary, tracks: List<LocalTrack>, now: Long): Backup {
        val byId = tracks.associateBy { LocalCatalog.ref(it).id }
        val files = (refs(lib).map { it.id } + removed(lib)).filter(::isLocal).distinct().mapNotNull { id ->
            byId[id]?.let { t -> id to FileKey(t.folder, t.title, t.artist, t.durationMs) }
        }.toMap()
        return Backup(createdAt = now, library = lib, files = files)
    }

    fun encode(backup: Backup): String = json.encodeToString(Backup.serializer(), backup)

    /** null se non è un backup di BaldMusic (o è di una versione più nuova che non si sa leggere). */
    fun decode(text: String): Backup? =
        runCatching { json.decodeFromString(Backup.serializer(), text) }.getOrNull()?.takeIf { it.app == Backup.APP && it.format <= Backup.FORMAT }

    fun restore(backup: Backup, tracks: List<LocalTrack>): RestorePlan {
        val lib = backup.library
        val byId = tracks.associateBy { LocalCatalog.ref(it).id }
        val byFolder = tracks.groupBy { it.folder to ArtistNames.key(it.title) }
        val byName = tracks.groupBy { ArtistNames.key(it.artist.orEmpty()) to ArtistNames.key(it.title) }

        fun closest(list: List<LocalTrack>?, durationMs: Long): LocalTrack? =
            list?.filter { durationMs <= 0 || abs(it.durationMs - durationMs) <= SAME_LENGTH_MS }
                ?.minByOrNull { abs(it.durationMs - durationMs) }

        fun match(id: String): LocalTrack? {
            val key = backup.files[id]
            val same = byId[id]?.takeIf { key == null || ArtistNames.key(it.title) == ArtistNames.key(key.title) }
            return same
                ?: key?.let { closest(byFolder[it.folder to ArtistNames.key(it.title)], it.durationMs) }
                ?: key?.let { closest(byName[ArtistNames.key(it.artist.orEmpty()) to ArtistNames.key(it.title)], it.durationMs) }
        }

        val ids = refs(lib).map { it.id }.filter(::isLocal).distinct()
        val replace = HashMap<String, TrackRef>()
        val gone = ArrayList<String>()
        for (id in ids) {
            val match = match(id)
            when {
                match == null -> gone += id
                LocalCatalog.ref(match).id != id -> replace[id] = LocalCatalog.ref(match)
            }
        }

        // I brani tolti a mano da una raccolta restano fuori anche col nuovo ID. Senza la loro
        // chiave (backup di prima) restano come sono; con la chiave e non ritrovati escono, così
        // il vecchio ID non tiene fuori un altro brano che ora ha quell'ID.
        fun removedNow(id: String): String? = when {
            !isLocal(id) -> id
            backup.files[id] == null -> replace[id]?.id ?: id
            else -> match(id)?.let { LocalCatalog.ref(it).id }
        }
        val remapped = lib.copy(
            playlists = lib.playlists.map { pl ->
                if (pl.removed.isEmpty()) pl else pl.copy(removed = pl.removed.mapNotNullTo(HashSet(), ::removedNow))
            },
        )
        // Prima si tolgono quelli spariti (vecchi ID), poi si cambiano gli ID: un ID nuovo può
        // coincidere con il vecchio ID di un altro brano sparito.
        val restored = remapped.forgetTracks(gone).mergeTracks(replace)
        val byOldId = refs(lib).associateBy { it.id }
        return RestorePlan(
            library = restored,
            localTracks = ids.size,
            found = ids.size - gone.size,
            missing = gone.mapNotNull { byOldId[it] },
        )
    }
}
