package io.github.baldborne94.baldmusic

import android.app.Application
import android.content.Context
import io.github.baldborne94.baldmusic.core.library.LibraryTree
import io.github.baldborne94.baldmusic.core.library.LocalCatalog
import io.github.baldborne94.baldmusic.core.library.MusicSource
import io.github.baldborne94.baldmusic.core.library.PersonalSource
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import io.github.baldborne94.baldmusic.data.Backups
import io.github.baldborne94.baldmusic.data.LyricsRepository
import io.github.baldborne94.baldmusic.data.UserData
import io.github.baldborne94.baldmusic.library.LocalSource
import io.github.baldborne94.baldmusic.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Gli oggetti condivisi da interfaccia, servizio di riproduzione e provider delle copertine. */
class BaldMusicApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var local: LocalSource
        private set
    lateinit var userData: UserData
        private set
    lateinit var personal: PersonalSource
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val lyrics: LyricsRepository by lazy { LyricsRepository(this, http) }

    /** Backup della tua musica, a mano e in automatico in Download/BaldMusic. */
    val backups: Backups by lazy { Backups(this, userData, local) }

    /** Aggiornamenti dell'app dalla release "apk-latest" di GitHub. */
    val updater: Updater by lazy { Updater(this, http) }

    /** Un solo albero: lo vedono identico l'app e Android Auto. */
    val tree: LibraryTree by lazy { LibraryTree(::sources) }

    /**
     * Fine del timer di spegnimento (millis), [SLEEP_END_OF_TRACK] per "a fine
     * brano", null se spento. Lo gestisce il servizio, l'interfaccia lo mostra.
     */
    val sleepTimer = MutableStateFlow<Long?>(null)

    private var remote: RemoteSource? = null
    private var remoteCreds: Credentials? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        // Tag letti dai file: si ricarica la libreria, come quando cambiano le impostazioni.
        local = LocalSource(
            this,
            onTagsRead = { settings.touch() },
            // Le cartelle diventano raccolte, e restano allineate ai file.
            onSongs = { songs -> userData.syncFolders(LocalCatalog.folderCollections(songs), songs.mapTo(HashSet()) { LocalCatalog.ref(it).id }) },
            plays = { userData.value.plays.mapValues { it.value.count } },
        )
        userData = UserData(this)
        personal = PersonalSource { userData.value }
        backups.start()
    }

    /** La tua musica per prima: in Android Auto è la prima scheda. */
    fun sources(): List<MusicSource> = listOfNotNull(personal, local, remoteSource())

    @Synchronized
    fun remoteSource(): RemoteSource? {
        val creds = settings.credentials
        if (creds != remoteCreds) {
            remoteCreds = creds
            remote = creds?.let { RemoteSource(SubsonicClient(it, http)) }
        }
        return remote
    }

    fun subsonic(): SubsonicClient? = settings.credentials?.let { SubsonicClient(it, http) }

    companion object {
        const val SLEEP_END_OF_TRACK = -1L
    }
}

val Context.app: BaldMusicApp get() = applicationContext as BaldMusicApp
