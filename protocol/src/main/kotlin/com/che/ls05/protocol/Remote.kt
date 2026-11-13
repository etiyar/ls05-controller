package com.che.ls05.protocol

/*
 * v0.5: saatten telefona kumanda ve gelen arama.
 * Güven düzeyleri yorumlarda. Saate yazan komutlar yalnızca iki kaynakta ve kayıtta tutarlı olanlardır.
 */

/**
 * Telefon -> saat müzik durumu. Güven: yüksek.
 *  12 0C [1 çalıyor / 2 durdu]: Haylou Fun MusicControlTask (isPlaying ? 1 : 2), Hello Haylou SET_MUSIC_STATE (0C 01 / 0C 02),
 *  kayıtta 12 0C 02, saat 12 0C 00 ile yanıtlıyor.
 *  12 0D [ses %]: Haylou Fun MusicControlTask, Hello Haylou (13, ses yüzdesi), kayıtta 12 0D 06 / 12 0D 64 / 12 0D 00.
 */
object UteMusic {
    fun playState(playing: Boolean): ByteArray = byteArrayOf(0x12, 0x0C, if (playing) 1 else 2)

    fun volume(percent: Int): ByteArray = byteArrayOf(0x12, 0x0D, percent.coerceIn(0, 100).toByte())

    /** Haylou Fun gibi: mevcut * 100 / en yüksek. */
    fun percent(current: Int, max: Int): Int = if (max <= 0) 0 else (current * 100 / max).coerceIn(0, 100)
}

/** Saatten gelen kumandanın telefondaki karşılığı. */
enum class RemoteAction {
    PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN,
    FIND_PHONE_START, FIND_PHONE_STOP, FIND_PHONE_TOGGLE,

    /** 12 02: aramayı reddet / kapat. Bu sürümde yalnızca loglanır (ek izin gerektirir). */
    REJECT_CALL,

    /** 12 11 / 12 0F: kamera kumandasına giriş / çıkış. Yalnızca loglanır. */
    CAMERA_ENTER, CAMERA_EXIT,
    UNKNOWN,
}

/**
 * Saat -> telefon kumanda kodları (12 [kod]).
 * Haylou Fun: OnClickKeyTask kodu data[1] olarak verir, rd/b eşler: 2 aramayı kapat, 7 oynat/duraklat, 8 sonraki,
 * 9 önceki, 13 ses aç, 14 ses kıs, 10 telefonu bul (ek bayt 1 başlat / 0 durdur, ek bayt yoksa değiştir),
 * 17 kamera aç, 15 kamera kapat. D1 0A da telefonu bul.
 * Hello Haylou (a3 + MiBandSupport geri çağrısı): 12 02, 12 07, 12 08, 12 09, 12 0D, 12 0E, 12 0A birebir aynı anlamda,
 * paketleri tam 2 bayt olarak eşliyor.
 * Güven: yüksek (iki kaynak tutarlı). Kayıtta saatten gelen 12 xx tuşu yok; ilk kullanım kontrollü testtir.
 *
 * Güvenlik: müzik ve ses kodları yalnızca tam 2 baytlık pakette uygulanır (Hello Haylou gibi). Telefonun gönderdiği
 * 12 0D [ses] saat tarafından yankılanırsa "ses aç" sanılıp döngüye girilmesin.
 */
object WatchKeys {
    fun action(key: UteFrame.WatchKey): RemoteAction {
        val exact = key.args.isEmpty()
        if (key.prefix == 0xD1) return if (key.code == 0x0A) findPhone(key.args) else RemoteAction.UNKNOWN
        return when (key.code) {
            0x07 -> if (exact) RemoteAction.PLAY_PAUSE else RemoteAction.UNKNOWN
            0x08 -> if (exact) RemoteAction.NEXT else RemoteAction.UNKNOWN
            0x09 -> if (exact) RemoteAction.PREVIOUS else RemoteAction.UNKNOWN
            0x0D -> if (exact) RemoteAction.VOLUME_UP else RemoteAction.UNKNOWN
            0x0E -> if (exact) RemoteAction.VOLUME_DOWN else RemoteAction.UNKNOWN
            0x0A -> findPhone(key.args)
            0x02 -> RemoteAction.REJECT_CALL
            0x11 -> RemoteAction.CAMERA_ENTER
            0x0F -> RemoteAction.CAMERA_EXIT
            else -> RemoteAction.UNKNOWN
        }
    }

    /** Haylou Fun: ek bayt yoksa değiştir; 3 ya da 4 baytlık pakette son bayt 1 başlat, 0 durdur. */
    private fun findPhone(args: List<Int>): RemoteAction = when {
        args.isEmpty() -> RemoteAction.FIND_PHONE_TOGGLE
        args.size > 2 -> RemoteAction.UNKNOWN
        args.last() == 1 -> RemoteAction.FIND_PHONE_START
        args.last() == 0 -> RemoteAction.FIND_PHONE_STOP
        else -> RemoteAction.FIND_PHONE_TOGGLE
    }

    fun label(a: RemoteAction): String = when (a) {
        RemoteAction.PLAY_PAUSE -> "oynat/duraklat"
        RemoteAction.NEXT -> "sonraki parça"
        RemoteAction.PREVIOUS -> "önceki parça"
        RemoteAction.VOLUME_UP -> "ses aç"
        RemoteAction.VOLUME_DOWN -> "ses kıs"
        RemoteAction.FIND_PHONE_START -> "telefonu bul (başlat)"
        RemoteAction.FIND_PHONE_STOP -> "telefonu bul (durdur)"
        RemoteAction.FIND_PHONE_TOGGLE -> "telefonu bul"
        RemoteAction.REJECT_CALL -> "aramayı reddet"
        RemoteAction.CAMERA_ENTER -> "kamera aç"
        RemoteAction.CAMERA_EXIT -> "kamera kapat"
        RemoteAction.UNKNOWN -> "bilinmeyen"
    }
}

/** Bir telefon bildiriminin arama açısından türü. */
enum class CallKind { NONE, INCOMING, ONGOING, MISSED }

/**
 * Bildirimden arama türünü çıkarır (ek izin gerektirmez; NotificationListener verisiyle çalışır).
 *  - Notification.CATEGORY_CALL ("call"): API 31+ EXTRA_CALL_TYPE 1 = gelen, 2 = süren, 3 = filtreleniyor.
 *    Tip yoksa tam ekran niyeti (fullScreenIntent) olan arama bildirimi gelen arama sayılır, olmayan süren arama.
 *  - "missed_call" kategorisi ya da arama uygulaması paketinden gelen kalıcı olmayan bildirim: cevapsız arama.
 *  - Kategorisiz ama arama uygulamasından gelen tam ekran niyetli bildirim: gelen arama (bazı üretici arayüzleri).
 */
object CallClassifier {
    const val CATEGORY_CALL = "call"
    const val CATEGORY_MISSED_CALL = "missed_call"
    const val CALL_TYPE_INCOMING = 1
    const val CALL_TYPE_ONGOING = 2
    const val CALL_TYPE_SCREENING = 3

    /** Arama ve cevapsız arama bildirimlerini gönderen bilinen paketler. HyperOS için doğrulanmadı. */
    val DIALER_PACKAGES: Set<String> = setOf(
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
    )

    fun classify(pkg: String, category: String?, ongoing: Boolean, callType: Int, fullScreen: Boolean): CallKind = when {
        category == CATEGORY_CALL -> when (callType) {
            CALL_TYPE_INCOMING -> CallKind.INCOMING
            CALL_TYPE_ONGOING, CALL_TYPE_SCREENING -> CallKind.ONGOING
            else -> if (fullScreen) CallKind.INCOMING else CallKind.ONGOING
        }
        category == CATEGORY_MISSED_CALL -> CallKind.MISSED
        pkg in DIALER_PACKAGES && category == null && fullScreen -> CallKind.INCOMING
        pkg in DIALER_PACKAGES && !ongoing -> CallKind.MISSED
        else -> CallKind.NONE
    }
}

/**
 * Gelen aramanın saate bir kez gitmesi ve bitince durdurulması.
 * Arama uygulaması aynı bildirimi çalarken sık sık günceller; aynı anahtar için ikinci uyarı gönderilmez.
 * Aynı anahtar "süren arama" olarak güncellenirse (cevaplandı) ya da kaldırılırsa (reddedildi, cevapsız) durdurulur.
 */
class CallTracker(private val maxRingMs: Long = 90_000) {

    enum class Decision { NONE, ALERT, STOP }

    private val ringing = LinkedHashMap<String, Long>()

    val isRinging: Boolean get() = ringing.isNotEmpty()

    fun onPosted(key: String, kind: CallKind, now: Long): Decision = when (kind) {
        CallKind.INCOMING -> {
            if (ringing.containsKey(key)) {
                Decision.NONE
            } else {
                val wasRinging = ringing.isNotEmpty()
                ringing[key] = now
                if (wasRinging) Decision.NONE else Decision.ALERT
            }
        }
        CallKind.ONGOING -> stopIfLast(key)
        else -> Decision.NONE
    }

    fun onRemoved(key: String): Decision = stopIfLast(key)

    /** Kaldırılma bildirimi hiç gelmezse çok eski uyarılar düşer; son uyarı düşerse durdurma gerekir. */
    fun expire(now: Long): Decision {
        if (ringing.isEmpty()) return Decision.NONE
        val it = ringing.entries.iterator()
        while (it.hasNext()) if (now - it.next().value >= maxRingMs) it.remove()
        return if (ringing.isEmpty()) Decision.STOP else Decision.NONE
    }

    fun clear() = ringing.clear()

    private fun stopIfLast(key: String): Decision {
        if (ringing.remove(key) == null) return Decision.NONE
        return if (ringing.isEmpty()) Decision.STOP else Decision.NONE
    }
}
