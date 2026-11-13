package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** v0.5: arama, saatten kumanda, profil. Profil değerleri yapaydır (kişisel değil). */
class V05Test {

    // ---------- Gelen arama ----------

    @Test
    fun incomingCallPacketsMatchSourceLayout() {
        // Yapay ad, 5 karakter = 10 bayt: 0F 00 00 0A + ad, 0F FD (11 Kasım kaydındaki düzen: 0F 00 00 [uzunluk]).
        val p = NotificationPacketizer.buildIncomingCall("Ahmet")
        assertEquals(2, p.size)
        assertContentEquals(Hex.parse("0F 00 00 0A 00 41 00 68 00 6D 00 65 00 74"), p[0])
        assertContentEquals(Hex.parse("0F FD"), p[1])
    }

    @Test
    fun incomingCallNameCappedAt30Bytes() {
        val p = NotificationPacketizer.buildIncomingCall("Çok uzun bir arayan adı burada")
        assertEquals(30, p[0][3].toInt() and 0xFF, "Haylou Fun 30 bayta kesiyor")
        assertEquals(3, p.size) // 16 + 14 bayt + bitiş
        assertEquals(0, p[0][2].toInt(), "arama tipi 0")
        val empty = NotificationPacketizer.buildIncomingCall("  ")
        assertEquals(22, empty[0][3].toInt() and 0xFF, "boş ad yerine 'Gelen arama'")
    }

    @Test
    fun callCommandsMatchRecording() {
        assertContentEquals(Hex.parse("0D 04"), UteCommand.CALL_STOP)
        assertContentEquals(Hex.parse("06 00 00 00 00 00 00 00"), UteCommand.VIBRATE_STOP)
        assertContentEquals(Hex.parse("06 00 00 00 FF FF 00 00"), UteCommand.VIBRATE_CALL)
        assertEquals(UteFrame.CallStopAck, UteParser.parse(Hex.parse("0D 04")))
        assertTrue(UteParser.parse(Hex.parse("0D 05")) is UteFrame.Unknown)
    }

    @Test
    fun callClassifier() {
        val c = CallClassifier
        assertEquals(CallKind.INCOMING, c.classify("x.dialer", "call", true, 1, false))
        assertEquals(CallKind.ONGOING, c.classify("x.dialer", "call", true, 2, true))
        assertEquals(CallKind.ONGOING, c.classify("x.dialer", "call", true, 3, true), "filtrelenen arama uyarılmaz")
        assertEquals(CallKind.INCOMING, c.classify("x.dialer", "call", true, 0, true), "tipsiz: tam ekran niyeti gelen arama")
        assertEquals(CallKind.ONGOING, c.classify("x.dialer", "call", true, 0, false))
        assertEquals(CallKind.MISSED, c.classify("x.dialer", "missed_call", false, 0, false))
        assertEquals(CallKind.MISSED, c.classify("com.google.android.dialer", null, false, 0, false))
        assertEquals(CallKind.NONE, c.classify("com.google.android.dialer", null, true, 0, false), "kalıcı, tam ekran değil")
        assertEquals(CallKind.INCOMING, c.classify("com.android.incallui", null, true, 0, true))
        assertEquals(CallKind.NONE, c.classify("com.whatsapp", null, false, 0, false))
    }

    @Test
    fun callTrackerSendsOnceAndStops() {
        val t = CallTracker(maxRingMs = 60_000)
        assertEquals(CallTracker.Decision.ALERT, t.onPosted("k1", CallKind.INCOMING, 0))
        assertEquals(CallTracker.Decision.NONE, t.onPosted("k1", CallKind.INCOMING, 1000), "çalarken güncelleme tekrar gönderilmez")
        assertEquals(CallTracker.Decision.STOP, t.onPosted("k1", CallKind.ONGOING, 5000), "cevaplandı")
        assertEquals(CallTracker.Decision.NONE, t.onPosted("k1", CallKind.ONGOING, 6000), "süren arama güncellemesi")
        assertEquals(CallTracker.Decision.NONE, t.onRemoved("k1"))

        assertEquals(CallTracker.Decision.ALERT, t.onPosted("k1", CallKind.INCOMING, 10_000), "yeni arama")
        assertEquals(CallTracker.Decision.STOP, t.onRemoved("k1"), "reddedildi ya da cevapsız")
        assertEquals(CallTracker.Decision.NONE, t.onRemoved("başka"))
    }

    @Test
    fun callTrackerSecondLineAndExpiry() {
        val t = CallTracker(maxRingMs = 60_000)
        assertEquals(CallTracker.Decision.ALERT, t.onPosted("a", CallKind.INCOMING, 0))
        assertEquals(CallTracker.Decision.NONE, t.onPosted("b", CallKind.INCOMING, 100), "saat zaten çalıyor")
        assertEquals(CallTracker.Decision.NONE, t.onRemoved("a"), "diğer arama sürüyor")
        assertEquals(CallTracker.Decision.STOP, t.onRemoved("b"))
        assertEquals(CallTracker.Decision.ALERT, t.onPosted("c", CallKind.INCOMING, 0))
        assertEquals(CallTracker.Decision.NONE, t.expire(59_999))
        assertEquals(CallTracker.Decision.STOP, t.expire(60_000), "kaldırılma gelmezse süre dolunca durdur")
        assertTrue(!t.isRinging)
    }

    // ---------- Saatten kumanda ----------

    private fun key(hex: String) = UteParser.parse(Hex.parse(hex)) as UteFrame.WatchKey

    @Test
    fun watchKeysFromBothSources() {
        assertEquals(RemoteAction.PLAY_PAUSE, WatchKeys.action(key("12 07")))
        assertEquals(RemoteAction.NEXT, WatchKeys.action(key("12 08")))
        assertEquals(RemoteAction.PREVIOUS, WatchKeys.action(key("12 09")))
        assertEquals(RemoteAction.VOLUME_UP, WatchKeys.action(key("12 0D")))
        assertEquals(RemoteAction.VOLUME_DOWN, WatchKeys.action(key("12 0E")))
        assertEquals(RemoteAction.FIND_PHONE_TOGGLE, WatchKeys.action(key("12 0A")))
        assertEquals(RemoteAction.FIND_PHONE_START, WatchKeys.action(key("12 0A 01")))
        assertEquals(RemoteAction.FIND_PHONE_STOP, WatchKeys.action(key("12 0A 00")))
        assertEquals(RemoteAction.FIND_PHONE_STOP, WatchKeys.action(key("12 0A 05 00")))
        assertEquals(RemoteAction.FIND_PHONE_TOGGLE, WatchKeys.action(key("D1 0A")))
        assertEquals(RemoteAction.REJECT_CALL, WatchKeys.action(key("12 02")))
        assertEquals(RemoteAction.CAMERA_ENTER, WatchKeys.action(key("12 11")))
        assertEquals(RemoteAction.UNKNOWN, WatchKeys.action(key("12 30")))
        assertTrue(UteParser.parse(Hex.parse("D1 07")) is UteFrame.Unknown, "D1 07 yalnızca tek kaynakta")
    }

    @Test
    fun volumeEchoIsNotAKeyPress() {
        // Telefonun gönderdiği 12 0D [ses] yankılanırsa ses aç sayılmamalı.
        assertEquals(RemoteAction.UNKNOWN, WatchKeys.action(key("12 0D 40")))
        assertEquals(RemoteAction.UNKNOWN, WatchKeys.action(key("12 07 01")))
        assertTrue(UteParser.parse(Hex.parse("12 0C 00")) is UteFrame.MusicStateAck, "kayıttaki yanıt")
        assertTrue(UteParser.parse(Hex.parse("12")) is UteFrame.Unknown)
    }

    @Test
    fun musicStateMatchesRecording() {
        assertContentEquals(Hex.parse("12 0C 02"), UteMusic.playState(false))
        assertContentEquals(Hex.parse("12 0C 01"), UteMusic.playState(true))
        assertContentEquals(Hex.parse("12 0D 06"), UteMusic.volume(6))
        assertContentEquals(Hex.parse("12 0D 64"), UteMusic.volume(100))
        assertContentEquals(Hex.parse("12 0D 64"), UteMusic.volume(250))
        assertEquals(46, UteMusic.percent(7, 15))
        assertEquals(0, UteMusic.percent(3, 0))
    }

    // ---------- Profil (05) ----------

    private val sample = ProfileSetting(170, 70, 30, UteProfile.SEX_MALE, 5, true, 8000)

    @Test
    fun profilePacketLayout() {
        // Yapay değerler. Kayıttaki kişisel olmayan alanlar: ekran 5, bilek 1, FF, 00, kayıp 0, dil 2, km 1, alt 0.
        assertContentEquals(
            Hex.parse("05 00 AA 00 46 05 00 00 1F 40 01 FF 00 1E 01 00 02 01 00"),
            sample.command(),
        )
        val p = UteProfile.build(181, 92, 15, 12345, false, 41, UteProfile.SEX_FEMALE)
        assertEquals(19, p.size)
        assertContentEquals(Hex.parse("05 00 B5 00 5C 0F 00 00 30 39 00 FF 00 29 02 00 02 01 00"), p)
        assertEquals("05 … (profil gizli)", Hex.redacted(p))
    }

    @Test
    fun profileNotBuiltWhenIncompleteOrInvalid() {
        assertEquals(null, ProfileSetting.DEFAULT.command(), "profil girilmeden paket üretilmez")
        assertTrue(!ProfileSetting.DEFAULT.personalComplete)
        assertEquals(null, sample.copy(age = null).command())
        assertEquals(null, sample.copy(screenSec = 7).command(), "5, 10, 15 dışı ekran süresi")
        assertEquals(null, SettingCommands.build(ProfileSetting.KEY, ProfileSetting.DEFAULT.encode()))
        assertContentEquals(sample.command(), SettingCommands.build(ProfileSetting.KEY, sample.encode()))
    }

    @Test
    fun profileCodecRoundTrip() {
        assertEquals(sample, ProfileSetting.decode(sample.encode()))
        assertEquals(ProfileSetting.DEFAULT, ProfileSetting.decode(ProfileSetting.DEFAULT.encode()))
        assertEquals("||||5|1|8000", ProfileSetting.DEFAULT.encode())
        assertEquals(null, ProfileSetting.decode("170|70|30|1|6|1|8000"))
        assertEquals(null, ProfileSetting.decode("170|70|30|1|5|x|8000"))
        assertEquals(null, ProfileSetting.decode(null))
        assertEquals(null, ProfileSetting.decode("bozuk"))
        assertEquals(sample.copy(heightCm = null), ProfileSetting.decode("999|70|30|1|5|1|8000"), "aralık dışı alan boş sayılır")
    }

    @Test
    fun profileAckGoesThroughSettingSync() {
        assertEquals(UteFrame.ProfileAck, UteParser.parse(Hex.parse("05")))
        assertTrue(UteParser.parse(Hex.parse("05 01")) is UteFrame.Unknown)
        val s = SettingSync()
        s.setDesired(ProfileSetting.KEY, sample.encode())
        s.markSent(ProfileSetting.KEY, sample.encode(), 1, 0)
        assertEquals(SettingSync.Ack.MATCHED, s.onAck(ProfileSetting.KEY, 1, 10))
        assertEquals(SyncPhase.CONFIRMED, s.entry(ProfileSetting.KEY).phase)
        val r = SettingSync.deserialize(s.serialize())
        assertEquals(sample.encode(), r.entry(ProfileSetting.KEY).confirmed, "| ayıracı kalıcılığı bozmaz")
    }
}
