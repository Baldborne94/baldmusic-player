package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Loudness
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import io.github.baldborne94.baldmusic.core.library.ReplayGainMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoudnessTest {

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test
    fun `livello finale come Spotify, -14 LUFS, non -18 del vecchio ReplayGain`() {
        // Un brano di oggi a -8 LUFS: ReplayGain (riferimento -18) dice -10 dB. In macchina col
        // rumore della strada sembrava basso rispetto alle altre app: ora si abbassa di 6 dB (fino a -14).
        near(0.501f, Loudness.volume(ReplayGain(trackGain = -10f), ReplayGainMode.TRACK))
        // Un brano gia a -14 LUFS (guadagno -4) resta com'e.
        near(1f, Loudness.volume(ReplayGain(trackGain = -4f), ReplayGainMode.TRACK))
        assertEquals(4f, Loudness.PREAMP_DB)
    }

    @Test
    fun `un brano forte si abbassa del suo guadagno`() {
        // -6 dB dai tag, +4 per arrivare a -14 LUFS: -2 dB.
        near(0.794f, Loudness.volume(ReplayGain(trackGain = -6f, albumGain = -6f), ReplayGainMode.ALBUM))
    }

    @Test
    fun `un brano piano resta com'e, non si alza`() {
        near(1f, Loudness.volume(ReplayGain(trackGain = 4f), ReplayGainMode.TRACK))
    }

    @Test
    fun `mai oltre il picco`() {
        near(1f / 1.25f, Loudness.volume(ReplayGain(trackGain = 0f, trackPeak = 1.25f), ReplayGainMode.TRACK))
    }

    @Test
    fun `album o brano, e se manca uno si usa l'altro`() {
        val rg = ReplayGain(trackGain = -10f, albumGain = -6f)
        near(0.794f, Loudness.volume(rg, ReplayGainMode.ALBUM))
        near(0.501f, Loudness.volume(rg, ReplayGainMode.TRACK))
        near(0.501f, Loudness.volume(ReplayGain(trackGain = -10f), ReplayGainMode.ALBUM))
    }

    @Test
    fun `senza tag o spento suona com'e`() {
        near(1f, Loudness.volume(null, ReplayGainMode.ALBUM))
        near(1f, Loudness.volume(ReplayGain(), ReplayGainMode.ALBUM))
        near(1f, Loudness.volume(ReplayGain(trackGain = -8f), ReplayGainMode.OFF))
    }

    @Test
    fun `album solo ascoltando un disco, in raccolte e casuale ogni brano al suo livello`() {
        // Due brani di una raccolta che Android mette nello stesso «album» (la cartella): guadagno
        // dell'album uguale, guadagni dei brani diversi.
        val forte = ReplayGain(trackGain = -9f, albumGain = -5f)
        val piano = ReplayGain(trackGain = -1f, albumGain = -5f)
        // Dalla raccolta: ognuno al suo livello (prima tutti e due con l'album, uno più alto dell'altro).
        assertEquals(Loudness.volume(forte, ReplayGainMode.TRACK), Loudness.volume(forte, ReplayGainMode.ALBUM, fromAlbum = false))
        assertEquals(Loudness.volume(piano, ReplayGainMode.TRACK), Loudness.volume(piano, ReplayGainMode.ALBUM, fromAlbum = false))
        // Dal disco: il guadagno dell'album.
        assertEquals(Loudness.volume(forte, ReplayGainMode.ALBUM, fromAlbum = true), Loudness.volume(piano, ReplayGainMode.ALBUM, fromAlbum = true))
        // Solo il guadagno dell'album: si usa quello.
        assertEquals(Loudness.volume(ReplayGain(albumGain = -6f), ReplayGainMode.ALBUM, fromAlbum = true), Loudness.volume(ReplayGain(albumGain = -6f), ReplayGainMode.ALBUM, fromAlbum = false))
        assertTrue(Loudness.fromAlbum("local/track/42|local/album/rg:abc"))
        assertTrue(Loudness.fromAlbum("remote/track/7|remote/album/a1"))
        assertFalse(Loudness.fromAlbum("local/track/42|me/playlist/p"))
        assertFalse(Loudness.fromAlbum("local/track/42"))
        assertFalse(Loudness.fromAlbum(null))
    }
}
