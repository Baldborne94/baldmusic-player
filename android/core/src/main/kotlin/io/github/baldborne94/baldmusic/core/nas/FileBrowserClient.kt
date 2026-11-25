package io.github.baldborne94.baldmusic.core.nas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Un errore di File Browser: [code] = risposta HTTP, null = non raggiungibile. */
class NasException(val code: Int?) : IOException(NasTrash.errorMessage(code))

/**
 * Le poche chiamate di File Browser (github.com/filebrowser/filebrowser) che servono per il
 * cestino del NAS: accesso (il token va nell'intestazione `X-Auth`), creare una cartella,
 * spostare un file. Nessuna cancellazione.
 */
class FileBrowserClient(baseUrl: String, private val http: OkHttpClient) {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: throw NasException(null)

    /** Accede e restituisce il token (valido due ore). */
    suspend fun login(user: String, password: String): String {
        val body = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            put("username", user)
            put("password", password)
            put("recaptcha", "")
        })
        val request = Request.Builder().url(api("login")).post(body.toRequestBody("application/json".toMediaType())).build()
        return send(request).trim().takeIf { it.count { c -> c == '.' } == 2 } ?: throw NasException(403)
    }

    /** Crea la cartella [dir] (anche quelle sopra). */
    suspend fun mkdirs(token: String, dir: String) {
        val url = resource(dir).newBuilder().addPathSegment("").build()
        send(Request.Builder().url(url).header("X-Auth", token).post(ByteArray(0).toRequestBody()).build())
    }

    /** Sposta [from] in [to]; se c'è già un file con quel nome, File Browser ne aggiunge uno numerato. */
    suspend fun move(token: String, from: String, to: String) {
        val url = resource(from).newBuilder()
            .addQueryParameter("action", "rename")
            .addQueryParameter("destination", NasTrash.encodedOnce(to))
            .addQueryParameter("override", "false")
            .addQueryParameter("rename", "true")
            .build()
        send(Request.Builder().url(url).header("X-Auth", token).patch(ByteArray(0).toRequestBody()).build())
    }

    private fun api(path: String) = base.newBuilder().addPathSegments("api/$path").build()

    /** `/api/resources/<percorso>`, ogni pezzo codificato. */
    private fun resource(path: String): HttpUrl = base.newBuilder().addPathSegment("api").addPathSegment("resources").apply {
        path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
    }.build()

    private suspend fun send(request: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw NasException(res.code)
                res.body.string()
            }
        } catch (e: NasException) {
            throw e
        } catch (e: IOException) {
            throw NasException(null)
        }
    }
}
