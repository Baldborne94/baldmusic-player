package io.github.baldborne94.baldmusic

import android.content.Context
import androidx.core.content.edit
import io.github.baldborne94.baldmusic.core.audio.AudioPrefs
import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preferenze dell'app. [changes] avvisa il servizio quando la libreria va ricaricata. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private val _audio = MutableStateFlow(
        runCatching { prefs.getString(KEY_AUDIO, null)?.let { json.decodeFromString(AudioPrefs.serializer(), it) } }.getOrNull() ?: AudioPrefs(),
    )

    /** Equalizzatore, effetti e velocità: il servizio li applica appena cambiano. */
    val audio: StateFlow<AudioPrefs> = _audio.asStateFlow()

    fun updateAudio(change: (AudioPrefs) -> AudioPrefs) {
        val next = change(_audio.value)
        _audio.value = next
        prefs.edit { putString(KEY_AUDIO, json.encodeToString(AudioPrefs.serializer(), next)) }
    }

    /** Cerca i testi dei brani su internet (LRCLIB). */
    var lyricsOnline: Boolean
        get() = prefs.getBoolean(KEY_LYRICS, true)
        set(value) {
            prefs.edit { putBoolean(KEY_LYRICS, value) }
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

    /** Kbps massimi in streaming dal server; 0 = file originale. */
    var maxBitRate: Int
        get() = prefs.getInt(KEY_BITRATE, 0)
        set(value) {
            prefs.edit { putInt(KEY_BITRATE, value) }
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
        const val KEY_BITRATE = "max_bitrate"
        const val KEY_LAST_ID = "last_media_id"
        const val KEY_LAST_POS = "last_position"
        const val KEY_AUDIO = "audio"
        const val KEY_LYRICS = "lyrics_online"
    }
}
