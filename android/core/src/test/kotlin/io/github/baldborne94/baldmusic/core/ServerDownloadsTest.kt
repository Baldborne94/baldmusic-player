package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.DownloadedSet
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.library.ServerDownloads
import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.Song
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerDownloadsTest {
    private fun song(id: String, cover: String? = "al-1") = Song(id, "Brano $id", artist = "Queen", album = "A Night at the Opera", duration = 200, coverArt = cover)
    private val album = DownloadedSet("remote/album/al-1", "A Night at the Opera", "Queen", "al-1", listOf(song("s1"), song("s2")))
    private val playlist = DownloadedSet("remote/playlist/p9", "Corsa", coverArt = "pl-9", songs = listOf(song("s2"), song("s3", cover = "al-3")))

    @Test
    fun `si scarica quello che manca e si cancella quello che non serve piu`() {
        val d = ServerDownloads().with(album).with(playlist)
        assertEquals(listOf("s1", "s3") to setOf("s9"), d.plan(present = setOf("s2", "s9")))
        // Tolto l'album: s2 resta, perché c'è anche nella playlist.
        assertEquals(emptyList<String>() to setOf("s1"), d.without("remote/album/al-1").plan(present = setOf("s1", "s2", "s3")))
        assertEquals(setOf("al-1", "pl-9", "al-3"), d.covers)
    }

    @Test
    fun `scaricata di nuovo, una playlist cambiata sul PC prende il posto di quella di prima`() {
        val d = ServerDownloads().with(playlist).with(playlist.copy(songs = listOf(song("s4"))))
        assertEquals(1, d.sets.size)
        assertEquals(setOf("s4"), d.songIds)
    }

    @Test
    fun `si scaricano solo album e playlist del server`() {
        assertTrue(ServerDownloads.downloadable("remote/album/al-1"))
        assertTrue(ServerDownloads.downloadable("remote/playlist/p9"))
        assertFalse(ServerDownloads.downloadable("remote/artist/ar-1"))
        assertFalse(ServerDownloads.downloadable("local/album/x"))
        assertFalse(ServerDownloads.downloadable("remote/track/s1|remote/album/al-1"))
    }

    @Test
    fun `senza rete i scaricati si sfogliano e suonano dal file`() = runTest {
        // Un server che non risponde: niente di quello che segue deve chiedergli qualcosa.
        val client = SubsonicClient(Credentials.create("http://127.0.0.1:9", "u", "p", "s"), OkHttpClient())
        val d = ServerDownloads().with(album)
        val source = RemoteSource(client, offline = { d }, fileOf = { id -> "file:///musica/$id.audio".takeIf { id == "s1" } })
        val section = source.sections().first()
        assertEquals("Scaricati sul telefono", section.title)
        val sets = source.children(MediaId.parse(section.mediaId)!!)
        assertEquals(listOf("A Night at the Opera"), sets.map { it.title })
        assertEquals("Queen · 2 brani", sets.single().subtitle)
        val tracks = source.children(MediaId.parse(sets.single().mediaId)!!)
        assertEquals(listOf("Brano s1", "Brano s2"), tracks.map { it.title })
        val played = source.track(MediaId.parse(tracks.first().mediaId)!!)!!
        assertEquals("file:///musica/s1.audio", played.streamUri)
        // Non ancora scaricato del tutto: dal server.
        assertTrue(source.track(MediaId.parse(tracks[1].mediaId)!!)!!.streamUri!!.contains("/rest/stream"))
    }

    @Test
    fun `senza niente scaricato la sezione non c'e`() = runTest {
        val client = SubsonicClient(Credentials.create("http://127.0.0.1:9", "u", "p", "s"), OkHttpClient())
        assertFalse(RemoteSource(client).sections().any { it.title == "Scaricati sul telefono" })
    }
}
