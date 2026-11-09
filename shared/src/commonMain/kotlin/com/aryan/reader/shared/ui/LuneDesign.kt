package com.aryan.reader.shared.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * Design language borrowed from the Lune music player (https://github.com/MrDemonc/Lune):
 * springy press feedback, frosted-glass surfaces with a hairline light edge, and soft
 * colour that drifts slowly behind the content so the glass has something to refract.
 * The helpers only decorate existing layouts; they never change where things are placed.
 */

/**
 * Lune's "bounce click": the element springs down while the finger is on it and springs
 * back on release. It observes the pointer without consuming it, so it can sit in front of
 * `clickable` / `combinedClickable` without changing how taps and long presses behave.
 */
fun Modifier.bounceClick(
    scaleDown: Float = 0.95f,
    dampingRatio: Float = Spring.DampingRatioMediumBouncy,
    stiffness: Float = Spring.StiffnessMedium,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(scaleDown, dampingRatio, stiffness) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val pressJob = scope.launch {
                    scale.animateTo(scaleDown, spring(dampingRatio = dampingRatio, stiffness = stiffness))
                }
                waitForUpOrCancellation()
                pressJob.cancel()
                scope.launch {
                    scale.animateTo(1f, spring(dampingRatio = dampingRatio, stiffness = stiffness))
                }
            }
        }
}

/** True when the active Material colour scheme is a dark one. */
@Composable
fun luneIsDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Translucent fill for frosted-glass cards, tuned separately for dark and light schemes. */
@Composable
fun luneGlassFill(strength: Float = 1f): Color {
    val dark = luneIsDark()
    val base = if (dark) Color.White else MaterialTheme.colorScheme.surface
    val alpha = (if (dark) 0.09f else 0.62f) * strength
    return base.copy(alpha = alpha.coerceIn(0f, 1f))
}

/** Hairline light edge that makes a glass surface read as a pane of glass. */
@Composable
fun luneGlassBorder(): Color =
    if (luneIsDark()) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.75f)

/** Adds the hairline glass edge around [shape]. */
@Composable
fun Modifier.luneGlassEdge(shape: Shape): Modifier = border(1.dp, luneGlassBorder(), shape)

/**
 * Slowly drifting blobs of the theme colours behind the content. Drawing happens in the
 * draw phase from animated values, so the animation never triggers recomposition.
 */
fun Modifier.luneAmbientBlobs(primary: Color, secondary: Color, tertiary: Color): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "luneAmbient")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(22_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "luneAmbientDrift",
    )
    drawBehind {
        val w = size.width
        val h = size.height
        val radius = maxOf(w, h) * 0.62f
        fun blob(color: Color, cx: Float, cy: Float, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        blob(primary, w * (0.15f + 0.25f * drift), h * (0.10f + 0.10f * drift), 0.20f)
        blob(secondary, w * (0.95f - 0.25f * drift), h * (0.45f + 0.10f * drift), 0.12f)
        blob(tertiary, w * (0.30f + 0.20f * drift), h * (0.95f - 0.12f * drift), 0.14f)
    }
}
