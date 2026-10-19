package io.github.baldborne94.baldmusic.core.library

import kotlinx.serialization.Serializable

/**
 * Un tag così come esce dal file: chiave e valori. L'app traduce in questa forma
 * quello che legge (frame ID3, commenti Vorbis di FLAC/Ogg/Opus, atomi MP4):
 * - ID3: l'ID del frame ("TPE2"), o "TXXX:<descrizione>" per i frame liberi;
 * - Vorbis: il nome del campo ("ALBUMARTIST");
 * - MP4: gli atomi noti arrivano già come ID3, quelli liberi come "----:<nome>".
 */
data class RawTag(val key: String, val values: List<String>)

/** Il ReplayGain scritto nei tag (in dB, picchi lineari 0…1). */
@Serializable
data class ReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    /** Misurato dall'app (i file non avevano il tag), non letto dal file. */
    val measured: Boolean = false,
)

/** I tag di un file che servono a BaldMusic; vuoti se il file non si è potuto leggere. */
@Serializable
data class FileTags(
    val title: String? = null,
    /** L'artista come va mostrato, es. "Avril Lavigne feat. Chad Kroeger". */
    val artist: String? = null,
    /** Gli artisti uno per uno (tag ARTISTS di Picard). */
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val artistSort: String? = null,
    val albumArtistSort: String? = null,
    val track: Int = 0,
    val disc: Int = 0,
    val discSubtitle: String? = null,
    val year: Int = 0,
    val originalYear: Int = 0,
    val compilation: Boolean = false,
    val artistMbids: List<String> = emptyList(),
    val albumArtistMbids: List<String> = emptyList(),
    val albumMbid: String? = null,
    val releaseGroupMbid: String? = null,
    /** Tipo di uscita da MusicBrainz, in minuscolo: "album", "single", "ep", "compilation", "live"… */
    val releaseTypes: List<String> = emptyList(),
    val replayGain: ReplayGain? = null,
) {
    /** Qualcosa si è letto: se manca l'album, manca davvero. */
    val isEmpty get() = title == null && artist == null && album == null

    companion object {
        /** Dai tag grezzi ai campi, qualunque sia il formato del file. */
        fun from(raw: List<RawTag>): FileTags {
            val tags = HashMap<String, MutableList<String>>()
            raw.forEach { t ->
                val values = t.values.map { it.trim() }.filter { it.isNotEmpty() }
                if (values.isNotEmpty()) tags.getOrPut(t.key.trim().uppercase()) { mutableListOf() } += values
            }
            fun all(vararg keys: String) = keys.firstNotNullOfOrNull { tags[it] }.orEmpty()
            fun one(vararg keys: String) = all(*keys).firstOrNull()

            val artistMbids = ids(all("TXXX:MUSICBRAINZ ARTIST ID", "MUSICBRAINZ_ARTISTID", "----:MUSICBRAINZ ARTIST ID"))
            return FileTags(
                title = one("TIT2", "TITLE"),
                artist = one("TPE1", "ARTIST"),
                artists = artistList(all("TXXX:ARTISTS", "ARTISTS", "----:ARTISTS"), artistMbids.size),
                album = one("TALB", "ALBUM"),
                albumArtist = one("TPE2", "ALBUMARTIST", "ALBUM ARTIST"),
                artistSort = one("TSOP", "ARTISTSORT"),
                albumArtistSort = one("TSO2", "ALBUMARTISTSORT"),
                track = number(one("TRCK", "TRACKNUMBER")),
                disc = number(one("TPOS", "DISCNUMBER")),
                discSubtitle = one("TSST", "DISCSUBTITLE", "TXXX:DISCSUBTITLE", "----:DISCSUBTITLE"),
                year = year(one("TDRC", "TYER", "DATE", "YEAR")),
                originalYear = year(one("TDOR", "TORY", "ORIGINALDATE", "ORIGINALYEAR", "TXXX:ORIGINALYEAR", "TXXX:ORIGINALDATE", "----:ORIGINALDATE", "----:ORIGINALYEAR")),
                compilation = one("TCMP", "COMPILATION", "TXXX:COMPILATION")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false,
                artistMbids = artistMbids,
                albumArtistMbids = ids(all("TXXX:MUSICBRAINZ ALBUM ARTIST ID", "MUSICBRAINZ_ALBUMARTISTID", "----:MUSICBRAINZ ALBUM ARTIST ID")),
                albumMbid = ids(all("TXXX:MUSICBRAINZ ALBUM ID", "MUSICBRAINZ_ALBUMID", "----:MUSICBRAINZ ALBUM ID")).firstOrNull(),
                releaseGroupMbid = ids(all("TXXX:MUSICBRAINZ RELEASE GROUP ID", "MUSICBRAINZ_RELEASEGROUPID", "----:MUSICBRAINZ RELEASE GROUP ID")).firstOrNull(),
                releaseTypes = all("TXXX:MUSICBRAINZ ALBUM TYPE", "RELEASETYPE", "----:MUSICBRAINZ ALBUM TYPE")
                    .flatMap { it.split('/', ';', ',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
                replayGain = replayGain(
                    track = one("TXXX:REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_GAIN", "----:REPLAYGAIN_TRACK_GAIN"),
                    album = one("TXXX:REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_GAIN", "----:REPLAYGAIN_ALBUM_GAIN"),
                    trackPeak = one("TXXX:REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_TRACK_PEAK", "----:REPLAYGAIN_TRACK_PEAK"),
                    albumPeak = one("TXXX:REPLAYGAIN_ALBUM_PEAK", "REPLAYGAIN_ALBUM_PEAK", "----:REPLAYGAIN_ALBUM_PEAK"),
                    r128Track = one("R128_TRACK_GAIN"),
                    r128Album = one("R128_ALBUM_GAIN"),
                ),
            )
        }

        /** Gli ID MusicBrainz: in ID3 v2.3 più valori finiscono in uno solo, separati da "/" o ";". */
        private fun ids(values: List<String>) =
            values.flatMap { it.split('/', ';') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /**
         * ARTISTS di Picard. In ID3 v2.3 i valori arrivano uniti da "/": si separano
         * solo se tornano col numero di ID degli artisti, così "AC/DC" resta intero.
         */
        private fun artistList(values: List<String>, mbids: Int): List<String> {
            if (values.size != 1 || mbids < 2) return values
            val parts = values[0].split('/', ';').map { it.trim() }.filter { it.isNotEmpty() }
            return if (parts.size == mbids) parts else values
        }

        /** "3/12" → 3, "03" → 3; 0 se manca. */
        private fun number(s: String?) = s?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0

        /**
         * "1999-05-01", "1999" → 1999; 0 se manca. "1970-01-01" è la data zero dei
         * computer, che certi programmi scrivono quando non sanno la data: non è un anno.
         */
        private fun year(s: String?): Int {
            if (s == null || s.trim().startsWith("1970-01-01")) return 0
            return Regex("\\d{4}").find(s)?.value?.toIntOrNull() ?: 0
        }

        private fun db(s: String?) = s?.replace(Regex("(?i)\\s*db\\s*$"), "")?.replace(',', '.')?.trim()?.toFloatOrNull()

        private fun replayGain(track: String?, album: String?, trackPeak: String?, albumPeak: String?, r128Track: String?, r128Album: String?): ReplayGain? {
            // Opus: R128_*_GAIN è in 1/256 di dB rispetto a -23 LUFS; il ReplayGain sta 5 dB più in alto.
            fun r128(s: String?) = s?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
            val rg = ReplayGain(
                trackGain = db(track) ?: r128(r128Track),
                albumGain = db(album) ?: r128(r128Album),
                trackPeak = trackPeak?.replace(',', '.')?.trim()?.toFloatOrNull(),
                albumPeak = albumPeak?.replace(',', '.')?.trim()?.toFloatOrNull(),
            )
            return rg.takeIf { it.trackGain != null || it.albumGain != null }
        }
    }
}
