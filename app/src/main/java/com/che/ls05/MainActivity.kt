package com.che.ls05

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.app.UiModeManager
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
 * v0.6 panel: sayfa başlığı ve bağlantı kapsülü, dört sekme (Bugün, Saat, Bildirimler, Ayarlar), yüzen alt menü.
 * Üst uygulama çubuğu yoktur. Bağlantı servis tarafından tutulur; bu ekran kapansa ya da tema değişip
 * yeniden kurulsa da devam eder.
 */
class MainActivity : Activity(), WatchHub.Observer {

    class SetupItem(val label: String, val ok: Boolean?, val fix: () -> Unit)

    companion object {
        private const val KEY_TAB = "tab"
        /** Konum izni isteği (son görülen yer). 1: Bluetooth, 2: bildirim, 3: arama. */
        const val REQ_LOCATION = 4
        private const val REQ_FACE = 41
    }

    lateinit var ui: Ui
    val handler = Handler(Looper.getMainLooper())

    lateinit var today: TodayPage
    lateinit var watch: WatchPage
    lateinit var notify: NotifyPage
    lateinit var settings: SettingsPage

    private lateinit var titleView: TextView
    private lateinit var statusPill: TextView
    private lateinit var barWrap: LinearLayout
    private lateinit var pages: List<Page>
    private val tabs = ArrayList<TabItem>()
    private val dialogs = ArrayList<Dialog>()
    private var tab = 0

    /**
     * Tema seçimi (API 26-30): kaynaklar seçilmeden önce gece bayrağı zorlanır.
     * API 31 ve üstünde seçimi sistem saklar ve uygular (UiModeManager.setApplicationNightMode).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(themedContext(newBase))
    }

    private fun themedContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 31) return base
        val night = when (Prefs.themeMode(base)) {
            Prefs.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            Prefs.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            else -> return base
        }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    /** Ayarlar'daki tema segmenti çağırır. Seçim hemen uygulanır; ekran yeniden kurulur, bağlantı etkilenmez. */
    fun setThemeMode(mode: Int) {
        if (mode == Prefs.themeMode(this)) return
        Prefs.setThemeMode(this, mode)
        if (Build.VERSION.SDK_INT >= 31) {
            val night = when (mode) {
                Prefs.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                Prefs.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
            getSystemService(UiModeManager::class.java).setApplicationNightMode(night)
            // Görünen tema değişiyorsa sistem ekranı kendisi yeniden kurar. Kurmadıysa burada kurulur.
            handler.postDelayed({
                if (!isFinishing && !isDestroyed && themeMismatch(mode)) recreate()
            }, 600L)
        } else {
            recreate()
        }
    }

    private fun themeMismatch(mode: Int): Boolean {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (mode) {
            Prefs.THEME_DARK -> !night
            Prefs.THEME_LIGHT -> night
            else -> false
        }
    }

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
        if (savedInstanceState == null) Reminders.reschedule(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        // Servis arka planda başlatılamadıysa (ör. kaydırma sonrası) kuyruktaki bildirimler şimdi gitsin.
        if (WatchHub.service == null && Prefs.serviceEnabled(this) && Prefs.address(this) != null && hasBlePermissions()) {
            WatchService.start(this)
        }
        // Servis arka planda başladıysa konum türü eklenememiş olabilir; uygulama öndeyken yeniden kurulur.
        if (LastSeen.active(this)) WatchHub.service?.refreshForegroundType()
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
        if (requestCode == REQ_LOCATION) settings.onLocationPermission(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
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
        if (WatchHub.faceBusy) {
            toast("Kadran yükleniyor; bitince tekrar dene.")
            return null
        }
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

        // Sayfa başlığı + bağlantı kapsülü (her ekranda aynı).
        val top = ui.horizontal()
        top.minimumHeight = ui.dp(56)
        top.setPadding(ui.dp(20), ui.dp(8), ui.dp(16), ui.dp(4))
        titleView = ui.text("", 26f, R.color.text, bold = true)
        titleView.includeFontPadding = false
        ui.add(top, titleView, width = Ui.WRAP)
        // Kapsül kalan genişliğe sığar; uzun durum metni başlığı itmez, kendi içinde kısalır.
        val pillBox = ui.horizontal()
        pillBox.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        statusPill = ui.pill("", Tone.NEUTRAL)
        ui.add(pillBox, statusPill, width = Ui.WRAP)
        ui.add(top, pillBox, width = 0, weight = 1f, leftDp = 12)
        ui.add(root, top)

        today = TodayPage(this)
        watch = WatchPage(this)
        notify = NotifyPage(this)
        settings = SettingsPage(this)
        pages = listOf(today, watch, notify, settings)
        val content = FrameLayout(this)
        pages.forEach { content.addView(it.root, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)) }
        ui.add(root, content, height = 0, weight = 1f)

        // Alt menü: tek yüzey. İçerik menünün üstünde biter; menü sistem gezinme alanına girmez.
        barWrap = ui.vertical()
        val bar = ui.tabBar()
        val icons = intArrayOf(R.drawable.ic_today, R.drawable.ic_watch, R.drawable.ic_bell, R.drawable.ic_settings)
        pages.forEachIndexed { i, p ->
            val item = ui.tabItem(icons[i], p.title) { selectTab(i) }
            tabs += item
            ui.add(bar, item.view, width = 0, weight = 1f)
        }
        ui.add(barWrap, bar)
        setBarInsets(0)
        ui.add(root, barWrap)

        root.setOnApplyWindowInsetsListener { v, insets ->
            applyInsets(v, insets)
            insets
        }
        return root
    }

    private fun setBarInsets(bottom: Int) {
        barWrap.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8) + bottom)
    }

    /** Sistem çubukları ve klavye için boşluk. Klavye açıkken alt menü gizlenir (arama kutusuna yer kalsın). */
    private fun applyInsets(v: View, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val all = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            val imeOpen = all.bottom > bars.bottom
            v.setPadding(all.left, all.top, all.right, if (imeOpen) all.bottom else 0)
            barWrap.visibility = if (imeOpen) View.GONE else View.VISIBLE
            setBarInsets(bars.bottom)
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
            setBarInsets(bottom)
        }
    }

    fun selectTab(i: Int) {
        tab = i
        pages.forEachIndexed { k, p -> p.root.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t -> ui.setTab(t, k == i) }
        titleView.text = pages[i].title
        pages[i].refresh()
    }

    /** Kapsül: bağlıyken nane ("Bağlı · %54"), bağlanırken nötr, bağlı değilken uyarı tonu. */
    private fun refreshTop() {
        if (WatchHub.ready) {
            val b = WatchHub.battery
            ui.setPill(statusPill, if (b != null) "Bağlı · %$b" else "Bağlı", Tone.OK)
        } else {
            val s = WatchHub.status
            val busy = s.endsWith("…") || s.startsWith("Saatte onay")
            ui.setPill(statusPill, s, if (busy) Tone.NEUTRAL else Tone.WARN)
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
        today.refresh()
        settings.refreshWatchInfo()
    }

    override fun onFaceChanged() {
        if (WatchHub.faceBusy) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (WatchHub.faceBusy) watch.refreshFaceProgress() else watch.refreshFaces()
    }

    // ---------- Kadran dosyası seçme ----------

    fun pickFace() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_FACE)
        } catch (e: ActivityNotFoundException) {
            toast("Dosya seçici açılamadı.")
        }
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HealthExport.REQ) {
            HealthExport.onResult(this, resultCode, data)
            return
        }
        if (requestCode != REQ_FACE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // .tbwf tasarım dosyası açılınca 8 MB'a kadar çıkabilir; zip de bu sınıra kadar okunur.
        val limit = TbwfCompiler.MAX_TOTAL
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > limit) return@use null
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
        if (bytes == null || (!TbwfCompiler.isTbwf(bytes) && bytes.size > com.che.ls05.protocol.FaceFile.MAX_SIZE)) {
            toast("Dosya okunamadı ya da ${com.che.ls05.protocol.FaceFile.MAX_SIZE / 1024} KB sınırından büyük.")
            return
        }
        var name = "Kadran"
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)?.substringBeforeLast('.')?.replace('_', ' ') ?: name
            }
        } catch (_: Exception) {
        }
        val tbwf = TbwfCompiler.isTbwf(bytes)
        val progress = if (tbwf) showProgress("Tasarım kadrana dönüştürülüyor…") else null
        FaceLibrary.add(this, bytes, name) { r ->
            try { progress?.dismiss() } catch (_: Exception) { }
            if (isFinishing || isDestroyed) return@add
            r.onSuccess {
                watch.refreshFaces()
                if (it.warnings.isEmpty()) {
                    toast("Eklendi: ${it.entry.name}")
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Eklendi: ${it.entry.name}")
                        .setMessage("Bazı öğeler çizilmedi:\n\n" + it.warnings.joinToString("\n") { w -> "• $w" })
                        .setPositiveButton("Tamam", null)
                        .show()
                }
            }.onFailure {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Kadran eklenemedi")
                    .setMessage(it.message ?: "Dosya kadran biçiminde değil.")
                    .setPositiveButton("Tamam", null)
                    .show()
            }
        }
    }

    /** İptal edilemeyen bekleme penceresi. */
    private fun showProgress(text: String): android.app.AlertDialog {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(pad, pad, pad, pad)
        row.addView(android.widget.ProgressBar(this))
        val t = android.widget.TextView(this)
        t.text = text
        t.setPadding(pad, 0, 0, 0)
        row.addView(t)
        val d = android.app.AlertDialog.Builder(this).setView(row).setCancelable(false).create()
        d.show()
        return d
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
