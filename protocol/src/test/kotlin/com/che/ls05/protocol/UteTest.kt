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
    fun lengthFieldIsTotalAndTextIsCappedAt80Units() {
        val packets = NotificationPacketizer.buildFromText("x".repeat(500))
        assertEquals(160, NotificationPacketizer.MAX_TEXT_BYTES)
        assertEquals(160, packets[0][3].toInt() and 0xFF, "saat 0F FD 04 A0 ile 160 bayt alıyor")
        // 4+16 ilk paket, 8 devam paketi × 18 = 144, toplam 160; + bitiş
        assertEquals(1 + 8 + 1, packets.size)
        assertEquals(80, NotificationPacketizer.truncate("ş".repeat(81), 80).length)
        assertEquals("abc", NotificationPacketizer.truncate("abc", 80))
    }

    @Test
    fun truncationNeverSplitsSurrogatePair() {
        // 79 harf + emoji (vekil çifti): 80. birim yüksek vekil olur, kesme 79'da yapılmalı.
        val text = "a".repeat(79) + "\uD83D\uDE00" + "b"
        val cut = NotificationPacketizer.truncate(text, 80)
        assertEquals(79, cut.length)
        assertTrue(!Character.isHighSurrogate(cut.last()))
        assertEquals(158, NotificationPacketizer.encode(text).size)
        // Emoji tam sığıyorsa korunur.
        val fits = "a".repeat(78) + "\uD83D\uDE00"
        assertEquals(80, NotificationPacketizer.truncate(fits, 80).length)
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
    fun bindRequestFormatAndRedaction() {
        // Örnek kimlik; gerçek kimlik depoya yazılmaz.
        val id = UteCommand.parseUserId("0A0B0C0D")!!
        assertContentEquals(Hex.parse("20 02 0A 0B 0C 0D"), UteCommand.bindRequest(id))
        assertEquals("20 02 XX XX XX XX (kimlik gizli)", Hex.redacted(UteCommand.bindRequest(id)))
        assertEquals(null, UteCommand.parseUserId("12345"))
        assertEquals(null, UteCommand.parseUserId("GGGGGGGG"))
        assertEquals(UteFrame.AuthStatus(1), UteParser.parse(Hex.parse("20 04 01")))
    }

    @Test
    fun timeSyncMatchesRecording() {
        // Kayıt: 04 07 EA 0B 0B 13 33 2B 03 = 2026-11-11 19:51:43, UTC+3
        assertContentEquals(Hex.parse("04 07 EA 0B 0B 13 33 2B 03"), UteCommand.timeSync(2026, 11, 11, 19, 51, 43, 3))
        assertTrue(UteParser.parse(Hex.parse("04 07 EA 0B 0B 13 33 2B 03")) is UteFrame.TimeAck)
    }

    @Test
    fun sanitizerRemovesEmojiAndKeepsTurkish() {
        assertEquals("Merhaba şğıöüç ŞĞİÖÜÇ", TextSanitizer.clean("Merhaba \uD83D\uDE00 şğıöüç\nŞĞİÖÜÇ \u2764\uFE0F"))
        val cleaned = TextSanitizer.clean("a\uD83D\uDC4D".repeat(200))
        NotificationPacketizer.encode(cleaned).let { assertEquals(0, it.size % 2) }
        assertEquals("", TextSanitizer.clean("\uD83D\uDE00\u200D"))
    }

    @Test
    fun appTypeMapping() {
        assertEquals(7, AppTypeMap.forPackage("com.whatsapp.w4b"))
        assertEquals(24, AppTypeMap.forPackage("org.telegram.messenger"))
        assertEquals(4, AppTypeMap.forPackage("com.example.unknown"))
        assertTrue("com.whatsapp" in AppTypeMap.defaultOn)
    }

    @Test
    fun redactionHidesNotificationText() {
        val first = NotificationPacketizer.build("LS05TEST123", "LS05TEST123")[0]
        val shown = Hex.redacted(first)
        assertTrue("4C" !in shown, shown)
        assertEquals("0F FD 04 30", Hex.redacted(Hex.parse("0F FD 04 30")))
        // Tek karakterlik son parça (0F 01 00 41) da gizlenmeli.
        assertTrue("41" !in Hex.redacted(Hex.parse("0F 01 00 41")), "kısa parça gizlenmeli")
        assertEquals("0F FD", Hex.redacted(Hex.parse("0F FD")))
        assertEquals("0F 01", Hex.redacted(Hex.parse("0F 01")), "saatin parça onayı görünür kalır")
    }
}
