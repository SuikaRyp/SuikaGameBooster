package com.example.gamepanel

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Status timer/stopwatch bertahan walau panel ditutup selama game masih berjalan. */
object MiniState {
    private val h = Handler(Looper.getMainLooper())

    // Stopwatch
    var swRunning = false
    var swStart = 0L
    var swAcc = 0L
    fun swElapsed(): Long = swAcc + if (swRunning) SystemClock.elapsedRealtime() - swStart else 0L

    // Timer (hitung mundur)
    var tmRunning = false
    var tmEnd = 0L
    var tmRemaining = 0L
    private var tmRunnable: Runnable? = null

    fun tmLeft(): Long = if (tmRunning) (tmEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else tmRemaining

    fun tmAdd(ctx: Context, ms: Long) {
        if (tmRunning) { tmEnd += ms; reschedule(ctx) } else tmRemaining += ms
    }

    fun tmStart(ctx: Context) {
        if (tmRunning || tmRemaining <= 0L) return
        tmRunning = true
        tmEnd = SystemClock.elapsedRealtime() + tmRemaining
        reschedule(ctx)
    }

    fun tmPause() {
        if (!tmRunning) return
        tmRemaining = tmLeft()
        tmRunning = false
        tmRunnable?.let { h.removeCallbacks(it) }
    }

    fun tmReset() {
        tmRunning = false; tmRemaining = 0L
        tmRunnable?.let { h.removeCallbacks(it) }
    }

    private fun reschedule(ctx: Context) {
        tmRunnable?.let { h.removeCallbacks(it) }
        val app = ctx.applicationContext
        val r = Runnable {
            tmRunning = false; tmRemaining = 0L
            GamePanelController.toast("Timer selesai")
            try {
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v?.vibrate(500)
            } catch (e: Exception) { /* abaikan */ }
        }
        tmRunnable = r
        h.postDelayed(r, (tmEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    fun reset() {
        swRunning = false; swAcc = 0L; swStart = 0L
        tmReset()
    }

    fun fmt(ms: Long, tenths: Boolean): String {
        val t = ms / 1000
        val base = String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
        return if (tenths) base + "." + (ms % 1000) / 100 else base
    }
}

/** Menjalankan ticker selama view terpasang; berhenti otomatis saat lepas (tanpa kebocoran). */
private fun View.tick(intervalMs: Long, block: () -> Unit) {
    val h = Handler(Looper.getMainLooper())
    val r = object : Runnable {
        override fun run() { block(); h.postDelayed(this, intervalMs) }
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { h.removeCallbacks(r); h.post(r) }
        override fun onViewDetachedFromWindow(v: View) { h.removeCallbacks(r) }
    })
    if (isAttachedToWindow) h.post(r)
}

object MiniTools {

    fun stopwatch(ctx: Context): View {
        val c = card(ctx)
        c.addView(tv(ctx, "STOPWATCH", 11f, Pal.emberSoft, true))
        val time = tv(ctx, MiniState.fmt(MiniState.swElapsed(), true), 30f, Pal.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        }
        c.addView(time)
        val row = hbox(ctx)
        val startBtn = button(ctx, if (MiniState.swRunning) "JEDA" else "MULAI") {}
        val resetBtn = button(ctx, "RESET", false) {}
        startBtn.setOnClickListener {
            if (MiniState.swRunning) {
                MiniState.swAcc = MiniState.swElapsed(); MiniState.swRunning = false
            } else {
                MiniState.swStart = SystemClock.elapsedRealtime(); MiniState.swRunning = true
            }
            startBtn.text = if (MiniState.swRunning) "JEDA" else "MULAI"
        }
        resetBtn.setOnClickListener {
            MiniState.swRunning = false; MiniState.swAcc = 0L
            startBtn.text = "MULAI"
            time.text = MiniState.fmt(0, true)
        }
        row.addView(startBtn, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = ctx.dp(6) })
        row.addView(resetBtn, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row)
        time.tick(100) { time.text = MiniState.fmt(MiniState.swElapsed(), true) }
        return c
    }

    fun timer(ctx: Context): View {
        val c = card(ctx)
        c.addView(tv(ctx, "TIMER", 11f, Pal.emberSoft, true))
        val time = tv(ctx, MiniState.fmt(MiniState.tmLeft(), false), 30f, Pal.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        }
        c.addView(time)
        val add = hbox(ctx)
        for ((label, ms) in listOf("+1 mnt" to 60_000L, "+5 mnt" to 300_000L, "+10 mnt" to 600_000L)) {
            add.addView(button(ctx, label, false) {
                MiniState.tmAdd(ctx, ms)
                time.text = MiniState.fmt(MiniState.tmLeft(), false)
            }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = ctx.dp(4) })
        }
        c.addView(add)
        c.addView(spacer(ctx, 6))
        val row = hbox(ctx)
        val startBtn = button(ctx, if (MiniState.tmRunning) "JEDA" else "MULAI") {}
        startBtn.setOnClickListener {
            if (MiniState.tmRunning) MiniState.tmPause() else MiniState.tmStart(ctx)
            startBtn.text = if (MiniState.tmRunning) "JEDA" else "MULAI"
        }
        row.addView(startBtn, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = ctx.dp(6) })
        row.addView(button(ctx, "RESET", false) {
            MiniState.tmReset(); startBtn.text = "MULAI"; time.text = MiniState.fmt(0, false)
        }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row)
        time.tick(300) {
            time.text = MiniState.fmt(MiniState.tmLeft(), false)
            startBtn.text = if (MiniState.tmRunning) "JEDA" else "MULAI"
        }
        return c
    }

    fun calculator(ctx: Context): View {
        val c = card(ctx)
        c.addView(tv(ctx, "KALKULATOR", 11f, Pal.emberSoft, true))
        val display = tv(ctx, "0", 26f, Pal.text, true).apply {
            gravity = Gravity.END
            setPadding(0, ctx.dp(6), 0, ctx.dp(8))
            maxLines = 1
        }
        c.addView(display)

        var acc: Double? = null
        var op: Char? = null
        var entry = ""
        var justEq = false

        fun show(v: String) { display.text = v }
        fun fmt(d: Double): String = if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString()
        else String.format(Locale.US, "%.8f", d).trimEnd('0').trimEnd('.')
        fun calc(a: Double, o: Char, b: Double): Double? = when (o) {
            '+' -> a + b; '−' -> a - b; '×' -> a * b
            '÷' -> if (b == 0.0) null else a / b
            else -> b
        }

        fun press(k: String) {
            when (k) {
                "C" -> { acc = null; op = null; entry = ""; justEq = false; show("0") }
                "DEL" -> { if (entry.isNotEmpty()) { entry = entry.dropLast(1); show(if (entry.isEmpty()) "0" else entry) } }
                "+", "−", "×", "÷" -> {
                    if (entry.isNotEmpty()) {
                        val b = entry.toDoubleOrNull() ?: 0.0
                        val a = acc
                        val o = op
                        acc = if (a != null && o != null) calc(a, o, b) else b
                        if (acc == null) { show("Error"); op = null; entry = ""; return }
                        entry = ""
                    }
                    op = k[0]; justEq = false
                    acc?.let { show(fmt(it)) }
                }
                "=" -> {
                    val a = acc
                    val o = op
                    if (a != null && o != null && entry.isNotEmpty()) {
                        val r = calc(a, o, entry.toDoubleOrNull() ?: 0.0)
                        if (r == null) { show("Error"); acc = null; op = null; entry = "" }
                        else { show(fmt(r)); acc = r; op = null; entry = ""; justEq = true }
                    }
                }
                else -> {
                    if (justEq) { acc = null; justEq = false }
                    if (k == "." && entry.contains('.')) return
                    if (entry.length < 14) entry += if (k == "." && entry.isEmpty()) "0." else k
                    show(entry)
                }
            }
        }

        val keys = listOf(
            listOf("7", "8", "9", "÷"), listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "−"), listOf("0", ".", "DEL", "+"),
            listOf("C", "=")
        )
        for (rowKeys in keys) {
            val row = hbox(ctx)
            for (k in rowKeys) {
                val isOp = k in listOf("÷", "×", "−", "+", "=")
                val b = tv(ctx, k, 16f, if (isOp) Pal.emberSoft else Pal.text, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ctx.dp(10), 0, ctx.dp(10))
                    background = rounded(Color_alpha(if (isOp) 60 else 35), ctx.dpf(10f))
                    setOnClickListener { press(k) }
                }
                row.addView(b, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2)) })
            }
            c.addView(row)
        }
        return c
    }

    @Suppress("FunctionName")
    private fun Color_alpha(a: Int): Int = android.graphics.Color.argb(a, 255, 255, 255)

    /** Catatan cepat. EditText butuh jendela fokus → panel mengaktifkannya hanya saat mengetik. */
    fun notes(ctx: Context, host: PanelWindow): View {
        val c = card(ctx)
        c.addView(tv(ctx, "CATATAN CEPAT", 11f, Pal.emberSoft, true))
        val et = EditText(ctx).apply {
            setText(PanelSettings.notes(ctx))
            setTextColor(Pal.text)
            setHintTextColor(Pal.muted)
            hint = "Ketuk lalu ketik catatan…"
            textSize = 13f
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = rounded(Color_alpha(25), ctx.dpf(10f))
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        }
        host.enableTyping(et)
        c.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = ctx.dp(6) })
        c.addView(spacer(ctx, 6))
        c.addView(button(ctx, "SIMPAN") {
            PanelSettings.setNotes(ctx, et.text.toString())
            host.disableTyping()
            GamePanelController.toast("Catatan disimpan")
        })
        return c
    }
}

