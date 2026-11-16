package io.github.baldborne94.baldmusic.ui

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.baldborne94.baldmusic.R
import io.github.baldborne94.baldmusic.app
import io.github.baldborne94.baldmusic.core.library.AppTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I colori dell'app in uno dei due temi (scelta dell'utente: «pop di giorno, scuro di sera»,
 * regola in [io.github.baldborne94.baldmusic.core.library.AppTheme]). Lo scuro è quello di
 * sempre; il pop è giallo, contorni neri e rosso, come il logo.
 */
@Immutable
class ThemePalette(
    val pop: Boolean,
    val background: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surfaceLow: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val accent2: Color,
    val accentBrush: Brush,
    val danger: Color,
    /** Il contorno nero del pop (nello scuro non c'è). */
    val ink: Color,
    /** La barra delle schede in basso. */
    val bar: Color,
)

val DarkPalette = ThemePalette(
    pop = false,
    background = Color(0xFF0B0B0F),
    surface1 = Color(0xFF16171D),
    surface2 = Color(0xFF22242C),
    surface3 = Color(0xFF2C2F38),
    surfaceLow = Color(0xFF121318),
    text = Color(0xFFF4F4F6),
    textMuted = Color(0xFFA7ABB8),
    accent = Color(0xFFFF7A59),
    accent2 = Color(0xFFC026D3),
    accentBrush = Brush.linearGradient(listOf(Color(0xFFFF7A59), Color(0xFFC026D3))),
    danger = Color(0xFFFF5C6C),
    ink = Color.Transparent,
    bar = Color(0xFF0B0B0F),
)

val PopPalette = ThemePalette(
    pop = true,
    background = Color(0xFFFFF4D6),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFFFE9A8),
    surface3 = Color(0xFFFFE08A),
    surfaceLow = Color(0xFFFFF8E6),
    text = Color(0xFF111114),
    textMuted = Color(0xFF57534E),
    accent = Color(0xFFE11D48),
    accent2 = Color(0xFFFFB000),
    accentBrush = Brush.linearGradient(listOf(Color(0xFFE11D48), Color(0xFFF43F5E))),
    danger = Color(0xFFB91C1C),
    ink = Color(0xFF111114),
    bar = Color(0xFFFFD23F),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

// I colori di sempre, ora presi dal tema in uso: chi li usa non cambia.
val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val Accent2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent2
val AccentBrush: Brush @Composable @ReadOnlyComposable get() = LocalPalette.current.accentBrush
val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
val Surface1: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface1
/** Per le azioni che cancellano qualcosa. */
val Danger: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.danger
val Surface2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textMuted
/** Il testo sullo sfondo dell'app (bianco nello scuro, nero nel pop). */
val TextColor: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
val isPop: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current.pop

/** I "Brani che ti piacciono" hanno il loro colore, come una playlist speciale. */
val LikedBrush = Brush.linearGradient(listOf(Color(0xFF5B2BE0), Color(0xFFC4B5FD)))

/** Il carattere dei titoli nel pop: grosso e pieno, come il logo. */
val PopFont = FontFamily(Font(R.font.archivo_black))

private val darkColors = darkColorScheme(
    primary = DarkPalette.accent,
    onPrimary = Color.White,
    secondary = DarkPalette.accent2,
    background = DarkPalette.background,
    onBackground = DarkPalette.text,
    surface = DarkPalette.surface1,
    onSurface = DarkPalette.text,
    surfaceVariant = DarkPalette.surface2,
    onSurfaceVariant = DarkPalette.textMuted,
    surfaceContainer = DarkPalette.surface1,
    surfaceContainerHigh = DarkPalette.surface2,
    surfaceContainerHighest = DarkPalette.surface3,
    surfaceContainerLow = DarkPalette.surfaceLow,
    outline = Color(0xFF34374A),
    error = DarkPalette.danger,
)

private val popColors = lightColorScheme(
    primary = PopPalette.accent,
    onPrimary = Color.White,
    secondary = PopPalette.accent2,
    onSecondary = PopPalette.text,
    background = PopPalette.background,
    onBackground = PopPalette.text,
    surface = PopPalette.surface1,
    onSurface = PopPalette.text,
    surfaceVariant = PopPalette.surface2,
    onSurfaceVariant = PopPalette.textMuted,
    surfaceContainer = PopPalette.surface1,
    surfaceContainerHigh = PopPalette.surface2,
    surfaceContainerHighest = PopPalette.surface3,
    surfaceContainerLow = PopPalette.surfaceLow,
    outline = PopPalette.ink,
    error = PopPalette.danger,
)

private val darkTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val popTypography = darkTypography.run {
    copy(
        headlineLarge = headlineLarge.copy(fontFamily = PopFont, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp),
        headlineMedium = headlineMedium.copy(fontFamily = PopFont, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(fontFamily = PopFont, fontWeight = FontWeight.Normal),
    )
}

/**
 * Il tema dell'app: pop o scuro secondo l'impostazione «Tema» (in automatico come il telefono,
 * [AppTheme.isPop]). Tutta l'app e le schermate a parte (schermo copertina) passano da qui.
 */
@Composable
fun BaldMusicTheme(content: @Composable () -> Unit) {
    val mode by LocalContext.current.app.settings.theme.collectAsState()
    val pop = AppTheme.isPop(mode, isSystemInDarkTheme())
    val palette = if (pop) PopPalette else DarkPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = if (pop) popColors else darkColors, typography = if (pop) popTypography else darkTypography, content = content)
    }
}

private val DarkSectionTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
private val PopSectionTitle = TextStyle(fontSize = 21.sp, fontFamily = PopFont, letterSpacing = (-0.2).sp)

val SectionTitle: TextStyle @Composable @ReadOnlyComposable get() = if (LocalPalette.current.pop) PopSectionTitle else DarkSectionTitle

/**
 * Lo stile dei fumetti nel pop: contorno nero e ombra netta spostata in basso a destra. Nello
 * scuro non fa niente. Va prima di `clip`/`background` nella catena.
 */
fun Modifier.popCard(shape: Shape = RoundedCornerShape(10.dp), shadow: Dp = 3.dp, border: Dp = 2.5.dp): Modifier = composed {
    val palette = LocalPalette.current
    if (!palette.pop) return@composed this
    val ink = palette.ink
    this
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(shadow.toPx(), shadow.toPx()) { drawOutline(outline, ink) }
        }
        .border(border, ink, shape)
}

/**
 * Le sfumature di mix e categorie: una tavolozza sola, intonata all'accento
 * dell'app, scelta da una stringa così la stessa tessera ha sempre lo stesso colore.
 * È la stessa dell'app web (web/src/lib/colors.ts).
 */
private val Tiles = listOf(
    Color(0xFFFF7A59) to Color(0xFFE0245E),
    Color(0xFF7C3AED) to Color(0xFFC026D3),
    Color(0xFF0EA5E9) to Color(0xFF2563EB),
    Color(0xFF10B981) to Color(0xFF0F766E),
    Color(0xFFF59E0B) to Color(0xFFDC2626),
    Color(0xFF6366F1) to Color(0xFF9333EA),
    Color(0xFF14B8A6) to Color(0xFF0369A1),
    Color(0xFFF43F5E) to Color(0xFF9F1239),
)

fun tileColors(key: String): Pair<Color, Color> = Tiles[(key.hashCode() and Int.MAX_VALUE) % Tiles.size]
fun tileColor(key: String): Color = tileColors(key).first
fun tileBrush(key: String): Brush = tileColors(key).let { (a, b) -> Brush.linearGradient(listOf(a, b)) }

private val colorCache = HashMap<Uri, Color>()

/**
 * Il colore dominante di una copertina, per sfondi e mini-player. Calcolato una
 * volta per copertina; finché non è pronto vale [fallback].
 */
@Composable
fun rememberArtworkColor(uri: Uri?, fallback: Color = Surface2): State<Color> {
    val context = LocalContext.current
    return produceState(initialValue = uri?.let { synchronized(colorCache) { colorCache[it] } } ?: fallback, uri) {
        if (uri == null) {
            value = fallback
            return@produceState
        }
        synchronized(colorCache) { colorCache[uri] }?.let {
            value = it
            return@produceState
        }
        val request = ImageRequest.Builder(context).data(uri).size(128).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@produceState
        val color = withContext(Dispatchers.Default) {
            val palette = Palette.from(result.image.toBitmap()).generate()
            val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
            swatch?.let { Color(it.rgb) }
        } ?: return@produceState
        // Abbastanza scuro da leggerci sopra il testo bianco, abbastanza vivo da riconoscerlo.
        val tuned = lerp(color, Color.Black, 0.35f)
        synchronized(colorCache) { colorCache[uri] = tuned }
        value = tuned
    }
}

/** Testo e icone di «In riproduzione»: bianco sul colore della copertina, nero sul giallo del pop. */
val PlayerText: Color @Composable @ReadOnlyComposable get() = if (LocalPalette.current.pop) PopPalette.ink else Color.White

/** L'icona dentro il tasto play di «In riproduzione»: nero sul bianco, giallo sul nero del pop. */
val PlayerOnText: Color @Composable @ReadOnlyComposable get() = if (LocalPalette.current.pop) PopPalette.bar else Color.Black

/**
 * Lo sfondo pop di «In riproduzione»: giallo a puntini, come i fumetti. I puntini si calcolano
 * una volta per dimensione (un percorso solo), non a ogni ridisegno.
 */
fun Modifier.popDots(): Modifier = this
    .background(PopPalette.bar)
    .drawWithCache {
        val step = 12.dp.toPx()
        val radius = 1.6.dp.toPx()
        val dots = Path().apply {
            var y = step / 2
            var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) step / 2 else step
                while (x < size.width) {
                    addOval(Rect(x - radius, y - radius, x + radius, y + radius))
                    x += step
                }
                y += step
                row++
            }
        }
        onDrawBehind { drawPath(dots, Color(0x24111114)) }
    }

/** Il testo su un tasto o una scelta dell'accento: nero sull'arancio, bianco sul rosso del pop. */
val OnAccent: Color @Composable @ReadOnlyComposable get() = if (LocalPalette.current.pop) Color.White else Color.Black
