package io.github.baldborne94.baldmusic.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.baldborne94.baldmusic.core.library.CoverScreen
import kotlinx.coroutines.delay

/**
 * «Schermo copertina» (da «In riproduzione», o da solo quando parte la musica: [CoverScreen.autoOpen]):
 * la copertina a tutto schermo ([CoverScreenContent]) anche col telefono bloccato, come il
 * salvaschermo di un Kindle. Lo schermo resta acceso e poco luminoso solo mentre suona
 * ([CoverPlayer.stayOn]); poi si spegne da solo come sempre. Sta sopra il blocco schermo senza
 * sbloccarlo: chiudendolo col telefono bloccato si torna al blocco, non al resto dell'app.
 * Col telefono in carica fa lo stesso il salvaschermo ([CoverDream]).
 */
class CoverActivity : ComponentActivity() {
    private val player by lazy { CoverPlayer(this) { finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sopra il blocco schermo, senza sbloccarlo.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // A tutto schermo: niente barre in alto e in basso (tornano scorrendo dal bordo).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        dim(true)
        setContent {
            BaldMusicTheme {
                // Ogni secondo: acceso mentre suona, poi si lascia spegnere (subito col timer o con poca batteria).
                LaunchedEffect(Unit) {
                    while (true) {
                        awake(player.stayOn(System.currentTimeMillis()))
                        delay(1_000)
                    }
                }
                CoverScreenContent(player, onClose = ::finish, onTouched = { touched -> dim(!touched) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dim(true)
        player.connect(lifecycleScope)
    }

    override fun onStop() {
        player.release()
        super.onStop()
    }

    private fun awake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Poco luminoso (ancora meno di notte); toccato lo schermo, la luminosità di sempre finché ci sono i comandi. */
    private fun dim(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) coverBrightness() else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, CoverActivity::class.java))
    }
}
