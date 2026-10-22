package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.ContainerRef
import io.github.baldborne94.baldmusic.core.library.Crossfade
import io.github.baldborne94.baldmusic.core.library.LibraryBackup
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.TrackRef
import io.github.baldborne94.baldmusic.core.library.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lavoro che non si fa quando non serve: pagine, backup e dissolvenza. */
class QuietUpdatesTest {
    private fun track(n: Int) = TrackRef("local/track/$n", "Brano $n")
    private val album = ContainerRef("local/album/1", "Disco", kind = "album")

    private val lib = UserLibrary()
        .toggleLike(track(1))
        .createPlaylist("p1", "Strada", 0, listOf(track(2), track(3)))
    private val afterPlay = lib.recordPlay(track(4), album, now = 1_000)

    private fun shown(l: UserLibrary, page: String) = PersonalSource.shownBy(l, page)

    @Test
    fun `un ascolto non ricarica preferiti, playlist e raccolte`() {
        // Prima ogni ascolto ricaricava tutte le pagine della tua musica (in app e in Auto).
        listOf("me/liked", "me/playlists", "me/collections", "me/playlist/p1", "me").forEach { page ->
            assertEquals(shown(lib, page), shown(afterPlay, page), page)
        }
        // Quelle che mostrano gli ascolti invece si aggiornano.
        listOf("me/recent", "me/top", "me/mixes", "me/mix/rediscover").forEach { page ->
            assertNotEquals(shown(lib, page), shown(afterPlay, page), page)
        }
    }

    @Test
    fun `cambiano le pagine che mostrano quello che e cambiato`() {
        val liked = lib.toggleLike(track(9))
        assertNotEquals(shown(lib, "me/liked"), shown(liked, "me/liked"))
        assertNotEquals(shown(lib, "me"), shown(liked, "me"), "il conteggio dei preferiti nelle sezioni")
        assertEquals(shown(lib, "me/playlist/p1"), shown(liked, "me/playlist/p1"))
        val added = lib.addToPlaylist("p1", listOf(track(7)))
        assertNotEquals(shown(lib, "me/playlist/p1"), shown(added, "me/playlist/p1"))
        // Una playlist diversa non tocca questa.
        val other = lib.createPlaylist("p2", "Altra", 0)
        assertEquals(shown(lib, "me/playlist/p1"), shown(other, "me/playlist/p1"))
        assertNull(shown(lib, "local/albums"))
        assertTrue(PersonalSource.PAGES.all { shown(lib, it) != null })
    }

    @Test
    fun `il backup automatico non si riscrive a ogni brano ascoltato`() {
        val every = LibraryBackup.PLAYS_EVERY_MS
        // Preferiti o playlist cambiati: subito.
        assertEquals(0, LibraryBackup.autoWait(lib, lib.toggleLike(track(9)), false, lastAt = 0, nowMs = 1_000))
        assertEquals(0, LibraryBackup.autoWait(lib, lib.addToPlaylist("p1", listOf(track(7))), false, 0, 1_000))
        // Un'impostazione cambiata, o nessun backup ancora in questa sessione: subito.
        assertEquals(0, LibraryBackup.autoWait(lib, lib, settingsChanged = true, lastAt = 0, nowMs = 1_000))
        assertEquals(0, LibraryBackup.autoWait(null, afterPlay, false, null, 1_000))
        // Solo un ascolto: si aspetta fino a mezz'ora dall'ultimo backup.
        assertEquals(every - 60_000, LibraryBackup.autoWait(lib, afterPlay, false, lastAt = 0, nowMs = 60_000))
        assertEquals(0, LibraryBackup.autoWait(lib, afterPlay, false, lastAt = 0, nowMs = every + 5_000))
        // Niente di nuovo: niente da scrivere.
        assertNull(LibraryBackup.autoWait(lib, lib, false, 0, 1_000))
    }

    @Test
    fun `il controllo della dissolvenza gira solo mentre suona con la dissolvenza accesa`() {
        assertTrue(Crossfade.watching(seconds = 6, playWhenReady = true))
        assertFalse(Crossfade.watching(seconds = 6, playWhenReady = false))
        assertFalse(Crossfade.watching(seconds = 0, playWhenReady = true))
    }
}

class PreloadTest {
    @Test
    fun `la lettura parte subito in sottofondo e non blocca chi crea`() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val file = java.util.concurrent.CountDownLatch(1)
        var reads = 0
        val begin = System.nanoTime()
        val preload = io.github.baldborne94.baldmusic.core.library.Preload(pool) { file.await(); reads++; "la tua musica" }
        // Il file è lento: chi crea non aspetta.
        assertTrue(System.nanoTime() - begin < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500))
        assertFalse(preload.ready)
        file.countDown()
        // Chi chiede aspetta solo quello che manca, e la lettura si fa una volta.
        assertEquals("la tua musica", preload.value)
        assertEquals("la tua musica", preload.value)
        assertTrue(preload.ready)
        assertEquals(1, reads)
        pool.shutdown()
    }
}
