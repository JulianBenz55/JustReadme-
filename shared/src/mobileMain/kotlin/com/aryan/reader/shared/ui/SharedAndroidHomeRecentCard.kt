package com.aryan.reader.shared.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cards at least this wide switch from the cover grid style to the wide list style. */
private val WideCardMinWidth = 260.dp

/** Book cover aspect ratio (width / height), close to a real paperback cover. */
private const val CoverAspect = 0.68f

/** Cover size inside the wide list card (width / height follow [CoverAspect]). */
private val WideCoverWidth = 104.dp
private val WideCoverHeight = 153.dp

/**
 * Library book card. Narrow cells (2-4 books per row) show a large cover with a single-line
 * title below, like Readest. A full-width cell (1 book per row) shows a list card with the
 * cover on the left and a long title, author and an animated reading-progress bar on the right.
 * Colors come from the app theme, so they follow the user's color settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedAndroidHomeRecentCard(
    bookId: String,
    title: String,
    author: String,
    progressPercent: Int?,
    isAvailable: Boolean,
    isDownloading: Boolean,
    isSelected: Boolean,
    hasCustomCover: Boolean,
    showStatusBadges: Boolean,
    unavailableDescription: String,
    selectedDescription: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    statusBadges: @Composable (Modifier) -> Unit,
    fileTypeBadge: @Composable (compact: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Tapping the title (instead of the cover) runs this; the rest of the card keeps [onClick]. */
    onTitleClick: (() -> Unit)? = null,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(durationMillis = 320), label = "cardAppear")

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "cardPress",
    )

    BoxWithConstraints(
        modifier
            .testTag("HomeRecentFileCard_$bookId")
            .graphicsLayer {
                alpha = appear * (if (isAvailable) 1f else 0.8f)
                translationY = (1f - appear) * 24.dp.toPx()
                scaleX = pressScale
                scaleY = pressScale
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (maxWidth >= WideCardMinWidth) {
            WideBookCard(
                title = title,
                author = author,
                progressPercent = progressPercent,
                isAvailable = isAvailable,
                isDownloading = isDownloading,
                isSelected = isSelected,
                hasCustomCover = hasCustomCover,
                showStatusBadges = showStatusBadges,
                unavailableDescription = unavailableDescription,
                selectedDescription = selectedDescription,
                shown = shown,
                cover = cover,
                statusBadges = statusBadges,
                fileTypeBadge = fileTypeBadge,
                onTitleClick = onTitleClick,
                onTitleLongClick = onLongClick,
            )
        } else {
            GridBookCard(
                title = title,
                author = author,
                progressPercent = progressPercent,
                isAvailable = isAvailable,
                isDownloading = isDownloading,
                isSelected = isSelected,
                hasCustomCover = hasCustomCover,
                showStatusBadges = showStatusBadges,
                unavailableDescription = unavailableDescription,
                selectedDescription = selectedDescription,
                cover = cover,
                statusBadges = statusBadges,
                fileTypeBadge = fileTypeBadge,
                onTitleClick = onTitleClick,
                onTitleLongClick = onLongClick,
            )
        }
    }
}

@Composable
private fun GridBookCard(
    title: String,
    author: String,
    progressPercent: Int?,
    isAvailable: Boolean,
    isDownloading: Boolean,
    isSelected: Boolean,
    hasCustomCover: Boolean,
    showStatusBadges: Boolean,
    unavailableDescription: String,
    selectedDescription: String,
    cover: @Composable (Modifier) -> Unit,
    statusBadges: @Composable (Modifier) -> Unit,
    fileTypeBadge: @Composable (compact: Boolean) -> Unit,
    onTitleClick: (() -> Unit)?,
    onTitleLongClick: () -> Unit,
) {
    val coverShape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CoverAspect)
                .shadow(4.dp, coverShape)
                .clip(coverShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, coverShape) else Modifier),
        ) {
            cover(Modifier.fillMaxSize())
            if (hasCustomCover) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.12f),
                            0.3f to Color.Transparent,
                            0.7f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.35f),
                        ),
                    ),
                )
            }
            if (showStatusBadges) statusBadges(Modifier.align(Alignment.TopStart).padding(6.dp))
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { fileTypeBadge(true) }
            CoverStateOverlays(isAvailable, isDownloading, isSelected, unavailableDescription, selectedDescription)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .titleTapTarget(onTitleClick, onTitleLongClick)
                .padding(horizontal = 2.dp, vertical = 2.dp),
        )
        val secondLine = progressPercent?.let { "$it %" } ?: author.trim()
        if (secondLine.isNotEmpty()) {
            Text(
                secondLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (progressPercent != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (progressPercent != null) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun WideBookCard(
    title: String,
    author: String,
    progressPercent: Int?,
    isAvailable: Boolean,
    isDownloading: Boolean,
    isSelected: Boolean,
    hasCustomCover: Boolean,
    showStatusBadges: Boolean,
    unavailableDescription: String,
    selectedDescription: String,
    shown: Boolean,
    cover: @Composable (Modifier) -> Unit,
    statusBadges: @Composable (Modifier) -> Unit,
    fileTypeBadge: @Composable (compact: Boolean) -> Unit,
    onTitleClick: (() -> Unit)?,
    onTitleLongClick: () -> Unit,
) {
    val coverShape = RoundedCornerShape(10.dp)
    val progress by animateFloatAsState(
        targetValue = if (shown) (progressPercent ?: 0) / 100f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "wideProgress",
    )
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isSelected) 6.dp else 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .width(WideCoverWidth)
                    .height(WideCoverHeight)
                    .clip(coverShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                cover(Modifier.fillMaxSize())
                if (hasCustomCover) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.25f)),
                        ),
                    )
                }
                if (showStatusBadges) statusBadges(Modifier.align(Alignment.TopStart).padding(4.dp))
                CoverStateOverlays(isAvailable, isDownloading, isSelected, unavailableDescription, selectedDescription)
            }
            Column(
                Modifier.weight(1f).height(WideCoverHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.titleTapTarget(onTitleClick, onTitleLongClick),
                    )
                    val authorText = author.trim()
                    if (authorText.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            authorText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (progressPercent != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Text(
                            "$progressPercent %",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    fileTypeBadge(false)
                }
            }
        }
    }
}

/**
 * Makes a card title its own tap target. Long press still selects the book, so the title does not
 * swallow the gesture the card normally handles.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.titleTapTarget(onTitleClick: (() -> Unit)?, onLongClick: () -> Unit): Modifier =
    if (onTitleClick == null) this else this.combinedClickable(onClick = onTitleClick, onLongClick = onLongClick)

@Composable
private fun BoxScope.CoverStateOverlays(
    isAvailable: Boolean,
    isDownloading: Boolean,
    isSelected: Boolean,
    unavailableDescription: String,
    selectedDescription: String,
) {
    if (!isAvailable) {
        Box(
            Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isDownloading) CircularProgressIndicator(color = Color.White)
            else Icon(Icons.Default.Info, unavailableDescription, Modifier.size(40.dp), tint = Color.White)
        }
    }
    if (isSelected) {
        Box(
            Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Check,
                selectedDescription,
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(8.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
