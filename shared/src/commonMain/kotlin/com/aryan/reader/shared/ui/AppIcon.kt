package com.aryan.reader.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/** What a shape of the logo is, so the theme can decide how to paint it. */
internal enum class AppIconRole { Bracket, Accent, CubeTop, CubeLeft, CubeRight }

/** One flat shape of the logo, in the order the artwork paints them. */
internal data class AppIconLayer(
    val pathData: String,
    val role: AppIconRole,
    /** The artwork's own colour; the theme keeps its lightness and replaces hue and saturation. */
    val colour: Color,
)

/**
 * The app's own icon: the Vellum logo, repainted in the app theme.
 *
 * The launcher icon ships the logo in its fixed purple, which is right on a home
 * screen and wrong inside the app: it ignores light/dark and any dynamic or custom
 * seed colour. So the in-app icon keeps the logo's geometry -- generated from the one
 * source in `scripts/generate_app_icons.py` -- and repaints it: the four brackets take
 * the theme's `onSurface`, and the rhombi and cube faces take the hue and saturation
 * of the theme's primary while each keeps its own lightness ([inHueOf]), so the cube
 * keeps its three readable faces. The plate behind it is a themed surface.
 *
 * Pure Compose, so it lives in shared and works on every host.
 */
@Composable
fun AppIcon(
    contentDescription: String,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(percent = LAUNCHER_CORNER_PERCENT),
    plateColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(plateColor),
        contentAlignment = Alignment.Center,
    ) {
        ThemedLogo(
            contentDescription = contentDescription,
            // height comes from the artwork's own aspect ratio, so the logo is
            // never distorted and the generated file stays the single source
            // of its proportions
            modifier = Modifier
                .fillMaxWidth(MARK_WIDTH_FRACTION)
                .aspectRatio(AppIconArtwork.viewportWidth / AppIconArtwork.viewportHeight),
        )
    }
}

@Composable
private fun ThemedLogo(contentDescription: String, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.onSurface
    // Paths and colours are pure functions of the artwork and the theme, so they
    // are built once per theme rather than per draw.
    val layers = remember(primary, neutral) {
        AppIconArtwork.layers.map { layer ->
            val path = PathParser().parsePathString(layer.pathData).toPath()
            val colour = when (layer.role) {
                AppIconRole.Bracket -> neutral
                else -> layer.colour.inHueOf(primary)
            }
            PaintedLayer(path, colour)
        }
    }
    Canvas(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    ) {
        // The artwork is authored in source coordinates; the canvas is laid out
        // to the artwork's own viewport, so one scale factor maps between them
        // on every host and at every density. Both transforms pivot on the
        // origin -- `scale` otherwise pivots on the middle of the canvas, which
        // would slide the logo off the plate.
        val unit = size.width / AppIconArtwork.viewportWidth
        withTransform({
            translate(
                AppIconArtwork.sourceTranslateX * unit,
                AppIconArtwork.sourceTranslateY * unit,
            )
            scale(
                AppIconArtwork.sourceScale * unit,
                AppIconArtwork.sourceScale * unit,
                pivot = Offset.Zero,
            )
        }) {
            layers.forEach { drawPath(it.path, it.colour) }
        }
    }
}

/** One artwork layer, ready to draw: its path parsed and its colour themed. */
private data class PaintedLayer(val path: Path, val colour: Color)

/**
 * The artwork's colour, re-hued into the app theme.
 *
 * The logo's accents are a ladder of one hue: pale cube top, mid rhombi, deep cube
 * side. Giving each the theme primary's hue and saturation while keeping its own
 * lightness keeps that ladder -- and with it the cube's three readable faces --
 * while the icon follows light/dark, dynamic colour and any custom seed.
 */
internal fun Color.inHueOf(primary: Color, alpha: Float = this.alpha): Color {
    val (hue, saturation) = primary.hueAndSaturation()
    return Color.hsl(hue, saturation, hslLightness(), alpha)
}

/** The HSL lightness of a colour, in 0..1. */
internal fun Color.hslLightness(): Float =
    (maxOf(red, green, blue) + minOf(red, green, blue)) / 2f

/** The HSL hue in degrees and the HSL saturation of a colour. */
internal fun Color.hueAndSaturation(): Pair<Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    if (delta == 0f) return 0f to 0f
    val lightness = (max + min) / 2f
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (max) {
        red -> ((green - blue) / delta) % 6f
        green -> (blue - red) / delta + 2f
        else -> (red - green) / delta + 4f
    } * 60f
    // Saturation is a ratio of channel distances, so it can only leave 0..1 by
    // a rounding step rather than by intent -- and it does: on a colour with a
    // channel at 0 or 255 the numerator and the denominator above are
    // mathematically equal (saturation is exactly one), and Float can still
    // make them differ in the last bit, which puts the quotient a hair over
    // one. `Color.hsl` rejects that outright, so such colours -- Material You
    // primaries among them, since its tone-40 shades and tone-80 tints clip a
    // channel -- are pulled back to the value the formula already claims.
    return ((hue + 360f) % 360f) to saturation.coerceIn(0f, 1f)
}

/**
 * The mark's width as a fraction of the plate's.
 *
 * The logo is taller than wide (about 0.84:1), so the fraction is set by its height:
 * 0.54 of the width puts it at ~64% of the plate's height. At the tightest slot, a
 * 32dp circle, the logo's farthest point is ~11.9dp from its centre, inside the
 * circle's 16dp radius.
 */
private const val MARK_WIDTH_FRACTION = 0.54f

/** The launcher's own mask, so an in-app plate reads as the icon rather than a rounded box. */
private const val LAUNCHER_CORNER_PERCENT = 31
