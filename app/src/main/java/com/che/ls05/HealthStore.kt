package com.che.ls05

import android.content.Context
import com.che.ls05.protocol.HealthEntry
import com.che.ls05.protocol.HealthLog
import com.che.ls05.protocol.HealthView
import com.che.ls05.protocol.Hex
import com.che.ls05.protocol.StepHour
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ham sağlık kayıtları: filesDir/health/yyyy-MM-dd.log (alınma gününe göre), yalnızca sona ekleme.
 * Bellekte son 3 günün kayıtları tutulur. Canlı adım (09) dosyaya yazılmaz, yalnızca bellekte.
 * Sağlık değerleri loga yazılmaz. Tüm çağrılar ana iş parçacığında yapılır.
 */
object HealthStore {

    private const val DIR = "health"
    private const val VIEW_DAYS = 3L
    private const val KEEP_DAYS = 90L
    private val NAME = Regex("""\d{4}-\d{2}-\d{2}\.log""")

    private var dir: File? = null
    private val entries = ArrayList<HealthEntry>()
    private val seen = HashSet<String>()
    private var trimmedFor: LocalDate? = null

    /** Canlı (09) adım kayıtları, anahtar "y-m-d-h". Dosyaya yazılmaz. */
    val live: MutableMap<String, StepHour> = LinkedHashMap()

    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun today(): LocalDate = LocalDate.now(zone())
    private fun fileFor(d: File, day: LocalDate) = File(d, "$day.log")

    fun init(c: Context) {
        if (dir != null) return
        val d = File(c.filesDir, DIR)
        dir = d
        try {
            if (!d.exists()) d.mkdirs()
        } catch (e: Exception) {
            WatchHub.log("Sağlık klasörü açılamadı: ${e.javaClass.simpleName}")
        }
        val today = today()
        var bad = 0
        for (back in VIEW_DAYS - 1 downTo 0L) {
            val day = today.minusDays(back)
            val f = fileFor(d, day)
            try {
                if (!f.exists()) continue
                val text = f.readText(Charsets.UTF_8)
                val parsed = HealthLog.parseAll(text)
                bad += text.split('\n').count { it.isNotBlank() } - parsed.size
                for (e in parsed) {
                    if (seen.add(e.hex)) entries += e
                }
                // Yarım kalmış son satır yeni kayda yapışmasın.
                if (back == 0L && text.isNotEmpty() && !text.endsWith("\n")) {
                    FileOutputStream(f, true).use { it.write("\n".toByteArray(Charsets.UTF_8)) }
                }
            } catch (e: Exception) {
                WatchHub.log("Sağlık kaydı okunamadı ($day): ${e.javaClass.simpleName}")
            }
        }
        trimmedFor = today
        if (bad > 0) WatchHub.log("Sağlık kayıtlarında $bad bozuk satır atlandı.")
        deleteOld(d, today)
    }

    /** 90 günden eski günlük dosyaları siler. Yalnızca adı tarih biçimine uyan dosyalara dokunur. */
    private fun deleteOld(d: File, today: LocalDate) {
        try {
            val limit = today.minusDays(KEEP_DAYS)
            var removed = 0
            for (f in d.listFiles() ?: emptyArray()) {
                if (!f.isFile || !NAME.matches(f.name)) continue
                val day = try { LocalDate.parse(f.name.removeSuffix(".log")) } catch (e: Exception) { continue }
                if (day.isBefore(limit) && f.delete()) removed++
            }
            if (removed > 0) WatchHub.log("90 günden eski $removed sağlık dosyası silindi.")
        } catch (e: Exception) {
            WatchHub.log("Eski sağlık dosyaları silinemedi: ${e.javaClass.simpleName}")
        }
    }

    /** Gün değiştiyse bellekte yalnızca son 3 günü bırakır ve eski dosyaları siler. */
    private fun trim() {
        val today = today()
        if (trimmedFor == today) return
        trimmedFor = today
        val start = today.minusDays(VIEW_DAYS - 1).atStartOfDay(zone()).toEpochSecond()
        entries.removeAll { it.receivedSec < start }
        seen.clear()
        entries.forEach { seen.add(it.hex) }
        dir?.let { deleteOld(it, today) }
    }

    /** Saklanması gereken bir kayıtsa dosyaya ekler. Saklandıysa true. Aynı paket ikinci kez yazılmaz. */
    fun add(c: Context, bytes: ByteArray): Boolean {
        init(c)
        if (!HealthLog.shouldStore(bytes)) return false
        trim()
        val copy = bytes.copyOf()
        val hex = Hex.of(copy)
        if (hex in seen) return false
        val d = dir ?: return false
        val nowMs = System.currentTimeMillis()
        val instant = Instant.ofEpochMilli(nowMs)
        val offsetMin = zone().rules.getOffset(instant).totalSeconds / 60
        val entry = HealthEntry(nowMs / 1000, offsetMin, copy)
        val day = instant.atZone(zone()).toLocalDate()
        return try {
            if (!d.exists()) d.mkdirs()
            FileOutputStream(fileFor(d, day), true).use {
                it.write((HealthLog.line(entry) + "\n").toByteArray(Charsets.UTF_8))
            }
            seen.add(hex)
            entries += entry
            true
        } catch (e: Exception) {
            WatchHub.log("Sağlık kaydı yazılamadı: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Son 3 günün kayıtları + bellekteki canlı adımlar. */
    fun view(): HealthView {
        if (dir != null) trim()
        return HealthView(entries.toList(), live.values.toList())
    }
}
