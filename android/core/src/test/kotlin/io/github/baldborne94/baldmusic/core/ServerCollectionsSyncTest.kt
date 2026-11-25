package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.ServerCollections
import io.github.baldborne94.baldmusic.core.subsonic.ServerCollectionsSync
import io.github.baldborne94.baldmusic.core.subsonic.Song
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Un Navidrome finto con le playlist in memoria: crea, rinomina, aggiunge, riscrive. */
private class PlaylistServer : Dispatcher() {
    class Pl(val id: String, var name: String, var comment: String? = null, val songs: MutableList<String> = mutableListOf())

    val playlists = LinkedHashMap<String, Pl>()
    val starred = LinkedHashSet<String>()
    var next = 1

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val q = { k: String -> url.queryParameter(k) }
        val body: String = when (url.encodedPath.substringAfterLast('/')) {
            "getPlaylists" -> """"playlists":{"playlist":[${playlists.values.joinToString(",") { info(it) }}]}"""
            "getPlaylist" -> playlists[q("id")]?.let { """"playlist":${info(it, entries = true)}""" } ?: return failed()
            "createPlaylist" -> {
                val ids = url.queryParameterValues("songId").filterNotNull()
                val pl = q("playlistId")?.let { playlists.getValue(it).also { p -> p.songs.clear() } }
                    ?: Pl("pl${next++}", q("name")!!).also { playlists[it.id] = it }
                pl.songs += ids
                """"playlist":${info(pl, entries = true)}"""
            }
            "updatePlaylist" -> {
                val pl = playlists.getValue(q("playlistId")!!)
                q("name")?.let { pl.name = it }
                q("comment")?.let { pl.comment = it }
                pl.songs += url.queryParameterValues("songIdToAdd").filterNotNull()
                """"x":1"""
            }
            "getStarred2" -> """"starred2":{"song":[${starred.joinToString(",") { """{"id":"$it"}""" }}]}"""
            "star" -> {
                starred += url.queryParameterValues("id").filterNotNull()
                """"x":1"""
            }
            else -> return failed()
        }
        return MockResponse.Builder().body("""{"subsonic-response":{"status":"ok",$body}}""").build()
    }

    private fun failed() = MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}""").build()

    private fun info(p: Pl, entries: Boolean = false): String = buildJsonObject {
        put("id", p.id)
        put("name", p.name)
        put("owner", "ale")
        put("songCount", p.songs.size)
        p.comment?.let { put("comment", it) }
        if (entries) put("entry", buildJsonArray { p.songs.forEach { add(buildJsonObject { put("id", it); put("title", titles[it] ?: it); put("duration", 200) }) } })
    }.let { Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }

    val titles = HashMap<String, String>()
}

class ServerCollectionsSyncTest {
    private val server = MockWebServer()
    private val nd = PlaylistServer()

    @BeforeTest fun start() {
        server.dispatcher = nd
        server.start()
    }

    @AfterTest fun stop() = server.close()

    private val sync by lazy {
        ServerCollectionsSync(SubsonicClient(Credentials.create(server.url("/").toString(), "ale", "sesame", "c19b2d"), OkHttpClient()), "ale")
    }

    private fun song(id: String, folder: String, title: String = id) = Song(id, title, path = "/music/$folder/$title.mp3", duration = 200)

    @Test
    fun `dal telefono una cartella nuova sul NAS diventa raccolta e i brani nuovi entrano`() = runBlocking {
        // Il sito aveva già fatto «Disney»; sul NAS arrivano «Rock» e un brano nuovo in Disney.
        nd.playlists["p1"] = PlaylistServer.Pl("p1", "Disney", ServerCollections.comment("Disney"), mutableListOf("d1"))
        val first = sync.run(listOf(song("d1", "Disney"), song("r1", "Rock")), saved = null)
        assertEquals(1, first.created)
        val rock = nd.playlists.values.single { it.name == "Rock" }
        assertEquals(ServerCollections.comment("Rock"), rock.comment)
        assertEquals(listOf("r1"), rock.songs)

        val second = sync.run(listOf(song("d1", "Disney"), song("d2", "Disney"), song("r1", "Rock")), first.state)
        assertEquals(1, second.added)
        assertEquals(listOf("d1", "d2"), nd.playlists.getValue("p1").songs)
        // Niente da rifare: nessuna raccolta doppia.
        val third = sync.run(listOf(song("d1", "Disney"), song("d2", "Disney"), song("r1", "Rock")), second.state)
        assertFalse(third.changed)
        assertEquals(2, nd.playlists.size)
    }

    @Test
    fun `un brano tolto a mano dalla raccolta non torna`() = runBlocking {
        val songs = listOf(song("d1", "Disney"), song("d2", "Disney"), song("r1", "Rock"))
        val first = sync.run(songs, saved = null)
        val disney = nd.playlists.values.single { it.name == "Disney" }
        disney.songs.remove("d2")
        sync.run(songs, first.state)
        assertEquals(listOf("d1"), disney.songs)
    }

    @Test
    fun `una raccolta eliminata dal sito non si ricrea e finisce nell'elenco delle eliminate`() = runBlocking {
        val songs = listOf(song("d1", "Disney"), song("r1", "Rock"))
        val first = sync.run(songs, saved = null)
        nd.playlists.values.removeIf { it.name == "Rock" }
        val second = sync.run(songs, first.state)
        assertEquals(0, second.created)
        val store = nd.playlists.values.single { it.name == ServerCollections.DELETED_STORE_NAME }
        assertEquals(listOf("Rock"), ServerCollections.deletedInStore(store.comment))
        // Un telefono nuovo (senza stato) non la ricrea: legge l'elenco su Navidrome.
        assertEquals(0, sync.run(songs, saved = null).created)
        assertFalse(nd.playlists.values.any { it.name == "Rock" })
    }

    @Test
    fun `cartella rinominata sul NAS - la raccolta la segue con nome, brani e mi piace`() = runBlocking {
        val rock = song("r1", "Rock")
        val first = sync.run(listOf(song("a", "Disney", "Uno"), song("b", "Disney", "Due"), rock), saved = null)
        val disney = nd.playlists.values.single { it.name == "Disney" }
        nd.starred += "a"
        nd.titles += mapOf("a" to "Uno", "b" to "Due")
        // Rinominata: Navidrome dà ID nuovi agli stessi file.
        val result = sync.run(listOf(song("a2", "Disney Italia", "Uno"), song("b2", "Disney Italia", "Due"), rock), first.state)
        assertEquals(1, result.followed)
        assertEquals(0, result.created)
        assertEquals("Disney Italia", disney.name)
        assertEquals(ServerCollections.comment("Disney Italia"), disney.comment)
        // Stesso ordine della raccolta (per percorso: «Due» prima di «Uno»), con gli ID nuovi.
        assertEquals(listOf("b2", "a2"), disney.songs)
        assertTrue("a2" in nd.starred)
        assertEquals(setOf("Disney Italia", "Rock"), result.state!!.known.keys)
    }

    @Test
    fun `con i percorsi finti non tocca niente e lo dice`() = runBlocking {
        val result = sync.run(listOf(Song("a", "Uno", path = "Avril/Let Go/Uno.mp3")), saved = null)
        assertTrue(result.noRealPath)
        assertTrue(nd.playlists.isEmpty())
    }

    @Test
    fun `una raccolta grande si crea a blocchi`() = runBlocking {
        val songs = (1..450).map { song("s%03d".format(it), "Grande", "%03d".format(it)) }
        sync.run(songs + song("r1", "Rock"), saved = null)
        assertEquals(songs.map { it.id }, nd.playlists.values.single { it.name == "Grande" }.songs)
    }
}
