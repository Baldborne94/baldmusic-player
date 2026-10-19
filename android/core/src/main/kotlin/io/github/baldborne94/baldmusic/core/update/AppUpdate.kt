package io.github.baldborne94.baldmusic.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// Aggiornamenti dall'app: ogni APK compilato da main finisce nella release
// "apk-latest" insieme a version.json (.github/scripts/apk_version.py), con il
// numero di versione e le novità delle ultime versioni.

/** Le novità di una versione: i titoli delle PR unite. */
@Serializable
data class ReleaseNotes(val versionCode: Int, val date: String = "", val changes: List<String> = emptyList())

/** L'ultimo APK pubblicato. */
@Serializable
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String = "",
    val date: String = "",
    val apk: String = "baldmusic.apk",
    val size: Long? = null,
    val history: List<ReleaseNotes> = emptyList(),
) {
    /** C'è da aggiornare chi ha la versione [installed]. */
    fun isNewerThan(installed: Long): Boolean = versionCode > installed

    /** Le novità dopo [from] fino a [to] compresa, la più recente in cima: chi salta versioni le vede tutte. */
    fun changesBetween(from: Long, to: Long = versionCode.toLong()): List<ReleaseNotes> =
        history.filter { it.versionCode in (from + 1)..to }.sortedByDescending { it.versionCode }
}

object AppUpdate {
    const val VERSION_URL = "https://github.com/Baldborne94/baldmusic-player/releases/download/apk-latest/version.json"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun parse(text: String): ReleaseInfo? = runCatching { json.decodeFromString<ReleaseInfo>(text) }.getOrNull()?.takeIf { it.versionCode > 0 }

    /** Dove scaricare l'APK: accanto a version.json. */
    fun apkUrl(info: ReleaseInfo, versionUrl: String = VERSION_URL): String =
        versionUrl.toHttpUrl().resolve(info.apk)?.toString() ?: versionUrl.substringBeforeLast('/') + "/" + info.apk
}

class UpdateClient(private val http: OkHttpClient, private val url: String = AppUpdate.VERSION_URL) {
    /** L'ultimo APK pubblicato, o null se non si sa (niente rete, release vuota). */
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        // Sempre fresco: la release si sovrascrive a ogni build con lo stesso indirizzo.
        val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()
        runCatching {
            http.newCall(request).execute().use { r -> if (r.isSuccessful) AppUpdate.parse(r.body.string()) else null }
        }.getOrNull()
    }
}
