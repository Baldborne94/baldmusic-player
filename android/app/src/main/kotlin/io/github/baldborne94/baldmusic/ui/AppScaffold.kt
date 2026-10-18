package io.github.baldborne94.baldmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/** Le schermate dell'app; la pila si scorre col tasto indietro. */
sealed interface Screen {
    data object Home : Screen
    data class Browse(val mediaId: String, val title: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Queue : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(vm: PlayerViewModel) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    val screen = stack.last()
    val go: (Screen) -> Unit = { stack.add(it) }

    BackHandler(enabled = nowPlaying || stack.size > 1) {
        if (nowPlaying) nowPlaying = false else stack.removeAt(stack.lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (screen) {
                                Screen.Home -> "BaldMusic"
                                is Screen.Browse -> screen.title
                                Screen.Search -> "Cerca"
                                Screen.Settings -> "Impostazioni"
                                Screen.Queue -> "Coda"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (stack.size > 1) {
                            IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro")
                            }
                        }
                    },
                    actions = {
                        if (screen != Screen.Search) IconButton(onClick = { go(Screen.Search) }) { Icon(Icons.Rounded.Search, "Cerca") }
                        if (screen != Screen.Queue) IconButton(onClick = { go(Screen.Queue) }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Coda") }
                        if (screen != Screen.Settings) IconButton(onClick = { go(Screen.Settings) }) { Icon(Icons.Rounded.Settings, "Impostazioni") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    MiniPlayer(vm, onOpen = { nowPlaying = true })
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    Screen.Home -> HomeScreen(vm, go)
                    is Screen.Browse -> BrowseScreen(vm, screen, go)
                    Screen.Search -> SearchScreen(vm, go)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Queue -> QueueScreen(vm)
                }
            }
        }

        AnimatedVisibility(
            visible = nowPlaying && vm.current != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(vm, onClose = { nowPlaying = false }, onQueue = {
                nowPlaying = false
                go(Screen.Queue)
            })
        }
    }
}
