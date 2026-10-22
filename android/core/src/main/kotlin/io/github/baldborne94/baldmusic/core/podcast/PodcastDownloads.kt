package io.github.baldborne94.baldmusic.core.podcast

/**
 * Le puntate da tenere sul telefono, per ascoltarle senza rete (in macchina, in galleria) e
 * senza i link dell'audio di Patreon che scadono. Si tengono le ultime [PodcastLibrary.autoDownload]
 * non ascoltate di ogni podcast e quelle chieste a mano; una puntata ascoltata si toglie.
 */
object PodcastDownloads {
    data class Wanted(val show: Podcast, val episode: Episode, /** Chiesta a mano: si scarica con qualsiasi rete. */ val manual: Boolean) {
        val key get() = PodcastSource.keyOf(show, episode)
    }

    data class Plan(
        /** Da scaricare, prima quelle chieste a mano, poi dalla più recente. */
        val download: List<Wanted>,
        /** Chiavi dei file da togliere: puntate ascoltate, troppo vecchie o di podcast non più seguiti. */
        val delete: Set<String>,
    )

    /** Cosa scaricare e cosa togliere, sapendo quali puntate sono già sul telefono ([present]). */
    fun plan(shows: List<Podcast>, lib: PodcastLibrary, present: Set<String>): Plan {
        fun finished(key: String) = lib.progress[key]?.finished == true
        val manual = ArrayList<Wanted>()
        val auto = ArrayList<Wanted>()
        shows.forEach { show ->
            val keys = show.episodes.map { PodcastSource.keyOf(show, it) }
            show.episodes.zip(keys).forEach { (e, key) -> if (key in lib.downloads && !finished(key)) manual += Wanted(show, e, manual = true) }
            show.episodes.zip(keys).filter { (_, key) -> !finished(key) && key !in lib.downloads }
                .take(lib.autoDownload).forEach { (e, _) -> auto += Wanted(show, e, manual = false) }
        }
        val keep = (manual + auto).mapTo(HashSet()) { it.key }
        val download = (manual + auto.sortedByDescending { it.episode.publishedAt ?: Long.MIN_VALUE }).filter { it.key !in present }
        return Plan(download, present - keep)
    }
}
