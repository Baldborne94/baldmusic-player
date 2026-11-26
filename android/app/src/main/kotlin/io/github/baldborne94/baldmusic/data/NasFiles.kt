package io.github.baldborne94.baldmusic.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import io.github.baldborne94.baldmusic.BaldMusicApp
import io.github.baldborne94.baldmusic.core.library.MediaId
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.nas.FileBrowserClient
import io.github.baldborne94.baldmusic.core.nas.NasException
import io.github.baldborne94.baldmusic.core.nas.NasTrash
import io.github.baldborne94.baldmusic.core.nas.NasTrasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Eliminare i brani dal NAS (scelta dell'utente: «voglio che il brano venga cancellato anche da
 * X»), con File Browser sul NAS (regole in [NasTrash]): il file va nel «Cestino BaldMusic» della
 * cartella condivisa, mai cancellato davvero. Poi esce da raccolte, playlist e «mi piace» (sul
 * server e sul telefono) e Navidrome rilegge la musica.
 *
 * La password di File Browser serve ogni volta (il suo accesso dura due ore): sta cifrata con
 * una chiave del portachiavi di Android, che non esce dal telefono, e mai nel backup.
 */
class NasFiles(private val app: BaldMusicApp) {
    private val prefs = app.getSharedPreferences("nas", Context.MODE_PRIVATE)

    data class Account(val url: String, val user: String, val password: String, val config: NasTrash.Config)

    private val _ready = MutableStateFlow(account() != null)
    /** File Browser impostato: nell'app compare «Elimina dal NAS». */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun account(): Account? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.let(::decrypt) ?: return null
        return Account(url, user, password, NasTrash.Config(musicFolder = prefs.getString(KEY_MUSIC, null) ?: NasTrash.Config().musicFolder))
    }

    /** Prova l'accesso e, se va, lo salva. null = tutto bene, altrimenti il perché. */
    suspend fun connect(url: String, user: String, password: String, musicFolder: String): String? {
        val error = runCatching { FileBrowserClient(url, app.http).login(user.trim(), password) }.exceptionOrNull()
        if (error != null) return error.message ?: "Accesso non riuscito"
        prefs.edit {
            putString(KEY_URL, url.trim())
            putString(KEY_USER, user.trim())
            putString(KEY_PASSWORD, encrypt(password))
            putString(KEY_MUSIC, "/" + musicFolder.trim().trim('/'))
        }
        _ready.value = true
        return null
    }

    fun disconnect() {
        prefs.edit { clear() }
        _ready.value = false
    }

    /** Il brano del server di una riga, se lo è. */
    fun serverSong(mediaId: String): String? =
        MediaId.parse(mediaId)?.takeIf { it.source == RemoteSource.KEY && it.isTrack }?.id

    /** Com'è andata: il messaggio da mostrare e i brani spostati davvero. */
    data class Outcome(val message: String, val moved: Set<String> = emptySet())

    /**
     * Manda nel cestino del NAS i file dei brani [songIds] e li toglie da raccolte, playlist e
     * «mi piace».
     */
    suspend fun trash(songIds: List<String>): Outcome {
        val account = account() ?: return Outcome("Imposta File Browser in Impostazioni → Server")
        val client = app.subsonic() ?: return Outcome("Nessun server collegato")
        val creds = app.settings.credentials ?: return Outcome("Nessun server collegato")
        return try {
            val songs = songIds.distinct().map { client.song(it) }
            val files = FileBrowserClient(account.url, app.http)
            val token = files.login(account.user, account.password)
            val result = NasTrasher(files, account.config).trash(token, songs)
            val moved = result.moved.toSet()
            if (moved.isNotEmpty()) {
                // Sul server: fuori da raccolte e playlist tue, niente più «mi piace».
                val remote = app.remoteSource()
                for (p in client.playlists()) {
                    if ((p.owner != null && p.owner != creds.user) || p.isAppData) continue
                    runCatching { remote?.removeFromPlaylist(p.id, moved) }
                }
                runCatching { client.star(moved, false) }
                // Sul telefono: fuori da preferiti, playlist e ascolti.
                app.userData.forgetTracks(moved.map { MediaId.track(RemoteSource.KEY, it, null).toString() })
                // Navidrome si accorge subito che il file non c'è più (serve l'amministratore; se no lo fa entro un'ora).
                runCatching { client.startScan(full = false) }
                app.tree.invalidate()
                app.settings.touch()
            }
            val message = listOfNotNull(
                moved.size.takeIf { it > 0 }?.let { if (it == 1) "Nel cestino del NAS (X:\\Cestino BaldMusic)" else "$it brani nel cestino del NAS (X:\\Cestino BaldMusic)" },
                result.failed.values.firstOrNull()?.let { reason -> "${result.failed.size} non spostati: $reason" },
            ).joinToString(". ")
            Outcome(message, moved)
        } catch (e: NasException) {
            Outcome(e.message.orEmpty())
        } catch (e: Exception) {
            Outcome(e.message ?: "Non riuscito")
        }
    }

    // --- Password cifrata col portachiavi di Android (AES-GCM, la chiave non esce dal telefono).

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEY_URL = "url"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val KEY_MUSIC = "music_folder"
        const val KEY_ALIAS = "baldmusic-nas"
    }
}
