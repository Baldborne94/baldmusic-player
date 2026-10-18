package io.github.baldborne94.baldmusic.core.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Una riga di testo sincronizzato: compare al tempo [timeMs]. */
@Serializable
data class LyricLine(val timeMs: Long, val text: String)

/** Testo di un brano: sincronizzato se disponibile, altrimenti solo il testo. */
@Serializable
data class Lyrics(val synced: List<LyricLine> = emptyList(), val plain: String? = null) {
    val isEmpty get() = synced.isEmpty() && plain.isNullOrBlank()

    /** Indice della riga da evidenziare a [positionMs], -1 prima della prima. */
    fun lineAt(positionMs: Long): Int {
        if (synced.isEmpty() || positionMs < synced.first().timeMs) return -1
        var lo = 0
        var hi = synced.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (synced[mid].timeMs <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }
}

private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/**
 * Formato LRC: "[01:23.45]Testo". Una riga può avere più tempi
 * ("[00:10.00][01:10.00]Ritornello"); i tag di servizio ([ar:…], [offset:…]) si ignorano
 * tranne offset, che sposta tutto.
 */
fun parseLrc(text: String): List<LyricLine> {
    val offset = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val lines = mutableListOf<LyricLine>()
    for (raw in text.lineSequence()) {
        val tags = TIME_TAG.findAll(raw).toList()
        if (tags.isEmpty()) continue
        val lyric = raw.substring(tags.last().range.last + 1).trim()
        for (tag in tags) {
            val (min, sec, frac) = tag.destructured
            val fracMs = when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.take(3).toLong()
            }
            val time = min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs - offset
            lines += LyricLine(time.coerceAtLeast(0), lyric)
        }
    }
    return lines.sortedBy { it.timeMs }
}

@Serializable
private data class LrcLibRecord(
    val trackName: String? = null,
    val artistName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * Testi da LRCLIB (https://lrclib.net): archivio libero di testi sincronizzati,
 * senza chiave né account. Prima la ricerca esatta, poi quella libera.
 */
class LrcLibClient(private val http: OkHttpClient, private val baseUrl: String = "https://lrclib.net") {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun find(artist: String, title: String, album: String?, durationSec: Int?): Lyrics? = withContext(Dispatchers.IO) {
        val exact = "$baseUrl/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
            .apply { if (durationSec != null && durationSec > 0) addQueryParameter("duration", "$durationSec") }
            .build()
        get(exact)?.let { record -> return@withContext record.toLyrics() }

        val search = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val body = fetch(search.toString()) ?: return@withContext null
        val results = runCatching { json.decodeFromString<List<LrcLibRecord>>(body) }.getOrDefault(emptyList())
        // Preferisce un risultato sincronizzato con durata vicina a quella del file.
        val best = results
            .filter { it.syncedLyrics != null || it.plainLyrics != null }
            .sortedWith(
                compareBy<LrcLibRecord> { if (it.syncedLyrics != null) 0 else 1 }
                    .thenBy { r -> if (durationSec != null && r.duration != null) kotlin.math.abs(r.duration - durationSec) else 0.0 },
            )
            .firstOrNull()
        best?.toLyrics()
    }

    private fun get(url: okhttp3.HttpUrl): LrcLibRecord? =
        fetch(url.toString())?.let { runCatching { json.decodeFromString<LrcLibRecord>(it) }.getOrNull() }

    private fun fetch(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "BaldMusic (https://github.com/Baldborne94/baldmusic-player)").build()
        http.newCall(request).execute().use { res -> if (res.isSuccessful) res.body.string() else null }
    }.getOrNull()

    private fun LrcLibRecord.toLyrics(): Lyrics? {
        if (instrumental) return Lyrics(plain = "♪ Brano strumentale ♪")
        val lyrics = Lyrics(synced = syncedLyrics?.let(::parseLrc).orEmpty(), plain = plainLyrics)
        return lyrics.takeUnless { it.isEmpty }
    }
}
