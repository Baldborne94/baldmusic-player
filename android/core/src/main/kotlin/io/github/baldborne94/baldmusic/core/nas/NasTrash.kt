package io.github.baldborne94.baldmusic.core.nas

/**
 * Eliminare un brano dal NAS dal telefono (scelta dell'utente: «voglio che il brano venga
 * cancellato anche da X»). Navidrome non sa cancellare file, quindi lo fa File Browser, un
 * secondo programma sul NAS con la cartella condivisa: il file non si cancella mai davvero, va
 * nel «Cestino BaldMusic» accanto alla musica (fuori da quella che legge Navidrome), con lo
 * stesso percorso, e si recupera rimettendolo a posto da X:.
 *
 * Le cartelle, come le vede File Browser (la cartella condivisa del NAS, guida in
 * `server/README.md`): la musica in [Config.musicFolder], il cestino in [Config.trashFolder].
 * Navidrome vede la stessa musica in [Config.navidromeRoot].
 */
object NasTrash {

    data class Config(
        /** La cartella della musica dentro Navidrome («/music» nel docker-compose). */
        val navidromeRoot: String = "/music",
        /** La stessa cartella dentro File Browser («/Musica»: File Browser vede tutta la cartella condivisa). */
        val musicFolder: String = "/Musica",
        /** Dove vanno i brani eliminati, fuori dalla musica (Navidrome non la vede). */
        val trashFolder: String = "/Cestino BaldMusic",
    )

    /** Un percorso in pezzi, senza «.» né «..» (mai fuori dalla cartella). */
    private fun parts(path: String): List<String>? {
        val out = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        return out.takeIf { p -> p.none { it == ".." } }
    }

    /**
     * Il percorso del file dentro la cartella della musica, da quello che manda Navidrome con
     * «Report Real Path» («/music/Disney/01 - X.mp3» → «Disney/01 - X.mp3»); null se il file non
     * sta lì sotto (percorso finto, o un'altra cartella).
     */
    fun relative(songPath: String?, config: Config = Config()): String? {
        val path = parts(songPath ?: return null) ?: return null
        val root = parts(config.navidromeRoot) ?: return null
        if (!songPath.replace('\\', '/').startsWith("/") || path.size <= root.size) return null
        if (path.take(root.size) != root) return null
        return path.drop(root.size).joinToString("/")
    }

    /** Dove sta il file per File Browser. */
    fun source(relative: String, config: Config = Config()): String = join(config.musicFolder, relative)

    /** Dove va nel cestino: lo stesso percorso, sotto il cestino. */
    fun destination(relative: String, config: Config = Config()): String = join(config.trashFolder, relative)

    /** La cartella che contiene [path]. */
    fun parentOf(path: String): String = "/" + parts(path).orEmpty().dropLast(1).joinToString("/")

    private fun join(folder: String, relative: String): String =
        "/" + (parts(folder).orEmpty() + parts(relative).orEmpty()).joinToString("/")

    /**
     * File Browser decodifica due volte la destinazione (`Query().Get` e poi `url.QueryUnescape`):
     * la si manda già codificata una volta (il client la codifica la seconda), così «+», «%» e
     * spazi nei nomi arrivano giusti. Le «/» restano.
     */
    fun encodedOnce(path: String): String = buildString {
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 0x80 || ch in "-._~/") append(ch) else append("%%%02X".format(c))
        }
    }

    /** Il messaggio per un errore di File Browser, da mostrare nell'app. */
    fun errorMessage(code: Int?): String = when (code) {
        401, 403 -> "File Browser non accetta utente e password (Impostazioni → Cestino sul NAS)"
        404 -> "File non trovato su File Browser: controlla la cartella della musica nelle Impostazioni"
        409 -> "Nel cestino del NAS c'è già un file con quel nome"
        null -> "File Browser non raggiungibile: controlla l'indirizzo e che Tailscale sia attivo"
        else -> "File Browser ha risposto $code"
    }
}
