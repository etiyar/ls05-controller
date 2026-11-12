package com.che.ls05

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.che.ls05.protocol.NotificationState
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.UteFrame

/**
 * v0.2 kontrol ekranı. Bağlantı servis tarafından tutulur; bu ekran kapansa da devam eder.
 * Önce işlev: sade, programatik arayüz.
 */
class MainActivity : Activity(), WatchHub.Observer {

    private lateinit var statusView: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var idInput: EditText
    private lateinit var deviceList: LinearLayout
    private lateinit var actionButtons: List<Button>
    private lateinit var confirmRow: LinearLayout
    private lateinit var appList: LinearLayout
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private var notifState = NotificationState.IDLE
    private val logShown = 300

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        WatchHub.addObserver(this)
        refreshLog()
        onStatus(WatchHub.status)
        onReadyChanged(WatchHub.ready)
        if (!hasBlePermissions()) requestBlePermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshChecklist()
        refreshApps()
    }

    override fun onDestroy() {
        WatchHub.removeObserver(this)
        super.onDestroy()
    }

    // ---------- İzinler ve kurulum kontrolleri ----------

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBlePermissions(): Boolean =
        blePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestBlePermissions() = requestPermissions(blePermissions(), 1)

    private fun hasPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess(): Boolean {
        val cn = ComponentName(this, NotifyForwarder::class.java)
        return if (Build.VERSION.SDK_INT >= 27) {
            getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(cn)
        } else {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.contains(cn.flattenToString()) == true
        }
    }

    private fun ignoresBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshChecklist()
    }

    private fun refreshChecklist() {
        checklist.removeAllViews()
        checkRow("Bluetooth izinleri", hasBlePermissions()) { requestBlePermissions() }
        if (Build.VERSION.SDK_INT >= 33) {
            checkRow("Durum bildirimi izni", hasPostNotifications()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
        checkRow("Bildirim erişimi (iletim için)", hasNotificationAccess()) {
            openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        checkRow("Pil kısıtlaması yok", ignoresBatteryOptimizations()) {
            openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        checkRow("Xiaomi otomatik başlatma (elle kontrol et)", null) { openXiaomiAutostart() }
    }

    private fun checkRow(label: String, ok: Boolean?, fix: () -> Unit) {
        val mark = when (ok) {
            true -> "TAMAM"
            false -> "EKSİK"
            null -> "?"
        }
        val text = TextView(this).apply { this.text = "$mark  $label"; textSize = 15f }
        val btn = button(if (ok == true) "Aç" else "Düzelt") { fix() }
        checklist.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            toast("Ayarlar açılamadı.")
        }
    }

    private fun openXiaomiAutostart() {
        val i = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            openAppDetails()
        } catch (e: SecurityException) {
            openAppDetails()
        }
    }

    // ---------- Arayüz ----------

    private fun buildUi(): View {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val sb = insets.getInsets(WindowInsets.Type.systemBars())
                sb.top to sb.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = "LS05 Controller v0.2"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(statusView)

        root.addView(header("Kurulum"))
        checklist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(checklist)

        root.addView(header("Saat"))
        idInput = EditText(this).apply {
            hint = "Haylou kimliği (8 hane)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(Prefs.userIdText(this@MainActivity))
        }
        root.addView(idInput)
        root.addView(row(
            button("Tara") { scan() },
            button("Bağlan") { connectSaved() },
            button("Durdur") { WatchService.start(this, WatchService.ACTION_STOP) },
        ))
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceList)

        root.addView(header("Ayarlar"))
        root.addView(checkBox("Bildirimleri saate ilet", Prefs.forwardingEnabled(this)) {
            Prefs.setForwardingEnabled(this, it)
        })
        root.addView(checkBox("Bildirimden sonra titret", Prefs.vibrate(this)) { Prefs.setVibrate(this, it) })
        root.addView(checkBox("Bağlanınca saati otomatik eşitle (önce 'Saati eşitle' ile elle dene)", Prefs.autoTime(this)) {
            Prefs.setAutoTime(this, it)
        })

        root.addView(header("Testler"))
        val fw = button("Firmware") { WatchHub.service?.command(UteCommand.firmwareRequest(), "firmware sorgu") }
        val bat = button("Pil") { WatchHub.service?.command(UteCommand.batteryRequest(), "pil sorgu") }
        val time = button("Saati eşitle") { WatchHub.service?.sendTime() }
        val test = button("Test bildirimi") { sendTest("LS05TEST123", "LS05TEST123") }
        val tr = button("Türkçe test") { sendTest("Test", "şğıöüç ŞĞİÖÜÇ") }
        val vib = button("Titreşim") { WatchHub.service?.command(UteCommand.VIBRATE_TEXT, "mesaj titreşimi (06), tek başına") }
        actionButtons = listOf(fw, bat, time, test, tr, vib)
        root.addView(row(fw, bat, time))
        root.addView(row(test, tr, vib))
        confirmRow = row(
            button("Saatte gördüm") { setNotifState(NotificationState.DISPLAYED_CONFIRMED) },
            button("Görmedim") { setNotifState(NotificationState.NOT_DISPLAYED) },
        ).apply { visibility = View.GONE }
        root.addView(confirmRow)

        root.addView(header("İletilecek uygulamalar"))
        root.addView(TextView(this).apply {
            text = "Bildirim gönderen uygulamalar burada görünür. Mesajlaşma uygulamaları varsayılan açık."
            textSize = 13f
        })
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appList)

        root.addView(header("Log"))
        root.addView(row(
            button("Logu kopyala") { copyLog() },
            button("Logu temizle") { WatchHub.logLines.clear(); logView.text = "" },
        ))
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(logView)
        return scroll
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun checkBox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---------- Saat seçimi ve bağlantı ----------

    private fun saveId(): Boolean {
        val text = idInput.text.toString()
        if (UteCommand.parseUserId(text) == null) {
            WatchHub.log("Kimlik geçersiz veya boş: 8 haneli onaltılık değer gerekli.")
            toast("Kimlik geçersiz")
            return false
        }
        Prefs.setUserIdText(this, text)
        return true
    }

    private fun scan() {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        deviceList.removeAllViews()
        if (WatchHub.service == null) WatchService.start(this)
        statusView.postDelayed({
            val s = WatchHub.service
            if (s == null) WatchHub.log("Servis başlamadı; Bluetooth iznini kontrol et.") else s.ble.startScan()
        }, 600)
    }

    private fun connectSaved() {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        if (!saveId()) return
        val address = Prefs.address(this)
        if (address == null) {
            WatchHub.log("Kayıtlı saat yok; önce Tara ile saati seç.")
            return
        }
        WatchService.start(this, WatchService.ACTION_CONNECT_NEW, address)
    }

    // ---------- Uygulama listesi ----------

    private fun refreshApps() {
        appList.removeAllViews()
        val pm = packageManager
        val apps = Prefs.seenApps(this).map { pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            pkg to label
        }.sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = "Henüz bildirim görülmedi. Bildirim erişimini açtıktan sonra gelen bildirimlerle dolar."
                textSize = 13f
            })
        }
        apps.forEach { (pkg, label) ->
            appList.addView(checkBox(label, Prefs.appEnabled(this, pkg)) { Prefs.setAppEnabled(this, pkg, it) })
        }
    }

    // ---------- Test bildirimi ----------

    private fun sendTest(title: String, body: String) {
        val s = WatchHub.service
        if (s == null || !s.isWatchReady()) {
            WatchHub.log("Saat hazır değil; önce bağlan.")
            return
        }
        if (s.ble.sendNotification(title, body, Prefs.vibrate(this)) == 0) return
        setNotifState(NotificationState.SENDING)
        statusView.postDelayed({
            if (notifState == NotificationState.SENDING) setNotifState(NotificationState.TRANSFER_TIMEOUT)
        }, 6_000)
    }

    private fun setNotifState(s: NotificationState) {
        notifState = s
        val text = when (s) {
            NotificationState.IDLE -> return
            NotificationState.SENDING -> "Test bildirimi gönderiliyor…"
            NotificationState.TRANSFER_DONE -> "Aktarım tamam (saat onayladı). Ekranda gördün mü?"
            NotificationState.TRANSFER_TIMEOUT -> "Aktarım onayı 6 sn içinde gelmedi. Ekranda gördün mü?"
            NotificationState.DISPLAYED_CONFIRMED -> "SONUÇ: Test bildirimi saatte göründü (kullanıcı onayı)."
            NotificationState.NOT_DISPLAYED -> "SONUÇ: Test bildirimi saatte görünmedi (kullanıcı onayı)."
        }
        WatchHub.log(text)
        confirmRow.visibility =
            if (s == NotificationState.TRANSFER_DONE || s == NotificationState.TRANSFER_TIMEOUT) View.VISIBLE else View.GONE
    }

    // ---------- WatchHub.Observer ----------

    override fun onLog(line: String) {
        logView.append(line + "\n")
        val lines = logView.lineCount
        if (lines > logShown * 2) refreshLog()
    }

    override fun onStatus(status: String) {
        statusView.text = "Durum: $status"
    }

    override fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) {
        deviceList.addView(button("Bu saati kullan: $name ($rssi dBm)") {
            if (!saveId()) return@button
            deviceList.removeAllViews()
            WatchService.start(this, WatchService.ACTION_CONNECT_NEW, device.address)
        })
    }

    override fun onFrame(frame: UteFrame) {
        if (frame is UteFrame.NotificationTransferDone && notifState == NotificationState.SENDING) {
            setNotifState(NotificationState.TRANSFER_DONE)
        }
    }

    override fun onReadyChanged(ready: Boolean) {
        actionButtons.forEach { it.isEnabled = ready }
    }

    private fun refreshLog() {
        logView.text = WatchHub.logLines.toList().takeLast(logShown).joinToString("\n", postfix = "\n")
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("LS05 log", WatchHub.logLines.joinToString("\n")))
        toast("Log kopyalandı (bildirim içerikleri gizli, MAC maskeli).")
    }
}
