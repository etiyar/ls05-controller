package com.che.ls05.protocol

/**
 * Haylou Solar LS05 için UTE / Youcy protokolü.
 *
 * Bu modül Android'e bağımlı değildir; JVM üzerinde birim testiyle doğrulanır.
 * Kaynak: Haylou Fun 4.0.7 (com.yingsheng.protocol.youcy) + kullanıcının btsnoop kaydı.
 */
object UteUuids {
    /*
     * Komut kanalı (KESİN, gerçek cihazda doğrulandı, v0.1.4):
     *  B) 60FF: yaz 6001 (Write Without Response), dinle 6002. Haylou Fun bunu kullanır.
     *     Kayıttaki handle 0x0022 = 6001, 0x0024 = 6002 (CCCD 0x25/0x2B/0x2F ile eşlendi).
     *     Önce 20 02 + kimlik ile doğrulama şart; yoksa saat yanıt vermez ve ~30 sn sonra keser.
     *  A) 55FF: 33F1 (READ, WRITE) / 33F2. Hello Haylou'nun tablosunda komut kanalı. Bu saatte
     *     doğrulamasız yanıt vermedi; doğrulamalı hali TEST EDİLMEDİ. Haylou Fun bağlanırken
     *     33F1'i yalnızca okur (yapılandırma).
     */
    const val CMD_A_SERVICE = "000055ff-0000-1000-8000-00805f9b34fb"
    const val CMD_A_WRITE = "000033f1-0000-1000-8000-00805f9b34fb"
    const val CMD_A_NOTIFY = "000033f2-0000-1000-8000-00805f9b34fb"

    const val CMD_B_SERVICE = "000060ff-0000-1000-8000-00805f9b34fb"
    const val CMD_B_WRITE = "00006001-0000-1000-8000-00805f9b34fb"
    const val CMD_B_NOTIFY = "00006002-0000-1000-8000-00805f9b34fb"

    const val ALT_NOTIFY_SERVICE = "000061ff-0000-1000-8000-00805f9b34fb"
    const val ALT_NOTIFY_1 = "00006101-0000-1000-8000-00805f9b34fb"
    const val ALT_NOTIFY_2 = "00006102-0000-1000-8000-00805f9b34fb"

    /** Kadran/firmware aktarım kanalı (Hello Haylou). v0.1'de KULLANILMAZ. */
    const val TRANSFER_SERVICE = "000056ff-0000-1000-8000-00805f9b34fb"
    const val TRANSFER_WRITE = "000034f1-0000-1000-8000-00805f9b34fb"
    const val TRANSFER_NOTIFY = "000034f2-0000-1000-8000-00805f9b34fb"

    /** Notify açmak için standart CCCD. */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** Standart BLE pil servisi (çapraz kontrol için). */
    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
}

/** Yalnızca gerçek cihazda kanıtlanmış komutlar. Diğerleri test edilmeden eklenmez. */
object UteCommand {
    const val FIRMWARE: Byte = 0x02
    const val BATTERY: Byte = 0x03
    const val NOTIFICATION: Byte = 0x0F

    /** Çok parçalı akışın bitiş işareti (0xFD). */
    const val SEGMENT_END: Byte = 0xFD.toByte()

    /** v0.1'de otomatik çalışmasına izin verilen komutlar. */
    val AUTO_SAFE: Set<Byte> = setOf(FIRMWARE, BATTERY)

    const val BIND: Byte = 0x20

    /**
     * Doğrulama (bağlama) isteği: 20 02 + 4 bayt kullanıcı kimliği (big-endian).
     * Haylou Fun bağlanınca bunu 60FF/6001'e yazar; saat 6002'den 20 04 <durum> ile cevap verir.
     * Kayıtla doğrulandı (haylou_notification_test.log, kayıt 27-28). Kimlik kişiseldir: koda yazılmaz.
     */
    fun bindRequest(userId: ByteArray): ByteArray {
        require(userId.size == 4) { "kimlik 4 bayt olmalı" }
        return byteArrayOf(BIND, 0x02) + userId
    }

    /** "0A0B0C0D" gibi 8 haneli onaltılık metni 4 bayta çevirir; geçersizse null. */
    fun parseUserId(hex: String): ByteArray? {
        val h = hex.trim().replace(" ", "").replace(":", "")
        if (h.length != 8 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(4) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    const val TIME: Byte = 0x04

    /**
     * Saat/tarih eşitleme: 04 YY YY AA GG ss dd sn TZ.
     * Son bayt saat dilimi farkı (saat, tam sayı). Kaynak: Haylou Fun SetDeviceDateEvent
     * (TimeZone offset / 3600000). Kayıt: 04 07 EA 0B 0B 13 33 2B 03 = 2026-11-11 19:51:43, UTC+3.
     * Saat bunu aynen geri yollar.
     */
    fun timeSync(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, utcOffsetHours: Int): ByteArray =
        byteArrayOf(
            TIME, (year shr 8).toByte(), year.toByte(), month.toByte(), day.toByte(),
            hour.toByte(), minute.toByte(), second.toByte(), utcOffsetHours.toByte(),
        )

    fun firmwareRequest(): ByteArray = byteArrayOf(FIRMWARE)
    fun batteryRequest(): ByteArray = byteArrayOf(BATTERY)

    /**
     * Mesaj titreşimi. Kayıtta 0F FD'den hemen sonra gönderilmiş (indeks 1486) ve Hello Haylou'da
     * VIBRATE_TEXT olarak tanımlı. Bildirim akışının parçası olarak kanıtlı.
     */
    val VIBRATE_TEXT: ByteArray = byteArrayOf(0x06, 0, 0, 0, 0x02, 0x02, 0, 0)

    /**
     * Gelen arama titreşimi. Düzen Haylou Fun SetVibrateEvent: 06 00 00 00 [sayı] [süre] 00 00.
     * Değer (FF FF) yalnızca kayıttan: 11 Kasım kaydında Haylou Fun gelen aramada bunu gönderdi, saat 06 00 ile onayladı.
     * Haylou Fun 4.0.7 kodu 03 20, Hello Haylou 01 0A 02 01 gönderiyor; değerler kaynaklar arasında tutarsız (güven: orta).
     * Durdurma (VIBRATE_STOP) her zaman ardından gönderilir.
     */
    val VIBRATE_CALL: ByteArray = byteArrayOf(0x06, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0)

    /** Titreşimi durdur. Haylou Fun (arama bitince), Hello Haylou VIBRATE_END ve kayıt birebir aynı (güven: yüksek). */
    val VIBRATE_STOP: ByteArray = byteArrayOf(0x06, 0, 0, 0, 0, 0, 0, 0)

    /**
     * Arama bitti / cevaplandı: saatteki arama ekranını kapatır. Haylou Fun UpdateMobCallStatusEvent,
     * Hello Haylou ALERT_CALL_TEXT_STOP ve kayıt (saat 0D 04 yankısı) birebir aynı (güven: yüksek).
     */
    val CALL_STOP: ByteArray = byteArrayOf(0x0D, 0x04)
}

/** Bildirim metnini saatin çizebileceği hale getirir. */
object TextSanitizer {
    /**
     * Emoji ve 4 baytlık karakterleri, birleştiricileri, kontrol karakterlerini çıkarır; satır sonlarını
     * boşluğa çevirir. Sonuçta her karakter UTF-16'da 2 bayt olur. Kesme (80 birim) ayrıca vekil çifti
     * bölmeyecek şekilde yapılır.
     */
    fun clean(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp == '\n'.code || cp == '\r'.code || cp == '\t'.code -> sb.append(' ')
                cp > 0xFFFF -> Unit
                cp in 0xFE00..0xFE0F -> Unit
                cp in 0x2600..0x27BF -> Unit
                type == Character.CONTROL.toInt() -> Unit
                type == Character.FORMAT.toInt() -> Unit
                type == Character.SURROGATE.toInt() -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }
}

/** Uygulama türü baytı (Hello Haylou tablosu). Saatte simge karşılıkları henüz test edilmedi. */
object UteAppType {
    const val QQ = 1
    const val WECHAT = 2
    const val CALL = 3
    const val MESSAGE = 4
    const val FACEBOOK = 5
    const val TWITTER = 6
    const val WHATSAPP = 7
    const val SKYPE = 8
    const val MESSENGER = 9
    const val HANGOUTS = 10
    const val LINE = 11
    const val LINKEDIN = 12
    const val INSTAGRAM = 13
    const val VIBER = 14
    const val KAKAOTALK = 15
    const val VK = 16
    const val SNAPCHAT = 17
    const val EMAIL = 19
    const val YOUTUBE = 23
    const val TELEGRAM = 24
}

/** Android paket adı -> bildirim tipi baytı. Bilinmeyenler 4 (mesaj). */
object AppTypeMap {
    private val map: Map<String, Int> = mapOf(
        "com.whatsapp" to UteAppType.WHATSAPP,
        "com.whatsapp.w4b" to UteAppType.WHATSAPP,
        "org.telegram.messenger" to UteAppType.TELEGRAM,
        "org.telegram.messenger.web" to UteAppType.TELEGRAM,
        "org.thunderdog.challegram" to UteAppType.TELEGRAM,
        "com.facebook.orca" to UteAppType.MESSENGER,
        "com.facebook.katana" to UteAppType.FACEBOOK,
        "com.instagram.android" to UteAppType.INSTAGRAM,
        "com.twitter.android" to UteAppType.TWITTER,
        "com.skype.raider" to UteAppType.SKYPE,
        "jp.naver.line.android" to UteAppType.LINE,
        "com.linkedin.android" to UteAppType.LINKEDIN,
        "com.viber.voip" to UteAppType.VIBER,
        "com.kakao.talk" to UteAppType.KAKAOTALK,
        "com.vkontakte.android" to UteAppType.VK,
        "com.snapchat.android" to UteAppType.SNAPCHAT,
        "com.google.android.gm" to UteAppType.EMAIL,
        "com.microsoft.office.outlook" to UteAppType.EMAIL,
        "com.google.android.youtube" to UteAppType.YOUTUBE,
        "com.tencent.mm" to UteAppType.WECHAT,
        "com.tencent.mobileqq" to UteAppType.QQ,
    )

    fun forPackage(pkg: String): Int = map[pkg] ?: UteAppType.MESSAGE

    /** İlk kurulumda iletimi açık gelen uygulamalar: mesajlaşma ve SMS. Diğerleri kullanıcı açınca. */
    val defaultOn: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web",
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
    )
}

/** Kayıttaki parça boyutu. MTU büyük olsa bile 20 baytta kalınır. */
const val UTE_CHUNK_SIZE = 20

/** Bildirim uygulama türü. 4 = genel. Diğer değerler cihazda doğrulanmadı. */
const val MSG_TYPE_GENERIC = 4
const val MSG_TYPE_CALL = 3

/**
 * Gelen arama metni tipi. Haylou Fun UpdateMobCallStatusEvent ve Hello Haylou ALERT_CALL_TEXT (0F 00 00 ..)
 * aynı; 11 Kasım kaydında gelen aramada 0F 00 00 [uzunluk] görüldü (güven: yüksek).
 */
const val MSG_TYPE_INCOMING_CALL = 0

object NotificationPacketizer {

    /** İlk pakette en çok 16 bayt metin (4 bayt başlık). */
    private const val FIRST_TEXT_MAX = UTE_CHUNK_SIZE - 4

    /** Devam paketlerinde en çok 18 bayt metin (2 bayt başlık). */
    private const val NEXT_TEXT_MAX = UTE_CHUNK_SIZE - 2

    /**
     * Saatin tuttuğu metin: 80 UTF-16 birimi. Gerçek saat testi: uzun metinde saat 0F FD 04 A0
     * ile 160 bayt aldığını bildiriyor, ekranda yaklaşık 78 karakter gösterip "..." koyuyor.
     * Fazlasını göndermek yalnızca aktarımı uzatır.
     */
    const val MAX_TEXT_CHARS = 80
    const val MAX_TEXT_BYTES = MAX_TEXT_CHARS * 2

    /**
     * Gelen arama adı: 30 bayt (15 UTF-16 birimi). Haylou Fun UpdateMobCallStatusEvent ve
     * UpdateMobCallStatusTask ikisi de 30 bayta kesiyor.
     */
    const val CALL_NAME_MAX_CHARS = 15

    /** Orijinal uygulama gibi "başlık: gövde" birleştirir. Arama (tip 3) yalnızca gövdeyi gönderir. */
    fun joinText(title: String, body: String, type: Int = MSG_TYPE_GENERIC): String =
        if (type == MSG_TYPE_CALL || title.isBlank()) body else if (body.isBlank()) title else "$title: $body"

    /** Metni en çok maxChars UTF-16 birimine keser; vekil çifti ortadan bölmez. */
    fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /** UTF-16 Big Endian, BOM'suz (Java "UnicodeBigUnmarked"), en çok 80 birim. */
    fun encode(text: String): ByteArray = truncate(text, MAX_TEXT_CHARS).toByteArray(Charsets.UTF_16BE)

    fun build(title: String, body: String, type: Int = MSG_TYPE_GENERIC): List<ByteArray> =
        buildFromText(joinText(title, body, type), type)

    fun buildFromText(text: String, type: Int = MSG_TYPE_GENERIC): List<ByteArray> {
        require(type in 1..255) { "tip 1..255 olmalı" }
        return packets(encode(text), type)
    }

    /**
     * Gelen arama ekranı: 0F 00 00 [uzunluk] + ad (en çok 30 bayt), 0F FD. Ad boşsa "Gelen arama".
     * Ardından VIBRATE_CALL; arama bitince CALL_STOP ve VIBRATE_STOP gönderilir.
     */
    fun buildIncomingCall(name: String): List<ByteArray> {
        val shown = name.ifBlank { "Gelen arama" }
        return packets(truncate(shown, CALL_NAME_MAX_CHARS).toByteArray(Charsets.UTF_16BE), MSG_TYPE_INCOMING_CALL)
    }

    private fun packets(payload: ByteArray, type: Int): List<ByteArray> {
        require(payload.size <= 255) { "metin en çok 255 bayt" }
        val cmd = UteCommand.NOTIFICATION
        val packets = mutableListOf<ByteArray>()

        val firstLen = minOf(payload.size, FIRST_TEXT_MAX)
        val first = ByteArray(4 + firstLen)
        first[0] = cmd
        first[1] = 0
        first[2] = type.toByte()
        first[3] = payload.size.toByte()
        payload.copyInto(first, 4, 0, firstLen)
        packets += first

        var offset = firstLen
        var seg = 1
        while (offset < payload.size) {
            val n = minOf(NEXT_TEXT_MAX, payload.size - offset)
            val p = ByteArray(2 + n)
            p[0] = cmd
            p[1] = seg.toByte()
            payload.copyInto(p, 2, offset, offset + n)
            packets += p
            offset += n
            seg++
        }

        packets += byteArrayOf(cmd, UteCommand.SEGMENT_END)
        return packets
    }
}

/** Saatten gelen bir bildirim çerçevesinin yorumu. */
sealed class UteFrame {
    data class Battery(val percent: Int) : UteFrame()
    data class Firmware(val version: String) : UteFrame()
    data class NotificationSegmentAck(val segment: Int) : UteFrame()

    /** 0F FD [tip] [uzunluk]. Aktarımın bittiğini gösterir; ekranda gösterildiğini KANITLAMAZ. */
    data class NotificationTransferDone(val type: Int?, val length: Int?) : UteFrame()
    data class VibrateAck(val bytes: ByteArray) : UteFrame()

    /** 04 …: saat, gönderilen saat/tarihi aynen geri yollar. */
    data class TimeAck(val bytes: ByteArray) : UteFrame()

    /** 20 04 <durum>: saatin bildirdiği bağlama/yetki durumu (Hello Haylou bunu dinliyor). Anlamı doğrulanmadı. */
    data class AuthStatus(val code: Int) : UteFrame()

    /** 06 [yuva]: alarm yuvası yazıldı. */
    data class AlarmAck(val slot: Int) : UteFrame()

    /** 15 …: rahatsız etme ayarının aynen geri yollanışı. */
    data class DndAck(val enabled: Boolean, val bytes: ByteArray) : UteFrame()

    /** 11 [parça]: hava durumu paketi alındı. */
    data class WeatherAck(val part: Int) : UteFrame()

    /** 0c 02 [bayraklar]: hızlı ayar durumu. 0x08 = rahatsız etme açık (güven: orta, tek örnek). */
    data class DeviceStatus(val flags: Int) : UteFrame() {
        val dndOn get() = flags and 0x08 != 0
    }

    data class Steps(val hour: StepHour) : UteFrame()
    data class StepHistoryEnd(val checksum: Int) : UteFrame()

    /** 18 03 [tarih] [sa] [dk] [nabız]: saat 10 dakikada bir kendiliğinden yollar. */
    data class HeartLive(val date: UteDate, val hour: Int, val minute: Int, val bpm: Int) : UteFrame()

    /** 18 04 [tarih] [sa] [dk] [en yüksek] [en düşük] [ortalama]: günlük özet. */
    data class HeartSummary(val date: UteDate, val max: Int, val min: Int, val avg: Int) : UteFrame()
    data class HeartBlockFrame(val block: HeartBlock) : UteFrame()
    data class HeartHistoryEnd(val checksum: Int) : UteFrame()

    /** 16 …: saatteki anlık nabız ölçümüyle ilgili paket. Değer yalnızca 16 xx 00 [nabız] biçiminde gelir (O). */
    data class RealHeart(val bpm: Int?, val bytes: ByteArray) : UteFrame()

    /** 1D 01 [tarih] [bölüm sayısı]: uyku kaydı başlığı. Tarih uyanılan gündür. */
    data class SleepHeader(val date: UteDate, val count: Int) : UteFrame()

    /** 1E + 6 baytlık bölümler (61FF/6102 kanalından gelir). Bölüm sayısı başlıktakiyle aynıdır. */
    data class SleepData(val segments: List<SleepSegment>) : UteFrame()

    /**
     * Saatin kumanda paketi: 12 [kod] [ek baytlar]. Kodların anlamı Haylou Fun (OnClickKeyTask + rd/b) ve
     * Hello Haylou (a3) ile çıkarıldı; çözümü WatchKeys.action yapar. D1 0A de buraya düşer (prefix 0xD1).
     */
    data class WatchKey(val prefix: Int, val code: Int, val args: List<Int>) : UteFrame()

    /** 12 0C …: saat, telefonun gönderdiği müzik durumunu onayladı (kayıtta 12 0C 00). */
    data class MusicStateAck(val bytes: ByteArray) : UteFrame()

    /** 05: profil paketi alındı (kayıtta tek bayt 05). */
    object ProfileAck : UteFrame()

    /** 0D 04: arama durdurma alındı (kayıtta saat aynen yankıladı). */
    object CallStopAck : UteFrame()

    /** Tanınan ama anlamı önemsiz yanıtlar (ör. 18 01, 1d 02, 25 …, 01 …). */
    data class Echo(val bytes: ByteArray) : UteFrame()
    data class Unknown(val bytes: ByteArray) : UteFrame()
}

object UteParser {
    fun parse(data: ByteArray): UteFrame {
        if (data.isEmpty()) return UteFrame.Unknown(data)
        return when (data[0]) {
            UteCommand.BATTERY ->
                if (data.size >= 2) UteFrame.Battery(data[1].toInt() and 0xFF) else UteFrame.Unknown(data)

            UteCommand.FIRMWARE ->
                UteFrame.Firmware(String(data, 1, data.size - 1, Charsets.US_ASCII).trim { it <= ' ' })

            UteCommand.NOTIFICATION -> when {
                data.size >= 2 && data[1] == UteCommand.SEGMENT_END -> UteFrame.NotificationTransferDone(
                    type = data.getOrNull(2)?.let { it.toInt() and 0xFF },
                    length = data.getOrNull(3)?.let { it.toInt() and 0xFF },
                )
                data.size >= 2 -> UteFrame.NotificationSegmentAck(data[1].toInt() and 0xFF)
                else -> UteFrame.Unknown(data)
            }

            0x06.toByte() ->
                if (data.size == 2 && (data[1].toInt() and 0xFF) in 1..UteAlarm.SLOTS) UteFrame.AlarmAck(data[1].toInt())
                else UteFrame.VibrateAck(data)

            0x15.toByte() ->
                if (data.size >= 7) UteFrame.DndAck(data[6].toInt() == 1, data) else UteFrame.Echo(data)

            0x11.toByte() ->
                if (data.size >= 2) UteFrame.WeatherAck(data[1].toInt() and 0xFF) else UteFrame.Unknown(data)

            0x0C.toByte() ->
                if (data.size >= 3 && data[1] == 0x02.toByte()) UteFrame.DeviceStatus(data[2].toInt() and 0xFF)
                else UteFrame.Unknown(data)

            0x09.toByte(), 0x0A.toByte() -> when {
                data.size >= 3 && data[1] == UteCommand.SEGMENT_END -> UteFrame.StepHistoryEnd(data[2].toInt() and 0xFF)
                else -> StepHour.parse(data)?.let { UteFrame.Steps(it) } ?: UteFrame.Unknown(data)
            }

            0x18.toByte() -> parseHeart(data)

            0x16.toByte() -> UteFrame.RealHeart(
                if (data.size >= 4 && data[2].toInt() == 0) (data[3].toInt() and 0xFF).takeIf { it in HealthLog.VALID_BPM } else null,
                data,
            )

            0x1D.toByte() ->
                if (data.size >= 7 && data[1] == 0x01.toByte()) UteFrame.SleepHeader(UteDate.read(data, 2), data[6].toInt() and 0xFF)
                else UteFrame.Echo(data)

            0x1E.toByte() ->
                if (data.size >= 7) UteFrame.SleepData(SleepSegment.parseAll(data, 1))
                else UteFrame.Unknown(data)

            0x01.toByte(), 0x25.toByte() -> UteFrame.Echo(data)

            0x12.toByte() -> when {
                data.size < 2 -> UteFrame.Unknown(data)
                data[1] == 0x0C.toByte() -> UteFrame.MusicStateAck(data)
                else -> UteFrame.WatchKey(0x12, data[1].toInt() and 0xFF, (2 until data.size).map { data[it].toInt() and 0xFF })
            }

            0xD1.toByte() ->
                if (data.size >= 2 && data[1] == 0x0A.toByte()) {
                    UteFrame.WatchKey(0xD1, 0x0A, (2 until data.size).map { data[it].toInt() and 0xFF })
                } else {
                    UteFrame.Unknown(data)
                }

            0x05.toByte() -> if (data.size == 1) UteFrame.ProfileAck else UteFrame.Unknown(data)

            0x0D.toByte() -> if (data.size == 2 && data[1] == 0x04.toByte()) UteFrame.CallStopAck else UteFrame.Unknown(data)

            UteCommand.TIME -> UteFrame.TimeAck(data)

            0x20.toByte() ->
                if (data.size >= 3 && data[1] == 0x04.toByte()) UteFrame.AuthStatus(data[2].toInt() and 0xFF)
                else UteFrame.Unknown(data)

            else -> UteFrame.Unknown(data)
        }
    }

    private fun parseHeart(d: ByteArray): UteFrame {
        if (d.size < 2) return UteFrame.Unknown(d)
        val u = { i: Int -> d[i].toInt() and 0xFF }
        return when (u(1)) {
            0x03 -> if (d.size >= 9) UteFrame.HeartLive(UteDate.read(d, 2), u(6), u(7), u(8)) else UteFrame.Unknown(d)
            0x04 -> if (d.size >= 11) UteFrame.HeartSummary(UteDate.read(d, 2), u(8), u(9), u(10)) else UteFrame.Unknown(d)
            0xFD -> if (d.size >= 3) UteFrame.HeartHistoryEnd(u(2)) else UteFrame.Unknown(d)
            0x01, 0x02, 0xFA -> UteFrame.Echo(d)
            else -> HeartBlock.parse(d)?.let { UteFrame.HeartBlockFrame(it) } ?: UteFrame.Unknown(d)
        }
    }
}

/**
 * Bildirim durumları. Aktarımın bitmesi ile ekranda görünmesi ayrı tutulur:
 * DISPLAYED yalnızca kullanıcı saatte gördüğünü onaylayınca kurulur.
 */
enum class NotificationState { IDLE, SENDING, TRANSFER_DONE, TRANSFER_TIMEOUT, DISPLAYED_CONFIRMED, NOT_DISPLAYED }

object Hex {
    fun of(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun parse(hex: String): ByteArray =
        hex.split(' ', '\n', '\t').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    /** Bildirim metnini ve kullanıcı kimliğini loglamaz. */
    fun redacted(bytes: ByteArray): String = when {
        // Kısa son parçalar (0F 01 xx xx) da tek karakter taşıyabilir; 0F FD dışındaki her metin parçası gizlenir.
        bytes.size > 2 && bytes[0] == UteCommand.NOTIFICATION && bytes[1] != UteCommand.SEGMENT_END ->
            "${of(bytes.copyOf(2))} … (${bytes.size} bayt, içerik gizli)"
        bytes.size >= 6 && bytes[0] == UteCommand.BIND && bytes[1] == 0x02.toByte() ->
            "20 02 XX XX XX XX (kimlik gizli)"
        bytes.size > 11 && bytes[0] == 0x11.toByte() && bytes[1] == 0x01.toByte() ->
            "${of(bytes.copyOf(11))} … (şehir gizli)"
        bytes.size >= 10 && bytes[0] == 0x05.toByte() ->
            "05 … (profil gizli)"
        else -> of(bytes)
    }
}
