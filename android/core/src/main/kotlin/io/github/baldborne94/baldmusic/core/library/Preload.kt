package io.github.baldborne94.baldmusic.core.library

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Un valore che si comincia a leggere in sottofondo appena creato (es. la tua musica, all'avvio
 * dell'app): chi lo chiede per primo aspetta solo quello che manca, e l'avvio non resta fermo
 * sul file. [load] gira una volta sola.
 */
class Preload<T>(background: Executor, load: () -> T) {
    private val future: CompletableFuture<T> = CompletableFuture.supplyAsync(load, background)

    val value: T by lazy { future.join() }

    /** Già letto: chiederlo non fa aspettare. */
    val ready get() = future.isDone
}
