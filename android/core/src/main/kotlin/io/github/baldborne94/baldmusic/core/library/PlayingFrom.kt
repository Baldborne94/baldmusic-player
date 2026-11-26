package io.github.baldborne94.baldmusic.core.library

/**
 * La pagina da cui viene il brano in corso, per «In riproduzione da» nel lettore (richiesta
 * dell'utente: tornare alla playlist o raccolta da cui ha preso il brano). Prima il «|contenitore»
 * dell'ID del brano, che c'è anche dopo una ripresa o con la musica partita da Android Auto; ma un
 * brano aggiunto da «Continua ad ascoltare» viene da una radio o dal mix casuale del server, che
 * non sono la pagina da cui hai cominciato: allora quella aperta dall'app ([requested]).
 */
object PlayingFrom {
    fun container(currentMediaId: String?, requested: String?): String? {
        val parent = currentMediaId?.let(MediaId::parse)?.parent
        if (parent != null && !isAutoplay(parent)) return parent.toString()
        return requested?.takeIf { MediaId.parse(it) != null }
    }

    private fun isAutoplay(id: MediaId) =
        (id.source == LocalCatalog.KEY && id.kind == LocalCatalog.RADIO) ||
            (id.source == RemoteSource.KEY && id.kind == RemoteSource.RANDOM)
}
