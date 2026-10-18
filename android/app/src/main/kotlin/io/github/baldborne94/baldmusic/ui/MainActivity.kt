package io.github.baldborne94.baldmusic.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
