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
 * Counts reading time while a reader screen is resumed, and pages as the reader reports positions.
 * Time stops counting after [IDLE_LIMIT_MS] without any page movement (phone left open on a table).
 */
object ReadingSessionTracker {
    private const val TICK_MS = 10_000L
    private const val IDLE_LIMIT_MS = 10 * 60_000L
    private const val MAX_FORWARD_STEP = 4
    private const val CHARS_PER_PAGE = 1800.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var appContext: Context? = null
    private var tickJob: Job? = null
    private var lastTickMs = 0L
    private var lastActivityMs = 0L
    private var remainderMs = 0L

    private var pageKey: String? = null
    private var pdfHighWater = Int.MIN_VALUE
    private var epubHighWater = -1f
    private var pageCarry = 0.0

    fun start(context: Context) {
        synchronized(lock) {
            if (tickJob?.isActive == true) return
            val app = context.applicationContext
            appContext = app
            ReadingStatsStore.init(app)
            val now = System.currentTimeMillis()
            lastTickMs = now
            lastActivityMs = now
            tickJob = scope.launch {
                while (true) {
                    delay(TICK_MS)
                    synchronized(lock) { tickLocked() }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (tickJob == null) return
            tickLocked()
            tickJob?.cancel()
            tickJob = null
            pageKey = null
        }
    }

    fun onPdfPage(key: String, page: Int) {
        synchronized(lock) {
            if (tickJob == null) return
            touchLocked()
            if (pageKey != key) { pageKey = key; pdfHighWater = page; return }
            val delta = page - pdfHighWater
            when {
                delta in 1..MAX_FORWARD_STEP -> { pdfHighWater = page; addPagesLocked(delta.toDouble()) }
                delta > MAX_FORWARD_STEP -> pdfHighWater = page      // slider jump: navigation, not reading
                delta < -MAX_FORWARD_STEP -> pdfHighWater = page     // deliberate jump back
            }
        }
    }

    /** [progressPercent] is 0..100; pages are estimated from the book's text length. */
    fun onEpubProgress(key: String, progressPercent: Float, totalChars: Int) {
        synchronized(lock) {
            if (tickJob == null) return
            touchLocked()
            val p = progressPercent.coerceIn(0f, 100f)
            if (pageKey != key) { pageKey = key; epubHighWater = p; return }
            val pagesInBook = totalChars / CHARS_PER_PAGE
            val deltaPages = (p - epubHighWater) / 100.0 * pagesInBook
            when {
                deltaPages > 0 && deltaPages <= MAX_FORWARD_STEP * 2 -> { epubHighWater = p; addPagesLocked(deltaPages) }
                deltaPages > MAX_FORWARD_STEP * 2 -> epubHighWater = p
                deltaPages < -MAX_FORWARD_STEP * 2 -> epubHighWater = p
            }
        }
    }

    /**
     * Webbrowser-Ansicht: eine Bildschirmhöhe Scrollen zählt als eine Seite.
     * [scrollY] und [viewportHeight] in Pixeln; [key] ist die aktuelle URL.
     */
    private var webHighWater = 0f

    fun onWebScroll(key: String, scrollY: Int, viewportHeight: Int) {
        if (viewportHeight <= 0) return
        synchronized(lock) {
            if (tickJob == null) return
            touchLocked()
            val screens = scrollY.coerceAtLeast(0) / viewportHeight.toFloat()
            if (pageKey != key) { pageKey = key; webHighWater = screens; return }
            val delta = screens - webHighWater
            when {
                delta > 0f && delta <= MAX_FORWARD_STEP * 2 -> { webHighWater = screens; addPagesLocked(delta.toDouble()) }
                delta > MAX_FORWARD_STEP * 2 -> webHighWater = screens   // Sprung ans Seitenende
                delta < 0f -> webHighWater = screens                     // zurückscrollen zählt nicht
            }
        }
    }

    private fun touchLocked() {
        val now = System.currentTimeMillis()
        // Flush the time up to now first (it may still count as active), then mark activity.
        tickLocked()
        lastActivityMs = now
    }

    private fun addPagesLocked(pages: Double) {
        pageCarry += pages
        val whole = pageCarry.toInt()
        if (whole > 0) {
            pageCarry -= whole
            ReadingStatsStore.addPages(LocalDate.now(), whole)
            evaluateGoalLocked()
        }
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
