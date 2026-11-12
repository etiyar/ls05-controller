package com.che.ls05.protocol

import java.time.LocalDateTime

/*
 * Ham sağlık kayıtları (v0.4.0).
 *
 * Saatten gelen kayıt baytları olduğu gibi saklanır; bütün değerler okurken hesaplanır.
 * Bir alanın anlamı sonradan düzelirse veri dönüştürmek gerekmez.
 *
 * Satır: alınma zamanı (epoch saniye) <sekme> telefonun UTC farkı (dakika) <sekme> onaltılık baytlar.
 * Cihazın ölçüm zamanı baytların içindedir.
 */

class HealthEntry(val receivedSec: Long, val offsetMin: Int, val bytes: ByteArray) {
    val hex: String get() = Hex.of(bytes)
}

data class HeartPoint(val at: LocalDateTime, val bpm: Int)

object HealthLog {

    /**
     * Saklanacak kayıt mı? Saklananlar: saatlik adım geçmişi (0a), nabız bloğu, nabız özeti (18 04),
     * sıfır olmayan canlı nabız (18 03), uyku kaydı (1e, henüz çözülmedi).
     * Saklanmayanlar: saniyede bir gelen canlı adım (09), bitiş işaretleri, yankılar.
     */
    fun shouldStore(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        return when (val f = UteParser.parse(b)) {
            is UteFrame.Steps -> !f.hour.live
            is UteFrame.HeartBlockFrame -> true
            is UteFrame.HeartSummary -> true
            is UteFrame.HeartLive -> f.bpm in VALID_BPM
            else -> b[0] == 0x1E.toByte() && b.size > 2
        }
    }

    /** Nabızda 0 ve FF "ölçüm yok" demektir; canlı bir insanda gerçek değer olamaz. */
    val VALID_BPM = 25..240

    fun line(e: HealthEntry): String = "${e.receivedSec}\t${e.offsetMin}\t${e.hex}"

    /** Bozuk ya da yarım satır için null. */
    fun parseLine(line: String): HealthEntry? {
        val p = line.trim().split('\t')
        if (p.size != 3) return null
        val at = p[0].toLongOrNull() ?: return null
        val off = p[1].toIntOrNull() ?: return null
        if (!p[2].split(' ').all { it.length == 2 }) return null
        val bytes = try { Hex.parse(p[2]) } catch (e: Exception) { return null }
        if (bytes.isEmpty()) return null
        return HealthEntry(at, off, bytes)
    }

    /** Bozuk satırlar atlanır, diğerleri okunur. */
    fun parseAll(text: String): List<HealthEntry> = text.split('\n').mapNotNull { parseLine(it) }
}

/**
 * Kayıtlardan hesaplanan görünüm. entries alınma sırasıyla verilir; aynı saate ait kayıtlarda sonuncusu geçerlidir.
 * live: bellekteki canlı adım kayıtları (dosyaya yazılmaz).
 */
class HealthView(entries: List<HealthEntry>, live: Collection<StepHour> = emptyList()) {

    private val stepByHour = LinkedHashMap<Pair<UteDate, Int>, Int>()
    private val heart = ArrayList<HeartPoint>()

    /** Saatin bildirdiği son günlük nabız özeti. */
    var heartSummary: UteFrame.HeartSummary? = null
        private set

    init {
        for (e in entries) {
            when (val f = UteParser.parse(e.bytes)) {
                is UteFrame.Steps -> if (validDate(f.hour.date) && f.hour.hour in 0..23) {
                    stepByHour[f.hour.date to f.hour.hour] = f.hour.total
                }
                is UteFrame.HeartBlockFrame -> addBlock(f.block)
                is UteFrame.HeartLive -> if (f.bpm in HealthLog.VALID_BPM && validDate(f.date) && f.hour in 0..23 && f.minute in 0..59) {
                    heart += HeartPoint(LocalDateTime.of(f.date.year, f.date.month, f.date.day, f.hour, f.minute), f.bpm)
                }
                is UteFrame.HeartSummary -> heartSummary = f
                else -> Unit
            }
        }
        // Canlı kayıt içinde bulunulan saatin güncel toplamıdır; geçmiş kaydı eskiyse büyük olan geçerlidir.
        for (s in live) {
            if (!validDate(s.date) || s.hour !in 0..23) continue
            val k = s.date to s.hour
            stepByHour[k] = maxOf(stepByHour[k] ?: 0, s.total)
        }
    }

    private fun validDate(d: UteDate) = d.year in 2020..2100 && d.month in 1..12 && d.day in 1..31 &&
        try { LocalDateTime.of(d.year, d.month, d.day, 0, 0); true } catch (e: Exception) { false }

    /**
     * Blok 12 değer taşır, 10 dakika arayla. Son değer blok saatine (tam saat), ilk değer ondan 110 dakika öncesine aittir.
     * Saat 0 bloğunun ilk 11 değeri bir önceki günündür.
     */
    private fun addBlock(b: HeartBlock) {
        if (!validDate(b.date) || b.endHour !in 0..23) return
        val end = LocalDateTime.of(b.date.year, b.date.month, b.date.day, b.endHour, 0)
        b.values.forEachIndexed { i, v ->
            if (v != null && v in HealthLog.VALID_BPM) heart += HeartPoint(end.minusMinutes((11 - i) * 10L), v)
        }
    }

    /** O güne ait kayıt yoksa null, varsa toplam adım. */
    fun stepsForDay(day: UteDate): Int? {
        val hours = stepByHour.filterKeys { it.first == day }
        return if (hours.isEmpty()) null else hours.values.sum()
    }

    /** 24 saatlik dağılım. */
    fun hourlySteps(day: UteDate): IntArray {
        val out = IntArray(24)
        for ((k, v) in stepByHour) if (k.first == day) out[k.second] = v
        return out
    }

    /** En son geçerli nabız ölçümü. notAfter verilirse ondan sonraki (saat hatası) kayıtlar sayılmaz. */
    fun lastHeart(notAfter: LocalDateTime? = null): HeartPoint? =
        heart.filter { notAfter == null || !it.at.isAfter(notAfter) }.maxByOrNull { it.at }

    fun heartPoints(): List<HeartPoint> = heart.sortedBy { it.at }
}

/** Uzun bildirimde saatin kaç karakter gösterdiğini ölçmek için cetvel metinleri (127 karakterin altında). */
object LengthTest {
    /** "01 02 03 … 42": saatte görünen son sayı × 3 - 1 = görünen karakter sayısı. */
    fun ruler(): String = (1..42).joinToString(" ") { "%02d".format(it) }

    /** Türkçe harfli cetvel: her sayı öncesinde bir Türkçe harf. Görünen son sayı × 3 = karakter sayısı. */
    fun turkishRuler(): String {
        val letters = "şğıöüçİŞĞÖÜÇ"
        return (1..42).joinToString("") { "${letters[(it - 1) % letters.length]}%02d".format(it) }
    }
}
