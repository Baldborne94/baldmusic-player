package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.update.AppUpdate
import io.github.baldborne94.baldmusic.core.update.UpdateClient
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateTest {
    private val sample = """
        {
          "versionCode": 42, "versionName": "0.1.42", "date": "2026-10-19", "sha": "abc",
          "apk": "baldmusic.apk", "size": 1234,
          "history": [
            {"versionCode": 42, "date": "2026-10-19", "changes": ["Aggiornamenti dall'app"]},
            {"versionCode": 40, "date": "2026-10-18", "changes": ["Tablet affiancato"]},
            {"versionCode": 35, "date": "2026-10-10", "changes": ["Dissolvenza"]}
          ]
        }
    """.trimIndent()

    @Test
    fun `legge version json e ignora i campi che non conosce`() {
        val info = AppUpdate.parse(sample)!!
        assertEquals(42, info.versionCode)
        assertEquals("0.1.42", info.versionName)
        assertEquals(1234L, info.size)
        assertNull(AppUpdate.parse("non json"))
        assertNull(AppUpdate.parse("""{"versionCode": 0}"""))
    }

    @Test
    fun `aggiornare solo se la versione pubblicata e piu nuova`() {
        val info = AppUpdate.parse(sample)!!
        assertTrue(info.isNewerThan(41))
        assertFalse(info.isNewerThan(42))
        assertFalse(info.isNewerThan(50))
    }

    @Test
    fun `chi salta versioni vede tutte le novita dalla sua, la piu recente in cima`() {
        val info = AppUpdate.parse(sample)!!
        assertEquals(listOf(42, 40), info.changesBetween(36).map { it.versionCode })
        assertEquals(listOf(40), info.changesBetween(35, 40).map { it.versionCode })
        assertEquals(emptyList(), info.changesBetween(42))
    }

    @Test
    fun `APK accanto a version json`() {
        val info = AppUpdate.parse(sample)!!
        assertEquals("https://github.com/Baldborne94/baldmusic-player/releases/download/apk-latest/baldmusic.apk", AppUpdate.apkUrl(info))
    }

    @Test
    fun `senza rete o con la release vuota non si sa niente`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(sample).build())
            server.enqueue(MockResponse.Builder().code(404).build())
            server.start()
            val client = UpdateClient(OkHttpClient(), server.url("/version.json").toString())
            assertEquals(42, client.latest()?.versionCode)
            assertNull(client.latest())
        }
    }
}
