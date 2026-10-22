package io.github.baldborne94.baldmusic.core.podcast

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Come si mostrano le puntate di un podcast: dalla più recente, con un'intestazione per mese
 * (le puntate esclusive e quelle delle stagioni escono mescolate: per stagione l'ordine
 * salterebbe a ogni riga), e senza l'etichetta che il creatore ripete in fondo a ogni titolo
 * («(Patreon Exclusive)», «(Ad-Free)»): va nella riga sotto, così il titolo si legge intero.
 */
object EpisodeLabels {
    private val trailing = Regex("""\s*[(\[]([^()\[\]]{2,40})[)\]]\s*$""")
    private val MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.ITALIAN)

    /** Un'etichetta conta se torna almeno così tante volte (e in almeno una puntata su dieci). */
    private const val MIN_REPEATS = 3

    /** Le etichette ripetute in fondo ai titoli delle puntate. */
    fun tags(episodes: List<Episode>): Set<String> {
        val counts = episodes.mapNotNull { e -> trailing.find(e.title)?.groupValues?.get(1)?.trim() }.groupingBy { it }.eachCount()
        return counts.filter { (_, n) -> n >= MIN_REPEATS && n * 10 >= episodes.size }.keys
    }

    /** Il titolo senza l'etichetta ripetuta, e l'etichetta (null se il titolo non ne ha una di [tags]). */
    fun split(title: String, tags: Set<String>): Pair<String, String?> {
        val m = trailing.find(title) ?: return title to null
        val tag = m.groupValues[1].trim()
        val rest = title.substring(0, m.range.first).trimEnd(' ', '-', '–', '·')
        return if (tag in tags && rest.isNotBlank()) rest to tag else title to null
    }

    /** L'intestazione del mese di uscita: «Ottobre 2026»; «Senza data» se il feed non la dice. */
    fun month(publishedAt: Long?, zone: ZoneId): String =
        publishedAt?.let { MONTH.withZone(zone).format(Instant.ofEpochMilli(it)).replaceFirstChar { c -> c.titlecase(Locale.ITALIAN) } } ?: "Senza data"
}
