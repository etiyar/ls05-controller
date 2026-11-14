package com.che.ls05.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/*
 * Kadran dosyası yazıcı (tbwf derlemesi ve kendi kadranlarımız için).
 *
 * Başlık: saatte kanıtlanmış resmi Haylou biçimi
 *   [kimlik u16 LE][00 00][dosya-24 u32 LE][CRC32 u32 LE][240 u16][240 u16][00 01 FF FF FF FF FF FF]
 * Tablo: 24 baytlık girdiler, 2 sıfır baytla biter (resmi dosyada veri 24 + n*24 + 2'de başlıyor).
 *   [tür u8][0][en u16][konum u32][boy u16][x u16][y u16][0 0][kare u8][0 0 0 0][3 bayt kuyruk]
 * Görüntü: RGB565 büyük uçlu; 0x0000 saydam, siyah 0x0020.
 */

class FaceSprite(
    val type: Int,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val frames: Int,
    /** Tüm kareler art arda, RGB565 BE. Boyu en*boy*2*kare olmalı. */
    val data: ByteArray,
    /** İbrelerde 3 bayt (uzunluk, uca uzaklık, genişlik); diğerlerinde sıfır. */
    val tail: ByteArray = ByteArray(3),
    /** Rakam alanlarında sayının en uzun hâli (sınır denetimi için hane sayısı); diğerlerinde 1. */
    val maxCells: Int = 1,
)

object FaceWriter {

    const val SCREEN = 240
    const val MAX_SIZE = 1_179_648

    /** Bizim ürettiğimiz dosyalarda izin verilen türler ve kare sayıları. */
    val FRAMES: Map<Int, Int> = mapOf(
        1 to 1, 2 to 1, 3 to 1, 10 to 1, 17 to 1,
        4 to 14, 5 to 14, 6 to 14, 11 to 14, 13 to 14, 19 to 14, 21 to 14, 25 to 14, 27 to 14, 30 to 14, 33 to 14, 47 to 14,
        15 to 7, 8 to 5, 9 to 2, 20 to 16, 32 to 11,
    )
    private val FULL = setOf(1, 2, 3, 10, 17)

    /** Denetim; sorun varsa Türkçe açıklama listesi döner (boşsa geçerli). */
    fun check(sprites: List<FaceSprite>): List<String> {
        val err = ArrayList<String>()
        val seen = HashSet<Int>()
        for (s in sprites) {
            val n = "tür ${s.type}"
            val want = FRAMES[s.type]
            if (want == null) { err += "$n desteklenmiyor"; continue }
            if (!seen.add(s.type)) err += "$n iki kez var"
            if (s.frames != want) err += "$n: $want kare olmalı, ${s.frames} var"
            if (s.type in FULL && (s.width != SCREEN || s.height != SCREEN || s.x != 0 || s.y != 0)) err += "$n tam ekran olmalı"
            if (s.width <= 0 || s.height <= 0) err += "$n boyu sıfır"
            if (s.x < 0 || s.y < 0) err += "$n ekranın dışında (x=${s.x}, y=${s.y})"
            if (s.x + s.width * maxOf(1, s.maxCells) > SCREEN || s.y + s.height > SCREEN) err += "$n ekrana sığmıyor"
            if (s.data.size != s.width * s.height * 2 * s.frames) err += "$n veri boyu tutmuyor"
            if (s.tail.size != 3) err += "$n kuyruk 3 bayt değil"
        }
        return err
    }

    /**
     * Dosyayı yazar. Tablo tür sırasındadır (saatte kanıtlanan sıra).
     * dedupe: aynı görüntü verisi bir kez yazılır, öğeler aynı konumu paylaşır.
     */
    fun write(id: Int, sprites: List<FaceSprite>, dedupe: Boolean = false): ByteArray {
        val problems = check(sprites)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        require(id in 0..0xFFFE) { "kimlik geçersiz" }
        val list = sprites.sortedBy { it.type }
        val dataStart = 24 + list.size * 24 + 2
        val offsets = IntArray(list.size)
        val data = ByteArrayOutputStream()
        val known = HashMap<String, Int>()
        for ((i, s) in list.withIndex()) {
            val key = if (dedupe) "${s.width}x${s.height}x${s.frames}:" + java.util.Arrays.hashCode(s.data) else null
            val prev = key?.let { known[it] }
            if (prev != null && list.withIndex().any { (j, o) -> j < i && offsets[j] == prev && o.data.contentEquals(s.data) }) {
                offsets[i] = prev
            } else {
                offsets[i] = dataStart + data.size()
                data.write(s.data)
                if (key != null) known[key] = offsets[i]
            }
        }
        val out = ByteArray(dataStart + data.size())
        fun u16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        fun u32(o: Int, v: Long) { u16(o, (v and 0xFFFF).toInt()); u16(o + 2, ((v shr 16) and 0xFFFF).toInt()) }
        u16(0, id)
        u32(4, (out.size - 24).toLong())
        u16(12, SCREEN); u16(14, SCREEN)
        out[16] = 0; out[17] = 1
        for (i in 18..23) out[i] = 0xFF.toByte()
        for ((i, s) in list.withIndex()) {
            val o = 24 + i * 24
            u16(o, s.type)
            u16(o + 2, s.width)
            u32(o + 4, offsets[i].toLong())
            u16(o + 8, s.height)
            u16(o + 10, s.x)
            u16(o + 12, s.y)
            out[o + 16] = s.frames.toByte()
            s.tail.copyInto(out, o + 21)
        }
        data.toByteArray().copyInto(out, dataStart)
        val crc = CRC32().apply { update(out, 24, out.size - 24) }.value
        u32(8, crc)
        require(out.size <= MAX_SIZE) { "Kadran ${out.size / 1024} KB; sınır ${MAX_SIZE / 1024} KB" }
        return out
    }

    /** Var olan bir dosyanın öğelerini yazıcı biçimine çevirir (yeniden yazma testi ve kendi kadranlarımız için). */
    fun spritesOf(f: FaceFile): List<FaceSprite> = f.elements.map { e ->
        val len = e.width * e.height * 2 * maxOf(1, e.frames)
        val o = e.offset.toInt()
        FaceSprite(e.type, e.width, e.height, e.x, e.y, e.frames, f.bytes.copyOfRange(o, o + len), f.tail(e))
    }
}

/** Hello Haylou'nun piksel kuralı (LS05, alfa kipi kapalı). */
object Rgb565Encode {
    private fun pack(r: Int, g: Int, b: Int): Int = ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (b shr 3)

    /** ARGB → RGB565 (BE değer olarak 16 bit). bg: altındaki zeminin RGB'si. */
    fun encode(argb: Int, bg: Int): Int {
        if (argb == 0) return 0
        val a = (argb ushr 24) and 0xFF
        var r = (argb shr 16) and 0xFF
        var g = (argb shr 8) and 0xFF
        var b = argb and 0xFF
        val nearBlack = r <= 4 && g <= 4 && b <= 4
        if (nearBlack && a >= 40) { r = 5; g = 5; b = 5 }
        if (a < 40) return 0
        if (a <= 220) {
            val t = a / 255.0
            r = (t * r + (1 - t) * ((bg shr 16) and 0xFF)).toInt()
            g = (t * g + (1 - t) * ((bg shr 8) and 0xFF)).toInt()
            b = (t * b + (1 - t) * (bg and 0xFF)).toInt()
        }
        val v = pack(r, g, b)
        // Siyah asla saydam olmasın.
        return if (v == 0) 0x0020 else v
    }

    fun frames(argb: IntArray, bg: Int): ByteArray {
        val out = ByteArray(argb.size * 2)
        for (i in argb.indices) {
            val v = encode(argb[i], bg)
            out[i * 2] = (v shr 8).toByte()
            out[i * 2 + 1] = v.toByte()
        }
        return out
    }
}
