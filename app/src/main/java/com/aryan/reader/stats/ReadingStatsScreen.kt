package com.aryan.reader.stats

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aryan.reader.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Simple bar-chart glyph; the app's trimmed icon set has none. */
val ReadingStatsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ReadingStats",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M4,12h4v8H4z M10,4h4v16h-4z M16,9h4v11h-4z").toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(onBackClick: () -> Unit) {
    ReadingStatsStore.init(LocalContext.current)
    val snapshot by ReadingStatsStore.state.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    var metric by remember { mutableStateOf(StatsMetric.TIME) }
    var offset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var showGoalDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.stats_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val window = remember(snapshot, period, offset) { buildWindow(snapshot, period, offset, today) }
            val sel = selected?.let { window.bars.getOrNull(it) }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val items = listOf(
                            StatsPeriod.WEEK to R.string.stats_week,
                            StatsPeriod.MONTH to R.string.stats_month,
                            StatsPeriod.YEAR to R.string.stats_year,
                        )
                        items.forEachIndexed { i, (p, label) ->
                            SegmentedButton(
                                selected = period == p,
                                onClick = { period = p; offset = 0; selected = null },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
                            ) { Text(stringResource(label)) }
                        }
                    }

                    // Top-left info: today + average, or the tapped bar + its date.
                    val avg = window.averagePerDay(metric)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (sel != null) {
                            InfoBlock(
                                label = barLabel(sel, period, locale),
                                value = barValue(context, sel.seconds, sel.pages, metric),
                            )
                            InfoBlock(
                                label = stringResource(R.string.stats_date),
                                value = barDate(sel, period, locale),
                            )
                        } else {
                            val first = if (offset == 0 && period != StatsPeriod.YEAR) {
                                val t = snapshot.days[today] ?: ReadingDayStat()
                                stringResource(R.string.stats_today) to barValue(context, t.seconds, t.pages, metric)
                            } else {
                                val tot = window.total(metric)
                                stringResource(R.string.stats_total) to metricText(context, metric, tot)
                            }
                            InfoBlock(first.first, first.second)
                            InfoBlock(stringResource(R.string.stats_avg_per_day), metricText(context, metric, avg))
                        }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { offset -= 1; selected = null }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null)
                        }
                        Text(
                            periodLabel(window, period, locale),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        IconButton(onClick = { offset += 1; selected = null }, enabled = offset < 0) {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }

                    val goalLine: Double? = snapshot.goal.takeIf { it.isActive && period != StatsPeriod.YEAR }?.let {
                        when {
                            it.type == ReadingGoalType.TIME && metric == StatsMetric.TIME -> it.target.toDouble()
                            it.type == ReadingGoalType.PAGES && metric == StatsMetric.PAGES -> it.target.toDouble()
                            else -> null
                        }
                    }
                    BarChart(
                        bars = window.bars,
                        metric = metric,
                        period = period,
                        selected = selected,
                        goalLine = goalLine,
                        locale = locale,
                        onSelect = { selected = it },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = metric == StatsMetric.TIME,
                            onClick = { metric = StatsMetric.TIME },
                            label = { Text(stringResource(R.string.stats_metric_time)) },
                        )
                        FilterChip(
                            selected = metric == StatsMetric.PAGES,
                            onClick = { metric = StatsMetric.PAGES },
                            label = { Text(stringResource(R.string.stats_metric_pages)) },
                        )
                    }
                }
            }

            GoalCard(snapshot, today, onEdit = { showGoalDialog = true })
            StatsHeroRow(snapshot, today, locale)
            PagesAndPaceCard(snapshot, today)
            MilestonesCard(snapshot, today, locale)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showGoalDialog) {
        GoalDialog(
            initial = snapshot.goal,
            notificationsEnabled = snapshot.goalNotificationsEnabled,
            onDismiss = { showGoalDialog = false },
            onSave = { goal, notify ->
                ReadingStatsStore.setGoal(goal)
                ReadingStatsStore.setNotificationsEnabled(notify)
                showGoalDialog = false
            },
        )
    }
}

@Composable
private fun InfoBlock(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun BarChart(
    bars: List<StatsBar>,
    metric: StatsMetric,
    period: StatsPeriod,
    selected: Int?,
    goalLine: Double?,
    locale: Locale,
    onSelect: (Int?) -> Unit,
) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val barIdle = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val maxValue = maxOf(bars.maxOfOrNull { if (it.isFuture) 0.0 else it.value(metric) } ?: 0.0, goalLine ?: 0.0)
    val axisMax = niceAxisMax(maxValue, metric)

    Column {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(bars.size) {
                        detectTapGestures { pos ->
                            if (bars.isEmpty()) return@detectTapGestures
                            val idx = (pos.x / (size.width / bars.size)).toInt().coerceIn(0, bars.size - 1)
                            if (bars[idx].isFuture) return@detectTapGestures
                            currentOnSelect(if (currentSelected == idx) null else idx)
                        }
                    },
            ) {
                val n = bars.size.coerceAtLeast(1)
                val slot = size.width / n
                val barWidth = slot * if (n > 12) 0.7f else 0.6f
                val corner = (barWidth / 3f).coerceAtMost(8.dp.toPx())
                val minBar = 2.dp.toPx()
                listOf(0f, 0.5f, 1f).forEach { f ->
                    val y = size.height * (1f - f)
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                bars.forEachIndexed { i, b ->
                    if (b.isFuture) return@forEachIndexed
                    val v = b.value(metric)
                    val h = if (v <= 0.0) minBar else maxOf(minBar, (v / axisMax * size.height).toFloat())
                    val color = when {
                        v <= 0.0 -> empty
                        selected == i -> primary
                        selected != null -> barIdle
                        else -> primary
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                }
                if (goalLine != null && goalLine > 0) {
                    val y = size.height * (1f - (goalLine / axisMax).toFloat().coerceIn(0f, 1f))
                    drawLine(
                        goalColor, Offset(0f, y), Offset(size.width, y),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                    )
                }
            }
            Text(
                metricText(context, metric, axisMax, short = true),
                modifier = Modifier.align(Alignment.TopEnd),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                metricText(context, metric, axisMax / 2, short = true),
                modifier = Modifier.align(Alignment.CenterEnd),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            bars.forEach { b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val text = when (period) {
                        StatsPeriod.WEEK -> b.start.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                        // Month: only Mondays carry their date number.
                        StatsPeriod.MONTH -> if (b.start.dayOfWeek == DayOfWeek.MONDAY) b.start.dayOfMonth.toString() else ""
                        StatsPeriod.YEAR -> b.start.month.getDisplayName(TextStyle.SHORT, locale)
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = if (period == StatsPeriod.WEEK) 11.sp else 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalCard(snapshot: ReadingStatsSnapshot, today: LocalDate, onEdit: () -> Unit) {
    val context = LocalContext.current
    val goal = snapshot.goal
    val result = snapshot.goalResults[today]
    val day = snapshot.days[today] ?: ReadingDayStat()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.stats_goal_title), style = MaterialTheme.typography.titleMedium)
            when {
                !goal.isActive -> {
                    Text(stringResource(R.string.stats_goal_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onEdit) { Text(stringResource(R.string.stats_goal_set)) }
                }
                result != null -> {
                    Text(stringResource(R.string.stats_goal_reached), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    val summary = if (result.goal.type == ReadingGoalType.PAGES) {
                        stringResource(R.string.stats_goal_reached_pages, result.goal.target, formatDuration(context, result.secondsAtReach))
                    } else {
                        stringResource(R.string.stats_goal_reached_time, formatDuration(context, result.goal.target * 60L), result.pagesAtReach)
                    }
                    Text(summary)
                    val pace = historicPace(snapshot, today)
                    if (pace == null) {
                        Text(stringResource(R.string.stats_goal_no_history), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (result.goal.type == ReadingGoalType.PAGES) {
                        Text(
                            stringResource(
                                R.string.stats_goal_before_pages,
                                result.goal.target,
                                formatDuration(context, (pace.avgSecondsPerPage * result.goal.target).toLong()),
                                formatDuration(context, (pace.bestSecondsPerPage * result.goal.target).toLong()),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val pagesBefore = (result.goal.target * 60.0 / pace.avgSecondsPerPage).toInt()
                        Text(
                            stringResource(R.string.stats_goal_before_time, formatDuration(context, result.goal.target * 60L), pagesBefore),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.stats_goal_today_total, formatDuration(context, day.seconds), day.pages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.stats_goal_change)) }
                }
                else -> {
                    val current = if (goal.type == ReadingGoalType.PAGES) day.pages.toDouble() else day.seconds / 60.0
                    val fraction = (current / goal.target).toFloat().coerceIn(0f, 1f)
                    val text = if (goal.type == ReadingGoalType.PAGES) {
                        stringResource(R.string.stats_goal_progress_pages, day.pages, goal.target)
                    } else {
                        stringResource(R.string.stats_goal_progress_time, formatDuration(context, day.seconds), formatDuration(context, goal.target * 60L))
                    }
                    Text(text)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.stats_goal_change)) }
                }
            }
        }
    }
}

@Composable
private fun GoalDialog(
    initial: ReadingGoal,
    notificationsEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (ReadingGoal, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(initial.type) }
    var text by remember { mutableStateOf(if (initial.isActive) initial.target.toString() else "") }
    var notify by remember { mutableStateOf(notificationsEnabled) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_goal_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == ReadingGoalType.TIME, { type = ReadingGoalType.TIME }, { Text(stringResource(R.string.stats_metric_time)) })
                    FilterChip(type == ReadingGoalType.PAGES, { type = ReadingGoalType.PAGES }, { Text(stringResource(R.string.stats_metric_pages)) })
                    FilterChip(type == ReadingGoalType.NONE, { type = ReadingGoalType.NONE }, { Text(stringResource(R.string.stats_goal_off)) })
                }
                if (type != ReadingGoalType.NONE) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = {
                            Text(stringResource(if (type == ReadingGoalType.TIME) R.string.stats_goal_minutes else R.string.stats_goal_pages))
                        },
                    )
                    val presets = if (type == ReadingGoalType.TIME) listOf(15, 30, 60, 120) else listOf(10, 25, 50, 100)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { p ->
                            FilterChip(text == p.toString(), { text = p.toString() }, { Text(p.toString()) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stats_goal_notify), modifier = Modifier.weight(1f))
                        Switch(checked = notify, onCheckedChange = { notify = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = type == ReadingGoalType.NONE || (text.toIntOrNull() ?: 0) > 0,
                onClick = {
                    val target = text.toIntOrNull() ?: 0
                    if (type != ReadingGoalType.NONE && notify && Build.VERSION.SDK_INT >= 33 && !ReadingGoalNotifier.canNotify(context)) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    onSave(if (type == ReadingGoalType.NONE) ReadingGoal() else ReadingGoal(type, target), notify)
                },
            ) { Text(stringResource(R.string.stats_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.stats_cancel)) } },
    )
}

private fun metricText(context: android.content.Context, metric: StatsMetric, value: Double, short: Boolean = false): String =
    if (metric == StatsMetric.TIME) {
        formatDuration(context, (value * 60).toLong())
    } else if (short || value >= 10 || value == Math.floor(value)) {
        context.getString(R.string.stats_goal_pages_value, Math.round(value).toInt())
    } else {
        context.getString(R.string.stats_pages_decimal, value)
    }

private fun barValue(context: android.content.Context, seconds: Long, pages: Int, metric: StatsMetric): String =
    if (metric == StatsMetric.TIME) formatDuration(context, seconds) else context.getString(R.string.stats_goal_pages_value, pages)

private fun barLabel(b: StatsBar, period: StatsPeriod, locale: Locale): String = when (period) {
    StatsPeriod.WEEK, StatsPeriod.MONTH -> b.start.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    StatsPeriod.YEAR -> b.start.month.getDisplayName(TextStyle.FULL, locale)
}

private fun barDate(b: StatsBar, period: StatsPeriod, locale: Locale): String = when (period) {
    StatsPeriod.YEAR -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(b.start)
    else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(b.start)
}

private fun periodLabel(w: StatsWindow, period: StatsPeriod, locale: Locale): String = when (period) {
    StatsPeriod.WEEK -> {
        val f = DateTimeFormatter.ofPattern("d MMM", locale)
        "${f.format(w.first)} – ${f.format(w.last)}"
    }
    StatsPeriod.MONTH -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(w.first)
    StatsPeriod.YEAR -> w.first.year.toString()
}
