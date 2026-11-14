package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Kopma uyarısı, geçmiş okuma kapısı, Türkçe harf sadeleştirme ve A2 çerçevesi. */
class MiscTest {

    // ---------- Türkçe harf sadeleştirme ----------

    @Test
    fun foldDefaultOnlyDotlessI() {
        assertEquals("Işik iyi", TurkishFold.apply("Işık iyi"))
        assertEquals("Istanbul", TurkishFold.apply("İstanbul"))
        // ğ, ş, ç, ö, ü varsayılan kümede yok.
        assertEquals("ğş ĞŞ çöü ÇÖÜ", TurkishFold.apply("ğş ĞŞ çöü ÇÖÜ"))
        assertEquals("Iğdir", TurkishFold.apply("Iğdır"))
    }

    @Test
    fun foldExtended() {
        assertEquals("sgi SGI çöü ÇÖÜ", TurkishFold.apply("şğı ŞĞİ çöü ÇÖÜ", TurkishFold.EXTENDED))
    }

    @Test
    fun foldKeepsLengthAndPlainText() {
        val s = "Merhaba, saat 10:30'da buluşalım. ıİ"
        assertEquals(s.length, TurkishFold.apply(s).length)
        val plain = "Hello world 123"
        assertTrue(TurkishFold.apply(plain) === plain)
        assertEquals("", TurkishFold.apply(""))
    }

    // ---------- A2 durum sinyali ----------

    @Test
    fun a2IsStatusSignal() {
        assertEquals(UteFrame.StatusSignal(0x2B), UteParser.parse(Hex.parse("A2 2B")))
        assertEquals(UteFrame.StatusSignal(66), UteParser.parse(Hex.parse("A2 42")))
        assertTrue(UteParser.parse(Hex.parse("A2")) is UteFrame.Unknown)
        assertTrue(UteParser.parse(Hex.parse("A2 01 02")) is UteFrame.Unknown)
    }

    // ---------- Kopma uyarısı ----------

    @Test
    fun windowSameDayAndOvernight() {
        assertTrue(LostLinkRules.inWindow(9 * 60, 17 * 60, 12 * 60))
        assertFalse(LostLinkRules.inWindow(9 * 60, 17 * 60, 17 * 60))
        assertFalse(LostLinkRules.inWindow(9 * 60, 17 * 60, 8 * 60 + 59))
        assertTrue(LostLinkRules.inWindow(22 * 60, 7 * 60, 23 * 60))
        assertTrue(LostLinkRules.inWindow(22 * 60, 7 * 60, 3 * 60))
        assertTrue(LostLinkRules.inWindow(22 * 60, 7 * 60, 22 * 60))
        assertFalse(LostLinkRules.inWindow(22 * 60, 7 * 60, 7 * 60))
        assertFalse(LostLinkRules.inWindow(22 * 60, 7 * 60, 12 * 60))
        assertFalse(LostLinkRules.inWindow(600, 600, 600))
    }

    @Test
    fun quietUsesDndOnlyWhenEnabled() {
        val on = DndSetting(true, 22, 0, 7, 0)
        assertTrue(LostLinkRules.quiet(on, 23, 30))
        assertFalse(LostLinkRules.quiet(on, 12, 0))
        assertFalse(LostLinkRules.quiet(on.copy(enabled = false), 23, 30))
        assertFalse(LostLinkRules.quiet(null, 23, 30))
    }

    @Test
    fun decideOrder() {
        val d = LostLinkRules::decide
        assertEquals(LostLinkRules.Decision.OFF, d(false, false, true, 0L, 1000L, false))
        assertEquals(LostLinkRules.Decision.RECONNECTED, d(true, true, true, 0L, 1000L, false))
        assertEquals(LostLinkRules.Decision.BLUETOOTH_OFF, d(true, false, false, 0L, 1000L, false))
        assertEquals(LostLinkRules.Decision.SNOOZED, d(true, false, true, 2000L, 1000L, false))
        assertEquals(LostLinkRules.Decision.QUIET, d(true, false, true, 0L, 1000L, true))
        assertEquals(LostLinkRules.Decision.ALERT, d(true, false, true, 1000L, 1000L, false))
    }

    // ---------- Geçmiş okuma kapısı ----------

    private val all = setOf(HistoryGate.Part.STEPS, HistoryGate.Part.HEART, HistoryGate.Part.SLEEP)

    @Test
    fun gateBlocksWhileRunningAndShortlyAfter() {
        val g = HistoryGate()
        assertEquals(HistoryGate.Check.Start, g.check(0))
        g.start(0, all)
        // Kayıttaki durum: 6 sn sonra ikinci istek.
        assertEquals(HistoryGate.Check.Running, g.check(6_000))
        assertFalse(g.partDone(HistoryGate.Part.STEPS, 3_000))
        assertFalse(g.partDone(HistoryGate.Part.STEPS, 3_100))
        assertFalse(g.partDone(HistoryGate.Part.HEART, 4_000))
        assertTrue(g.partDone(HistoryGate.Part.SLEEP, 7_000))
        assertFalse(g.running)
        assertEquals(HistoryGate.Check.Recent(10), g.check(17_000))
        assertEquals(HistoryGate.Check.Start, g.check(37_000))
    }

    @Test
    fun gateTimeoutAndReset() {
        val g = HistoryGate()
        g.start(0, all)
        assertTrue(g.timeout(25_000))
        assertFalse(g.timeout(26_000))
        assertTrue(g.check(30_000) is HistoryGate.Check.Recent)
        g.reset()
        assertEquals(HistoryGate.Check.Start, g.check(30_000))
        // Bitiş paketi hiç gelmese ve zaman aşımı çalışmasa bile kapı kilitli kalmaz.
        g.start(100_000, all)
        assertEquals(HistoryGate.Check.Start, g.check(100_000 + HistoryGate.TIMEOUT_MS))
        // İstenmeyen parçanın bitişi okumayı bitirmez.
        val h = HistoryGate()
        h.start(0, setOf(HistoryGate.Part.STEPS, HistoryGate.Part.HEART))
        assertFalse(h.partDone(HistoryGate.Part.SLEEP, 1_000))
        assertTrue(h.running)
    }
}
