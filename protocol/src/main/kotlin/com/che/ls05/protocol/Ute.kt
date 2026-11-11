package com.che.ls05.protocol

/**
 * Haylou Solar LS05 için UTE / Youcy protokolü.
 *
 * Bu modül Android'e bağımlı değildir; JVM üzerinde birim testiyle doğrulanır.
 * Kaynak: Haylou Fun 4.0.7 (com.yingsheng.protocol.youcy) + kullanıcının btsnoop kaydı.
 */
object UteUuids {
    /*
     * İki aday komut kanalı var:
     *  A) 55FF: yaz 33F1, dinle 33F2. Hello Haylou (bağımsız istemci) pil, firmware, bildirim,
     *     titreşim ve saat ayarı için bunu kullanır. Kayıttaki bitişik handle'lar (0x22 / 0x24) ile uyumlu.
     *  B) 60FF: yaz 6001, dinle 6002 (+ 61FF/6101). Haylou Fun'ın youcy kodu bunu kullanır;
     *     Hello Haylou burayı yalnızca sağlık geçmişi çekmek için kullanır.
     * Hangisinin LS05'te komut kanalı olduğu ilk bağlantıda pil sorgusuyla deneysel olarak seçilir.
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

    fun firmwareRequest(): ByteArray = byteArrayOf(FIRMWARE)
    fun batteryRequest(): ByteArray = byteArrayOf(BATTERY)

    /**
     * Mesaj titreşimi. Kayıtta 0F FD'den hemen sonra gönderilmiş (indeks 1486) ve Hello Haylou'da
     * VIBRATE_TEXT olarak tanımlı. Bildirim akışının parçası olarak kanıtlı.
     */
    val VIBRATE_TEXT: ByteArray = byteArrayOf(0x06, 0, 0, 0, 0x02, 0x02, 0, 0)
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

/** Kayıttaki parça boyutu. MTU büyük olsa bile 20 baytta kalınır. */
const val UTE_CHUNK_SIZE = 20

/** Bildirim uygulama türü. 4 = genel. Diğer değerler cihazda doğrulanmadı. */
const val MSG_TYPE_GENERIC = 4
const val MSG_TYPE_CALL = 3

object NotificationPacketizer {

    /** İlk pakette en çok 16 bayt metin (4 bayt başlık). */
    private const val FIRST_TEXT_MAX = UTE_CHUNK_SIZE - 4

    /** Devam paketlerinde en çok 18 bayt metin (2 bayt başlık). */
    private const val NEXT_TEXT_MAX = UTE_CHUNK_SIZE - 2

    /**
     * Uzunluk alanı tek bayt. Orijinal uygulama 255 bayta keser; bu tek sayıdır ve son
     * UTF-16 karakteri ikiye bölebilir. Biz 254'te keserek karakter bütünlüğünü koruyoruz.
     */
    const val MAX_TEXT_BYTES = 254

    /** Orijinal uygulama gibi "başlık: gövde" birleştirir. Arama (tip 3) yalnızca gövdeyi gönderir. */
    fun joinText(title: String, body: String, type: Int = MSG_TYPE_GENERIC): String =
        if (type == MSG_TYPE_CALL) body else "$title: $body"

    /** UTF-16 Big Endian, BOM'suz (Java "UnicodeBigUnmarked"). */
    fun encode(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_16BE)
        return if (bytes.size <= MAX_TEXT_BYTES) bytes else bytes.copyOf(MAX_TEXT_BYTES)
    }

    fun build(title: String, body: String, type: Int = MSG_TYPE_GENERIC): List<ByteArray> =
        buildFromText(joinText(title, body, type), type)

    fun buildFromText(text: String, type: Int = MSG_TYPE_GENERIC): List<ByteArray> {
        require(type in 1..255) { "tip 1..255 olmalı" }
        val payload = encode(text)
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

    /** 20 04 <durum>: saatin bildirdiği bağlama/yetki durumu (Hello Haylou bunu dinliyor). Anlamı doğrulanmadı. */
    data class AuthStatus(val code: Int) : UteFrame()
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

            0x06.toByte() -> UteFrame.VibrateAck(data)

            0x20.toByte() ->
                if (data.size >= 3 && data[1] == 0x04.toByte()) UteFrame.AuthStatus(data[2].toInt() and 0xFF)
                else UteFrame.Unknown(data)

            else -> UteFrame.Unknown(data)
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

    /** Bildirim çerçevelerinde metni loglamaz; yalnızca komut, segment ve uzunluk. */
    fun redacted(bytes: ByteArray): String =
        if (bytes.isNotEmpty() && bytes[0] == UteCommand.NOTIFICATION && bytes.size > 4) {
            "${of(bytes.copyOf(2))} … (${bytes.size} bayt, içerik gizli)"
        } else of(bytes)
}
