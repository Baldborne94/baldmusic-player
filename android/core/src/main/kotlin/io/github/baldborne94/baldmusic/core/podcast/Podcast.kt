package io.github.baldborne94.baldmusic.core.podcast

import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** Una puntata di un podcast, come la descrive il feed RSS. */
@Serializable
data class Episode(
    /** Stabile tra un aggiornamento e l'altro del feed (dal guid, o dall'indirizzo dell'audio). */
    val key: String,
    val title: String,
    /** L'audio da riprodurre. Nei feed privati (Patreon) è un indirizzo personale: mai nei log. */
    val audioUrl: String,
    val publishedAt: Long? = null,
    val durationMs: Long? = null,
    val image: String? = null,
    /** Il testo che il creatore scrive per la puntata, già ripulito dall'HTML ([PodcastFeed.plainText]). */
    val description: String? = null,
)

/** Un podcast letto dal suo feed: le puntate dalla più recente. */
@Serializable
data class Podcast(
    /** Dall'indirizzo del feed ([PodcastFeed.idOf]), senza contenerlo. */
    val id: String,
    val title: String,
    val author: String? = null,
    val image: String? = null,
    val episodes: List<Episode> = emptyList(),
    val fetchedAt: Long = 0,
)

/**
 * I feed RSS dei podcast: quelli pubblici e quelli privati che Patreon dà a ogni abbonato
 * («Ascolta in altre app» → link RSS). Solo lettura del feed: niente account, niente API.
 */
object PodcastFeed {
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"

    /** La descrizione di una puntata più lunga di così si taglia (il file del podcast resta piccolo). */
    const val MAX_DESCRIPTION = 4_000

    /** Un ID corto e stabile per un feed: l'indirizzo (con la chiave privata) non finisce negli ID. */
    fun idOf(feedUrl: String): String = hash(feedUrl.trim()).take(12)

    /**
     * L'indirizzo di un feed scritto o incollato dall'utente, ripulito; null se non lo è.
     * Accetta anche "itpc://" e "pcast://" (i link «apri nell'app di podcast») e "feed://".
     */
    fun cleanUrl(input: String): String? {
        val text = input.trim().replace(Regex("\\s+"), "")
        if (text.isEmpty()) return null
        val url = Regex("^(?:itpc|pcast|podcast|feed)://", RegexOption.IGNORE_CASE).replace(text, "https://")
        val ok = Regex("^https?://[^/\\s]+\\.[^/\\s]+(/.*)?$", RegexOption.IGNORE_CASE).matches(url)
        return url.takeIf { ok }
    }

    /** Il feed letto; null se il testo non è un feed RSS con almeno un titolo. */
    fun parse(xml: String, showId: String, now: Long): Podcast? {
        val doc = runCatching { builder().parse(InputSource(StringReader(xml.trimStart('﻿', ' ', '\n', '\r', '\t')))) }.getOrNull() ?: return null
        val channel = doc.documentElement?.let { root -> if (root.localName == "channel" || root.tagName == "channel") root else root.child("channel") } ?: return null
        val title = channel.text("title") ?: return null
        val image = channel.childNs(ITUNES, "image")?.getAttribute("href")?.takeIf { it.isNotBlank() }
            ?: channel.child("image")?.text("url")
        val episodes = channel.children("item").mapNotNull { item -> episode(item) }
            .distinctBy { it.key }
            .sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }
        return Podcast(
            id = showId,
            title = title,
            author = channel.textNs(ITUNES, "author") ?: channel.text("managingEditor"),
            image = image,
            episodes = episodes,
            fetchedAt = now,
        )
    }

    private fun episode(item: Element): Episode? {
        val enclosure = item.children("enclosure").firstOrNull { e ->
            val type = e.getAttribute("type").lowercase()
            e.getAttribute("url").isNotBlank() && (type.isEmpty() || type.startsWith("audio/") || type == "video/mp4" || type == "application/octet-stream")
        } ?: return null
        val url = enclosure.getAttribute("url").trim()
        val guid = item.text("guid")
        return Episode(
            key = hash(guid ?: url).take(16),
            title = item.text("title") ?: item.textNs(ITUNES, "title") ?: "Puntata senza titolo",
            audioUrl = url,
            publishedAt = item.text("pubDate")?.let(::date),
            durationMs = item.textNs(ITUNES, "duration")?.let(::duration),
            image = item.childNs(ITUNES, "image")?.getAttribute("href")?.takeIf { it.isNotBlank() },
            description = (item.text("description") ?: item.textNs(CONTENT, "encoded") ?: item.textNs(ITUNES, "summary"))
                ?.let(::plainText)?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Il testo di una descrizione scritta in HTML (come la mandano quasi tutti i feed): a capo dove
     * c'erano paragrafi e righe, «• » per gli elenchi, i link con il loro indirizzo, niente tag.
     */
    fun plainText(html: String): String {
        var t = html.replace("\r\n", "\n")
        // Un link il cui testo non dice l'indirizzo: "testo (indirizzo)".
        t = Regex("<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(t) { m ->
            val url = m.groupValues[1].trim()
            val text = m.groupValues[2]
            if (url.isEmpty() || text.contains(url) || !url.startsWith("http")) text else "$text ($url)"
        }
        t = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(t, "\n")
        t = Regex("<li[^>]*>", RegexOption.IGNORE_CASE).replace(t, "\n• ")
        t = Regex("</(p|div|ul|ol|h[1-6]|blockquote)>|<(p|div|ul|ol|h[1-6]|blockquote)(\\s[^>]*)?>", RegexOption.IGNORE_CASE).replace(t, "\n\n")
        t = Regex("<[^>]+>").replace(t, "")
        t = entities(t)
        val lines = t.split('\n').map { it.replace(Regex("[ \\t\u00A0]+"), " ").trim() }
        val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length <= MAX_DESCRIPTION) text else text.take(MAX_DESCRIPTION).trimEnd() + "…"
    }

    private fun entities(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { m ->
        val code = m.groupValues[1]
        when {
            code.startsWith("#x") || code.startsWith("#X") -> code.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[code]
        } ?: m.value
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "egrave" to "è", "eacute" to "é", "agrave" to "à", "ograve" to "ò", "ugrave" to "ù", "igrave" to "ì",
        "Egrave" to "È", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "ndash" to "–", "mdash" to "—", "laquo" to "«", "raquo" to "»",
    )

    /**
     * Il podcast che segui già e che [fresh] sostituisce: stesso titolo, o almeno metà delle
     * puntate in comune. Serve quando Patreon dà un link nuovo (abbonamento rinnovato, link
     * rigenerato): incollandolo si tiene dove eri arrivato in ogni puntata.
     */
    fun replaces(followed: List<Podcast>, fresh: Podcast): Podcast? {
        val title = fresh.title.trim().lowercase()
        val keys = fresh.episodes.mapTo(HashSet()) { it.key }
        return followed.firstOrNull { it.title.trim().lowercase() == title }
            ?: followed.firstOrNull { old -> keys.isNotEmpty() && old.episodes.count { it.key in keys } * 2 >= keys.size }
    }

    /** Puntate uscite da poco: più vecchie di così non sono «nuove» (un creatore che ripubblica l'archivio). */
    const val NEW_FOR_MS = 14L * 24 * 3600 * 1000

    /**
     * Le puntate da annunciare dopo aver riletto il feed: quelle che prima non c'erano e sono
     * uscite da poco. Il primo giro dopo aver seguito un podcast ([before] null) non annuncia niente.
     */
    fun newEpisodes(before: Podcast?, after: Podcast, now: Long): List<Episode> {
        before ?: return emptyList()
        val known = before.episodes.mapTo(HashSet()) { it.key }
        return after.episodes.filter { it.key !in known && (it.publishedAt ?: 0) >= now - NEW_FOR_MS }
    }

    /** Titolo e testo della notifica delle puntate nuove di [show]. */
    fun notice(show: Podcast, episodes: List<Episode>): Pair<String, String> = when (episodes.size) {
        1 -> "Nuova puntata di ${show.title}" to episodes[0].title
        else -> "${episodes.size} puntate nuove di ${show.title}" to episodes.joinToString(" · ") { it.title }
    }

    /** "01:02:03", "62:03", "3723" (secondi): la durata in millisecondi. */
    fun duration(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size > 3 || parts.any { it.isBlank() }) return null
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        val seconds = numbers.fold(0.0) { acc, n -> acc * 60 + n }
        return (seconds * 1000).toLong().takeIf { it > 0 }
    }

    /** "Tue, 21 Oct 2026 07:30:00 GMT" (RFC 822, come vogliono i feed): l'istante. */
    fun date(text: String): Long? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            // Alcuni feed sbagliano il giorno della settimana o scrivono il fuso a modo loro: si riprova senza.
            ?: LENIENT.firstNotNullOfOrNull { f -> runCatching { ZonedDateTime.parse(t.substringAfter(", "), f).toInstant().toEpochMilli() }.getOrNull() }
    }

    private val LENIENT = listOf("d MMM yyyy HH:mm[:ss] Z", "d MMM yyyy HH:mm[:ss] z").map { DateTimeFormatter.ofPattern(it, java.util.Locale.ENGLISH) }

    /** Un lettore XML che non va a prendere niente fuori dal feed (niente entità esterne). */
    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, on) -> runCatching { setFeature(feature, on) } }
    }.newDocumentBuilder()

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun Element.children(name: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == null && (n.localName ?: n.tagName) == name) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.childNs(ns: String, name: String): Element? {
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == ns && n.localName == name) return n
            n = n.nextSibling
        }
        return null
    }

    private fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    private fun Element.textNs(ns: String, name: String): String? = childNs(ns, name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
