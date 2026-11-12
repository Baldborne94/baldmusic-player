package io.github.baldborne94.baldmusic.core.library

import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlinx.serialization.Serializable

/**
 * «Mi piace» e playlist uguali sul telefono e su Navidrome (quindi anche nell'app web): un cuore
 * messo in macchina compare sul PC e viceversa. Si confronta con com'erano all'ultimo
 * allineamento ([SyncState]), così si capisce chi ha aggiunto e chi ha tolto, da una parte o
 * dall'altra; senza stato (la prima volta) si unisce e basta, non si toglie niente.
 *
 * Lo stesso brano si riconosce anche se sul telefono è un file e sul server un altro
 * ([Matcher]). Le raccolte restano di ogni dispositivo (le cartelle sono diverse): non si
 * allineano.
 */
object ServerSync {
    /** Stesso brano: stessa durata entro così. */
    const val SAME_SONG_MS = 3_000L

    /** Riconosce lo stesso brano tra telefono e server: ID del server, o artista + titolo e durata entro [SAME_SONG_MS]. */
    class Matcher(server: List<Song>, phone: List<TrackRef>) {
        private val byId = server.associateBy { it.id }
        private val serverByKey = server.groupBy { key(it.artist, it.title) }
        private val phoneByKey = phone.filter { MediaId.parse(it.id)?.source == LocalCatalog.KEY }.groupBy { key(it.artist, it.title) }

        /** Il server ha questo brano (per ID); senza l'elenco dei brani si crede all'ID. */
        fun has(serverId: String) = byId.isEmpty() || serverId in byId

        /**
         * L'ID del server per un brano del telefono (o del server), null se il server non lo ha.
         * Un brano di un altro server (il PC prima del NAS) ha un ID che qui non c'è: si ritrova
         * come i file, per artista, titolo e durata.
         */
        fun toServer(t: TrackRef): String? {
            val id = MediaId.parse(t.id)
            if (id?.source == RemoteSource.KEY && id.id != null && has(id.id)) return id.id
            return sameSong(t.artist, t.title, t.durationMs)?.id
        }

        /** Lo stesso brano su questo server: artista principale e titolo, durata entro [SAME_SONG_MS]. */
        fun sameSong(artist: String?, title: String, durationMs: Long?): Song? =
            closest(serverByKey[key(artist, title)], durationMs) { s -> s.duration?.let { it * 1000L } }

        /** Il brano del server sul telefono: il file uguale se c'è (si ascolta senza rete), se no dal server. */
        fun toPhone(serverId: String): TrackRef? {
            val s = byId[serverId] ?: return null
            val durationMs = s.duration?.let { it * 1000L }
            return closest(phoneByKey[key(s.artist, s.title)], durationMs) { it.durationMs } ?: remoteRef(s)
        }

        private fun <T> closest(candidates: List<T>?, durationMs: Long?, duration: (T) -> Long?): T? {
            if (candidates.isNullOrEmpty()) return null
            if (durationMs == null) return candidates.singleOrNull()
            return candidates
                .mapNotNull { c -> duration(c)?.let { c to kotlin.math.abs(it - durationMs) } }
                .filter { it.second <= SAME_SONG_MS }
                .minByOrNull { it.second }?.first
        }

        companion object {
            /** Artista principale e titolo, senza maiuscole, accenti e ospiti ("A feat. B" è di A). */
            fun key(artist: String?, title: String): String {
                val main = artist?.let { ArtistNames.split(it).firstOrNull() }.orEmpty()
                return ArtistNames.key(main) + "\u0000" + ArtistNames.key(title)
            }

            fun remoteRef(s: Song) = TrackRef(
                id = MediaId.track(RemoteSource.KEY, s.id, null).toString(),
                title = s.title,
                artist = s.artist,
                album = s.album,
                artwork = s.coverArt?.let { "${RemoteSource.KEY}/$it" },
                durationMs = s.duration?.let { it * 1000L },
            )
        }
    }

    /** Una playlist del server come serve qui: i brani per ID, nell'ordine. */
    data class ServerList(val id: String, val name: String, val songs: List<String>)

    /** Cosa scrivere sul server. */
    sealed interface Write {
        data class Create(val phoneId: String, val name: String, val songs: List<String>) : Write
        data class Replace(val serverId: String, val name: String?, val songs: List<String>) : Write
        data class Delete(val serverId: String) : Write
    }

    data class Plan(
        val star: Set<String>,
        val unstar: Set<String>,
        /** La tua musica dopo l'allineamento (uguale a prima se dal server non arriva niente). */
        val library: UserLibrary,
        val writes: List<Write>,
        /** Lo stato da salvare; le playlist create sul server si aggiungono con [SyncState.created]. */
        val state: SyncState,
    )

    fun plan(
        lib: UserLibrary,
        starred: Set<String>,
        lists: List<ServerList>,
        state: SyncState,
        match: Matcher,
        newId: () -> String,
        now: Long,
    ): Plan {
        // «Mi piace»: quello che c'era e una parte ha tolto esce, il resto si unisce.
        val phoneKeys = lib.liked.mapNotNull { t -> match.toServer(t)?.let { it to t } }.toMap(LinkedHashMap())
        val base = state.liked
        val removed = if (base == null) emptySet() else (base - phoneKeys.keys) + (base - starred)
        val final = (phoneKeys.keys + starred) - removed
        var library = lib
        val unlike = phoneKeys.filterKeys { it !in final }.values.map { it.id }.toSet()
        if (unlike.isNotEmpty()) library = library.copy(liked = library.liked.filterNot { it.id in unlike })
        val likeNew = (starred - phoneKeys.keys).filter { it in final }.mapNotNull(match::toPhone)
            .filter { ref -> library.liked.none { it.id == ref.id } }
        if (likeNew.isNotEmpty()) library = library.copy(liked = likeNew + library.liked)

        // Playlist (non le raccolte): unite col loro collegamento, o per nome la prima volta.
        val writes = ArrayList<Write>()
        val links = ArrayList<PlaylistLink>()
        val serverById = lists.associateBy { it.id }
        val mine = library.playlists.filterNot { it.isCollection }
        val linkedPhone = HashSet<String>()
        val linkedServer = HashSet<String>()

        fun merge(phone: UserPlaylist, server: ServerList, link: PlaylistLink?) {
            linkedPhone += phone.id
            linkedServer += server.id
            val p = phone.tracks.mapNotNull(match::toServer).distinct()
            val s = server.songs.distinct()
            val b = link?.songs
            val merged = when {
                b == null -> p + (s - p.toSet())
                p == b && s == b -> b
                p == b -> s
                s == b -> p
                else -> {
                    val removedServer = (b - s.toSet()).toSet()
                    val kept = p.filterNot { it in removedServer }
                    kept + s.filter { it !in b.toSet() && it !in kept.toSet() }
                }
            }
            val name = if (link != null && phone.name != link.name) phone.name else server.name.ifBlank { phone.name }
            if (merged != s || name != server.name) writes += Write.Replace(server.id, name.takeIf { it != server.name }, merged)
            if (merged != p || name != phone.name) {
                library = library.copy(playlists = library.playlists.map { pl ->
                    if (pl.id != phone.id) pl else pl.copy(name = name, tracks = rebuild(pl.tracks, merged, match))
                })
            }
            links += PlaylistLink(phone.id, server.id, name, merged)
        }

        for (link in state.playlists) {
            val phone = mine.firstOrNull { it.id == link.phoneId }
            val server = serverById[link.serverId]
            when {
                phone != null && server != null -> merge(phone, server, link)
                // Eliminata da una parte: via anche dall'altra.
                phone == null && server != null -> {
                    linkedServer += server.id
                    writes += Write.Delete(server.id)
                }
                phone != null && server == null -> {
                    linkedPhone += phone.id
                    library = library.deletePlaylist(phone.id)
                }
            }
        }
        // La prima volta: le playlist con lo stesso nome sono la stessa.
        for (phone in mine.filter { it.id !in linkedPhone }) {
            val server = lists.firstOrNull { it.id !in linkedServer && ArtistNames.key(it.name) == ArtistNames.key(phone.name) }
            if (server != null) merge(phone, server, null)
        }
        // Solo sul telefono: si crea sul server. Solo sul server: si crea sul telefono.
        for (phone in library.playlists.filter { !it.isCollection && it.id !in linkedPhone }) {
            writes += Write.Create(phone.id, phone.name, phone.tracks.mapNotNull(match::toServer).distinct())
        }
        for (server in lists.filter { it.id !in linkedServer }) {
            val id = newId()
            library = library.copy(playlists = library.playlists + UserPlaylist(id, server.name, server.songs.mapNotNull(match::toPhone).distinctBy { it.id }, now))
            links += PlaylistLink(id, server.id, server.name, server.songs.distinct())
        }
        return Plan(
            star = final - starred,
            unstar = starred.intersect(removed),
            library = library,
            writes = writes,
            state = state.copy(liked = final, playlists = links),
        )
    }

    /** Ascolti da mandare al server e ascolti fatti altrove da mettere sul telefono. */
    data class PlaysPlan(
        /** Brano del server e momento dell'ascolto, uno per ascolto fatto dal telefono e non ancora mandato. */
        val scrobbles: List<Pair<String, Long>>,
        /** Quanti ascolti del telefono ha già il server, per brano: da salvare in [SyncState.pushed]. */
        val pushed: Map<String, Int>,
        /** Gli ascolti fatti altrove (app web), per [UserLibrary.otherPlays]. */
        val others: Map<String, PlayCount>,
    )

    /** Al massimo così tanti ascolti dello stesso brano mandati in un giro. */
    const val MAX_SCROBBLES_PER_SONG = 50

    /**
     * Statistiche unite: gli ascolti del telefono vanno al server (così l'app web li conta, anche
     * quelli fatti in macchina senza rete) e quelli del server che non vengono dal telefono
     * diventano [UserLibrary.otherPlays]. [server] = i brani col loro `playCount` letto prima di
     * mandare; [pushedBefore] null = la prima volta: gli ascolti di brani del server fatti dal
     * telefono il server li ha già (li registrava mentre suonavano), quelli dei file no.
     */
    fun planPlays(lib: UserLibrary, server: List<Song>, pushedBefore: Map<String, Int>?, match: Matcher): PlaysPlan {
        val phone = HashMap<String, Int>()
        val last = HashMap<String, Long>()
        val viaServer = HashMap<String, Int>()
        for (p in lib.plays.values) {
            val sid = match.toServer(p.track) ?: continue
            phone[sid] = (phone[sid] ?: 0) + p.count
            last[sid] = maxOf(last[sid] ?: 0L, p.lastPlayed)
            if (MediaId.parse(p.track.id)?.source == RemoteSource.KEY) viaServer[sid] = (viaServer[sid] ?: 0) + p.count
        }
        val base = pushedBefore ?: viaServer
        val scrobbles = ArrayList<Pair<String, Long>>()
        val pushed = HashMap(base)
        for ((sid, count) in phone) {
            val missing = count - (base[sid] ?: 0)
            if (missing <= 0) continue
            repeat(missing.coerceAtMost(MAX_SCROBBLES_PER_SONG)) { scrobbles += sid to (last[sid] ?: 0L) }
            pushed[sid] = count
        }
        val others = HashMap<String, PlayCount>()
        for (s in server) {
            val elsewhere = (s.playCount ?: 0) - (base[s.id] ?: 0)
            if (elsewhere <= 0) continue
            val ref = match.toPhone(s.id) ?: continue
            val id = UserLibrary.baseId(ref.id)
            val previous = others[id]
            val played = s.played?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
            others[id] = if (previous == null) PlayCount(ref.copy(id = id), elsewhere, played)
            else previous.copy(count = previous.count + elsewhere, lastPlayed = maxOf(previous.lastPlayed, played))
        }
        return PlaysPlan(scrobbles, pushed, others)
    }

    /**
     * I brani di una playlist del telefono dopo l'unione: quelli del server nell'ordine unito
     * (il file del telefono se c'è), e quelli che il server non ha restano dov'erano.
     */
    private fun rebuild(tracks: List<TrackRef>, merged: List<String>, match: Matcher): List<TrackRef> {
        val byServer = HashMap<String, TrackRef>()
        val phoneOnly = ArrayList<IndexedValue<TrackRef>>()
        tracks.forEachIndexed { i, t ->
            val sid = match.toServer(t)
            if (sid == null) phoneOnly += IndexedValue(i, t) else byServer.putIfAbsent(sid, t)
        }
        val out = merged.mapNotNull { byServer[it] ?: match.toPhone(it) }.toMutableList()
        for ((i, t) in phoneOnly) out.add(i.coerceAtMost(out.size), t)
        return out.distinctBy { it.id }
    }
}

/** Com'erano «mi piace» e playlist all'ultimo allineamento con un server (ID del server). */
@Serializable
data class SyncState(
    /** null = mai allineati: la prima volta si unisce soltanto. */
    val liked: Set<String>? = null,
    val playlists: List<PlaylistLink> = emptyList(),
    /** Quanti ascolti del telefono ha già il server, per brano del server (null = mai mandati). */
    val pushed: Map<String, Int>? = null,
) {
    /** Una playlist del telefono appena creata sul server ([ServerSync.Write.Create]). */
    fun created(phoneId: String, serverId: String, name: String, songs: List<String>) =
        copy(playlists = playlists + PlaylistLink(phoneId, serverId, name, songs))
}

@Serializable
data class PlaylistLink(val phoneId: String, val serverId: String, val name: String, val songs: List<String>)

/**
 * Cambio di server (dal PC al NAS): i brani del server di prima salvati sul telefono (preferiti,
 * playlist, ascolti) hanno ID che il server nuovo non ha. Qui, per ognuno, lo stesso brano sul
 * server nuovo (o il file del telefono, se c'è): da passare a [UserLibrary.mergeTracks], che
 * sposta anche gli ascolti. Quelli che il server nuovo non ha restano come sono.
 */
fun ServerSync.Matcher.moved(lib: UserLibrary): Map<String, TrackRef> =
    (lib.liked + lib.playlists.flatMap { it.tracks } + lib.plays.values.map { it.track })
        .filter { t -> MediaId.parse(t.id)?.let { it.source == RemoteSource.KEY && it.id != null && !has(it.id) } == true }
        .distinctBy { it.id }
        .mapNotNull { t -> toServer(t)?.let(::toPhone)?.let { t.id to it } }
        .toMap()
