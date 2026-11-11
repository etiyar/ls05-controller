package com.che.ls05

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.che.ls05.protocol.NotificationState
import com.che.ls05.protocol.UteFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.1 test ekranı. Kapsam: tara, bağlan, UUID doğrula, pil + firmware oku, test bildirimi gönder.
 * Sağlık senkronu, kadran ve firmware kodu bu sürümde YOK.
 */
class MainActivity : Activity(), Ls05Ble.Listener {

    private lateinit var ble: Ls05Ble
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var deviceList: LinearLayout
    private lateinit var actionButtons: List<Button>
    private lateinit var confirmRow: LinearLayout
    private lateinit var vibrateBox: CheckBox

    private val logLines = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var notifState = NotificationState.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = Ls05Ble(applicationContext, this)
        setContentView(buildUi())
        log("LS05 Controller v0.1. Test öncesi Haylou uygulamasını durdurmayı unutma.")
        requestBlePermissions()
    }

    override fun onDestroy() {
        ble.stopScan()
        ble.disconnect()
        super.onDestroy()
    }

    // ---------- İzinler ----------

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean =
        neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestBlePermissions() {
        if (!hasPermissions()) requestPermissions(neededPermissions(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        log(if (hasPermissions()) "Bluetooth izinleri verildi." else "Bluetooth izinleri reddedildi. Ayarlar > Uygulamalar'dan ver.")
    }

    // ---------- Arayüz ----------

    private fun buildUi(): View {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            text = "Durum: Bağlı değil"
        }
        root.addView(statusView)

        root.addView(row(
            button("Tara") { if (hasPermissions()) ble.startScan() else requestBlePermissions() },
            button("Bağlantıyı kes") { ble.disconnect() },
        ))

        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceList)

        val fw = button("Firmware (02)") { ble.sendCommand(byteArrayOf(0x02), "firmware sorgu") }
        val bat = button("Pil (03)") { ble.sendCommand(byteArrayOf(0x03), "pil sorgu") }
        val std = button("Standart pil") { ble.readStandardBattery() }
        val test = button("Test bildirimi") { sendTestNotification("LS05TEST123", "LS05TEST123") }
        val tr = button("Türkçe glif testi") { sendTestNotification("Test", "şğıöüç ŞĞİÖÜÇ") }
        val vib = button("Sadece titreşim (06)") { ble.vibrateText() }
        actionButtons = listOf(fw, bat, std, test, tr, vib)
        actionButtons.forEach { it.isEnabled = false }
        root.addView(row(fw, bat, std))
        root.addView(row(test, tr, vib))
        vibrateBox = CheckBox(this).apply {
            text = "Bildirimden sonra titret (kayıttaki akış)"
            isChecked = true
        }
        root.addView(vibrateBox)

        confirmRow = row(
            button("Saatte gördüm") { setNotifState(NotificationState.DISPLAYED_CONFIRMED) },
            button("Görmedim") { setNotifState(NotificationState.NOT_DISPLAYED) },
        ).apply { visibility = View.GONE }
        root.addView(confirmRow)

        root.addView(row(
            button("Logu kopyala") { copyLog() },
            button("Logu temizle") { logLines.clear(); logView.text = "" },
        ))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    // ---------- Bildirim akışı ----------

    private fun sendTestNotification(title: String, body: String) {
        if (ble.sendNotification(title, body, vibrateBox.isChecked) == 0) return
        setNotifState(NotificationState.SENDING)
        confirmRow.visibility = View.GONE
        logView.postDelayed({
            if (notifState == NotificationState.SENDING) setNotifState(NotificationState.TRANSFER_TIMEOUT)
        }, 6_000)
    }

    private fun setNotifState(s: NotificationState) {
        notifState = s
        val text = when (s) {
            NotificationState.IDLE -> return
            NotificationState.SENDING -> "Bildirim gönderiliyor…"
            NotificationState.TRANSFER_DONE -> "Aktarım tamam (saat onayladı). Ekranda gördün mü?"
            NotificationState.TRANSFER_TIMEOUT -> "Aktarım onayı 6 sn içinde gelmedi. Yine de ekranda gördün mü?"
            NotificationState.DISPLAYED_CONFIRMED -> "SONUÇ: Bildirim saatte göründü (kullanıcı onayı)."
            NotificationState.NOT_DISPLAYED -> "SONUÇ: Bildirim saatte görünmedi (kullanıcı onayı)."
        }
        log(text)
        status(text)
        confirmRow.visibility = if (s == NotificationState.TRANSFER_DONE || s == NotificationState.TRANSFER_TIMEOUT) View.VISIBLE else View.GONE
    }

    // ---------- Ls05Ble.Listener ----------

    override fun onLog(line: String) = log(line)

    override fun onStatus(status: String) {
        statusView.text = "Durum: $status"
    }

    override fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) {
        deviceList.addView(button("Bağlan: $name ($rssi dBm)") { ble.connect(device) })
    }

    override fun onFrame(frame: UteFrame) {
        if (frame is UteFrame.NotificationTransferDone && notifState == NotificationState.SENDING) {
            setNotifState(NotificationState.TRANSFER_DONE)
        }
    }

    override fun onReadyChanged(ready: Boolean) {
        actionButtons.forEach { it.isEnabled = ready }
        if (ready) deviceList.removeAllViews()
    }

    // ---------- Log ----------

    private fun status(s: String) {
        statusView.text = "Durum: $s"
    }

    private fun log(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        logLines += stamped
        if (logLines.size > 2_000) logLines.removeAt(0)
        logView.append(stamped + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("LS05 log", logLines.joinToString("\n")))
        Toast.makeText(this, "Log kopyalandı (bildirim içerikleri gizli, MAC maskeli).", Toast.LENGTH_SHORT).show()
    }
}
