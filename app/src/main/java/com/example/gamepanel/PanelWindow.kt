package com.example.gamepanel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.abs

enum class PanelTab(@androidx.annotation.DrawableRes val icon: Int, val label: String) {
    TOOLS(com.example.R.drawable.ic_gamepad, "Alat Game"),
    PERF(com.example.R.drawable.ic_trend, "Kinerja"),
    NET(com.example.R.drawable.ic_globe, "Jaringan"),
    TOUCH(com.example.R.drawable.ic_touch, "Sentuhan"),
    GAMES(com.example.R.drawable.ic_grid, "Game"),
    SETTINGS(com.example.R.drawable.ic_gear, "Setelan")
}

/** Layout yang menutup panel bila pengguna menggeser ke kanan (kebalikan dari gestur buka). */
private class SwipeCloseLayout(ctx: Context, private val onClose: () -> Unit) : FrameLayout(ctx) {
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; tracking = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = abs(e.rawY - downY)
                if (dx > slop * 4 && dx > dy * 1.6f) { tracking = true; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_MOVE && tracking) {
            if (e.rawX - downX > context.dp(90)) { tracking = false; onClose() }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) tracking = false
        return tracking
    }
}

/**
 * Panel Game Booster (satu jendela overlay). Dibuat saat gestur terdeteksi, dilepas total saat ditutup,
 * jadi tidak ada beban ketika panel tertutup.
 */
class PanelWindow(private val ctx: Context) {

    companion object { private const val TAG = "PanelWindow" }

    private val wm = windowManager(ctx)
    private var wrapper: FrameLayout? = null
    private var card: SwipeCloseLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var panelWidth = 0
    private var animating = false

    private lateinit var contentHost: FrameLayout
    private lateinit var headerMetrics: TextView
    private val railItems = ArrayList<Pair<PanelTab, LinearLayout>>()
    private var currentTab = PanelTab.TOOLS
    private var toolsRefresher: (() -> Unit)? = null

    /** Coroutine scope untuk pekerjaan panel (tes jaringan dsb.). Dibatalkan saat panel dilepas. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Pembaruan halaman aktif dari snapshot monitor. */
    var pageUpdater: ((PerfSnapshot) -> Unit)? = null
    private val pages = PanelPages(ctx, this)

    private var typingEnabled = false

    // ────────────────────────────────────────────────────────────────
    // Tampil / tutup
    // ────────────────────────────────────────────────────────────────

    fun show(): Boolean {
        val size = realScreenSize(ctx)
        val landscape = size.x > size.y
        val pct = PanelSettings.panelSizePercent(ctx)
        val fraction = if (landscape) pct / 100f else minOf(0.96f, (pct + 30) / 100f)
        panelWidth = (size.x * fraction).toInt().coerceAtLeast(ctx.dp(300))
        val margin = ctx.dp(10)

        val wrap = FrameLayout(ctx)
        wrap.setPadding(margin, margin, margin, margin)
        wrap.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { GamePanelController.closePanel(); true } else false
        }

        val accent = Pal.accent(PanelSettings.theme(ctx))
        val alpha = (PanelSettings.panelOpacity(ctx) * 255 / 100).coerceIn(90, 255)
        val c = SwipeCloseLayout(ctx) { GamePanelController.closePanel() }
        c.background = rounded(Color.argb(alpha, 15, 19, 24), ctx.dpf(18f), (accent and 0x00FFFFFF) or 0x55000000, ctx.dp(1))
        c.elevation = ctx.dpf(16f)
        c.clipToOutline = true

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(buildRail(accent), LinearLayout.LayoutParams(ctx.dp(84), ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(View(ctx).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(buildContent(accent), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        c.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wrap.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val params = WindowManager.LayoutParams(
            panelWidth + margin * 2, ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType(), flags, PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.END or Gravity.TOP
        // Blur latar (Android 12+, hanya jika perangkat mengizinkan)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (wm.isCrossWindowBlurEnabled) {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    params.blurBehindRadius = ctx.dp(18)
                }
            } catch (e: Exception) { /* tanpa blur */ }
        }
        lp = params

        wrap.translationX = (panelWidth + margin * 2).toFloat()
        if (!safeAdd(ctx, wrap, params)) { scope.cancel(); return false }
        wrapper = wrap
        card = c

        selectTab(PanelTab.TOOLS)
        PanelMonitor.snapshot.value?.let { onSnapshot(it) }

        wrap.animate()
            .translationX(0f)
            .setDuration(PanelSettings.animDurationMs(ctx))
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
        return true
    }

    fun dismiss(onEnd: () -> Unit = {}) {
        val w = wrapper
        if (w == null || animating) { dismissNow(); onEnd(); return }
        animating = true
        disableTyping()
        w.animate()
            .translationX(w.width.toFloat().coerceAtLeast(panelWidth.toFloat()))
            .setDuration(PanelSettings.animDurationMs(ctx))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    w.animate().setListener(null)
                    release()
                    onEnd()
                }
                override fun onAnimationCancel(a: Animator) {
                    w.animate().setListener(null)
                    release()
                    onEnd()
                }
            })
            .start()
    }

    fun dismissNow() {
        wrapper?.animate()?.cancel()
        release()
    }

    private fun release() {
        disableTyping()
        pageUpdater = null
        toolsRefresher = null
        contentHost.removeAllViews()
        safeRemove(ctx, wrapper)
        wrapper = null; card = null; lp = null
        scope.cancel()
        animating = false
    }

    // ────────────────────────────────────────────────────────────────
    // Rail (tab + shortcut game)
    // ────────────────────────────────────────────────────────────────

    private fun buildRail(accent: Int): View {
        val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = vbox(ctx, ctx.dp(4))
        col.gravity = Gravity.CENTER_HORIZONTAL
        for (t in PanelTab.values()) {
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(8))
                addView(iconView(ctx, t.icon, 22, Pal.muted), LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
                addView(
                    tv(ctx, t.label, 10f, Pal.muted, true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = ctx.dp(3) }
                )
                setOnClickListener { selectTab(t) }
            }
            railItems.add(t to item)
            col.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(View(ctx).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) },
            LinearLayout.LayoutParams(ctx.dp(40), 1).also { it.topMargin = ctx.dp(6); it.bottomMargin = ctx.dp(6) })
        // Shortcut game (ikon aplikasi asli dari daftar game)
        GamePanelController.loadGames { games ->
            val pm = ctx.packageManager
            if (wrapper == null) return@loadGames
            for (g in games.take(10)) {
                val icon = try { pm.getApplicationIcon(g.packageName) } catch (e: Exception) { null } ?: continue
                val iv = ImageView(ctx).apply {
                    setImageDrawable(icon)
                    setOnClickListener {
                        if (ToolActions.launchGame(ctx, g.packageName)) GamePanelController.closePanel()
                        else GamePanelController.toast("Tidak bisa membuka ${g.displayName}")
                    }
                }
                col.addView(iv, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).also { it.topMargin = ctx.dp(6) })
            }
        }
        sv.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun styleRail(accent: Int) {
        for ((t, v) in railItems) {
            val on = t == currentTab
            val c = if (on) accent else Pal.muted
            (v.getChildAt(0) as ImageView).setColorFilter(c)
            (v.getChildAt(1) as TextView).setTextColor(c)
            v.background = if (on) rounded(Color.argb(28, 255, 255, 255), ctx.dpf(10f)) else null
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Konten + header
    // ────────────────────────────────────────────────────────────────

    private fun buildContent(accent: Int): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val header = vbox(ctx, ctx.dp(10))
        val top = hbox(ctx)
        val title = tv(ctx, "Game Booster  ·  ${GamePanelController.gameName}", 13f, accent, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        top.addView(title, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(iconBtn(com.example.R.drawable.ic_minus) { GamePanelController.minimize() }, LinearLayout.LayoutParams(ctx.dp(38), ctx.dp(38)))
        top.addView(iconBtn(com.example.R.drawable.ic_close) { GamePanelController.closePanel() }, LinearLayout.LayoutParams(ctx.dp(38), ctx.dp(38)))
        header.addView(top)
        headerMetrics = tv(ctx, "Mengukur…", 11f, Pal.text).apply { setPadding(0, ctx.dp(4), 0, 0) }
        header.addView(headerMetrics)
        col.addView(header)
        col.addView(View(ctx).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        contentHost = FrameLayout(ctx)
        col.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun iconBtn(@androidx.annotation.DrawableRes res: Int, onClick: () -> Unit): ImageView =
        iconView(ctx, res, 18, Pal.text).apply {
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
            setOnClickListener { onClick() }
        }

    fun openTab(t: PanelTab) = selectTab(t)

    private fun selectTab(t: PanelTab) {
        disableTyping()
        currentTab = t
        pageUpdater = null
        toolsRefresher = null
        styleRail(Pal.accent(PanelSettings.theme(ctx)))

        val page: View = try {
            when (t) {
                PanelTab.TOOLS -> pages.toolsPage { toolsRefresher = it }
                PanelTab.PERF -> pages.performancePage()
                PanelTab.NET -> pages.networkPage()
                PanelTab.TOUCH -> pages.touchPage()
                PanelTab.GAMES -> pages.gamesPage()
                PanelTab.SETTINGS -> pages.settingsPage()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Halaman ${t.name} gagal: ${e.message}", e)
            tv(ctx, "Fitur tidak tersedia pada perangkat ini.", 13f, Pal.muted).apply { setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16)) }
        }

        // Transisi halus: fade + geser kecil
        contentHost.removeAllViews()
        val sv = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(12))
            clipToPadding = false
        }
        sv.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.alpha = 0f
        sv.translationX = ctx.dpf(14f)
        contentHost.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sv.animate().alpha(1f).translationX(0f).setDuration(PanelSettings.animDurationMs(ctx) * 3 / 4).start()

        PanelMonitor.snapshot.value?.let { pageUpdater?.invoke(it) }
    }

    // ────────────────────────────────────────────────────────────────
    // Data realtime
    // ────────────────────────────────────────────────────────────────

    fun onSnapshot(s: PerfSnapshot) {
        if (wrapper == null) return
        val parts = ArrayList<String>()
        parts.add("FPS ${s.fps ?: "N/A"}")
        parts.add("CPU ${s.cpuPct?.let { "$it%" } ?: "N/A"}")
        parts.add("GPU ${s.gpuPct?.let { "$it%" } ?: "N/A"}")
        parts.add("RAM ${s.ramPct}%")
        parts.add(s.tempC?.let { String.format(java.util.Locale.US, "%.0f°C", it) } ?: "Suhu N/A")
        parts.add("Bat ${s.batteryPct?.let { "$it%" } ?: "N/A"}")
        headerMetrics.text = parts.joinToString("  ·  ")
        try { pageUpdater?.invoke(s) } catch (e: Exception) { Log.w(TAG, "updater: ${e.message}") }
    }

    fun refreshTools() { toolsRefresher?.invoke() }

    // ────────────────────────────────────────────────────────────────
    // Input teks (jendela sementara fokusable)
    // ────────────────────────────────────────────────────────────────

    fun enableTyping(et: EditText) {
        et.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && !typingEnabled) setFocusableWindow(true)
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                v.requestFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
            false
        }
    }

    fun disableTyping() {
        if (!typingEnabled) return
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        wrapper?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
        setFocusableWindow(false)
    }

    private fun setFocusableWindow(focusable: Boolean) {
        val p = lp ?: return
        typingEnabled = focusable
        p.flags = if (focusable) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        safeUpdate(ctx, wrapper, p)
    }

    /** Waktu berjalan game sejak terdeteksi. */
    fun gameDurationText(): String {
        val ms = SystemClock.elapsedRealtime() - GamePanelController.gameStartElapsed
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} j ${m % 60} m" else "$m m"
    }
}
