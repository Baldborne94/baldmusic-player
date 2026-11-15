package io.github.baldborne94.baldmusic.core.diagnostics

/**
 * Le righe del registro del lettore ([ProblemReport.addTrace]): chi ha chiesto cosa (l'auto,
 * le cuffie, l'app) e cosa è successo dopo, in parole brevi. Serve quando un tasto non fa quello
 * che dovrebbe e non si può provare in macchina: il resoconto dice com'è andata davvero.
 * I numeri sono quelli di Media3 (`Player.DISCONTINUITY_REASON_*`, `MEDIA_ITEM_TRANSITION_REASON_*`,
 * `Player.REPEAT_MODE_*`, `KeyEvent.KEYCODE_*`), qui senza Android.
 */
object PlayerTrace {

    private val known = mapOf(
        "com.google.android.projection.gearhead" to "Android Auto",
        "com.android.systemui" to "notifica",
        "com.google.android.googlequicksearchbox" to "Assistente Google",
        "com.google.android.apps.bard" to "Gemini",
        "com.google.android.bluetooth" to "Bluetooth",
        "com.android.bluetooth" to "Bluetooth",
        "com.google.android.carassistant" to "Assistente dell'auto",
        "android.media.session.MediaController" to "sistema",
    )

    /** Chi ha mandato un comando, dal nome del pacchetto ([own] = questa app). */
    fun who(packageName: String?, own: String): String = when {
        packageName.isNullOrBlank() -> "?"
        packageName == own -> "app"
        else -> known[packageName] ?: packageName
    }

    /** Minuti e secondi («1:05»). */
    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Dove si è nella coda e con quali modi: «brano 3 di 40 a 1:05 su 3:30, casuale sì, ripeti no». */
    fun state(index: Int, count: Int, positionMs: Long, durationMs: Long?, shuffle: Boolean, repeat: Int): String =
        "brano ${index + 1} di $count a ${time(positionMs)}" +
            (durationMs?.takeIf { it > 0 }?.let { " su ${time(it)}" } ?: "") +
            ", casuale ${if (shuffle) "sì" else "no"}, ripeti ${repeat(repeat)}"

    fun repeat(mode: Int): String = when (mode) {
        1 -> "il brano"
        2 -> "tutto"
        else -> "no"
    }

    /** Perché il punto di ascolto è cambiato (`Player.DISCONTINUITY_REASON_*`). */
    fun discontinuity(reason: Int): String = when (reason) {
        0 -> "finito da solo"
        1 -> "comando"
        2 -> "aggiustamento"
        3 -> "saltato dal lettore"
        4 -> "brano tolto"
        6 -> "silenzio saltato"
        else -> "interno"
    }

    /** Perché è cambiato il brano (`Player.MEDIA_ITEM_TRANSITION_REASON_*`). */
    fun transition(reason: Int): String = when (reason) {
        0 -> "ripetuto"
        1 -> "finito il precedente"
        2 -> "comando"
        3 -> "coda nuova"
        else -> "?"
    }

    /** Un tasto multimediale (`KeyEvent.KEYCODE_*`). */
    fun key(code: Int): String = when (code) {
        87 -> "avanti"
        88 -> "indietro"
        85 -> "play/pausa"
        79 -> "tasto delle cuffie"
        126 -> "play"
        127 -> "pausa"
        86 -> "stop"
        90 -> "avanti veloce"
        89 -> "indietro veloce"
        272 -> "salta avanti"
        273 -> "salta indietro"
        else -> "tasto $code"
    }
}
