package io.github.baldborne94.baldmusic.core.library

/** Un elemento della libreria, indipendente da Media3: l'app lo converte in MediaItem. */
data class LibraryNode(
    val mediaId: String,
    val title: String,
    val kind: Kind,
    val subtitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Chiave della copertina, es. "remote/al-123" o "local/42": l'app la trasforma in un URI. */
    val artwork: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    /** Indirizzo riproducibile del brano (content:// o http). */
    val streamUri: String? = null,
    /** Come mostrare i figli: a griglia (album) o a lista (brani). */
    val gridChildren: Boolean = false,
    /** Per i brani: dove portano "Vai all'album" e "Vai all'artista". */
    val albumId: String? = null,
    val artistId: String? = null,
) {
    enum class Kind { SOURCE, SECTION, ALBUM, ARTIST, PLAYLIST, FOLDER, TRACK, MESSAGE }

    val playable get() = kind == Kind.TRACK
    val browsable get() = kind != Kind.TRACK && kind != Kind.MESSAGE
}

/** Una sorgente di musica: i file sul telefono oppure il server Navidrome. */
interface MusicSource {
    /** Prefisso degli ID: "local" o "remote". */
    val key: String
    val title: String
    /** Figli della radice della sorgente (Album, Artisti, …). */
    suspend fun sections(): List<LibraryNode>
    suspend fun children(id: MediaId): List<LibraryNode>
    /** Un brano completo di streamUri, o null se non esiste più. */
    suspend fun track(id: MediaId): LibraryNode?
    suspend fun search(query: String): List<LibraryNode>

    /** Un contenitore per ID, se la sorgente lo sa costruire senza elencare il genitore. */
    suspend fun node(id: MediaId): LibraryNode? = null

    /**
     * false per le sorgenti i cui elenchi cambiano a ogni ascolto (preferiti,
     * cronologia): leggerle costa niente e una cache le mostrerebbe vecchie.
     */
    val cacheable: Boolean get() = true
}
