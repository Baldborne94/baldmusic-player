package io.github.baldborne94.baldmusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.baldborne94.baldmusic.core.library.LetterIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Le lettere sul bordo destro di un elenco lungo ([LetterIndex]): compaiono mentre si scorre
 * e spariscono poco dopo, così non coprono i ⋮ delle righe. Toccando o trascinando il dito
 * sulla colonna si salta all'intestazione della lettera ([onJump] riceve la sua posizione).
 */
@Composable
fun LetterRail(entries: List<LetterIndex.Entry>, scrolling: Boolean, onJump: suspend (Int) -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    var touched by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, touched) {
        if (scrolling || touched != null) {
            visible = true
        } else {
            delay(1500)
            visible = false
        }
    }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.fillMaxHeight().padding(vertical = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            // La lettera grande accanto al dito, come nella rubrica.
            touched?.let { letter ->
                Box(Modifier.size(64.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                    Text(letter, fontSize = 30.sp, fontWeight = FontWeight.Black, color = Background)
                }
                Spacer(Modifier.width(12.dp))
            }
            BoxWithConstraints(Modifier.fillMaxHeight()) {
                // Su uno schermo basso (telefono in orizzontale) si mostra una lettera ogni tanto; il dito le prende tutte.
                val step = ceil(entries.size * 16f / maxHeight.value.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
                val shown = entries.filterIndexed { i, _ -> i % step == 0 }
                val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                fun jump(y: Float) {
                    val entry = LetterIndex.at(entries, y / height) ?: return
                    if (entry.letter == touched) return
                    touched = entry.letter
                    scope.launch { onJump(entry.position) }
                }
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface2.copy(alpha = 0.9f))
                        .pointerInput(entries) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                jump(down.position.y)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val finger = event.changes.firstOrNull { it.pressed } ?: break
                                    finger.consume()
                                    jump(finger.position.y)
                                }
                                touched = null
                            }
                        }
                        .semantics { contentDescription = "Indice alfabetico: tocca una lettera per saltare lì" },
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    shown.forEach { entry ->
                        Text(
                            entry.letter,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (entry.letter == touched) Accent else TextMuted,
                        )
                    }
                }
            }
        }
    }
}
