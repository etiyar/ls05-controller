package com.che.ls05

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.che.ls05.protocol.UteAlarm
import com.che.ls05.protocol.UteDate
import com.che.ls05.protocol.UteDnd
import com.che.ls05.protocol.UteFrame
import com.che.ls05.protocol.UteHealth
import java.util.Calendar

/**
 * v0.2 M3 (alarm, rahatsız etme) ve v0.3a başlangıcı (adım, nabız).
 * Ayar, saat onay verince kaydedilir; onay gelmezse ekrandaki değer değişmez.
 */
class FeaturePanel(private val a: Activity) {

    val sendButtons = mutableListOf<Button>()
    private val alarmLabels = arrayOfNulls<TextView>(UteAlarm.SLOTS + 1)
    private val pendingAlarms = HashMap<Int, Prefs.Alarm>()
    private var pendingDnd: Prefs.Dnd? = null
    private lateinit var dndLabel: TextView
    private lateinit var healthView: TextView
    private val main = Handler(Looper.getMainLooper())
    private val pad get() = (16 * a.resources.displayMetrics.density).toInt()

    fun build(root: LinearLayout, header: (String) -> View) {
        root.addView(header("Alarmlar"))
        root.addView(note("Saatteki alarmlar telefondan okunamıyor. Bir yuvayı buradan gönderince saatteki o yuva bu ayarla değişir."))
        for (slot in 1..UteAlarm.SLOTS) {
            val label = TextView(a)
            alarmLabels[slot] = label
            val edit = btn("Düzenle") { editAlarm(slot) }
            sendButtons += edit
            root.addView(line(label, edit))
        }
        refreshAlarms()

        root.addView(header("Rahatsız etme"))
        dndLabel = TextView(a)
        val dndBtn = btn("Düzenle") { editDnd() }
        sendButtons += dndBtn
        root.addView(line(dndLabel, dndBtn))
        refreshDnd()

        root.addView(header("Sağlık (deneysel)"))
        healthView = TextView(a)
        root.addView(healthView)
        val read = btn("Adım, nabız ve uyku geçmişini oku") { readHealth() }
        sendButtons += read
        root.addView(read)
        refreshHealth()
    }

    fun onFrame(f: UteFrame) {
        when (f) {
            is UteFrame.AlarmAck -> pendingAlarms.remove(f.slot)?.let {
                Prefs.setAlarm(a, f.slot, it)
                refreshAlarms()
                toast("Alarm ${f.slot} saate yazıldı.")
            }
            is UteFrame.DndAck -> pendingDnd?.let {
                Prefs.setDnd(a, it)
                pendingDnd = null
                refreshDnd()
                toast("Rahatsız etme ayarı saate yazıldı.")
            }
            is UteFrame.Steps, is UteFrame.HeartLive, is UteFrame.HeartSummary,
            is UteFrame.StepHistoryEnd, is UteFrame.HeartHistoryEnd -> refreshHealth()
            else -> Unit
        }
    }

    // ---------- Alarm ----------

    private fun refreshAlarms() {
        for (slot in 1..UteAlarm.SLOTS) {
            val al = Prefs.alarm(a, slot)
            alarmLabels[slot]?.text = "Alarm $slot: ${hm(al.hour, al.minute)} · ${UteAlarm.maskLabel(al.mask)} · ${if (al.enabled) "açık" else "kapalı"}"
        }
    }

    private fun editAlarm(slot: Int) {
        val cur = Prefs.alarm(a, slot)
        var hour = cur.hour
        var minute = cur.minute
        val box = vertical()
        val timeBtn = Button(a).apply { text = hm(hour, minute) }
        timeBtn.setOnClickListener {
            TimePickerDialog(a, { _, h, m -> hour = h; minute = m; timeBtn.text = hm(h, m) }, hour, minute, true).show()
        }
        box.addView(timeBtn)
        val names = arrayOf("Pt", "Sa", "Ça", "Pe", "Cu", "Ct", "Pz")
        val days = UteAlarm.UI_ORDER.mapIndexed { i, bit ->
            CheckBox(a).apply { text = names[i]; isChecked = cur.mask and bit != 0 }
        }
        box.addView(rowOf(days.take(4)))
        box.addView(rowOf(days.drop(4)))
        val enabled = CheckBox(a).apply { text = "Açık"; isChecked = cur.enabled }
        box.addView(enabled)

        AlertDialog.Builder(a).setTitle("Alarm $slot").setView(box)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Saate gönder") { _, _ ->
                val mask = UteAlarm.UI_ORDER.indices.filter { days[it].isChecked }
                    .fold(0) { acc, i -> acc or UteAlarm.UI_ORDER[i] }
                if (enabled.isChecked && mask == 0) {
                    toast("Açık alarm için en az bir gün seç.")
                    return@setPositiveButton
                }
                val al = Prefs.Alarm(enabled.isChecked, hour, minute, if (mask == 0) cur.mask else mask)
                val svc = readyService() ?: return@setPositiveButton
                pendingAlarms[slot] = al
                svc.command(UteAlarm.build(slot, al.hour, al.minute, al.mask, al.enabled), "alarm $slot (06)")
            }
            .show()
    }

    // ---------- Rahatsız etme ----------

    private fun refreshDnd() {
        val d = Prefs.dnd(a)
        dndLabel.text = "${hm(d.startHour, d.startMinute)} → ${hm(d.endHour, d.endMinute)} · ${if (d.enabled) "açık" else "kapalı"}"
    }

    private fun editDnd() {
        val cur = Prefs.dnd(a)
        var sh = cur.startHour; var sm = cur.startMinute
        var eh = cur.endHour; var em = cur.endMinute
        val box = vertical()
        val startBtn = Button(a).apply { text = "Başlangıç ${hm(sh, sm)}" }
        startBtn.setOnClickListener {
            TimePickerDialog(a, { _, h, m -> sh = h; sm = m; startBtn.text = "Başlangıç ${hm(h, m)}" }, sh, sm, true).show()
        }
        val endBtn = Button(a).apply { text = "Bitiş ${hm(eh, em)}" }
        endBtn.setOnClickListener {
            TimePickerDialog(a, { _, h, m -> eh = h; em = m; endBtn.text = "Bitiş ${hm(h, m)}" }, eh, em, true).show()
        }
        val enabled = CheckBox(a).apply { text = "Açık"; isChecked = cur.enabled }
        box.addView(startBtn); box.addView(endBtn); box.addView(enabled)

        AlertDialog.Builder(a).setTitle("Rahatsız etme").setView(box)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Saate gönder") { _, _ ->
                val d = Prefs.Dnd(enabled.isChecked, sh, sm, eh, em)
                val svc = readyService() ?: return@setPositiveButton
                pendingDnd = d
                svc.command(UteDnd.build(sh, sm, eh, em, d.enabled), "rahatsız etme (15)")
            }
            .show()
    }

    // ---------- Sağlık ----------

    private fun readHealth() {
        val svc = readyService() ?: return
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val from = UteDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        svc.command(UteHealth.stepHistory(from), "adım geçmişi (0a), dünden beri")
        // Haylou Fun da iki isteği arka arkaya yolluyor; yine de adım yanıtına kısa süre tanınır.
        main.postDelayed({ WatchHub.service?.command(UteHealth.heartHistory(from), "nabız geçmişi (18 FA), dünden beri") }, 1500)
        main.postDelayed({ WatchHub.service?.command(UteHealth.sleepHistory(from), "uyku geçmişi (1d 01), dünden beri; kayıtlar ham loglanır") }, 4000)
    }

    fun refreshHealth() {
        if (!::healthView.isInitialized) return
        val steps = WatchHub.stepsToday()
        val heart = WatchHub.lastHeart
        val sum = WatchHub.heartSummary
        healthView.text = buildString {
            append("Bugün adım: ").append(steps?.toString() ?: "veri yok (geçmişi oku)").append('\n')
            append("Son nabız: ").append(heart?.let { "${it.bpm} (${hm(it.hour, it.minute)})" } ?: "henüz yok").append('\n')
            sum?.let { append("Nabız özeti: en yüksek ${it.max}, en düşük ${it.min}, ortalama ${it.avg}\n") }
            append("Doğrulanmadı: saatin ekranındaki adımla karşılaştır.")
        }
    }

    // ---------- yardımcılar ----------

    private fun readyService(): WatchService? {
        val svc = WatchHub.service
        if (svc == null || !WatchHub.ready) {
            toast("Saat bağlı değil.")
            return null
        }
        return svc
    }

    private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)
    private fun toast(t: String) = Toast.makeText(a, t, Toast.LENGTH_SHORT).show()
    private fun note(t: String) = TextView(a).apply { text = t; textSize = 13f }
    private fun vertical() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }

    private fun btn(label: String, onClick: () -> Unit) = Button(a).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun line(label: View, action: View) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        addView(action, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun rowOf(views: List<View>) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }
}
