package com.che.ls05

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.che.ls05.protocol.UteFrame

/**
 * v0.4 panel: üst şerit (bağlantı hapı), dört sekme (Bugün, Saat, Bildirimler, Ayarlar), alt çubuk.
 * Bağlantı servis tarafından tutulur; bu ekran kapansa da devam eder.
 */
class MainActivity : Activity(), WatchHub.Observer {

    class SetupItem(val label: String, val ok: Boolean?, val fix: () -> Unit)

    companion object {
        private const val KEY_TAB = "tab"
    }

    lateinit var ui: Ui
    val handler = Handler(Looper.getMainLooper())

    lateinit var today: TodayPage
    lateinit var watch: WatchPage
    lateinit var notify: NotifyPage
    lateinit var settings: SettingsPage

    private lateinit var titleView: TextView
    private lateinit var statusPill: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var pages: List<Page>
    private val tabs = ArrayList<TabItem>()
    private val dialogs = ArrayList<Dialog>()
    private var tab = 0
    private var barPad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        HealthStore.init(applicationContext) // tekrar çağrılırsa bir şey yapmaz
        tab =(savedInstanceState?.getInt(KEY_TAB, 0) ?: 0).coerceIn(0, 3)
        setContentView(buildUi())
        WatchHub.addObserver(this)
        selectTab(tab)
        refreshTop()
        if (savedInstanceState == null && !hasBlePermissions()) requestBlePermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onDestroy() {
        WatchHub.removeObserver(this)
        handler.removeCallbacksAndMessages(null)
        dialogs.forEach { if (it.isShowing) it.dismiss() }
        dialogs.clear()
        super.onDestroy()
    }

    // ---------- İzinler ve kurulum kontrolleri ----------

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasBlePermissions(): Boolean =
        blePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun requestBlePermissions() = requestPermissions(blePermissions(), 1)

    private fun hasPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess(): Boolean {
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
        refreshAll()
    }

    /** Kurulum denetimleri. ok: true tamam, false eksik, null elle kontrol edilmeli. */
    fun setupItems(): List<SetupItem> {
        val list = ArrayList<SetupItem>()
        list += SetupItem("Bluetooth izinleri", hasBlePermissions()) { requestBlePermissions() }
        if (Build.VERSION.SDK_INT >= 33) {
            list += SetupItem("Durum bildirimi izni", hasPostNotifications()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
        list += SetupItem("Bildirim erişimi", hasNotificationAccess()) {
            openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        list += SetupItem("Pil kısıtlaması kapalı", ignoresBatteryOptimizations()) {
            openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        list += SetupItem("Xiaomi otomatik başlatma", null) { openXiaomiAutostart() }
        return list
    }

    /** İlk eksik kurulum adımının adı; hepsi tamamsa null. Elle kontrol edilenler eksik sayılmaz. */
    fun firstMissingSetup(): String? = setupItems().firstOrNull { it.ok == false }?.label

    fun openSettings(intent: Intent) {
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

    // ---------- Ortak yardımcılar (sayfalar kullanır) ----------

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Saat hazırsa servis; değilse Toast ve null. */
    fun readyService(): WatchService? {
        val s = WatchHub.service
        if (s == null || !s.isWatchReady()) {
            toast("Saat bağlı değil.")
            return null
        }
        return s
    }

    /** Etkinlik kapanırken açık pencereler kapatılsın diye kaydedilir. */
    fun track(d: Dialog) {
        dialogs.removeAll { !it.isShowing }
        dialogs += d
    }

    fun pickTime(hour: Int, minute: Int, done: (Int, Int) -> Unit) {
        val d = TimePickerDialog(this, { _, h, m -> done(h, m) }, hour, minute, true)
        track(d)
        d.show()
    }

    // ---------- Arayüz ----------

    private fun buildUi(): View {
        val root = ui.vertical()
        root.setBackgroundColor(ui.color(R.color.bg))

        val top = ui.horizontal()
        top.minimumHeight = ui.dp(56)
        top.setPadding(ui.dp(20), ui.dp(8), ui.dp(16), ui.dp(8))
        titleView = ui.text("", 22f, R.color.text, bold = true)
        ui.add(top, titleView, width = Ui.WRAP)
        val pillBox = ui.horizontal()
        pillBox.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        statusPill = ui.pill("", Tone.WARN)
        ui.add(pillBox, statusPill, width = Ui.WRAP)
        ui.add(top, pillBox, width = 0, weight = 1f, leftDp = 16)
        ui.add(root, top)

        today = TodayPage(this)
        watch = WatchPage(this)
        notify = NotifyPage(this)
        settings = SettingsPage(this)
        pages = listOf(today, watch, notify, settings)
        val content = FrameLayout(this)
        pages.forEach { content.addView(it.root, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)) }
        ui.add(root, content, height = 0, weight = 1f)

        bottomBar = ui.horizontal()
        bottomBar.setBackgroundColor(ui.color(R.color.card))
        barPad = ui.dp(4)
        bottomBar.setPadding(ui.dp(8), barPad, ui.dp(8), barPad)
        val icons = intArrayOf(R.drawable.ic_today, R.drawable.ic_watch, R.drawable.ic_bell, R.drawable.ic_settings)
        pages.forEachIndexed { i, p ->
            val item = ui.tabItem(icons[i], p.title) { selectTab(i) }
            tabs += item
            ui.add(bottomBar, item.view, width = 0, weight = 1f)
        }
        ui.add(root, bottomBar)

        root.setOnApplyWindowInsetsListener { v, insets ->
            applyInsets(v, insets)
            insets
        }
        return root
    }

    /** Sistem çubukları ve klavye için boşluk. Alt çubuğun zemini gezinme çubuğunun altına uzanır. */
    private fun applyInsets(v: View, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val all = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            val imeOpen = all.bottom > bars.bottom
            v.setPadding(all.left, all.top, all.right, if (imeOpen) all.bottom else 0)
            bottomBar.setPadding(ui.dp(8), barPad, ui.dp(8), barPad + (if (imeOpen) 0 else bars.bottom))
        } else {
            @Suppress("DEPRECATION")
            val left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            val topInset = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            v.setPadding(left, topInset, right, 0)
            bottomBar.setPadding(ui.dp(8), barPad, ui.dp(8), barPad + bottom)
        }
    }

    fun selectTab(i: Int) {
        tab = i
        pages.forEachIndexed { k, p -> p.root.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t ->
            val c = ui.color(if (k == i) R.color.accent else R.color.text2)
            t.label.setTextColor(c)
            t.icon?.imageTintList = ColorStateList.valueOf(c)
        }
        titleView.text = pages[i].title
        pages[i].refresh()
    }

    private fun refreshTop() {
        if (WatchHub.ready) {
            val b = WatchHub.battery
            ui.setPill(statusPill, if (b != null) "Bağlı · Pil %$b" else "Bağlı", Tone.OK)
        } else {
            ui.setPill(statusPill, WatchHub.status, Tone.WARN)
        }
    }

    private fun refreshAll() {
        refreshTop()
        pages.forEach { it.refresh() }
    }

    // ---------- WatchHub.Observer ----------

    override fun onLog(line: String) {
        settings.onLog(line)
    }

    override fun onStatus(status: String) {
        refreshTop()
        watch.refresh()
    }

    override fun onReadyChanged(ready: Boolean) {
        refreshTop()
        watch.refresh()
        notify.onReady(ready)
        settings.onReady(ready)
    }

    override fun onSettingsChanged() {
        refreshTop()
        watch.refresh()
        settings.refreshWatchInfo()
    }

    override fun onHealthChanged() {
        today.refresh()
    }

    override fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) {
        settings.onDeviceFound(device, name, rssi)
    }

    override fun onFrame(frame: UteFrame) {
        notify.onFrame(frame)
    }
}
