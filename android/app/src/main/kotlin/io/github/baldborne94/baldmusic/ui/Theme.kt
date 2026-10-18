package io.github.baldborne94.baldmusic.ui

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

val Accent = Color(0xFFFF7A59)
val Accent2 = Color(0xFFC026D3)
val AccentBrush = Brush.linearGradient(listOf(Accent, Accent2))
val Background = Color(0xFF0B0B0F)
val Surface1 = Color(0xFF16171D)
val Surface2 = Color(0xFF22242C)
val TextMuted = Color(0xFFA7ABB8)

/** I "Brani che ti piacciono" hanno il loro colore, come una playlist speciale. */
val LikedBrush = Brush.linearGradient(listOf(Color(0xFF5B2BE0), Color(0xFFC4B5FD)))

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent2,
    background = Background,
    onBackground = Color(0xFFF4F4F6),
    surface = Surface1,
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Color(0xFF2C2F38),
    surfaceContainerLow = Color(0xFF121318),
    outline = Color(0xFF34374A),
    error = Color(0xFFFF5C6C),
)

private val typography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun BaldMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

val SectionTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)

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
