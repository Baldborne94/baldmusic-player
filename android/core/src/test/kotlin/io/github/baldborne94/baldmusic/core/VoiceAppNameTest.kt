package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.library.VoiceQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceAppNameTest {
    @Test
    fun `il nome dell'app che l'Assistente lascia nella richiesta si toglie`() {
        // Dal resoconto del 15/11 in macchina: con quelle parole in piu' la ricerca non trovava niente.
        assertEquals("Linkin Park", VoiceQuery.searchOf("", "Linkin Park su bald music"))
        assertEquals("Metal mix", VoiceQuery.searchOf("", "Metal mix su bald music"))
        assertEquals("Limp Bizkit", VoiceQuery.searchOf("", "Limp Bizkit su Bud music"))
        assertEquals("Linkin Park", VoiceQuery.withoutAppName("Linkin Park su BaldMusic"))
        assertEquals("Vasco Rossi", VoiceQuery.withoutAppName("metti Vasco Rossi su Bald Music"))
        assertEquals("la raccolta Disney Italia", VoiceQuery.withoutAppName("la raccolta Disney Italia on BaldMusic"))
        // Il resto non si tocca.
        assertEquals("Hotel California", VoiceQuery.withoutAppName("Hotel California"))
        assertEquals("Music of the Night", VoiceQuery.withoutAppName("Music of the Night"))
    }

    @Test
    fun `se la richiesta non trova niente si riprova senza le parole di contorno`() {
        assertEquals(listOf("Metal mix", "Metal"), VoiceQuery.attempts("Metal mix"))
        assertEquals(listOf("musica di Vasco", "Vasco"), VoiceQuery.attempts("musica di Vasco"))
        assertEquals(listOf("Linkin Park"), VoiceQuery.attempts("Linkin Park"))
    }

    @Test
    fun `metti musica senza un nome fa partire un mix`() {
        assertTrue(VoiceQuery.anyMusic(""))
        assertTrue(VoiceQuery.anyMusic("musica"))
        assertTrue(VoiceQuery.anyMusic(VoiceQuery.searchOf("", "un po' di musica su bald music")!!))
        assertFalse(VoiceQuery.anyMusic("Metal mix"))
    }

    @Test
    fun `quando non trova niente l'auto dice cosa cercava`() {
        assertEquals("Non ho trovato «Linkin Park» su BaldMusic", VoiceQuery.notFound("Linkin Park"))
        assertEquals("Niente da suonare", VoiceQuery.notFound(null))
    }
}
