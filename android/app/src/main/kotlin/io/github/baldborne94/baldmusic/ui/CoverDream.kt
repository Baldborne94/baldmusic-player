package io.github.baldborne94.baldmusic.ui

import android.service.dreams.DreamService
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Lo schermo copertina come salvaschermo di Android («Salvaschermo» nelle impostazioni del
 * telefono, «In carica»): col telefono in carica la copertina compare anche se la musica è
 * partita dalle cuffie, da Android Auto o con l'app chiusa, dove l'app non può aprire lo
 * schermo copertina da sola. Non suona niente, o la musica è ferma da un po': il salvaschermo
 * finisce e lo schermo si spegne come sempre.
 */
class CoverDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val scope = MainScope()
    private val player by lazy { CoverPlayer(this) { finish() } }

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Si tocca (comandi, gesti) senza che il tocco chiuda il salvaschermo.
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        dim(true)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@CoverDream)
            setViewTreeSavedStateRegistryOwner(this@CoverDream)
            setContent {
                BaldMusicTheme {
                    // Ferma da un po', col timer finito o con poca batteria: il salvaschermo finisce.
                    LaunchedEffect(Unit) {
                        delay(2_000)
                        // Partito senza musica: niente da mostrare, lo schermo si spegne come sempre.
                        if (!player.playing) finish()
                        while (true) {
                            if (!player.stayOn(System.currentTimeMillis())) finish()
                            delay(1_000)
                        }
                    }
                    CoverScreenContent(player, onClose = ::finish, onTouched = { touched -> dim(!touched) })
                }
            }
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        player.connect(scope)
    }

    override fun onDreamingStopped() {
        player.release()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        super.onDestroy()
    }

    private fun dim(on: Boolean) {
        val w = window ?: return
        w.attributes = w.attributes.apply {
            screenBrightness = if (on) coverBrightness() else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}
