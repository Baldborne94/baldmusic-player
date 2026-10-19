package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.Loudness
import io.github.baldborne94.baldmusic.core.library.ReplayGain
import io.github.baldborne94.baldmusic.core.library.ReplayGainMode
import kotlin.test.Test
import kotlin.test.assertEquals

class LoudnessTest {

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test
    fun `un brano forte si abbassa del suo guadagno`() {
        near(0.501f, Loudness.volume(ReplayGain(trackGain = -6f, albumGain = -6f), ReplayGainMode.ALBUM))
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
        val rg = ReplayGain(trackGain = -10f, albumGain = -4f)
        near(0.631f, Loudness.volume(rg, ReplayGainMode.ALBUM))
        near(0.316f, Loudness.volume(rg, ReplayGainMode.TRACK))
        near(0.316f, Loudness.volume(ReplayGain(trackGain = -10f), ReplayGainMode.ALBUM))
    }

    @Test
    fun `senza tag o spento suona com'e`() {
        near(1f, Loudness.volume(null, ReplayGainMode.ALBUM))
        near(1f, Loudness.volume(ReplayGain(), ReplayGainMode.ALBUM))
        near(1f, Loudness.volume(ReplayGain(trackGain = -8f), ReplayGainMode.OFF))
    }
}
