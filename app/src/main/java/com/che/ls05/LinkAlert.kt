package com.che.ls05

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.che.ls05.protocol.DndSetting
import com.che.ls05.protocol.LostLinkRules
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * "Saat uzaklaştı" uyarısı.
 *
 * - Bağlantı beklenmedik biçimde koparsa (kullanıcı kesmedi, servis durmuyor, Bluetooth kapanmadı)
 *   60 sn sonrasına bir denetim kurulur. Servis bu sürede kendisi yeniden bağlanmayı dener.
 * - Denetim AlarmManager ile yapılır: ekran kapalıyken Handler gecikmesi uykuda durur, alarm durmaz.
 *   Tam zamanlı alarm izni yoksa Android denetimi biraz geciktirebilir.
 * - Saat yeniden bağlanınca denetim ve bildirim kendiliğinden kalkar.
 * - Bluetooth kapalıysa hiçbir uyarı gösterilmez (kararın gerekçesi LostLinkRules.Decision.BLUETOOTH_OFF).
 * - Sessiz saatler ayrı saat seçimi istemez: saatin rahatsız etme penceresi kullanılır. Tek ayar kaynağı
 *   olduğu için iki pencere birbirinden kopamaz; rahatsız etme kapalıysa sessiz saat yoktur.
 * - Konum tutulmaz. Loga yalnızca saat:dakika yazılır.
 */
object LinkAlert {
    private const val CHANNEL_ID = "watch_lost"
    private const val NOTIF_ID = 3
    private const val REQ_CHECK = 400
    private const val REQ_SNOOZE = 401
    const val ACTION_CHECK = "com.che.ls05.LOST_CHECK"
    const val ACTION_SNOOZE = "com.che.ls05.LOST_SNOOZE"

    private fun checkIntent(c: Context, create: Boolean): PendingIntent? {
        val i = Intent(c, LinkAlertReceiver::class.java).setAction(ACTION_CHECK)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(c, REQ_CHECK, i, flags)
    }

    /** Beklenmeyen kopma. Ayar kapalıysa bir şey yapmaz. Zaten bekleyen bir kopma varsa ilk zaman korunur. */
    fun arm(c: Context) {
        if (!Prefs.lostAlert(c)) return
        if (Prefs.lostSince(c) != 0L && checkIntent(c, create = false) != null) return
        val now = System.currentTimeMillis()
        Prefs.setLostSince(c, now)
        val at = now + LostLinkRules.GRACE_MS
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = checkIntent(c, create = true) ?: return
        val exact = Reminders.canExact(c)
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        WatchHub.log("Bağlantı beklenmedik koptu; ${LostLinkRules.GRACE_MS / 1000} sn içinde dönmezse telefonda uyarı çıkacak.")
    }

    /** Saat bağlandı, servis durdu ya da Bluetooth kapandı: bekleyen denetim ve bildirim kalkar. */
    fun disarm(c: Context, reason: String) {
        checkIntent(c, create = false)?.let { pi ->
            c.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
        Prefs.setLostSince(c, 0L)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val shown = try { nm.activeNotifications.any { it.id == NOTIF_ID } } catch (_: Exception) { false }
        nm.cancel(NOTIF_ID)
        if (shown) WatchHub.log("\"Saat bağlantısı koptu\" uyarısı kaldırıldı ($reason).")
    }

    /** Bekleme süresi doldu: son kez denetlenir, gerekirse uyarı gösterilir. */
    fun check(c: Context) {
        val since = Prefs.lostSince(c)
        Prefs.setLostSince(c, 0L)
        if (since == 0L) return
        if (WatchHub.service == null) {
            WatchHub.log("Kopma denetimi: saat servisi çalışmıyor; uyarı verilmedi.")
            return
        }
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter
        val now = System.currentTimeMillis()
        val t = LocalTime.now()
        val dnd = DndSetting.decode(SettingsStore.entry(c, DndSetting.KEY).desired)
        val quiet = Prefs.lostQuiet(c) && LostLinkRules.quiet(dnd, t.hour, t.minute)
        val decision = LostLinkRules.decide(
            enabled = Prefs.lostAlert(c),
            ready = WatchHub.ready,
            bluetoothOn = adapter?.isEnabled == true,
            snoozedUntil = Prefs.lostSnoozeUntil(c),
            now = now,
            quiet = quiet,
        )
        when (decision) {
            LostLinkRules.Decision.ALERT -> show(c, since)
            LostLinkRules.Decision.OFF, LostLinkRules.Decision.RECONNECTED -> Unit
            LostLinkRules.Decision.BLUETOOTH_OFF -> WatchHub.log("Kopma denetimi: Bluetooth kapalı; uyarı verilmedi.")
            LostLinkRules.Decision.SNOOZED -> WatchHub.log("Kopma denetimi: uyarı susturulmuş; gösterilmedi.")
            LostLinkRules.Decision.QUIET -> WatchHub.log("Kopma denetimi: sessiz saatler; uyarı gösterilmedi.")
        }
    }

    fun snooze(c: Context) {
        Prefs.setLostSnoozeUntil(c, System.currentTimeMillis() + LostLinkRules.SNOOZE_MS)
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        WatchHub.log("\"Saat bağlantısı koptu\" uyarısı 1 saat susturuldu.")
    }

    fun createChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL_ID, "Saat uzaklaştı", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Saat bağlantısı beklenmedik koptuğunda ve geri gelmediğinde"
        nm.createNotificationChannel(ch)
    }

    private fun show(c: Context, since: Long) {
        createChannel(c)
        val hm = Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).toLocalTime()
        val time = Fmt.hm(hm.hour, hm.minute)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze = PendingIntent.getBroadcast(
            c, REQ_SNOOZE, Intent(c, LinkAlertReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Saat bağlantısı koptu")
            .setContentText("Kopma: $time. Saat yakındaysa kendiliğinden bağlanır.")
            .setWhen(since)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "1 saat sustur", snooze).build())
            .build()
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) {
            WatchHub.log("Saat bağlantısı $time itibarıyla kopuk, ama bildirim izni kapalı; uyarı gösterilemedi.")
            return
        }
        try {
            nm.notify(NOTIF_ID, n)
            WatchHub.log("Uyarı gösterildi: saat bağlantısı $time itibarıyla kopuk ve geri gelmedi.")
        } catch (e: Exception) {
            WatchHub.log("Uyarı gösterilemedi (${e.javaClass.simpleName}). Bildirim iznini kontrol et.")
        }
    }
}

/** Kopma denetimi ve "1 saat sustur" eylemi. */
class LinkAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        when (intent.action) {
            LinkAlert.ACTION_CHECK -> LinkAlert.check(c)
            LinkAlert.ACTION_SNOOZE -> LinkAlert.snooze(c)
        }
    }
}
