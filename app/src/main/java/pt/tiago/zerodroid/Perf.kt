package pt.tiago.zerodroid

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/**
 * On-device performance probe, read over SSH (no adb here). Enabled in debuggable and `perf` builds.
 * Records startup marks, timed spans, RPC latencies, per-screen frame metrics, main-thread stalls
 * (with sampled stacks) and recomposition counts, and writes a report on every onStop.
 */
object Perf {
    @Volatile var enabled = false
        private set

    // Lazy: JVM unit tests touch Perf (via RpcClient) but have no android.os.Process.
    private val bootUptime by lazy { Process.getStartUptimeMillis() }
    private val marks = ConcurrentHashMap<String, Long>()
    private val markOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val spans = ConcurrentHashMap<String, MutableList<Double>>()
    private val recompositions = ConcurrentHashMap<String, AtomicInteger>()
    private val stalls = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val frames = ConcurrentHashMap<String, FrameStats>()
    private val navs = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @Volatile var screen: String = "boot"
    @Volatile private var pendingNav: Pair<String, Long>? = null
    @Volatile private var frameBudgetNs = 16_666_667L
    private var thread: HandlerThread? = null

    fun init(context: Context) {
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        enabled = debuggable || BuildConfig.PERF
        if (!enabled) return
        mark("app.onCreate")
        thread = HandlerThread("perf").also { it.start() }
        watchMainLooper()
    }

    /** First occurrence only: ms since process start. */
    fun mark(name: String) {
        if (!enabled) return
        if (marks.putIfAbsent(name, SystemClock.uptimeMillis() - bootUptime) == null) markOrder.add(name)
    }

    fun record(name: String, ms: Double) {
        if (!enabled) return
        val list = spans.getOrPut(name) { java.util.Collections.synchronizedList(mutableListOf()) }
        if (list.size < MAX_SAMPLES) list.add(ms)
    }

    inline fun <T> span(name: String, block: () -> T): T {
        if (!enabled) return block()
        val t = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, (System.nanoTime() - t) / 1e6)
        }
    }

    fun recomposed(name: String) {
        recompositions.getOrPut(name) { AtomicInteger() }.incrementAndGet()
    }

    /** A tap that changes screen: the next frames measure how long until it is on screen. */
    fun navigate(to: String) {
        if (!enabled) return
        pendingNav = to to System.nanoTime()
        screen = to
    }

    // ── Frames ─────────────────────────────────────────────────────────────

    /** Fixed-size histogram (0.25 ms buckets up to 250 ms): constant memory however long the app runs. */
    private class FrameStats {
        val buckets = IntArray(BUCKETS + 1)
        var count = 0
        var sumMs = 0.0
        var maxMs = 0f
        var janky = 0
        var severe = 0
        val phaseSums = LongArray(PHASES.size)
    }

    private val PHASES = listOf(
        "input" to FrameMetrics.INPUT_HANDLING_DURATION,
        "anim" to FrameMetrics.ANIMATION_DURATION,
        "measure/layout" to FrameMetrics.LAYOUT_MEASURE_DURATION,
        "draw" to FrameMetrics.DRAW_DURATION,
        "sync" to FrameMetrics.SYNC_DURATION,
        "gpu-cmd" to FrameMetrics.COMMAND_ISSUE_DURATION,
        "swap" to FrameMetrics.SWAP_BUFFERS_DURATION,
    )

    fun attach(activity: Activity) {
        if (!enabled) return
        val handler = Handler(thread!!.looper)
        @Suppress("DEPRECATION")
        val hz = activity.windowManager.defaultDisplay.refreshRate
        frameBudgetNs = (1e9 / hz).roundToLong()
        record("display.hz", hz.toDouble())
        activity.window.addOnFrameMetricsAvailableListener({ _, fm, _ ->
            val total = fm.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= 31) fm.getMetric(FrameMetrics.DEADLINE) else frameBudgetNs
            if (fm.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
                record("frame.first.ms", total / 1e6)
            }
            val s = frames.getOrPut(screen) { FrameStats() }
            synchronized(s) {
                val ms = (total / 1e6).toFloat()
                s.buckets[(ms / BUCKET_MS).toInt().coerceIn(0, BUCKETS)]++
                s.count++
                s.sumMs += ms
                if (ms > s.maxMs) s.maxMs = ms
                if (total > deadline) s.janky++
                if (total > 4 * frameBudgetNs) s.severe++
                PHASES.forEachIndexed { i, (_, id) -> s.phaseSums[i] += fm.getMetric(id) }
            }
            pendingNav?.let { (to, tapNs) ->
                val vsync = fm.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                if (vsync >= tapNs) {
                    pendingNav = null
                    if (navLines.incrementAndGet() <= MAX_LINES) navs.add("%-10s tap→frame %6.1f ms  (that frame %5.1f ms)".format(Locale.US, to, (vsync + total - tapNs) / 1e6, total / 1e6))
                }
            }
        }, handler)
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                mark("activity.firstDraw")
            }
        })
    }

    // ── Main-thread stalls ─────────────────────────────────────────────────

    @Volatile private var dispatchStart = 0L
    @Volatile private var dispatchWhat: String? = null

    private fun watchMainLooper() {
        val main = Looper.getMainLooper()
        val h = Handler(thread!!.looper)
        // Watchdog: sample the main thread's stack once if a dispatch is still running after SAMPLE_AT_MS.
        val sampler = Runnable {
            val start = dispatchStart
            if (start != 0L) {
                val st = main.thread.stackTrace
                val ours = st.filter { it.className.startsWith("pt.tiago") }.take(6)
                addStall(
                    "      sample @${SystemClock.uptimeMillis() - start}ms [${screen}]\n" +
                        ours.ifEmpty { st.take(8) }.joinToString("\n") { "        at $it" },
                )
            }
        }
        main.setMessageLogging { line ->
            if (line.startsWith(">>>>>")) {
                dispatchWhat = line
                dispatchStart = SystemClock.uptimeMillis()
                h.postDelayed(sampler, SAMPLE_AT_MS)
            } else if (line.startsWith("<<<<<")) {
                h.removeCallbacks(sampler)
                val took = SystemClock.uptimeMillis() - dispatchStart
                dispatchStart = 0
                if (took >= STALL_MS) {
                    addStall("%5d ms [%s] %s".format(Locale.US, took, screen, dispatchWhat?.substringAfter("} ")?.take(140)))
                }
            }
        }
    }

    // ── Report ─────────────────────────────────────────────────────────────

    /** Upper edge of the bucket holding the p-th frame. */
    private fun pct(s: FrameStats, p: Double): Float {
        if (s.count == 0) return 0f
        val target = ((s.count - 1) * p).toLong() + 1
        var seen = 0L
        for (i in s.buckets.indices) {
            seen += s.buckets[i]
            if (seen >= target) return if (i == BUCKETS) s.maxMs else ((i + 1) * BUCKET_MS).coerceAtMost(s.maxMs)
        }
        return s.maxMs
    }

    fun report(context: Context): String = buildString {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("ZeroDroid perf report  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("build: ${pi.versionName} (${pi.longVersionCode})  type=${BuildConfig.BUILD_TYPE}  debuggable=${context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  frame budget ${"%.2f".format(Locale.US, frameBudgetNs / 1e6)} ms")
        appendLine("process up: ${SystemClock.uptimeMillis() - bootUptime} ms")
        appendLine()
        appendLine("== Startup marks (ms since process start) ==")
        markOrder.forEach { appendLine("  %7d  %s".format(Locale.US, marks[it], it)) }
        appendLine()
        appendLine("== Spans (ms): n  min  p50  p90  max ==")
        spans.toSortedMap().forEach { (k, v) ->
            val s = synchronized(v) { v.sorted() }
            if (s.isEmpty()) return@forEach
            appendLine("  %-34s %4d %8.1f %8.1f %8.1f %8.1f".format(Locale.US, k, s.size, s.first(), s[(s.size - 1) / 2], s[((s.size - 1) * 0.9).toInt()], s.last()))
        }
        appendLine()
        appendLine("== Frames per screen (ms) ==")
        appendLine("  %-10s %6s %6s %6s %6s %6s %7s %7s %7s".format(Locale.US, "screen", "n", "p50", "p90", "p99", "max", "jank%", ">4x", "busy-s"))
        frames.toSortedMap().forEach { (k, s) ->
            synchronized(s) {
                appendLine(
                    "  %-10s %6d %6.1f %6.1f %6.1f %6.1f %6.1f%% %7d %7.1f".format(
                        Locale.US, k, s.count, pct(s, 0.5), pct(s, 0.9), pct(s, 0.99), s.maxMs,
                        if (s.count == 0) 0.0 else 100.0 * s.janky / s.count, s.severe, s.sumMs / 1000.0,
                    ),
                )
                val n = s.count.coerceAtLeast(1)
                appendLine("             avg phases: " + PHASES.indices.joinToString("  ") { i -> "${PHASES[i].first} %.2f".format(Locale.US, s.phaseSums[i] / 1e6 / n) })
            }
        }
        appendLine()
        appendLine("== Navigation (tap → first frame of the new screen) ==")
        navs.forEach { appendLine("  $it") }
        appendLine()
        appendLine("== Recompositions ==")
        recompositions.toSortedMap().forEach { (k, v) -> appendLine("  %-30s %8d".format(Locale.US, k, v.get())) }
        appendLine()
        appendLine("== Main-thread messages ≥ ${STALL_MS} ms (and sampled stacks ≥ ${SAMPLE_AT_MS} ms) ==")
        stalls.forEach { appendLine("  $it") }
        appendLine()
        val rt = Runtime.getRuntime()
        appendLine("== Memory ==")
        appendLine("  java heap used ${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024} MB / max ${rt.maxMemory() / 1024 / 1024} MB")
        appendLine("  native heap ${Debug.getNativeHeapAllocatedSize() / 1024 / 1024} MB   PSS ${Debug.getPss() / 1024} MB")
    }

    /** Writes the report to Download/crashlogs/ (readable over SSH). Off the main thread. */
    fun dump(context: Context) {
        if (!enabled) return
        val app = context.applicationContext
        Handler(thread!!.looper).post {
            runCatching {
                val text = report(app)
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "${app.packageName}-perf-${BuildConfig.BUILD_TYPE}-$stamp.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/crashlogs")
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
                app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }

    private const val MAX_SAMPLES = 2_000
    private const val BUCKET_MS = 0.25f
    private const val BUCKETS = 1000
    private const val MAX_LINES = 400
    private val stallLines = AtomicInteger()
    private val navLines = AtomicInteger()

    private fun addStall(line: String) {
        if (stallLines.incrementAndGet() <= MAX_LINES) stalls.add(line)
    }

    private const val STALL_MS = 32L
    private const val SAMPLE_AT_MS = 40L
}

/** Counts successful (re)compositions of the calling scope. No-op when the probe is off. */
@Composable
fun RecomposeProbe(name: String) {
    if (Perf.enabled) SideEffect { Perf.recomposed(name) }
}
