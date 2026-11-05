package io.github.baldborne94.baldmusic.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.CoverScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()
    private var touchedAt = SystemClock.elapsedRealtime()

    override fun onUserInteraction() {
        super.onUserInteraction()
        touchedAt = SystemClock.elapsedRealtime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo alla prima apertura: ricreando l'activity (rotazione) la stessa risposta tornerebbe.
        if (savedInstanceState == null) intent?.let { app.updater.onInstallStatus(this, it) }
        // Schermo copertina da solo: con l'app davanti, parte la musica e non tocchi il telefono.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                touchedAt = SystemClock.elapsedRealtime()
                while (true) {
                    delay(1_000)
                    val idle = SystemClock.elapsedRealtime() - touchedAt
                    if (CoverScreen.autoOpen(app.settings.coverAuto, vm.coverArmed, vm.isPlaying, vm.reading, idle)) {
                        vm.openCover(this@MainActivity)
                    }
                }
            }
        }
        setContent {
            BaldMusicTheme {
                // Senza una Surface il colore del testo predefinito resta nero: su sfondo
                // scuro sparirebbero tutte le scritte senza colore esplicito.
                Surface(modifier = Modifier.fillMaxSize(), color = Background, contentColor = Color.White) {
                    AppScaffold(vm)
                }
            }
        }
    }

    // L'installatore di Android risponde qui (Updater.commit): conferma, fatto o errore.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.updater.onInstallStatus(this, intent)
    }
}
