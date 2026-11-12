package com.che.ls05

import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.che.ls05.protocol.AlarmSetting
import com.che.ls05.protocol.DndSetting
import com.che.ls05.protocol.HealthLog
import com.che.ls05.protocol.LengthTest
import com.che.ls05.protocol.NotificationState
import com.che.ls05.protocol.SyncEntry
import com.che.ls05.protocol.SyncPhase
import com.che.ls05.protocol.UteAlarm
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.UteDate
import com.che.ls05.protocol.UteFrame
import java.time.LocalDateTime

/** Bir sekme: kaydırılabilir dikey gövde. Görünümler bir kez kurulur, refresh yalnızca günceller. */
abstract class Page(val act: MainActivity, val title: String) {
    protected val ui: Ui = act.ui
    val body: LinearLayout = ui.vertical()
    val root: ScrollView = ui.scroll(body)

    init {
        body.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24))
    }

    abstract fun refresh()
}

// =====================================================================
// 1. Bugün
// =====================================================================

class TodayPage(act: MainActivity) : Page(act, "Bugün") {

    private val banner: Banner = ui.banner("", "Ayarlar") { act.selectTab(3) }
    private val stepNum: TextView = ui.big("")
    private val stepSub: TextView = ui.caption("")
    private val stepBar: ProgressBar = ui.progress()
    private val heartNum: TextView = ui.big("")
    private val heartUnit: TextView = ui.text("bpm", 15f, R.color.text2)
    private val heartTime: TextView = ui.caption("")
    private val heartRange: TextView = ui.caption("")
    private val syncText: TextView = ui.caption("")

    init {
        ui.add(body, banner.view, topDp = 8)

        val steps = ui.card()
        ui.add(steps, ui.cardTitle("Bugün adım"))
        ui.add(steps, stepNum, topDp = 8)
        ui.add(steps, stepSub, topDp = 8)
        ui.add(steps, stepBar, topDp = 16, height = ui.dp(8))
        ui.add(body, steps, topDp = 16)

        val heart = ui.card()
        ui.add(heart, ui.cardTitle("Nabız"))
        val line = LinearLayout(act)
        line.orientation = LinearLayout.HORIZONTAL // varsayılan üst hizalama: taban çizgisi hizalanır
        ui.add(line, heartNum, width = Ui.WRAP)
        ui.add(line, heartUnit, width = Ui.WRAP, leftDp = 8)
        ui.add(heart, line, topDp = 8)
        ui.add(heart, heartTime, topDp = 8)
        ui.add(heart, heartRange, topDp = 4)
        ui.add(body, heart, topDp = 16)

        val sync = ui.card()
        val r = ui.horizontal()
        ui.add(r, syncText, width = 0, weight = 1f)
        ui.add(r, ui.secondary("Verileri yenile") { requestData() }, width = Ui.WRAP, leftDp = 16)
        ui.add(sync, r)
        ui.add(body, sync, topDp = 16)
    }

    private fun requestData() {
        val s = act.readyService() ?: return
        s.requestHistory(true)
        act.toast("Saatten veriler isteniyor.")
    }

    override fun refresh() {
        val missing = act.firstMissingSetup()
        if (missing == null) {
            banner.view.visibility = View.GONE
        } else {
            banner.text.text = "Kurulum eksik: $missing"
            banner.view.visibility = View.VISIBLE
        }

        val now = LocalDateTime.now()
        val today = UteDate(now.year, now.monthValue, now.dayOfMonth)
        val view = HealthStore.view()

        val goal = Prefs.stepGoal(act)
        val steps = view.stepsForDay(today)
        if (steps == null) {
            ui.setBig(stepNum, null, "Veri yok")
            stepSub.text = "Hedef ${Fmt.num(goal)}"
            stepBar.progress = 0
        } else {
            ui.setBig(stepNum, Fmt.num(steps), "")
            val pct = if (goal > 0) (steps.toLong() * 100L / goal).toInt() else 0
            stepSub.text = "Hedef ${Fmt.num(goal)} · %$pct"
            stepBar.progress = pct.coerceIn(0, 100)
        }

        val hp = view.lastHeart(notAfter = now.plusMinutes(5))
        if (hp == null) {
            ui.setBig(heartNum, null, "Ölçüm yok")
            heartUnit.visibility = View.GONE
            heartTime.visibility = View.GONE
        } else {
            ui.setBig(heartNum, hp.bpm.toString(), "")
            heartUnit.visibility = View.VISIBLE
            heartTime.text = "Ölçüm: ${Fmt.day(hp.at)}"
            heartTime.visibility = View.VISIBLE
        }

        val sum = view.heartSummary
        val valid = HealthLog.VALID_BPM
        if (sum != null && sum.date == today && sum.min in valid && sum.avg in valid && sum.max in valid) {
            heartRange.text = "En düşük ${sum.min} · ortalama ${sum.avg} · en yüksek ${sum.max}"
            heartRange.visibility = View.VISIBLE
        } else {
            heartRange.visibility = View.GONE
        }

        val last = Prefs.lastSync(act)
        syncText.text = if (last == 0L) "Son eşitleme: henüz yok" else "Son eşitleme: ${Fmt.dayMs(last)}"
    }
}

// =====================================================================
// 2. Saat
// =====================================================================

class WatchPage(act: MainActivity) : Page(act, "Saat") {

    private class SettingViews(
        val main: TextView,
        val sub: TextView,
        val toggle: Toggle,
        val status: TextView,
        val retry: Button,
    )

    private val alarms = ArrayList<SettingViews>()
    private val dnd: SettingViews

    init {
        ui.add(body, ui.section("Alarmlar"))
        for (slot in 1..UteAlarm.SLOTS) {
            val key = AlarmSetting.key(slot)
            alarms += settingCard(
                sizeSp = 30f,
                onEdit = { editAlarm(slot) },
                onToggle = { on -> toggleAlarm(slot, on) },
                onRetry = { SettingsStore.retry(act, key) },
            )
        }

        ui.add(body, ui.section("Rahatsız etme"))
        dnd = settingCard(
            sizeSp = 26f,
            onEdit = { editDnd() },
            onToggle = { on -> toggleDnd(on) },
            onRetry = { SettingsStore.retry(act, DndSetting.KEY) },
        )
        dnd.sub.visibility = View.GONE

        ui.add(body, ui.section("Saati bul"))
        val find = ui.card()
        ui.add(find, ui.caption("Saat kısa bir süre titrer."))
        ui.add(find, ui.secondary("Saati titret") {
            act.readyService()?.command(UteCommand.VIBRATE_TEXT, "saati bul (06)")
        }, topDp = 16, width = Ui.WRAP)
        ui.add(body, find, topDp = 8)
    }

    private fun settingCard(
        sizeSp: Float,
        onEdit: () -> Unit,
        onToggle: (Boolean) -> Unit,
        onRetry: () -> Unit,
    ): SettingViews {
        val card = ui.card(onEdit)
        val top = ui.horizontal()
        val left = ui.vertical()
        val main = ui.big("", sizeSp)
        val sub = ui.caption("")
        ui.add(left, main)
        ui.add(left, sub, topDp = 4)
        ui.add(top, left, width = 0, weight = 1f)
        val tg = ui.toggle(false, onToggle)
        ui.add(top, tg.sw, width = Ui.WRAP, leftDp = 16)
        ui.add(card, top)
        val status = ui.text("", 13f, R.color.text2)
        ui.add(card, status, topDp = 8)
        val retry = ui.secondary("Yeniden gönder", onRetry)
        retry.visibility = View.GONE
        ui.add(card, retry, topDp = 8, width = Ui.WRAP)
        ui.add(body, card, topDp = 8)
        return SettingViews(main, sub, tg, status, retry)
    }

    private fun alarm(slot: Int): AlarmSetting =
        AlarmSetting.decode(SettingsStore.entry(act, AlarmSetting.key(slot)).desired) ?: AlarmSetting.DEFAULT

    private fun dndSetting(): DndSetting =
        DndSetting.decode(SettingsStore.entry(act, DndSetting.KEY).desired) ?: DndSetting.DEFAULT

    private fun toggleAlarm(slot: Int, on: Boolean) {
        SettingsStore.set(act, AlarmSetting.key(slot), alarm(slot).copy(enabled = on).encode())
    }

    private fun toggleDnd(on: Boolean) {
        SettingsStore.set(act, DndSetting.KEY, dndSetting().copy(enabled = on).encode())
    }

    override fun refresh() {
        for (slot in 1..UteAlarm.SLOTS) {
            val e = SettingsStore.entry(act, AlarmSetting.key(slot))
            val a = AlarmSetting.decode(e.desired) ?: AlarmSetting.DEFAULT
            val v = alarms[slot - 1]
            v.main.text = Fmt.hm(a.hour, a.minute)
            v.main.setTextColor(ui.color(if (a.enabled) R.color.text else R.color.text2))
            v.sub.text = UteAlarm.maskLabel(a.mask)
            v.toggle.set(a.enabled)
            showStatus(v, e)
        }
        val e = SettingsStore.entry(act, DndSetting.KEY)
        val d = DndSetting.decode(e.desired) ?: DndSetting.DEFAULT
        dnd.main.text = "${Fmt.hm(d.startHour, d.startMinute)} → ${Fmt.hm(d.endHour, d.endMinute)}"
        dnd.main.setTextColor(ui.color(if (d.enabled) R.color.text else R.color.text2))
        dnd.toggle.set(d.enabled)
        showStatus(dnd, e)
    }

    private fun showStatus(v: SettingViews, e: SyncEntry) {
        val (text, colorId) = statusText(e)
        v.status.text = text
        v.status.setTextColor(ui.color(colorId))
        val phase = e.phase
        v.retry.visibility =
            if (phase == SyncPhase.NO_ACK || phase == SyncPhase.UNKNOWN) View.VISIBLE else View.GONE
    }

    private fun statusText(e: SyncEntry): Pair<String, Int> = when (e.phase) {
        SyncPhase.SENDING -> "Gönderiliyor…" to R.color.text2
        SyncPhase.CONFIRMED ->
            (if (e.confirmedAt == 0L) "Saat onayladı" else "Saat onayladı · ${Fmt.dayMs(e.confirmedAt)}") to R.color.accent
        SyncPhase.PENDING ->
            if (WatchHub.ready) "Gönderiliyor…" to R.color.text2 else "Bağlanınca gönderilecek" to R.color.warn
        SyncPhase.NO_ACK -> "Onay gelmedi. Saatteki durum bilinmiyor." to R.color.warn
        SyncPhase.UNKNOWN -> "Saat sıfırlanmış olabilir. Saatteki durum bilinmiyor." to R.color.warn
        SyncPhase.IDLE -> "Henüz saate gönderilmedi" to R.color.text2
    }

    private fun editAlarm(slot: Int) {
        val key = AlarmSetting.key(slot)
        val cur = alarm(slot)
        var hour = cur.hour
        var minute = cur.minute

        val box = ui.dialogBox()
        ui.add(box, ui.cardTitle("Saat"), topDp = 8)
        val timeBtn = ui.secondary(Fmt.hm(hour, minute)) {}
        timeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        timeBtn.setOnClickListener {
            act.pickTime(hour, minute) { h, m ->
                hour = h
                minute = m
                timeBtn.text = Fmt.hm(h, m)
            }
        }
        ui.add(box, timeBtn, topDp = 8)

        ui.add(box, ui.cardTitle("Günler"), topDp = 16)
        val names = arrayOf("Pt", "Sa", "Ça", "Pe", "Cu", "Ct", "Pz")
        val row1 = ui.horizontal()
        val row2 = ui.horizontal()
        val boxes = ArrayList<CheckBox>()
        for (i in UteAlarm.UI_ORDER.indices) {
            val bit = UteAlarm.UI_ORDER[i]
            val cb = ui.check(names[i], (cur.mask and bit) != 0)
            boxes += cb
            ui.add(if (i < 4) row1 else row2, cb, width = 0, weight = 1f)
        }
        ui.add(row2, View(act), width = 0, weight = 1f) // sütunlar hizalı kalsın
        ui.add(box, row1, topDp = 8)
        ui.add(box, row2)

        val enabledBox = ui.check("Açık", cur.enabled)
        ui.add(box, enabledBox, topDp = 16)

        val dialog = AlertDialog.Builder(act)
            .setTitle("Alarm $slot")
            .setView(ui.scroll(box))
            .setPositiveButton("Kaydet", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var mask = 0
                for (i in boxes.indices) {
                    if (boxes[i].isChecked) mask = mask or UteAlarm.UI_ORDER[i]
                }
                val on = enabledBox.isChecked
                if (on && mask == 0) {
                    act.toast("Açık alarm için en az bir gün seç.")
                } else {
                    // Kapalı alarmda gün seçilmediyse önceki günler korunur (boş maske geçersiz).
                    val finalMask = if (mask == 0) cur.mask else mask
                    SettingsStore.set(act, key, AlarmSetting(on, hour, minute, finalMask).encode())
                    dialog.dismiss()
                }
            }
        }
        act.track(dialog)
        dialog.show()
    }

    private fun editDnd() {
        val cur = dndSetting()
        var sh = cur.startHour
        var sm = cur.startMinute
        var eh = cur.endHour
        var em = cur.endMinute

        val box = ui.dialogBox()
        val startBtn = ui.secondary(Fmt.hm(sh, sm)) {}
        startBtn.setOnClickListener {
            act.pickTime(sh, sm) { h, m ->
                sh = h
                sm = m
                startBtn.text = Fmt.hm(h, m)
            }
        }
        val endBtn = ui.secondary(Fmt.hm(eh, em)) {}
        endBtn.setOnClickListener {
            act.pickTime(eh, em) { h, m ->
                eh = h
                em = m
                endBtn.text = Fmt.hm(h, m)
            }
        }
        ui.add(box, ui.row("Başlangıç", trailing = startBtn), topDp = 8)
        ui.add(box, ui.row("Bitiş", trailing = endBtn))
        val enabledBox = ui.check("Açık", cur.enabled)
        ui.add(box, enabledBox, topDp = 8)

        val dialog = AlertDialog.Builder(act)
            .setTitle("Rahatsız etme")
            .setView(ui.scroll(box))
            .setPositiveButton("Kaydet") { _, _ ->
                SettingsStore.set(act, DndSetting.KEY, DndSetting(enabledBox.isChecked, sh, sm, eh, em).encode())
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(dialog)
        dialog.show()
    }
}

// =====================================================================
// 3. Bildirimler
// =====================================================================

class NotifyPage(act: MainActivity) : Page(act, "Bildirimler") {

    private val accessBanner: Banner = ui.banner(
        "Bildirim erişimi kapalı. Bildirimler saate iletilemez.",
        "Aç",
    ) { act.openSettings(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    private val forward: SwitchRow = ui.switchRow("Bildirimleri saate ilet", null, Prefs.forwardingEnabled(act)) {
        Prefs.setForwardingEnabled(act, it)
    }
    private val vibrate: SwitchRow = ui.switchRow("Bildirimden sonra titret", null, Prefs.vibrate(act)) {
        Prefs.setVibrate(act, it)
    }

    private val testButton: Button = ui.primary("Test bildirimi gönder") { sendTest("LS05TEST123", "LS05TEST123") }
    private val testState: TextView = ui.text("", 14f, R.color.text)
    private val confirmRow: LinearLayout = ui.horizontal()

    private val search = ui.field("Uygulama ara")
    private val appBox: LinearLayout = ui.card()
    private val labels = HashMap<String, String>()

    private var notifState = NotificationState.IDLE

    init {
        ui.add(body, accessBanner.view, topDp = 8)

        val main = ui.card()
        ui.add(main, forward.view)
        ui.add(main, ui.divider(), height = ui.dp(1))
        ui.add(main, vibrate.view)
        ui.add(body, main, topDp = 16)

        ui.add(body, ui.section("Test"))
        val test = ui.card()
        ui.add(test, ui.caption("Saate kısa bir deneme bildirimi gider. Sonra saatte görüp görmediğini işaretle."))
        ui.add(test, testButton, topDp = 16)
        testState.visibility = View.GONE
        ui.add(test, testState, topDp = 16)
        ui.add(confirmRow, ui.primary("Saatte gördüm") { setNotifState(NotificationState.DISPLAYED_CONFIRMED) },
            width = 0, weight = 1f)
        ui.add(confirmRow, ui.secondary("Görmedim") { setNotifState(NotificationState.NOT_DISPLAYED) },
            width = 0, weight = 1f, leftDp = 8)
        confirmRow.visibility = View.GONE
        ui.add(test, confirmRow, topDp = 8)
        ui.add(body, test, topDp = 8)

        ui.add(body, ui.section("Uygulamalar"))
        ui.add(body, search, topDp = 8)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshApps()
            }
        })
        ui.add(body, appBox, topDp = 8)
    }

    override fun refresh() {
        accessBanner.view.visibility = if (act.hasNotificationAccess()) View.GONE else View.VISIBLE
        forward.toggle.set(Prefs.forwardingEnabled(act))
        vibrate.toggle.set(Prefs.vibrate(act))
        onReady(WatchHub.ready)
        refreshApps()
    }

    fun onReady(ready: Boolean) {
        ui.enable(testButton, ready)
    }

    /** Yalnızca uygulama kartını yeniden kurar; arama kutusunun odağı ve kaydırma konumu korunur. */
    private fun refreshApps() {
        appBox.removeAllViews()
        val pm = act.packageManager
        val q = search.text.toString().trim().lowercase()
        val apps = Prefs.seenApps(act).map { pkg ->
            val label = labels.getOrPut(pkg) {
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
            }
            Triple(pkg, label, Prefs.lastNotified(act, pkg))
        }
        if (apps.isEmpty()) {
            ui.add(appBox, ui.caption("Henüz bildirim görülmedi. Bildirim erişimini açtıktan sonra gelen bildirimlerle dolar."))
            return
        }
        val shown = apps
            .filter { q.isEmpty() || it.second.lowercase().contains(q) || it.first.lowercase().contains(q) }
            .sortedWith(compareByDescending<Triple<String, String, Long>> { it.third }.thenBy { it.second.lowercase() })
        if (shown.isEmpty()) {
            ui.add(appBox, ui.caption("Aramayla eşleşen uygulama yok."))
            return
        }
        for ((pkg, label, last) in shown) {
            val sub = if (last > 0L) "Son: ${Fmt.ago(last)}" else null
            val r = ui.switchRow(label, sub, Prefs.appEnabled(act, pkg)) { Prefs.setAppEnabled(act, pkg, it) }
            ui.add(appBox, r.view)
        }
    }

    // ---------- Test bildirimi ----------

    /** Gönderildiyse true. Sonuç bu sekmede işaretlenir. */
    fun sendTest(title: String, message: String): Boolean {
        val s = WatchHub.service
        if (s == null || !s.isWatchReady()) {
            WatchHub.log("Saat hazır değil; önce bağlan.")
            act.toast("Saat bağlı değil.")
            return false
        }
        if (s.ble.sendNotification(title, message, Prefs.vibrate(act)) == 0) return false
        setNotifState(NotificationState.SENDING)
        act.handler.postDelayed({
            if (notifState == NotificationState.SENDING) setNotifState(NotificationState.TRANSFER_TIMEOUT)
        }, 6_000L)
        return true
    }

    private fun setNotifState(st: NotificationState) {
        notifState = st
        val (logText, shownText) = when (st) {
            NotificationState.IDLE -> return
            NotificationState.SENDING ->
                "Test bildirimi gönderiliyor…" to "Gönderiliyor…"
            NotificationState.TRANSFER_DONE ->
                "Aktarım tamam (saat onayladı). Ekranda gördün mü?" to "Aktarım tamam. Saatte gördün mü?"
            NotificationState.TRANSFER_TIMEOUT ->
                "Aktarım onayı 6 sn içinde gelmedi. Ekranda gördün mü?" to
                    "Aktarım onayı 6 sn içinde gelmedi. Saatte gördün mü?"
            NotificationState.DISPLAYED_CONFIRMED ->
                "SONUÇ: Test bildirimi saatte göründü (kullanıcı onayı)." to "Test bildirimi saatte göründü."
            NotificationState.NOT_DISPLAYED ->
                "SONUÇ: Test bildirimi saatte görünmedi (kullanıcı onayı)." to "Test bildirimi saatte görünmedi."
        }
        WatchHub.log(logText)
        testState.text = shownText
        testState.visibility = View.VISIBLE
        confirmRow.visibility =
            if (st == NotificationState.TRANSFER_DONE || st == NotificationState.TRANSFER_TIMEOUT) View.VISIBLE else View.GONE
    }

    fun onFrame(frame: UteFrame) {
        if (frame is UteFrame.NotificationTransferDone && notifState == NotificationState.SENDING) {
            setNotifState(NotificationState.TRANSFER_DONE)
        }
    }
}

// =====================================================================
// 4. Ayarlar
// =====================================================================

class SettingsPage(act: MainActivity) : Page(act, "Ayarlar") {

    private val logShown = 300
    private var logCount = 0

    private val setupBox: LinearLayout = ui.card()
    private val idValue: TextView = ui.text("", 15f, R.color.text2)
    private val deviceBox: LinearLayout = ui.vertical()
    private val autoTime: SwitchRow = ui.switchRow(
        "Bağlanınca saati eşitle",
        "Önce Gelişmiş bölümündeki \"Saati eşitle\" ile elle dene.",
        Prefs.autoTime(act),
    ) { Prefs.setAutoTime(act, it) }
    private val firmwareValue: TextView = ui.text("", 15f, R.color.text2)
    private val actionButtons = ArrayList<Button>()
    private val logView: TextView = ui.mono()

    init {
        // Kurulum
        ui.add(body, ui.section("Kurulum"))
        ui.add(body, setupBox, topDp = 8)

        // Saat
        ui.add(body, ui.section("Saat"))
        val w = ui.card()
        val idTrail = ui.horizontal()
        ui.add(idTrail, idValue, width = Ui.WRAP)
        ui.add(idTrail, ui.secondary("Değiştir") { editId() }, width = Ui.WRAP, leftDp = 16)
        ui.add(w, ui.row("Kimlik", trailing = idTrail))
        val btns = ui.horizontal()
        ui.add(btns, ui.secondary("Tara") { scan() }, width = 0, weight = 1f)
        ui.add(btns, ui.primary("Bağlan") { connectSaved() }, width = 0, weight = 1f, leftDp = 8)
        ui.add(btns, ui.secondary("Durdur") { WatchService.start(act, WatchService.ACTION_STOP) },
            width = 0, weight = 1f, leftDp = 8)
        ui.add(w, btns, topDp = 8)
        ui.add(w, deviceBox, topDp = 8)
        ui.add(w, autoTime.view, topDp = 8)
        ui.add(w, ui.row("Firmware", trailing = firmwareValue))
        ui.add(body, w, topDp = 8)

        // Gelişmiş
        ui.add(body, ui.section("Gelişmiş"))
        val adv = ui.card()
        actionButtons += ui.secondary("Firmware") {
            withWatch { it.command(UteCommand.firmwareRequest(), "firmware sorgu") }
        }
        actionButtons += ui.secondary("Pil") {
            withWatch { it.command(UteCommand.batteryRequest(), "pil sorgu") }
        }
        actionButtons += ui.secondary("Saati eşitle") {
            withWatch { it.sendTime() }
        }
        actionButtons += ui.secondary("Türkçe test") {
            if (act.notify.sendTest("Test", "şğıöüç ŞĞİÖÜÇ")) act.toast("Sonucu Bildirimler sekmesinde işaretle.")
        }
        actionButtons += ui.secondary("Titreşim") {
            withWatch { it.command(UteCommand.VIBRATE_TEXT, "mesaj titreşimi (06), tek başına") }
        }
        actionButtons += ui.secondary("Uzunluk testi") {
            withWatch {
                if (it.ble.sendNotificationText(LengthTest.ruler(), Prefs.vibrate(act)) > 0) {
                    WatchHub.log("Uzunluk testi gönderildi. Saatte görünen son sayıyı not et.")
                }
            }
        }
        actionButtons += ui.secondary("Uzunluk testi (Türkçe)") {
            withWatch {
                if (it.ble.sendNotificationText(LengthTest.turkishRuler(), Prefs.vibrate(act)) > 0) {
                    WatchHub.log("Uzunluk testi (Türkçe) gönderildi. Saatte görünen son sayıyı not et.")
                }
            }
        }
        actionButtons += ui.secondary("Geçmişi oku") {
            withWatch { it.requestHistory(true) }
        }
        var i = 0
        while (i < actionButtons.size) {
            val r = ui.horizontal()
            ui.add(r, actionButtons[i], width = 0, weight = 1f)
            if (i + 1 < actionButtons.size) {
                ui.add(r, actionButtons[i + 1], width = 0, weight = 1f, leftDp = 8)
            } else {
                ui.add(r, View(act), width = 0, weight = 1f, leftDp = 8)
            }
            ui.add(adv, r, topDp = if (i == 0) 0 else 8)
            i += 2
        }
        ui.add(body, adv, topDp = 8)

        // Log
        ui.add(body, ui.section("Log"))
        val logCard = ui.card()
        val lr = ui.horizontal()
        ui.add(lr, ui.secondary("Logu kopyala") { copyLog() }, width = 0, weight = 1f)
        ui.add(lr, ui.secondary("Logu temizle") { clearLog() }, width = 0, weight = 1f, leftDp = 8)
        ui.add(logCard, lr)
        ui.add(logCard, logView, topDp = 16)
        ui.add(body, logCard, topDp = 8)

        val footer = ui.caption("LS05 Controller 0.4.0")
        footer.setGravity(Gravity.CENTER)
        ui.add(body, footer, topDp = 24)

        refreshLog()
    }

    override fun refresh() {
        refreshSetup()
        refreshWatchInfo()
        onReady(WatchHub.ready)
    }

    private fun refreshSetup() {
        setupBox.removeAllViews()
        for (item in act.setupItems()) {
            val (label, tone) = when (item.ok) {
                true -> "Tamam" to Tone.OK
                false -> "Eksik" to Tone.WARN
                null -> "Kontrol et" to Tone.WARN
            }
            ui.add(setupBox, ui.row(item.label, trailing = ui.pill(label, tone), onClick = item.fix))
        }
    }

    fun refreshWatchInfo() {
        idValue.text = if (hasId()) "Kayıtlı" else "Girilmedi"
        autoTime.toggle.set(Prefs.autoTime(act))
        firmwareValue.text = WatchHub.firmware ?: "bilinmiyor"
    }

    fun onReady(ready: Boolean) {
        actionButtons.forEach { ui.enable(it, ready) }
    }

    private fun withWatch(block: (WatchService) -> Unit) {
        val s = act.readyService() ?: return
        block(s)
    }

    // ---------- Kimlik ----------

    private fun hasId(): Boolean = UteCommand.parseUserId(Prefs.userIdText(act)) != null

    /** Kimlik ekranda ve logda gösterilmez; kutu boş açılır. */
    private fun editId() {
        val box = ui.dialogBox()
        ui.add(box, ui.caption("8 haneli onaltılık kimlik. Yalnızca telefonda saklanır, ekranda ve logda gösterilmez."), topDp = 8)
        val input = ui.field("Kimlik (8 hane)")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        ui.add(box, input, topDp = 16)
        val dialog = AlertDialog.Builder(act)
            .setTitle("Kimlik")
            .setView(box)
            .setPositiveButton("Kaydet", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (UteCommand.parseUserId(text) == null) {
                    WatchHub.log("Kimlik geçersiz veya boş: 8 haneli onaltılık değer gerekli.")
                    act.toast("Kimlik geçersiz: 8 haneli onaltılık değer gerekli.")
                } else {
                    Prefs.setUserIdText(act, text)
                    WatchHub.log("Kimlik kaydedildi.")
                    refreshWatchInfo()
                    dialog.dismiss()
                }
            }
        }
        act.track(dialog)
        dialog.show()
    }

    // ---------- Tarama ve bağlantı ----------

    private fun scan() {
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        deviceBox.removeAllViews()
        if (WatchHub.service == null) WatchService.start(act)
        act.toast("Saat aranıyor.")
        act.handler.postDelayed({
            val s = WatchHub.service
            if (s == null) WatchHub.log("Servis başlamadı; Bluetooth iznini kontrol et.") else s.ble.startScan()
        }, 600L)
    }

    private fun connectSaved() {
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        if (!hasId()) {
            WatchHub.log("Kimlik geçersiz veya boş: 8 haneli onaltılık değer gerekli.")
            act.toast("Önce kimliği gir.")
            editId()
            return
        }
        val address = Prefs.address(act)
        if (address == null) {
            WatchHub.log("Kayıtlı saat yok; önce Tara ile saati seç.")
            act.toast("Kayıtlı saat yok. Önce Tara ile saati seç.")
            return
        }
        WatchService.start(act, WatchService.ACTION_CONNECT_NEW, address)
    }

    fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) {
        ui.add(deviceBox, ui.row(name, "Bu saati kullan · $rssi dBm", onClick = { useDevice(device) }))
    }

    private fun useDevice(device: BluetoothDevice) {
        if (!hasId()) {
            act.toast("Önce kimliği gir.")
            editId()
            return
        }
        deviceBox.removeAllViews()
        WatchService.start(act, WatchService.ACTION_CONNECT_NEW, device.address)
    }

    // ---------- Log ----------

    fun onLog(line: String) {
        logView.append(line + "\n")
        logCount++
        if (logCount > logShown * 2) refreshLog()
    }

    private fun refreshLog() {
        val lines = WatchHub.logLines.toList().takeLast(logShown)
        logCount = lines.size
        logView.text = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
    }

    private fun clearLog() {
        WatchHub.logLines.clear()
        logView.text = ""
        logCount = 0
    }

    private fun copyLog() {
        val cm = act.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("LS05 log", WatchHub.logLines.joinToString("\n")))
        act.toast("Log kopyalandı (bildirim içerikleri gizli, MAC maskeli).")
    }
}
