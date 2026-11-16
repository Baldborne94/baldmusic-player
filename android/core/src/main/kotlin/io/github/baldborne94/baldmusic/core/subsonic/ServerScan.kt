package io.github.baldborne94.baldmusic.core.subsonic

import kotlinx.coroutines.delay

/**
 * «Aggiorna libreria» dal telefono, come nell'app web (`web/src/lib/scan.ts`): Navidrome rilegge la
 * cartella della musica del PC (come «Quick Scan» o «Full Scan» nella sua pagina), l'app aspetta che
 * finisca e poi rilegge il server. Senza tasto Navidrome lo fa comunque da solo ogni ora.
 */
object ServerScan {
    /** Ogni quanto si chiede a Navidrome a che punto è. */
    const val POLL_MS = 1_500L

    /** Oltre così si smette di aspettare (la scansione continua da sola su Navidrome). */
    const val MAX_MS = 30 * 60 * 1000L

    /** Il codice di Subsonic per «non hai il permesso»: la scansione la fa partire solo un amministratore. */
    const val NOT_AUTHORIZED = 50

    const val TOO_LONG = "La scansione ci mette tanto: continua su Navidrome, riprova più tardi."

    interface Scanner {
        suspend fun startScan(full: Boolean): ScanStatus
        suspend fun scanStatus(): ScanStatus
    }

    class Failed(message: String) : Exception(message)

    fun of(client: SubsonicClient) = object : Scanner {
        override suspend fun startScan(full: Boolean) = client.startScan(full)
        override suspend fun scanStatus() = client.scanStatus()
    }

    /**
     * Fa partire la scansione e aspetta che finisca; [onProgress] riceve i brani letti finora.
     * Restituisce quanti brani ha letto; un problema arriva come [Failed] col messaggio da mostrare.
     */
    suspend fun scanAndWait(
        scanner: Scanner,
        full: Boolean,
        onProgress: (Int) -> Unit = {},
        sleep: suspend (Long) -> Unit = { delay(it) },
        now: () -> Long = System::currentTimeMillis,
    ): Int {
        var status = guarded { scanner.startScan(full) }
        val started = now()
        onProgress(status.count)
        // Navidrome può rispondere «non sta leggendo» prima di aver cominciato: si guarda almeno una volta.
        var first = true
        while (status.scanning || first) {
            first = false
            if (now() - started > MAX_MS) throw Failed(TOO_LONG)
            sleep(POLL_MS)
            status = guarded { scanner.scanStatus() }
            onProgress(status.count)
        }
        return status.count
    }

    /** Il messaggio da mostrare quando la scansione non parte. */
    fun errorMessage(e: Throwable): String =
        if (e is SubsonicException && e.code == NOT_AUTHORIZED) {
            "Solo un amministratore di Navidrome può far partire la scansione: collega l'app con quell'utente, o usa Activity → Quick Scan nella pagina di Navidrome."
        } else {
            e.message ?: e.toString()
        }

    private suspend fun guarded(call: suspend () -> ScanStatus): ScanStatus =
        try {
            call()
        } catch (e: Failed) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Failed(errorMessage(e))
        }
}
