package com.che.ls05.protocol

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Değerler yapaydır; yalnızca düzen gerçek kayıtlarla aynıdır. */
class HealthLogTest {

    private fun e(hex: String, at: Long = 0) = HealthEntry(at, 180, Hex.parse(hex))
    private val day = UteDate(2026, 11, 12)

    private fun step(d: Int, hour: Int, total: Int) =
        "0A 07 EA 0B %02X %02X %02X %02X 00 00 00 00 00 00 00 00 %02X %02X".format(d, hour, total shr 8, total and 0xFF, total shr 8, total and 0xFF)

    @Test
    fun whatIsStored() {
        assertTrue(HealthLog.shouldStore(Hex.parse(step(12, 6, 300))))
        assertTrue(!HealthLog.shouldStore(Hex.parse(step(12, 6, 300).replaceFirst("0A", "09"))), "canlı adım saklanmaz")
        assertTrue(!HealthLog.shouldStore(Hex.parse("0A FD CF")))
        assertTrue(HealthLog.shouldStore(Hex.parse("18 03 07 EA 0B 0C 07 14 48")))
        assertTrue(!HealthLog.shouldStore(Hex.parse("18 03 07 EA 0B 0C 0B 14 00")), "0 nabız saklanmaz")
        assertTrue(HealthLog.shouldStore(Hex.parse("18 04 07 EA 0B 0C 07 10 64 32 46")))
        assertTrue(HealthLog.shouldStore(Hex.parse("18 07 EA 0B 0C 08 FF FF FF FF FF 3C FF FF FF FF FF FF")))
        assertTrue(!HealthLog.shouldStore(Hex.parse("18 FD A2")))
        assertTrue(HealthLog.shouldStore(Hex.parse("1E 17 1E 02 01 00 3C")))
        assertTrue(!HealthLog.shouldStore(Hex.parse("1E 00")))
        assertTrue(HealthLog.shouldStore(Hex.parse("1D 01 07 EA 0B 0D 02")))
        assertTrue(!HealthLog.shouldStore(Hex.parse("1D 02")))
        assertTrue(!HealthLog.shouldStore(Hex.parse("06 01")))
    }

    @Test
    fun lineRoundTripAndCorruptLinesAreSkipped() {
        val line = HealthLog.line(e(step(12, 6, 300), at = 1_800_000_000))
        val text = "$line\nyarım satır\n123\t180\tZZ 01\n456\t180\t0A 0\n\n$line"
        val all = HealthLog.parseAll(text)
        assertEquals(2, all.size, "bozuk satırlar diğerlerini engellemez")
        assertEquals(1_800_000_000L, all[0].receivedSec)
        assertEquals(180, all[0].offsetMin)
        assertEquals(step(12, 6, 300), all[0].hex)
    }

    @Test
    fun stepsLatestRecordWinsAndLiveMerges() {
        val v = HealthView(
            listOf(e(step(12, 6, 100)), e(step(12, 7, 20)), e(step(12, 7, 40)), e(step(11, 9, 999))),
            live = listOf(StepHour(day, 8, 15, 0, 15, 0, 0, true), StepHour(day, 7, 30, 0, 30, 0, 0, true)),
        )
        assertEquals(100 + 40 + 15, v.stepsForDay(day))
        assertEquals(999, v.stepsForDay(UteDate(2026, 11, 11)))
        assertEquals(null, v.stepsForDay(UteDate(2026, 11, 10)))
        assertEquals(40, v.hourlySteps(day)[7])
        assertEquals(0, v.hourlySteps(day)[5])
    }

    @Test
    fun heartBlockTimesEndAtBlockHour() {
        // Gerçek kayıtla doğrulanan düzen: blok saati 8, 7. değer 07:20, 9. değer 07:40.
        val v = HealthView(listOf(e("18 07 EA 0B 0C 08 FF FF FF FF FF FF FF 46 FF 50 FF FF")))
        assertEquals(
            listOf(HeartPoint(LocalDateTime.of(2026, 11, 12, 7, 20), 70), HeartPoint(LocalDateTime.of(2026, 11, 12, 7, 40), 80)),
            v.heartPoints(),
        )
    }

    @Test
    fun heartBlockAtMidnightBelongsMostlyToPreviousDay() {
        val v = HealthView(listOf(e("18 07 EA 0B 0C 00 3C FF FF FF FF FF FF FF FF FF 41 46")))
        assertEquals(
            listOf(
                HeartPoint(LocalDateTime.of(2026, 11, 11, 22, 10), 60),
                HeartPoint(LocalDateTime.of(2026, 11, 11, 23, 50), 65),
                HeartPoint(LocalDateTime.of(2026, 11, 12, 0, 0), 70),
            ),
            v.heartPoints(),
        )
    }

    @Test
    fun monthBoundary() {
        val v = HealthView(listOf(e("18 07 EA 0C 01 00 3C FF FF FF FF FF FF FF FF FF FF FF")))
        assertEquals(LocalDateTime.of(2026, 11, 30, 22, 10), v.heartPoints().single().at)
    }

    @Test
    fun lastHeartIgnoresZeroAndPrefersNewestRegardlessOfArrivalOrder() {
        val v = HealthView(
            listOf(
                e("18 03 07 EA 0B 0C 15 14 00"),
                e("18 03 07 EA 0B 0C 09 0A 58"),
                e("18 07 EA 0B 0C 08 FF FF FF FF FF FF FF 46 FF 50 FF FF"),
            ),
        )
        assertEquals(HeartPoint(LocalDateTime.of(2026, 11, 12, 9, 10), 88), v.lastHeart())
        assertEquals(
            HeartPoint(LocalDateTime.of(2026, 11, 12, 7, 40), 80),
            v.lastHeart(notAfter = LocalDateTime.of(2026, 11, 12, 8, 0)),
        )
        assertEquals(null, HealthView(listOf(e("18 03 07 EA 0B 0C 15 14 00"))).lastHeart())
    }

    @Test
    fun invalidDatesDoNotCrash() {
        val v = HealthView(listOf(e("18 07 EA 0D 28 08 3C 3C 3C 3C 3C 3C 3C 3C 3C 3C 3C 3C"), e(step(40, 30, 5))))
        assertEquals(0, v.heartPoints().size)
        assertEquals(null, v.stepsForDay(day))
    }

    @Test
    fun summaryAndRealHeartFrame() {
        val v = HealthView(listOf(e("18 04 07 EA 0B 0C 07 10 64 32 46")))
        assertEquals(100, v.heartSummary!!.max)
        assertEquals(null, (UteParser.parse(Hex.parse("16 11")) as UteFrame.RealHeart).bpm)
        assertEquals(72, (UteParser.parse(Hex.parse("16 11 00 48")) as UteFrame.RealHeart).bpm)
    }

    @Test
    fun rulersFitInOneNotification() {
        assertEquals(125, LengthTest.ruler().length)
        assertTrue(LengthTest.ruler().startsWith("01 02 03"))
        assertTrue(LengthTest.ruler().endsWith("41 42"))
        assertEquals(126, LengthTest.turkishRuler().length)
        assertTrue(LengthTest.turkishRuler().startsWith("ş01ğ02ı03"))
        assertTrue(NotificationPacketizer.encode(LengthTest.turkishRuler()).size <= NotificationPacketizer.MAX_TEXT_BYTES)
        assertEquals(LengthTest.turkishRuler(), TextSanitizer.clean(LengthTest.turkishRuler()), "temizleyici cetveli bozmamalı")
    }

    // Yapay gece; düzen 13 Kasım kaydıyla aynı (başlık 1D 01 + tarih + sayı, 1E + 6 baytlık bölümler).
    private val night = listOf(
        "1D 01 07 EA 0B 0D 04",
        "1E 17 1E 02 01 00 3C 00 1E 01 01 00 5A 02 00 02 01 00 64 03 28 03 01 00 0A",
    )

    @Test
    fun sleepNightAcrossMidnight() {
        val v = HealthView(night.map { e(it) })
        val n = v.sleepFor(UteDate(2026, 11, 13))!!
        assertEquals(LocalDateTime.of(2026, 11, 12, 23, 30), n.start)
        assertEquals(LocalDateTime.of(2026, 11, 13, 3, 50), n.end)
        assertEquals(90, n.deepMin)
        assertEquals(160, n.lightMin)
        assertEquals(10, n.awakeMin)
        assertEquals(250, n.sleepMin)
        assertEquals(null, v.sleepFor(UteDate(2026, 11, 12)))
    }

    @Test
    fun sleepRepeatedReadCountsOnceAndNeedsHeader() {
        val twice = HealthView((night + night).map { e(it) })
        assertEquals(250, twice.sleepFor(UteDate(2026, 11, 13))!!.sleepMin)
        val noHeader = HealthView(listOf(e(night[1])))
        assertEquals(null, noHeader.sleepFor(UteDate(2026, 11, 13)))
    }

    @Test
    fun sleepFramesParse() {
        val h = UteParser.parse(Hex.parse(night[0])) as UteFrame.SleepHeader
        assertEquals(UteDate(2026, 11, 13), h.date)
        assertEquals(4, h.count)
        val d = UteParser.parse(Hex.parse(night[1])) as UteFrame.SleepData
        assertEquals(SleepSegment(23, 30, 2, 60), d.segments.first())
        assertEquals(4, d.segments.size)
        assertTrue(UteParser.parse(Hex.parse("1D 02")) is UteFrame.Echo)
    }

    @Test
    fun sleepDataStoredBeforeHeaderIsStillPaired() {
        // v0.4.0 bölümleri saklayıp başlığı saklamadı; v0.4.1 başlığı sonradan yazdı, bölümler tekrar yazılmadı.
        val v = HealthView(listOf(e(night[1], at = 100), e(step(13, 9, 300), at = 101), e(night[0], at = 200)))
        assertEquals(250, v.sleepFor(UteDate(2026, 11, 13))!!.sleepMin)
        // Sayısı tutmayan bölüm grubu eşlenmez.
        val wrong = HealthView(listOf(e("1E 17 1E 02 01 00 3C"), e(night[0])))
        assertEquals(null, wrong.sleepFor(UteDate(2026, 11, 13)))
    }
}
