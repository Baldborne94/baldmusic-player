package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.SessionControls
import io.github.baldborne94.baldmusic.core.library.SessionControls.Button
import io.github.baldborne94.baldmusic.core.podcast.EpisodeActions
import io.github.baldborne94.baldmusic.core.podcast.EpisodeActions.Action
import io.github.baldborne94.baldmusic.core.podcast.PodcastSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionControlsTest {
    private val episode = MediaId.track(PodcastSource.KEY, "show~ep", null).toString()
    private val song = "local/track/42|me/playlist/p"

    @Test
    fun `musica cuore casuale ripeti timer, puntata indietro avanti timer, niente in coda nessun tasto`() {
        assertEquals(listOf(Button.HEART, Button.SHUFFLE, Button.REPEAT, Button.SLEEP), SessionControls.layout(song))
        assertEquals(listOf(Button.BACK, Button.FORWARD, Button.SLEEP), SessionControls.layout(episode))
        assertEquals(emptyList(), SessionControls.layout(null))
        // Il podcast intero non e una puntata.
        assertEquals(Button.HEART, SessionControls.layout("podcast/show/x").first())
    }

    @Test
    fun `indietro e avanti restano dentro la puntata`() {
        assertEquals(0, SessionControls.skipTarget(10_000, 600_000, -SessionControls.BACK_MS))
        assertEquals(45_000, SessionControls.skipTarget(60_000, 600_000, -SessionControls.BACK_MS))
        assertEquals(90_000, SessionControls.skipTarget(60_000, 600_000, SessionControls.FORWARD_MS))
        // Mai oltre la fine: l'ultimo secondo resta.
        assertEquals(599_000, SessionControls.skipTarget(590_000, 600_000, SessionControls.FORWARD_MS))
        // Durata sconosciuta: solo mai prima dell'inizio.
        assertEquals(630_000, SessionControls.skipTarget(600_000, null, SessionControls.FORWARD_MS))
        assertEquals(0, SessionControls.skipTarget(500, 800, SessionControls.FORWARD_MS))
    }

    @Test
    fun `ripeti spento, tutta la coda, il brano, spento`() {
        val seen = generateSequence(SessionControls.REPEAT_OFF) { SessionControls.nextRepeat(it) }.take(4).toList()
        assertEquals(listOf(SessionControls.REPEAT_OFF, SessionControls.REPEAT_ALL, SessionControls.REPEAT_ONE, SessionControls.REPEAT_OFF), seen)
        assertEquals(listOf("Ripeti", "Ripeti tutto", "Ripeti il brano"), seen.take(3).map(SessionControls::repeatLabel))
    }

    @Test
    fun `menu della puntata scarica, togli, segna e informazioni`() {
        fun of(downloaded: Boolean, manual: Boolean, finished: Boolean) = EpisodeActions.of(episode, downloaded, manual, finished)
        assertEquals(listOf(Action.DOWNLOAD, Action.MARK_FINISHED, Action.INFO), of(false, false, false))
        assertEquals(listOf(Action.REMOVE_DOWNLOAD, Action.MARK_FINISHED, Action.INFO), of(true, true, false))
        // Scaricata da sola: la toglie il giro dei download, non la mano.
        assertEquals(listOf(Action.MARK_FINISHED, Action.INFO), of(true, false, false))
        // Gia ascoltata: non si scarica, si puo rimettere da ascoltare.
        assertEquals(listOf(Action.MARK_UNFINISHED, Action.INFO), of(false, false, true))
        assertEquals(emptyList(), EpisodeActions.of(song, false, false, false))
    }
}
