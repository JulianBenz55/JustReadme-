package com.aryan.reader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** How the library screens paint their background. */
enum class AppBackgroundStyle(val id: String) {
    /** The theme colour as a soft gradient into the normal background (the original look). */
    GRADIENT("gradient"),

    /** Plain black. */
    BLACK("black"),

    /** The theme colour at the top, fading into black. */
    COLOR_TO_BLACK("color_to_black");

    companion object {
        fun fromId(id: String?): AppBackgroundStyle = entries.firstOrNull { it.id == id } ?: GRADIENT
    }
}

/** Remembers the chosen background style and exposes it as Compose state. */
object AppBackgroundStore {
    private const val PREFS = "app_background_prefs"
    private const val KEY_STYLE = "style"

    var style by mutableStateOf(AppBackgroundStyle.GRADIENT)
        private set

    fun load(context: Context) {
        style = AppBackgroundStyle.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STYLE, null),
        )
    }

    fun set(context: Context, value: AppBackgroundStyle) {
        style = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, value.id).apply()
    }
}

internal fun ColorScheme.isDarkScheme(): Boolean = background.luminance() < 0.5f

/**
 * The background painted behind the library. The black styles only make sense with a dark theme
 * (dark text on black would be unreadable), so in a light theme every style falls back to the gradient.
 */
internal fun appBackgroundBrush(style: AppBackgroundStyle, scheme: ColorScheme, dark: Boolean): Brush = when {
    !dark || style == AppBackgroundStyle.GRADIENT -> Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to scheme.primary.copy(alpha = 0.16f),
            0.42f to scheme.tertiary.copy(alpha = 0.07f),
            1.0f to scheme.background,
        ),
    )
    style == AppBackgroundStyle.BLACK -> SolidColor(Color.Black)
    else -> Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to lerp(Color.Black, scheme.primary, 0.5f),
            0.55f to lerp(Color.Black, scheme.primary, 0.12f),
            1.0f to Color.Black,
        ),
    )
}

/** Background picker shown in the app theme sheet: three small previews. */
@Composable
internal fun AppBackgroundStyleControls() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.app_background_title),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppBackgroundStyle.entries.forEach { option ->
                val selected = AppBackgroundStore.style == option
                val label = when (option) {
                    AppBackgroundStyle.GRADIENT -> R.string.app_background_gradient
                    AppBackgroundStyle.BLACK -> R.string.app_background_black
                    AppBackgroundStyle.COLOR_TO_BLACK -> R.string.app_background_color_to_black
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .clip(RoundedCornerShape(14.dp))
                            // The preview always shows the dark look, so the black options are visible.
                            .background(appBackgroundBrush(option, scheme, dark = true))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) scheme.primary else scheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable { AppBackgroundStore.set(context, option) },
                    )
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.app_background_hint),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
