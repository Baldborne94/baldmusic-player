package io.github.baldborne94.baldmusic.core.acoustid

import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.FileTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * I brani riconosciuti dal suono (`riconoscimenti.json`): per ogni file la proposta di
 * AcoustID e cosa ne ha deciso l'utente. Un file si riconosce una volta sola, e di nuovo
 * solo se cambia; anche un file non riconosciuto ha la sua voce (senza proposta), così non
 * lo si riprova sempre. I tag proposti valgono solo dopo "Applica": prima si guardano e basta.
 */
class RecognitionIndex private constructor(private val entries: MutableMap<Long, Entry>) {

    @Serializable
    enum class Decision { PENDING, ACCEPTED, REJECTED }

    @Serializable
    data class Entry(
        val modified: Long,
        val size: Long,
        /** null = AcoustID non l'ha riconosciuto. */
        val proposal: Proposal? = null,
        val decision: Decision = Decision.PENDING,
    )

    @Serializable
    private data class Stored(val version: Int, val entries: Map<Long, Entry> = emptyMap())

    constructor() : this(HashMap())

    private fun valid(stamp: FileStamp) = entries[stamp.id]?.takeIf { it.modified == stamp.modified && it.size == stamp.size }

    /** I file di [files] ancora da riconoscere (nuovi, o cambiati dopo l'ultima volta). */
    @Synchronized
    fun pending(files: List<FileStamp>): List<FileStamp> = files.filter { valid(it) == null }

    @Synchronized
    fun put(stamp: FileStamp, proposal: Proposal?) {
        entries[stamp.id] = Entry(stamp.modified, stamp.size, proposal)
    }

    /** La voce del file, se è ancora quella del file com'è adesso. */
    @Synchronized
    fun entry(stamp: FileStamp): Entry? = valid(stamp)

    /** Tutte le voci con una proposta, per ID del file (anche di file cambiati: chi chiama li confronta). */
    @Synchronized
    fun proposals(): Map<Long, Entry> = entries.filterValues { it.proposal != null }

    /** Quanti file non sono stati riconosciuti. */
    @Synchronized
    fun notFound(): Int = entries.values.count { it.proposal == null }

    @Synchronized
    fun decide(ids: Collection<Long>, decision: Decision) {
        ids.forEach { id -> entries[id]?.takeIf { it.proposal != null }?.let { entries[id] = it.copy(decision = decision) } }
    }

    /** L'utente sceglie un altro dei dischi possibili: vale come "Applica" per quel file. */
    @Synchronized
    fun choose(id: Long, candidate: Int) {
        val e = entries[id] ?: return
        val p = e.proposal ?: return
        if (candidate in p.candidates.indices) entries[id] = e.copy(proposal = p.copy(chosen = candidate), decision = Decision.ACCEPTED)
    }

    /** "Riprova": i file tornano da riconoscere (es. dopo aver messo la chiave giusta). */
    @Synchronized
    fun forget(ids: Collection<Long>) {
        entries.keys.removeAll(ids.toSet())
    }

    /** I tag da usare per il file: solo se la proposta è stata accettata e il file non è cambiato. */
    @Synchronized
    fun tagsFor(stamp: FileStamp): FileTags? = valid(stamp)?.takeIf { it.decision == Decision.ACCEPTED }?.proposal?.tags

    /**
     * Le proposte in attesa si mettono d'accordo per cartella ([Recognition.harmonize]),
     * contando anche quelle già accettate; quelle decise non cambiano.
     */
    @Synchronized
    fun harmonize(folderOf: (Long) -> String) {
        val withProposal = entries.filterValues { it.proposal != null && it.decision != Decision.REJECTED }
        val harmonized = Recognition.harmonize(withProposal.mapValues { it.value.proposal!! }, folderOf)
        harmonized.forEach { (id, p) ->
            val e = entries.getValue(id)
            if (e.decision == Decision.PENDING && e.proposal != p) entries[id] = e.copy(proposal = p)
        }
    }

    /** I file non riconosciuti tornano da riconoscere. */
    @Synchronized
    fun forgetNotFound() {
        entries.values.removeAll { it.proposal == null }
    }

    /** Toglie i file che non ci sono più; true se ne ha tolto qualcuno. */
    @Synchronized
    fun retain(ids: Set<Long>): Boolean = entries.keys.retainAll(ids)

    @Synchronized
    fun toJson(): String = json.encodeToString(Stored.serializer(), Stored(VERSION, HashMap(entries)))

    companion object {
        /** Da aumentare quando cambia cosa si propone: i file si riconoscono da capo (le decisioni si perdono). */
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun fromJson(text: String?): RecognitionIndex {
            val stored = text?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            return if (stored == null || stored.version != VERSION) RecognitionIndex() else RecognitionIndex(HashMap(stored.entries))
        }
    }
}
