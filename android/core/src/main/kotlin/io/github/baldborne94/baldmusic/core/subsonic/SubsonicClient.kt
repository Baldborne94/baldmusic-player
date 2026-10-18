package io.github.baldborne94.baldmusic.core.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

// Client per l'API Subsonic, quella che parla Navidrome.
// https://www.subsonic.org/pages/api.jsp

const val API_VERSION = "1.16.1"
const val CLIENT_NAME = "baldmusic"

/** La password in chiaro non viene salvata: solo sale e md5(password + sale). */
data class Credentials(val url: String, val user: String, val salt: String, val token: String) {
    companion object {
        fun create(url: String, user: String, password: String, salt: String = randomSalt()) =
            Credentials(normalizeServerUrl(url), user.trim(), salt, md5Hex(password + salt))
    }
}

class SubsonicException(message: String, val code: Int? = null) : IOException(message)

/** Porta predefinita di Navidrome. */
const val DEFAULT_PORT = 4533

/**
 * Normalizza l'indirizzo digitato: aggiunge lo schema, toglie lo slash finale e,
 * se è un indirizzo IP o localhost senza porta, aggiunge :4533. Dimenticare la
 * porta è l'errore più comune; i nomi (es. …ts.net) restano invariati perché lì
 * di solito c'è un proxy sulla porta standard.
 */
fun normalizeServerUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url = "http://$url"
    val match = Regex("^(https?://)([^/:]+)(:\\d+)?(.*)$", RegexOption.IGNORE_CASE).find(url) ?: return url
    val (scheme, host, port, rest) = match.destructured
    val isIp = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
    return if (port.isEmpty() && (isIp || host.equals("localhost", ignoreCase = true))) "$scheme$host:$DEFAULT_PORT$rest" else url
}

fun randomSalt(bytes: Int = 8): String {
    val buf = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
    return buf.joinToString("") { "%02x".format(it) }
}

fun md5Hex(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

class SubsonicClient(val creds: Credentials, private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun url(method: String, vararg params: Pair<String, Any?>): HttpUrl {
        val base = "${creds.url}/rest/$method".toHttpUrlOrNull()
            ?: throw SubsonicException("Indirizzo del server non valido")
        return base.newBuilder().apply {
            addQueryParameter("u", creds.user)
            addQueryParameter("t", creds.token)
            addQueryParameter("s", creds.salt)
            addQueryParameter("v", API_VERSION)
            addQueryParameter("c", CLIENT_NAME)
            addQueryParameter("f", "json")
            for ((k, v) in params) if (v != null) addQueryParameter(k, v.toString())
        }.build()
    }

    internal fun parse(body: String): Envelope {
        val env = try {
            json.decodeFromString(Root.serializer(), body).response
        } catch (e: Exception) {
            null
        } ?: throw SubsonicException("Risposta non valida: il server non sembra Navidrome/Subsonic")
        if (env.status != "ok") {
            throw SubsonicException(env.error?.message?.ifBlank { null } ?: "Errore del server", env.error?.code)
        }
        return env
    }

    private suspend fun call(method: String, vararg params: Pair<String, Any?>): Envelope = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(method, *params)).build()
        val body = try {
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw SubsonicException("Il server ha risposto ${res.code}")
                res.body.string()
            }
        } catch (e: SubsonicException) {
            throw e
        } catch (e: IOException) {
            throw SubsonicException("Server non raggiungibile. Controlla indirizzo e porta (di solito :4533), che il PC sia acceso e, fuori casa, che Tailscale sia attivo.")
        }
        parse(body)
    }

    suspend fun ping() {
        call("ping")
    }

    suspend fun albumList(type: String, size: Int = 50, offset: Int = 0): List<Album> =
        call("getAlbumList2", "type" to type, "size" to size, "offset" to offset).albumList2?.album.orEmpty()

    suspend fun album(id: String): Album = call("getAlbum", "id" to id).album
        ?: throw SubsonicException("Album non trovato")

    suspend fun artists(): List<Artist> = call("getArtists").artists?.index.orEmpty().flatMap { it.artist }

    suspend fun artist(id: String): Artist = call("getArtist", "id" to id).artist
        ?: throw SubsonicException("Artista non trovato")

    suspend fun song(id: String): Song = call("getSong", "id" to id).song
        ?: throw SubsonicException("Brano non trovato")

    suspend fun playlists(): List<Playlist> = call("getPlaylists").playlists?.playlist.orEmpty()

    suspend fun playlist(id: String): Playlist = call("getPlaylist", "id" to id).playlist
        ?: throw SubsonicException("Playlist non trovata")

    suspend fun randomSongs(size: Int = 100): List<Song> =
        call("getRandomSongs", "size" to size).randomSongs?.song.orEmpty()

    suspend fun starred(): SearchResult = call("getStarred2").starred2 ?: SearchResult()

    suspend fun search(query: String): SearchResult =
        call("search3", "query" to query, "artistCount" to 10, "albumCount" to 20, "songCount" to 50).searchResult3
            ?: SearchResult()

    suspend fun scrobble(id: String, submission: Boolean) {
        call("scrobble", "id" to id, "submission" to submission, "time" to System.currentTimeMillis())
    }

    /** maxBitRate = 0 significa "originale, senza conversione". */
    fun streamUrl(id: String, maxBitRate: Int = 0): String =
        url("stream", "id" to id, "maxBitRate" to maxBitRate.takeIf { it > 0 }).toString()

    fun coverUrl(id: String, size: Int = 512): String = url("getCoverArt", "id" to id, "size" to size).toString()
}
