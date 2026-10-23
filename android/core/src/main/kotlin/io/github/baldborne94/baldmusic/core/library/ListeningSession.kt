package io.github.baldborne94.baldmusic.core.library

import kotlin.random.Random

/**
 * Mentre ascolti una raccolta (o una playlist, un album, un mix): i brani già sentiti e quelli
 * saltati. Un brano saltato non torna in questo ascolto (né col casuale rifatto, né con «ripeti
 * tutto», né toccando un altro brano della stessa raccolta); uno già sentito non si ripropone
 * prima di quelli che mancano. Vale finché ascolti lo stesso contenitore: un altro contenitore,
 * o [GAP_MS] senza ascoltare, e si ricomincia. Gli ID si confrontano senza il contenitore.
 */
data class ListeningSession(
    /** Il contenitore della coda ("me/playlist/x"); null = niente da ricordare (puntate, brani sciolti). */
    val container: String? = null,
    val heard: Set<String> = emptySet(),
    val skipped: Set<String> = emptySet(),
    val lastAt: Long = 0,
) {
    /** Stai per suonare [container]: se è lo stesso di adesso (e da poco) si continua, altrimenti si ricomincia. */
    fun startingOn(container: String?, now: Long): ListeningSession =
        if (container != null && container == this.container && now - lastAt <= GAP_MS) copy(lastAt = now)
        else ListeningSession(container, lastAt = now)

    /**
     * Da [trackId] a un altro brano, arrivati a [positionMs] su [durationMs]: finito o sentito
     * quasi tutto = sentito; lasciato prima andando avanti ([forward], non «precedente») = saltato.
     * Conta solo un brano scelto da [container] (il suo ID lo porta dopo «|»).
     */
    fun left(trackId: String, positionMs: Long, durationMs: Long?, forward: Boolean, ended: Boolean, now: Long): ListeningSession {
        // Solo i brani di questo contenitore: non una puntata, né quelli aggiunti da «Continua ad ascoltare».
        if (container == null || MediaId.parse(trackId)?.parent?.toString() != container) return this
        val id = UserLibrary.baseId(trackId)
        return when {
            ended || !isSkip(positionMs, durationMs) -> copy(heard = heard + id, skipped = skipped - id, lastAt = now)
            forward -> copy(skipped = skipped + id, lastAt = now)
            else -> copy(lastAt = now)
        }
    }

    fun wasSkipped(trackId: String) = UserLibrary.baseId(trackId) in skipped

    /**
     * Una coda nuova dello stesso contenitore ([ids], partendo da [start]; -1 = nessun brano
     * scelto): fuori i saltati e i già sentiti, tranne quello che hai scelto tu. Se non resta
     * niente (raccolta già sentita tutta) la coda resta intera. Restituisce gli indici tenuti e
     * il nuovo punto di partenza.
     */
    fun filter(ids: List<String>, start: Int): Pair<List<Int>, Int> {
        val all = ids.indices.toList()
        if (container == null || (heard.isEmpty() && skipped.isEmpty())) return all to start
        val done = heard + skipped
        val kept = ids.indices.filter { i -> i == start || UserLibrary.baseId(ids[i]) !in done }
        // Sentita già tutta (resta al massimo quello scelto): si riascolta intera.
        if (kept.none { it != start }) return all to start
        return kept to (if (start < 0) start else kept.indexOf(start))
    }

    /**
     * L'ordine del casuale rifatto mentre ascolti ([ids], brano in corso [current]): prima i già
     * sentiti (così con «ripeti» spento non tornano), poi quello in corso, poi gli altri a caso.
     */
    fun shuffleOrder(ids: List<String>, current: Int, random: Random = Random.Default): IntArray {
        val others = ids.indices.filter { it != current }
        val (done, rest) = others.partition { UserLibrary.baseId(ids[it]) in heard }
        return (done + listOf(current) + rest.shuffled(random)).toIntArray()
    }

    /**
     * Spento il casuale, la coda torna in ordine dal brano in corso: i già sentiti che stanno
     * dopo si spostano subito prima, così non si risentono. Le mosse ("da", "a") vanno fatte
     * una dopo l'altra, come `moveMediaItem` del player.
     */
    fun linearMoves(ids: List<String>, current: Int): List<Pair<Int, Int>> {
        var at = current
        val moves = ArrayList<Pair<Int, Int>>()
        for (i in (current + 1) until ids.size) {
            if (UserLibrary.baseId(ids[i]) !in heard) continue
            moves += i to at
            at++
        }
        return moves
    }

    companion object {
        /** Più di così senza ascoltare: è un ascolto nuovo. */
        const val GAP_MS = 2L * 3600 * 1000

        /**
         * Saltato se lasciato prima degli ultimi 30 s e prima dei tre quarti (la dissolvenza
         * parte al massimo 12 s prima della fine: non è un salto). Durata sconosciuta: prima di 30 s.
         */
        fun isSkip(positionMs: Long, durationMs: Long?): Boolean {
            val d = durationMs?.takeIf { it > 0 } ?: return positionMs < 30_000
            return positionMs < minOf(d - 30_000, d * 3 / 4)
        }
    }
}
