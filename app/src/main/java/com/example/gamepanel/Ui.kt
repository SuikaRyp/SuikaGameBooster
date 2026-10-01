package com.example.gamepanel

import android.content.Context
import android.widget.ImageView
import androidx.annotation.DrawableRes
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Palet gelap + aksen cyan (mengikuti tema app). */
object Pal {
    val bg = Color.rgb(15, 19, 24)
    val card = Color.argb(22, 255, 255, 255)
    val cardSolid = Color.rgb(22, 27, 34)
    val stroke = Color.argb(34, 255, 255, 255)
    val text = Color.rgb(232, 235, 240)
    val muted = Color.rgb(147, 160, 177)
    val ember = Color.rgb(255, 122, 26)
    val emberSoft = Color.rgb(255, 160, 96)
    val onAccent = Color.rgb(26, 15, 6)
    val green = Color.rgb(76, 195, 138)
    val orange = Color.rgb(233, 185, 73)
    val red = Color.rgb(229, 83, 75)
    val yellow = Color.rgb(233, 185, 73)

    /** Kunci tema tersimpan tetap ("cyan","green","orange","purple") supaya setelan lama tidak rusak. */
    fun accent(theme: String): Int = when (theme) {
        "green" -> green
        "orange" -> orange
        "purple" -> Color.rgb(112, 168, 254)
        else -> ember
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun tv(ctx: Context, text: String, sp: Float = 13f, color: Int = Pal.text, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun vbox(ctx: Context, pad: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(pad, pad, pad, pad)
}

fun hbox(ctx: Context, pad: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(pad, pad, pad, pad)
}

fun card(ctx: Context): LinearLayout = vbox(ctx, ctx.dp(12)).apply {
    background = rounded(Pal.card, ctx.dpf(12f), Pal.stroke, 1)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.bottomMargin = ctx.dp(8) }
}

fun sectionTitle(ctx: Context, text: String): TextView =
    tv(ctx, text, 12f, Pal.muted, true).apply {
        setPadding(ctx.dp(2), ctx.dp(8), 0, ctx.dp(6))
    }

/** ImageView berisi ikon vektor (SVG) dengan warna [tint]. */
fun iconView(ctx: Context, @DrawableRes res: Int, sizeDp: Int = 24, tint: Int = Pal.text): ImageView =
    ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = ViewGroup.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

/** Ikon vektor di kiri teks (menggantikan emoji di depan label). */
fun TextView.leftIcon(@DrawableRes res: Int, tint: Int, sizeDp: Int = 16) {
    val d = androidx.core.content.ContextCompat.getDrawable(context, res)?.mutate() ?: return
    d.setTint(tint)
    val px = (sizeDp * resources.displayMetrics.density + 0.5f).toInt()
    d.setBounds(0, 0, px, px)
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = (6 * resources.displayMetrics.density + 0.5f).toInt()
}

fun spacer(ctx: Context, h: Int): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(h))
}

fun button(ctx: Context, text: String, primary: Boolean = true, onClick: () -> Unit): TextView =
    tv(ctx, text, 13f, if (primary) Pal.onAccent else Pal.text, true).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        background = if (primary) rounded(Pal.ember, ctx.dpf(10f))
        else rounded(Color.argb(22, 255, 255, 255), ctx.dpf(10f), Pal.stroke, 1)
        isClickable = true
        setOnClickListener { onClick() }
    }

/** Baris: label (+ subteks) di kiri, Switch di kanan. */
fun switchRow(ctx: Context, label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
    val row = hbox(ctx).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) }
    val col = vbox(ctx)
    col.addView(tv(ctx, label, 13f, Pal.text, true))
    if (sub != null) col.addView(tv(ctx, sub, 10.5f, Pal.muted).apply { setPadding(0, ctx.dp(2), 0, 0) })
    row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val sw = Switch(ctx).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    row.addView(sw)
    return row
}

/** Slider dengan label dan nilai terformat. */
fun seekRow(
    ctx: Context, label: String, min: Int, max: Int, value: Int,
    fmt: (Int) -> String, onChange: (Int) -> Unit
): View {
    val col = vbox(ctx).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }
    val head = hbox(ctx)
    val valueTv = tv(ctx, fmt(value), 12f, Pal.emberSoft, true)
    head.addView(tv(ctx, label, 13f, Pal.text, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(valueTv)
    val sb = SeekBar(ctx).apply {
        this.max = max - min
        progress = (value - min).coerceIn(0, max - min)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                valueTv.text = fmt(p + min)
                if (fromUser) onChange(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    col.addView(head)
    col.addView(sb)
    return col
}

/** Kontrol segmen (mis. Penghemat / Seimbang / BOOST). Mengembalikan view + fungsi untuk mengganti pilihan. */
class Segmented(
    private val ctx: Context,
    private val options: List<String>,
    selected: Int,
    private val accent: Int = Pal.emberSoft,
    private val onSelect: (Int) -> Unit
) {
    val view: LinearLayout = LinearLayout(ctx)
    private val items = ArrayList<TextView>()

    init {
        view.orientation = LinearLayout.HORIZONTAL
        view.background = rounded(Color.argb(24, 255, 255, 255), ctx.dpf(10f))
        view.setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        options.forEachIndexed { i, o ->
            val t = tv(ctx, o, 12f, Pal.muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(6), ctx.dp(9), ctx.dp(6), ctx.dp(9))
                setOnClickListener { select(i, true) }
            }
            items.add(t)
            view.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        select(selected, false)
    }

    fun select(i: Int, notify: Boolean) {
        items.forEachIndexed { idx, t ->
            if (idx == i) {
                t.setTextColor(Pal.onAccent)
                t.background = rounded(accent, ctx.dpf(8f))
            } else {
                t.setTextColor(Pal.muted)
                t.background = null
            }
        }
        if (notify) onSelect(i)
    }
}

/** Tile alat: kotak ikon vektor + label. Aktif → disorot aksen. */
class ToolTile(private val ctx: Context, @DrawableRes iconRes: Int, label: String, private val onClick: () -> Unit) {
    val view: LinearLayout = LinearLayout(ctx)
    private val iconBox = FrameLayout(ctx)
    private val icon = iconView(ctx, iconRes, 24, Pal.text)
    private val labelTv = tv(ctx, label, 11f, Pal.text)

    init {
        view.orientation = LinearLayout.VERTICAL
        view.gravity = Gravity.CENTER_HORIZONTAL
        view.setPadding(ctx.dp(2), ctx.dp(6), ctx.dp(2), ctx.dp(6))
        iconBox.addView(icon, FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        view.addView(iconBox, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)))
        labelTv.gravity = Gravity.CENTER
        labelTv.maxLines = 2
        view.addView(labelTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = ctx.dp(4) })
        view.isClickable = true
        view.setOnClickListener { onClick() }
        setActive(false)
    }

    fun setActive(active: Boolean) {
        iconBox.background = if (active) rounded(Pal.ember, ctx.dpf(14f))
        else rounded(Color.argb(22, 255, 255, 255), ctx.dpf(14f), Pal.stroke, 1)
        icon.setColorFilter(if (active) Pal.onAccent else Pal.text)
        labelTv.setTextColor(if (active) Pal.emberSoft else Pal.text)
    }

    fun setLabel(t: String) { labelTv.text = t }
}

/** Gauge lingkar: persen dengan busur; null → "N/A". */
class GaugeView(ctx: Context, private val title: String) : View(ctx) {
    private var value: Int? = null
    private var accent: Int = Pal.ember
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(6f); color = Color.argb(50, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(6f); strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = ctx.dpf(26f); typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.muted; textAlign = Paint.Align.CENTER; textSize = ctx.dpf(11f)
    }
    private val rect = RectF()

    fun set(v: Int?, accent: Int) {
        if (v == value && accent == this.accent) return
        value = v
        this.accent = accent
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val pad = track.strokeWidth
        val size = minOf(width, height).toFloat()
        val left = (width - size) / 2f + pad
        val top = (height - size) / 2f + pad
        rect.set(left, top, left + size - 2 * pad, top + size - 2 * pad)
        c.drawArc(rect, 135f, 270f, false, track)
        val v = value
        if (v != null) {
            arc.color = accent
            c.drawArc(rect, 135f, 270f * v / 100f, false, arc)
            c.drawText("$v", width / 2f, height / 2f + big.textSize * 0.15f, big)
        } else {
            c.drawText("N/A", width / 2f, height / 2f + big.textSize * 0.15f, big)
        }
        c.drawText(title, width / 2f, height / 2f - big.textSize * 0.65f, small)
    }
}

/** Grafik garis riwayat pendek. */
class SparklineView(ctx: Context, private val title: String, private val unit: String, private val lineColor: Int) : View(ctx) {
    private var data: FloatArray = FloatArray(0)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.8f); color = lineColor; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(35, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.muted; textSize = ctx.dpf(10.5f) }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.dpf(12f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    private val path = Path()
    private val fillPath = Path()

    fun set(d: FloatArray) { data = d; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = label.textSize + 6f
        c.drawText(title, 0f, label.textSize, label)
        val valid = data.filter { !it.isNaN() }
        if (valid.isEmpty()) {
            valuePaint.color = Pal.muted
            c.drawText("N/A", w, label.textSize, valuePaint)
            return
        }
        valuePaint.color = Color.WHITE
        val last = valid.last()
        c.drawText("${Math.round(last)}$unit", w, label.textSize, valuePaint)
        var mn = valid.min()
        var mx = valid.max()
        if (mx - mn < 5f) { val mid = (mx + mn) / 2f; mn = mid - 2.5f; mx = mid + 2.5f }
        val gh = h - top - 2f
        path.reset(); fillPath.reset()
        var started = false
        var lastX = 0f
        val n = data.size
        for (i in 0 until n) {
            val v = data[i]
            if (v.isNaN()) continue
            val x = if (n <= 1) w else w * i / (n - 1)
            val y = top + gh - (v - mn) / (mx - mn) * gh
            if (!started) { path.moveTo(x, y); fillPath.moveTo(x, top + gh); fillPath.lineTo(x, y); started = true }
            else { path.lineTo(x, y); fillPath.lineTo(x, y) }
            lastX = x
        }
        if (started) {
            fillPath.lineTo(lastX, top + gh); fillPath.close()
            c.drawPath(fillPath, fill)
            c.drawPath(path, line)
        }
    }
}

fun fmtBytes(b: Long): String {
    val gb = b / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) String.format(java.util.Locale.US, "%.1f GB", gb)
    else String.format(java.util.Locale.US, "%d MB", b / 1024 / 1024)
}

fun na(v: Any?, suffix: String = ""): String = if (v == null) "N/A" else "$v$suffix"
