package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Beklenen baytlar kullanıcının btsnoop kaydından (haylou_notification_test.log,
 * kayıt indeksleri 1482-1485, Write ATT handle 0x0022) birebir alınmıştır.
 */
class UteTest {

    private val recorded = listOf(
        "0F 00 04 30 00 4C 00 53 00 30 00 35 00 54 00 45 00 53 00 54",
        "0F 01 00 31 00 32 00 33 00 3A 00 20 00 4C 00 53 00 30 00 35",
        "0F 02 00 54 00 45 00 53 00 54 00 31 00 32 00 33",
        "0F FD",
    ).map(Hex::parse)

    @Test
    fun notificationMatchesRecordingByteForByte() {
        val packets = NotificationPacketizer.build("LS05TEST123", "LS05TEST123")
        assertEquals(recorded.size, packets.size, "paket sayısı")
        packets.zip(recorded).forEachIndexed { i, (got, want) ->
            assertContentEquals(want, got, "paket $i: ${Hex.of(got)} != ${Hex.of(want)}")
        }
    }

    @Test
    fun everyPacketFitsInTwentyBytes() {
        val long = "Ş".repeat(200)
        NotificationPacketizer.build("Başlık", long).forEach {
            assertTrue(it.size <= UTE_CHUNK_SIZE, "paket ${it.size} bayt")
        }
    }

    @Test
    fun shortMessageIsOnePacketPlusEnd() {
        val packets = NotificationPacketizer.buildFromText("Selam")
        assertEquals(2, packets.size)
        assertEquals(4 + 10, packets[0].size)
        assertContentEquals(byteArrayOf(0x0F, 0xFD.toByte()), packets[1])
    }

    @Test
    fun turkishCharactersEncodeAsUtf16BigEndian() {
        val bytes = NotificationPacketizer.encode("şğıöüç")
        assertContentEquals(Hex.parse("01 5F 01 1F 01 31 00 F6 00 FC 00 E7"), bytes)
    }

    @Test
    fun lengthFieldIsTotalAndTextIsCappedEven() {
        val packets = NotificationPacketizer.buildFromText("x".repeat(500))
        assertEquals(NotificationPacketizer.MAX_TEXT_BYTES, packets[0][3].toInt() and 0xFF)
        assertEquals(0, NotificationPacketizer.MAX_TEXT_BYTES % 2)
    }

    @Test
    fun parsesRecordedReplies() {
        assertEquals(UteFrame.Battery(46), UteParser.parse(Hex.parse("03 2E")))
        val fw = "02 " + "RH192SV002066".toByteArray().joinToString(" ") { "%02X".format(it) }
        assertEquals(UteFrame.Firmware("RH192SV002066"), UteParser.parse(Hex.parse(fw)))
        assertEquals(UteFrame.NotificationSegmentAck(1), UteParser.parse(Hex.parse("0F 01")))
        assertEquals(UteFrame.NotificationTransferDone(4, 0x30), UteParser.parse(Hex.parse("0F FD 04 30")))
    }

    @Test
    fun vibrateMatchesRecordingAndRepliesParse() {
        // Kayıt indeksi 1486 (yazma) ve 1491 (yanıt).
        assertContentEquals(Hex.parse("06 00 00 00 02 02 00 00"), UteCommand.VIBRATE_TEXT)
        assertTrue(UteParser.parse(Hex.parse("06 00")) is UteFrame.VibrateAck)
        assertEquals(UteFrame.AuthStatus(1), UteParser.parse(Hex.parse("20 04 01")))
    }

    @Test
    fun redactionHidesNotificationText() {
        val first = NotificationPacketizer.build("LS05TEST123", "LS05TEST123")[0]
        val shown = Hex.redacted(first)
        assertTrue("4C" !in shown, shown)
        assertEquals("0F FD 04 30", Hex.redacted(Hex.parse("0F FD 04 30")))
    }
}
