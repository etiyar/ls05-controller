package com.che.ls05.protocol

import java.util.zip.CRC32

/*
 * Kadran dosyası ve aktarımı (Haylou Fun SendPlateTask ve 12 Kasım kaydıyla birebir).
 *
 * Dosya: 24 bayt başlık + öğe tablosu + RGB565 (büyük uçlu) resimler.
 *   [0..7]  Haylou galerisi: [kimlik u16 LE][00 00][veri boyu u32 LE = dosya boyu - 24]
 *           Hello Haylou derlemesi: "HSWTv1.0" / "HSWTv1.1" ASCII imza (boy alanı yok)
 *   [8..11] CRC32 (u32 LE), 24. bayttan dosya sonuna
 *   [12..13] en, [14..15] boy (u16 LE)
 *   0x18'den: 24 baytlık öğeler, tür 0 ile biter:
 *     [tür u16][en u16][konum u32][boy u16][x u16][y u16][00 00][kare u8][7 bayt]
 *
 * Aktarım: 1a 01 yapılandırma, 1a 02 başlat, 4096 baytlık bloklar parça parça 61FF/6101'e
 * (1b + sıra hi + sıra lo + veri), her blok sonunda saat 26 03 04 der, bitiş 1a 03 00.
 */

class FaceElement(
    val type: Int, val width: Int, val height: Int, val offset: Long,
    val x: Int, val y: Int, val frames: Int,
    /** Tablo girdisinin son 3 baytı (ibrelerde boyut bilgisi). */
    val tail: ByteArray = ByteArray(3),
)

class FaceFile private constructor(
    val bytes: ByteArray,
    val id: Int,
    val width: Int,
    val height: Int,
    val elements: List<FaceElement>,
) {
    val size: Int get() = bytes.size

    fun tail(e: FaceElement): ByteArray = e.tail.copyOf()

    /** Önizleme için en uygun öğe: önizleme (10), yoksa arka plan (17), yoksa ilk tam ekran öğe. */
    fun previewElement(): FaceElement? =
        elements.firstOrNull { it.type == 10 && it.width == width && it.height == height }
            ?: elements.firstOrNull { it.type == 17 && it.width == width && it.height == height }
            ?: elements.firstOrNull { it.width == width && it.height == height }

    /** Öğenin bir karesini ARGB piksel dizisine çevirir (RGB565, büyük uçlu). */
    fun pixels(e: FaceElement, frame: Int = 0): IntArray =
        Rgb565.toArgb(bytes, (e.offset + frame.toLong() * e.width * e.height * 2).toInt(), e.width, e.height)

    /**
     * Önizleme pikselleri. Dosyada önizleme (10) varsa o; yoksa arka plan üzerine öğelerin örnek kareleri
     * ve ibreler 10:08:36 konumunda çizilir. Siyah piksel saydam sayılır (saat de böyle çiziyor olmalı).
     */
    fun previewPixels(): IntArray? {
        elements.firstOrNull { it.type == 10 && it.width == width && it.height == height }?.let { return pixels(it) }
        val bg = elements.firstOrNull { it.type == 17 && it.width == width && it.height == height }
        val out = bg?.let { pixels(it) } ?: IntArray(width * height) { 0xFF000000.toInt() }
        var sample = 0
        for (e in elements) {
            if (e === bg || e.type == 10) continue
            if (e.width == width && e.height == height) {
                val angle = when (e.type) { 1 -> 304.0; 2 -> 48.0; 3 -> 216.0; else -> continue }
                rotateOnto(out, pixels(e), angle)
                continue
            }
            val frame = if (e.frames >= 13) 3 + (sample++ * 3 + 1) % 10 else 0
            val px = pixels(e, minOf(frame, maxOf(e.frames, 1) - 1))
            for (yy in 0 until e.height) for (xx in 0 until e.width) {
                val v = px[yy * e.width + xx]
                if (v and 0xFFFFFF == 0) continue
                val tx = e.x + xx
                val ty = e.y + yy
                if (tx in 0 until width && ty in 0 until height) out[ty * width + tx] = v
            }
        }
        return out
    }

    /**
     * Doğrulama önizlemesi: dosyadaki önizleme (10) yok sayılır, görüntü derlenmiş öğelerden kurulur
     * (rakamlar örnek değerle, ibreler 10:08:36). 0x0000 saydam sayılır.
     */
    fun composedPreview(): IntArray {
        val px = FaceCompose.compose(width, elements) { e, f -> pixelsTransparent(e, f) }
        // Saydam kalan yerler siyah görünsün.
        for (i in px.indices) px[i] = FaceCompose.over(0xFF000000.toInt(), px[i])
        return px
    }

    /** Öğenin karesi; 0x0000 saydam (alfa 0), diğerleri opak. */
    fun pixelsTransparent(e: FaceElement, frame: Int = 0): IntArray {
        val o = (e.offset + frame.toLong() * e.width * e.height * 2).toInt()
        val px = Rgb565.toArgb(bytes, o, e.width, e.height)
        for (i in px.indices) {
            if (bytes[o + i * 2].toInt() == 0 && bytes[o + i * 2 + 1].toInt() == 0) px[i] = 0
        }
        return px
    }

    /** Tam ekran ibre resmini merkez etrafında döndürüp siyah olmayan pikselleri çizer (en yakın komşu). */
    private fun rotateOnto(out: IntArray, src: IntArray, degrees: Double) {
        val a = Math.toRadians(degrees)
        val cos = Math.cos(a)
        val sin = Math.sin(a)
        val cx = width / 2.0
        val cy = height / 2.0
        for (y in 0 until height) for (x in 0 until width) {
            val dx = x - cx
            val dy = y - cy
            val sx = (cx + dx * cos + dy * sin).toInt()
            val sy = (cy - dx * sin + dy * cos).toInt()
            if (sx !in 0 until width || sy !in 0 until height) continue
            val v = src[sy * width + sx]
            if (v and 0xFFFFFF != 0) out[y * width + x] = v
        }
    }

    companion object {
        const val HEADER = 24
        const val TABLE = 0x18
        const val ENTRY = 24
        /** Hello Haylou'nun LS05 sınırı (0x120000). */
        const val MAX_SIZE = 1_179_648

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, o: Int): Long =
            (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

        fun crc(b: ByteArray): Long {
            val c = CRC32()
            c.update(b, HEADER, b.size - HEADER)
            return c.value
        }

        /** Dosyayı doğrular. Geçersizse nedenini Türkçe döndürür (Result.failure). */
        fun parse(b: ByteArray, screenW: Int = 240, screenH: Int = 240): Result<FaceFile> {
            fun fail(msg: String) = Result.failure<FaceFile>(IllegalArgumentException(msg))
            if (b.size >= 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) {
                return fail("Bu bir zip dosyası; kadran dosyası ya da .tbwf tasarımı değil.")
            }
            if (b.size < HEADER + ENTRY) return fail("Dosya çok küçük; kadran dosyası değil.")
            if (b.size > MAX_SIZE) return fail("Dosya ${MAX_SIZE / 1024} KB sınırından büyük.")
            val hsw = b[0] == 'H'.code.toByte() && b[1] == 'S'.code.toByte() && b[2] == 'W'.code.toByte()
            if (!hsw && u32(b, 4) != (b.size - HEADER).toLong()) return fail("Boy alanı tutmuyor; dosya eksik ya da farklı biçimde.")
            if (u32(b, 8) != crc(b)) return fail("Doğrulama toplamı (CRC) tutmuyor; dosya bozuk.")
            val w = u16(b, 12)
            val h = u16(b, 14)
            if (w != screenW || h != screenH) return fail("Kadran ${w}x$h, saat ${screenW}x$screenH; uyumsuz.")
            val els = ArrayList<FaceElement>()
            var o = TABLE
            while (true) {
                if (o + ENTRY > b.size) return fail("Öğe tablosu bitmiyor.")
                val type = u16(b, o)
                if (type == 0) break
                val e = FaceElement(
                    type = type, width = u16(b, o + 2), offset = u32(b, o + 4), height = u16(b, o + 8),
                    x = u16(b, o + 10), y = u16(b, o + 12), frames = b[o + 16].toInt() and 0xFF,
                    tail = b.copyOfRange(o + 21, o + 24),
                )
                val end = e.offset + e.width.toLong() * e.height * 2 * maxOf(e.frames, 1)
                if (e.width == 0 || e.height == 0 || e.width > w || e.height > h || e.offset < TABLE || end > b.size) {
                    return fail("Öğe ${els.size + 1} dosya sınırlarının dışında.")
                }
                if (e.x + e.width > w || e.y + e.height > h) {
                    return fail("Öğe ${els.size + 1} (tür ${e.type}) ekranın dışına taşıyor.")
                }
                els += e
                o += ENTRY
                if (els.size > 64) return fail("Öğe tablosu çok uzun.")
            }
            if (els.isEmpty()) return fail("Kadranda öğe yok.")
            return Result.success(FaceFile(b, if (hsw) 0 else u16(b, 0), w, h, els))
        }
    }
}

/** 1a 01 yanıtı: 1a 01 ff ff ff ff [en u16 BE] [boy u16 BE] 00 00 → ekran boyu. */
object FaceConfig {
    fun screen(v: ByteArray): Pair<Int, Int>? {
        if (v.size < 10 || v[0] != 0x1a.toByte() || v[1] != 0x01.toByte()) return null
        val w = ((v[6].toInt() and 0xFF) shl 8) or (v[7].toInt() and 0xFF)
        val h = ((v[8].toInt() and 0xFF) shl 8) or (v[9].toInt() and 0xFF)
        return w to h
    }
}

/** Aktarım durum makinesi. BLE'den bağımsız; uygulama yazar, saat yanıtını [onReply] ile verir. */
class FaceTransfer(file: ByteArray, packetSize: Int) {

    sealed class Step {
        /** Bu parçaları sırayla yaz (sıra numaralı, hazır paketler). */
        class Send(val from: Int, val to: Int) : Step()
        /** Tüm parçalar onaylandı: 1a 03 00 gönder. */
        object Finish : Step()
        object Done : Step()
        class Fail(val code: Int, val reason: String) : Step()
        /** Bu aktarıma ait değil, yok say. */
        object Ignore : Step()
    }

    companion object {
        const val BLOCK = 4096
        const val HEAD = 3
        val START = byteArrayOf(0x1a, 0x02)
        val QUERY = byteArrayOf(0x1a, 0x01)
        val END = byteArrayOf(0x1a, 0x03, 0x00)
    }

    /** Hazır paketler: 1b + sıra (BE) + veri. */
    val packets: List<ByteArray>
    /** Her bloğun ilk paket sırası, artan. */
    private val blockStarts: IntArray

    var next = 0
        private set
    private var finishing = false

    init {
        val data = packetSize - HEAD
        require(data > 0) { "Paket boyu çok küçük" }
        val out = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var pos = 0
        while (pos < file.size) {
            val blockEnd = minOf(pos + BLOCK, file.size)
            starts += out.size
            var p = pos
            while (p < blockEnd) {
                val n = minOf(data, blockEnd - p)
                val seq = out.size
                require(seq <= 0xFFFF) { "Dosya çok büyük" }
                val pk = ByteArray(n + HEAD)
                pk[0] = 0x1b
                pk[1] = (seq shr 8).toByte()
                pk[2] = seq.toByte()
                System.arraycopy(file, p, pk, HEAD, n)
                out += pk
                p += n
            }
            pos = blockEnd
        }
        packets = out
        blockStarts = starts.toIntArray()
    }

    val total: Int get() = packets.size
    val percent: Int get() = if (total == 0) 0 else minOf(100, next * 100 / total)

    /** idx paketinin bloğunun bittiği yer (hariç). */
    private fun blockEnd(idx: Int): Int {
        for (s in blockStarts) if (s > idx) return s
        return packets.size
    }

    /** 1a 02 yankısı geldi: ilk blok. */
    fun begin(): Step.Send {
        next = 0
        return Step.Send(0, blockEnd(0))
    }

    /** Gönderilen blok tamamlanınca beklenen yanıt işlenir. */
    fun onReply(v: ByteArray): Step {
        if (v.size < 3) return Step.Ignore
        val b0 = v[0].toInt() and 0xFF
        if ((b0 != 0x1a && b0 != 0x26) || v[1] != 0x03.toByte()) return Step.Ignore
        return when (val code = v[2].toInt() and 0xFF) {
            0 -> if (finishing) Step.Done else Step.Ignore
            4 -> {
                if (finishing) return Step.Ignore
                next = blockEnd(next)
                if (next >= packets.size) {
                    finishing = true
                    Step.Finish
                } else {
                    Step.Send(next, blockEnd(next))
                }
            }
            3 -> {
                if (v.size < 5) return Step.Fail(3, "Saat kopukluk bildirdi ama sıra yok.")
                val idx = ((v[3].toInt() and 0xFF) shl 8) or (v[4].toInt() and 0xFF)
                if (idx !in packets.indices) return Step.Fail(3, "Saat geçersiz sıra istedi ($idx).")
                next = idx
                finishing = false
                Step.Send(idx, blockEnd(idx))
            }
            else -> Step.Fail(code, if (code == 2) "Saat dosyayı reddetti (kod 2)." else "Saat hata bildirdi (kod $code).")
        }
    }

    /** Son blok onayı gelmeden zaman aşımı olursa Haylou Fun gibi yine bitişe geçilir. */
    fun forceFinish(): Step {
        finishing = true
        return Step.Finish
    }
}

object Rgb565 {
    /** Büyük uçlu RGB565 → opak ARGB. */
    fun toArgb(b: ByteArray, offset: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var o = offset
        for (i in out.indices) {
            val v = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
            val r = (v shr 11) and 31
            val g = (v shr 5) and 63
            val bl = v and 31
            out[i] = (0xFF shl 24) or (((r * 255 + 15) / 31) shl 16) or (((g * 255 + 31) / 63) shl 8) or ((bl * 255 + 15) / 31)
            o += 2
        }
        return out
    }
}
