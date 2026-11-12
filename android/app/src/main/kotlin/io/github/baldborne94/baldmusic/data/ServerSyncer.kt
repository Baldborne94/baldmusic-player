package io.github.baldborne94.baldmusic.data

import android.util.Log
import io.github.baldborne94.baldmusic.BaldMusicApp
import io.github.baldborne94.baldmusic.core.library.ArtistNames
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.ServerSync
import io.github.baldborne94.baldmusic.core.library.SyncState
import io.github.baldborne94.baldmusic.core.library.moved
import io.github.baldborne94.baldmusic.core.subsonic.Song
import io.github.baldborne94.baldmusic.core.subsonic.md5Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Tiene «mi piace» e playlist uguali sul telefono e su Navidrome (le regole in [ServerSync]):
 * all'avvio, 20 s dopo ogni cambio di cuori o playlist sul telefono e ogni mezz'ora (per quelli
 * fatti dal PC). Senza rete o senza server non fa niente e riprova al giro dopo. Lo stato
 * dell'ultimo allineamento sta in un file per server e utente.
 */
class ServerSyncer(private val app: BaldMusicApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Tutti i brani del server, letti al massimo ogni mezz'ora (per riconoscere i brani). */
    private var songs: Triple<String, Long, List<Song>>? = null

    private val _status = MutableStateFlow<String?>(null)
    /** Com'è andato l'ultimo allineamento, per le Impostazioni. */
    val status: StateFlow<String?> = _status.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            app.userData.state
                .map { lib -> lib.liked.map { it.id } to lib.playlists.filterNot { it.isCollection }.map { it.id to it.name to it.tracks.map { t -> t.id } } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(CHANGE_WAIT_MS)
                .collect { syncNow() }
        }
        scope.launch {
            delay(START_WAIT_MS)
            while (true) {
                syncNow()
                delay(EVERY_MS)
            }
        }
    }

    /** Allinea adesso (anche dal tasto nelle Impostazioni); un allineamento alla volta. */
    suspend fun syncNow(): Unit = mutex.withLock {
        if (!app.settings.syncWithServer) return
        val creds = app.settings.credentials ?: return
        val client = app.subsonic() ?: return
        runCatching {
            var before = app.userData.value
            val starred = client.starred().song.map { it.id }.toSet()
            val lists = client.playlists()
                .filter { (it.owner == null || it.owner == creds.user) && !it.isAppData && !it.isPcCollection }
                .map { pl -> ServerSync.ServerList(pl.id, pl.name, client.playlist(pl.id).entry.map { it.id }) }
            val now = System.currentTimeMillis()
            val server = "${creds.url}|${creds.user}"
            val known = songs?.takeIf { it.first == server && now - it.second < EVERY_MS }?.third
            val needed = starred + lists.flatMap { it.songs }
            // Brani del server riletti adesso: anche gli ascolti (servono i playCount di adesso).
            val fresh = known == null || !known.map { it.id }.toSet().containsAll(needed)
            val all = if (!fresh) known!! else client.allSongs().also { songs = Triple(server, now, it) }
            val phone = app.local.allTracks().map(LocalCatalog::ref)
            val file = stateFile(creds.url, creds.user)
            val state = runCatching { json.decodeFromString(SyncState.serializer(), file.readText()) }.getOrDefault(SyncState())
            val match = ServerSync.Matcher(all, phone)
            // Cambio di server (dal PC al NAS): i brani del server di prima passano allo stesso
            // brano sul nuovo, anche quelli scaricati (i file si rinominano, non si riscaricano).
            var moved = false
            if (fresh) {
                val moves = match.moved(before)
                if (moves.isNotEmpty()) {
                    app.userData.mergeTracks(moves)
                    before = app.userData.value
                    moved = true
                }
                app.serverDownloads.moveTo(match, lists.associate { ArtistNames.key(it.name) to it.id })
            }
            val plan = ServerSync.plan(before, starred, lists, state, match, { UUID.randomUUID().toString().take(8) }, now)
            // Statistiche unite: gli ascolti del telefono al server, quelli fatti dal PC sul telefono.
            // Su un server nuovo gli ascolti fatti col server di prima non ci sono: si mandano tutti.
            val plays = if (fresh) ServerSync.planPlays(before, all, state.pushed ?: emptyMap<String, Int>().takeIf { moved }, match) else null
            if (plays != null) {
                client.scrobbleAll(plays.scrobbles)
                // Mandati: si ricorda subito, qualunque cosa succeda dopo (non vanno rimandati).
                file.writeText(json.encodeToString(SyncState.serializer(), state.copy(pushed = plays.pushed)))
            }

            client.star(plan.star, true)
            client.star(plan.unstar, false)
            var saved = plan.state.copy(pushed = plays?.pushed ?: state.pushed)
            for (w in plan.writes) when (w) {
                is ServerSync.Write.Create -> saved = saved.created(w.phoneId, client.createPlaylist(w.name, w.songs).id, w.name, w.songs)
                is ServerSync.Write.Replace -> client.replacePlaylist(w.serverId, w.name, w.songs)
                is ServerSync.Write.Delete -> client.deletePlaylist(w.serverId)
            }
            // Nel frattempo hai cambiato cuori o playlist: lo stato resta quello di prima e si rifà al giro dopo.
            val next = plays?.let { plan.library.copy(otherPlays = it.others) } ?: plan.library
            if (next != before && !app.userData.applySync(before, next)) return@runCatching
            file.writeText(json.encodeToString(SyncState.serializer(), saved))
            val changes = plan.star.size + plan.unstar.size + plan.writes.size + (if (plan.library != before) 1 else 0)
            _status.value = if (changes == 0) "Allineati ✓" else "Allineati ✓ (cambiati ${plan.star.size + plan.unstar.size} «mi piace» e ${plan.writes.size} playlist sul server)"
        }.onFailure {
            Log.w(TAG, "Allineamento col server non riuscito", it)
            _status.value = "Non allineati: ${it.message ?: "server non raggiungibile"}"
        }
    }

    private fun stateFile(url: String, user: String) = File(app.filesDir, "allineamento-${md5Hex("$url|$user").take(12)}.json")

    private companion object {
        const val TAG = "ServerSyncer"
        const val CHANGE_WAIT_MS = 20_000L
        const val START_WAIT_MS = 15_000L
        const val EVERY_MS = 30 * 60_000L
    }
}
