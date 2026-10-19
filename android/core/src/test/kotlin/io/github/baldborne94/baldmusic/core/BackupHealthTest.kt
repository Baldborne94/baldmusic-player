package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.LibraryBackup
import io.github.baldborne94.baldmusic.core.library.LibraryHealth
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.LooseReason
import io.github.baldborne94.baldmusic.core.library.PlayCount
import io.github.baldborne94.baldmusic.core.library.TagProblem
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import io.github.baldborne94.baldmusic.core.library.UserPlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupHealthTest {
    private fun t(
        id: Long, title: String, artist: String = "Artista", folder: String = "Music/Disney Italia", ms: Long = 200_000,
        album: String? = "Album", albumId: Long = 1, track: Int = 0, year: Int = 0, mbid: String? = null,
    ) = LocalTrack(id, title, artist, 1, album, albumId, ms, 0, track, year, 0, folder, albumMbid = mbid)

    private fun ref(t: LocalTrack) = LocalCatalog.ref(t)

    // --- Backup ---

    @Test
    fun `sullo stesso telefono il backup torna uguale`() {
        val a = t(1, "Hakuna Matata")
        val b = t(2, "Il cerchio della vita")
        val lib = UserLibrary(
            liked = listOf(ref(a)),
            playlists = listOf(UserPlaylist("p", "Disney", listOf(ref(a), ref(b)), folder = "Music/Disney Italia")),
        )
        val backup = LibraryBackup.decode(LibraryBackup.encode(LibraryBackup.create(lib, listOf(a, b), now = 5)))!!
        val plan = LibraryBackup.restore(backup, listOf(a, b))
        assertEquals(lib, plan.library)
        assertEquals(2, plan.found)
        assertEquals(emptyList(), plan.missing)
    }

    @Test
    fun `su un telefono nuovo i brani si ritrovano per cartella e titolo anche con ID diversi`() {
        val old = listOf(t(1, "Hakuna Matata"), t(2, "Il cerchio della vita"), t(3, "Sparito"))
        val server = TrackRef("server/track/x", "Dal server")
        val lib = UserLibrary(
            liked = listOf(ref(old[0]), server),
            playlists = listOf(UserPlaylist("p", "Disney", old.map(::ref), folder = "Music/Disney Italia", removed = setOf(ref(old[1]).id))),
            plays = mapOf(ref(old[0]).id to PlayCount(ref(old[0]), 7, 100)),
        )
        val backup = LibraryBackup.create(lib, old, now = 5)
        // Telefono nuovo: altri ID, uno è scambiato con un vecchio ID ("2" ora è Hakuna Matata), un file manca.
        val now = listOf(t(2, "Hakuna Matata", ms = 201_000), t(40, "Il Cerchio Della Vita"))
        val plan = LibraryBackup.restore(backup, now)
        assertEquals(3, plan.localTracks)
        assertEquals(2, plan.found)
        assertEquals(listOf("Sparito"), plan.missing.map { it.title })
        assertEquals(listOf(ref(now[0]).id, server.id), plan.library.liked.map { it.id })
        val pl = plan.library.playlists.single()
        assertEquals(listOf(ref(now[0]).id, ref(now[1]).id), pl.tracks.map { it.id })
        assertEquals(setOf(ref(now[1]).id), pl.removed)
        assertEquals(7, plan.library.plays[ref(now[0]).id]?.count)
    }

    @Test
    fun `senza la cartella giusta vale artista e titolo, ma non una durata diversa`() {
        val old = t(1, "Numb", artist = "Linkin Park", folder = "Music/A")
        val backup = LibraryBackup.create(UserLibrary(liked = listOf(ref(old))), listOf(old), now = 0)
        val moved = t(9, "Numb", artist = "Linkin Park", folder = "Music/B")
        assertEquals(listOf(ref(moved).id), LibraryBackup.restore(backup, listOf(moved)).library.liked.map { it.id })
        val live = t(9, "Numb", artist = "Linkin Park", folder = "Music/B", ms = 260_000)
        assertEquals(emptyList(), LibraryBackup.restore(backup, listOf(live)).library.liked)
    }

    @Test
    fun `un file che non e un backup non si legge`() {
        assertNull(LibraryBackup.decode("{}".replace("{}", """{"app":"Altro"}""")))
        assertNull(LibraryBackup.decode("non json"))
    }

    // --- Salute della libreria ---

    @Test
    fun `cartella con un album diverso per ogni brano`() {
        val tracks = (1..5L).map { t(it, "Brano $it", album = "Brano $it", albumId = it) }
        val report = LibraryHealth.check(tracks)
        assertEquals(5, report.toFix)
        assertEquals(5, report.notTagged)
        val folder = report.folders.single()
        assertEquals("Disney Italia", folder.name)
        assertTrue(folder.tracks.all { it.problems.first() == TagProblem.ALBUM_PER_TRACK })
        assertTrue(folder.tracks.all { it.loose == LooseReason.ALONE })
    }

    @Test
    fun `un album completo e taggato sta bene, anche se mai passato da Picard conta a parte`() {
        val good = (1..3L).map { t(it, "Brano $it", folder = "Music/Queen", album = "A Night at the Opera", track = it.toInt(), year = 1975, mbid = "mb") }
        val untagged = (4..5L).map { t(it, "Altro $it", folder = "Music/Queen", album = "Greatest Hits", albumId = 2, track = (it - 3).toInt(), year = 1981) }
        val report = LibraryHealth.check(good + untagged)
        assertEquals(0, report.toFix)
        assertEquals(2, report.notTagged)
        assertEquals(emptyList(), report.folders)
    }

    @Test
    fun `senza numero di traccia, numero doppio, anno mancante e senza album`() {
        val tracks = listOf(
            t(1, "Uno", album = "Disco", track = 1, year = 2001),
            t(2, "Due", album = "Disco", track = 1, year = 1970),
            t(3, "Tre", album = "Disco", track = 0, year = 2001),
            t(4, "Sciolto", album = null, year = 2001),
        )
        val problems = LibraryHealth.check(tracks).folders.single().tracks.associate { it.track.title to it.problems }
        assertEquals(listOf(TagProblem.SAME_TRACK_NUMBER, TagProblem.NOT_TAGGED), problems["Uno"])
        assertEquals(listOf(TagProblem.SAME_TRACK_NUMBER, TagProblem.NO_YEAR, TagProblem.NOT_TAGGED), problems["Due"])
        assertEquals(listOf(TagProblem.NO_TRACK_NUMBER, TagProblem.NOT_TAGGED), problems["Tre"])
        assertEquals(listOf(TagProblem.NO_ALBUM, TagProblem.NOT_TAGGED), problems["Sciolto"])
    }
}
