package com.che.ls05.protocol

/*
 * v0.2 M3 ve v0.3a komutları.
 * Kaynak: 2026-11-12 Haylou Fun yakalaması (btsnoop) + Haylou Fun youcy olay kodu.
 * Her yapının güven düzeyi yorumda yazılıdır. "Yüksek" = yakalama ve kod birebir uyuşuyor.
 */

private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

/** Basit tarih/saat; java.time kullanılmaz ki modül saf Kotlin kalsın. */
data class UteDate(val year: Int, val month: Int, val day: Int) {
    fun bytes(): ByteArray = byteArrayOf((year shr 8).toByte(), year.toByte(), month.toByte(), day.toByte())

    companion object {
        fun read(b: ByteArray, at: Int) = UteDate(u16(b, at), u8(b, at + 2), u8(b, at + 3))
    }
}

/**
 * Alarm (komut 06). Güven: yüksek.
 * 06 [tekrar] [saat] [dakika] [02 08 06 | 00 00 00] 00 [yuva]
 * Kapalı alarmda tekrar baytı 0x60, üç bayt sıfır (Haylou Fun böyle gönderiyor).
 * Saat yanıtı: 06 [yuva].
 */
object UteAlarm {
    const val SLOTS = 3

    /** Gün bitleri: bit0 pazar, bit1 pazartesi … bit6 cumartesi. */
    const val SUNDAY = 0x01
    const val MONDAY = 0x02
    const val TUESDAY = 0x04
    const val WEDNESDAY = 0x08
    const val THURSDAY = 0x10
    const val FRIDAY = 0x20
    const val SATURDAY = 0x40
    const val WEEKDAYS = 0x3E
    const val WEEKEND = 0x41
    const val EVERY_DAY = 0x7F
    private const val DISABLED_MARK = 0x60

    /** Arayüz sırası: pazartesiden pazara. */
    val UI_ORDER = intArrayOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)

    fun build(slot: Int, hour: Int, minute: Int, repeatMask: Int, enabled: Boolean): ByteArray {
        require(slot in 1..SLOTS) { "yuva 1..$SLOTS olmalı" }
        require(hour in 0..23 && minute in 0..59) { "geçersiz saat" }
        require(repeatMask in 0..0x7F) { "geçersiz gün maskesi" }
        // Maskesi boş bir alarm tek seferlik değil, belirsiz. Açık alarmda en az bir gün iste.
        require(!enabled || repeatMask != 0) { "açık alarmda en az bir gün seçilmeli" }
        return if (enabled) {
            byteArrayOf(0x06, repeatMask.toByte(), hour.toByte(), minute.toByte(), 0x02, 0x08, 0x06, 0x00, slot.toByte())
        } else {
            byteArrayOf(0x06, DISABLED_MARK.toByte(), hour.toByte(), minute.toByte(), 0, 0, 0, 0, slot.toByte())
        }
    }

    fun maskLabel(mask: Int): String = when (mask) {
        EVERY_DAY -> "Her gün"
        WEEKDAYS -> "Hafta içi"
        WEEKEND -> "Hafta sonu"
        else -> {
            val names = arrayOf("Pt", "Sa", "Ça", "Pe", "Cu", "Ct", "Pz")
            UI_ORDER.indices.filter { mask and UI_ORDER[it] != 0 }.joinToString(" ") { names[it] }.ifEmpty { "Gün yok" }
        }
    }
}

/**
 * Rahatsız etme (komut 15). Güven: yüksek.
 * 15 00 [başlangıç saat] [dakika] [bitiş saat] [dakika] [01 açık | 00 kapalı]. Saat aynen geri yollar.
 */
object UteDnd {
    fun build(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, enabled: Boolean): ByteArray {
        require(startHour in 0..23 && endHour in 0..23 && startMinute in 0..59 && endMinute in 0..59) { "geçersiz saat" }
        return byteArrayOf(
            0x15, 0x00, startHour.toByte(), startMinute.toByte(), endHour.toByte(), endMinute.toByte(),
            if (enabled) 1 else 0,
        )
    }
}

/**
 * Hava durumu (komut 11), üç paket. Güven: düzen yüksek, hava kodu anlamları orta.
 * 11 01 [kod lo] [kod hi] [şimdi] [yüksek] [düşük] [pm2.5 2B] [hava kalitesi 2B] [şehir: UTF-16BE, en çok 8 bayt]
 * 11 02 + 4 gün × [kod lo, kod hi, yüksek, düşük]
 * 11 03 + 2 gün × [kod lo, kod hi, yüksek, düşük]
 * Sıcaklık: mutlak değer, eksi ise bit7 = 1.
 */
object UteWeather {
    /** Haylou sunucu kodları, başka saat sürücüsündeki eşlemeden çıkarıldı. Güven: orta. */
    const val SUNNY = 1
    const val PARTLY_CLOUDY = 2
    const val CLOUDY = 3
    const val SHOWERS = 4
    const val THUNDER = 5
    const val SLEET = 6
    const val DRIZZLE = 7
    const val HEAVY_RAIN = 8
    const val SNOW = 9
    const val DUST = 10
    const val HAZE = 11
    const val STORM = 12

    data class Day(val code: Int, val high: Int, val low: Int)

    fun temp(t: Int): Byte {
        val a = minOf(kotlin.math.abs(t), 127)
        return (if (t < 0) a or 0x80 else a).toByte()
    }

    fun decodeTemp(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (v and 0x80 != 0) -(v and 0x7F) else v
    }

    /** days: bugün dahil tam 7 gün. city: en çok 4 karakter gönderilir. */
    fun build(current: Int, days: List<Day>, city: String, pm25: Int = 0, air: Int = 0): List<ByteArray> {
        require(days.size == 7) { "7 gün gerekli" }
        val p1 = ByteArray(19)
        p1[0] = 0x11; p1[1] = 0x01
        p1[2] = days[0].code.toByte(); p1[3] = (days[0].code shr 8).toByte()
        p1[4] = temp(current); p1[5] = temp(days[0].high); p1[6] = temp(days[0].low)
        p1[7] = (pm25 shr 8).toByte(); p1[8] = pm25.toByte()
        p1[9] = (air shr 8).toByte(); p1[10] = air.toByte()
        val name = TextSanitizer.clean(city).toByteArray(Charsets.UTF_16BE)
        name.copyInto(p1, 11, 0, minOf(8, name.size))

        fun part(index: Int, from: Int, count: Int): ByteArray {
            val p = ByteArray(2 + count * 4)
            p[0] = 0x11; p[1] = index.toByte()
            for (k in 0 until count) {
                val d = days[from + k]
                val o = 2 + k * 4
                p[o] = d.code.toByte(); p[o + 1] = (d.code shr 8).toByte()
                p[o + 2] = temp(d.high); p[o + 3] = temp(d.low)
            }
            return p
        }
        return listOf(p1, part(2, 1, 4), part(3, 5, 2))
    }
}

/** Sağlık verisi istekleri. Hepsi okuma; Haylou Fun her bağlantıda gönderiyor. */
object UteHealth {
    /** 0a [tarih] 00 00: o günden bugüne saatlik adım kayıtları. */
    fun stepHistory(from: UteDate): ByteArray = byteArrayOf(0x0A) + from.bytes() + byteArrayOf(0, 0)

    /** 18 FA [tarih]: önce 18 04 özet, sonra 2 saatlik nabız blokları, sonra 18 FD [xor]. */
    fun heartHistory(from: UteDate): ByteArray = byteArrayOf(0x18, 0xFA.toByte()) + from.bytes()

    /**
     * 1d 01 [tarih] 00: uyku geçmişi (Haylou Fun biçimi). Kayıtlar 1e … olarak 61FF/6102'den gelir,
     * son 1d 02. 1e düzeni çözülmedi; ham loglanır.
     */
    fun sleepHistory(from: UteDate): ByteArray = byteArrayOf(0x1D, 0x01) + from.bytes() + byteArrayOf(0)

    /** Geçmiş sonu baytı: kayıtların komut baytı hariç tüm baytlarının XOR'u. Güven: yüksek (4/4). */
    fun historyChecksum(records: List<ByteArray>): Int =
        records.fold(0) { acc, r -> (1 until r.size).fold(acc) { a, i -> a xor u8(r, i) } }
}

/**
 * Saatlik adım kaydı. 0a (geçmiş) ve 09 (canlı, saat kendiliğinden yollar) aynı düzende, 18 bayt.
 * [1-4] tarih [5] saat [6-7] toplam [8] koşu baş dk [9] koşu son dk [10] koşu dk [11-12] koşu adım
 * [13] yürüme baş dk [14] yürüme son dk [15] yürüme dk [16-17] yürüme adım.
 * Güven: toplam = koşu + yürüme üç örnekte tuttu (yüksek); dakika alanları orta.
 */
data class StepHour(
    val date: UteDate, val hour: Int, val total: Int,
    val runSteps: Int, val walkSteps: Int, val runMinutes: Int, val walkMinutes: Int,
    val live: Boolean,
) {
    val consistent get() = total == runSteps + walkSteps

    companion object {
        fun parse(b: ByteArray): StepHour? {
            if (b.size < 18 || (b[0] != 0x0A.toByte() && b[0] != 0x09.toByte())) return null
            return StepHour(
                date = UteDate.read(b, 1), hour = u8(b, 5), total = u16(b, 6),
                runSteps = u16(b, 11), walkSteps = u16(b, 16),
                runMinutes = u8(b, 10), walkMinutes = u8(b, 15),
                live = b[0] == 0x09.toByte(),
            )
        }
    }
}

/**
 * Nabız bloğu: 18 [tarih] [saat] + 12 değer (10 dakikada bir, FF = ölçüm yok).
 * Blok, [saat]-2:00 ile [saat]:00 arasını kapsar; saat 0 bir önceki günün 22-24 dilimidir.
 * Güven: yüksek (Haylou Fun ayrıştırıcısı).
 */
data class HeartBlock(val date: UteDate, val endHour: Int, val values: List<Int?>) {
    companion object {
        fun parse(b: ByteArray): HeartBlock? {
            if (b.size < 18 || b[0] != 0x18.toByte()) return null
            val second = u8(b, 1)
            if (second in setOf(0x01, 0x02, 0x03, 0x04, 0xFA, 0xFD)) return null
            val vals = (0 until 12).map { u8(b, 6 + it).let { v -> if (v == 0xFF || v == 0) null else v } }
            return HeartBlock(UteDate.read(b, 1), u8(b, 5), vals)
        }
    }
}

/** Gün sonu özeti için basit toplama. Saat 0 bloğu önceki güne aittir; çağıran tarih süzmeli. */
object HealthSummary {
    fun stepsForDay(day: UteDate, records: Collection<StepHour>): Int =
        records.filter { it.date == day }.groupBy { it.hour }
            .values.sumOf { list -> list.maxOf { it.total } }
}

/**
 * Profil ve ekran ayarları (komut 05), 19 bayt. Güven: düzen yüksek.
 * Kaynaklar: Haylou Fun SetDeviceParamsEvent, Hello Haylou a3.u0 (SET_USER_INFO) ve kayıt (iki bağlantıda aynı düzen).
 *
 * 05 [boy cm 2B] [kilo kg 2B] [ekran açık süresi sn] [adım hedefi 4B] [bileği kaldır 0/1]
 *    [nabız üst uyarı, kapalıysa FF] 00 [yaş] [cinsiyet 1 erkek / 2 kadın]
 *    [kayıp uyarısı] [dil] [mesafe birimi 1 km / 2 mil] [nabız alt uyarı, kapalıysa 00]
 *
 * Kodda çözülen iki bayt:
 *  - bayt 15 ("d6"): Haylou Fun'da o6.a.d("watch_device_lost_warning", 0), yani saatin bağlantı kopunca
 *    uyarması (kayıp uyarısı). Hello Haylou hep 0. Kayıtta 0. Biz 0 gönderiyoruz (kapalı).
 *  - bayt 16 ("E"): Haylou Fun'da SetWatchLanguageEvent.E(), yani dil kodu (25 AB [dil] ile aynı tablo: 2 İngilizce,
 *    16 Türkçe). Hello Haylou hep 2. Kayıtta 2. Biz 2 gönderiyoruz; Türkçe menü ayrı bir kontrollü test konusu.
 * Sabit tutulanlar (kayıttaki değerler): nabız uyarıları kapalı (FF / 00), mesafe km (1), bayt 12 = 0.
 * Ekran süresi seçenekleri Haylou Fun arayüzünden: 5, 10, 15 sn. Saatin yanıtı tek bayt 05 (kayıt).
 */
object UteProfile {
    val SCREEN_SECONDS = intArrayOf(5, 10, 15)
    const val SEX_MALE = 1
    const val SEX_FEMALE = 2
    val HEIGHT_RANGE = 80..250
    val WEIGHT_RANGE = 20..250
    val AGE_RANGE = 5..120
    val GOAL_RANGE = 1000..50000

    const val LOST_WARNING_OFF = 0
    const val LANGUAGE_ENGLISH = 2
    const val DISTANCE_KM = 1
    const val HR_HIGH_OFF = 0xFF
    const val HR_LOW_OFF = 0

    fun build(heightCm: Int, weightKg: Int, screenSec: Int, stepGoal: Int, raiseWrist: Boolean, age: Int, sex: Int): ByteArray {
        require(heightCm in HEIGHT_RANGE) { "boy aralık dışında" }
        require(weightKg in WEIGHT_RANGE) { "kilo aralık dışında" }
        require(screenSec in SCREEN_SECONDS) { "ekran süresi 5, 10 ya da 15 sn olmalı" }
        require(stepGoal in GOAL_RANGE) { "adım hedefi aralık dışında" }
        require(age in AGE_RANGE) { "yaş aralık dışında" }
        require(sex == SEX_MALE || sex == SEX_FEMALE) { "cinsiyet 1 ya da 2 olmalı" }
        return byteArrayOf(
            0x05,
            (heightCm shr 8).toByte(), heightCm.toByte(),
            (weightKg shr 8).toByte(), weightKg.toByte(),
            screenSec.toByte(),
            (stepGoal shr 24).toByte(), (stepGoal shr 16).toByte(), (stepGoal shr 8).toByte(), stepGoal.toByte(),
            (if (raiseWrist) 1 else 0).toByte(),
            HR_HIGH_OFF.toByte(),
            0,
            age.toByte(),
            sex.toByte(),
            LOST_WARNING_OFF.toByte(),
            LANGUAGE_ENGLISH.toByte(),
            DISTANCE_KM.toByte(),
            HR_LOW_OFF.toByte(),
        )
    }
}
