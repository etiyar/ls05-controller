package com.che.ls05.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kadran yazıcı. Resmi dosya repoda yok; LS05_FACE ortam değişkeni verilirse yeniden yazma testi de çalışır. */
class FaceWriterTest {

    private fun solid(w: Int, h: Int, frames: Int, v: Int): ByteArray {
        val b = ByteArray(w * h * 2 * frames)
        for (i in b.indices step 2) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
        return b
    }

    private fun minimal(): List<FaceSprite> = listOf(
        FaceSprite(17, 240, 240, 0, 0, 1, solid(240, 240, 1, 0x0020)),
        FaceSprite(4, 20, 30, 60, 100, 14, solid(20, 30, 14, 0xFFFF), maxCells = 2),
        FaceSprite(10, 240, 240, 0, 0, 1, solid(240, 240, 1, 0x0020)),
    )

    @Test
    fun writtenFileParsesInTypeOrder() {
        val b = FaceWriter.write(0xF0F0, minimal())
        val f = FaceFile.parse(b).getOrThrow()
        assertEquals(listOf(4, 10, 17), f.elements.map { it.type })
        assertEquals(0xF0F0, f.id)
        // Veri 24 + 3*24 + 2'de başlar.
        assertEquals(98L, f.elements[0].offset)
        assertEquals(0, b[16].toInt()); assertEquals(1, b[17].toInt()); assertEquals(-1, b[23].toInt())
    }

    @Test
    fun rejectsOverflowAndWrongFrames() {
        val wide = FaceSprite(4, 20, 30, 210, 100, 14, solid(20, 30, 14, 0xFFFF), maxCells = 2)
        assertTrue(FaceWriter.check(listOf(wide)).isNotEmpty())
        val neg = FaceSprite(5, 20, 30, 0, 230, 14, solid(20, 30, 14, 0xFFFF))
        assertTrue(FaceWriter.check(listOf(neg)).isNotEmpty())
        val frames = FaceSprite(25, 10, 10, 0, 0, 13, solid(10, 10, 13, 0xFFFF))
        assertTrue(FaceWriter.check(listOf(frames)).isNotEmpty())
        val unknown = FaceSprite(48, 10, 10, 0, 0, 1, solid(10, 10, 1, 0xFFFF))
        assertTrue(FaceWriter.check(listOf(unknown)).isNotEmpty())
    }

    @Test
    fun dedupeSharesOffset() {
        val a = FaceSprite(19, 10, 10, 10, 10, 14, solid(10, 10, 14, 0xF800))
        val b = FaceSprite(25, 10, 10, 10, 40, 14, solid(10, 10, 14, 0xF800))
        val f = FaceFile.parse(FaceWriter.write(0xF001, listOf(a, b), dedupe = true)).getOrThrow()
        assertEquals(f.elements[0].offset, f.elements[1].offset)
    }

    @Test
    fun pixelRule() {
        assertEquals(0, Rgb565Encode.encode(0, 0))
        assertEquals(0x0020, Rgb565Encode.encode(0xFF000000.toInt(), 0))
        assertEquals(0x0020, Rgb565Encode.encode(0xFF050505.toInt(), 0))
        assertEquals(0, Rgb565Encode.encode(0x20FFFFFF, 0))
        assertEquals(0xFFFF, Rgb565Encode.encode(0xFFFFFFFF.toInt(), 0))
        // Yarı saydam beyaz, siyah zemin: yaklaşık yarı gri.
        val mid = Rgb565Encode.encode(0x80FFFFFF.toInt(), 0)
        assertEquals(16, (mid shr 11) and 31)
    }

    @Test
    fun officialRewriteIsIdenticalExceptId() {
        val path = System.getenv("LS05_FACE") ?: return
        val orig = File(path).readBytes()
        val f = FaceFile.parse(orig).getOrThrow()
        val re = FaceWriter.write(f.id, FaceWriter.spritesOf(f))
        assertTrue(orig.contentEquals(re), "yeniden yazım farklı")
    }
}
