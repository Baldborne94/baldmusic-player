package io.github.baldborne94.baldmusic.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Un file come lo vede l'indice di Android: basta a capire se è cambiato. */
data class FileStamp(val id: Long, val modified: Long, val size: Long)

/**
 * I tag già letti dai file, per non rileggerli a ogni avvio. Un file si rilegge
 * solo se è nuovo o se data di modifica o dimensione sono cambiate. Anche un file
 * illeggibile ha la sua voce (vuota): così non lo si riprova a ogni scansione.
 */
class TagIndex private constructor(private val entries: MutableMap<Long, Entry>) {

    @Serializable
    data class Entry(val modified: Long, val size: Long, val tags: FileTags)

    @Serializable
    // La versione senza valore predefinito: così si scrive sempre, e un file che non ce l'ha non vale.
    private data class Stored(val version: Int, val entries: Map<Long, Entry> = emptyMap())

    constructor() : this(HashMap())

    @Synchronized
    fun tags(stamp: FileStamp): FileTags? = entries[stamp.id]?.takeIf { it.modified == stamp.modified && it.size == stamp.size }?.tags

    /** I file da (ri)leggere tra quelli presenti adesso. */
    @Synchronized
    fun stale(files: List<FileStamp>): List<FileStamp> = files.filter { tags(it) == null }

    @Synchronized
    fun put(stamp: FileStamp, tags: FileTags) {
        entries[stamp.id] = Entry(stamp.modified, stamp.size, tags)
    }

    /** Toglie i file che non ci sono più; true se ne ha tolto qualcuno. */
    @Synchronized
    fun retain(ids: Set<Long>): Boolean = entries.keys.retainAll(ids)

    @Synchronized
    fun size() = entries.size

    @Synchronized
    fun toJson(): String = json.encodeToString(Stored.serializer(), Stored(VERSION, HashMap(entries)))

    companion object {
        /** Da aumentare quando cambia cosa si legge dai file: l'indice si rifà da capo. */
        // 2: la data "1970-01-01" non vale più come anno.
        // 3: un ReplayGain "NaN" o "Infinity" nei tag vale come mancante.
        const val VERSION = 3

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** Un indice salvato, o vuoto se manca, è rovinato o di una versione vecchia. */
        fun fromJson(text: String?): TagIndex {
            val stored = text?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            return if (stored == null || stored.version != VERSION) TagIndex() else TagIndex(HashMap(stored.entries))
        }
    }
}
