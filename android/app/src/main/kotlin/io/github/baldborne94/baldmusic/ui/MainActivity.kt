package io.github.baldborne94.baldmusic.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.baldborne94.baldmusic.app

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo alla prima apertura: ricreando l'activity (rotazione) la stessa risposta tornerebbe.
        if (savedInstanceState == null) intent?.let { app.updater.onInstallStatus(this, it) }
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
