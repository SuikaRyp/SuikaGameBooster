package com.example.gamepanel

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

private const val OVL_TAG = "GamePanelOverlay"

fun overlayType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

fun windowManager(c: Context): WindowManager = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager

@Suppress("DEPRECATION")
fun realScreenSize(c: Context): Point {
    val p = Point()
    windowManager(c).defaultDisplay.getRealSize(p)
    return p
}

/** Tambah view ke WindowManager tanpa pernah melempar exception ke pemanggil. */
fun safeAdd(c: Context, v: View, lp: WindowManager.LayoutParams): Boolean = try {
    windowManager(c).addView(v, lp)
    true
} catch (e: Exception) {
    Log.w(OVL_TAG, "addView gagal: ${e.message}")
    false
}

fun safeRemove(c: Context, v: View?) {
    if (v == null) return
    try { windowManager(c).removeViewImmediate(v) } catch (e: Exception) { /* sudah terlepas */ }
}

fun safeUpdate(c: Context, v: View?, lp: WindowManager.LayoutParams) {
    if (v == null) return
    try { windowManager(c).updateViewLayout(v, lp) } catch (e: Exception) { /* abaikan */ }
}

private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

// ════════════════════════════════════════════════════════════════════
// Penjaga tepi + mesin edge swipe
// ════════════════════════════════════════════════════════════════════

/**
 * Kumpulan jendela tipis di tepi layar:
 *  • KANAN: mendeteksi gestur geser kanan → kiri (buka panel). Sekaligus proteksi tepi bila diaktifkan.
 *  • KIRI/ATAS/BAWAH: hanya menelan sentuhan tak sengaja bila proteksi dinyalakan.
 *
 * Saat semua opsi mati, tidak ada jendela sama sekali → tidak ada gangguan ke game.
 * Catatan jujur: sentuhan yang jatuh tepat di strip TIDAK diteruskan ke game (batasan overlay Android).
 */
class EdgeGuards(
    private val ctx: Context,
    /** Level proteksi efektif (sentuh, tepi) — sudah menggabungkan pengaturan global + profil game. */
    private val levels: () -> Pair<Level, Level>,
    private val onOpenPanel: () -> Unit
) {
    private var right: View? = null
    private var left: View? = null
    private var top: View? = null
    private var bottom: View? = null
    private var lastTrigger = 0L
    private var keepAwake = false

    val active: Boolean get() = right != null || left != null || top != null || bottom != null

    fun apply() {
        remove()
        if (!Settings.canDrawOverlays(ctx)) return
        val (touchProt, edgeProt) = levels()
        val swipeOn = PanelSettings.edgeEnabled(ctx)
        val protOn = edgeProt != Level.OFF || touchProt != Level.OFF
        val screen = realScreenSize(ctx)

        if (swipeOn || protOn) {
            val swipeW = ctx.dp(PanelSettings.edgeWidthDp(ctx))
            val w = maxOf(swipeW, ctx.dp(edgeProt.dp))
            val fullHeight = edgeProt != Level.OFF
            val h = if (fullHeight) ViewGroup.LayoutParams.MATCH_PARENT
            else (screen.y * PanelSettings.edgeHeightPercent(ctx) / 100)
            right = makeStrip(w, h, Gravity.END or Gravity.CENTER_VERTICAL, true)
        }
        if (edgeProt != Level.OFF) {
            left = makeStrip(ctx.dp(edgeProt.dp), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL, false)
        }
        if (touchProt != Level.OFF) {
            top = makeStrip(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(touchProt.dp), Gravity.TOP or Gravity.CENTER_HORIZONTAL, false)
            bottom = makeStrip(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(touchProt.dp), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, false)
        }
    }

    private fun makeStrip(w: Int, h: Int, gravity: Int, swipe: Boolean): View? {
        val v = View(ctx)
        v.setBackgroundColor(Color.TRANSPARENT)
        val ss = if (swipe) swipeListener() else null
        v.setOnTouchListener { _, e ->
            if (ss != null) ss.onTouch(e)
            true // telan sentuhan agar tidak ikut memicu gestur sistem/game
        }
        val lp = WindowManager.LayoutParams(w, h, overlayType(), BASE_FLAGS, PixelFormat.TRANSLUCENT)
        lp.gravity = gravity
        if (!safeAdd(ctx, v, lp)) return null
        // Minta sistem tidak memakai area ini untuk gestur back/home (efek bergantung ROM).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.post {
                try { v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height)) }
                catch (e: Exception) { /* tidak didukung */ }
            }
        }
        return v
    }

    private fun swipeListener() = object {
        var downX = 0f
        var downY = 0f
        var fired = false
        fun onTouch(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> {
                    if (fired || !PanelSettings.edgeEnabled(ctx)) return
                    val dx = downX - e.rawX          // positif = jari bergerak ke kiri
                    val dy = abs(e.rawY - downY)
                    if (dx >= neededPx() && dx > dy * 1.2f) {
                        fired = true
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTrigger >= PanelSettings.cooldownMs(ctx)) {
                            lastTrigger = now
                            onOpenPanel()
                        }
                    }
                }
            }
        }
    }

    /** Jarak minimum: jarak dasar dikali faktor sensitivitas (1 = susah, 5 = mudah). */
    private fun neededPx(): Float {
        val base = ctx.dpf(PanelSettings.swipeDistanceDp(ctx).toFloat())
        val factor = (6 - PanelSettings.sensitivity(ctx)) / 3f
        return maxOf(ctx.dpf(16f), base * factor)
    }

    fun remove() {
        safeRemove(ctx, right); safeRemove(ctx, left); safeRemove(ctx, top); safeRemove(ctx, bottom)
        right = null; left = null; top = null; bottom = null
    }
}

// ════════════════════════════════════════════════════════════════════
// Kunci sentuh (touch lock)
// ════════════════════════════════════════════════════════════════════

class TouchLockOverlay(private val ctx: Context, private val onUnlock: () -> Unit) {
    private var root: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    val isOn: Boolean get() = root != null

    fun show() {
        if (root != null || !Settings.canDrawOverlays(ctx)) return
        val r = FrameLayout(ctx)
        r.setBackgroundColor(Color.argb(1, 0, 0, 0))
        r.setOnTouchListener { _, _ -> true }

        val btn = tv(ctx, "Tahan 1,5 dtk untuk membuka kunci", 12f, Pal.text, true).apply {
            leftIcon(com.example.R.drawable.ic_unlock, Pal.text, 16)
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            background = rounded(Color.argb(200, 15, 19, 24), ctx.dpf(20f), Pal.stroke, 1)
        }
        val hold = Runnable { hide(); onUnlock() }
        btn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { handler.postDelayed(hold, 1500L); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(hold); true }
                else -> true
            }
        }
        r.addView(btn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).also {
            it.leftMargin = ctx.dp(16); it.bottomMargin = ctx.dp(16)
        })

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType(), BASE_FLAGS, PixelFormat.TRANSLUCENT
        )
        if (safeAdd(ctx, r, lp)) root = r
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        safeRemove(ctx, root)
        root = null
    }
}

// ════════════════════════════════════════════════════════════════════
// Utilitas: kunci orientasi + layar tetap menyala (satu jendela 1×1 px, tak bisa disentuh)
// ════════════════════════════════════════════════════════════════════

class UtilityOverlay(private val ctx: Context) {
    private var v: View? = null

    /** @param orientation konstanta ActivityInfo.SCREEN_ORIENTATION_* atau UNSPECIFIED */
    fun apply(orientation: Int, keepAwake: Boolean) {
        remove()
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED && !keepAwake) return
        if (!Settings.canDrawOverlays(ctx)) return
        val view = View(ctx)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (keepAwake) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val lp = WindowManager.LayoutParams(1, 1, overlayType(), flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.screenOrientation = orientation
        if (safeAdd(ctx, view, lp)) v = view
    }

    fun remove() {
        safeRemove(ctx, v)
        v = null
    }
}

// ════════════════════════════════════════════════════════════════════
// Crosshair (tidak bisa disentuh)
// ════════════════════════════════════════════════════════════════════

class CrosshairOverlay(private val ctx: Context) {
    private var v: View? = null
    val isOn: Boolean get() = v != null

    private class CrossView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 60, 60); strokeWidth = c.dpf(1.6f); style = Paint.Style.STROKE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 60, 60); style = Paint.Style.FILL }
        override fun onDraw(cv: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val gap = width * 0.16f
            cv.drawLine(cx - width / 2f, cy, cx - gap, cy, p)
            cv.drawLine(cx + gap, cy, cx + width / 2f, cy, p)
            cv.drawLine(cx, cy - height / 2f, cx, cy - gap, p)
            cv.drawLine(cx, cy + gap, cx, cy + height / 2f, p)
            cv.drawCircle(cx, cy, width * 0.05f, dot)
        }
    }

    fun show() {
        if (v != null || !Settings.canDrawOverlays(ctx)) return
        val view = CrossView(ctx)
        val size = ctx.dp(34)
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.CENTER
        if (safeAdd(ctx, view, lp)) v = view
    }

    fun hide() { safeRemove(ctx, v); v = null }
}

// ════════════════════════════════════════════════════════════════════
// HUD performa
// ════════════════════════════════════════════════════════════════════

class HudOverlay(private val ctx: Context) {
    private var root: LinearLayout? = null
    private var text: TextView? = null
    private var done: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var edit = false
    val isShown: Boolean get() = root != null

    fun show() {
        if (root != null || !Settings.canDrawOverlays(ctx)) return
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
            text = "…"
        }
        val d = tv(ctx, "Selesai atur posisi", 11f, Pal.onAccent, true).apply {
            leftIcon(com.example.R.drawable.ic_check, Pal.onAccent, 14)
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
            background = rounded(Pal.ember, ctx.dpf(8f))
            visibility = View.GONE
            setOnClickListener { setEditMode(false) }
        }
        r.addView(t)
        r.addView(d)
        root = r; text = t; done = d

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType(), hudFlags(false), PixelFormat.TRANSLUCENT
        )
        lp = params
        styleAndPosition()
        installDrag(r)
        if (!safeAdd(ctx, r, params)) { root = null; text = null; done = null; lp = null }
    }

    private fun hudFlags(touchable: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        return f
    }

    private fun styleAndPosition() {
        val r = root ?: return
        val t = text ?: return
        val p = lp ?: return
        val accent = Pal.accent(PanelSettings.theme(ctx))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, PanelSettings.hudSizeSp(ctx).toFloat())
        r.background = rounded(Color.argb(170, 8, 12, 24), ctx.dpf(10f), accent and 0x00FFFFFF or 0x55000000, 1)
        r.alpha = PanelSettings.hudOpacity(ctx) / 100f
        val corner = PanelSettings.hudCorner(ctx)
        val m = ctx.dp(12)
        when (corner) {
            0 -> { p.gravity = Gravity.TOP or Gravity.START; p.x = m; p.y = m }
            1 -> { p.gravity = Gravity.TOP or Gravity.END; p.x = m; p.y = m }
            2 -> { p.gravity = Gravity.BOTTOM or Gravity.START; p.x = m; p.y = m }
            3 -> { p.gravity = Gravity.BOTTOM or Gravity.END; p.x = m; p.y = m }
            else -> { p.gravity = Gravity.TOP or Gravity.START; p.x = PanelSettings.hudX(ctx); p.y = PanelSettings.hudY(ctx) }
        }
        safeUpdate(ctx, r, p)
    }

    /** Terapkan ulang gaya/posisi setelah pengaturan berubah. */
    fun refreshStyle() = styleAndPosition()

    fun setEditMode(on: Boolean) {
        val r = root ?: return
        val p = lp ?: return
        edit = on
        done?.visibility = if (on) View.VISIBLE else View.GONE
        p.flags = hudFlags(on)
        if (on) {
            // saat mengatur posisi, pindah ke mode koordinat kustom dari posisi sekarang
            val loc = IntArray(2)
            r.getLocationOnScreen(loc)
            p.gravity = Gravity.TOP or Gravity.START
            p.x = loc[0]; p.y = loc[1]
            PanelSettings.setHudCorner(ctx, 4)
            PanelSettings.setHudXY(ctx, p.x, p.y)
        }
        safeUpdate(ctx, r, p)
    }

    private fun installDrag(r: View) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        r.setOnTouchListener { _, e ->
            val p = lp ?: return@setOnTouchListener false
            if (!edit) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y }
                MotionEvent.ACTION_MOVE -> {
                    val sz = realScreenSize(ctx)
                    p.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, maxOf(0, sz.x - r.width))
                    p.y = (oy + (e.rawY - sy)).toInt().coerceIn(0, maxOf(0, sz.y - r.height))
                    safeUpdate(ctx, r, p)
                }
                MotionEvent.ACTION_UP -> PanelSettings.setHudXY(ctx, p.x, p.y)
            }
            true
        }
    }

    fun update(s: PerfSnapshot?, pingMs: Int?) {
        val t = text ?: return
        if (s == null) { t.text = "Mengukur…"; return }
        val sb = StringBuilder()
        fun line(key: String, content: String) {
            if (!PanelSettings.hudMetric(ctx, key)) return
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(content)
        }
        line("fps", "FPS  : ${s.fps ?: "N/A"}")
        line("cpu", "CPU  : ${s.cpuPct?.let { "$it%" } ?: "N/A"}")
        line("gpu", "GPU  : ${s.gpuPct?.let { "$it%" } ?: "N/A"}")
        line("ram", "RAM  : ${fmtBytes(s.ramUsed)} (${s.ramPct}%)")
        line("temp", "TEMP : ${s.tempC?.let { String.format(java.util.Locale.US, "%.0f°C", it) } ?: "N/A"}")
        line("bat", "BAT  : ${s.batteryPct?.let { "$it%" } ?: "N/A"}")
        line("ping", "PING : ${pingMs?.let { "${it}ms" } ?: "N/A"}")
        t.text = if (sb.isEmpty()) "(semua metrik HUD dimatikan)" else sb.toString()
    }

    fun hide() {
        safeRemove(ctx, root)
        root = null; text = null; done = null; lp = null; edit = false
    }
}

// ════════════════════════════════════════════════════════════════════
// Bubble (mode mini)
// ════════════════════════════════════════════════════════════════════

class BubbleOverlay(private val ctx: Context, private val onTap: () -> Unit) {
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    val isShown: Boolean get() = view != null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val size = ctx.dp(48)
        val v = iconView(ctx, com.example.R.drawable.ic_gamepad, 24, Pal.emberSoft).apply {
            setPadding(ctx.dp(11), ctx.dp(11), ctx.dp(11), ctx.dp(11))
            background = rounded(Color.argb(235, 15, 19, 24), size / 2f, Pal.ember, ctx.dp(2))
            elevation = ctx.dpf(6f)
        }
        val sz = realScreenSize(ctx)
        val params = WindowManager.LayoutParams(size, size, overlayType(), BASE_FLAGS, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        val sx = PanelSettings.bubbleX(ctx)
        params.x = if (sx < 0) sz.x - size else sx.coerceIn(0, sz.x - size)
        params.y = PanelSettings.bubbleY(ctx).coerceIn(0, sz.y - size)
        lp = params
        view = v

        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var dx = 0f; var dy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = params.x; oy = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx
                    val my = e.rawY - dy
                    if (!moved && (abs(mx) > slop || abs(my) > slop)) moved = true
                    if (moved) {
                        val s2 = realScreenSize(ctx)
                        params.x = (ox + mx).toInt().coerceIn(0, s2.x - size)
                        params.y = (oy + my).toInt().coerceIn(0, s2.y - size)
                        safeUpdate(ctx, v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap() else snapToEdge(v, params, size)
                }
            }
            true
        }
        if (!safeAdd(ctx, v, params)) { view = null; lp = null }
    }

    private fun snapToEdge(v: View, p: WindowManager.LayoutParams, size: Int) {
        val sz = realScreenSize(ctx)
        val target = if (p.x + size / 2 < sz.x / 2) 0 else sz.x - size
        val from = p.x
        ValueAnimator.ofInt(from, target).apply {
            duration = 180L
            addUpdateListener {
                p.x = it.animatedValue as Int
                safeUpdate(ctx, v, p)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    PanelSettings.setBubbleXY(ctx, p.x, p.y)
                }
            })
            start()
        }
    }

    fun hide() {
        safeRemove(ctx, view)
        view = null; lp = null
    }
}
