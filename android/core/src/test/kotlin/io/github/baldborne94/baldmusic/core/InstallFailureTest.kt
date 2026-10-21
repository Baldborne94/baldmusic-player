package io.github.baldborne94.baldmusic.core

import io.github.baldborne94.baldmusic.core.update.InstallFailure
import io.github.baldborne94.baldmusic.core.update.InstallFailure.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallFailureTest {
    private val conflict = InstallFailure.STATUS_FAILURE_CONFLICT
    private val incompatible = InstallFailure.STATUS_FAILURE_INCOMPATIBLE

    @Test
    fun `un conflitto o un'incompatibilita non vogliono dire per forza firma diversa`() {
        // Prima ogni conflitto e ogni incompatibilità diceva "firma diversa": falso per molti casi.
        assertEquals(Kind.OTHER, InstallFailure.kind(conflict, null))
        assertEquals(Kind.OTHER, InstallFailure.kind(incompatible, "INSTALL_FAILED_CONFLICTING_PROVIDER: …"))
        assertEquals(Kind.BLOCKED, InstallFailure.kind(incompatible, "INSTALL_FAILED_USER_RESTRICTED: Install blocked"))
        assertEquals(Kind.DOWNGRADE, InstallFailure.kind(incompatible, "INSTALL_FAILED_VERSION_DOWNGRADE"))
    }

    @Test
    fun `la firma diversa la dice Android o il confronto dell'app`() {
        assertEquals(Kind.SIGNATURE, InstallFailure.kind(conflict, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package io.github.baldborne94.baldmusic signatures do not match newer version; ignoring!"))
        assertEquals(Kind.SIGNATURE, InstallFailure.kind(InstallFailure.STATUS_FAILURE, null, signatureDiffers = true))
        assertEquals(Kind.OTHER, InstallFailure.kind(conflict, null, signatureDiffers = false))
    }

    @Test
    fun `il messaggio dice cosa fare e finisce col dettaglio di Android`() {
        val signature = InstallFailure.message(conflict, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match")
        assertTrue("backup" in signature && "disinstalla" in signature && "Ripristina" in signature)
        assertTrue(signature.endsWith("(Android: INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match, codice 5)"))
        assertTrue(InstallFailure.message(InstallFailure.STATUS_FAILURE_BLOCKED, null).endsWith("(Android: codice 2)"))
        assertTrue("Blocco automatico" in InstallFailure.message(incompatible, "INSTALL_FAILED_USER_RESTRICTED"))
        assertTrue("Spazio" in InstallFailure.message(InstallFailure.STATUS_FAILURE_STORAGE, null))
    }

    @Test
    fun `con le impronte delle firme si vede quale chiave ha ciascuna`() {
        val text = InstallFailure.message(conflict, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", certificates = "1a2b3c4d5e6f" to "977dcf00eddc")
        assertTrue(text.endsWith("(firma installata 1a2b3c4d…, nuova 977dcf00…)"), text)
    }
}
