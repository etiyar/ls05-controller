package com.che.ls05.protocol

import java.text.DateFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * .tbwf (Hello Haylou tasarım dosyası) derlemesinin saf kuralları. Android çizimi uygulamada;
 * burada yalnızca tablolar, örnek metinler, hizalama, hücre ölçüsü ve ibre geometrisi var (yerelde test edilir).
 */

object TbwfSpec {

    const val SCREEN = 240

    /** Rakam karelerinin sırası. Virgül, kadran dilinin ondalık ayırıcısıyla çizilir. */
    const val CHARSET = "-,/0123456789:"

    enum class Kind {
        /** Rakam alanı, 14 kare. */
        NUMBER,
        /** Gün adı, 7 kare (Pazar ilk). */
        DAY_OF_WEEK,
        /** Tam ekran ibre. */
        HAND,
        /** Arka plan. */
        DIAL,
        /** Ayırıcı (":" ya da tarih ayırıcısı); arka plana çizilir. */
        SEPARATOR,
        /** Birim işareti (% ya da °); arka plana çizilir. */
        SIGN,
        /** Sabit simge (adım, kalori, mesafe, nabız); arka plana çizilir. */
        STATIC_ICON,
        /** Durum simgesi (pil, bağlantı, hava, hedef); aşama 2. */
        ICON,
    }

    class Element(val name: String, val type: Int, val kind: Kind, val frames: Int, val label: String)

    val ELEMENTS: Map<String, Element> = listOf(
        Element("dial", 17, Kind.DIAL, 1, "Arka plan"),
        Element("hour", 4, Kind.NUMBER, 14, "Saat"),
        Element("minute", 5, Kind.NUMBER, 14, "Dakika"),
        Element("second", 6, Kind.NUMBER, 14, "Saniye"),
        Element("month", 11, Kind.NUMBER, 14, "Ay"),
        Element("day", 13, Kind.NUMBER, 14, "Gün"),
        Element("heartRate", 19, Kind.NUMBER, 14, "Nabız"),
        Element("weatherTemperature", 21, Kind.NUMBER, 14, "Sıcaklık"),
        Element("step", 25, Kind.NUMBER, 14, "Adım"),
        Element("year", 27, Kind.NUMBER, 14, "Yıl"),
        Element("calorie", 30, Kind.NUMBER, 14, "Kalori"),
        Element("battery", 33, Kind.NUMBER, 14, "Pil yüzdesi"),
        Element("distance", 47, Kind.NUMBER, 14, "Mesafe"),
        Element("dayOfWeek", 15, Kind.DAY_OF_WEEK, 7, "Gün adı"),
        Element("hourHand", 1, Kind.HAND, 1, "Akrep"),
        Element("minuteHand", 2, Kind.HAND, 1, "Yelkovan"),
        Element("secondHand", 3, Kind.HAND, 1, "Saniye ibresi"),
        Element("timeSeparator", 0, Kind.SEPARATOR, 1, "Saat ayırıcısı"),
        Element("dateSeparator", 0, Kind.SEPARATOR, 1, "Tarih ayırıcısı"),
        Element("yearSeparator", 0, Kind.SEPARATOR, 1, "Yıl ayırıcısı"),
        Element("batteryPercentSign", 0, Kind.SIGN, 1, "Yüzde işareti"),
        Element("weatherTemperatureUnitSign", 0, Kind.SIGN, 1, "Derece işareti"),
        Element("stepResource", 0, Kind.STATIC_ICON, 1, "Adım simgesi"),
        Element("calorieResource", 0, Kind.STATIC_ICON, 1, "Kalori simgesi"),
        Element("distanceResource", 0, Kind.STATIC_ICON, 1, "Mesafe simgesi"),
        Element("heartRateResource", 0, Kind.STATIC_ICON, 1, "Nabız simgesi"),
        Element("batteryResource", 8, Kind.ICON, 5, "Pil simgesi"),
        Element("connectionResource", 9, Kind.ICON, 2, "Bağlantı simgesi"),
        Element("weatherResource", 20, Kind.ICON, 16, "Hava durumu simgesi"),
        Element("stepGoalProgressResource", 32, Kind.ICON, 11, "Adım hedefi simgesi"),
    ).associateBy { it.name }

    /** Hello Haylou 3.7.19 katman sırası (çizim sırası; önizleme bileşimi bu sırayla yapılır). */
    val LAYER: Map<Int, Int> = mapOf(
        10 to 0, 17 to 10, 19 to 200, 47 to 300, 30 to 400, 32 to 500, 25 to 510, 20 to 610, 21 to 620,
        8 to 710, 33 to 720, 9 to 800, 15 to 920, 27 to 930, 11 to 940, 13 to 950,
        4 to 1010, 5 to 1020, 6 to 1030, 1 to 2000, 2 to 2010, 3 to 2020,
    )

    /** Birim işaretini örnek metninde taşıyan rakam alanları (properties & 4). */
    fun inlineSign(name: String, properties: Int): Boolean =
        (name == "battery" || name == "weatherTemperature") && (properties and 4) != 0

    /** Birim işareti metni: properties & 1 ise "°C", değilse "°". */
    fun unitSign(properties: Int, character: Boolean = false): String =
        if ((properties and 1) != 0) (if (character) "℃" else "°C") else "°"

    /**
     * Hello Haylou'nun yerleşim için kullandığı örnek metin (hizalama kaydırması bu uzunlukla hesaplanır).
     * Tanımsız ad için null.
     */
    fun sample(name: String, properties: Int, texts: FaceTexts): String? = when (name) {
        "hour" -> "20"
        "minute" -> "42"
        "second" -> "35"
        "year" -> texts.year
        "month" -> "11"
        "day" -> "26"
        "step" -> "12345"
        "calorie" -> "6789"
        "distance" -> "19,87".replace(",", texts.decimal)
        "heartRate" -> "106"
        "battery" -> if (inlineSign(name, properties)) "100%" else "100"
        "weatherTemperature" -> if (inlineSign(name, properties)) "-10°" else "-10"
        "timeSeparator" -> ":"
        "dateSeparator", "yearSeparator" -> texts.dateSeparator
        "batteryPercentSign" -> "%"
        "weatherTemperatureUnitSign" -> unitSign(properties)
        else -> null
    }

    /** Hücre ölçüsünün hesaplandığı karakter seti (ayırıcılarda set + ayırıcı). */
    fun measureSet(name: String, properties: Int, texts: FaceTexts): String {
        val digits = CHARSET.replace(",", texts.decimal)
        return when (ELEMENTS[name]?.kind) {
            Kind.NUMBER -> digits
            Kind.SEPARATOR -> if (name == "timeSeparator") digits else digits + texts.dateSeparator
            else -> sample(name, properties, texts) ?: digits
        }
    }

    /** Rakam karelerinin metinleri (14 kare, CHARSET sırasıyla). */
    fun frameTexts(texts: FaceTexts): List<String> = CHARSET.replace(",", texts.decimal).map { it.toString() }

    /** Hizalama: properties & 0x70 eşitlikle; 0x20 orta, 0x40 sağ, diğerleri sol. */
    fun align(properties: Int): Int = when (properties and 0x70) {
        0x20 -> 1
        0x40 -> 2
        else -> 0
    }

    /** x kaydırması (piksel): ortada hücreEn*uzunluk/2, sağda hücreEn*uzunluk sola. */
    fun xShift(properties: Int, cellWidth: Int, sampleLength: Int): Double = when (align(properties)) {
        1 -> -cellWidth.toDouble() * sampleLength / 2.0
        2 -> -cellWidth.toDouble() * sampleLength
        else -> 0.0
    }

    /** Öğenin ekran x'i: round(240*x + kaydırma). */
    fun screenX(x: Double, properties: Int, cellWidth: Int, sampleLength: Int): Int =
        Math.round(SCREEN * x + xShift(properties, cellWidth, sampleLength)).toInt()

    fun screenY(y: Double): Int = Math.round(SCREEN * y).toInt()

    /** Gün adı biçimi: &4 dar, (&1 yok ve &2 var) tam, diğer her durum kısa. */
    fun dayPattern(properties: Int): String = when {
        (properties and 4) != 0 -> "EEEEE"
        (properties and 1) == 0 && (properties and 2) != 0 -> "EEEE"
        else -> "EEE"
    }

    /** Gün adının hücre içindeki x'i (hücre içinde hizalama; kadranda kaydırma yok). */
    fun dayOffset(properties: Int, cellWidth: Int, textWidth: Int): Float = when (align(properties)) {
        1 -> (cellWidth - textWidth) / 2f
        2 -> (cellWidth - textWidth).toFloat()
        else -> 0f
    }

    /** Önizleme ve doğrulama için türe göre örnek değer (10:08:36). */
    fun previewText(type: Int): String? = when (type) {
        4 -> "10"
        5 -> "08"
        6 -> "36"
        11 -> "11"
        13 -> "26"
        27 -> "2026"
        19 -> "106"
        21 -> "-10"
        25 -> "12345"
        30 -> "6789"
        33 -> "100"
        47 -> "19,87"
        else -> null
    }

    /** Bir karakterin rakam karesi (ondalık ayırıcı nokta da olsa virgül karesine düşer); yoksa -1. */
    fun frameOf(c: Char): Int = if (c == '.') 1 else CHARSET.indexOf(c)

    /** Kadran için ibre açıları (derece, saat yönünde): saat, dakika, saniye. */
    fun handAngles(h: Int, m: Int, s: Int): DoubleArray = doubleArrayOf(
        ((h % 12) + m / 60.0 + s / 3600.0) * 30.0,
        (m + s / 60.0) * 6.0,
        s * 6.0,
    )

    /** resourceCharacter dosya adları (Hello Haylou karakter bölümü). */
    fun characterFile(c: Char): String? = when (c) {
        in '0'..'9' -> "_$c"
        ',' -> "_comma"
        '.' -> "_dot"
        ':' -> "_colon"
        '-' -> "_hyphen"
        '/' -> "_slash"
        '%' -> "_percent"
        '°' -> "_degree"
        '℃' -> "_celsius"
        '℉' -> "_fahrenheit"
        else -> null
    }

    /** resourceCharacter klasörü: "/resourceCharacter/<ad>/" (baştaki "/" olmadan). */
    fun characterDir(resource: String): String {
        val r = resource.trim().trimStart('/')
        val base = if (r.startsWith("resourceCharacter/")) r else "resourceCharacter/$r"
        return if (base.endsWith("/")) base else "$base/"
    }
}

/** Kadran diline bağlı metinler (Hello Haylou'nun yerel ayarlarıyla aynı kaynaklar). */
class FaceTexts(val locale: Locale, now: Date = Date()) {
    val decimal: String
    val dateSeparator: String
    val year: String
    /** Pazar ilk; dar, kısa, tam. */
    val narrow = Array(7) { "" }
    val short = Array(7) { "" }
    val full = Array(7) { "" }

    init {
        val sep = DecimalFormatSymbols.getInstance(locale).decimalSeparator.toString()
        decimal = if (sep.length == 1) sep else ","
        val pattern = (DateFormat.getDateInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern().orEmpty()
            .replace(Regex("\\s"), "")
        dateSeparator = Regex("[-./]").find(pattern)?.value ?: "/"
        val cal = GregorianCalendar.getInstance(locale)
        cal.time = now
        year = SimpleDateFormat("yyyy", locale).format(cal.time)
        cal.set(Calendar.HOUR_OF_DAY, cal.getActualMinimum(Calendar.HOUR_OF_DAY))
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        val fn = SimpleDateFormat("EEEEE", locale)
        val fs = SimpleDateFormat("EEE", locale)
        val ff = SimpleDateFormat("EEEE", locale)
        for (i in 0 until 7) {
            narrow[i] = fn.format(cal.time)
            short[i] = fs.format(cal.time)
            full[i] = ff.format(cal.time)
            cal.add(Calendar.DAY_OF_WEEK, 1)
        }
    }

    fun dayNames(properties: Int): Array<String> = when (TbwfSpec.dayPattern(properties)) {
        "EEEEE" -> narrow
        "EEEE" -> full
        else -> short
    }

    companion object {
        /** Varsayılan kadran dili: telefon Türkçeyse Türkçe, değilse İngilizce. */
        fun defaultLocale(phone: Locale = Locale.getDefault()): Locale =
            if (phone.language == "tr") Locale.forLanguageTag("tr-TR") else Locale.ENGLISH
    }
}

/** Bir karakterin (ya da metnin) çizim sınırları; Android Rect ile aynı anlam (üst negatif, taban 0). */
class GlyphBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Rakam hücresi: üst (taban çizgisine göre, negatif), alt ve genişlik. */
class Cell(val top: Int, val bottom: Int, val width: Int) {
    val height: Int get() = bottom - top
}

object CellLayout {

    /**
     * Rakam hücresi (Hello Haylou): üst = setin ve tek tek karakterlerin en küçük üstü, alt = en büyük alt,
     * genişlik = en geniş karakter.
     */
    fun digits(whole: GlyphBox, chars: List<GlyphBox>): Cell {
        var top = whole.top
        var bottom = whole.bottom
        var w = 0
        for (c in chars) {
            if (c.top < top) top = c.top
            if (c.bottom > bottom) bottom = c.bottom
            if (c.width > w) w = c.width
        }
        return Cell(top, bottom, w)
    }

    /** Karakterin hücrede ortalı çizim başlangıcı (drawText x, y). */
    fun glyphOrigin(cell: Cell, g: GlyphBox): FloatArray =
        floatArrayOf((cell.width - g.width) / 2f - g.left, -cell.top.toFloat())

    /** Gün adı hücresi: yazı tipi yükseklik ölçüleri ve adların sınırları. */
    fun text(ascent: Float, descent: Float, names: List<GlyphBox>): Cell {
        var h = (ceil(descent.toDouble()) - floor(ascent.toDouble())).toInt()
        var top = floor(ascent.toDouble()).toInt()
        var w = 0
        for (n in names) {
            if (n.height > h) h = n.height
            if (n.top < top) top = n.top
            if (n.width > w) w = n.width
        }
        return Cell(top, top + h, w)
    }

    /** resourceCharacter resminin boyu: uzun kenar = 240*size. */
    fun imageSize(srcW: Int, srcH: Int, size: Double): IntArray {
        val long = TbwfSpec.SCREEN * size
        val ratio = srcW.toDouble() / srcH
        return if (ratio >= 1.0) intArrayOf(long.roundToInt(), (long / ratio).roundToInt())
        else intArrayOf((ratio * long).roundToInt(), long.roundToInt())
    }
}

/** Tam ekran ibre yerleşimi ve tablo kuyruğu (Hello Haylou formülü). */
class HandBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val tail: ByteArray)

object HandGeometry {

    /** Göbek boyu oranı (akrep, yelkovan, saniye). */
    fun hubRatio(type: Int): Double = when (type) {
        1 -> 0.95
        2 -> 0.75
        3 -> 0.55
        else -> 1.0
    }

    /** Pivot kaydırması (piksel): yalnızca properties tam olarak 2 ya da 4 ise 240*pivotY. */
    fun pivot(properties: Int, pivotY: Double): Double =
        if (properties == 2 || properties == 4) TbwfSpec.SCREEN * pivotY else 0.0

    /**
     * w x h ibre resmi; pivot piksel; hub göbek boyu (0 = göbek yok).
     * Kuyruk: [min(H2,255), H2+t (255'e kırpılır, 0 altı 0), w & 0xFF].
     */
    fun box(w: Int, h: Int, pivot: Double, hub: Int = 0): HandBox {
        val s = TbwfSpec.SCREEN
        val left = Math.round((s - w) / 2f)
        val top = Math.round((s / 2f - h) - pivot.toFloat())
        val right = Math.round((s - w) / 2f + w)
        val bottom = Math.round(s / 2f - pivot.toFloat())
        var h2 = h
        var t = pivot.toFloat()
        if (hub > 0) {
            val half = hub / 2f
            val p = pivot.toFloat()
            when {
                p == 0f -> h2 = ceil(half.toDouble()).toInt() + h
                p < 0f -> if (-half < p) h2 = ceil((half + p).toDouble()).toInt() + h
                else -> h2 = ceil((half + p).toDouble()).toInt() + h
            }
            t = -half
        }
        val ti = Math.round(t)
        val b0 = if (h2 < 255) h2 else 255
        val b1 = (h2 + ti).coerceIn(0, 255)
        return HandBox(left, top, right, bottom, byteArrayOf(b0.toByte(), b1.toByte(), (w and 0xFF).toByte()))
    }
}

/** Saf ARGB bileşim (önizleme ve doğrulama). */
object FaceCompose {

    class Layer(val type: Int, val x: Int, val y: Int, val width: Int, val height: Int, val pixels: IntArray)

    /** src-over karışım. */
    fun over(dst: Int, src: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return dst
        if (sa == 255) return src
        val da = (dst ushr 24) and 0xFF
        val oa = sa + da * (255 - sa) / 255
        if (oa == 0) return 0
        fun ch(shift: Int): Int {
            val s = (src shr shift) and 0xFF
            val d = (dst shr shift) and 0xFF
            return ((s * sa + d * da * (255 - sa) / 255) / oa).coerceIn(0, 255)
        }
        return (oa shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun draw(canvas: IntArray, size: Int, l: Layer) {
        for (yy in 0 until l.height) {
            val ty = l.y + yy
            if (ty !in 0 until size) continue
            for (xx in 0 until l.width) {
                val tx = l.x + xx
                if (tx !in 0 until size) continue
                val i = ty * size + tx
                canvas[i] = over(canvas[i], l.pixels[yy * l.width + xx])
            }
        }
    }

    /** Tam ekran resmi merkez etrafında saat yönünde döndürüp çizer (en yakın komşu). */
    fun drawRotated(canvas: IntArray, size: Int, src: IntArray, degrees: Double) {
        val a = Math.toRadians(degrees)
        val cos = Math.cos(a)
        val sin = Math.sin(a)
        val c = size / 2.0
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x + 0.5 - c
            val dy = y + 0.5 - c
            val sx = Math.floor(c + dx * cos + dy * sin).toInt()
            val sy = Math.floor(c - dx * sin + dy * cos).toInt()
            if (sx !in 0 until size || sy !in 0 until size) continue
            val i = y * size + x
            canvas[i] = over(canvas[i], src[sy * size + sx])
        }
    }

    /** Merkez etrafında ölçekler (çift doğrusal); dışarısı saydam. */
    fun scale(src: IntArray, size: Int, factor: Double): IntArray {
        val out = IntArray(size * size)
        val c = size / 2.0
        for (y in 0 until size) for (x in 0 until size) {
            val sx = (x + 0.5 - c) / factor + c - 0.5
            val sy = (y + 0.5 - c) / factor + c - 0.5
            if (sx < -0.5 || sy < -0.5 || sx > size - 0.5 || sy > size - 0.5) continue
            val x0 = Math.floor(sx).toInt()
            val y0 = Math.floor(sy).toInt()
            val fx = sx - x0
            val fy = sy - y0
            fun px(xx: Int, yy: Int): Int = src[yy.coerceIn(0, size - 1) * size + xx.coerceIn(0, size - 1)]
            val p = intArrayOf(px(x0, y0), px(x0 + 1, y0), px(x0, y0 + 1), px(x0 + 1, y0 + 1))
            val w = doubleArrayOf((1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy)
            var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
            for (k in 0..3) {
                val pa = ((p[k] ushr 24) and 0xFF) * w[k]
                a += pa
                r += ((p[k] shr 16) and 0xFF) * pa
                g += ((p[k] shr 8) and 0xFF) * pa
                b += (p[k] and 0xFF) * pa
            }
            if (a < 0.5) continue
            out[y * size + x] = (Math.round(a).toInt().coerceIn(0, 255) shl 24) or
                (Math.round(r / a).toInt().coerceIn(0, 255) shl 16) or
                (Math.round(g / a).toInt().coerceIn(0, 255) shl 8) or
                Math.round(b / a).toInt().coerceIn(0, 255)
        }
        return out
    }

    /**
     * Baskın renk (Hello Haylou yöntemi): kutudaki pikseller RGB565'e indirgenip sayılır, en sık rengin
     * yarısından fazla görülen renklerin ağırlıklı karesel ortalaması. Kutu boşsa siyah.
     */
    fun dominant(pixels: IntArray, size: Int, x: Int, y: Int, w: Int, h: Int): Int {
        val counts = HashMap<Int, Int>()
        for (yy in y until y + h) for (xx in x until x + w) {
            if (xx !in 0 until size || yy !in 0 until size) continue
            val p = pixels[yy * size + xx]
            val key = (p and 0xFF000000.toInt()) or ((((p shr 16) and 0xFF) shr 3) shl 11) or
                ((((p shr 8) and 0xFF) shr 2) shl 5) or ((p and 0xFF) shr 3)
            counts[key] = (counts[key] ?: 0) + 1
        }
        if (counts.isEmpty()) return 0xFF000000.toInt()
        val max = counts.values.max()
        var n = 0L; var r = 0L; var g = 0L; var b = 0L
        for ((k, c) in counts) {
            if (c < max / 2) continue
            val rr = ((k shr 11) and 31) * 255 / 31
            val gg = ((k shr 5) and 63) * 255 / 63
            val bb = (k and 31) * 255 / 31
            n += c; r += rr.toLong() * rr * c; g += gg.toLong() * gg * c; b += bb.toLong() * bb * c
        }
        fun root(v: Long) = Math.round(Math.sqrt(v.toDouble() / n)).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (root(r) shl 16) or (root(g) shl 8) or root(b)
    }

    /**
     * Öğelerden kadran görüntüsü: katman sırası, rakamlar örnek değerle, ibreler 10:08:36.
     * frame(type, index) öğenin bir karesini verir.
     */
    fun compose(
        size: Int,
        elements: List<FaceElement>,
        frame: (FaceElement, Int) -> IntArray,
    ): IntArray {
        val out = IntArray(size * size)
        val angles = TbwfSpec.handAngles(10, 8, 36)
        val ordered = elements.filter { it.type != 10 }.sortedBy { TbwfSpec.LAYER[it.type] ?: 1500 }
        var sample = 0
        for (e in ordered) {
            if (e.type in 1..3 && e.width == size && e.height == size) {
                drawRotated(out, size, frame(e, 0), angles[e.type - 1])
                continue
            }
            val text = TbwfSpec.previewText(e.type)
            if (text != null && e.frames == 14) {
                for ((i, ch) in text.withIndex()) {
                    val f = TbwfSpec.frameOf(ch)
                    if (f < 0) continue
                    draw(out, size, Layer(e.type, e.x + i * e.width, e.y, e.width, e.height, frame(e, f)))
                }
                continue
            }
            val idx = when {
                e.frames == 7 -> 4
                e.frames >= 13 -> 3 + (sample++ * 3 + 1) % 10
                e.frames > 1 -> e.frames / 2
                else -> 0
            }.coerceIn(0, maxOf(1, e.frames) - 1)
            draw(out, size, Layer(e.type, e.x, e.y, e.width, e.height, frame(e, idx)))
        }
        return out
    }
}
