package com.che.ls05.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** v0.10: sağlık verisinin CSV'ye aktarılması. Değerler yapaydır. */
class HealthCsvTest {

    private fun e(hex: String) = HealthEntry(0, 180, Hex.parse(hex))

    private fun step(d: Int, hour: Int, total: Int) =
        "0A 07 EA 0B %02X %02X %02X %02X 00 00 00 00 00 00 00 00 %02X %02X".format(d, hour, total shr 8, total and 0xFF, total shr 8, total and 0xFF)

    private fun live(d: Int, h: Int, m: Int, bpm: Int) = "18 03 07 EA 0B %02X %02X %02X %02X".format(d, h, m, bpm)

    private val d12 = LocalDate.of(2026, 11, 12)
    private val d13 = LocalDate.of(2026, 11, 13)
    private val d14 = LocalDate.of(2026, 11, 14)

    private fun body(csv: String) = csv.removePrefix(HealthCsv.BOM).split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun escapeRules() {
        assertEquals("abc", HealthCsv.escape("abc"))
        assertEquals("\"a,b\"", HealthCsv.escape("a,b"))
        assertEquals("\"a \"\"b\"\"\"", HealthCsv.escape("a \"b\""))
        assertEquals("\"a\nb\"", HealthCsv.escape("a\nb"))
        assertEquals("şğıİ", HealthCsv.escape("şğıİ"))
        assertEquals("x,\"y,z\",\r\n", HealthCsv.row(listOf("x", "y,z", "")))
    }

    @Test
    fun bomAndHeaderEvenWhenEmpty() {
        val v = HealthView(emptyList())
        val s = HealthCsv.steps(v, d12, d14)
        assertTrue(s.startsWith("﻿"), "BOM")
        assertEquals("﻿tarih,saat,adim\r\n", s)
        assertEquals("﻿tarih_saat,nabiz\r\n", HealthCsv.heart(v, d12, d14))
        assertEquals("﻿gece,baslangic,bitis,tur,dakika\r\n", HealthCsv.sleep(v, d12, d14))
        val bytes = HealthCsv.files(v, d12, d14)[0].second
        assertEquals(0xEF, bytes[0].toInt() and 0xFF)
        assertEquals(0xBB, bytes[1].toInt() and 0xFF)
        assertEquals(0xBF, bytes[2].toInt() and 0xFF)
    }

    @Test
    fun stepsSortedSkipEmptyHoursAndDays() {
        // Kayıtlar karışık sırada; 13 Kasım boş gün; 11 Kasım aralık dışında.
        val v = HealthView(listOf(e(step(14, 9, 120)), e(step(12, 7, 300)), e(step(12, 6, 50)), e(step(12, 8, 0)), e(step(11, 6, 999))))
        assertEquals(
            listOf("tarih,saat,adim", "2026-11-12,06:00,50", "2026-11-12,07:00,300", "2026-11-14,09:00,120"),
            body(HealthCsv.steps(v, d12, d14)),
        )
    }

    @Test
    fun heartValidSortedAndInRange() {
        val v = HealthView(
            listOf(
                e(live(13, 9, 5, 80)),
                e(live(12, 7, 20, 72)),
                e(live(12, 7, 20, 72)),
                e(live(12, 7, 30, 0xF5)),
                e(live(11, 23, 0, 60)),
                // Blok: 13 Kasım 08:00'de biten; yalnızca 07:00 (70) ve 08:00 (75) geçerli, FF ölçüm yok.
                e("18 07 EA 0B 0D 08 FF FF FF FF FF 46 FF FF FF FF FF 4B"),
            ),
        )
        assertEquals(
            listOf("tarih_saat,nabiz", "2026-11-12 07:20,72", "2026-11-13 07:00,70", "2026-11-13 08:00,75", "2026-11-13 09:05,80"),
            body(HealthCsv.heart(v, d12, d13)),
        )
    }

    @Test
    fun sleepAcrossMidnightOnlyDeepAndLight() {
        val v = HealthView(listOf(e("1D 01 07 EA 0B 0D 04"), e("1E 17 1E 02 01 00 3C 00 1E 01 01 00 5A 02 00 02 01 00 64 03 28 03 01 00 0A")))
        assertEquals(
            listOf(
                "gece,baslangic,bitis,tur,dakika",
                "2026-11-13,2026-11-12 23:30,2026-11-13 00:30,hafif,60",
                "2026-11-13,2026-11-13 00:30,2026-11-13 02:00,derin,90",
                "2026-11-13,2026-11-13 02:00,2026-11-13 03:40,hafif,100",
            ),
            body(HealthCsv.sleep(v, d12, d14)),
        )
        assertEquals(1, body(HealthCsv.sleep(v, d14, d14)).size, "aralık dışındaki gece yazılmaz")
    }

    @Test
    fun namesAndFormats() {
        assertEquals("finns-watch-saglik-20261114.zip", HealthCsv.fileName(d14))
        assertEquals("finns-watch-saglik-20260105.zip", HealthCsv.fileName(LocalDate.of(2026, 1, 5)))
        assertEquals("2026-11-14 13:20", HealthCsv.dateTime(LocalDateTime.of(2026, 11, 14, 13, 20)))
        assertEquals("2026-01-02 03:04", HealthCsv.dateTime(LocalDateTime.of(2026, 1, 2, 3, 4)))
    }

    @Test
    fun zipHoldsThreeFiles() {
        val v = HealthView(listOf(e(step(12, 7, 300))))
        val files = HealthCsv.files(v, d12, d14)
        val buf = ByteArrayOutputStream()
        HealthCsv.writeZip(buf, files, 0L)
        val names = ArrayList<String>()
        var steps = ""
        ZipInputStream(ByteArrayInputStream(buf.toByteArray())).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                names += entry.name
                val text = z.readBytes().toString(Charsets.UTF_8)
                if (entry.name == HealthCsv.STEPS) steps = text
            }
        }
        assertEquals(listOf("adim.csv", "nabiz.csv", "uyku.csv"), names)
        assertEquals("﻿tarih,saat,adim\r\n2026-11-12,07:00,300\r\n", steps)
    }
}
