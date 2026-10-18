package io.github.baldborne94.baldmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.baldborne94.baldmusic.artwork.ArtworkProvider
import io.github.baldborne94.baldmusic.core.library.ListeningStats
import io.github.baldborne94.baldmusic.core.library.Ranked
import java.text.DateFormat
import java.util.Date

@Composable
private fun PageHeader(title: String) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** "Il tuo anno in musica", ma sempre disponibile: cosa e quanto ascolti. */
@Composable
fun StatsScreen(vm: PlayerViewModel) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val stats = remember(lib.plays) { ListeningStats.from(lib) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF1D3B6B), 0.35f to Background)),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { PageHeader("Le tue statistiche") }
        if (stats.totalPlays == 0) {
            item { Message("Ascolta un po' di musica: qui compariranno i tuoi artisti e brani preferiti.") }
            return@LazyColumn
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Hai ascoltato", color = TextMuted, fontWeight = FontWeight.SemiBold)
                Text(
                    "%,d minuti".format(stats.minutes).replace(',', '.'),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 50.sp,
                    style = MaterialTheme.typography.headlineLarge.copy(brush = AccentBrush),
                )
                stats.since?.let {
                    Text("dal ${DateFormat.getDateInstance(DateFormat.LONG).format(Date(it))}", color = TextMuted)
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${stats.totalPlays}", "ascolti", Modifier.weight(1f))
                StatTile("${stats.tracks}", "brani diversi", Modifier.weight(1f))
                StatTile("${stats.artists}", "artisti", Modifier.weight(1f))
            }
        }
        if (stats.topArtists.isNotEmpty()) {
            item { SectionHeader("I tuoi artisti") }
            item {
                val first = stats.topArtists.first()
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFE13300), Color(0xFF8D67AB)))).padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(rankItem(context, first), Modifier.size(96.dp), shape = androidx.compose.foundation.shape.CircleShape)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Il tuo n. 1", color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.SemiBold)
                            Text(first.name, fontSize = 28.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${first.plays} ascolti", color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }
            items(stats.topArtists.drop(1).withIndex().toList()) { (i, r) -> RankRow(i + 2, r, round = true) }
        }
        if (stats.topTracks.isNotEmpty()) {
            item { SectionHeader("I tuoi brani") }
            items(stats.topTracks.withIndex().toList()) { (i, r) -> RankRow(i + 1, r) }
        }
        if (stats.topAlbums.isNotEmpty()) {
            item { SectionHeader("I tuoi album") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(stats.topAlbums) { r ->
                        Column(Modifier.width(140.dp)) {
                            Artwork(rankItem(context, r), Modifier.size(140.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${r.plays} ascolti", color = TextMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun rankItem(context: android.content.Context, r: Ranked) = MediaItem.Builder()
    .setMediaId("stats/${r.name}")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(r.name).setArtworkUri(r.artwork?.let { ArtworkProvider.uri(context, it) }).build())
    .build()

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Surface1).padding(14.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(label, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun RankRow(position: Int, r: Ranked, round: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$position", Modifier.width(28.dp), fontWeight = FontWeight.Black, fontSize = 18.sp, color = TextMuted)
        Artwork(rankItem(context, r), Modifier.size(52.dp), shape = if (round) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(r.subtitle, "${r.plays} ascolti").joinToString(" · "), color = TextMuted, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** Equalizzatore ed effetti audio. */
@Composable
fun AudioScreen(vm: PlayerViewModel) {
    val prefs by vm.audio.collectAsStateWithLifecycle()
    val info by vm.audioEffects.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        PageHeader("Equalizzatore ed effetti")

        val eq = info
        if (eq == null) {
            Message("Avvia un brano: l'equalizzatore si aggancia alla riproduzione.")
        } else {
            SwitchRow("Equalizzatore", "Regola bassi, medi e alti", prefs.eqEnabled) { on -> vm.updateAudio { it.copy(eqEnabled = on) } }

            if (eq.presets.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("Personalizzato", selected = prefs.preset < 0) { vm.updateAudio { it.copy(preset = -1, eqEnabled = true) } } }
                    items(eq.presets.withIndex().toList()) { (i, name) ->
                        Chip(name, selected = prefs.preset == i) {
                            val levels = vm.presetLevels(i)
                            vm.updateAudio { it.copy(preset = i, eqEnabled = true, bands = levels ?: it.bands) }
                        }
                    }
                }
            }

            if (eq.bands.isNotEmpty()) {
                // Bande verticali, come un equalizzatore vero.
                Row(
                    Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    eq.bands.forEachIndexed { i, hz ->
                        val level = prefs.bands.getOrElse(i) { 0 }
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${if (level > 0) "+" else ""}${level / 100}", fontSize = 12.sp, color = TextMuted)
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Slider(
                                    value = level.toFloat(),
                                    onValueChange = { v -> vm.updateAudio { it.withBand(i, v.toInt(), eq.bands.size).copy(eqEnabled = true) } },
                                    valueRange = eq.minLevel.toFloat()..eq.maxLevel.toFloat(),
                                    enabled = prefs.eqEnabled,
                                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                                    modifier = Modifier.fillMaxHeight().vertical(),
                                )
                            }
                            Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                }
            }

            if (eq.bassBoost) {
                LevelRow("Bass boost", prefs.bassBoost, enabled = prefs.eqEnabled) { v -> vm.updateAudio { it.copy(bassBoost = v) } }
            }
            if (eq.virtualizer) {
                LevelRow("Spazialità (cuffie)", prefs.virtualizer, enabled = prefs.eqEnabled) { v -> vm.updateAudio { it.copy(virtualizer = v) } }
            }
            if (eq.loudness) {
                SwitchRow("Volume uniforme", "Alza i brani registrati più bassi", prefs.loudness > 0) { on ->
                    vm.updateAudio { it.copy(loudness = if (on) 600 else 0) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Riproduzione", style = SectionTitle, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SwitchRow("Salta i silenzi", "Elimina le pause all'inizio e alla fine dei brani", prefs.skipSilence) { on -> vm.updateAudio { it.copy(skipSilence = on) } }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Velocità: ${formatSpeed(prefs.speed)}×", fontWeight = FontWeight.SemiBold)
            Slider(
                value = prefs.speed,
                onValueChange = { v -> vm.setSpeed(v) },
                valueRange = 0.5f..2f,
                steps = 29,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
        }
    }
}

/**
 * I cursori di Compose sono orizzontali: questo li ruota di 90° scambiando anche
 * larghezza e altezza nel layout, così ognuno occupa solo la sua colonna.
 */
private fun Modifier.vertical(): Modifier = this
    .graphicsLayer {
        rotationZ = 270f
        transformOrigin = TransformOrigin(0f, 0f)
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            ),
        )
        layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
    }

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = TextMuted, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}

@Composable
private fun LevelRow(title: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("$title: ${value / 10}%", fontWeight = FontWeight.SemiBold)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..1000f,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )
    }
}
