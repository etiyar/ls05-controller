package com.che.ls05.protocol

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Alarm hatırlatma notu (v0.6.1).
 *
 * Saatin alarm komutu yazı taşımıyor. Not yalnızca telefonda saklanır; alarm saati gelince telefon notu
 * saate sıradan bildirim olarak gönderir. Saatin kendi alarmı bundan bağımsızdır.
 *
 * Kurallar:
 *  - Not isteğe bağlı, en çok 40 karakter, tek satır.
 *  - Planlanan saatten 5 dakika sonra artık gönderilmez (geç kalmış hatırlatma düşmez).
 *  - Aynı alarmın notu aynı gün bir kez gider.
 *  - Tetiklenen zamanlama ancak ayarlar hâlâ aynı anı üretiyorsa geçerlidir; ayar değiştiyse eskisi düşer.
 */
object AlarmReminder {

    const val MAX_NOTE = 40
    const val GRACE_MS = 5 * 60 * 1000L

    /** Satır sonları ve fazla boşluklar tek boşluk olur; baş/son boşluk atılır; 40 karaktere kesilir. */
    fun cleanNote(text: String?): String {
        if (text == null) return ""
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= MAX_NOTE) return flat
        var cut = flat.substring(0, MAX_NOTE)
        // Yarım kalmış vekil çifti bırakma.
        if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
        return cut.trimEnd()
    }

    /** Bu alarm ve not için hatırlatma kurulmalı mı. */
    fun active(alarm: AlarmSetting?, note: String?): Boolean =
        alarm != null && alarm.enabled && alarm.mask != 0 && cleanNote(note).isNotEmpty()

    /** Haftanın gününe karşılık gelen maske biti (bit0 = Pazar … bit6 = Cumartesi). */
    private fun bit(t: ZonedDateTime): Int = 1 shl (t.dayOfWeek.value % 7)

    /**
     * after anından kesin olarak sonraki ilk alarm anı. Etkin değilse null.
     * Yaz saati boşluğuna düşen saat ZonedDateTime kuralıyla ileri kayar.
     */
    fun next(alarm: AlarmSetting?, note: String?, afterMs: Long, zone: ZoneId): Long? {
        if (!active(alarm, note)) return null
        val a = alarm!!
        val after = Instant.ofEpochMilli(afterMs).atZone(zone)
        for (d in 0..7) {
            val date = after.toLocalDate().plusDays(d.toLong())
            val t = ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, a.hour, a.minute, 0, 0, zone)
            if ((a.mask and bit(t)) != 0 && t.toInstant().toEpochMilli() > afterMs) {
                return t.toInstant().toEpochMilli()
            }
        }
        return null
    }

    /** Tetiklenen an, şimdiki ayarlarla hâlâ aynı hatırlatmaya mı karşılık geliyor. */
    fun stillValid(alarm: AlarmSetting?, note: String?, scheduledMs: Long, zone: ZoneId): Boolean =
        next(alarm, note, scheduledMs - 1, zone) == scheduledMs

    /** Planlanan andan bu yana 5 dakikadan fazla geçtiyse true. */
    fun stale(scheduledMs: Long, nowMs: Long): Boolean = nowMs - scheduledMs > GRACE_MS

    /** Aynı gün tekrarı engellemek için gün anahtarı (yerel tarih, ör. 2026-11-14). */
    fun dayKey(scheduledMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(scheduledMs).atZone(zone).toLocalDate().toString()
}
