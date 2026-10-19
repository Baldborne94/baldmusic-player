package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Backup
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupHealthMoreTest {
    private fun t(
        id: Long, title: String, artist: String = "Artista", folder: String = "Music/Disney Italia", ms: Long = 200_000,
        album: String? = "Album", albumId: Long = 1, track: Int = 0, year: Int = 2000,
    ) = LocalTrack(id, title, artist, 1, album, albumId, ms, 0, track, year, 0, folder)

    private fun ref(t: LocalTrack) = LocalCatalog.ref(t)
    private fun present(tracks: List<LocalTrack>) = tracks.mapTo(HashSet()) { ref(it).id }

    // --- Backup ---

    @Test
    fun `una libreria vuota si salva e si ripristina vuota`() {
        val backup = LibraryBackup.decode(LibraryBackup.encode(LibraryBackup.create(UserLibrary(), emptyList(), now = 1)))!!
        assertEquals(1, backup.createdAt)
        assertTrue(backup.files.isEmpty())
        val plan = LibraryBackup.restore(backup, emptyList())
        assertEquals(UserLibrary(), plan.library)
        assertEquals(0, plan.localTracks)
        assertEquals(0, plan.found)
    }

    @Test
    fun `i brani del server restano come sono e non contano tra quelli del telefono`() {
        val server = TrackRef("remote/track/abc", "Dal server", "Queen")
        val phone = t(1, "Hakuna Matata")
        val lib = UserLibrary(
            liked = listOf(server),
            playlists = listOf(UserPlaylist("p", "Mista", listOf(server, ref(phone)))),
            plays = mapOf(server.id to PlayCount(server, 4, 9)),
        )
        val backup = LibraryBackup.create(lib, listOf(phone), now = 0)
        assertEquals(setOf(ref(phone).id), backup.files.keys)
        // Telefono vuoto: sparisce solo il brano del telefono.
        val plan = LibraryBackup.restore(backup, emptyList())
        assertEquals(1, plan.localTracks)
        assertEquals(0, plan.found)
        assertEquals(listOf(server.id), plan.library.playlists.single().tracks.map { it.id })
        assertEquals(listOf(server.id), plan.library.liked.map { it.id })
        assertEquals(4, plan.library.plays.getValue(server.id).count)
    }

    @Test
    fun `due brani con gli ID scambiati tornano ognuno al suo posto`() {
        val a = t(1, "Hakuna Matata")
        val b = t(2, "Il cerchio della vita")
        val lib = UserLibrary(
            liked = listOf(ref(a)),
            playlists = listOf(UserPlaylist("c", "Disney Italia", listOf(ref(a), ref(b)), folder = "Music/Disney Italia")),
            plays = mapOf(ref(a).id to PlayCount(ref(a), 5, 10), ref(b).id to PlayCount(ref(b), 2, 20)),
        )
        val backup = LibraryBackup.create(lib, listOf(a, b), now = 0)
        val swapped = listOf(t(2, "Hakuna Matata"), t(1, "Il cerchio della vita"))
        val plan = LibraryBackup.restore(backup, swapped)
        assertEquals(2, plan.found)
        assertEquals(listOf("local/track/2"), plan.library.liked.map { it.id })
        assertEquals(listOf("Hakuna Matata", "Il cerchio della vita"), plan.library.playlists.single().tracks.map { it.title })
        assertEquals(listOf("local/track/2", "local/track/1"), plan.library.playlists.single().tracks.map { it.id })
        assertEquals(5, plan.library.plays.getValue("local/track/2").count)
        assertEquals(2, plan.library.plays.getValue("local/track/1").count)
    }

    @Test
    fun `un brano tolto a mano da una raccolta resta fuori anche su un telefono nuovo`() {
        val old = listOf(t(1, "Hakuna Matata"), t(2, "Il cerchio della vita"), t(3, "Sotto il mare"))
        var lib = UserLibrary().syncFolders(LocalCatalog.folderCollections(old), present(old), 1) { "c" }
        lib = lib.removeFromPlaylist("c", ref(old[2]).id)
        val backup = LibraryBackup.decode(LibraryBackup.encode(LibraryBackup.create(lib, old, now = 0)))!!
        // Telefono nuovo: altri ID, e il vecchio ID del brano tolto ora è "Hakuna Matata".
        val now = listOf(t(3, "Hakuna Matata"), t(12, "Il cerchio della vita"), t(13, "Sotto il mare"))
        val plan = LibraryBackup.restore(backup, now)
        assertEquals(2, plan.localTracks)
        assertEquals(setOf("local/track/13"), plan.library.collections.single().removed)
        val synced = plan.library.syncFolders(LocalCatalog.folderCollections(now), present(now), 2) { "x" }
        assertEquals(listOf("Hakuna Matata", "Il cerchio della vita"), synced.collections.single().tracks.map { it.title })
    }

    @Test
    fun `un backup senza la chiave dei brani tolti li tiene come sono`() {
        val lib = UserLibrary(playlists = listOf(UserPlaylist("c", "Disney", folder = "Music/Disney Italia", removed = setOf("local/track/3"))))
        val plan = LibraryBackup.restore(Backup(library = lib), listOf(t(3, "Sotto il mare")))
        assertEquals(setOf("local/track/3"), plan.library.collections.single().removed)
    }

    @Test
    fun `un backup di una versione piu nuova non si legge, campi in piu si`() {
        assertNull(LibraryBackup.decode("""{"app":"BaldMusic","format":${Backup.FORMAT + 1}}"""))
        assertEquals(7, LibraryBackup.decode("""{"app":"BaldMusic","format":1,"createdAt":7,"futuro":[1,2]}""")?.createdAt)
    }

    // --- Salute della libreria ---

    @Test
    fun `una libreria vuota non ha niente da sistemare`() {
        val report = LibraryHealth.check(emptyList())
        assertEquals(0, report.total)
        assertEquals(0, report.toFix)
        assertTrue(report.counts.isEmpty())
        assertTrue(report.folders.isEmpty())
    }

    @Test
    fun `tre brani con album diversi non bastano per dire album per ogni brano`() {
        val tracks = (1..3L).map { t(it, "Brano $it", album = "Brano $it", albumId = it) }
        val problems = LibraryHealth.check(tracks).folders.single().tracks.map { it.problems.first() }
        assertEquals(listOf(TagProblem.ALONE, TagProblem.ALONE, TagProblem.ALONE), problems)
    }

    @Test
    fun `un album vero del 1970 conta come anno da sistemare`() {
        val paranoid = (1..3L).map { t(it, "Paranoid $it", folder = "Music/Black Sabbath", album = "Paranoid", track = it.toInt(), year = 1970) }
        val report = LibraryHealth.check(paranoid)
        assertEquals(3, report.counts[TagProblem.NO_YEAR])
        assertEquals(0, report.loose)
    }

    @Test
    fun `i file sciolti fanno cartella a se, dalla piu malmessa`() {
        val loose = listOf(t(1, "Sciolto", folder = "Music", album = null), t(2, "Nota", folder = "Android/media/app", album = null))
        val disney = (3..7L).map { t(it, "Brano $it", album = "Brano $it", albumId = it) }
        val report = LibraryHealth.check(loose + disney)
        assertEquals(listOf("Disney Italia", "app", "Music"), report.folders.map { it.name })
        assertEquals("Android/media/app", report.folders[1].folder)
        assertEquals(7, report.loose)
        assertEquals(2, report.counts[TagProblem.NO_ALBUM])
        val music = report.folders.first { it.name == "Music" }
        assertEquals(LooseReason.NO_ALBUM, music.tracks.single().loose)
        assertEquals(0, music.ok)
    }

    @Test
    fun `un brano a posto ma mai passato da Picard non va sistemato`() {
        val ok = (1..2L).map { t(it, "Brano $it", folder = "Music/Queen", album = "Innuendo", track = it.toInt(), year = 1991) }
        val report = LibraryHealth.check(ok)
        assertEquals(2, report.notTagged)
        assertEquals(0, report.toFix)
        assertFalse(report.folders.any())
    }
}
