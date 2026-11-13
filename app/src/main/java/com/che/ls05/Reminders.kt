package com.che.ls05

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.che.ls05.protocol.AlarmReminder
import com.che.ls05.protocol.AlarmSetting
import com.che.ls05.protocol.MSG_TYPE_GENERIC
import com.che.ls05.protocol.UteAlarm
import java.time.ZoneId

/**
 * Alarm hatırlatma notları (v0.6.1).
 *
 * Not telefonda saklanır. Alarm saati gelince telefon notu saate sıradan bildirim olarak gönderir.
 * Saatin kendi alarmı bundan bağımsızdır; not gitmese bile saat çalar.
 *
 * Tam zamanlı alarm izni varsa setExactAndAllowWhileIdle, yoksa setAndAllowWhileIdle kullanılır.
 * İkincisinde Android gönderimi geciktirebilir; 5 dakikayı aşan gecikmede not atılır.
 */
object Reminders {

    private const val REQUEST_BASE = 300
    const val EXTRA_SLOT = "slot"
    const val EXTRA_AT = "at"

    fun alarm(c: Context, slot: Int): AlarmSetting? =
        AlarmSetting.decode(SettingsStore.entry(c, AlarmSetting.key(slot)).desired)

    fun note(c: Context, slot: Int): String = AlarmReminder.cleanNote(Prefs.alarmNote(c, slot))

    /** Tam zamanlı alarm izni var mı. Android 12 öncesinde izin gerekmez. */
    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** En az bir yuvada etkin not var mı (izin uyarısını göstermek için). */
    fun anyActive(c: Context): Boolean =
        (1..UteAlarm.SLOTS).any { AlarmReminder.active(alarm(c, it), note(c, it)) }

    private fun pending(c: Context, slot: Int, at: Long, create: Boolean): PendingIntent? {
        val i = Intent(c, ReminderReceiver::class.java)
            .setAction("com.che.ls05.REMINDER.$slot")
            .putExtra(EXTRA_SLOT, slot)
            .putExtra(EXTRA_AT, at)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(c, REQUEST_BASE + slot, i, flags)
    }

    /** Üç yuvanın zamanlamasını baştan kurar. Eski zamanlama her durumda iptal edilir. */
    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val exact = canExact(c)
        var count = 0
        for (slot in 1..UteAlarm.SLOTS) {
            pending(c, slot, 0, create = false)?.let { am.cancel(it); it.cancel() }
            val at = AlarmReminder.next(alarm(c, slot), note(c, slot), now, zone) ?: continue
            val pi = pending(c, slot, at, create = true) ?: continue
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                count++
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                count++
            }
        }
        if (count > 0) WatchHub.log("Alarm notu zamanlaması: $count not (${if (exact) "tam zamanlı" else "gecikebilir"}).")
    }

    /** Zamanlama tetiklendi. Geçerlilik, gecikme ve aynı gün kontrolünden sonra not saate iletilir. */
    fun fire(c: Context, slot: Int, at: Long) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val a = alarm(c, slot)
        val n = note(c, slot)
        val day = AlarmReminder.dayKey(at, zone)
        when {
            !AlarmReminder.stillValid(a, n, at, zone) ->
                WatchHub.log("Alarm $slot notu: ayar değişmiş, eski zamanlama atıldı.")
            AlarmReminder.stale(at, now) ->
                WatchHub.log("Alarm $slot notu: ${(now - at) / 60_000} dakika gecikti, gönderilmedi.")
            Prefs.reminderSentDay(c, slot) == day ->
                WatchHub.log("Alarm $slot notu bugün zaten işlendi.")
            else -> {
                Prefs.setReminderSentDay(c, slot, day)
                val ttl = at + AlarmReminder.GRACE_MS - now
                WatchHub.forward(
                    c,
                    WatchHub.Note(c.packageName, "Alarm $slot notu", "Alarm", n, MSG_TYPE_GENERIC, now, ttlMs = ttl),
                )
            }
        }
        reschedule(c)
    }
}

/** Hatırlatma zamanlaması tetiklenince çalışır. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getIntExtra(Reminders.EXTRA_SLOT, 0)
        val at = intent.getLongExtra(Reminders.EXTRA_AT, 0L)
        if (slot !in 1..UteAlarm.SLOTS || at <= 0L) return
        Reminders.fire(context.applicationContext, slot, at)
    }
}
