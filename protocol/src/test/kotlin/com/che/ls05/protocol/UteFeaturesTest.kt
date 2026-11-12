package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Alarm, rahatsız etme ve hava baytları 2026-11-12 Haylou Fun yakalamasından (Write, handle 0x22).
 * Şehir adı yakalamadan alınmadı. Adım ve nabız değerleri yapaydır (sağlık verisi depoya girmez);
 * yalnızca düzen yakalamadakiyle aynıdır.
 */
class UteFeaturesTest {

    @Test
    fun alarmMatchesRecording() {
        assertContentEquals(Hex.parse("06 3E 07 00 02 08 06 00 01"), UteAlarm.build(1, 7, 0, UteAlarm.WEEKDAYS, true))
        assertContentEquals(Hex.parse("06 7F 07 12 02 08 06 00 02"), UteAlarm.build(2, 7, 18, UteAlarm.EVERY_DAY, true))
        assertContentEquals(Hex.parse("06 60 07 12 00 00 00 00 02"), UteAlarm.build(2, 7, 18, UteAlarm.EVERY_DAY, false))
    }

    @Test
    fun alarmRejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { UteAlarm.build(0, 7, 0, UteAlarm.EVERY_DAY, true) }
        assertFailsWith<IllegalArgumentException> { UteAlarm.build(4, 7, 0, UteAlarm.EVERY_DAY, true) }
        assertFailsWith<IllegalArgumentException> { UteAlarm.build(1, 24, 0, UteAlarm.EVERY_DAY, true) }
        assertFailsWith<IllegalArgumentException> { UteAlarm.build(1, 7, 0, 0, true) }
        assertEquals("Hafta sonu", UteAlarm.maskLabel(UteAlarm.SATURDAY or UteAlarm.SUNDAY))
        assertEquals("Pt Ça", UteAlarm.maskLabel(UteAlarm.MONDAY or UteAlarm.WEDNESDAY))
    }

    @Test
    fun alarmAckIsNotVibrateAck() {
        assertEquals(UteFrame.AlarmAck(2), UteParser.parse(Hex.parse("06 02")))
        assertTrue(UteParser.parse(Hex.parse("06 00")) is UteFrame.VibrateAck)
    }

    @Test
    fun dndMatchesRecording() {
        assertContentEquals(Hex.parse("15 00 16 00 07 00 01"), UteDnd.build(22, 0, 7, 0, true))
        assertContentEquals(Hex.parse("15 00 16 00 07 00 00"), UteDnd.build(22, 0, 7, 0, false))
        val f = UteParser.parse(Hex.parse("15 00 16 00 07 00 01"))
        assertTrue(f is UteFrame.DndAck && f.enabled)
    }

    @Test
    fun deviceStatusDndBit() {
        val off = UteParser.parse(Hex.parse("0C 02 42 00 00")) as UteFrame.DeviceStatus
        val on = UteParser.parse(Hex.parse("0C 02 4A 00 00")) as UteFrame.DeviceStatus
        assertEquals(false, off.dndOn)
        assertEquals(true, on.dndOn)
    }

    @Test
    fun weatherLayoutMatchesRecordingExceptCity() {
        val days = listOf(
            UteWeather.Day(2, 19, 13), UteWeather.Day(2, 20, 15), UteWeather.Day(7, 20, 12),
            UteWeather.Day(1, 22, 10), UteWeather.Day(1, 21, 10), UteWeather.Day(2, 21, 11), UteWeather.Day(2, 17, 11),
        )
        val p = UteWeather.build(current = 11, days = days, city = "Ev")
        assertEquals(3, p.size)
        assertContentEquals(Hex.parse("11 01 02 00 0B 13 0D 00 00 00 00"), p[0].copyOf(11))
        assertContentEquals(Hex.parse("00 45 00 76 00 00 00 00"), p[0].copyOfRange(11, 19))
        assertContentEquals(Hex.parse("11 02 02 00 14 0F 07 00 14 0C 01 00 16 0A 01 00 15 0A"), p[1])
        assertContentEquals(Hex.parse("11 03 02 00 15 0B 02 00 11 0B"), p[2])
    }

    @Test
    fun weatherNegativeTemperatureAndLongCity() {
        assertEquals(0x83.toByte(), UteWeather.temp(-3))
        assertEquals(-3, UteWeather.decodeTemp(UteWeather.temp(-3)))
        val days = List(7) { UteWeather.Day(UteWeather.SNOW, 0, -5) }
        val p = UteWeather.build(-2, days, "Uzunşehiradı")
        assertEquals(19, p[0].size, "şehir 8 baytta kesilmeli")
    }

    @Test
    fun weatherRedactedInLog() {
        val p = UteWeather.build(5, List(7) { UteWeather.Day(1, 9, 1) }, "Ev")
        assertTrue(Hex.redacted(p[0]).endsWith("(şehir gizli)"))
    }

    @Test
    fun healthRequests() {
        val d = UteDate(2026, 11, 12)
        assertContentEquals(Hex.parse("0A 07 EA 0B 0C 00 00"), UteHealth.stepHistory(d))
        assertContentEquals(Hex.parse("18 FA 07 EA 0B 0C"), UteHealth.heartHistory(d))
        assertContentEquals(Hex.parse("1D 01 07 EA 0B 05 00"), UteHealth.sleepHistory(UteDate(2026, 11, 5)))
    }

    @Test
    fun stepRecordParsesAndSums() {
        // Yapay: 10. saatte 300 adım = 50 koşu + 250 yürüme.
        val rec = Hex.parse("0A 07 EA 0B 0C 0A 01 2C 05 09 01 00 32 0A 28 04 00 FA")
        val s = (UteParser.parse(rec) as UteFrame.Steps).hour
        assertEquals(UteDate(2026, 11, 12), s.date)
        assertEquals(10, s.hour)
        assertEquals(300, s.total)
        assertEquals(50, s.runSteps)
        assertEquals(250, s.walkSteps)
        assertTrue(s.consistent)
        assertEquals(false, s.live)
        val live = Hex.parse("09 07 EA 0B 0C 0B 00 0A 00 00 00 00 00 05 06 01 00 0A")
        assertTrue((UteParser.parse(live) as UteFrame.Steps).hour.live)
        assertEquals(UteFrame.StepHistoryEnd(0x11), UteParser.parse(Hex.parse("0A FD 11")))
    }

    @Test
    fun historyChecksumIsXorWithoutCommandByte() {
        val a = Hex.parse("0A 01 02 03")
        val b = Hex.parse("0A 10 20")
        assertEquals(0x01 xor 0x02 xor 0x03 xor 0x10 xor 0x20, UteHealth.historyChecksum(listOf(a, b)))
    }

    @Test
    fun stepsForDayKeepsLatestPerHour() {
        val d = UteDate(2026, 11, 12)
        fun h(hour: Int, total: Int, live: Boolean = false) = StepHour(d, hour, total, 0, total, 0, 0, live)
        val other = StepHour(UteDate(2026, 11, 11), 9, 999, 0, 999, 0, 0, false)
        assertEquals(100 + 40, HealthSummary.stepsForDay(d, listOf(h(6, 100), h(7, 20, true), h(7, 40, true), other)))
    }

    @Test
    fun heartFrames() {
        val live = UteParser.parse(Hex.parse("18 03 07 EA 0B 0C 07 14 48")) as UteFrame.HeartLive
        assertEquals(7, live.hour); assertEquals(20, live.minute); assertEquals(72, live.bpm)
        val sum = UteParser.parse(Hex.parse("18 04 07 EA 0B 0C 07 10 64 32 46")) as UteFrame.HeartSummary
        assertEquals(100, sum.max); assertEquals(50, sum.min); assertEquals(70, sum.avg)
        val blk = UteParser.parse(Hex.parse("18 07 EA 0B 0C 04 3C FF FF 41 FF FF FF FF FF FF FF 46")) as UteFrame.HeartBlockFrame
        assertEquals(4, blk.block.endHour)
        assertEquals(listOf(60, null, null, 65, null, null, null, null, null, null, null, 70), blk.block.values)
        assertEquals(UteFrame.HeartHistoryEnd(0x57), UteParser.parse(Hex.parse("18 FD 57")))
        assertTrue(UteParser.parse(Hex.parse("18 01")) is UteFrame.Echo)
    }
}
