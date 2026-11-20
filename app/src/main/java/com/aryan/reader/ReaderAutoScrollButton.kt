package com.aryan.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Top bar shortcut that starts auto scroll, so it no longer has to be dug out of the three-dot
 * menu. Shared by the EPUB and PDF readers; both only support auto scroll in their vertical
 * scroll mode and not while text-to-speech is running, which callers express through [enabled].
 */
@Composable
fun ReaderAutoScrollButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    val label = stringResource(R.string.menu_auto_scroll)
    TooltipIconButton(
        text = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        ReaderAutoScrollIcon(
            tint = tint,
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.38f)
                .semantics { contentDescription = label },
        )
    }
}

/** Two stacked chevrons pointing down: the page keeps moving down by itself. */
@Composable
fun ReaderAutoScrollIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val unit = size.width / 24f
        val strokeWidth = 2.2f * unit
        fun chevron(top: Float) {
            drawLine(
                color = tint,
                start = Offset(6f * unit, top * unit),
                end = Offset(12f * unit, (top + 6f) * unit),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(12f * unit, (top + 6f) * unit),
                end = Offset(18f * unit, top * unit),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        chevron(4f)
        chevron(11f)
    }
}
