package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.FolderCollection
import io.github.baldborne94.baldmusic.core.library.LibraryCleanup
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.LocalTrack
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanupTest {
    private var next = 1L
    private fun t(title: String, artist: String, ms: Long, folder: String, added: Long = 0, albumArtist: String? = null) =
        LocalTrack(next++, title, artist, 1, "Album", 1, ms, 0, 0, 0, added, folder, albumArtist = albumArtist)

    @Test
    fun `lo stesso brano in due cartelle e un doppione, si tiene la copia piu completa`() {
        val a = t("In the End", "Linkin Park", 216_000, "Music/90s Metal Classics", added = 10)
        val b = t("In The End", "LINKIN PARK", 217_500, "Music/Linkin Park", added = 20, albumArtist = "Linkin Park")
        val other = t("Numb", "Linkin Park", 185_000, "Music/Linkin Park")
        val groups = LibraryCleanup.duplicates(listOf(a, b, other))
        assertEquals(1, groups.size)
        assertEquals(b.id, groups.single().keep.id)
        assertEquals(listOf(a.id), groups.single().others.map { it.id })
    }

    @Test
    fun `a parita di tag si tiene la copia arrivata prima`() {
        val first = t("Sober", "Tool", 306_000, "Music/B", added = 5)
        val second = t("Sober", "Tool", 306_000, "Music/A", added = 9)
        assertEquals(first.id, LibraryCleanup.duplicates(listOf(second, first)).single().keep.id)
    }

    @Test
    fun `una versione di durata diversa non e un doppione, e nemmeno un'anteprima`() {
        val studio = t("Alice", "Avril Lavigne", 300_000, "Music/A")
        val extended = t("Alice", "Avril Lavigne", 420_000, "Music/B")
        val preview = t("Alice", "Avril Lavigne", 30_000, "Music/C")
        assertTrue(LibraryCleanup.duplicates(listOf(studio, extended, preview)).isEmpty())
        assertEquals(listOf(preview.id), LibraryCleanup.previews(listOf(studio, extended, preview)).map { it.id })
    }

    @Test
    fun `le anteprime sono i brani di circa 30 secondi`() {
        val tracks = listOf(t("A", "x", 29_500, "Music/X"), t("B", "x", 31_000, "Music/X"), t("C", "x", 60_000, "Music/X"), t("D", "x", 12_000, "Music/X"))
        assertEquals(listOf("A", "B"), LibraryCleanup.previews(tracks).map { it.title })
    }

    @Test
    fun `buttato il doppione, la sua raccolta tiene la copia rimasta`() {
        val keep = t("In the End", "Linkin Park", 216_000, "Music/Linkin Park")
        val gone = t("In the End", "Linkin Park", 216_000, "Music/90s Metal Classics")
        val numb = t("Numb", "Linkin Park", 185_000, "Music/90s Metal Classics")
        val keepRef = LocalCatalog.ref(keep)
        val goneRef = LocalCatalog.ref(gone)
        var lib = UserLibrary().syncFolders(LocalCatalog.folderCollections(listOf(keep, gone, numb)), setOf(keepRef.id, goneRef.id, LocalCatalog.ref(numb).id), 1) { "c${next++}" }
        lib = lib.toggleLike(goneRef).toggleLike(keepRef).recordPlay(goneRef, null, 5).recordPlay(keepRef, null, 7)
        lib = lib.mergeTracks(mapOf(goneRef.id to keepRef))
        val metal = lib.collections.first { it.name == "90s Metal Classics" }
        assertEquals(listOf(keepRef.id, LocalCatalog.ref(numb).id), metal.tracks.map { it.id })
        assertEquals(listOf(keepRef.id), lib.liked.map { it.id })
        assertEquals(2, lib.plays.getValue(keepRef.id).count)
        // Alla rilettura la copia rimasta resta nella raccolta, anche se il file sta in un'altra cartella.
        val after = lib.syncFolders(LocalCatalog.folderCollections(listOf(keep, numb)), setOf(keepRef.id, LocalCatalog.ref(numb).id), 2) { "x" }
        assertEquals(listOf(keepRef.id, LocalCatalog.ref(numb).id), after.collections.first { it.name == "90s Metal Classics" }.tracks.map { it.id })
    }

    @Test
    fun `senza sostituzioni la libreria resta la stessa`() {
        val lib = UserLibrary().syncFolders(listOf(FolderCollection("Music/A", "A", listOf(TrackRef("local/track/1", "X")))), setOf("local/track/1"), 1) { "a" }
        assertTrue(lib.mergeTracks(emptyMap()) === lib)
    }

    @Test
    fun `un doppione ripescato dal cestino torna al suo posto nella raccolta`() {
        val keep = t("In the End", "Linkin Park", 216_000, "Music/Linkin Park")
        val gone = t("In the End", "Linkin Park", 216_000, "Music/90s Metal Classics")
        val numb = t("Numb", "Linkin Park", 185_000, "Music/90s Metal Classics")
        val present = { list: List<LocalTrack> -> list.mapTo(HashSet()) { LocalCatalog.ref(it).id } }
        var lib = UserLibrary().syncFolders(LocalCatalog.folderCollections(listOf(gone, numb, keep)), present(listOf(gone, numb, keep)), 1) { "c${next++}" }
        // Pulizia: la copia in "90s Metal Classics" va nel cestino e la raccolta passa a quella tenuta.
        lib = lib.mergeTracks(mapOf(LocalCatalog.ref(gone).id to LocalCatalog.ref(keep))).forgetTracks(listOf(LocalCatalog.ref(gone).id))
        lib = lib.syncFolders(LocalCatalog.folderCollections(listOf(numb, keep)), present(listOf(numb, keep)), 2) { "x" }
        val metal = { l: UserLibrary -> l.collections.first { it.name == "90s Metal Classics" }.tracks.map { it.id } }
        assertEquals(listOf(LocalCatalog.ref(keep).id, LocalCatalog.ref(numb).id), metal(lib))
        // Ripescata: l'allineamento la rimette in fondo, il ripristino al suo posto al posto della copia.
        val all = listOf(gone, numb, keep)
        lib = lib.syncFolders(LocalCatalog.folderCollections(all), present(all), 3) { "y" }
        lib = LibraryCleanup.restoreInto(lib, listOf(gone), all)
        assertEquals(listOf(LocalCatalog.ref(gone).id, LocalCatalog.ref(numb).id), metal(lib))
        // La raccolta "Linkin Park" non cambia.
        assertEquals(listOf(LocalCatalog.ref(keep).id), lib.collections.first { it.name == "Linkin Park" }.tracks.map { it.id })
    }

    @Test
    fun `un brano ripescato senza sostituti resta dove lo mette la cartella`() {
        val a = t("A", "x", 200_000, "Music/X")
        val b = t("B", "x", 210_000, "Music/X")
        val lib = UserLibrary().syncFolders(LocalCatalog.folderCollections(listOf(a, b)), setOf(LocalCatalog.ref(a).id, LocalCatalog.ref(b).id), 1) { "c" }
        assertTrue(LibraryCleanup.restoreInto(lib, listOf(b), listOf(a, b)) == lib)
    }
}
