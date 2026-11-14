package com.che.ls05.protocol

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** .tbwf derlemesinin saf kuralları. */
class TbwfSpecTest {

    private val en = FaceTexts(Locale.ENGLISH)
    private val tr = FaceTexts(Locale.forLanguageTag("tr-TR"))

    @Test
    fun typesAndFrames() {
        val want = mapOf(
            "hour" to 4, "minute" to 5, "second" to 6, "month" to 11, "day" to 13, "heartRate" to 19,
            "weatherTemperature" to 21, "step" to 25, "year" to 27, "calorie" to 30, "battery" to 33, "distance" to 47,
            "dayOfWeek" to 15, "dial" to 17, "hourHand" to 1, "minuteHand" to 2, "secondHand" to 3,
        )
        for ((n, t) in want) assertEquals(t, TbwfSpec.ELEMENTS[n]?.type, n)
        // Yazıcının kare beyaz listesiyle uyumlu.
        for (e in TbwfSpec.ELEMENTS.values) if (e.type != 0) assertEquals(FaceWriter.FRAMES[e.type], e.frames, e.name)
        assertEquals(14, TbwfSpec.frameTexts(en).size)
        assertEquals(".", TbwfSpec.frameTexts(en)[1])
        assertEquals(",", TbwfSpec.frameTexts(tr)[1])
    }

    @Test
    fun shiftUsesEquality() {
        assertEquals(0.0, TbwfSpec.xShift(0, 20, 2))
        assertEquals(-20.0, TbwfSpec.xShift(0x20, 20, 2))
        assertEquals(-40.0, TbwfSpec.xShift(0x40, 20, 2))
        // 0x60 ne orta ne sağ: sol.
        assertEquals(0.0, TbwfSpec.xShift(0x60, 20, 2))
        // 36 = 0x24: orta; 20 = 0x14: sol; 64: sağ.
        assertEquals(-50.0, TbwfSpec.xShift(36, 25, 4))
        assertEquals(0.0, TbwfSpec.xShift(20, 25, 4))
        assertEquals(-75.0, TbwfSpec.xShift(64, 15, 5))
        // round(240*x + kaydırma)
        assertEquals(TbwfSpec.screenX(0.75, 32, 10, 5), Math.round(180.0 - 25.0).toInt())
        assertEquals(41, TbwfSpec.screenX(0.1708333, 0, 10, 2))
    }

    @Test
    fun samples() {
        assertEquals("20", TbwfSpec.sample("hour", 0, en))
        assertEquals("12345", TbwfSpec.sample("step", 32, en))
        assertEquals("100", TbwfSpec.sample("battery", 20 and 4.inv(), en))
        assertEquals("100%", TbwfSpec.sample("battery", 36, en))
        assertEquals("100%", TbwfSpec.sample("battery", 20, en))
        assertEquals("-10°", TbwfSpec.sample("weatherTemperature", 4, en))
        assertEquals("-10", TbwfSpec.sample("weatherTemperature", 32, en))
        assertEquals("19.87", TbwfSpec.sample("distance", 64, en))
        assertEquals("19,87", TbwfSpec.sample("distance", 64, tr))
        assertEquals(4, TbwfSpec.sample("year", 0, en)!!.length)
        assertEquals(":", TbwfSpec.sample("timeSeparator", 0, en))
        assertEquals("/", TbwfSpec.sample("dateSeparator", 0, en))
        assertEquals(".", TbwfSpec.sample("dateSeparator", 0, tr))
        assertEquals("°C", TbwfSpec.sample("weatherTemperatureUnitSign", 1, en))
        assertEquals("°", TbwfSpec.sample("weatherTemperatureUnitSign", 4, en))
        assertEquals(null, TbwfSpec.sample("batteryResource", 0, en))
    }

    @Test
    fun dayFormat() {
        assertEquals("EEE", TbwfSpec.dayPattern(0))
        assertEquals("EEE", TbwfSpec.dayPattern(1))
        assertEquals("EEEE", TbwfSpec.dayPattern(2))
        assertEquals("EEE", TbwfSpec.dayPattern(3))
        assertEquals("EEEEE", TbwfSpec.dayPattern(4))
        assertEquals("EEEEE", TbwfSpec.dayPattern(6))
        assertEquals("EEE", TbwfSpec.dayPattern(33))
        assertEquals("EEEE", TbwfSpec.dayPattern(34))
        assertEquals("Sun", en.short[0])
        assertEquals("Saturday", en.full[6])
        assertEquals("Pazar", tr.full[0])
        assertEquals("Pazartesi", tr.full[1])
        assertEquals(en.full, en.dayNames(2))
        // Gün adı hücre içinde hizalanır.
        assertEquals(10f, TbwfSpec.dayOffset(0x20, 50, 30))
        assertEquals(20f, TbwfSpec.dayOffset(0x40, 50, 30))
        assertEquals(0f, TbwfSpec.dayOffset(0x21, 50, 50))
        assertEquals(Locale.ENGLISH, FaceTexts.defaultLocale(Locale.GERMANY))
        assertEquals("tr", FaceTexts.defaultLocale(Locale.forLanguageTag("tr-TR")).language)
    }

    @Test
    fun cellLayout() {
        val whole = GlyphBox(0, -50, 300, 2)
        val chars = listOf(GlyphBox(2, -48, 28, 0), GlyphBox(1, -52, 30, 1), GlyphBox(3, -10, 9, 0))
        val c = CellLayout.digits(whole, chars)
        assertEquals(-52, c.top); assertEquals(2, c.bottom); assertEquals(29, c.width); assertEquals(54, c.height)
        val o = CellLayout.glyphOrigin(c, GlyphBox(3, -10, 9, 0))
        assertEquals((29 - 6) / 2f - 3, o[0]); assertEquals(52f, o[1])
        val t = CellLayout.text(-40.3f, 10.2f, listOf(GlyphBox(0, -30, 50, 0), GlyphBox(0, -45, 60, 5)))
        assertEquals(-45, t.top); assertEquals(7, t.bottom); assertEquals(60, t.width)
        assertEquals(listOf(63, 63), CellLayout.imageSize(180, 180, 0.2638889).toList())
        assertEquals(listOf(25, 40), CellLayout.imageSize(26, 41, 0.1666667).toList())
    }

    @Test
    fun characterFiles() {
        assertEquals("_7", TbwfSpec.characterFile('7'))
        assertEquals("_comma", TbwfSpec.characterFile(','))
        assertEquals("_hyphen", TbwfSpec.characterFile('-'))
        assertEquals("resourceCharacter/hour/", TbwfSpec.characterDir("/resourceCharacter/hour/"))
        assertEquals("resourceCharacter/hour/", TbwfSpec.characterDir("hour"))
        assertEquals(1, TbwfSpec.frameOf('.'))
        assertEquals(3, TbwfSpec.frameOf('0'))
        assertEquals(13, TbwfSpec.frameOf(':'))
        assertEquals(-1, TbwfSpec.frameOf('%'))
    }

    @Test
    fun handGeometry() {
        // FNGEENv2 saniye ibresi: 180x180 resim, size 0.2638889, pivotY -0.12222222, properties 2.
        val p = HandGeometry.pivot(2, -0.12222222)
        assertTrue(Math.abs(p + 29.333) < 0.01)
        assertEquals(0.0, HandGeometry.pivot(1, -0.12222222))
        assertEquals(0.0, HandGeometry.pivot(6, -0.12222222))
        val b = HandGeometry.box(63, 63, p)
        assertEquals(89, b.left); assertEquals(86, b.top); assertEquals(152, b.right); assertEquals(149, b.bottom)
        assertEquals(listOf(63, 34, 63), b.tail.map { it.toInt() and 0xFF })
        // Göbekli: C=19, pivot 0 → H2 = ceil(9.5)+80 = 90, t = -9.5 → -9.
        val h = HandGeometry.box(20, 80, 0.0, hub = 19)
        assertEquals(listOf(90, 81, 20), h.tail.map { it.toInt() and 0xFF })
        // Uzun ibre: 255'e kırpılır; genişlik alt bayt.
        val l = HandGeometry.box(300, 260, 0.0)
        assertEquals(listOf(255, 255, 300 and 0xFF), l.tail.map { it.toInt() and 0xFF })
        assertEquals(0.95, HandGeometry.hubRatio(1)); assertEquals(0.55, HandGeometry.hubRatio(3))
    }

    @Test
    fun angles() {
        val a = TbwfSpec.handAngles(10, 8, 36)
        assertTrue(Math.abs(a[0] - 304.3) < 0.01)
        assertTrue(Math.abs(a[1] - 51.6) < 0.01)
        assertEquals(216.0, a[2])
    }

    @Test
    fun composeAndDominant() {
        val n = 240
        val red = 0xFFFF0000.toInt()
        val full = IntArray(n * n) { red }
        val s = FaceCompose.scale(full, n, 0.72)
        assertEquals(0, s[0])
        assertEquals(red, s[120 * n + 120])
        assertEquals(0, s[120 * n + 10])
        assertEquals(0xFF7F0080.toInt(), FaceCompose.over(red, 0x800000FF.toInt()))
        val px = IntArray(n * n) { i -> if (i % 10 < 7) red else 0xFF0000FF.toInt() }
        assertEquals(0xFFFF0000.toInt(), FaceCompose.dominant(px, n, 0, 0, 20, 20))
    }

    private fun solid(w: Int, h: Int, frames: Int, perFrame: (Int) -> Int): ByteArray {
        val b = ByteArray(w * h * 2 * frames)
        for (f in 0 until frames) {
            val v = perFrame(f)
            for (i in 0 until w * h) {
                val o = (f * w * h + i) * 2
                b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte()
            }
        }
        return b
    }

    @Test
    fun composedPreviewIgnoresStoredPreview() {
        val sprites = listOf(
            FaceSprite(17, 240, 240, 0, 0, 1, solid(240, 240, 1) { 0xF800 }),
            // Rakam karesi f: renk f+1 (frame 4 = '1' → 5, frame 3 = '0' → 4).
            FaceSprite(4, 10, 20, 50, 100, 14, solid(10, 20, 14) { it + 1 }, maxCells = 2),
            FaceSprite(10, 240, 240, 0, 0, 1, solid(240, 240, 1) { 0x07E0 }),
        )
        val f = FaceFile.parse(FaceWriter.write(0xF000, sprites)).getOrThrow()
        val px = f.composedPreview()
        // Arka plan kırmızı, önizlemenin yeşili görünmez.
        assertEquals(0xFFFF0000.toInt(), px[10 * 240 + 120])
        // "10": ilk hücre kare 4, ikinci hücre kare 3.
        assertEquals(Rgb565.toArgb(byteArrayOf(0, 5), 0, 1, 1)[0], px[105 * 240 + 52])
        assertEquals(Rgb565.toArgb(byteArrayOf(0, 4), 0, 1, 1)[0], px[105 * 240 + 62])
        // Kayıtlı önizleme hâlâ previewPixels'te.
        assertEquals(0xFF00FF00.toInt(), f.previewPixels()!![0])
    }
}
