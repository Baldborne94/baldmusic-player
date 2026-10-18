package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ArtistNames
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.FileTags
import io.github.baldborne94.baldmusic.core.library.RawTag
import io.github.baldborne94.baldmusic.core.library.TagIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagsTest {
    private fun raw(vararg pairs: Pair<String, String>) = pairs.map { (k, v) -> RawTag(k, listOf(v)) }

    @Test
    fun `MP3 di Picard in ID3 v2-3`() {
        val t = FileTags.from(
            raw(
                "TIT2" to "Let Me Go",
                "TPE1" to "Avril Lavigne feat. Chad Kroeger",
                "TPE2" to "Avril Lavigne",
                "TALB" to "Avril Lavigne",
                "TRCK" to "3/13",
                "TPOS" to "1/1",
                "TYER" to "2013",
                "TORY" to "2013",
                "TXXX:ARTISTS" to "Avril Lavigne/Chad Kroeger",
                "TXXX:MusicBrainz Artist Id" to "a-avril/a-chad",
                "TXXX:MusicBrainz Album Artist Id" to "a-avril",
                "TXXX:MusicBrainz Album Id" to "r-1",
                "TXXX:MusicBrainz Release Group Id" to "rg-1",
                "TXXX:MusicBrainz Album Type" to "album",
                "TXXX:REPLAYGAIN_TRACK_GAIN" to "-7.25 dB",
                "TXXX:REPLAYGAIN_TRACK_PEAK" to "0.988",
            ),
        )
        assertEquals("Let Me Go", t.title)
        assertEquals("Avril Lavigne feat. Chad Kroeger", t.artist)
        assertEquals(listOf("Avril Lavigne", "Chad Kroeger"), t.artists)
        assertEquals(listOf("a-avril", "a-chad"), t.artistMbids)
        assertEquals("Avril Lavigne", t.albumArtist)
        assertEquals(3, t.track)
        assertEquals(1, t.disc)
        assertEquals(2013, t.year)
        assertEquals("rg-1", t.releaseGroupMbid)
        assertEquals(listOf("album"), t.releaseTypes)
        assertEquals(-7.25f, t.replayGain?.trackGain)
        assertEquals(0.988f, t.replayGain?.trackPeak)
    }

    @Test
    fun `AC-DC in ID3 v2-3 non si spezza perche ha un ID solo`() {
        val t = FileTags.from(raw("TPE1" to "AC/DC", "TXXX:ARTISTS" to "AC/DC", "TXXX:MusicBrainz Artist Id" to "a-acdc"))
        assertEquals(listOf("AC/DC"), t.artists)
    }

    @Test
    fun `FLAC e Ogg con i commenti Vorbis, anche a valori multipli`() {
        val t = FileTags.from(
            listOf(
                RawTag("title", listOf("Roots Bloody Roots")),
                RawTag("ARTIST", listOf("Sepultura")),
                RawTag("ARTISTS", listOf("Sepultura")),
                RawTag("ALBUMARTIST", listOf("Sepultura")),
                RawTag("ALBUM", listOf("Roots")),
                RawTag("TRACKNUMBER", listOf("1")),
                RawTag("DISCNUMBER", listOf("1")),
                RawTag("DATE", listOf("2017-01-27")),
                RawTag("ORIGINALDATE", listOf("1996-02-20")),
                RawTag("RELEASETYPE", listOf("album", "compilation")),
                RawTag("COMPILATION", listOf("1")),
                RawTag("MUSICBRAINZ_RELEASEGROUPID", listOf("rg-roots")),
                RawTag("REPLAYGAIN_ALBUM_GAIN", listOf("-9.10 dB")),
            ),
        )
        assertEquals("Roots Bloody Roots", t.title)
        assertEquals(2017, t.year)
        assertEquals(1996, t.originalYear)
        assertEquals(listOf("album", "compilation"), t.releaseTypes)
        assertTrue(t.compilation)
        assertEquals(-9.10f, t.replayGain?.albumGain)
        assertNull(t.replayGain?.trackGain)
    }

    @Test
    fun `M4A con gli atomi liberi di iTunes`() {
        val t = FileTags.from(
            raw(
                "TIT2" to "Sober", "TPE1" to "Tool", "TPE2" to "Tool", "TALB" to "Undertow",
                "----:MusicBrainz Album Id" to "r-undertow", "----:MusicBrainz Album Type" to "album",
                "----:replaygain_track_gain" to "-4.5 dB",
            ),
        )
        assertEquals("r-undertow", t.albumMbid)
        assertEquals(listOf("album"), t.releaseTypes)
        assertEquals(-4.5f, t.replayGain?.trackGain)
    }

    @Test
    fun `Opus col guadagno R128`() {
        val t = FileTags.from(raw("TITLE" to "X", "R128_TRACK_GAIN" to "-512"))
        assertEquals(3f, t.replayGain?.trackGain)
    }

    @Test
    fun `la data zero dei computer non e un anno`() {
        assertEquals(0, FileTags.from(raw("TITLE" to "X", "DATE" to "1970-01-01")).year)
        assertEquals(0, FileTags.from(raw("TITLE" to "X", "TDRC" to "1970-01-01T00:00:00Z")).year)
        assertEquals(1970, FileTags.from(raw("TITLE" to "Paranoid", "DATE" to "1970-09-18")).year)
        assertEquals(1970, FileTags.from(raw("TITLE" to "Paranoid", "DATE" to "1970")).year)
    }

    @Test
    fun `un file senza tag e vuoto`() {
        assertTrue(FileTags.from(emptyList()).isEmpty)
        assertNull(FileTags.from(emptyList()).replayGain)
    }

    @Test
    fun `i nomi si confrontano senza maiuscole, accenti e apostrofi diversi`() {
        assertEquals(ArtistNames.key("Guns N' Roses"), ArtistNames.key("guns  n’ roses"))
        assertEquals(ArtistNames.key("Beyoncé"), ArtistNames.key("Beyonce"))
        assertTrue(ArtistNames.isVarious("Various Artists"))
        assertTrue(ArtistNames.isVarious("Artisti Vari"))
    }

    @Test
    fun `si separano solo gli artisti senza dubbi`() {
        assertEquals(listOf("Avril Lavigne", "Chad Kroeger"), ArtistNames.split("Avril Lavigne feat. Chad Kroeger"))
        assertEquals(listOf("Limp Bizkit", "Method Man"), ArtistNames.split("Limp Bizkit ft. Method Man"))
        assertEquals(listOf("Vincent Thoma", "Cristina"), ArtistNames.split("Vincent Thoma/Cristina"))
        assertEquals(listOf("Howard Shore", "Edward Ross"), ArtistNames.split("Howard Shore / Edward Ross"))
        assertEquals(listOf("A", "B"), ArtistNames.split("A; B"))
        assertEquals(listOf("Simon & Garfunkel"), ArtistNames.split("Simon & Garfunkel"))
        assertEquals(listOf("Earth, Wind & Fire"), ArtistNames.split("Earth, Wind & Fire"))
        assertEquals(listOf("AC/DC"), ArtistNames.split("AC/DC"))
        assertEquals(listOf("Rage/Machine"), ArtistNames.split("Rage/Machine", known = setOf(ArtistNames.key("Rage/Machine"))))
    }

    @Test
    fun `gli ospiti scritti nel titolo`() {
        assertEquals(listOf("Chad Kroeger"), ArtistNames.featuredInTitle("Let Me Go (feat. Chad Kroeger)"))
        assertEquals(listOf("A", "B"), ArtistNames.featuredInTitle("Brano [ft. A & B]"))
        assertEquals(emptyList(), ArtistNames.featuredInTitle("Feather"))
    }

    @Test
    fun `l'indice rilegge solo i file nuovi o cambiati`() {
        val index = TagIndex()
        val a = FileStamp(1, 100, 5000)
        val b = FileStamp(2, 100, 6000)
        assertEquals(listOf(a, b), index.stale(listOf(a, b)))
        index.put(a, FileTags(title = "A"))
        index.put(b, FileTags())
        assertEquals(emptyList(), index.stale(listOf(a, b)))
        assertEquals(listOf(a.copy(modified = 200)), index.stale(listOf(a.copy(modified = 200), b)))
        assertEquals("A", index.tags(a)?.title)
        assertTrue(index.retain(setOf(1)))
        assertFalse(index.retain(setOf(1)))
        assertEquals(1, index.size())
    }

    @Test
    fun `l'indice si salva e si rilegge, e uno rovinato riparte vuoto`() {
        val index = TagIndex()
        val a = FileStamp(7, 1, 2)
        index.put(a, FileTags(title = "Sette", artists = listOf("X", "Y")))
        val again = TagIndex.fromJson(index.toJson())
        assertNotNull(again.tags(a))
        assertEquals(listOf("X", "Y"), again.tags(a)?.artists)
        assertEquals(0, TagIndex.fromJson("{rotto").size())
        assertEquals(0, TagIndex.fromJson(null).size())
        assertEquals(0, TagIndex.fromJson("""{"version":0,"entries":{"7":{"modified":1,"size":2,"tags":{}}}}""").size())
        // Un indice della prima versione, salvato senza numero: si rifà.
        assertEquals(0, TagIndex.fromJson("""{"entries":{"7":{"modified":1,"size":2,"tags":{}}}}""").size())
        assertTrue(index.toJson().contains("\"version\":${TagIndex.VERSION}"))
    }
}
