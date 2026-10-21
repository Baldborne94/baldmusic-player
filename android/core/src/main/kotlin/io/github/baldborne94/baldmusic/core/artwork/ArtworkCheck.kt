package io.github.baldborne94.baldmusic.core.artwork

import kotlin.math.abs

/**
 * Riconosce una copertina rovinata: un'immagine nel file guastata (scaricata male, scritta a
 * metà) si decodifica in righe orizzontali di colori accesi che non c'entrano con quella sotto.
 * In una copertina vera, anche piena di dettagli, una riga somiglia a quella accanto in
 * verticale quanto in orizzontale. Si guarda l'immagine rimpicciolita (pixel ARGB).
 */
object ArtworkCheck {
    /** Differenza media minima tra una riga e quella sotto (0–255) perché siano "righe a caso". */
    private const val MIN_ROW_JUMP = 35.0

    /** Quante volte il salto tra righe supera quello tra pixel vicini sulla stessa riga. */
    private const val STRIPES_RATIO = 2.5

    /** Quota di pixel dai colori accesi: le righe rovinate sono verdi, rosse, viola. */
    private const val MIN_VIVID_SHARE = 0.25

    fun looksBroken(argb: IntArray, width: Int, height: Int): Boolean {
        if (width < 8 || height < 8 || argb.size < width * height) return false
        var vertical = 0.0
        var horizontal = 0.0
        var vivid = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                if (x + 1 < width) horizontal += diff(p, argb[y * width + x + 1])
                if (y + 1 < height) vertical += diff(p, argb[(y + 1) * width + x])
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                if (maxOf(r, g, b) - minOf(r, g, b) > 100) vivid++
            }
        }
        val rowJump = vertical / (width * (height - 1))
        val sideJump = horizontal / ((width - 1) * height)
        val vividShare = vivid.toDouble() / (width * height)
        return rowJump > MIN_ROW_JUMP && rowJump > STRIPES_RATIO * (sideJump + 1) && vividShare >= MIN_VIVID_SHARE
    }

    private fun diff(a: Int, b: Int): Double =
        (abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))) / 3.0
}
