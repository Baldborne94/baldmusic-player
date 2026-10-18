package io.github.baldborne94.baldmusic

import android.app.Application
import android.content.Context
import io.github.baldborne94.baldmusic.core.library.LibraryTree
import io.github.baldborne94.baldmusic.core.library.MusicSource
import io.github.baldborne94.baldmusic.core.library.RemoteSource
import io.github.baldborne94.baldmusic.core.subsonic.Credentials
import io.github.baldborne94.baldmusic.core.subsonic.SubsonicClient
import io.github.baldborne94.baldmusic.library.LocalSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Gli oggetti condivisi da interfaccia, servizio di riproduzione e provider delle copertine. */
class BaldMusicApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var local: LocalSource
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Un solo albero: lo vedono identico l'app e Android Auto. */
    val tree: LibraryTree by lazy { LibraryTree(::sources) }

    private var remote: RemoteSource? = null
    private var remoteCreds: Credentials? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        local = LocalSource(this)
    }

    fun sources(): List<MusicSource> = listOfNotNull(local, remoteSource())

    @Synchronized
    fun remoteSource(): RemoteSource? {
        val creds = settings.credentials
        if (creds != remoteCreds) {
            remoteCreds = creds
            remote = creds?.let { RemoteSource(SubsonicClient(it, http)) { settings.maxBitRate } }
        }
        return remote
    }

    fun subsonic(): SubsonicClient? = settings.credentials?.let { SubsonicClient(it, http) }
}

val Context.app: BaldMusicApp get() = applicationContext as BaldMusicApp
