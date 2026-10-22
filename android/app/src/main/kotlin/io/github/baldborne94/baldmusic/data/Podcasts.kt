package io.github.baldborne94.baldmusic.data

import android.content.Context
import io.github.baldborne94.baldmusic.core.podcast.Podcast
import io.github.baldborne94.baldmusic.core.podcast.PodcastFeed
import io.github.baldborne94.baldmusic.core.podcast.PodcastLibrary
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * I podcast che segui: i feed (anche quello privato di Patreon) e dove eri arrivato in ogni
 * puntata. Le regole stanno in [PodcastLibrary] e [PodcastFeed] (`:core`, testate); qui solo
 * rete, file e stato. Tutto resta nei file dell'app: gli indirizzi privati non vanno nel backup
 * in Download né nei resoconti.
 */
class Podcasts(context: Context, private val http: OkHttpClient, private val onShowsChanged: () -> Unit) {

    private val dir = File(context.filesDir, "podcast").apply { mkdirs() }
    private val file = File(dir, "podcast.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = Mutex()

    private val _library = MutableStateFlow(PodcastLibrary.decode(runCatching { file.readText() }.getOrNull()))
    val library: StateFlow<PodcastLibrary> = _library.asStateFlow()

    /** I podcast letti l'ultima volta, per ID: si mostrano subito, anche senza rete. */
    @Volatile private var cached: Map<String, Podcast>? = null

    val hasAny get() = _library.value.feeds.isNotEmpty()

    /** I podcast da mostrare: quelli salvati, e se sono vecchi si aggiornano in sottofondo. */
    suspend fun shows(): List<Podcast> {
        val all = loaded()
        val now = System.currentTimeMillis()
        if (_library.value.feeds.any { (all[it.id]?.fetchedAt ?: 0) < now - STALE_MS }) scope.launch { refresh() }
        return _library.value.feeds.mapNotNull { all[it.id] }
    }

    /**
     * I podcast per riprodurre una puntata: se il feed è vecchio si aggiorna prima (al massimo
     * qualche secondo), perché gli indirizzi dell'audio di Patreon scadono.
     */
    suspend fun showsForPlayback(): List<Podcast> {
        withTimeoutOrNull(PLAYBACK_WAIT_MS) { refresh() }
        return _library.value.feeds.mapNotNull { loaded()[it.id] }
    }

    /** L'indirizzo della copertina di un podcast; lo chiede [io.github.baldborne94.baldmusic.artwork.ArtworkProvider], fuori dal thread principale. */
    fun imageUrl(showId: String): String? = (cached ?: runBlocking { loaded() })[showId]?.image

    /** Aggiorna i feed più vecchi di [maxAgeMs]: puntate nuove, indirizzi dell'audio rinnovati. */
    suspend fun refresh(maxAgeMs: Long = STALE_MS) = refreshing.withLock {
        val now = System.currentTimeMillis()
        var changed = false
        _library.value.feeds.forEach { sub ->
            if ((loaded()[sub.id]?.fetchedAt ?: 0) >= now - maxAgeMs) return@forEach
            val show = fetch(sub.url, sub.id).getOrNull() ?: return@forEach
            if (show.episodes.map { it.key } != loaded()[sub.id]?.episodes?.map { it.key }) changed = true
            store(show)
        }
        if (changed) onShowsChanged()
    }

    /**
     * Segui un podcast dal suo link: si legge subito, così un link sbagliato (la pagina del
     * creatore invece del link RSS) si scopre adesso e si dice perché.
     */
    suspend fun follow(input: String): Result<Podcast> {
        val url = PodcastFeed.cleanUrl(input) ?: return Result.failure(IllegalArgumentException("Questo non è un link: copialo intero, comincia con https://"))
        if (url.contains("patreon.com", ignoreCase = true) && !url.contains("/rss", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException(PATREON_HINT))
        }
        val id = PodcastFeed.idOf(url)
        return fetch(url, id).onSuccess { show ->
            store(show)
            update { it.follow(url, System.currentTimeMillis()) }
            onShowsChanged()
        }
    }

    fun unfollow(id: String) {
        update { it.unfollow(id) }
        cached = cached?.minus(id)
        File(dir, "$id.json").delete()
        onShowsChanged()
    }

    /** Dove sei arrivato nella puntata [mediaId] (se è una puntata). */
    fun record(mediaId: String, positionMs: Long, durationMs: Long?) {
        val key = PodcastSource.episodeKey(mediaId) ?: return
        update { it.record(key, positionMs, durationMs, System.currentTimeMillis()) }
    }

    /** Da dove far ripartire la puntata [mediaId]; 0 se non è una puntata o va dall'inizio. */
    fun resumeAt(mediaId: String): Long = PodcastSource.episodeKey(mediaId)?.let { _library.value.resumeAt(it) } ?: 0

    private fun update(change: (PodcastLibrary) -> PodcastLibrary) {
        val before = _library.value
        val after = change(before)
        if (after == before) return
        _library.value = after
        scope.launch { save() }
    }

    /** Si salva sempre l'ultimo stato, uno alla volta: due salvataggi insieme non si rovinano. */
    @Synchronized
    private fun save() {
        val text = PodcastLibrary.encode(_library.value)
        val tmp = File(dir, "podcast.json.tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    private suspend fun loaded(): Map<String, Podcast> = cached ?: withContext(Dispatchers.IO) {
        _library.value.feeds.mapNotNull { sub ->
            runCatching { json.decodeFromString(Podcast.serializer(), File(dir, "${sub.id}.json").readText()) }.getOrNull()
        }.associateBy { it.id }.also { cached = it }
    }

    private suspend fun store(show: Podcast) = withContext(Dispatchers.IO) {
        cached = loaded() + (show.id to show)
        runCatching {
            val tmp = File(dir, "${show.id}.json.tmp")
            tmp.writeText(json.encodeToString(Podcast.serializer(), show))
            tmp.renameTo(File(dir, "${show.id}.json"))
        }
    }

    private suspend fun fetch(url: String, id: String): Result<Podcast> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "BaldMusic (https://github.com/Baldborne94/baldmusic-player)").build()
            http.newCall(request).execute().use { res ->
                when {
                    res.code == 401 || res.code == 403 || res.code == 404 ->
                        error("Il link non dà le puntate (errore ${res.code}). Se è di Patreon, copialo di nuovo da «Ascolta in altre app»: i link vecchi smettono di funzionare.")
                    !res.isSuccessful -> error("Il server del podcast ha risposto con un errore (${res.code}). Riprova più tardi.")
                }
                val text = res.body.string()
                PodcastFeed.parse(text, id, System.currentTimeMillis())
                    ?: error(if ("patreon" in url.lowercase()) PATREON_HINT else "Questo link non è un feed di podcast (RSS).")
            }
        }.recoverCatching { e ->
            throw if (e is java.io.IOException) IllegalStateException("Senza rete o il server del podcast non risponde. Riprova quando sei connesso.") else e
        }
    }

    companion object {
        /** Ogni quanto si guarda se ci sono puntate nuove. */
        const val STALE_MS = 30 * 60_000L
        private const val PLAYBACK_WAIT_MS = 8_000L

        const val PATREON_HINT = "Serve il link RSS privato, non la pagina del podcast: su Patreon apri il podcast, " +
            "tocca «Il tuo abbonamento» (o il menu della raccolta audio) → «Ascolta in altre app» → «Copia link» e incollalo qui."
    }
}
