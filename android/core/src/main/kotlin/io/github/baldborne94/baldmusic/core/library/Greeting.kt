package io.github.baldborne94.baldmusic.core.library

/**
 * Il saluto in cima alla Home: l'ora del giorno e il nome dell'utente collegato a Navidrome
 * («Buonasera, Alessandro»). Senza server collegato, l'utente dell'ultimo collegamento
 * (`Settings.savedServer`); senza nemmeno quello, solo l'ora. Stessa regola nel web
 * (`web/src/lib/greeting.ts`).
 */
object Greeting {
    fun of(hour: Int, user: String?): String {
        val time = when (hour) {
            in 5..12 -> "Buongiorno"
            in 13..17 -> "Buon pomeriggio"
            else -> "Buonasera"
        }
        val name = displayName(user)
        return if (name.isEmpty()) time else "$time, $name"
    }

    /**
     * Il nome da mostrare: senza la parte dopo la @ di un indirizzo, punti, trattini e trattini
     * bassi come spazi, iniziale maiuscola («mario.rossi» → «Mario Rossi»); le maiuscole che
     * ci sono già restano.
     */
    fun displayName(user: String?): String =
        (user ?: "").trim().substringBefore('@')
            .split(Regex("[._\\-\\s]+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(java.util.Locale.ITALIAN) } }
}
