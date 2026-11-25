package io.github.baldborne94.baldmusic.core.nas

import io.github.baldborne94.baldmusic.core.subsonic.Song

/**
 * Manda nel cestino del NAS i file dei brani del server ([NasTrash]): per ognuno la cartella nel
 * cestino, poi lo spostamento. Un brano senza percorso vero (manca «Report Real Path») o fuori
 * dalla cartella della musica si salta, gli altri vanno avanti.
 */
class NasTrasher(private val files: FileBrowserClient, private val config: NasTrash.Config = NasTrash.Config()) {

    data class Result(
        /** ID dei brani spostati nel cestino. */
        val moved: List<String>,
        /** Brano → perché non si è potuto. */
        val failed: Map<String, String>,
    )

    suspend fun trash(token: String, songs: List<Song>): Result {
        val moved = mutableListOf<String>()
        val failed = LinkedHashMap<String, String>()
        val made = HashSet<String>()
        for (s in songs) {
            val rel = NasTrash.relative(s.path, config)
            if (rel == null) {
                failed[s.id] = "Percorso del file sconosciuto: in Navidrome (Players) attiva «Report Real Path» per il lettore del telefono"
                continue
            }
            try {
                val to = NasTrash.destination(rel, config)
                val dir = NasTrash.parentOf(to)
                if (made.add(dir)) files.mkdirs(token, dir)
                files.move(token, NasTrash.source(rel, config), to)
                moved += s.id
            } catch (e: NasException) {
                // Utente o password sbagliati, o NAS irraggiungibile: inutile provare gli altri.
                if (e.code == null || e.code == 401 || e.code == 403) throw e
                failed[s.id] = e.message.orEmpty()
            }
        }
        return Result(moved, failed)
    }
}
