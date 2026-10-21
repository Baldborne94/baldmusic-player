package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.artwork.ArtworkCheck
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkCheckTest {
    private val size = 64
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun image(pixel: (x: Int, y: Int) -> Int) = IntArray(size * size) { pixel(it % size, it / size) }

    @Test
    fun `righe di colori accesi che cambiano a ogni riga sono una copertina rovinata`() {
        // Come quella di «Classical Music»: bande verdi, rosse, viola, ognuna diversa da quella sotto.
        val r = Random(3)
        val rows = List(size) { listOf(rgb(20, 230, 40), rgb(230, 30, 40), rgb(200, 30, 220), rgb(240, 230, 20)).random(r) }
        val broken = image { x, y -> if (r.nextInt(40) == 0) rows[(y + 1) % size] else rows[y] }
        assertTrue(ArtworkCheck.looksBroken(broken, size, size))
    }

    @Test
    fun `una copertina vera non e rovinata, anche piena di dettagli o a righe`() {
        val r = Random(5)
        // Una foto: sfumature e un po' di grana, simili in orizzontale e in verticale.
        val photo = image { x, y -> rgb(40 + x * 2 + r.nextInt(20), 90 + y + r.nextInt(20), 160 - x + r.nextInt(20)) }
        // Righe orizzontali in bianco e nero (copertine "a linee"): niente colori accesi.
        val lines = image { _, y -> if (y % 4 == 0) rgb(240, 240, 240) else rgb(10, 10, 10) }
        // Strisce verticali colorate: cambiano in orizzontale, non riga per riga.
        val vertical = image { x, _ -> if ((x / 4) % 2 == 0) rgb(230, 20, 30) else rgb(20, 200, 40) }
        // Onde colorate morbide.
        val waves = image { x, y -> rgb((128 + 120 * sin(y / 5.0)).toInt(), (128 + 120 * sin(x / 7.0)).toInt(), 60) }
        val plain = image { _, _ -> rgb(200, 30, 30) }
        listOf(photo, lines, vertical, waves, plain).forEachIndexed { i, img -> assertFalse(ArtworkCheck.looksBroken(img, size, size), "immagine $i") }
    }

    @Test
    fun `immagini troppo piccole o vuote non si giudicano`() {
        assertFalse(ArtworkCheck.looksBroken(IntArray(0), 0, 0))
        assertFalse(ArtworkCheck.looksBroken(IntArray(16), 4, 4))
    }
}
