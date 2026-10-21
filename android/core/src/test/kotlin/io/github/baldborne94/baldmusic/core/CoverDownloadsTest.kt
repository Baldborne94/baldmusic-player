package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.artwork.CoverDownloads
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverDownloadsTest {
    @Test
    fun `senza la copertina vera si mostra subito quella del file, anche se la rete non risponde`() {
        val pool = Executors.newSingleThreadExecutor()
        val downloads = CoverDownloads { pool.execute(it) }
        val network = CountDownLatch(1)
        val started = AtomicInteger()
        val download = { started.incrementAndGet(); network.await(10, TimeUnit.SECONDS); Unit }

        // Il download resta appeso (rete che non risponde): la risposta arriva lo stesso, subito.
        val begin = System.nanoTime()
        assertEquals("del file", downloads.shown("abc", real = null, local = { "del file" }, download = download))
        assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(1))
        // Le altre righe con la stessa copertina non fanno partire un secondo download.
        assertEquals("del file", downloads.shown("abc", real = null, local = { "del file" }, download = download))
        assertTrue(downloads.downloading("abc"))

        network.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, started.get())
        assertFalse(downloads.downloading("abc"))
    }

    @Test
    fun `scaricata la vera, vale quella e non si riscarica`() {
        val started = AtomicInteger()
        val downloads = CoverDownloads { it.run() }
        assertEquals("vera", downloads.shown("abc", real = "vera", local = { "del file" }, download = { started.incrementAndGet() }))
        assertEquals(0, started.get())
        // Senza vera e senza copertina nel file: niente, ma il download parte.
        assertNull(downloads.shown<String>("xyz", real = null, local = { null }, download = { started.incrementAndGet() }))
        assertEquals(1, started.get())
    }

    @Test
    fun `se il lavoro in sottofondo non parte si riprova la volta dopo`() {
        var refuse = true
        val started = AtomicInteger()
        val downloads = CoverDownloads { job -> if (refuse) error("pieno") else job.run() }
        assertEquals("del file", downloads.shown("abc", null, { "del file" }) { started.incrementAndGet() })
        assertFalse(downloads.downloading("abc"))
        refuse = false
        downloads.shown("abc", null, { "del file" }) { started.incrementAndGet() }
        assertEquals(1, started.get())
    }
}
