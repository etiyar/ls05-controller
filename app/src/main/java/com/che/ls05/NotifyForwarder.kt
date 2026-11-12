package com.che.ls05

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.che.ls05.protocol.AppTypeMap
import com.che.ls05.protocol.TextSanitizer

/**
 * Telefon bildirimlerini saate iletir.
 * Atlanır: kendi bildirimimiz, kalıcı bildirimler (müzik, navigasyon), grup özetleri, ön plan servisleri,
 * kullanıcının kapattığı uygulamalar ve 60 sn içindeki aynı içerik.
 * Bildirim içeriği loga yazılmaz; yalnızca uygulama adı ve karakter sayısı.
 */
class NotifyForwarder : NotificationListenerService() {

    private val recent = LinkedHashMap<String, Long>()

    override fun onListenerConnected() {
        WatchHub.log("Bildirim erişimi etkin; iletim hazır.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        val skipFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and skipFlags != 0) return

        Prefs.addSeenApp(this, pkg)
        if (!Prefs.forwardingEnabled(this) || !Prefs.appEnabled(this, pkg)) return

        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: n.tickerText
            )?.toString().orEmpty()

        val cleanTitle = TextSanitizer.clean(title)
        val cleanText = TextSanitizer.clean(text)
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) return

        val now = System.currentTimeMillis()
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) if (now - iterator.next().value > 60_000) iterator.remove()
        val key = "$pkg|${cleanTitle.hashCode()}|${cleanText.hashCode()}"
        if (recent.containsKey(key)) return
        recent[key] = now

        val label = appLabel(pkg)
        WatchHub.forward(
            this,
            WatchHub.Note(
                pkg = pkg,
                appLabel = label,
                title = cleanTitle.ifEmpty { label },
                body = cleanText,
                type = AppTypeMap.forPackage(pkg),
                at = now,
            ),
        )
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
