package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.DownloadedSet
import io.github.baldborne94.baldmusic.core.library.ServerDownloads
import io.github.baldborne94.baldmusic.core.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scaricati nella cartella scelta: una sottocartella per raccolta o album, nomi leggibili. */
class DownloadNamesTest {
    private val album = DownloadedSet("remote/album/al1", "Meteora", "Linkin Park", songs = emptyList())
    private val playlist = DownloadedSet("remote/playlist/p1", "Metal Mix", songs = emptyList())

    @Test
    fun `cartella della raccolta o dell'album`() {
        assertEquals("Metal Mix", ServerDownloads.folderName(playlist))
        assertEquals("Linkin Park - Meteora", ServerDownloads.folderName(album))
    }

    @Test
    fun `file dell'album col numero, della playlist con l'artista`() {
        val numb = Song(id = "s1", title = "Numb", artist = "Linkin Park", track = 13, suffix = "mp3")
        assertEquals("13 - Numb.mp3", ServerDownloads.fileName(numb, album))
        assertEquals("Linkin Park - Numb.mp3", ServerDownloads.fileName(numb, playlist))
        // Disco 2 di un album doppio.
        assertEquals("2-03 - X.flac", ServerDownloads.fileName(Song(id = "s2", title = "X", track = 3, discNumber = 2, suffix = "flac"), album))
        // Senza numero o senza estensione dal server.
        assertEquals("Numb.mp3", ServerDownloads.fileName(Song(id = "s3", title = "Numb"), album))
    }

    @Test
    fun `niente caratteri che Android non accetta nei nomi`() {
        val bad = Song(id = "s4", title = "AC/DC: Who? *Live* <\"1979\"> | Remaster", artist = "AC/DC", suffix = "mp3")
        val name = ServerDownloads.fileName(bad, playlist)
        assertTrue(name.none { it in "\\/:*?\"<>|" }, name)
        assertEquals("Senza titolo", ServerDownloads.folderName(DownloadedSet("remote/playlist/p2", "  ...  ")))
        assertTrue(ServerDownloads.fileName(Song(id = "s5", title = "a".repeat(400), suffix = "mp3"), playlist).length <= 130)
    }
}
