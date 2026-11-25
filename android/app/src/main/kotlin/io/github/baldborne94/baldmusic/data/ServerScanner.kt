package io.github.baldborne94.baldmusic.data

import io.github.baldborne94.baldmusic.BaldMusicApp
import io.github.baldborne94.baldmusic.core.subsonic.ServerScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * «Aggiorna libreria» del server (Impostazioni → Server Navidrome; regole in [ServerScan]): Navidrome
 * rilegge la musica del PC, si aspetta la fine e poi l'app rilegge il server (anche in Auto) e
 * riallinea preferiti e playlist. Gira fuori dalla schermata: uscire dalle Impostazioni non lo ferma.
 */
class ServerScanner(private val app: BaldMusicApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class State(val busy: Boolean = false, val message: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(full: Boolean) {
        if (_state.value.busy) return
        val client = app.subsonic() ?: return
        _state.value = State(busy = true, message = "Navidrome sta leggendo la musica…")
        scope.launch {
            val result = runCatching {
                ServerScan.scanAndWait(ServerScan.of(client), full, onProgress = { n ->
                    _state.value = State(busy = true, message = "Navidrome sta leggendo la musica… $n brani")
                })
            }
            result.onSuccess {
                // L'albero del server si rilegge (anche in Android Auto) e i brani nuovi compaiono.
                app.settings.touch()
                _state.value = State(message = "Libreria aggiornata ✓ ($it brani)")
                // I file nuovi sul NAS: si rileggono i brani e le raccolte seguono le cartelle.
                app.serverSync.syncNow(reread = true)
            }.onFailure { e ->
                _state.value = State(message = if (e is ServerScan.Failed) e.message else ServerScan.errorMessage(e))
            }
        }
    }
}
