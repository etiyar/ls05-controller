package com.che.ls05.protocol

import java.util.Locale
import kotlin.math.roundToInt

/** Telefonun bir konumu: enlem, boylam, doğruluk (m, 0 = bilinmiyor) ve zaman (ms). */
data class SeenFix(val lat: Double, val lon: Double, val accuracy: Float, val at: Long)

/**
 * "Son görülen yer" kuralları. Android'e bağımlı değildir; birim testiyle doğrulanır.
 * Konum yalnızca telefonda saklanır, loga yazılmaz.
 */
object LastSeenRules {
    /** Eski konum (getLastKnownLocation) en çok bu kadar eski olabilir. */
    const val MAX_AGE_MS = 5 * 60 * 1000L

    /** Telefon saatleri arasındaki küçük farklar için: bu kadar "gelecekteki" konum da taze sayılır. */
    const val FUTURE_SKEW_MS = 60 * 1000L

    /** Anlık konum en çok bu kadar beklenir. */
    const val CURRENT_WAIT_MS = 10_000L

    /** Adaylardan en yeni ve yeterince taze olanı; yoksa null. */
    fun pick(candidates: List<SeenFix?>, now: Long, maxAgeMs: Long = MAX_AGE_MS): SeenFix? =
        candidates.filterNotNull()
            .filter { now - it.at <= maxAgeMs && it.at - now <= FUTURE_SKEW_MS && valid(it) }
            .maxByOrNull { it.at }

    fun valid(f: SeenFix): Boolean =
        !f.lat.isNaN() && !f.lon.isNaN() && f.lat in -90.0..90.0 && f.lon in -180.0..180.0 &&
            !(f.lat == 0.0 && f.lon == 0.0)

    /** Doğruluk metni: "±40 m", "±1,2 km"; bilinmiyorsa boş. */
    fun accuracyText(accuracy: Float): String {
        if (accuracy.isNaN() || accuracy <= 0f) return ""
        val m = accuracy.roundToInt().coerceAtLeast(1)
        if (m < 1000) return "±$m m"
        val km = (m / 100.0).roundToInt() / 10.0
        return "±" + String.format(Locale.US, "%.1f", km).replace('.', ',').removeSuffix(",0") + " km"
    }

    /** "Son görülen: 14:32 (±40 m)". Doğruluk bilinmiyorsa parantez yazılmaz. */
    fun label(hour: Int, minute: Int, accuracy: Float): String {
        val hm = String.format(Locale.US, "%02d:%02d", hour, minute)
        val acc = accuracyText(accuracy)
        return if (acc.isEmpty()) "Son görülen: $hm" else "Son görülen: $hm ($acc)"
    }

    /** Harita uygulaması için geo adresi: geo:lat,lon?q=lat,lon(Saat). */
    fun geoUri(f: SeenFix): String {
        val ll = String.format(Locale.US, "%.6f,%.6f", f.lat, f.lon)
        return "geo:$ll?q=$ll(Saat)"
    }

    /**
     * Kayıt bu kopmaya mı ait. Kopma zamanı [since] ilk kopmanın zamanıdır; kayıt o andan sonra alınır.
     * Önceki bir kopmadan kalan kayıt uyarıda gösterilmez.
     */
    fun belongsTo(f: SeenFix, since: Long): Boolean = f.at >= since - 2_000

    /** Ayar dosyasında saklama biçimi: "lat;lon;doğruluk;zaman". */
    fun encode(f: SeenFix): String = "${f.lat};${f.lon};${f.accuracy};${f.at}"

    fun decode(s: String?): SeenFix? {
        val p = s?.split(';') ?: return null
        if (p.size != 4) return null
        val f = SeenFix(
            p[0].toDoubleOrNull() ?: return null,
            p[1].toDoubleOrNull() ?: return null,
            p[2].toFloatOrNull() ?: return null,
            p[3].toLongOrNull() ?: return null,
        )
        return if (valid(f)) f else null
    }
}
