package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingSyncTest {

    private val a0700 = AlarmSetting(true, 7, 0, UteAlarm.WEEKDAYS).encode()
    private val a0900 = AlarmSetting(true, 9, 0, UteAlarm.WEEKDAYS).encode()
    private val k = AlarmSetting.key(1)

    @Test
    fun happyPath() {
        val s = SettingSync()
        assertEquals(SyncPhase.IDLE, s.entry(k).phase)
        s.setDesired(k, a0700)
        assertEquals(SyncPhase.PENDING, s.entry(k).phase)
        assertEquals(listOf(k to a0700), s.nextToSend())
        s.markSent(k, a0700, session = 1, now = 1000)
        assertEquals(SyncPhase.SENDING, s.entry(k).phase)
        assertTrue(s.nextToSend().isEmpty(), "yoldayken ikinci komut gönderilmez")
        assertEquals(SettingSync.Ack.MATCHED, s.onAck(k, 1, 1400))
        assertEquals(SyncPhase.CONFIRMED, s.entry(k).phase)
        assertEquals(1400L, s.entry(k).confirmedAt)
        assertTrue(s.nextToSend().isEmpty())
    }

    @Test
    fun changeWhileInFlightIsSentAfterAck() {
        val s = SettingSync()
        s.setDesired(k, a0700)
        s.markSent(k, a0700, 1, 1000)
        s.setDesired(k, a0900)
        assertTrue(s.nextToSend().isEmpty())
        s.onAck(k, 1, 1300)
        assertEquals(a0700, s.entry(k).confirmed, "onay yoldaki değere aittir, yeni isteğe değil")
        assertEquals(SyncPhase.PENDING, s.entry(k).phase)
        assertEquals(listOf(k to a0900), s.nextToSend())
    }

    @Test
    fun ackWithoutInFlightOrFromOldSessionIsUnmatched() {
        val s = SettingSync()
        assertEquals(SettingSync.Ack.UNMATCHED, s.onAck(k, 1, 10))
        s.setDesired(k, a0700)
        assertEquals(SettingSync.Ack.UNMATCHED, s.onAck(k, 1, 10))
        s.markSent(k, a0700, session = 2, now = 100)
        assertEquals(SettingSync.Ack.UNMATCHED, s.onAck(k, 1, 200), "eski oturumun onayı sayılmaz")
        assertEquals(SyncPhase.SENDING, s.entry(k).phase)
    }

    @Test
    fun timeoutIsNoAckAndNeverAutoResent() {
        val s = SettingSync()
        s.setDesired(k, a0700)
        s.markSent(k, a0700, 1, 1000)
        assertTrue(s.expire(5999, 5000).isEmpty())
        assertEquals(listOf(k), s.expire(6000, 5000))
        assertEquals(SyncPhase.NO_ACK, s.entry(k).phase)
        assertTrue(s.nextToSend().isEmpty(), "onay gelmeyen komut otomatik gönderilmez")
        assertEquals(SettingSync.Ack.UNMATCHED, s.onAck(k, 1, 7000), "zaman aşımından sonraki onay sayılmaz")
        s.retry(k)
        assertEquals(listOf(k to a0700), s.nextToSend())
    }

    @Test
    fun disconnectMakesInFlightNoAckButKeepsUnsentPending() {
        val s = SettingSync()
        s.setDesired(k, a0700)
        s.markSent(k, a0700, 1, 1000)
        s.setDesired("alarm2", a0900)
        assertEquals(listOf(k), s.onDisconnect())
        assertEquals(SyncPhase.NO_ACK, s.entry(k).phase)
        assertEquals(SyncPhase.PENDING, s.entry("alarm2").phase)
        assertEquals(listOf("alarm2" to a0900), s.nextToSend(), "hiç gönderilmemiş istek bağlanınca gider")
    }

    @Test
    fun newUserChoiceClearsNoAck() {
        val s = SettingSync()
        s.setDesired(k, a0700)
        s.markSent(k, a0700, 1, 0)
        s.expire(10_000, 5000)
        s.setDesired(k, a0900)
        assertEquals(listOf(k to a0900), s.nextToSend())
    }

    @Test
    fun unknownAfterResetIsNotAutoSent() {
        val s = SettingSync()
        s.setDesired(k, a0700); s.markSent(k, a0700, 1, 0); s.onAck(k, 1, 5)
        s.markAllUnknown()
        assertEquals(SyncPhase.UNKNOWN, s.entry(k).phase)
        assertTrue(s.nextToSend().isEmpty())
        assertEquals(SyncPhase.IDLE, s.entry("alarm3").phase, "hiç ayarlanmamış yuva belirsiz olmaz")
        s.retry(k)
        assertEquals(listOf(k to a0700), s.nextToSend())
    }

    @Test
    fun retryResendsEvenWhenDesiredEqualsConfirmed() {
        val s = SettingSync()
        s.setDesired(k, a0700); s.markSent(k, a0700, 1, 0); s.onAck(k, 1, 5)
        s.markSent(k, a0700, 1, 10); s.expire(10_000, 5000)
        assertEquals(SyncPhase.NO_ACK, s.entry(k).phase)
        s.retry(k)
        assertEquals(listOf(k to a0700), s.nextToSend())
    }

    @Test
    fun dndEchoMustMatchSentValue() {
        val s = SettingSync()
        val on = DndSetting(true, 22, 0, 7, 0)
        s.setDesired(DndSetting.KEY, on.encode())
        s.markSent(DndSetting.KEY, on.encode(), 1, 0)
        val wrongEcho = DndSetting.fromEcho(Hex.parse("15 00 16 00 07 00 00"))!!.encode()
        assertEquals(SettingSync.Ack.UNMATCHED, s.onAck(DndSetting.KEY, 1, 5, wrongEcho))
        val echo = DndSetting.fromEcho(Hex.parse("15 00 16 00 07 00 01"))!!.encode()
        assertEquals(SettingSync.Ack.MATCHED, s.onAck(DndSetting.KEY, 1, 6, echo))
    }

    @Test
    fun persistenceRoundTripAndInFlightBecomesNoAck() {
        val s = SettingSync()
        s.setDesired(k, a0700); s.markSent(k, a0700, 1, 0); s.onAck(k, 1, 1234)
        s.setDesired("alarm2", a0900); s.markSent("alarm2", a0900, 1, 0)
        s.setDesired(DndSetting.KEY, DndSetting.DEFAULT.encode())
        val r = SettingSync.deserialize(s.serialize() + "\nbozuk satır\n\tx\t")
        assertEquals(SyncPhase.CONFIRMED, r.entry(k).phase)
        assertEquals(1234L, r.entry(k).confirmedAt)
        assertEquals(SyncPhase.NO_ACK, r.entry("alarm2").phase, "uygulama komut yoldayken kapandıysa durum belirsizdir")
        assertEquals(SyncPhase.PENDING, r.entry(DndSetting.KEY).phase)
        assertEquals(3, r.keys().size)
    }

    @Test
    fun settingCodecsAndCommands() {
        assertEquals(AlarmSetting(true, 7, 18, 127), AlarmSetting.decode("1|7|18|127"))
        assertEquals(null, AlarmSetting.decode("1|25|0|62"))
        assertEquals(null, AlarmSetting.decode("x"))
        assertEquals(null, AlarmSetting.decode(null))
        assertContentEquals(Hex.parse("06 7F 07 12 02 08 06 00 02"), SettingCommands.build("alarm2", "1|7|18|127"))
        assertContentEquals(Hex.parse("06 60 07 00 00 00 00 00 01"), SettingCommands.build("alarm1", "0|7|0|62"))
        assertContentEquals(Hex.parse("15 00 16 00 07 00 01"), SettingCommands.build("dnd", "1|22|0|7|0"))
        assertEquals(null, SettingCommands.build("alarm4", "1|7|0|62"))
        assertEquals(null, SettingCommands.build("bilinmeyen", "1"))
    }
}
