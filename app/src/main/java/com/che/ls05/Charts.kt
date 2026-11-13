package com.che.ls05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import com.che.ls05.protocol.SleepSegment
import java.util.Locale

/*
 * Bugün ekranının çizimleri. Renkler kaynaklardan gelir; gölge, gradyan, animasyon yok.
 * Görünümler yalnızca kendilerine verilen değerleri çizer; veri yoksa ekran bunları göstermez.
 */

/** İlerleme halkası: yükseltilmiş yüzey renginde yol, nane yay, ortada kısa metin. */
class RingView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
    private var fraction = 0f
    private var center = ""

    init {
        val w = 10f * density
        track.style = Paint.Style.STROKE
        track.strokeWidth = w
        track.color = context.getColor(R.color.raised)
        arc.style = Paint.Style.STROKE
        arc.strokeWidth = w
        arc.strokeCap = Paint.Cap.ROUND
        arc.color = context.getColor(R.color.accent)
        label.color = context.getColor(R.color.text)
        label.textAlign = Paint.Align.CENTER
        label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** fraction 0..1; centerText boşsa ortaya bir şey yazılmaz. */
    fun set(fraction: Float, centerText: String) {
        this.fraction = fraction.coerceIn(0f, 1f)
        this.center = centerText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = track.strokeWidth
        val size = minOf(width, height).toFloat()
        if (size <= stroke * 2f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - stroke / 2f
        box.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, 0f, 360f, false, track)
        if (fraction > 0f) canvas.drawArc(box, -90f, 360f * fraction, false, arc)
        if (center.isNotEmpty()) {
            val inner = (r - stroke / 2f) * 2f
            label.textSize = minOf(labelPx, inner * 0.3f)
            val fm = label.fontMetrics
            canvas.drawText(center, cx, cy - (fm.ascent + fm.descent) / 2f, label)
        }
    }
}

/**
 * Sütun grafiği. Varsayılan 24 saat (etiket 6 saatte bir); [setDaily] ile günlük görünüm.
 * Vurgulanan sütun nane, değeri olan diğerleri soluk gri. 0 değeri kısa bir çizgidir.
 */
class BarsView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG)
    private val now = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var values = IntArray(24)
    private var current = -1
    private var labels: Array<String?>? = null

    init {
        bar.color = context.getColor(R.color.muted)
        bar.alpha = 150
        empty.color = context.getColor(R.color.raised)
        now.color = context.getColor(R.color.accent)
        label.color = context.getColor(R.color.muted)
        label.textAlign = Paint.Align.LEFT
        label.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    fun set(hourly: IntArray, currentHour: Int) {
        values = hourly.copyOf()
        current = currentHour
        labels = null
        invalidate()
    }

    /** Günlük görünüm: etiketler sütunun ortasına yazılır; null olan sütun etiketsiz. */
    fun setDaily(daily: IntArray, highlight: Int, dayLabels: Array<String?>) {
        values = daily.copyOf()
        current = highlight
        labels = dayLabels.copyOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0 || width == 0) return
        val fm = label.fontMetrics
        val labelH = fm.descent - fm.ascent
        val chartH = height - labelH - 6f * density
        if (chartH <= 0f) return
        val slot = width.toFloat() / n
        val barW = slot * 0.62f
        val pad = (slot - barW) / 2f
        val max = maxOf(values.maxOrNull() ?: 0, 1)
        val minH = 2f * density
        val radius = minOf(barW / 2f, 3f * density)
        for (i in 0 until n) {
            val h = maxOf(minH, chartH * values[i].toFloat() / max.toFloat())
            val left = slot * i + pad
            box.set(left, chartH - h, left + barW, chartH)
            val p = when {
                i == current -> now
                values[i] > 0 -> bar
                else -> empty
            }
            canvas.drawRoundRect(box, radius, radius, p)
        }
        val custom = labels
        if (custom != null) {
            label.textAlign = Paint.Align.CENTER
            for (i in 0 until minOf(n, custom.size)) {
                val text = custom[i] ?: continue
                val half = label.measureText(text) / 2f
                val x = (slot * i + slot / 2f).coerceIn(half, width - half)
                canvas.drawText(text, x, height - fm.descent, label)
            }
            label.textAlign = Paint.Align.LEFT
            return
        }
        var hour = 0
        while (hour < n) {
            canvas.drawText(String.format(Locale.US, "%02d", hour), slot * hour + pad, height - fm.descent, label)
            hour += 6
        }
    }
}

/** Uyku şeridi: bölümler süreleriyle orantılı; derin, hafif ve uyanık ayrı renkte. Uçlar tam yuvarlak. */
class SleepStripView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val deep = context.getColor(R.color.sleep_deep)
    private val light = context.getColor(R.color.sleep_light)
    private val awake = context.getColor(R.color.raised)
    private var segments: List<SleepSegment> = emptyList()

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        clipToOutline = true
    }

    /** Başlangıç zamanına göre sıralı bölümler (HealthView.sleepSegmentsFor). */
    fun set(ordered: List<SleepSegment>) {
        segments = ordered
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = segments.sumOf { it.minutes }
        if (total <= 0 || width == 0) return
        var x = 0f
        for (s in segments) {
            val w = width * s.minutes.toFloat() / total.toFloat()
            paint.color = when (s.type) {
                SleepSegment.DEEP -> deep
                SleepSegment.LIGHT -> light
                else -> awake
            }
            canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
            x += w
        }
    }
}

/** Küçük nabız eğrisi: günün ölçümleri, zamana göre soldan sağa. Tek ölçüm bir noktadır. */
class SparkView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var minutes = IntArray(0)
    private var bpm = IntArray(0)

    init {
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2f * density
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        line.color = context.getColor(R.color.heart)
        dot.color = context.getColor(R.color.heart)
    }

    /** Zamana göre sıralı (günün dakikası, nabız) çiftleri. */
    fun set(points: List<Pair<Int, Int>>) {
        minutes = IntArray(points.size) { points[it].first }
        bpm = IntArray(points.size) { points[it].second }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = minutes.size
        if (n == 0 || width == 0 || height == 0) return
        val inset = line.strokeWidth
        val w = width - inset * 2f
        val h = height - inset * 2f
        if (w <= 0f || h <= 0f) return
        var lo = bpm.minOrNull() ?: 0
        var hi = bpm.maxOrNull() ?: 0
        if (hi - lo < 10) {
            lo -= 5
            hi += 5
        }
        val t0 = minutes[0]
        val span = maxOf(minutes[n - 1] - t0, 1)
        if (n == 1) {
            canvas.drawCircle(width / 2f, height / 2f, line.strokeWidth * 1.5f, dot)
            return
        }
        path.reset()
        for (i in 0 until n) {
            val x = inset + w * (minutes[i] - t0).toFloat() / span.toFloat()
            val y = inset + h * (hi - bpm[i]).toFloat() / (hi - lo).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
    }
}

/**
 * Yuvarlak kadran önizlemesi. Resim daireye kırpılır; çevresinde ince halka.
 * selected: nane halka (saatteki kadran). Resim yoksa "+" çizilir (ekle kutusu).
 */
class FaceThumbView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.raised) }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        strokeCap = Paint.Cap.ROUND
    }
    private val matrix = android.graphics.Matrix()
    private var bitmap: android.graphics.Bitmap? = null
    private var selected = false
    private var progress = -1

    fun set(bmp: android.graphics.Bitmap?, isSelected: Boolean) {
        bitmap = bmp
        selected = isSelected
        img.shader = bmp?.let { android.graphics.BitmapShader(it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP) }
        invalidate()
    }

    /** Yükleme sırasında halka ilerleme gösterir (0-100); -1 kapalı. */
    fun setProgress(p: Int) {
        progress = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val stroke = (if (selected || progress >= 0) 2.5f else 1f) * density
        val gap = 3f * density
        val r = size / 2f - stroke - gap
        val b = bitmap
        if (b != null) {
            val scale = (2f * r) / b.width
            matrix.setScale(scale, scale)
            matrix.postTranslate(cx - r, cy - r)
            img.shader?.setLocalMatrix(matrix)
            canvas.drawCircle(cx, cy, r, img)
        } else {
            canvas.drawCircle(cx, cy, r, fill)
            val arm = r * 0.32f
            plus.strokeWidth = 2.5f * density
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plus)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plus)
        }
        ring.strokeWidth = stroke
        val rr = size / 2f - stroke / 2f
        if (progress >= 0) {
            ring.color = context.getColor(R.color.raised)
            canvas.drawCircle(cx, cy, rr, ring)
            ring.color = context.getColor(R.color.accent)
            ring.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f, 360f * progress / 100f, false, ring)
        } else {
            ring.color = context.getColor(if (selected) R.color.accent else R.color.border)
            canvas.drawCircle(cx, cy, rr, ring)
        }
    }
}
