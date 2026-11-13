package com.che.ls05

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.che.ls05.protocol.AppTypeMap
import com.che.ls05.protocol.CallClassifier
import com.che.ls05.protocol.CallKind
import com.che.ls05.protocol.CallTracker
import com.che.ls05.protocol.MSG_TYPE_CALL
import com.che.ls05.protocol.MSG_TYPE_INCOMING_CALL
import com.che.ls05.protocol.TextSanitizer

/**
 * Telefon bildirimlerini saate iletir.
 * Atlanır: kendi bildirimimiz, kalıcı bildirimler (müzik, navigasyon), grup özetleri, ön plan servisleri,
 * kullanıcının kapattığı uygulamalar ve 60 sn içindeki aynı içerik.
 * İstisna: arama bildirimleri (kalıcı olsalar da) ayrı yoldan gider: gelen arama saatte arama ekranı açar,
 * cevaplanınca ya da bitince kapatılır; cevapsız arama tip 3 bildirim olarak gider. Ek izin gerekmez.
 * Bildirim içeriği ve arayan adı loga yazılmaz; yalnızca uygulama adı ve karakter sayısı.
 */
class NotifyForwarder : NotificationListenerService() {

    private val recent = LinkedHashMap<String, Long>()

    override fun onListenerConnected() {
        WatchHub.log("Bildirim erişimi etkin; iletim hazır.")
        // Süreç yeniden başladıysa (ör. uygulama son uygulamalardan kaydırıldı) servis ilk bildirimi beklemeden kalksın.
        if (WatchHub.service == null && Prefs.serviceEnabled(this) && Prefs.address(this) != null) {
            WatchHub.log("Saat servisi çalışmıyor; bildirim dinleyici başlatıyor.")
            WatchService.start(this)
        }
    }

    override fun onListenerDisconnected() {
        WatchHub.log("Bildirim erişimi koptu; sistemden yeniden bağlama isteniyor.")
        try {
            NotificationListenerService.requestRebind(ComponentName(this, NotifyForwarder::class.java))
        } catch (e: Exception) {
            WatchHub.log("Yeniden bağlama istenemedi (${e.javaClass.simpleName}).")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0

        val callType = if (Build.VERSION.SDK_INT >= 31) n.extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) ?: 0 else 0
        val kind = CallClassifier.classify(pkg, n.category, ongoing, callType, n.fullScreenIntent != null)
        if (kind != CallKind.NONE) {
            handleCall(sbn, n, kind)
            return
        }
        if (ongoing) return

        Prefs.addSeenApp(this, pkg)
        Prefs.setLastNotified(this, pkg, System.currentTimeMillis())
        if (!Prefs.forwardingEnabled(this) || !Prefs.appEnabled(this, pkg)) return

        val (cleanTitle, cleanText) = texts(n)
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) return

        val now = System.currentTimeMillis()
        if (isRepeat("$pkg|${cleanTitle.hashCode()}|${cleanText.hashCode()}", now)) return

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

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        if (WatchHub.calls.onRemoved(key) == CallTracker.Decision.STOP) stopCallOnWatch(sbn.packageName ?: "")
    }

    // ---------- Aramalar ----------

    private fun handleCall(sbn: StatusBarNotification, n: Notification, kind: CallKind) {
        val pkg = sbn.packageName ?: return
        val now = System.currentTimeMillis()
        if (WatchHub.calls.expire(now) == CallTracker.Decision.STOP) stopCallOnWatch(pkg)
        val forwarding = Prefs.forwardingEnabled(this)

        when (kind) {
            CallKind.INCOMING, CallKind.ONGOING -> when (WatchHub.calls.onPosted(sbn.key, kind, now)) {
                CallTracker.Decision.ALERT -> {
                    if (!forwarding || !Prefs.incomingCalls(this)) {
                        WatchHub.log("Gelen arama algılandı; saate iletim kapalı.")
                        return
                    }
                    val (title, text) = texts(n)
                    // Arama uygulamaları arayanı genelde başlıkta verir; yoksa metin.
                    val name = title.ifEmpty { text }
                    WatchHub.log("Gelen arama algılandı (${appLabel(pkg)}).")
                    WatchHub.forward(
                        this,
                        WatchHub.Note(pkg, appLabel(pkg), "", name, MSG_TYPE_INCOMING_CALL, now, WatchHub.NoteKind.CALL_ALERT),
                    )
                }
                CallTracker.Decision.STOP -> stopCallOnWatch(pkg)
                CallTracker.Decision.NONE -> Unit
            }
            CallKind.MISSED -> {
                if (!forwarding || !Prefs.missedCalls(this)) return
                val (title, text) = texts(n)
                val body = listOf(title, text).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { "Cevapsız arama" }
                if (isRepeat("missed|$pkg|${body.hashCode()}", now)) return
                WatchHub.forward(
                    this,
                    WatchHub.Note(pkg, appLabel(pkg), "", body, MSG_TYPE_CALL, now, WatchHub.NoteKind.MISSED_CALL),
                )
            }
            CallKind.NONE -> Unit
        }
    }

    private fun stopCallOnWatch(pkg: String) {
        WatchHub.forward(
            this,
            WatchHub.Note(pkg, "", "", "", MSG_TYPE_INCOMING_CALL, System.currentTimeMillis(), WatchHub.NoteKind.CALL_STOP),
        )
    }

    // ---------- Yardımcılar ----------

    /** Temizlenmiş başlık ve metin. */
    private fun texts(n: Notification): Pair<String, String> {
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: n.tickerText
            )?.toString().orEmpty()
        return TextSanitizer.clean(title) to TextSanitizer.clean(text)
    }

    /** 60 sn içinde aynı içerik ikinci kez gelirse true. */
    private fun isRepeat(key: String, now: Long): Boolean {
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) if (now - iterator.next().value > 60_000) iterator.remove()
        if (recent.containsKey(key)) return true
        recent[key] = now
        return false
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
