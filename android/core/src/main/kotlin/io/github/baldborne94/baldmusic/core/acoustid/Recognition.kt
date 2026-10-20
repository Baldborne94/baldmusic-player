package io.github.baldborne94.baldmusic.core.acoustid

import io.github.baldborne94.baldmusic.core.library.ArtistNames
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import kotlinx.serialization.Serializable
import kotlin.math.abs

// Dalla risposta di AcoustID ai tag da usare per un file: quale registrazione, su quale
// disco. Regole pure, testate; non tocca i file: i tag proposti si sovrappongono a
// quelli letti dal file solo quando l'utente li accetta (vedi RecognitionIndex).

/** Un disco possibile per il file, coi tag che ne vengono. */
@Serializable
data class Candidate(
    val releaseGroupMbid: String,
    val tags: FileTags,
    /** Più basso = disco migliore (album originale, uscito per primo). */
    val rank: Int,
)

/** Cosa si propone per un file: i dischi possibili, il primo è quello scelto. */
@Serializable
data class Proposal(
    /** Somiglianza dell'impronta secondo AcoustID, da 0 a 1. */
    val score: Double,
    val recordingMbid: String,
    val candidates: List<Candidate>,
    /** Quale dei [candidates] usare (cambia con [Recognition.harmonize]). */
    val chosen: Int = 0,
) {
    val tags: FileTags get() = candidates[chosen].tags

    /** Abbastanza sicuro da applicare con "Applica quelli sicuri". */
    val sure get() = score >= SURE_SCORE

    companion object {
        const val SURE_SCORE = 0.9
    }
}

object Recognition {

    /** Sotto questa somiglianza non è lo stesso brano (AcoustID ne manda anche di vaghi). */
    const val MIN_SCORE = 0.6

    /** La registrazione deve durare quanto il file, a meno di qualche secondo (silenzi, versioni tagliate). */
    private const val SAME_LENGTH_SEC = 15.0

    /**
     * Il disco migliore: prima gli album (anche le colonne sonore), poi EP e singoli, poi
     * il resto; raccolte, dal vivo e remix in fondo. A parità, quello uscito per primo.
     */
    private fun rankOf(rg: MbReleaseGroup, fileLooksLive: Boolean): Int {
        val primary = when (rg.type?.lowercase()) {
            "album" -> 0
            "ep" -> 1
            "single" -> 2
            null, "" -> 3
            else -> 4
        }
        val secondary = rg.secondarytypes.map { it.lowercase() }
        val penalty = secondary.sumOf { s ->
            when (s) {
                "soundtrack" -> 0
                "compilation" -> 10
                "live" -> if (fileLooksLive) -1 else 20
                "remix", "dj-mix", "mixtape/street" -> 30
                else -> 40 // demo, interview, spokenword, audiobook…
            }.toInt()
        }
        return penalty * 10 + primary
    }

    private fun firstYear(rg: MbReleaseGroup) = rg.releases.mapNotNull { it.date?.year?.takeIf { y -> y > 0 } }.minOrNull() ?: 0

    private fun credit(artists: List<MbArtist>): String? {
        if (artists.isEmpty()) return null
        return buildString {
            artists.forEachIndexed { i, a ->
                append(a.name)
                append(a.joinphrase ?: if (i < artists.size - 1) ", " else "")
            }
        }.trim().ifBlank { null }
    }

    private fun tagsFor(recording: MbRecording, rg: MbReleaseGroup): FileTags {
        // L'edizione più vecchia su cui il brano c'è davvero, per numero di traccia e disco.
        val release = rg.releases
            .filter { r -> r.mediums.any { m -> m.tracks.isNotEmpty() } }
            .minWithOrNull(compareBy({ it.date?.year?.takeIf { y -> y > 0 } ?: Int.MAX_VALUE }, { it.date?.month ?: 13 }, { it.date?.day ?: 32 }))
            ?: rg.releases.firstOrNull()
        val medium = release?.mediums?.firstOrNull { it.tracks.isNotEmpty() }
        val track = medium?.tracks?.firstOrNull()
        val various = rg.artists.any { it.id == ArtistNames.VARIOUS_MBID }
        val releaseYear = release?.date?.year ?: 0
        return FileTags(
            title = track?.title ?: recording.title,
            artist = credit(track?.artists?.ifEmpty { null } ?: recording.artists),
            artists = recording.artists.map { it.name },
            album = rg.title.ifBlank { release?.title },
            albumArtist = credit(rg.artists),
            track = track?.position ?: 0,
            disc = medium?.position ?: 0,
            year = releaseYear,
            originalYear = firstYear(rg).takeIf { it > 0 } ?: releaseYear,
            compilation = various,
            artistMbids = recording.artists.mapNotNull { it.id },
            albumArtistMbids = rg.artists.mapNotNull { it.id },
            albumMbid = release?.id,
            releaseGroupMbid = rg.id,
            releaseTypes = listOfNotNull(rg.type?.lowercase()) + rg.secondarytypes.map { it.lowercase() },
        )
    }

    /**
     * La proposta per un file lungo [durationMs], dalla risposta di AcoustID; null se
     * niente somiglia abbastanza. Tra più registrazioni con la stessa impronta vince quella
     * col titolo più vicino a quello del file (se il file un titolo ce l'ha).
     */
    fun propose(results: List<AcoustIdResult>, durationMs: Long, fileTitle: String?): Proposal? {
        val seconds = durationMs / 1000.0
        val looksLive = fileTitle?.let { Regex("\\b(live|dal vivo)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
        val title = fileTitle?.let(ArtistNames::key)
        data class Option(val score: Double, val recording: MbRecording)
        val options = results.filter { it.score >= MIN_SCORE }.flatMap { r ->
            r.recordings.filter { rec ->
                rec.releasegroups.isNotEmpty() && (rec.duration == null || seconds <= 0 || abs(rec.duration - seconds) <= SAME_LENGTH_SEC)
            }.map { Option(r.score, it) }
        }
        val best = options.maxWithOrNull(
            compareBy<Option> { it.score }
                .thenBy { o -> if (title != null && o.recording.title?.let(ArtistNames::key) == title) 1 else 0 }
                .thenBy { o -> o.recording.releasegroups.size },
        ) ?: return null
        val candidates = best.recording.releasegroups
            .map { rg -> Candidate(rg.id, tagsFor(best.recording, rg), rankOf(rg, looksLive)) }
            .sortedWith(compareBy({ it.rank }, { it.tags.originalYear.takeIf { y -> y > 0 } ?: Int.MAX_VALUE }, { it.tags.album }))
        return Proposal(best.score, best.recording.id, candidates)
    }

    /**
     * I file di una stessa cartella che escono sullo stesso disco ci vanno insieme: se un
     * disco va bene per almeno due file della cartella, ognuno di loro sceglie quello (tra
     * i dischi buoni, non una raccolta qualunque), così un album scaricato coi tag sbagliati
     * torna un album invece di spargersi su raccolte diverse. In una cartella-playlist, con
     * brani di dischi diversi, ognuno resta sul suo.
     */
    fun harmonize(proposals: Map<Long, Proposal>, folderOf: (Long) -> String): Map<Long, Proposal> {
        val out = HashMap(proposals)
        proposals.keys.groupBy(folderOf).values.forEach { ids ->
            if (ids.size < 2) return@forEach
            val count = HashMap<String, Int>()
            ids.forEach { id -> proposals.getValue(id).candidates.map { it.releaseGroupMbid }.distinct().forEach { count.merge(it, 1, Int::plus) } }
            ids.forEach { id ->
                val p = proposals.getValue(id)
                val bestRank = p.candidates.first().rank
                // Solo dischi buoni quanto il migliore: mai una raccolta o un singolo al posto dell'album.
                val shared = p.candidates.withIndex()
                    .filter { (_, c) -> c.rank == bestRank && (count[c.releaseGroupMbid] ?: 0) >= 2 }
                    .maxWithOrNull(compareBy({ count[it.value.releaseGroupMbid] ?: 0 }, { -it.index }))
                if (shared != null && shared.index != p.chosen) out[id] = p.copy(chosen = shared.index)
            }
        }
        return out
    }

    /**
     * Il brano coi tag riconosciuti al posto di quelli del file. Restano del file il
     * ReplayGain, i nomi di ordinamento e il titolo del disco, che AcoustID non dà; la
     * copertina diventa quella del disco riconosciuto ([LocalTrack.coverMbid]).
     */
    fun applyTo(track: LocalTrack, tags: FileTags): LocalTrack = track.withTags(
        tags.copy(
            replayGain = track.replayGain,
            artistSort = track.artistSort,
            albumArtistSort = track.albumArtistSort,
            discSubtitle = track.discSubtitle,
        ),
    ).copy(coverMbid = tags.releaseGroupMbid)
}
