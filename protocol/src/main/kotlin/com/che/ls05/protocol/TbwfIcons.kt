package com.che.ls05.protocol

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * .tbwf simgeleri: kare eşlemeleri, klasör sıralaması, hücre ölçüsü (saf kurallar) ve
 * yerleşik simgelerimizi çizmek için küçük bir vektör çizici (Android'e bağlı değil; yerelde test edilir).
 */

object TbwfIcons {

    /** Hava durumu: 18 kaynak kareden saatin 16 karesi (kare sırası = saatin hava kodu). */
    val WEATHER_REMAP = intArrayOf(0, 1, 2, 3, 4, 5, 8, 6, 7, 10, 14, 12, 11, 15, 16, 17)

    /** Pil: 10'dan fazla kaynak kareden 5 kare. */
    val BATTERY_REMAP = intArrayOf(0, 3, 5, 7, 10)

    val DAY_FILES = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    /**
     * Kaynak karelerden saatin karelerine eşleme (çıktı karesi i = kaynak kare map[i]).
     * Sayı tutmuyorsa null.
     */
    fun remap(element: String, sourceFrames: Int): IntArray? {
        val want = TbwfSpec.ELEMENTS[element]?.frames ?: return null
        val map = when {
            element == "batteryResource" && sourceFrames > 10 -> BATTERY_REMAP
            element == "weatherResource" && sourceFrames == 18 -> WEATHER_REMAP
            else -> IntArray(sourceFrames) { it }
        }
        return if (map.size == want && map.all { it in 0 until sourceFrames }) map else null
    }

    /** "<öğe>_ic_wf_x.png" → "ic_wf_x" (Hello Haylou yerleşik simge adı). */
    fun builtinName(element: String, resource: String): String {
        var r = resource.trim().substringAfterLast('/')
        if (r.startsWith(element + "_")) r = r.substring(element.length + 1)
        if (r.endsWith(".png", ignoreCase = true)) r = r.substring(0, r.length - 4)
        return r
    }

    /** Sondaki sayı (percent_10 → 10); yoksa null. */
    fun trailingNumber(name: String): Int? = Regex("(\\d+)$").find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** Adları sondaki sayıya göre sıralar (percent_0, percent_10, ..., percent_100). */
    fun numericSort(names: Collection<String>): List<String> =
        names.sortedWith(compareBy<String> { trailingNumber(it) ?: Int.MAX_VALUE }.thenBy { it })

    /**
     * Klasördeki resim adlarından (uzantısız) kare sırası. Tanınmazsa null.
     * Pil ve hedef: percent_N sayısal; bağlantı: connected, disconnected; gün adı: sunday…saturday;
     * hava: adların hepsi sayıyla bitiyorsa sayısal.
     */
    fun folderOrder(element: String, names: Collection<String>): List<String>? {
        val set = names.toSet()
        return when (element) {
            "batteryResource", "stepGoalProgressResource" -> {
                val p = names.filter { Regex("percent_\\d+").matches(it) }
                if (p.isEmpty()) null else numericSort(p)
            }
            "connectionResource" -> {
                val on = listOf("connected", "connect").firstOrNull { it in set }
                val off = listOf("disconnected", "disconnect").firstOrNull { it in set }
                if (on != null && off != null) listOf(on, off) else null
            }
            "dayOfWeek" -> if (DAY_FILES.all { it in set }) DAY_FILES else null
            "weatherResource" -> if (names.isNotEmpty() && names.all { trailingNumber(it) != null }) numericSort(names) else null
            else -> null
        }
    }

    /** Simge karesi boyu: uzun kenar = round(240*size), en/boy oranı korunur (Hello Haylou float hesabı). */
    fun frameSize(srcW: Float, srcH: Float, size: Double): IntArray {
        val long = (TbwfSpec.SCREEN * size).toFloat()
        val ratio = srcW / srcH
        return if (ratio >= 1f) intArrayOf(Math.round(long), Math.round(long / ratio))
        else intArrayOf(Math.round(ratio * long), Math.round(long))
    }

    /** Hücre: en geniş kare x en yüksek kare. */
    fun cell(sizes: List<IntArray>): IntArray =
        intArrayOf(sizes.maxOfOrNull { it[0] } ?: 0, sizes.maxOfOrNull { it[1] } ?: 0)

    /** Karenin hücre içindeki sol üstü (ortalı). */
    fun offset(cellW: Int, cellH: Int, w: Int, h: Int): IntArray =
        intArrayOf(Math.round((cellW - w) / 2f), Math.round((cellH - h) / 2f))

    /** İbre göbeği rengi: geçişlerde son renk, düz renkte ilk renk; 0 = boyanmaz. */
    fun hubColor(gradient: String, colors: IntArray): Int = when (gradient) {
        "linear_horizontal", "linear_vertical", "radial" -> colors.lastOrNull() ?: 0
        "solid" -> colors.firstOrNull() ?: 0
        else -> 0
    }
}

// ---------- Vektör çizim ----------

/** Vektör resim: vw x vh görüntü alanında sırayla çizilen beyaz şekiller. */
class Art(val vw: Float, val vh: Float, val shapes: List<ArtShape>)

/**
 * Şekil: alt yollar (x,y çiftleri, düzleştirilmiş). stroke 0 ise dolgu (herhangi bir alt yolun içi),
 * değilse yuvarlak uçlu çizgi. clear: alttakini siler.
 */
class ArtShape(val subs: List<FloatArray>, val stroke: Float, val alpha: Float, val clear: Boolean)

class ArtBuilder(private val vw: Float, private val vh: Float) {
    private val shapes = ArrayList<ArtShape>()

    class Path {
        internal val subs = ArrayList<FloatArray>()
        private var cur: ArrayList<Float>? = null
        private var lx = 0f
        private var ly = 0f

        private fun flush() {
            cur?.let { if (it.size >= 4) subs += it.toFloatArray() }
            cur = null
        }

        fun m(x: Float, y: Float) = apply { flush(); cur = arrayListOf(x, y); lx = x; ly = y }
        fun l(x: Float, y: Float) = apply {
            if (cur == null) cur = arrayListOf(lx, ly)
            cur!!.add(x); cur!!.add(y); lx = x; ly = y
        }

        fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = apply {
            val x0 = lx; val y0 = ly
            for (i in 1..16) {
                val t = i / 16f; val u = 1 - t
                l(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x,
                    u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y)
            }
        }

        fun q(x1: Float, y1: Float, x: Float, y: Float) = apply {
            val x0 = lx; val y0 = ly
            for (i in 1..12) {
                val t = i / 12f; val u = 1 - t
                l(u * u * x0 + 2 * u * t * x1 + t * t * x, u * u * y0 + 2 * u * t * y1 + t * t * y)
            }
        }

        /** Yay: açılar derece, 0 sağ, saat yönünde artar (ekran koordinatı). Yolu yayın başına bağlar. */
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, move: Boolean = false) = apply {
            val n = max(6, ceil(Math.abs(sweep) / 6f).toInt())
            for (i in 0..n) {
                val a = Math.toRadians((start + sweep * i / n).toDouble())
                val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
                if (i == 0 && (move || cur == null)) m(x, y) else l(x, y)
            }
        }

        fun z() = apply {
            val c = cur ?: return@apply
            if (c.size >= 4 && (c[0] != lx || c[1] != ly)) l(c[0], c[1])
            flush()
        }

        fun circle(cx: Float, cy: Float, r: Float) = apply { flush(); arc(cx, cy, r, 0f, 360f, move = true); z() }

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) = apply {
            flush()
            for (i in 0..48) {
                val a = Math.PI * 2 * i / 48
                val x = cx + rx * cos(a).toFloat(); val y = cy + ry * sin(a).toFloat()
                if (i == 0) m(x, y) else l(x, y)
            }
            z()
        }

        fun rect(x: Float, y: Float, w: Float, h: Float) = apply { m(x, y); l(x + w, y); l(x + w, y + h); l(x, y + h); z() }

        fun rrect(x: Float, y: Float, w: Float, h: Float, r0: Float) = apply {
            val r = min(r0, min(w, h) / 2)
            if (r <= 0f) return rect(x, y, w, h)
            flush()
            arc(x + w - r, y + r, r, 270f, 90f, move = true)
            arc(x + w - r, y + h - r, r, 0f, 90f)
            arc(x + r, y + h - r, r, 90f, 90f)
            arc(x + r, y + r, r, 180f, 90f)
            z()
        }

        fun poly(vararg p: Float) = apply {
            m(p[0], p[1])
            var i = 2
            while (i + 1 < p.size) { l(p[i], p[i + 1]); i += 2 }
            z()
        }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = apply { m(x0, y0); l(x1, y1); flush() }

        internal fun done(): List<FloatArray> { flush(); return subs }
    }

    fun fill(alpha: Float = 1f, block: Path.() -> Unit) = add(0f, alpha, false, block)
    fun stroke(width: Float, alpha: Float = 1f, block: Path.() -> Unit) = add(width, alpha, false, block)
    fun clear(block: Path.() -> Unit) = add(0f, 1f, true, block)
    fun clearStroke(width: Float, block: Path.() -> Unit) = add(width, 1f, true, block)

    private fun add(w: Float, a: Float, clear: Boolean, block: Path.() -> Unit) {
        val subs = Path().apply(block).done()
        if (subs.isNotEmpty()) shapes += ArtShape(subs, w, a, clear)
    }

    fun build(): Art = Art(vw, vh, shapes.toList())
}

/** İki çemberin kesişimleri (yoksa null): [x1, y1, x2, y2]. */
fun circleIntersections(ax: Float, ay: Float, ar: Float, bx: Float, by: Float, br: Float): FloatArray? {
    val dx = bx - ax; val dy = by - ay
    val d = sqrt(dx * dx + dy * dy)
    if (d <= 0f || d > ar + br || d < Math.abs(ar - br)) return null
    val a = (ar * ar - br * br + d * d) / (2 * d)
    val h = sqrt(max(0f, ar * ar - a * a))
    val px = ax + a * dx / d; val py = ay + a * dy / d
    return floatArrayOf(px + h * dy / d, py - h * dx / d, px - h * dy / d, py + h * dx / d)
}

/** Nokta açısı (derece, 0 sağ, saat yönünde). */
fun angleOf(cx: Float, cy: Float, x: Float, y: Float): Float = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

/** Vektör resmi w x h piksele çizer (beyaz + alfa, ARGB). Resim kutuya oranı korunarak ortalanır. */
object ArtRaster {

    private class Prepared(val subs: List<FloatArray>, val half: Float, val alpha: Float, val clear: Boolean,
                           val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    fun render(art: Art, w: Int, h: Int, ss: Int = 4): IntArray {
        val out = IntArray(max(0, w * h))
        if (w <= 0 || h <= 0) return out
        val s = min(w / art.vw, h / art.vh)
        val ox = (w - art.vw * s) / 2f
        val oy = (h - art.vh * s) / 2f
        val prepared = art.shapes.map { sh ->
            val subs = sh.subs.map { p -> FloatArray(p.size) { i -> if (i % 2 == 0) ox + p[i] * s else oy + p[i] * s } }
            val half = sh.stroke * s / 2f
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (p in subs) {
                var i = 0
                while (i < p.size) { x0 = min(x0, p[i]); x1 = max(x1, p[i]); y0 = min(y0, p[i + 1]); y1 = max(y1, p[i + 1]); i += 2 }
            }
            Prepared(subs, half, sh.alpha.coerceIn(0f, 1f), sh.clear, x0 - half, y0 - half, x1 + half, y1 + half)
        }
        val n = ss * ss
        for (py in 0 until h) for (px in 0 until w) {
            // Pikselle kesişen şekiller.
            val hits = prepared.filter { it.x1 >= px && it.x0 <= px + 1 && it.y1 >= py && it.y0 <= py + 1 }
            if (hits.isEmpty()) continue
            var sum = 0f
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val x = px + (sx + 0.5f) / ss
                val y = py + (sy + 0.5f) / ss
                var a = 0f
                for (p in hits) {
                    if (x < p.x0 || x > p.x1 || y < p.y0 || y > p.y1) continue
                    val inside = if (p.half > 0f) nearStroke(p.subs, x, y, p.half) else insideAny(p.subs, x, y)
                    if (!inside) continue
                    a = if (p.clear) 0f else p.alpha + a * (1 - p.alpha)
                }
                sum += a
            }
            val alpha = Math.round(sum / n * 255f)
            if (alpha > 0) out[py * w + px] = (alpha shl 24) or 0xFFFFFF
        }
        return out
    }

    private fun insideAny(subs: List<FloatArray>, x: Float, y: Float): Boolean {
        for (p in subs) {
            var inside = false
            val m = p.size / 2
            var j = m - 1
            for (i in 0 until m) {
                val xi = p[i * 2]; val yi = p[i * 2 + 1]; val xj = p[j * 2]; val yj = p[j * 2 + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            if (inside) return true
        }
        return false
    }

    private fun nearStroke(subs: List<FloatArray>, x: Float, y: Float, half: Float): Boolean {
        val h2 = half * half
        for (p in subs) {
            var i = 0
            while (i + 3 < p.size) {
                val ax = p[i]; val ay = p[i + 1]; val bx = p[i + 2]; val by = p[i + 3]
                val dx = bx - ax; val dy = by - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 <= 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
                val ex = ax + t * dx - x; val ey = ay + t * dy - y
                if (ex * ex + ey * ey <= h2) return true
                i += 2
            }
            if (p.size == 2) {
                val ex = p[0] - x; val ey = p[1] - y
                if (ex * ex + ey * ey <= h2) return true
            }
        }
        return false
    }
}

/** Renklendirme (Hello Haylou: düz renk setTint, geçişler SRC_IN). Kaynak alfası korunur. */
object ArtTint {

    /** gradient: solid, linear_horizontal, linear_vertical, radial; bilinmeyen tür ya da düz 0 → değişmez. */
    fun apply(px: IntArray, w: Int, h: Int, gradient: String, colors: IntArray, positions: FloatArray?) {
        if (colors.isEmpty()) return
        val solid = gradient == "solid" || colors.size < 2
        if (solid) {
            if (gradient != "solid" && gradient !in GRADIENTS) return
            val c = colors[0]
            if (c == 0) return
            for (i in px.indices) px[i] = srcIn(px[i], c)
            return
        }
        if (gradient !in GRADIENTS) return
        val r = min(w / 2f, h / 2f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (px[i] == 0) continue
            val t = when (gradient) {
                "linear_horizontal" -> (x + 0.5f) / w
                "linear_vertical" -> (y + 0.5f) / h
                else -> {
                    val dx = x + 0.5f - w / 2f; val dy = y + 0.5f - h / 2f
                    if (r <= 0f) 1f else sqrt(dx * dx + dy * dy) / r
                }
            }
            px[i] = srcIn(px[i], colorAt(colors, positions, t))
        }
    }

    private val GRADIENTS = setOf("linear_horizontal", "linear_vertical", "radial")

    fun srcIn(src: Int, color: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return 0
        val a = sa * ((color ushr 24) and 0xFF) / 255
        return (a shl 24) or (color and 0xFFFFFF)
    }

    /** Geçiş rengi (CLAMP; konumlar yoksa eşit aralık). */
    fun colorAt(colors: IntArray, positions: FloatArray?, t0: Float): Int {
        val n = colors.size
        if (n == 1) return colors[0]
        val pos = if (positions != null && positions.size == n) positions else FloatArray(n) { it / (n - 1f) }
        val t = t0.coerceIn(0f, 1f)
        if (t <= pos[0]) return colors[0]
        for (i in 1 until n) {
            if (t <= pos[i]) {
                val span = pos[i] - pos[i - 1]
                val f = if (span <= 0f) 1f else (t - pos[i - 1]) / span
                return mix(colors[i - 1], colors[i], f)
            }
        }
        return colors[n - 1]
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = Math.round(((a ushr s) and 0xFF) * (1 - f) + ((b ushr s) and 0xFF) * f).coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** ARGB kareyi hücreye (cellW x cellH) ortalayarak koyar. */
fun placeInCell(src: IntArray, w: Int, h: Int, cellW: Int, cellH: Int): IntArray {
    val out = IntArray(cellW * cellH)
    val o = TbwfIcons.offset(cellW, cellH, w, h)
    for (y in 0 until h) {
        val ty = o[1] + y
        if (ty !in 0 until cellH) continue
        for (x in 0 until w) {
            val tx = o[0] + x
            if (tx in 0 until cellW) out[ty * cellW + tx] = src[y * w + x]
        }
    }
    return out
}

/** Vektör resmi hücre karesi olarak verir: boy, çizim, renk. */
object ArtFrames {
    /** Tek kare: (frameSize) w x h piksele çizilir ve renklendirilir. */
    fun draw(art: Art, w: Int, h: Int, gradient: String, colors: IntArray, positions: FloatArray?): IntArray {
        val px = ArtRaster.render(art, w, h)
        ArtTint.apply(px, w, h, gradient, colors, positions)
        return px
    }
}
