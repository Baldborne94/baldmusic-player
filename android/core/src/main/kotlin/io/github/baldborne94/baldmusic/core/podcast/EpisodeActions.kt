package io.github.baldborne94.baldmusic.core.podcast

/**
 * Le voci del ⋮ di una puntata: scaricarla sul telefono (o toglierla, se l'hai chiesta tu),
 * segnarla come ascoltata o da ascoltare, le informazioni. Una scaricata da sola (le ultime non
 * ascoltate) non si toglie a mano: la tiene e la cancella [PodcastDownloads.plan].
 */
object EpisodeActions {

    enum class Action(val label: String) {
        DOWNLOAD("Scarica sul telefono"),
        REMOVE_DOWNLOAD("Togli dal telefono"),
        MARK_FINISHED("Segna come ascoltata"),
        MARK_UNFINISHED("Segna come da ascoltare"),
        INFO("Informazioni sulla puntata"),
    }

    /** Le voci per una puntata ([mediaId]); nessuna se non è una puntata. */
    fun of(mediaId: String, downloaded: Boolean, manualDownload: Boolean, finished: Boolean): List<Action> {
        if (PodcastSource.episodeKey(mediaId) == null) return emptyList()
        return buildList {
            when {
                // Una già ascoltata non si scarica: al giro dopo verrebbe cancellata.
                !downloaded && !finished -> add(Action.DOWNLOAD)
                manualDownload -> add(Action.REMOVE_DOWNLOAD)
            }
            add(if (finished) Action.MARK_UNFINISHED else Action.MARK_FINISHED)
            add(Action.INFO)
        }
    }
}
