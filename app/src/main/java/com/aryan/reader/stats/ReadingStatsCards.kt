package com.aryan.reader.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aryan.reader.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The cards under the daily goal: two highlight tiles (streak, reading speed), pages and pace for
 * today / this week / all time, and a grid of milestones.
 */
@Composable
internal fun StatsHeroRow(snapshot: ReadingStatsSnapshot, today: LocalDate, locale: Locale) {
    val records = remember(snapshot, today) { computeRecords(snapshot, today) }
    val pace = remember(snapshot) { readingSpeedPagesPerHour(snapshot) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroStatCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeightInRow(),
            title = stringResource(R.string.stats_streak),
            value = stringResource(R.string.stats_days_value, records.currentStreak),
            subtitle = stringResource(R.string.stats_streak_record, records.longestStreak),
            icon = StatsIcons.Flame,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        HeroStatCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeightInRow(),
            title = stringResource(R.string.stats_speed),
            value = pace?.let { String.format(locale, "%.1f", it) } ?: "–",
            subtitle = stringResource(R.string.stats_pages_per_hour_unit),
            icon = StatsIcons.Speed,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Makes the tiles of one row equally tall (the row is measured with IntrinsicSize.Max). */
private fun Modifier.fillMaxHeightInRow(): Modifier = this.fillMaxHeight()

@Composable
internal fun PagesAndPaceCard(snapshot: ReadingStatsSnapshot, today: LocalDate) {
    val context = LocalContext.current
    val day = snapshot.days[today] ?: ReadingDayStat()
    val week = remember(snapshot, today) { buildWindow(snapshot, StatsPeriod.WEEK, 0, today) }
    val weekPages = week.bars.sumOf { it.pages }
    val weekSeconds = week.bars.sumOf { it.seconds }
    val allPages = snapshot.days.values.sumOf { it.pages }
    val allSeconds = snapshot.days.values.sumOf { it.seconds }

    StatsSectionCard(title = stringResource(R.string.stats_pages_title)) {
        StatProgressRow(
            label = stringResource(R.string.stats_today),
            primaryValue = stringResource(R.string.stats_goal_pages_value, day.pages),
            secondaryValue = paceDetail(context, day.pages, day.seconds),
            icon = StatsIcons.Today,
        )
        SectionDivider()
        StatProgressRow(
            label = stringResource(R.string.stats_week),
            primaryValue = stringResource(R.string.stats_goal_pages_value, weekPages),
            secondaryValue = paceDetail(context, weekPages, weekSeconds),
            icon = StatsIcons.Week,
        )
        SectionDivider()
        StatProgressRow(
            label = stringResource(R.string.stats_all_time),
            primaryValue = stringResource(R.string.stats_goal_pages_value, allPages),
            secondaryValue = paceDetail(context, allPages, allSeconds),
            icon = Icons.Default.Book,
        )
    }
}

@Composable
internal fun MilestonesCard(snapshot: ReadingStatsSnapshot, today: LocalDate, locale: Locale) {
    val context = LocalContext.current
    val records = remember(snapshot, today) { computeRecords(snapshot, today) }
    val bestDay = records.bestDay
    val bestDate = bestDay?.first?.let {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(it)
    }
    val quote = if (records.daysRead > 0) {
        stringResource(R.string.stats_goal_quote, Math.round(records.goalDays * 100f / records.daysRead))
    } else {
        ""
    }

    StatsSectionCard(title = stringResource(R.string.stats_milestones_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiniMetricBox(
                    modifier = Modifier.weight(1f).fillMaxHeightInRow(),
                    label = stringResource(R.string.stats_best_day),
                    value = bestDay?.second?.let { formatDuration(context, it.seconds) } ?: "–",
                    subtext = bestDate ?: "",
                    icon = StatsIcons.Trophy,
                )
                MiniMetricBox(
                    modifier = Modifier.weight(1f).fillMaxHeightInRow(),
                    label = stringResource(R.string.stats_total_time_short),
                    value = formatDuration(context, records.totalSeconds),
                    subtext = stringResource(R.string.stats_total_time_sub),
                    icon = StatsIcons.Clock,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiniMetricBox(
                    modifier = Modifier.weight(1f).fillMaxHeightInRow(),
                    label = stringResource(R.string.stats_days_read),
                    value = stringResource(R.string.stats_days_value, records.daysRead),
                    subtext = stringResource(R.string.stats_days_read_sub),
                    icon = StatsIcons.Week,
                )
                MiniMetricBox(
                    modifier = Modifier.weight(1f).fillMaxHeightInRow(),
                    label = stringResource(R.string.stats_goal_days),
                    value = stringResource(R.string.stats_days_value, records.goalDays),
                    subtext = quote,
                    icon = StatsIcons.CheckCircle,
                )
            }
        }
    }
}

@Composable
private fun HeroStatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun StatsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            content()
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun StatProgressRow(label: String, primaryValue: String, secondaryValue: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = secondaryValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = primaryValue,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MiniMetricBox(label: String, value: String, subtext: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

/** "30 min • 2 min / page", or just the time when no page was counted. */
private fun paceDetail(context: android.content.Context, pages: Int, seconds: Long): String {
    val time = formatDuration(context, seconds)
    if (pages <= 0 || seconds <= 0) return time
    val perPage = formatDuration(context, Math.round(seconds.toDouble() / pages))
    return context.getString(R.string.stats_pace_detail, time, perPage)
}

/**
 * Pages per hour over the days that actually have pages. Time on days without any counted page
 * (reading a PDF that never changed page, for example) would only drag the speed towards zero.
 * Needs a minimum sample so the first few pages do not show a wild number.
 */
internal fun readingSpeedPagesPerHour(snapshot: ReadingStatsSnapshot): Double? {
    val withPages = snapshot.days.values.filter { it.pages > 0 && it.seconds > 0 }
    val pages = withPages.sumOf { it.pages }
    val seconds = withPages.sumOf { it.seconds }
    if (pages < 5 || seconds < 60) return null
    return pages * 3600.0 / seconds
}

/** Icons the project's trimmed-down icon set does not ship, drawn from Material path data. */
internal object StatsIcons {
    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()

    val Flame: ImageVector by lazy {
        icon("Flame", "M12,2c0,0 -6,6.5 -6,12a6,6 0,0 0,12 0c0,-2.2 -1,-4.2 -2.4,-5.6C15.4,10 14,11 13,11c0,-3 -0.5,-6 -1,-9z")
    }
    val Speed: ImageVector by lazy {
        icon(
            "Speed",
            "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z",
        )
    }
    val Today: ImageVector by lazy {
        icon(
            "Today",
            "M19,3h-1V1h-2v2H8V1H6v2H5C3.89,3 3.01,3.9 3.01,5L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM19,19H5V8h14V19zM7,10h5v5H7z",
        )
    }
    val Week: ImageVector by lazy {
        icon(
            "Week",
            "M19,3h-1V1h-2v2H8V1H6v2H5C3.89,3 3.01,3.9 3.01,5L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM19,19H5V8h14V19z",
        )
    }
    val Trophy: ImageVector by lazy {
        icon(
            "Trophy",
            "M19,5h-2V3H7v2H5C3.9,5 3,5.9 3,7v1c0,2.55 1.92,4.63 4.39,4.94 0.63,1.5 1.98,2.63 3.61,2.96V19H7v2h10v-2h-4v-3.1c1.63,-0.33 2.98,-1.46 3.61,-2.96C19.08,12.63 21,10.55 21,8V7c0,-1.1 -0.9,-2 -2,-2zM5,8V7h2v3.82C5.84,10.4 5,9.3 5,8zM19,8c0,1.3 -0.84,2.4 -2,2.82V7h2v1z",
        )
    }
    val Clock: ImageVector by lazy {
        icon(
            "Clock",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
        )
    }
    val CheckCircle: ImageVector by lazy {
        icon(
            "CheckCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z",
        )
    }
}
