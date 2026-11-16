package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.ScanStatus
import io.github.baldborne94.baldmusic.core.subsonic.ServerScan
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Un Navidrome finto: dopo l'avvio risponde «sto leggendo» per [rounds] volte, poi «finito». */
private class FakeScanner(private val rounds: Int, private val startError: Exception? = null) : ServerScan.Scanner {
    var full: Boolean? = null
    var asked = 0
    override suspend fun startScan(full: Boolean): ScanStatus {
        startError?.let { throw it }
        this.full = full
        return ScanStatus(scanning = true, count = 0)
    }
    override suspend fun scanStatus(): ScanStatus {
        asked++
        return ScanStatus(scanning = asked < rounds, count = asked * 100)
    }
}

class ServerScanTest {
    @Test
    fun `aspetta la fine della scansione e dice i brani letti`() = runBlocking {
        val scanner = FakeScanner(rounds = 3)
        val seen = mutableListOf<Int>()
        val waits = mutableListOf<Long>()
        val count = ServerScan.scanAndWait(scanner, full = true, onProgress = { seen += it }, sleep = { waits += it })
        assertEquals(300, count)
        assertEquals(true, scanner.full)
        assertEquals(listOf(0, 100, 200, 300), seen)
        assertTrue(waits.all { it == ServerScan.POLL_MS })
    }

    @Test
    fun `guarda almeno una volta anche se Navidrome non ha ancora cominciato`() = runBlocking {
        val scanner = object : ServerScan.Scanner {
            var asked = 0
            override suspend fun startScan(full: Boolean) = ScanStatus(scanning = false, count = 0)
            override suspend fun scanStatus(): ScanStatus { asked++; return ScanStatus(false, 42) }
        }
        assertEquals(42, ServerScan.scanAndWait(scanner, full = false, sleep = {}))
        assertEquals(1, scanner.asked)
    }

    @Test
    fun `utente non amministratore spiegato`() = runBlocking {
        val scanner = FakeScanner(1, startError = SubsonicException("User not authorized", ServerScan.NOT_AUTHORIZED))
        val e = assertFailsWith<ServerScan.Failed> { ServerScan.scanAndWait(scanner, full = false, sleep = {}) }
        assertTrue(e.message!!.contains("amministratore"))
    }

    @Test
    fun `smette di aspettare dopo mezz ora`() = runBlocking {
        var clock = 0L
        val scanner = FakeScanner(rounds = Int.MAX_VALUE)
        val e = assertFailsWith<ServerScan.Failed> {
            ServerScan.scanAndWait(scanner, full = false, sleep = { clock += 60_000 }, now = { clock })
        }
        assertEquals(ServerScan.TOO_LONG, e.message)
    }

    @Test
    fun `stato della scansione letto dalla risposta di Navidrome`() {
        val client = SubsonicClient(Credentials.create("http://nd.test", "b", "x", "s"), OkHttpClient())
        val env = client.parse("""{"subsonic-response":{"status":"ok","version":"1.16.1","scanStatus":{"scanning":true,"count":1234,"folderCount":10}}}""")
        assertEquals(ScanStatus(scanning = true, count = 1234), env.scanStatus)
    }
}
