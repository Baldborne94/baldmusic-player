package io.github.baldborne94.baldmusic.core.artwork

import java.util.concurrent.ConcurrentHashMap

/**
 * Le copertine vere (Cover Art Archive) si scaricano in sottofondo: chi chiede una copertina
 * (il lettore, un elenco, Android Auto, il widget) non aspetta mai la rete. Finché la vera non
 * c'è si mostra quella del file; la vera vale dalla richiesta dopo. Un download alla volta
 * per copertina, anche se la stessa viene chiesta da più righe insieme.
 */
class CoverDownloads(private val background: (Runnable) -> Unit) {
    private val running = ConcurrentHashMap.newKeySet<String>()

    /**
     * La copertina da mostrare adesso: [real] se è già scaricata, altrimenti quella del file
     * ([local]), mentre [download] parte in sottofondo se non sta già andando per [key].
     */
    fun <T : Any> shown(key: String, real: T?, local: () -> T?, download: () -> Unit): T? {
        if (real != null) return real
        if (running.add(key)) {
            val job = Runnable {
                try {
                    download()
                } finally {
                    running.remove(key)
                }
            }
            // Se il lavoro in sottofondo non parte, la prossima richiesta riprova.
            runCatching { background(job) }.onFailure { running.remove(key) }
        }
        return local()
    }

    /** Sta scaricando la copertina [key]. */
    fun downloading(key: String) = key in running
}
