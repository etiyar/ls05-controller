package com.che.ls05.protocol

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.6.1: alarm hatırlatma notu zamanlaması. */
class V061Test {

    private val ist = ZoneId.of("Europe/Istanbul")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun ms(z: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, z).toInstant().toEpochMilli()

    // 13 Kasım 2026 Cuma.
    private val weekdays7 = AlarmSetting(true, 7, 0, UteAlarm.WEEKDAYS)

    @Test
    fun noteIsCleanedAndCut() {
        assertEquals("Sabah ilacı", AlarmReminder.cleanNote("  Sabah\n  ilacı "))
        assertEquals(40, AlarmReminder.cleanNote("x".repeat(60)).length)
        assertEquals("", AlarmReminder.cleanNote(null))
        assertEquals("", AlarmReminder.cleanNote("   \n "))
    }

    @Test
    fun inactiveWithoutNoteOrWhenOff() {
        assertNull(AlarmReminder.next(weekdays7, "", ms(ist, 2026, 11, 13, 6, 0), ist))
        assertNull(AlarmReminder.next(weekdays7.copy(enabled = false), "not", ms(ist, 2026, 11, 13, 6, 0), ist))
        assertNull(AlarmReminder.next(null, "not", ms(ist, 2026, 11, 13, 6, 0), ist))
    }

    @Test
    fun nextIsSameDayWhenStillAhead() {
        val n = AlarmReminder.next(weekdays7, "not", ms(ist, 2026, 11, 13, 6, 59), ist)
        assertEquals(ms(ist, 2026, 11, 13, 7, 0), n)
    }

    @Test
    fun nextSkipsWeekendForWeekdayAlarm() {
        // Cuma 07:00 geçti: sonraki hafta içi günü Pazartesi 16 Kasım.
        val n = AlarmReminder.next(weekdays7, "not", ms(ist, 2026, 11, 13, 7, 0), ist)
        assertEquals(ms(ist, 2026, 11, 16, 7, 0), n)
    }

    @Test
    fun maskBitsMatchWatchOrder() {
        // bit0 = Pazar. 15 Kasım 2026 Pazar.
        val sunday = AlarmSetting(true, 9, 30, 0x01)
        assertEquals(ms(ist, 2026, 11, 15, 9, 30), AlarmReminder.next(sunday, "n", ms(ist, 2026, 11, 13, 12, 0), ist))
        // bit6 = Cumartesi, 14 Kasım.
        val saturday = AlarmSetting(true, 9, 30, 0x40)
        assertEquals(ms(ist, 2026, 11, 14, 9, 30), AlarmReminder.next(saturday, "n", ms(ist, 2026, 11, 13, 12, 0), ist))
    }

    @Test
    fun singleDayAlarmWrapsToNextWeek() {
        val friday = AlarmSetting(true, 7, 0, 0x20)
        assertEquals(ms(ist, 2026, 11, 20, 7, 0), AlarmReminder.next(friday, "n", ms(ist, 2026, 11, 13, 8, 0), ist))
    }

    @Test
    fun validityFollowsCurrentSettings() {
        val at = ms(ist, 2026, 11, 16, 7, 0)
        assertTrue(AlarmReminder.stillValid(weekdays7, "not", at, ist))
        assertFalse(AlarmReminder.stillValid(weekdays7.copy(minute = 5), "not", at, ist))
        assertFalse(AlarmReminder.stillValid(weekdays7.copy(enabled = false), "not", at, ist))
        assertFalse(AlarmReminder.stillValid(weekdays7, " ", at, ist))
        assertFalse(AlarmReminder.stillValid(weekdays7.copy(mask = 0x40), "not", at, ist))
    }

    @Test
    fun staleAfterFiveMinutes() {
        val at = ms(ist, 2026, 11, 16, 7, 0)
        assertFalse(AlarmReminder.stale(at, at))
        assertFalse(AlarmReminder.stale(at, at + 5 * 60_000L))
        assertTrue(AlarmReminder.stale(at, at + 5 * 60_000L + 1))
    }

    @Test
    fun dayKeyUsesLocalDate() {
        assertEquals("2026-11-16", AlarmReminder.dayKey(ms(ist, 2026, 11, 16, 0, 30), ist))
    }

    @Test
    fun dstGapMovesForward() {
        // Berlin 29 Mart 2026 Pazar: 02:00 → 03:00. 02:30 alarmı 03:30'a kayar.
        val sunday = AlarmSetting(true, 2, 30, 0x01)
        val n = AlarmReminder.next(sunday, "n", ms(berlin, 2026, 3, 28, 12, 0), berlin)!!
        assertEquals(ms(berlin, 2026, 3, 29, 3, 30), n)
        assertTrue(AlarmReminder.stillValid(sunday, "n", n, berlin))
    }
}
