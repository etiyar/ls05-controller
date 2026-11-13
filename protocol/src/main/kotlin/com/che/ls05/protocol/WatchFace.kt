package com.che.ls05.protocol

import java.util.zip.CRC32

/*
 * Kadran dosyası ve aktarımı (Haylou Fun SendPlateTask ve 12 Kasım kaydıyla birebir).
 *
 * Dosya: 24 bayt başlık + öğe tablosu + RGB565 (büyük uçlu) resimler.
 *   [0..1]  kimlik (u16 LE)
 *   [4..7]  veri boyu (u32 LE) = dosya boyu - 24
 *   [8..11] CRC32 (u32 LE), 24. bayttan dosya sonuna
 *   [12..13] en, [14..15] boy (u16 LE)
 *   0x18'den: 24 baytlık öğeler, tür 0 ile biter:
 *     [tür u16][en u16][konum u32][boy u16][x u16][y u16][00 00][kare u8][7 bayt]
 *
 * Aktarım: 1a 01 yapılandırma, 1a 02 başlat, 4096 baytlık bloklar parça parça 61FF/6101'e
 * (1b + sıra hi + sıra lo + veri), her blok sonunda saat 26 03 04 der, bitiş 1a 03 00.
 */

data class FaceElement(
    val type: Int, val width: Int, val height: Int, val offset: Long,
    val x: Int, val y: Int, val frames: Int,
)

class FaceFile private constructor(
    val bytes: ByteArray,
    val id: Int,
    val width: Int,
    val height: Int,
    val elements: List<FaceElement>,
) {
    val size: Int get() = bytes.size

    /** Önizleme için en uygun öğe: önizleme (10), yoksa arka plan (17), yoksa ilk tam ekran öğe. */
    fun previewElement(): FaceElement? =
        elements.firstOrNull { it.type == 10 && it.width == width && it.height == height }
            ?: elements.firstOrNull { it.type == 17 && it.width == width && it.height == height }
            ?: elements.firstOrNull { it.width == width && it.height == height }

    /** Öğenin ilk karesini ARGB piksel dizisine çevirir (RGB565, büyük uçlu). */
    fun pixels(e: FaceElement): IntArray = Rgb565.toArgb(bytes, e.offset.toInt(), e.width, e.height)

    companion object {
        const val HEADER = 24
        const val TABLE = 0x18
        const val ENTRY = 24
        const val MAX_SIZE = 2 * 1024 * 1024

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
            if (b.size < HEADER + ENTRY) return fail("Dosya çok küçük; kadran dosyası değil.")
            if (b.size > MAX_SIZE) return fail("Dosya 2 MB'tan büyük.")
            if (u32(b, 4) != (b.size - HEADER).toLong()) return fail("Boy alanı tutmuyor; dosya eksik ya da farklı biçimde.")
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
                )
                val end = e.offset + e.width.toLong() * e.height * 2 * maxOf(e.frames, 1)
                if (e.width == 0 || e.height == 0 || e.width > w || e.height > h || e.offset < TABLE || end > b.size) {
                    return fail("Öğe ${els.size + 1} dosya sınırlarının dışında.")
                }
                els += e
                o += ENTRY
                if (els.size > 64) return fail("Öğe tablosu çok uzun.")
            }
            if (els.isEmpty()) return fail("Kadranda öğe yok.")
            return Result.success(FaceFile(b, u16(b, 0), w, h, els))
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
