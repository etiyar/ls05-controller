package com.che.ls05

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import com.che.ls05.protocol.CellLayout
import com.che.ls05.protocol.FaceCompose
import com.che.ls05.protocol.FaceElement
import com.che.ls05.protocol.FaceFile
import com.che.ls05.protocol.FaceSprite
import com.che.ls05.protocol.FaceTexts
import com.che.ls05.protocol.FaceWriter
import com.che.ls05.protocol.GlyphBox
import com.che.ls05.protocol.HandGeometry
import com.che.ls05.protocol.Rgb565Encode
import com.che.ls05.protocol.TbwfSpec
import com.che.ls05.protocol.TbwfSpec.Kind
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.ceil

/**
 * .tbwf (Hello Haylou tasarım dosyası) → saat kadranı. Aşama 1: arka plan, rakam alanları, gün adı,
 * zip içindeki ibre resimleri, ayırıcılar ve sabit resimler. Desteklenmeyen öğeler uyarı listesine yazılır.
 * Ana iş parçacığında çağrılmamalı.
 */
class TbwfCompiler(private val ctx: Context, locale: Locale = FaceTexts.defaultLocale()) {

    class Output(val bytes: ByteArray, val warnings: List<String>, val name: String)

    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_TOTAL = 8 * 1024 * 1024
        const val MAX_PNG = 1024
        private const val S = TbwfSpec.SCREEN
        private const val JSON = "TiBoWaFe.json"

        /** "PK" ile başlayan ve TiBoWaFe.json girdisi olan zip (ad, zip dizininde düz metin durur). */
        fun isTbwf(b: ByteArray): Boolean {
            if (b.size < 4 || b[0] != 'P'.code.toByte() || b[1] != 'K'.code.toByte()) return false
            val key = JSON.toByteArray(Charsets.US_ASCII)
            outer@ for (i in 0..b.size - key.size) {
                for (j in key.indices) if (b[i + j] != key[j]) continue@outer
                return true
            }
            return false
        }
    }

    private val texts = FaceTexts(locale)
    private val warnings = ArrayList<String>()
    private lateinit var files: Map<String, ByteArray>
    private val typefaces = HashMap<String, Typeface>()
    private var fontDir: File? = null

    /** Rakam ve simge kutularının altındaki zemin rengi için. */
    private var dialSolid: Int? = null
    private lateinit var dialPx: IntArray

    fun compile(zip: ByteArray, id: Int, fallbackName: String): Result<Output> = try {
        Result.success(build(zip, id, fallbackName))
    } catch (e: TbwfException) {
        Result.failure(e)
    } catch (e: OutOfMemoryError) {
        Result.failure(TbwfException("Bellek yetmedi; dosya çok büyük."))
    } catch (e: Exception) {
        Result.failure(TbwfException("Tasarım dosyası okunamadı (${e.javaClass.simpleName})."))
    } finally {
        fontDir?.deleteRecursively()
        fontDir = null
    }

    class TbwfException(msg: String) : Exception(msg)

    private fun fail(msg: String): Nothing = throw TbwfException(msg)

    // ---------- Zip ----------

    private fun readZip(zip: ByteArray): Map<String, ByteArray> {
        if (Build.VERSION.SDK_INT >= 34) {
            // Girdiler "/" ile başlıyor; Android 14'ün yol denetimi bunları reddeder. Adları aşağıda kendimiz denetliyoruz.
            dalvik.system.ZipPathValidator.clearCallback()
        }
        val out = HashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val e = z.nextEntry ?: break
                if (++count > MAX_ENTRIES) fail("Tasarım dosyasında çok fazla girdi var.")
                val name = e.name.replace('\\', '/').trimStart('/')
                if (name.contains("..")) fail("Tasarım dosyasında geçersiz bir yol var.")
                if (e.isDirectory || name.isEmpty()) continue
                val bo = ByteArrayOutputStream()
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_TOTAL) fail("Tasarım dosyası açılınca ${MAX_TOTAL / 1024 / 1024} MB sınırını aşıyor.")
                    bo.write(buf, 0, n)
                }
                out[name] = bo.toByteArray()
            }
        }
        return out
    }

    private fun file(path: String): ByteArray? = files[path.trim().trimStart('/')]

    /** PNG'yi önce boyutuna bakarak çözer (en çok 1024x1024). */
    private fun png(path: String, label: String): Bitmap? {
        val b = file(path) ?: run { warnings += "$label: resim dosyası yok."; return null }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) { warnings += "$label: resim okunamadı."; return null }
        if (o.outWidth > MAX_PNG || o.outHeight > MAX_PNG) { warnings += "$label: resim ${MAX_PNG}x$MAX_PNG sınırından büyük."; return null }
        val d = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(b, 0, b.size, d)?.let {
            if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false)
        } ?: run { warnings += "$label: resim okunamadı."; null }
    }

    // ---------- Ana akış ----------

    private class Item(val name: String, val spec: TbwfSpec.Element, val json: JSONObject)

    private fun build(zip: ByteArray, id: Int, fallbackName: String): Output {
        files = readZip(zip)
        val jsonBytes = file(JSON) ?: fail("Tasarım dosyasında $JSON yok.")
        val root = JSONObject(String(jsonBytes, Charsets.UTF_8))
        val els = root.optJSONObject("elements") ?: fail("Tasarım dosyasında öğe yok.")
        val items = ArrayList<Item>()
        for (key in els.keys()) {
            val j = els.optJSONObject(key) ?: continue
            if (key != "dial" && !j.optBoolean("visible", false)) continue
            val spec = TbwfSpec.ELEMENTS[key]
            if (spec == null) {
                warnings += "Bilinmeyen öğe çizilmedi ($key)."
                continue
            }
            items += Item(key, spec, j)
        }

        // 1) Arka plan (üstüne ayırıcılar ve sabit resimler çizilecek).
        val dialItem = items.firstOrNull { x -> x.spec.kind == Kind.DIAL }
        val dial = drawDial(dialItem?.json)
        val dialCanvas = Canvas(dial)
        dialPx = IntArray(S * S).also { dial.getPixels(it, 0, S, 0, 0, S, S) }

        val sprites = ArrayList<FaceSprite>()
        val argb = HashMap<Int, List<IntArray>>()
        val elems = ArrayList<FaceElement>()

        fun add(type: Int, w: Int, h: Int, x: Int, y: Int, frames: List<IntArray>, cells: Int, label: String,
                tail: ByteArray = ByteArray(3), bg: Int) {
            if (x < 0 || y < 0 || x + w * cells > S || y + h > S) {
                warnings += "$label ekrana sığmadı, çizilmedi."
                return
            }
            if (sprites.any { it.type == type }) return
            val data = ByteArrayOutputStream()
            for (f in frames) data.write(Rgb565Encode.frames(f, bg))
            sprites += FaceSprite(type, w, h, x, y, frames.size, data.toByteArray(), tail, cells)
            argb[type] = frames
            elems += FaceElement(type, w, h, 0, x, y, frames.size, tail)
        }

        // 2) Arka plana çizilenler: ayırıcılar, işaretler, sabit resimler.
        for (item in items) {
            when (item.spec.kind) {
                Kind.SEPARATOR, Kind.SIGN -> drawSeparator(dialCanvas, item)
                Kind.STATIC_ICON -> drawStaticImage(dialCanvas, item)
                Kind.NUMBER -> if (TbwfSpec.inlineSign(item.name, item.json.optInt("properties", 0))) drawInlineSign(dialCanvas, item)
                else -> {}
            }
        }
        dial.getPixels(dialPx, 0, S, 0, 0, S, S)

        // 3) Hücreli öğeler ve ibreler.
        for (item in items) {
            val label = item.spec.label
            when (item.spec.kind) {
                Kind.NUMBER -> renderNumber(item)?.let { r ->
                    add(item.spec.type, r.w, r.h, r.x, r.y, r.frames, r.cells, label, bg = bgUnder(r.x, r.y, r.w, r.h))
                }
                Kind.DAY_OF_WEEK -> renderDay(item)?.let { r ->
                    add(item.spec.type, r.w, r.h, r.x, r.y, r.frames, 1, label, bg = bgUnder(r.x, r.y, r.w, r.h))
                }
                Kind.HAND -> renderHand(item)?.let { (px, tail) ->
                    add(item.spec.type, S, S, 0, 0, listOf(px), 1, label, tail, bg = bgUnder(0, 0, S, S))
                }
                Kind.ICON -> warnings += "$label çizilmedi (aşama 2)."
                else -> {}
            }
        }

        // 4) Arka plan ve önizleme (zemin 0).
        add(17, S, S, 0, 0, listOf(dialPx), 1, "Arka plan", bg = 0)
        val full = FaceCompose.compose(S, elems.distinctBy { it.type }) { e, f -> argb[e.type]!![f] }
        val preview = FaceCompose.scale(full, S, 0.72)
        add(10, S, S, 0, 0, listOf(preview), 1, "Önizleme", bg = 0)

        if (sprites.none { it.type in 1..6 }) warnings += "Saat gösteren öğe yok; kadranda saat görünmeyecek."
        val problems = FaceWriter.check(sprites)
        if (problems.isNotEmpty()) fail("Kadran oluşturulamadı: " + problems.joinToString("; ") + ".")
        val bytes = try {
            FaceWriter.write(id, sprites)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Kadran yazılamadı.")
        }
        FaceFile.parse(bytes).exceptionOrNull()?.let { fail("Derlenen kadran doğrulanamadı: ${it.message}") }
        val name = fallbackName.replace(Regex("(?i)\\.tbwf$"), "").trim().ifEmpty { "Kadran" }
        return Output(bytes, warnings.distinct(), name)
    }

    /** Zemin rengi: arka plan düz renkse o, değilse kutunun altındaki baskın renk. */
    private fun bgUnder(x: Int, y: Int, w: Int, h: Int): Int =
        dialSolid ?: FaceCompose.dominant(dialPx, S, x, y, w, h)

    // ---------- Renk ----------

    private class Colors(val gradient: String, val colors: IntArray, val positions: FloatArray?) {
        val first: Int get() = colors.firstOrNull() ?: -1
        val solid: Boolean get() = gradient == "solid" || colors.size < 2
    }

    private fun colors(j: JSONObject): Colors {
        val c = j.optJSONObject("color") ?: return Colors("solid", intArrayOf(-1), null)
        val arr: JSONArray? = c.optJSONArray("colors")
        val cols = IntArray(arr?.length() ?: 0) { arr!!.optLong(it).toInt() }
        val pa = c.optJSONArray("positions")
        var pos: FloatArray? = pa?.let { p -> FloatArray(p.length()) { p.optDouble(it).toFloat() } }
        if (pos != null && pos.size != cols.size) pos = null
        return Colors(c.optString("gradient", "solid"), if (cols.isEmpty()) intArrayOf(-1) else cols, pos)
    }

    /** Boyayı renge göre kurar (x0..x1 yatay, y0..y1 dikey, radyal merkez ve yarıçap). */
    private fun applyColor(p: Paint, c: Colors, hx0: Float, hx1: Float, vy0: Float, vy1: Float, rx: Float, ry: Float, rr: Float) {
        p.shader = null
        if (c.solid) {
            p.color = c.first
            return
        }
        p.color = -1
        p.shader = when (c.gradient) {
            "linear_horizontal" -> LinearGradient(hx0, 0f, hx1, 0f, c.colors, c.positions, Shader.TileMode.CLAMP)
            "linear_vertical" -> LinearGradient(0f, vy0, 0f, vy1, c.colors, c.positions, Shader.TileMode.CLAMP)
            "radial" -> RadialGradient(rx, ry, maxOf(rr, 1f), c.colors, c.positions, Shader.TileMode.CLAMP)
            else -> { p.color = c.first; null }
        }
    }

    // ---------- Arka plan ----------

    private fun drawDial(j: JSONObject?): Bitmap {
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        val img = j?.optJSONObject("resourceImage")?.optString("resource")?.takeIf { it.isNotEmpty() }
        val src = img?.let { png(it, "Arka plan") }
        if (src != null) {
            val scaled = Bitmap.createScaledBitmap(src, S, S, true)
            c.drawCircle(S / 2f, S / 2f, ceil(S / 2.0).toFloat(), mask)
            mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(scaled, 0f, 0f, mask)
            dialSolid = null
            return bmp
        }
        val col = if (j != null) colors(j) else Colors("solid", intArrayOf(0), null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (col.solid) {
            val v = if (col.first == 0) 0xFF000000.toInt() else col.first
            p.color = v
            val rgb = v and 0xFFFFFF
            dialSolid = if ((v ushr 24) == 0xFF && rgb != 0 && rgb != 0x050505) v else null
        } else {
            applyColor(p, col, 0f, S.toFloat(), 0f, S.toFloat(), S / 2f, S / 2f, S / 2f)
            dialSolid = null
        }
        c.drawCircle(S / 2f, S / 2f, ceil(S / 2.0).toFloat(), p)
        return bmp
    }

    // ---------- Yazı tipleri ----------

    private fun basePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isLinearText = true
        isSubpixelText = true
        isDither = true
        isFilterBitmap = true
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        @Suppress("DEPRECATION")
        hinting = Paint.HINTING_ON
    }

    private val bundled = mapOf(
        "oswald_medium" to R.font.oswald_medium,
        "fira_sans_condensed_regular" to R.font.fira_sans_condensed_regular,
        "fira_sans_condensed_medium" to R.font.fira_sans_condensed_medium,
        "rubik_medium" to R.font.rubik_medium,
        "rubik_light" to R.font.rubik_light,
    )

    private val system = setOf(
        "sans-serif", "sans-serif-thin", "sans-serif-light", "sans-serif-medium", "sans-serif-black",
        "sans-serif-condensed", "sans-serif-condensed-light", "sans-serif-condensed-medium",
        "monospace", "casual", "serif", "cursive", "sans-serif-smallcaps", "serif-monospace",
    )

    private fun typeface(family: String, label: String): Typeface = typefaces.getOrPut(family) {
        when {
            family.startsWith("/font/") || family.startsWith("font/") -> zipFont(family, label)
            family in bundled -> try {
                ctx.resources.getFont(bundled.getValue(family))
            } catch (e: Exception) {
                warnings += "$label: '$family' yazı tipi açılamadı; benzeri kullanıldı."
                similar(family)
            }
            family in system -> Typeface.create(family, Typeface.NORMAL)
            family.startsWith("oswald_") || family.startsWith("rubik_") || family.startsWith("fira_sans_condensed_") ||
                family.startsWith("big_shoulders_text_") -> {
                warnings += "$label: '$family' yazı tipi uygulamada yok; benzeri kullanıldı."
                similar(family)
            }
            else -> {
                warnings += "$label: '$family' yazı tipi tanınmadı; standart yazı kullanıldı."
                Typeface.SANS_SERIF
            }
        }
    }

    /** Hello Haylou'ya gömülü bir ailenin en yakın sistem karşılığı. */
    private fun similar(family: String): Typeface {
        val weight = family.substringAfterLast('_')
        val condensed = !family.startsWith("rubik_")
        val base = when (weight) {
            "thin", "extralight" -> if (condensed) "sans-serif-condensed-light" else "sans-serif-thin"
            "light" -> if (condensed) "sans-serif-condensed-light" else "sans-serif-light"
            "medium", "semibold" -> if (condensed) "sans-serif-condensed-medium" else "sans-serif-medium"
            "black", "extrabold" -> if (condensed) "sans-serif-condensed" else "sans-serif-black"
            else -> if (condensed) "sans-serif-condensed" else "sans-serif"
        }
        val bold = weight == "bold" || weight == "extrabold" || (condensed && weight == "black")
        return Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun zipFont(family: String, label: String): Typeface {
        val data = file(family)
        if (data == null) {
            warnings += "$label: yazı tipi dosyası yok; standart yazı kullanıldı."
            return Typeface.SANS_SERIF
        }
        val dir = fontDir ?: File(ctx.cacheDir, "tbwf_fonts_${System.nanoTime()}").also { it.mkdirs(); fontDir = it }
        val f = File(dir, "f${typefaces.size}_${data.size}.ttf")
        return try {
            f.writeBytes(data)
            val t = Typeface.createFromFile(f)
            if (t == null || t === Typeface.DEFAULT) {
                warnings += "$label: yazı tipi açılamadı; standart yazı kullanıldı."
                Typeface.SANS_SERIF
            } else t
        } catch (e: Exception) {
            warnings += "$label: yazı tipi açılamadı; standart yazı kullanıldı."
            Typeface.SANS_SERIF
        }
    }

    private fun fontPaint(item: Item): Paint? {
        val font = item.json.optJSONObject("font") ?: return null
        val p = basePaint()
        p.typeface = typeface(font.optString("family", "sans-serif"), item.spec.label)
        p.textSize = (S * font.optDouble("size", 0.08)).toFloat()
        return p
    }

    private fun bounds(p: Paint, s: String): GlyphBox {
        val r = Rect()
        p.getTextBounds(s, 0, s.length, r)
        return GlyphBox(r.left, r.top, r.right, r.bottom)
    }

    // ---------- Rakam alanları ----------

    private class Cells(val x: Int, val y: Int, val w: Int, val h: Int, val frames: List<IntArray>, val cells: Int)

    /** Rakam hücresi ölçüsü (yazı tipiyle). */
    private fun digitCell(p: Paint, set: String) =
        CellLayout.digits(bounds(p, set), set.map { bounds(p, it.toString()) })

    /** Bir karakteri hücre bitmap'ine çizer (rakam stili renk). */
    private fun drawGlyph(c: Canvas, p: Paint, col: Colors, cell: com.che.ls05.protocol.Cell, s: String, dx: Float = 0f) {
        val g = bounds(p, s)
        val o = CellLayout.glyphOrigin(cell, g)
        applyColor(p, col, dx, dx + cell.width, 0f, -cell.top.toFloat(), dx + cell.width / 2f, -cell.top / 2f,
            minOf(cell.width / 2f, cell.height / 2f))
        c.drawText(s, dx + o[0], o[1], p)
    }

    private fun renderNumber(item: Item): Cells? {
        val props = item.json.optInt("properties", 0)
        val sample = TbwfSpec.sample(item.name, props, texts) ?: return null
        val chars = TbwfSpec.frameTexts(texts)
        val p = fontPaint(item)
        val col = colors(item.json)
        val frames = ArrayList<IntArray>()
        val w: Int
        val h: Int
        if (p != null) {
            val cell = digitCell(p, TbwfSpec.measureSet(item.name, props, texts))
            if (cell.width <= 0 || cell.height <= 0) {
                warnings += "${item.spec.label}: yazı tipinde rakam yok, çizilmedi."
                return null
            }
            w = cell.width; h = cell.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            for (s in chars) {
                bmp.eraseColor(0)
                drawGlyph(c, p, col, cell, s)
                frames += IntArray(w * h).also { a -> bmp.getPixels(a, 0, w, 0, 0, w, h) }
            }
        } else {
            val rc = item.json.optJSONObject("resourceCharacter")
            if (rc == null) {
                warnings += "${item.spec.label}: yazı tipi ya da karakter resmi yok, çizilmedi."
                return null
            }
            val dir = TbwfSpec.characterDir(rc.optString("resource", item.name))
            val size = rc.optDouble("size", 0.1)
            val imgs = chars.map { s ->
                val n = TbwfSpec.characterFile(s[0])
                val path = "$dir$n.png"
                if (n == null || file(path) == null) null else png(path, item.spec.label)
            }
            val sizes = imgs.map { b -> b?.let { CellLayout.imageSize(it.width, it.height, size) } }
            w = sizes.maxOfOrNull { it?.get(0) ?: 0 } ?: 0
            h = sizes.maxOfOrNull { it?.get(1) ?: 0 } ?: 0
            if (w <= 0 || h <= 0) {
                warnings += "${item.spec.label}: karakter resimleri okunamadı, çizilmedi."
                return null
            }
            val missing = chars.filterIndexed { i, s -> imgs[i] == null && s[0] in "0123456789" }
            if (missing.isNotEmpty()) warnings += "${item.spec.label}: eksik karakter resmi (${missing.joinToString(" ")})."
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val ip = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for ((i, img) in imgs.withIndex()) {
                bmp.eraseColor(0)
                if (img != null) {
                    val sw = sizes[i]!![0]; val sh = sizes[i]!![1]
                    val l = Math.round((w - sw) / 2f); val t = Math.round((h - sh) / 2f)
                    c.drawBitmap(img, null, Rect(l, t, l + sw, t + sh), ip)
                }
                frames += IntArray(w * h).also { a -> bmp.getPixels(a, 0, w, 0, 0, w, h) }
            }
        }
        val x = TbwfSpec.screenX(item.json.optDouble("x", 0.0), props, w, sample.length)
        val y = TbwfSpec.screenY(item.json.optDouble("y", 0.0))
        return Cells(x, y, w, h, frames, sample.length)
    }

    /** Ayırıcı ya da birim işareti: rakam hücresi gibi ölçülür, arka plana çizilir. */
    private fun drawSeparator(dial: Canvas, item: Item) {
        val p = fontPaint(item)
        if (p == null) {
            warnings += "${item.spec.label}: yazı tipi yok, çizilmedi."
            return
        }
        val props = item.json.optInt("properties", 0)
        val text = TbwfSpec.sample(item.name, props, texts) ?: return
        val set = if (item.spec.kind == Kind.SEPARATOR) TbwfSpec.measureSet(item.name, props, texts) else text
        val cell = digitCell(p, set)
        if (cell.width <= 0 || cell.height <= 0) return
        val x = TbwfSpec.screenX(item.json.optDouble("x", 0.0), props, cell.width, text.length)
        val y = TbwfSpec.screenY(item.json.optDouble("y", 0.0))
        val col = colors(item.json)
        dial.save()
        dial.translate(x.toFloat(), y.toFloat())
        for ((i, ch) in text.withIndex()) drawGlyph(dial, p, col, cell, ch.toString(), dx = (i * cell.width).toFloat())
        dial.restore()
    }

    /** Pil "100%" ve sıcaklık "-10°": saatte % ve ° karesi yok; işaret son hücrenin yerine arka plana çizilir. */
    private fun drawInlineSign(dial: Canvas, item: Item) {
        val p = fontPaint(item) ?: return
        val props = item.json.optInt("properties", 0)
        val sample = TbwfSpec.sample(item.name, props, texts) ?: return
        val cell = digitCell(p, TbwfSpec.measureSet(item.name, props, texts))
        if (cell.width <= 0 || cell.height <= 0) return
        val sign = sample.last().toString()
        val sp = Paint(p)
        var g = bounds(sp, sign)
        var guard = 0
        while ((g.width > cell.width || g.height > cell.height) && guard++ < 40) {
            sp.textSize = sp.textSize * 0.9f
            g = bounds(sp, sign)
        }
        val x = TbwfSpec.screenX(item.json.optDouble("x", 0.0), props, cell.width, sample.length) + (sample.length - 1) * cell.width
        val y = TbwfSpec.screenY(item.json.optDouble("y", 0.0))
        val col = colors(item.json)
        dial.save()
        dial.translate(x.toFloat(), y.toFloat())
        applyColor(sp, col, 0f, cell.width.toFloat(), 0f, -cell.top.toFloat(), cell.width / 2f, -cell.top / 2f,
            minOf(cell.width / 2f, cell.height / 2f))
        dial.drawText(sign, -g.left.toFloat(), -cell.top.toFloat(), sp)
        dial.restore()
    }

    /** Sabit simge: zip içindeki resim (uzun kenar 240*size) arka plana; yerleşik simge aşama 3. */
    private fun drawStaticImage(dial: Canvas, item: Item) {
        val img = item.json.optJSONObject("resourceImage")
        if (img == null) {
            warnings += "${item.spec.label} çizilmedi (yerleşik simge, aşama 3)."
            return
        }
        val bmp = png(img.optString("resource"), item.spec.label) ?: return
        val sz = CellLayout.imageSize(bmp.width, bmp.height, img.optDouble("size", 0.1))
        val x = TbwfSpec.screenX(item.json.optDouble("x", 0.0), 0, 0, 0)
        val y = TbwfSpec.screenY(item.json.optDouble("y", 0.0))
        dial.drawBitmap(bmp, null, Rect(x, y, x + sz[0], y + sz[1]), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    // ---------- Gün adı ----------

    private fun renderDay(item: Item): Cells? {
        val p = fontPaint(item)
        if (p == null) {
            warnings += "${item.spec.label}: yazı tipi yok, çizilmedi."
            return null
        }
        val props = item.json.optInt("properties", 0)
        val names = texts.dayNames(props)
        val fm = p.fontMetrics
        val boxes = names.map { n -> bounds(p, n) }
        val cell = CellLayout.text(fm.ascent, fm.descent, boxes)
        if (cell.width <= 0 || cell.height <= 0) {
            warnings += "${item.spec.label}: yazı tipinde harf yok, çizilmedi."
            return null
        }
        val col = colors(item.json)
        val bmp = Bitmap.createBitmap(cell.width, cell.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val frames = ArrayList<IntArray>()
        for ((i, n) in names.withIndex()) {
            bmp.eraseColor(0)
            val g = boxes[i]
            val f2 = TbwfSpec.dayOffset(props, cell.width, g.width)
            applyColor(p, col, f2, f2 + g.width, -(cell.top - g.top).toFloat(), -cell.top.toFloat(),
                g.width / 2f + f2, g.top / 2f - cell.top, minOf(g.width / 2f, -g.top / 2f))
            c.drawText(n, f2 - g.left, -cell.top.toFloat(), p)
            frames += IntArray(cell.width * cell.height).also { a -> bmp.getPixels(a, 0, cell.width, 0, 0, cell.width, cell.height) }
        }
        val x = TbwfSpec.screenX(item.json.optDouble("x", 0.0), 0, 0, 0)
        val y = TbwfSpec.screenY(item.json.optDouble("y", 0.0))
        return Cells(x, y, cell.width, cell.height, frames, 1)
    }

    // ---------- İbreler ----------

    private fun renderHand(item: Item): Pair<IntArray, ByteArray>? {
        val img = item.json.optJSONObject("resourceImage")
        if (img == null) {
            warnings += "${item.spec.label} çizilmedi (yerleşik ibre, aşama 3)."
            return null
        }
        val bmp = png(img.optString("resource"), item.spec.label) ?: return null
        val props = item.json.optInt("properties", 0)
        val sz = CellLayout.imageSize(bmp.width, bmp.height, img.optDouble("size", 0.3))
        val pivot = HandGeometry.pivot(props, img.optDouble("pivotY", 0.0))
        if ((props and 4) != 0) warnings += "${item.spec.label}: orta göbek çizilmedi (yerleşik simge, aşama 3)."
        val box = HandGeometry.box(sz[0], sz[1], pivot)
        val out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, null, Rect(box.left, box.top, box.right, box.bottom),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(S * S)
        out.getPixels(px, 0, S, 0, 0, S, S)
        return px to box.tail
    }
}
