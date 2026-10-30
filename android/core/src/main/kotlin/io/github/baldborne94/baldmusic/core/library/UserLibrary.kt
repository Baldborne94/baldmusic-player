package io.github.baldborne94.baldmusic.core.library

import kotlinx.serialization.Serializable

// La musica "tua": brani che ti piacciono, playlist, cronologia. Vive sul
// telefono (un file JSON) e vale per entrambe le sorgenti: un brano del server e
// uno del telefono possono stare nella stessa playlist.
//
// Qui solo dati e regole pure; il salvataggio su file lo fa l'app.

/** Un brano ricordato: l'ID senza contenitore più quanto serve a mostrarlo. */
@Serializable
data class TrackRef(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artwork: String? = null,
    val durationMs: Long? = null,
)

/** Un album, una playlist o un artista ascoltato di recente. */
@Serializable
data class ContainerRef(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
    val kind: String,
)

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val tracks: List<TrackRef> = emptyList(),
    val createdAt: Long = 0,
    /** Per le raccolte: la cartella del telefono da cui nasce, es. "Music/Movie Soundtracks". */
    val folder: String? = null,
    /** Brani della cartella tolti a mano: non vanno rimessi. */
    val removed: Set<String> = emptySet(),
) {
    val isCollection get() = folder != null
}

/** Una cartella del telefono vista come raccolta: nome e brani nell'ordine in cui sono arrivati. */
data class FolderCollection(val folder: String, val name: String, val tracks: List<TrackRef>)

@Serializable
data class PlayCount(val track: TrackRef, val count: Int, val lastPlayed: Long)

@Serializable
data class UserLibrary(
    /** Dal più recente. */
    val liked: List<TrackRef> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    /** Dal più recente, senza doppioni. */
    val recent: List<ContainerRef> = emptyList(),
    val plays: Map<String, PlayCount> = emptyMap(),
    /** Ultime ricerche, dalla più recente. */
    val searches: List<String> = emptyList(),
    /** Cartelle la cui raccolta è stata eliminata: non si ricrea. */
    val ignoredFolders: Set<String> = emptySet(),
) {
    val collections get() = playlists.filter { it.isCollection }

    /**
     * Allinea le raccolte alle cartelle del telefono: una cartella nuova diventa una
     * raccolta, i brani nuovi di una cartella si aggiungono in fondo, quelli tolti a
     * mano restano fuori e quelli il cui file non c'è più ([present]) spariscono.
     * Le aggiunte a mano da altre cartelle o dal server restano. Restituisce la
     * stessa istanza se non cambia niente.
     */
    fun syncFolders(folders: List<FolderCollection>, present: Set<String>, now: Long, newId: () -> String): UserLibrary {
        val base = followMovedFolders(folders)
        val synced = base.syncExisting(folders, present, now, newId)
        return if (synced === base && base === this) this else synced
    }

    /**
     * Cartelle rinominate o spostate ("Music/Disney" → "Music/Disney Italia"): una raccolta
     * la cui cartella non c'è più segue la cartella nuova che ha almeno metà dei suoi brani,
     * con il suo ordine e il nome (se non l'avevi cambiato). Se Android ha dato ai file ID
     * nuovi (copia e cancella invece di rinominare), i brani si ritrovano per titolo e durata e
     * preferiti, playlist e ascolti passano ai nuovi ID; i brani tolti a mano restano fuori
     * solo se l'ID è lo stesso. Così non resta una raccolta vuota accanto a una nuova.
     */
    private fun followMovedFolders(folders: List<FolderCollection>): UserLibrary {
        val current = folders.mapTo(HashSet()) { it.folder }
        val used = playlists.mapNotNullTo(HashSet()) { it.folder }
        val fresh = folders.filter { it.folder !in used && it.folder !in ignoredFolders && it.tracks.isNotEmpty() }
        val orphans = playlists.filter { it.folder != null && it.folder !in current && it.tracks.any { t -> t.id.startsWith(LOCAL_TRACK) } }
        if (fresh.isEmpty() || orphans.isEmpty()) return this

        // Lo stesso file: stesso ID, o stesso titolo e durata (ID nuovo). Indici per cartella, così
        // con migliaia di brani non si confronta ogni brano con ogni altro.
        class Lookup(f: FolderCollection) {
            val byId = f.tracks.associateBy { it.id }
            val byTitle = f.tracks.groupBy { ArtistNames.key(it.title) }
            fun find(t: TrackRef, title: String): TrackRef? = byId[t.id] ?: byTitle[title]?.firstOrNull { o ->
                t.durationMs != null && o.durationMs != null && kotlin.math.abs(t.durationMs - o.durationMs) <= SAME_FILE_MS
            }
        }
        val lookups = fresh.associateWith(::Lookup)

        // Le coppie raccolta → cartella, dalla più somigliante; ogni cartella ne prende una sola.
        data class Match(val playlist: UserPlaylist, val folder: FolderCollection, val found: Map<String, TrackRef>)
        val matches = orphans.flatMap { pl ->
            val local = pl.tracks.filter { it.id.startsWith(LOCAL_TRACK) }.map { it to ArtistNames.key(it.title) }
            fresh.mapNotNull { f ->
                val lookup = lookups.getValue(f)
                val found = local.mapNotNull { (t, title) -> lookup.find(t, title)?.let { t.id to it } }.toMap()
                Match(pl, f, found).takeIf { found.size * 2 >= local.size }
            }
        }.sortedByDescending { it.found.size }
        val taken = HashSet<String>()
        val claimed = HashSet<String>()
        val moves = matches.filter { m -> m.playlist.id !in taken && m.folder.folder !in claimed }.onEach { m ->
            taken += m.playlist.id
            claimed += m.folder.folder
        }
        if (moves.isEmpty()) return this

        // ID nuovi dei file: valgono per tutta la tua musica, non solo per la raccolta.
        val newIds = moves.flatMap { m -> m.found.filter { (old, new) -> old != new.id }.entries }.associate { it.key to it.value }
        val renamed = mergeTracks(newIds)
        val byPlaylist = moves.associateBy { it.playlist.id }
        return renamed.copy(
            playlists = renamed.playlists.map { pl ->
                val m = byPlaylist[pl.id] ?: return@map pl
                val oldName = pl.folder!!.substringAfterLast('/')
                pl.copy(folder = m.folder.folder, name = if (pl.name == oldName) m.folder.name else pl.name)
            },
        )
    }

    private fun syncExisting(folders: List<FolderCollection>, present: Set<String>, now: Long, newId: () -> String): UserLibrary {
        var changed = false
        val byFolder = folders.associateBy { it.folder }
        val updated = playlists.map { pl ->
            val folder = pl.folder ?: return@map pl
            val source = byFolder[folder]
            val kept = pl.tracks.filter { t -> !t.id.startsWith(LOCAL_TRACK) || t.id in present }
            val have = kept.mapTo(HashSet()) { it.id }
            val added = source?.tracks.orEmpty().filter { it.id !in have && it.id !in pl.removed }
            if (kept.size == pl.tracks.size && added.isEmpty()) pl else {
                changed = true
                pl.copy(tracks = kept + added)
            }
        }
        val known = updated.mapNotNullTo(HashSet()) { it.folder } + ignoredFolders
        val created = folders.filter { it.folder !in known && it.tracks.isNotEmpty() }
            .map { UserPlaylist(newId(), it.name, it.tracks, now, folder = it.folder) }
        if (!changed && created.isEmpty()) return this
        return copy(playlists = updated + created)
    }

    // Si chiede per ogni riga di ogni elenco: un insieme invece di scorrere i preferiti (e non si salva).
    private val likedIds: Set<String> by lazy { liked.mapTo(HashSet()) { it.id } }

    fun isLiked(trackId: String) = baseId(trackId) in likedIds

    fun toggleLike(track: TrackRef): UserLibrary {
        val id = baseId(track.id)
        return if (liked.any { it.id == id }) copy(liked = liked.filterNot { it.id == id })
        else copy(liked = listOf(track.copy(id = id)) + liked)
    }

    /** Un ascolto: conta il brano e mette in cima il contenitore da cui veniva. */
    fun recordPlay(track: TrackRef, container: ContainerRef?, now: Long): UserLibrary {
        val id = baseId(track.id)
        val previous = plays[id]
        val count = PlayCount(track.copy(id = id), (previous?.count ?: 0) + 1, now)
        return copy(plays = trimPlays(plays + (id to count))).touchRecent(container)
    }

    /**
     * Il contenitore va in cima agli «ascoltati di recente» appena cominci ad ascoltarlo,
     * anche prima che l'ascolto conti ([ListeningSession.countsAsPlay]).
     */
    fun touchRecent(container: ContainerRef?): UserLibrary {
        if (container == null || recent.firstOrNull() == container) return this
        return copy(recent = (listOf(container) + recent.filterNot { it.id == container.id }).take(MAX_RECENT))
    }

    /** I brani più ascoltati, a parità di conteggio il più recente prima. */
    fun topTracks(limit: Int = 50): List<TrackRef> =
        plays.values.sortedWith(compareByDescending<PlayCount> { it.count }.thenByDescending { it.lastPlayed })
            .take(limit).map { it.track }

    fun createPlaylist(id: String, name: String, now: Long, first: List<TrackRef> = emptyList()) =
        copy(playlists = playlists + UserPlaylist(id, name.trim().ifEmpty { "La mia playlist" }, first.map { it.copy(id = baseId(it.id)) }.distinctBy { it.id }, now))

    fun renamePlaylist(id: String, name: String) =
        copy(playlists = playlists.map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it })

    /**
     * Toglie una playlist o una raccolta. Una raccolta eliminata non si ricrea dalla sua
     * cartella, tranne con [ignoreFolder] = false: quando se ne sono buttati anche i file,
     * la cartella resta vuota e, se un giorno si riempie, torna giusto che riappaia.
     */
    fun deletePlaylist(id: String, ignoreFolder: Boolean = true) = copy(
        playlists = playlists.filterNot { it.id == id },
        ignoredFolders = if (!ignoreFolder) ignoredFolders else ignoredFolders + listOfNotNull(playlists.firstOrNull { it.id == id }?.folder),
        recent = recent.filterNot { it.id == MediaId(PersonalSource.KEY, PersonalSource.PLAYLIST, id).toString() },
    )

    /**
     * Le cartelle del telefono ([folders]) la cui raccolta hai eliminato e che hanno ancora brani:
     * non si ricreano da sole, ma si possono ricreare a mano ([restoreFolder]).
     */
    fun deletedFolders(folders: List<FolderCollection>): List<FolderCollection> {
        val used = playlists.mapNotNullTo(HashSet()) { it.folder }
        return folders.filter { it.folder in ignoredFolders && it.folder !in used && it.tracks.isNotEmpty() }
    }

    /** Ricrea la raccolta di una cartella eliminata, con tutti i suoi brani: da qui segue di nuovo la cartella. */
    fun restoreFolder(folder: FolderCollection, now: Long, newId: () -> String): UserLibrary {
        val ignored = ignoredFolders - folder.folder
        if (playlists.any { it.folder == folder.folder }) return copy(ignoredFolders = ignored)
        return copy(ignoredFolders = ignored, playlists = playlists + UserPlaylist(newId(), folder.name, folder.tracks, now, folder = folder.folder))
    }

    /** Aggiunge in fondo; un brano già presente non viene duplicato. */
    fun addToPlaylist(id: String, tracks: List<TrackRef>) = copy(
        playlists = playlists.map { pl ->
            if (pl.id != id) pl
            else {
                val present = pl.tracks.map { it.id }.toSet()
                val adding = tracks.map { it.copy(id = baseId(it.id)) }.filter { it.id !in present }.distinctBy { it.id }
                // Un brano rimesso a mano in una raccolta non è più "tolto".
                pl.copy(tracks = pl.tracks + adding, removed = pl.removed - adding.map { it.id }.toSet())
            }
        },
    )

    /** Toglie un brano; da una raccolta lo toglie per sempre, anche se resta nella cartella. */
    fun removeFromPlaylist(id: String, trackId: String) = copy(
        playlists = playlists.map { pl ->
            if (pl.id != id) pl
            else {
                val base = baseId(trackId)
                pl.copy(tracks = pl.tracks.filterNot { it.id == base }, removed = if (pl.isCollection) pl.removed + base else pl.removed)
            }
        },
    )

    /** Brani i cui file sono stati buttati: spariscono da preferiti, playlist, raccolte e ascolti. */
    fun forgetTracks(trackIds: Collection<String>): UserLibrary {
        val gone = trackIds.mapTo(HashSet(), ::baseId)
        if (gone.isEmpty()) return this
        return copy(
            liked = liked.filterNot { it.id in gone },
            playlists = playlists.map { pl -> if (pl.tracks.none { it.id in gone }) pl else pl.copy(tracks = pl.tracks.filterNot { it.id in gone }) },
            plays = plays.filterKeys { it !in gone },
        )
    }

    /**
     * Doppioni buttati: ogni copia tolta ([replacements], dal suo ID alla copia tenuta)
     * è sostituita da quella tenuta in preferiti, playlist e raccolte, senza doppioni e
     * al suo posto; i suoi ascolti si sommano a quelli della copia tenuta. Così una
     * raccolta che aveva la copia buttata continua ad avere il brano.
     */
    fun mergeTracks(replacements: Map<String, TrackRef>): UserLibrary {
        val map = replacements.entries.associate { (from, to) -> baseId(from) to to.copy(id = baseId(to.id)) }
        if (map.isEmpty()) return this
        fun List<TrackRef>.merged() = map { map[it.id] ?: it }.distinctBy { it.id }
        val plays = HashMap<String, PlayCount>()
        this.plays.values.forEach { p ->
            val target = map[p.track.id]
            val id = target?.id ?: p.track.id
            val previous = plays[id]
            plays[id] = if (previous == null) p.copy(track = target ?: p.track)
            else previous.copy(count = previous.count + p.count, lastPlayed = maxOf(previous.lastPlayed, p.lastPlayed))
        }
        return copy(
            liked = liked.merged(),
            playlists = playlists.map { pl -> if (pl.tracks.none { it.id in map }) pl else pl.copy(tracks = pl.tracks.merged()) },
            plays = plays,
        )
    }

    /**
     * Il nuovo ordine dei brani di una playlist o raccolta ([orderedIds], come li ha
     * lasciati chi li ha trascinati). Chi non è nell'elenco resta in fondo, nell'ordine di prima.
     */
    fun reorderPlaylist(id: String, orderedIds: List<String>) = copy(
        playlists = playlists.map { pl ->
            if (pl.id != id) pl
            else {
                val position = orderedIds.map(::baseId).withIndex().associate { (i, t) -> t to i }
                pl.copy(tracks = pl.tracks.withIndex().sortedWith(compareBy({ position[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value })
            }
        },
    )

    /** Ordina una volta per tutte i brani di una playlist o raccolta, per titolo o per artista. */
    fun sortPlaylist(id: String, byArtist: Boolean) = copy(
        playlists = playlists.map { pl ->
            if (pl.id != id) pl
            else pl.copy(
                tracks = pl.tracks.sortedWith(
                    if (byArtist) compareBy({ ArtistNames.key(it.artist.orEmpty()) }, { ArtistNames.key(it.title) })
                    else compareBy { ArtistNames.key(it.title) },
                ),
            )
        },
    )

    fun addSearch(query: String): UserLibrary {
        val q = query.trim()
        if (q.length < 2) return this
        return copy(searches = (listOf(q) + searches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES))
    }

    fun clearSearches() = copy(searches = emptyList())

    private fun trimPlays(map: Map<String, PlayCount>): Map<String, PlayCount> =
        if (map.size <= MAX_PLAYS) map
        else map.values.sortedByDescending { it.lastPlayed }.take(MAX_PLAYS).associateBy { it.track.id }

    companion object {
        const val MAX_RECENT = 20
        const val MAX_SEARCHES = 10
        const val MAX_PLAYS = 2000
        private const val LOCAL_TRACK = "local/track/"

        /** Lo stesso file con un ID nuovo: stesso titolo e la stessa durata, a meno di un paio di secondi. */
        private const val SAME_FILE_MS = 2_000L

        /** L'ID di un brano senza il contenitore da cui è stato scelto. */
        fun baseId(trackId: String): String = MediaId.parse(trackId)?.copy(parent = null)?.toString() ?: trackId
    }
}
