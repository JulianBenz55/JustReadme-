package com.aryan.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aryan.reader.shared.ui.SharedReaderOrientationLabels
import com.aryan.reader.shared.ui.SharedReaderScreenOrientationSheet

private const val READER_SCREEN_ORIENTATION_PREFS_NAME = "epub_reader_settings"
private const val READER_SCREEN_ORIENTATION_KEY = "reader_screen_orientation_mode"

typealias ReaderScreenOrientationMode = com.aryan.reader.shared.reader.ReaderScreenOrientationMode

private val ReaderScreenOrientationMode.persistedId: Int
    get() = when (this) {
        ReaderScreenOrientationMode.FOLLOW_SYSTEM -> 0
        ReaderScreenOrientationMode.PORTRAIT -> 1
        ReaderScreenOrientationMode.LANDSCAPE -> 2
    }

fun saveReaderScreenOrientationMode(context: Context, mode: ReaderScreenOrientationMode) {
    val prefs = context.getSharedPreferences(READER_SCREEN_ORIENTATION_PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit { putInt(READER_SCREEN_ORIENTATION_KEY, mode.persistedId) }
}

fun loadReaderScreenOrientationMode(context: Context): ReaderScreenOrientationMode {
    val prefs = context.getSharedPreferences(READER_SCREEN_ORIENTATION_PREFS_NAME, Context.MODE_PRIVATE)
    return when (prefs.getInt(READER_SCREEN_ORIENTATION_KEY, 0)) {
        1 -> ReaderScreenOrientationMode.PORTRAIT
        2 -> ReaderScreenOrientationMode.LANDSCAPE
        else -> ReaderScreenOrientationMode.FOLLOW_SYSTEM
    }
}

fun ReaderScreenOrientationMode.toRequestedOrientation(): Int {
    return when (this) {
        ReaderScreenOrientationMode.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ReaderScreenOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ReaderScreenOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

@Composable
fun ReaderScreenOrientationEffect(mode: ReaderScreenOrientationMode) {
    val context = LocalContext.current
    val activity: Activity? = remember(context) { context.findReaderOrientationActivity() }

    // Remember the orientation the activity had before the reader took over, and give it back only
    // when the reader leaves. This is deliberately separate from the effect below: if one effect
    // were keyed on `mode` and restored on dispose, every change of mode (the one-tap orientation
    // button cycles through three of them) would briefly reset the activity to its old orientation
    // before applying the new one, which can trigger an extra rotation in between.
    DisposableEffect(activity) {
        val originalOrientation = activity?.requestedOrientation
        onDispose {
            if (activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }

    DisposableEffect(activity, mode) {
        activity?.requestedOrientation = mode.toRequestedOrientation()
        onDispose {}
    }
}

/**
 * The order the one-tap orientation button walks through: automatic -> fixed landscape ->
 * fixed portrait -> automatic again.
 */
fun ReaderScreenOrientationMode.nextInToggleCycle(): ReaderScreenOrientationMode = when (this) {
    ReaderScreenOrientationMode.FOLLOW_SYSTEM -> ReaderScreenOrientationMode.LANDSCAPE
    ReaderScreenOrientationMode.LANDSCAPE -> ReaderScreenOrientationMode.PORTRAIT
    ReaderScreenOrientationMode.PORTRAIT -> ReaderScreenOrientationMode.FOLLOW_SYSTEM
}

/**
 * One-tap screen orientation button for the reader's bottom bar (EPUB and PDF).
 *
 * Each tap moves to the next mode of [nextInToggleCycle]. The icon follows the mode: the classic
 * rotate-screen glyph while automatic, and a phone outline while the orientation is fixed. The
 * phone turns by 90 degrees when the mode changes between landscape and portrait, so the change
 * is visible on the button itself and not only on the screen behind it.
 */
@Composable
fun ReaderScreenOrientationToggleButton(
    mode: ReaderScreenOrientationMode,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    reduceMotion: Boolean = false,
) {
    val label = stringResource(
        when (mode) {
            ReaderScreenOrientationMode.FOLLOW_SYSTEM -> R.string.tooltip_orientation_auto
            ReaderScreenOrientationMode.LANDSCAPE -> R.string.tooltip_orientation_landscape
            ReaderScreenOrientationMode.PORTRAIT -> R.string.tooltip_orientation_portrait
        }
    )
    val animationSpec = tween<Float>(durationMillis = if (reduceMotion) 0 else 320)
    val autoAlpha by animateFloatAsState(
        targetValue = if (mode == ReaderScreenOrientationMode.FOLLOW_SYSTEM) 1f else 0f,
        animationSpec = animationSpec,
        label = "orientationAutoIconAlpha",
    )
    val phoneRotation by animateFloatAsState(
        targetValue = if (mode == ReaderScreenOrientationMode.LANDSCAPE) 90f else 0f,
        animationSpec = animationSpec,
        label = "orientationPhoneRotation",
    )

    TooltipIconButton(
        text = label,
        description = stringResource(R.string.tooltip_orientation_cycle_desc),
        onClick = onCycle,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ScreenRotation,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.alpha(autoAlpha),
            )
            Canvas(
                modifier = Modifier
                    .size(24.dp)
                    .alpha(1f - autoAlpha)
                    .rotate(phoneRotation),
            ) {
                val strokeWidth = 2.dp.toPx()
                val bodyWidth = size.width * 0.5f
                val bodyHeight = size.height * 0.84f
                val left = (size.width - bodyWidth) / 2f
                val top = (size.height - bodyHeight) / 2f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(left, top),
                    size = Size(bodyWidth, bodyHeight),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = strokeWidth),
                )
                // Home bar: shows which edge of the phone is "down", so the 90 degree turn reads.
                val barY = top + bodyHeight - 3.5.dp.toPx()
                drawLine(
                    color = tint,
                    start = Offset(size.width / 2f - 2.dp.toPx(), barY),
                    end = Offset(size.width / 2f + 2.dp.toPx(), barY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun ReaderScreenOrientationPicker(
    selectedMode: ReaderScreenOrientationMode,
    onModeSelected: (ReaderScreenOrientationMode) -> Unit,
    modifier: Modifier = Modifier
) {
    com.aryan.reader.shared.ui.SharedReaderScreenOrientationPicker(
        selectedMode = selectedMode,
        onModeSelected = onModeSelected,
        labels = ReaderScreenOrientationMode.entries.associateWith { it.title },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreenOrientationSheet(
    selectedMode: ReaderScreenOrientationMode,
    onModeSelected: (ReaderScreenOrientationMode) -> Unit,
    onDismiss: () -> Unit
) {
    SharedReaderScreenOrientationSheet(
        selectedMode = selectedMode,
        onModeSelected = onModeSelected,
        labels = SharedReaderOrientationLabels(
            title = stringResource(R.string.visual_options_screen_orientation),
            close = stringResource(R.string.action_close),
            description = stringResource(R.string.visual_options_screen_orientation_desc),
            options = ReaderScreenOrientationMode.entries.associateWith { it.title }
        ),
        onDismiss = onDismiss
    )
}

private tailrec fun Context.findReaderOrientationActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findReaderOrientationActivity()
        else -> null
    }
}
