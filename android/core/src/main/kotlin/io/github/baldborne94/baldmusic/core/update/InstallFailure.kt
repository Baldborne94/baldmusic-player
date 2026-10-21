package io.github.baldborne94.baldmusic.core.update

/**
 * Perché Android non ha installato l'aggiornamento, detto in modo che si sappia cosa fare.
 * L'installatore dà un codice ([PackageInstaller].STATUS_*, qui con gli stessi numeri) e un
 * messaggio tecnico (es. "INSTALL_FAILED_UPDATE_INCOMPATIBLE: … signatures do not match"):
 * lo stesso codice copre casi diversi (firma diversa, telefono che blocca le installazioni,
 * versione più vecchia), quindi conta il messaggio, che si mostra sempre in fondo.
 */
object InstallFailure {
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7

    enum class Kind { SIGNATURE, BLOCKED, DOWNGRADE, STORAGE, INVALID, OTHER }

    /**
     * Il caso. [signatureDiffers] è il confronto fatto dall'app prima di installare (null se
     * non si è potuto fare): quando dice "diversa" è certo, qualunque cosa dica Android.
     */
    fun kind(status: Int, detail: String?, signatureDiffers: Boolean? = null): Kind {
        val d = detail.orEmpty().uppercase()
        return when {
            signatureDiffers == true -> Kind.SIGNATURE
            "UPDATE_INCOMPATIBLE" in d || "SIGNATURES DO NOT MATCH" in d || "NO_CERTIFICATES" in d || "INCONSISTENT_CERTIFICATES" in d -> Kind.SIGNATURE
            "VERSION_DOWNGRADE" in d -> Kind.DOWNGRADE
            "USER_RESTRICTED" in d || status == STATUS_FAILURE_BLOCKED -> Kind.BLOCKED
            "INSUFFICIENT_STORAGE" in d || status == STATUS_FAILURE_STORAGE -> Kind.STORAGE
            status == STATUS_FAILURE_INVALID -> Kind.INVALID
            // Firma diversa solo se lo dice Android o il confronto: un conflitto può essere altro.
            else -> Kind.OTHER
        }
    }

    /** Il messaggio per chi usa l'app; il dettaglio di Android in fondo, per capire il resto. */
    fun message(status: Int, detail: String?, signatureDiffers: Boolean? = null): String {
        val text = when (kind(status, detail, signatureDiffers)) {
            Kind.SIGNATURE ->
                "La BaldMusic installata è firmata con un'altra chiave, e Android non la aggiorna sopra. Una volta sola: " +
                    "Impostazioni → Backup → «Salva un backup…», disinstalla BaldMusic, installa l'APK dalla pagina della release, " +
                    "poi «Ripristina» dalla Home (server e chiave di AcoustID vanno rimessi). Dopo, gli aggiornamenti si installano sopra."
            Kind.BLOCKED ->
                "Il telefono blocca le installazioni fatte dalle app. Su Samsung spegni «Blocco automatico» " +
                    "(Impostazioni → Sicurezza e privacy), oppure scarica l'APK dal browser con «Pagina della release»."
            Kind.DOWNGRADE -> "Sul telefono c'è già una versione uguale o più nuova di questa."
            Kind.STORAGE -> "Spazio insufficiente sul telefono per l'aggiornamento."
            Kind.INVALID -> "Il file scaricato è rovinato: riprova."
            Kind.OTHER -> "Android non ha installato l'aggiornamento. Prova a scaricarlo dal browser con «Pagina della release»."
        }
        val code = "codice $status"
        return detail?.takeIf { it.isNotBlank() }?.let { "$text\n(Android: ${it.trim().take(300)}, $code)" } ?: "$text\n(Android: $code)"
    }
}
