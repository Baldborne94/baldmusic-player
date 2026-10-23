package io.github.baldborne94.baldmusic.core.podcast

import kotlinx.serialization.Serializable

/**
 * Un podcast nel backup in Download: dove eri arrivato in ogni puntata, mai il link (quello di
 * Patreon vale come una password e il file sta dove altre app lo leggono). Le chiavi di
 * [progress] e [downloads] sono le puntate senza il podcast davanti: restano uguali anche
 * quando il link (e quindi l'ID) cambia.
 */
@Serializable
data class ShowBackup(
    val id: String,
    val title: String,
    val progress: Map<String, EpisodeProgress> = emptyMap(),
    val downloads: Set<String> = emptySet(),
)

@Serializable
data class PodcastBackup(
    val shows: List<ShowBackup> = emptyList(),
    val autoDownload: Int? = null,
)

/**
 * Il backup dei podcast e il suo ripristino. Dopo il ripristino i link vanno incollati di
 * nuovo: finché non lo fai, ogni podcast aspetta in [PodcastLibrary.pending] e, quando lo
 * segui, riprende ascoltate e punti di ripresa ([adopt]).
 */
object PodcastBackups {

    /** Null se non c'è niente da salvare (nessun podcast seguito né in attesa). */
    fun create(lib: PodcastLibrary, shows: List<Podcast>): PodcastBackup? {
        val byId = shows.associateBy { it.id }
        val followed = lib.feeds.map { sub ->
            val prefix = "${sub.id}~"
            ShowBackup(
                id = sub.id,
                // Senza il feed letto (mai aperto, file perso) si tiene il titolo che aspettava, se c'era.
                title = byId[sub.id]?.title ?: lib.pending.firstOrNull { it.id == sub.id }?.title.orEmpty(),
                progress = lib.progress.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) },
                downloads = lib.downloads.filterTo(HashSet()) { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) },
            )
        }
        // Quelli ripristinati e non ancora seguiti di nuovo: restano anche nel backup dopo.
        val waiting = lib.pending.filter { p -> followed.none { it.id == p.id } }
        val all = followed + waiting
        if (all.isEmpty()) return null
        return PodcastBackup(all, lib.autoDownload)
    }

    /** È lo stesso podcast: stesso ID (stesso link), stesso titolo, o almeno metà delle puntate ascoltate ci sono. */
    fun matches(b: ShowBackup, show: Podcast): Boolean {
        if (b.id == show.id) return true
        if (b.title.isNotBlank() && b.title.trim().lowercase() == show.title.trim().lowercase()) return true
        if (b.progress.isEmpty()) return false
        val keys = show.episodes.mapTo(HashSet()) { it.key }
        return b.progress.keys.count { it in keys } * 2 >= b.progress.size
    }

    /**
     * Rimette il backup: nei podcast che segui già si uniscono i punti di ripresa (vince il più
     * recente), gli altri aspettano il loro link in [PodcastLibrary.pending].
     */
    fun restore(lib: PodcastLibrary, backup: PodcastBackup, shows: List<Podcast>): PodcastLibrary {
        var out = backup.autoDownload?.let { lib.withAutoDownload(it) } ?: lib
        val followed = shows.filter { s -> lib.feeds.any { it.id == s.id } }
        for (b in backup.shows) {
            val show = followed.firstOrNull { matches(b, it) }
            out = if (show != null) merge(out, show.id, b)
            else out.copy(pending = out.pending.filterNot { it.id == b.id || it.title == b.title } + b)
        }
        return out
    }

    /** Hai appena seguito [show]: se aspettava nel backup, riprende ascoltate e punti di ripresa. */
    fun adopt(lib: PodcastLibrary, show: Podcast): PodcastLibrary {
        val waiting = lib.pending.filter { matches(it, show) }
        if (waiting.isEmpty()) return lib
        return waiting.fold(lib.copy(pending = lib.pending - waiting.toSet())) { acc, b -> merge(acc, show.id, b) }
    }

    private fun merge(lib: PodcastLibrary, showId: String, b: ShowBackup): PodcastLibrary {
        val progress = HashMap(lib.progress)
        b.progress.forEach { (episode, p) ->
            val key = "$showId~$episode"
            val now = progress[key]
            if (now == null || p.at > now.at) progress[key] = p
        }
        return lib.copy(progress = progress, downloads = lib.downloads + b.downloads.map { "$showId~$it" })
    }
}
