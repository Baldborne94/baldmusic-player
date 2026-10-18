package io.github.baldborne94.baldmusic.data

import android.content.Context
import io.github.baldborne94.baldmusic.core.lyrics.LrcLibClient
import io.github.baldborne94.baldmusic.core.lyrics.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/**
 * Testi dei brani da LRCLIB, con cache su disco: un testo trovato resta
 * disponibile anche offline, e uno mancante non viene richiesto di continuo.
 */
class LyricsRepository(context: Context, http: OkHttpClient) {

    private val client = LrcLibClient(http)
    private val dir = File(context.filesDir, "testi").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = HashMap<String, Lyrics?>()

    suspend fun get(artist: String?, title: String, album: String?, durationMs: Long?): Lyrics? {
        if (artist.isNullOrBlank() || title.isBlank()) return null
        val key = hash("$artist|$title")
        synchronized(memory) { if (memory.containsKey(key)) return memory[key] }
        val cached = File(dir, "$key.json")
        val missing = File(dir, "$key.none")
        val lyrics = withContext(Dispatchers.IO) {
            when {
                cached.exists() -> runCatching { json.decodeFromString(Lyrics.serializer(), cached.readText()) }.getOrNull()
                // Un testo che non c'era si ricerca dopo una settimana: l'archivio cresce.
                missing.exists() && System.currentTimeMillis() - missing.lastModified() < WEEK_MS -> null
                else -> client.find(artist, title, album, durationMs?.let { (it / 1000).toInt() }).also {
                    if (it != null) cached.writeText(json.encodeToString(Lyrics.serializer(), it)) else missing.writeText("")
                }
            }
        }
        synchronized(memory) { memory[key] = lyrics }
        return lyrics
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.lowercase().toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    private companion object {
        const val WEEK_MS = 7L * 24 * 3600 * 1000
    }
}
