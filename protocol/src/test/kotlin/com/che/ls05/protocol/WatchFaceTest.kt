package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kadran dosyası doğrulama ve aktarım. Gerçek kadran dosyası repoda yok; sentetik dosya kullanılır. */
class WatchFaceTest {

    private fun le16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, o: Int, v: Long) { le16(b, o, (v and 0xFFFF).toInt()); le16(b, o + 2, (v shr 16).toInt()) }

    /** Tek arka plan öğeli 240x240 sentetik kadran. */
    private fun synthetic(extra: Int = 0): ByteArray {
        val imgOff = 0x18 + 24 * 2
        val b = ByteArray(imgOff + 240 * 240 * 2 + extra)
        le16(b, 0, 0x138a)
        le16(b, 12, 240); le16(b, 14, 240)
        val e = 0x18
        le16(b, e, 17); le16(b, e + 2, 240); le32(b, e + 4, imgOff.toLong()); le16(b, e + 8, 240); b[e + 16] = 1
        for (i in imgOff until b.size) b[i] = (i * 7).toByte()
        le32(b, 4, (b.size - 24).toLong())
        le32(b, 8, FaceFile.crc(b))
        return b
    }

    @Test
    fun validFileParses() {
        val f = FaceFile.parse(synthetic()).getOrThrow()
        assertEquals(240, f.width)
        assertEquals(1, f.elements.size)
        assertEquals(17, f.elements[0].type)
    }

    @Test
    fun corruptByteFailsCrc() {
        val b = synthetic(); b[b.size - 1] = (b[b.size - 1] + 1).toByte()
        assertTrue(FaceFile.parse(b).isFailure)
    }

    @Test
    fun truncatedFileFails() {
        val b = synthetic().copyOf(1000)
        assertTrue(FaceFile.parse(b).isFailure)
    }

    @Test
    fun wrongScreenFails() {
        assertTrue(FaceFile.parse(synthetic(), 466, 466).isFailure)
    }

    @Test
    fun configReplyGivesScreen() {
        val v = byteArrayOf(0x1a, 0x01, -1, -1, -1, -1, 0x00, 0xf0.toByte(), 0x00, 0xf0.toByte(), 0, 0)
        assertEquals(240 to 240, FaceConfig.screen(v))
    }

    @Test
    fun packetsMatchHaylouLayout() {
        // 244 baytlık paket: 3 baş + 241 veri. 4096 = 16 x 241 + 240 → blok başına 17 paket.
        val file = ByteArray(4096 * 2 + 100) { it.toByte() }
        val t = FaceTransfer(file, 244)
        assertEquals(17 + 17 + 1, t.total)
        assertEquals(244, t.packets[0].size)
        assertEquals(243, t.packets[16].size)
        assertEquals(0x1b, t.packets[17][0].toInt())
        assertEquals(0, t.packets[17][1].toInt()); assertEquals(17, t.packets[17][2].toInt())
        assertEquals(file[4096], t.packets[17][3])
        assertEquals(103, t.packets.last().size)
        assertEquals(file.size, t.packets.sumOf { it.size - 3 })
    }

    @Test
    fun happyPath() {
        val t = FaceTransfer(ByteArray(4096 * 2 + 100), 244)
        val s0 = t.begin(); assertEquals(0, s0.from); assertEquals(17, s0.to)
        val ok = byteArrayOf(0x26, 0x03, 0x04)
        val s1 = t.onReply(ok) as FaceTransfer.Step.Send; assertEquals(17, s1.from); assertEquals(34, s1.to)
        val s2 = t.onReply(ok) as FaceTransfer.Step.Send; assertEquals(34, s2.from); assertEquals(35, s2.to)
        assertTrue(t.onReply(ok) is FaceTransfer.Step.Finish)
        assertTrue(t.onReply(byteArrayOf(0x1a, 0x03, 0x00)) is FaceTransfer.Step.Done)
    }

    @Test
    fun resendFromWatchIndex() {
        val t = FaceTransfer(ByteArray(4096 * 2), 244)
        t.begin()
        val s = t.onReply(byteArrayOf(0x26, 0x03, 0x03, 0x00, 0x05)) as FaceTransfer.Step.Send
        assertEquals(5, s.from); assertEquals(17, s.to)
    }

    @Test
    fun errorCodeFails() {
        val t = FaceTransfer(ByteArray(5000), 244)
        t.begin()
        assertTrue(t.onReply(byteArrayOf(0x26, 0x03, 0x02)) is FaceTransfer.Step.Fail)
    }

    @Test
    fun unrelatedFramesIgnored() {
        val t = FaceTransfer(ByteArray(5000), 244)
        t.begin()
        assertTrue(t.onReply(byteArrayOf(0x09, 0x01, 0x02)) is FaceTransfer.Step.Ignore)
        // Bitiş öncesi gelen 1a 03 00 başarı sayılmaz.
        assertTrue(t.onReply(byteArrayOf(0x1a, 0x03, 0x00)) is FaceTransfer.Step.Ignore)
    }

    @Test
    fun rgb565BigEndianDecodes() {
        val px = Rgb565.toArgb(byteArrayOf(0xF8.toByte(), 0x00, 0x07, 0xE0.toByte(), 0x00, 0x1F, -1, -1), 0, 4, 1)
        assertEquals(0xFFFF0000.toInt(), px[0])
        assertEquals(0xFF00FF00.toInt(), px[1])
        assertEquals(0xFF0000FF.toInt(), px[2])
        assertEquals(0xFFFFFFFF.toInt(), px[3])
    }

    @Test
    fun previewPicksBackgroundWhenNoPreview() {
        val f = FaceFile.parse(synthetic()).getOrThrow()
        assertEquals(17, f.previewElement()?.type)
    }
}
