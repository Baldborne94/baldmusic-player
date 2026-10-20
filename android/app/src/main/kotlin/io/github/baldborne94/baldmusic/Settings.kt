package io.github.baldborne94.baldmusic

import android.content.Context
import androidx.core.content.edit
import io.github.baldborne94.baldmusic.core.library.Crossfade
import io.github.baldborne94.baldmusic.core.library.ReplayGainMode
import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preferenze dell'app. [changes] avvisa il servizio quando la libreria va ricaricata. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Cerca i testi dei brani su internet (LRCLIB). */
    var lyricsOnline: Boolean
        get() = prefs.getBoolean(KEY_LYRICS, true)
        set(value) {
            prefs.edit { putBoolean(KEY_LYRICS, value) }
        }

    private val _replayGain = MutableStateFlow(
        prefs.getString(KEY_REPLAY_GAIN, null)?.let { runCatching { ReplayGainMode.valueOf(it) }.getOrNull() } ?: ReplayGainMode.ALBUM,
    )

    /** Volume uguale tra i brani (ReplayGain): il servizio lo segue mentre suona. */
    val replayGain: StateFlow<ReplayGainMode> = _replayGain.asStateFlow()

    fun setReplayGain(mode: ReplayGainMode) {
        prefs.edit { putString(KEY_REPLAY_GAIN, mode.name) }
        _replayGain.value = mode
    }

    private val _crossfade = MutableStateFlow(
        prefs.getInt(KEY_CROSSFADE, Crossfade.DEFAULT_SECONDS).takeIf { it in Crossfade.CHOICES } ?: Crossfade.DEFAULT_SECONDS,
    )

    /** Dissolvenza incrociata tra i brani, in secondi; 0 = spenta. */
    val crossfade: StateFlow<Int> = _crossfade.asStateFlow()

    fun setCrossfade(seconds: Int) {
        prefs.edit { putInt(KEY_CROSSFADE, seconds) }
        _crossfade.value = seconds
    }

    /** La chiave dell'applicazione su AcoustID (acoustid.org/new-application) per riconoscere i brani dal suono. */
    var acoustIdKey: String?
        get() = prefs.getString(KEY_ACOUSTID, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit { if (value.isNullOrBlank()) remove(KEY_ACOUSTID) else putString(KEY_ACOUSTID, value.trim()) }
        }

    /** Perché l'ultimo riconoscimento si è fermato (chiave sbagliata, niente rete); null se è andato bene. */
    var recognitionProblem: String?
        get() = prefs.getString(KEY_RECOGNITION_PROBLEM, null)
        set(value) {
            prefs.edit { if (value == null) remove(KEY_RECOGNITION_PROBLEM) else putString(KEY_RECOGNITION_PROBLEM, value) }
        }

    /** Qualcosa è cambiato (server, permessi): la libreria va riletta. */
    fun touch() {
        _changes.value++
    }

    var credentials: Credentials?
        get() {
            val url = prefs.getString(KEY_URL, null) ?: return null
            val user = prefs.getString(KEY_USER, null) ?: return null
            val salt = prefs.getString(KEY_SALT, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            return Credentials(url, user, salt, token)
        }
        set(value) {
            prefs.edit {
                if (value == null) {
                    remove(KEY_URL); remove(KEY_USER); remove(KEY_SALT); remove(KEY_TOKEN)
                } else {
                    putString(KEY_URL, value.url)
                    putString(KEY_USER, value.user)
                    putString(KEY_SALT, value.salt)
                    putString(KEY_TOKEN, value.token)
                }
            }
            touch()
        }

    /** Ultimo brano e posizione, per riprendere da dove si era rimasti (anche in auto). */
    fun saveLast(mediaId: String, positionMs: Long) {
        prefs.edit {
            putString(KEY_LAST_ID, mediaId)
            putLong(KEY_LAST_POS, positionMs)
        }
    }

    fun last(): Pair<String, Long>? {
        val id = prefs.getString(KEY_LAST_ID, null) ?: return null
        return id to prefs.getLong(KEY_LAST_POS, 0L)
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_USER = "server_user"
        const val KEY_SALT = "server_salt"
        const val KEY_TOKEN = "server_token"
        const val KEY_LAST_ID = "last_media_id"
        const val KEY_LAST_POS = "last_position"
        const val KEY_LYRICS = "lyrics_online"
        const val KEY_REPLAY_GAIN = "replay_gain"
        const val KEY_CROSSFADE = "crossfade"
        const val KEY_ACOUSTID = "acoustid_key"
        const val KEY_RECOGNITION_PROBLEM = "recognition_problem"
    }
}
