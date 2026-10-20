package io.github.baldborne94.baldmusic.core.acoustid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// AcoustID: dall'impronta acustica di un file (vedi Chromaprint) alle registrazioni di
// MusicBrainz che le somigliano, con i dischi su cui escono. Gratuito per uso non
// commerciale; serve la chiave di un'applicazione (acoustid.org/new-application).
// Una richiesta ogni terzo di secondo al massimo: ci pensa chi chiama.

@Serializable
data class MbArtist(val id: String? = null, val name: String = "", val joinphrase: String? = null)

@Serializable
data class MbTrack(val id: String? = null, val position: Int = 0, val title: String? = null, val artists: List<MbArtist> = emptyList())

@Serializable
data class MbMedium(val position: Int = 0, val format: String? = null, val track_count: Int = 0, val tracks: List<MbTrack> = emptyList())

@Serializable
data class MbDate(val year: Int = 0, val month: Int = 0, val day: Int = 0)

@Serializable
data class MbRelease(
    val id: String,
    val title: String? = null,
    val country: String? = null,
    val date: MbDate? = null,
    val medium_count: Int = 0,
    val track_count: Int = 0,
    val mediums: List<MbMedium> = emptyList(),
)

@Serializable
data class MbReleaseGroup(
    val id: String,
    val title: String = "",
    /** "Album", "Single", "EP", "Other", "Broadcast", oppure assente. */
    val type: String? = null,
    /** "Compilation", "Soundtrack", "Live", "Remix", … */
    val secondarytypes: List<String> = emptyList(),
    val artists: List<MbArtist> = emptyList(),
    val releases: List<MbRelease> = emptyList(),
)

@Serializable
data class MbRecording(
    val id: String,
    val title: String? = null,
    /** Secondi. */
    val duration: Double? = null,
    val artists: List<MbArtist> = emptyList(),
    val releasegroups: List<MbReleaseGroup> = emptyList(),
)

@Serializable
data class AcoustIdResult(val id: String, val score: Double = 0.0, val recordings: List<MbRecording> = emptyList())

@Serializable
private data class AcoustIdError(val code: Int = 0, val message: String = "")

@Serializable
private data class AcoustIdResponse(val status: String = "", val results: List<AcoustIdResult> = emptyList(), val error: AcoustIdError? = null)

/** Cosa ha risposto AcoustID per un file. */
sealed interface Lookup {
    /** Le registrazioni che somigliano, dalla più somigliante (può essere vuota: nessuna). */
    data class Found(val results: List<AcoustIdResult>) : Lookup

    /** Errore del servizio; [badKey] = la chiave non vale, inutile riprovare con gli altri file. */
    data class Failed(val message: String, val badKey: Boolean) : Lookup
}

class AcoustIdClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.acoustid.org/v2/",
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Cerca un'impronta ([fingerprint] compressa, come [Chromaprint.encode]) di un file lungo
     * [durationSec]. Un problema di rete è un'[IOException]: il file resta da riconoscere.
     */
    suspend fun lookup(fingerprint: String, durationSec: Int): Lookup = withContext(Dispatchers.IO) {
        // POST: un'impronta di due minuti è lunga qualche kB, troppo per l'indirizzo.
        val body = FormBody.Builder()
            .add("client", apiKey)
            .add("format", "json")
            .add("meta", "recordings releasegroups releases tracks compress")
            .add("duration", durationSec.toString())
            .add("fingerprint", fingerprint)
            .build()
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/lookup").post(body).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            val parsed = runCatching { json.decodeFromString(AcoustIdResponse.serializer(), text) }.getOrNull()
                ?: if (r.isSuccessful) throw IOException("Risposta non valida da AcoustID") else throw IOException("AcoustID: HTTP ${r.code}")
            when {
                parsed.status == "ok" -> Lookup.Found(parsed.results.sortedByDescending { it.score })
                parsed.error != null -> Lookup.Failed(parsed.error.message.ifBlank { "errore ${parsed.error.code}" }, badKey = parsed.error.code == INVALID_KEY)
                else -> throw IOException("AcoustID: HTTP ${r.code}")
            }
        }
    }

    companion object {
        const val USER_AGENT = "BaldMusic (https://github.com/Baldborne94/baldmusic-player)"

        /** Codice di AcoustID per "invalid API key". */
        const val INVALID_KEY = 4

        /** Dove si crea la chiave dell'applicazione. */
        const val NEW_KEY_URL = "https://acoustid.org/new-application"
    }
}
