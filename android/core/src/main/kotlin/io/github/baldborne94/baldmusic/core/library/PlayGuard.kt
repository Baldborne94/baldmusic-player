package io.github.baldborne94.baldmusic.core.library

/**
 * Un brano toccato (con un ID, non una ricerca a voce) che non si trova più (file spostato o
 * cancellato dal telefono) fa fallire la coda nuova e la coda di prima resta. L'app però manda
 * subito dopo «play», che faceva ripartire quella coda: un podcast al posto del brano toccato
 * (resoconto dell'utente). Il «play» che arriva entro [WINDOW_MS] dal rifiuto si ignora.
 * Le richieste a voce non toccano questa regola: lì la musica di prima deve continuare.
 */
object PlayGuard {
    const val WINDOW_MS = 3_000L

    const val MISSING = "Questo brano non si trova più sul telefono: forse il file è stato spostato o cancellato."

    fun ignorePlay(refusedAtMs: Long?, nowMs: Long): Boolean =
        refusedAtMs != null && nowMs >= refusedAtMs && nowMs - refusedAtMs <= WINDOW_MS
}
