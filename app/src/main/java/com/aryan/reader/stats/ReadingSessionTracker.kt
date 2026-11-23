package com.aryan.reader.stats

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aryan.reader.MainActivity
import com.aryan.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Set by the goal notification; AppNavigation consumes it and opens the stats screen. */
object ReadingStatsDeepLink {
    val pending = MutableStateFlow(false)
    fun request() { pending.value = true }
    fun consume() { pending.value = false }
}

/**
 * Counts reading time while a reader screen is resumed, and pages as the reader reports what is
 * on screen. Time stops counting after [IDLE_LIMIT_MS] without any page movement (phone left open
 * on a table). Pages are counted by [PageReadTracker]: a page is read once it stayed on screen
 * long enough, which is independent of how often a reader happens to save its position.
 */
object ReadingSessionTracker {
    private const val TICK_MS = 10_000L
    private const val IDLE_LIMIT_MS = 10 * 60_000L

    /** Paginated EPUB pages are reported directly; progress events in the same window are duplicates. */
    private const val VIEWPORT_PRIORITY_MS = 8_000L

    /** Scroll positions of web pages are counted as one page per screen height; far more than any book. */
    private const val WEB_PAGE_SPACE = 100_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var appContext: Context? = null
    private var tickJob: Job? = null
    private var lastTickMs = 0L
    private var lastActivityMs = 0L
    private var remainderMs = 0L
    private var lastEpubViewportMs = 0L

    private val pageTracker = PageReadTracker(
        ledger = ReadingStatsStore.pageLedger,
        clock = System::currentTimeMillis,
        today = LocalDate::now,
    ) { date, pages ->
        ReadingStatsStore.addPages(date, pages)
        evaluateGoalLocked()
    }

    fun start(context: Context) {
        synchronized(lock) {
            if (tickJob?.isActive == true) return
            val app = context.applicationContext
            appContext = app
            ReadingStatsStore.init(app)
            val now = System.currentTimeMillis()
            lastTickMs = now
            lastActivityMs = now
            pageTracker.resume()
            tickJob = scope.launch {
                while (true) {
                    delay(TICK_MS)
                    synchronized(lock) {
                        tickLocked()
                        pageTracker.checkpoint()
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (tickJob == null) return
            tickLocked()
            pageTracker.pause()
            tickJob?.cancel()
            tickJob = null
        }
    }

    /** PDF/comic reader: [page] is the 0-based page on screen of [totalPages]. */
    fun onPdfPage(key: String, page: Int, totalPages: Int) {
        synchronized(lock) {
            if (tickJob == null) return
            touchLocked()
            pageTracker.onViewport(key, totalPages, page, page)
        }
    }

    /**
     * EPUB reader, any mode: the text between [startFraction] and [endFraction] (0..1 of the whole
     * book) is on screen. A double-page spread simply reports the range of both pages.
     */
    fun onEpubViewport(key: String, startFraction: Double, endFraction: Double, totalChars: Int) {
        if (totalChars <= 0) return
        synchronized(lock) {
            if (tickJob == null) return
            touchLocked()
            lastEpubViewportMs = System.currentTimeMillis()
            val pages = PageReadTracker.virtualPageCount(totalChars)
            val range = PageReadTracker.virtualRange(startFraction, endFraction, pages)
            pageTracker.onViewport(key, pages, range.first, range.last)
        }
    }

    /**
     * Fallback for EPUB modes that only report a progress position ([progressPercent] 0..100):
     * the page that position falls on counts as the visible one.
     */
    fun onEpubProgress(key: String, progressPercent: Float, totalChars: Int) {
        if (totalChars <= 0) return
        synchronized(lock) {
            if (tickJob == null) return
            touchLocked()
            if (System.currentTimeMillis() - lastEpubViewportMs < VIEWPORT_PRIORITY_MS) return
            val pages = PageReadTracker.virtualPageCount(totalChars)
            val fraction = progressPercent.coerceIn(0f, 100f) / 100.0
            val range = PageReadTracker.virtualRange(fraction, fraction, pages)
            pageTracker.onViewport(key, pages, range.first, range.last)
        }
    }

    /** Web view: one screen height of scrolling is one page. [key] is the current URL. */
    fun onWebScroll(key: String, scrollY: Int, viewportHeight: Int) {
        if (viewportHeight <= 0) return
        synchronized(lock) {
            if (tickJob == null) return
            touchLocked()
            val screen = (scrollY.coerceAtLeast(0) / viewportHeight).coerceIn(0, WEB_PAGE_SPACE - 1)
            pageTracker.onViewport(key, WEB_PAGE_SPACE, screen, screen)
        }
    }

    private fun touchLocked() {
        val now = System.currentTimeMillis()
        // Flush the time up to now first (it may still count as active), then mark activity.
        tickLocked()
        lastActivityMs = now
    }

    private fun tickLocked() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTickMs
        lastTickMs = now
        if (elapsed <= 0) return
        val activeUntil = lastActivityMs + IDLE_LIMIT_MS
        val counted = (minOf(now, activeUntil) - (now - elapsed)).coerceIn(0L, elapsed)
        if (counted <= 0) return
        remainderMs += counted
        val secs = remainderMs / 1000
        if (secs > 0) {
            remainderMs -= secs * 1000
            ReadingStatsStore.addSeconds(LocalDate.now(), secs)
            evaluateGoalLocked()
        }
    }

    private fun evaluateGoalLocked() {
        val ctx = appContext ?: return
        val snap = ReadingStatsStore.state.value
        val goal = snap.goal
        if (!goal.isActive) return
        val today = LocalDate.now()
        if (snap.goalResults.containsKey(today)) return   // goal tracker stopped for today
        val day = snap.days[today] ?: return
        val reached = when (goal.type) {
            ReadingGoalType.TIME -> day.seconds >= goal.target * 60L
            ReadingGoalType.PAGES -> day.pages >= goal.target
            ReadingGoalType.NONE -> false
        }
        if (!reached) return
        val result = ReadingGoalResult(today, goal, day.seconds, day.pages)
        if (ReadingStatsStore.markGoalReached(result) && snap.goalNotificationsEnabled) {
            ReadingGoalNotifier.notifyReached(ctx, result)
        }
    }
}

/** Starts/stops the session with the screen's lifecycle (background, screen off, leaving the reader). */
@Composable
fun ReadingSessionEffect() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> ReadingSessionTracker.start(context)
                Lifecycle.Event.ON_PAUSE -> ReadingSessionTracker.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            ReadingSessionTracker.stop()
        }
    }
}

object ReadingGoalNotifier {
    const val ACTION_OPEN_STATS = "com.aryan.reader.action.OPEN_READING_STATS"
    private const val CHANNEL_ID = "reading_goal"
    private const val NOTIFICATION_ID = 7421

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun notifyReached(context: Context, result: ReadingGoalResult) {
        val title = context.getString(R.string.stats_notification_title)
        val goalText = if (result.goal.type == ReadingGoalType.PAGES) {
            context.getString(R.string.stats_goal_pages_value, result.goal.target)
        } else {
            formatDuration(context, result.goal.target * 60L)
        }
        val text = context.getString(
            R.string.stats_notification_text, goalText, formatDuration(context, result.secondsAtReach),
        )
        if (!canNotify(context)) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, "$title $text", Toast.LENGTH_LONG).show() }
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.stats_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_STATS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }
}

fun formatDuration(context: Context, seconds: Long): String {
    val totalMin = (seconds + 30) / 60
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> context.getString(R.string.stats_duration_h_min, h, m)
        h > 0 -> context.getString(R.string.stats_duration_h, h)
        seconds < 60 && seconds > 0 -> context.getString(R.string.stats_duration_sec, seconds)
        else -> context.getString(R.string.stats_duration_min, m)
    }
}
