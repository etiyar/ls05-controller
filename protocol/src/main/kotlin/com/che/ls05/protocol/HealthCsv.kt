package com.che.ls05.protocol

import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Sağlık verisini dışa aktarma (v0.10).
 *
 * Excel'de açılabilecek üç CSV: adim.csv, nabiz.csv, uyku.csv. Ayırıcı virgül, satır sonu CRLF,
 * dosya başında UTF-8 BOM (Türkçe Excel karakterleri doğru okusun diye). Tarihler ISO biçiminde.
 * Satırlar zamana göre sıralıdır; verisi olmayan gün ve saat yazılmaz.
 */
object HealthCsv {

    const val BOM = "﻿"
    const val STEPS = "adim.csv"
    const val HEART = "nabiz.csv"
    const val SLEEP = "uyku.csv"

    /** Virgül, tırnak ya da satır sonu içeren alan tırnağa alınır; içteki tırnak ikilenir. */
    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    fun row(fields: List<String>): String = fields.joinToString(",") { escape(it) } + "\r\n"

    /** BOM + başlık + satırlar. */
    fun table(header: List<String>, rows: List<List<String>>): String =
        buildString {
            append(BOM)
            append(row(header))
            rows.forEach { append(row(it)) }
        }

    private fun two(n: Int) = if (n < 10) "0$n" else n.toString()

    fun date(d: LocalDate): String = d.toString()

    /** "2026-11-14 13:20". */
    fun dateTime(t: LocalDateTime): String = "${t.toLocalDate()} ${two(t.hour)}:${two(t.minute)}"

    /** "finns-watch-saglik-20261114.zip". */
    fun fileName(d: LocalDate): String = "finns-watch-saglik-${d.year}${two(d.monthValue)}${two(d.dayOfMonth)}.zip"

    private fun days(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = from
        while (!d.isAfter(to)) {
            out += d
            d = d.plusDays(1)
        }
        return out
    }

    private fun ute(d: LocalDate) = UteDate(d.year, d.monthValue, d.dayOfMonth)

    /** tarih,saat,adim. Saatlik satırlar; adımı olmayan saat yazılmaz. */
    fun steps(view: HealthView, from: LocalDate, to: LocalDate): String {
        val rows = ArrayList<List<String>>()
        for (d in days(from, to)) {
            val hours = view.hourlySteps(ute(d))
            for (h in 0..23) if (hours[h] > 0) rows += listOf(date(d), "${two(h)}:00", hours[h].toString())
        }
        return table(listOf("tarih", "saat", "adim"), rows)
    }

    /** tarih_saat,nabiz. Yalnızca geçerli ölçümler; aynı dakikadaki aynı değer bir kez yazılır. */
    fun heart(view: HealthView, from: LocalDate, to: LocalDate): String {
        val rows = view.heartPoints()
            .filter { it.bpm in HealthLog.VALID_BPM && !it.at.toLocalDate().isBefore(from) && !it.at.toLocalDate().isAfter(to) }
            .distinct()
            .sortedBy { it.at }
            .map { listOf(dateTime(it.at), it.bpm.toString()) }
        return table(listOf("tarih_saat", "nabiz"), rows)
    }

    /**
     * gece,baslangic,bitis,tur,dakika. gece: uyanılan gün. Bölüm saati 18 ve sonrasıysa bir önceki güne aittir
     * ([HealthView.sleepFor] ile aynı kural). Yalnızca derin ve hafif bölümler yazılır.
     */
    fun sleep(view: HealthView, from: LocalDate, to: LocalDate): String {
        val rows = ArrayList<List<String>>()
        for (d in days(from, to)) {
            val wake = d.atStartOfDay()
            for (s in view.sleepSegmentsFor(ute(d))) {
                val kind = when (s.type) {
                    SleepSegment.DEEP -> "derin"
                    SleepSegment.LIGHT -> "hafif"
                    else -> continue
                }
                val start = (if (s.hour >= 18) wake.minusDays(1) else wake).withHour(s.hour).withMinute(s.minute)
                val end = start.plusMinutes(s.minutes.toLong())
                rows += listOf(date(d), dateTime(start), dateTime(end), kind, s.minutes.toString())
            }
        }
        return table(listOf("gece", "baslangic", "bitis", "tur", "dakika"), rows)
    }

    /** Üç dosya, ad ve UTF-8 içerik (BOM dahil). */
    fun files(view: HealthView, from: LocalDate, to: LocalDate): List<Pair<String, ByteArray>> = listOf(
        STEPS to steps(view, from, to).toByteArray(Charsets.UTF_8),
        HEART to heart(view, from, to).toByteArray(Charsets.UTF_8),
        SLEEP to sleep(view, from, to).toByteArray(Charsets.UTF_8),
    )

    /** Dosyaları zip olarak yazar. Akışı kapatmaz. */
    fun writeZip(out: OutputStream, files: List<Pair<String, ByteArray>>, timeMs: Long) {
        val zip = ZipOutputStream(out)
        for ((name, bytes) in files) {
            val e = ZipEntry(name)
            e.time = timeMs
            zip.putNextEntry(e)
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }
}
