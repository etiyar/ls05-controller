package com.che.ls05

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.che.ls05.protocol.AlarmSetting
import com.che.ls05.protocol.DndSetting
import com.che.ls05.protocol.ProfileSetting
import com.che.ls05.protocol.RemoteAction
import com.che.ls05.protocol.SettingCommands
import com.che.ls05.protocol.SettingSync
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.UteDate
import com.che.ls05.protocol.UteFrame
import com.che.ls05.protocol.UteHealth
import com.che.ls05.protocol.UteMusic
import com.che.ls05.protocol.WatchKeys
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Saatle kalıcı bağlantıyı tutan ön plan servisi (connectedDevice).
 *
 * - Kopunca kademeli bekleme ile yeniden bağlanır (autoConnect: saat menzile girince sistem bağlar).
 * - Her bağlantıda doğrulama yapılır. Ret (2/3/5), onay bekleme (4) veya kimlik yokluğu otomatik
 *   denemeleri durdurur; zaman aşımı en fazla 3 kez denenir (kural 7). Sonra kullanıcıya sorulur.
 * - Kullanıcı "Durdur" derse yeniden bağlanmaz ve açılışta başlamaz.
 */
class WatchService : Service(), Ls05Ble.Listener {

    companion object {
        const val ACTION_START = "com.che.ls05.START"
        const val ACTION_STOP = "com.che.ls05.STOP"
        const val ACTION_CONNECT_NEW = "com.che.ls05.CONNECT_NEW"
        const val EXTRA_ADDRESS = "address"

        private const val CHANNEL_ID = "watch"
        private const val NOTIF_ID = 1
        private const val MAX_AUTH_TIMEOUTS = 3
        private const val ACK_TIMEOUT_MS = 8_000L
        private const val ACK_CHECK_MS = 8_200L
        private const val HISTORY_STALE_MS = 30 * 60 * 1000L
        private const val MAX_LIVE_STEPS = 48

        /** Saat bu süreden uzun çalmaz: arama bitişi kaçırılırsa saat susturulur. */
        private const val CALL_MAX_MS = 60_000L
        private const val NUDGE_MIN_INTERVAL_MS = 30_000L
        private const val MUSIC_STATE_DELAY_MS = 700L

        /** Başlatma isteği sisteme iletildiyse true. Arka plan kısıtı (Android 12+) gibi hatalarda false. */
        fun start(context: Context, action: String = ACTION_START, address: String? = null): Boolean {
            val i = Intent(context, WatchService::class.java).setAction(action)
            if (address != null) i.putExtra(EXTRA_ADDRESS, address)
            return try {
                context.startForegroundService(i)
                true
            } catch (e: Exception) {
                WatchHub.log("Servis başlatılamadı: ${e.javaClass.simpleName}. Uygulamayı açıp tekrar dene.")
                false
            }
        }
    }

    lateinit var ble: Ls05Ble
        private set

    private val main = Handler(Looper.getMainLooper())
    private val backoffMs = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)

    private var stopping = false
    private var restarting = false
    private var authBlocked = false
    private var authTimeouts = 0
    private var reconnectAttempt = 0

    /** Bağlantı oturumu: her hazır olmada artar. Onay yalnızca aynı oturumda gönderilen komuta sayılır. */
    private var session = 0

    /** Gecikmeli işler (onay denetimi, geçmiş istekleri) için ortak etiket; servis kapanınca hepsi iptal edilir. */
    private val delayedToken = Any()

    private val reconnectRunnable = Runnable { connectSaved(autoConnect = true) }

    /** Saatte gelen arama ekranı açık (0D 04 henüz gönderilmedi). */
    private var callOnWatch = false
    private val callSafetyStop = Runnable { sendCallStop("arama ${CALL_MAX_MS / 1000} sn içinde bitmedi") }
    private val musicStateRunnable = Runnable { sendMusicState() }
    private var lastNudge = 0L

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    WatchHub.log("Bluetooth kapandı.")
                    main.removeCallbacks(reconnectRunnable)
                    restarting = true
                    ble.forceClose("Bluetooth kapandı")
                    restarting = false
                    updateStatus("Bluetooth kapalı")
                }
                BluetoothAdapter.STATE_ON -> {
                    WatchHub.log("Bluetooth açıldı; yeniden bağlanılıyor.")
                    reconnectAttempt = 0
                    main.removeCallbacks(reconnectRunnable)
                    main.postDelayed(reconnectRunnable, 1_500)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ble = Ls05Ble(applicationContext, this)
        HealthStore.init(applicationContext)
        SettingsStore.sync(applicationContext)
        WatchHub.service = this
        createChannel()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onDestroy() {
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(callSafetyStop)
        main.removeCallbacks(musicStateRunnable)
        PhoneFinder.stop("servis kapandı")
        main.removeCallbacksAndMessages(delayedToken)
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        if (ble.isConnected) {
            restarting = true
            ble.forceClose("servis kapandı")
            restarting = false
        }
        WatchHub.setReady(false)
        WatchHub.service = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                Prefs.setServiceEnabled(this, false)
                main.removeCallbacks(reconnectRunnable)
                WatchHub.log("Servis kullanıcı tarafından durduruluyor.")
                if (ble.isConnected) ble.disconnect()
                main.postDelayed({
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }, 1_500)
            }
            ACTION_CONNECT_NEW -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address != null) Prefs.setAddress(this, address)
                Prefs.setServiceEnabled(this, true)
                stopping = false
                resetAuth()
                main.removeCallbacks(reconnectRunnable)
                if (ble.isConnected) {
                    restarting = true
                    ble.forceClose("yeni bağlantı isteği")
                    restarting = false
                }
                connectSaved(autoConnect = false)
            }
            else -> {
                stopping = false
                if (!ble.isConnected) connectSaved(autoConnect = false)
            }
        }
        return START_STICKY
    }

    // ---------- Bağlantı yönetimi ----------

    fun isWatchReady(): Boolean = ble.isReady

    private fun resetAuth() {
        authBlocked = false
        authTimeouts = 0
        reconnectAttempt = 0
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun connectSaved(autoConnect: Boolean) {
        if (stopping || ble.isConnected) return
        if (!hasConnectPermission()) {
            updateStatus("Bluetooth izni yok")
            WatchHub.log("Bluetooth bağlantı izni yok; uygulamayı açıp izin ver.")
            return
        }
        val address = Prefs.address(this)
        if (address == null) {
            updateStatus("Saat seçilmedi")
            return
        }
        if (authBlocked) {
            updateStatus("Doğrulama durdu; uygulamayı aç")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("Bluetooth kapalı")
            return
        }
        ble.userId = Prefs.userIdBytes(this)
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            updateStatus("Kayıtlı saat adresi geçersiz")
            return
        }
        if (autoConnect) WatchHub.log("Saat menzile girince bağlanılacak (otomatik bağlantı).")
        ble.connect(device, autoConnect)
    }

    /**
     * Saat hazır değilken bildirim geldi: servis yaşıyor ama yeniden bağlanma beklemesindeyse beklemeyi öne alır.
     * Bağlantı denemesi sürüyorsa (otomatik bağlantı dahil) bir şey yapmaz. En çok 30 sn'de bir.
     */
    fun nudgeReconnect() {
        if (stopping || authBlocked || ble.isConnected) return
        val now = SystemClock.uptimeMillis()
        if (now - lastNudge < NUDGE_MIN_INTERVAL_MS) return
        lastNudge = now
        WatchHub.log("Bekleyen bildirim var; yeniden bağlanma öne alındı.")
        main.removeCallbacks(reconnectRunnable)
        connectSaved(autoConnect = false)
    }

    private fun scheduleReconnect() {
        val delay = backoffMs[minOf(reconnectAttempt, backoffMs.size - 1)]
        reconnectAttempt++
        WatchHub.log("Yeniden bağlanma ${delay / 1000} sn sonra (deneme $reconnectAttempt).")
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, delay)
    }

    // ---------- Komutlar ----------

    fun command(data: ByteArray, label: String) = ble.sendCommand(data, label)

    fun sendTime(label: String = "saat eşitleme (04)") {
        val now = ZonedDateTime.now()
        val offsetHours = now.offset.totalSeconds / 3600
        ble.sendCommand(
            UteCommand.timeSync(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second, offsetHours),
            label,
        )
    }

    fun forward(note: WatchHub.Note) {
        when (note.kind) {
            WatchHub.NoteKind.MESSAGE, WatchHub.NoteKind.MISSED_CALL -> {
                val sent = ble.sendNotification(note.title, note.body, Prefs.vibrate(this), note.type)
                if (sent > 0) {
                    val what = if (note.kind == WatchHub.NoteKind.MISSED_CALL) "Cevapsız arama" else note.appLabel
                    WatchHub.log("İletildi: $what (tip ${note.type}, ${note.title.length + note.body.length} karakter).")
                }
            }
            WatchHub.NoteKind.CALL_ALERT -> sendCallAlert(note.body)
            WatchHub.NoteKind.CALL_STOP -> sendCallStop("arama cevaplandı ya da bitti")
        }
    }

    // ---------- Gelen arama ----------

    private fun sendCallAlert(name: String) {
        if (!ble.isReady) return
        if (ble.sendIncomingCall(name) > 0) {
            callOnWatch = true
            main.removeCallbacks(callSafetyStop)
            main.postDelayed(callSafetyStop, CALL_MAX_MS)
            WatchHub.log("Gelen arama saate iletildi (${name.length} karakter).")
        }
    }

    /** Saatte arama ekranı açıksa kapatır. Saat hazır değilse bağlanınca kapatılır. */
    private fun sendCallStop(reason: String) {
        if (!callOnWatch) return
        if (!ble.isReady) return
        main.removeCallbacks(callSafetyStop)
        if (ble.sendCallStop()) {
            callOnWatch = false
            WatchHub.log("Saatteki arama ekranı kapatıldı ($reason).")
        }
    }

    // ---------- Saatten kumanda ----------

    private fun handleWatchKey(key: UteFrame.WatchKey) {
        val action = WatchKeys.action(key)
        val label = WatchKeys.label(action)
        if (!Prefs.remoteControl(this)) {
            if (action != RemoteAction.UNKNOWN) WatchHub.log("Saatten kumanda kapalı; yok sayıldı ($label).")
            return
        }
        when (action) {
            RemoteAction.PLAY_PAUSE -> PhoneRemote.mediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            RemoteAction.NEXT -> PhoneRemote.mediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT)
            RemoteAction.PREVIOUS -> PhoneRemote.mediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            RemoteAction.VOLUME_UP -> PhoneRemote.adjustVolume(this, true)
            RemoteAction.VOLUME_DOWN -> PhoneRemote.adjustVolume(this, false)
            RemoteAction.FIND_PHONE_START -> PhoneFinder.start(this)
            RemoteAction.FIND_PHONE_STOP -> PhoneFinder.stop("saatten durduruldu")
            RemoteAction.FIND_PHONE_TOGGLE ->
                if (PhoneFinder.isActive) PhoneFinder.stop("saatten durduruldu") else PhoneFinder.start(this)
            RemoteAction.REJECT_CALL -> {
                rejectCallFromWatch()
                return
            }
            RemoteAction.CAMERA_ENTER, RemoteAction.CAMERA_EXIT -> {
                WatchHub.log("Saat kamera kumandası gönderdi ($label). Bu sürümde desteklenmiyor; yalnızca loglandı.")
                return
            }
            RemoteAction.UNKNOWN -> {
                WatchHub.log("Saatten bilinmeyen kumanda paketi; yalnızca loglandı.")
                return
            }
        }
        WatchHub.log("Saatten kumanda uygulandı: $label.")
        if (action != RemoteAction.FIND_PHONE_START && action != RemoteAction.FIND_PHONE_STOP &&
            action != RemoteAction.FIND_PHONE_TOGGLE
        ) {
            main.removeCallbacks(musicStateRunnable)
            main.postDelayed(musicStateRunnable, MUSIC_STATE_DELAY_MS)
        }
    }

    /** Telefonun müzik durumu ve ses düzeyi (12 0C, 12 0D). Haylou Fun bağlanınca ve değişince gönderiyor. */
    private fun sendMusicState() {
        if (!ble.isReady || !Prefs.remoteControl(this)) return
        ble.sendCommand(UteMusic.playState(PhoneRemote.isMusicActive(this)), "müzik durumu (12 0C)")
        ble.sendCommand(UteMusic.volume(PhoneRemote.volumePercent(this)), "ses düzeyi (12 0D)")
    }

    // ---------- Ayar eşitleme ----------

    /** Saat hazırsa gönderilmeyi bekleyen ayarları gönderir. Onay gelmeyenler otomatik yeniden gönderilmez. */
    fun pushSettings() {
        if (!ble.isReady) return
        val sync = SettingsStore.sync(this)
        val todo = sync.nextToSend()
        if (todo.isEmpty()) return
        var sent = 0
        for ((key, value) in todo) {
            val cmd = try { SettingCommands.build(key, value) } catch (e: Exception) { null }
            if (cmd == null) {
                WatchHub.log("Ayar komutu üretilemedi ($key); gönderilmedi.")
                continue
            }
            ble.sendCommand(cmd, "ayar $key")
            sync.markSent(key, value, session, System.currentTimeMillis())
            sent++
        }
        if (sent == 0) return
        SettingsStore.save(this)
        WatchHub.settingsChanged()
        main.postAtTime(Runnable { checkAckTimeouts() }, delayedToken, SystemClock.uptimeMillis() + ACK_CHECK_MS)
    }

    private fun checkAckTimeouts() {
        val sync = SettingsStore.sync(this)
        val changed = sync.expire(System.currentTimeMillis(), ACK_TIMEOUT_MS)
        if (changed.isEmpty()) return
        SettingsStore.save(this)
        changed.forEach { WatchHub.log("Onay gelmedi: $it. Otomatik yeniden gönderilmeyecek.") }
        WatchHub.settingsChanged()
    }

    private fun handleAck(key: String, ackValue: String?) {
        val sync = SettingsStore.sync(this)
        when (sync.onAck(key, session, System.currentTimeMillis(), ackValue)) {
            SettingSync.Ack.MATCHED -> {
                SettingsStore.save(this)
                if (key == ProfileSetting.KEY && !Prefs.profileVerified(this)) {
                    Prefs.setProfileVerified(this, true)
                    WatchHub.log("Profil ilk kez onaylandı; bundan sonra değişiklikler saate kendiliğinden gider.")
                }
                WatchHub.log("Saat ayarı onayladı ($key).")
                WatchHub.settingsChanged()
                // Komut yoldayken kullanıcı değeri değiştirdiyse yeni istek şimdi gider.
                pushSettings()
            }
            SettingSync.Ack.UNMATCHED -> WatchHub.log("Eşleşmeyen onay ($key): hiçbir komuta sayılmadı.")
        }
    }

    // ---------- Sağlık geçmişi ----------

    /** Dünden beri adım, 1,5 sn sonra nabız, includeSleep ise 4 sn sonra uyku geçmişini ister. */
    fun requestHistory(includeSleep: Boolean) {
        if (!ble.isReady) {
            WatchHub.log("Saat hazır değil; geçmiş okunmadı.")
            return
        }
        val y = LocalDate.now().minusDays(1)
        val from = UteDate(y.year, y.monthValue, y.dayOfMonth)
        val s = session
        WatchHub.log("Geçmiş okunuyor (dünden beri${if (includeSleep) ", uyku dahil" else ""}).")
        ble.sendCommand(UteHealth.stepHistory(from), "adım geçmişi (0a)")
        main.postAtTime(Runnable {
            if (session == s && ble.isReady) ble.sendCommand(UteHealth.heartHistory(from), "nabız geçmişi (18 FA)")
        }, delayedToken, SystemClock.uptimeMillis() + 1_500)
        if (includeSleep) {
            main.postAtTime(Runnable {
                if (session == s && ble.isReady) ble.sendCommand(UteHealth.sleepHistory(from), "uyku geçmişi (1d 01)")
            }, delayedToken, SystemClock.uptimeMillis() + 4_000)
        }
    }

    private fun flushPending() {
        val notes = WatchHub.drainPending()
        if (notes.isNotEmpty()) WatchHub.log("Kuyruktaki ${notes.size} bildirim gönderiliyor.")
        notes.forEach { forward(it) }
    }

    // ---------- Ls05Ble.Listener ----------

    override fun onLog(line: String) = WatchHub.log(line)

    override fun onStatus(status: String) = updateStatus(status)

    override fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) =
        WatchHub.deviceFound(device, name, rssi)

    override fun onRawFrame(value: ByteArray) {
        if (HealthStore.add(this, value)) WatchHub.healthChanged()
    }

    override fun onFrame(frame: UteFrame) {
        when (frame) {
            is UteFrame.AlarmAck -> handleAck(AlarmSetting.key(frame.slot), null)
            is UteFrame.DndAck -> handleAck(DndSetting.KEY, DndSetting.fromEcho(frame.bytes)?.encode())
            is UteFrame.ProfileAck -> handleAck(ProfileSetting.KEY, null)
            is UteFrame.WatchKey -> handleWatchKey(frame)
            is UteFrame.Steps -> if (frame.hour.live) {
                val h = frame.hour
                HealthStore.live["${h.date.year}-${h.date.month}-${h.date.day}-${h.hour}"] = h
                while (HealthStore.live.size > MAX_LIVE_STEPS) HealthStore.live.remove(HealthStore.live.keys.first())
                WatchHub.healthChanged()
            }
            is UteFrame.StepHistoryEnd -> {
                Prefs.setLastSync(this, System.currentTimeMillis())
                WatchHub.healthChanged()
            }
            else -> Unit
        }
        WatchHub.frame(frame)
    }

    override fun onReadyChanged(ready: Boolean) {
        // Oturum, gözlemciler haber almadan önce artar ki o sırada yapılan ayar yeni oturuma sayılsın.
        if (ready) session++
        WatchHub.setReady(ready)
        if (!ready) return
        reconnectAttempt = 0
        authTimeouts = 0
        if (Prefs.autoTime(this)) sendTime("otomatik saat eşitleme (04)")
        // Bağlantı koparken saat çalıyor kaldıysa ve arama artık sürmüyorsa sustur.
        if (callOnWatch && !WatchHub.calls.isRinging) sendCallStop("bağlantı yeniden kuruldu, arama sürmüyor")
        flushPending()
        sendMusicState()
        pushSettings()
        if (System.currentTimeMillis() - Prefs.lastSync(this) > HISTORY_STALE_MS) requestHistory(true)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
        val sync = SettingsStore.sync(this)
        if (sync.onDisconnect().isNotEmpty()) {
            SettingsStore.save(this)
            WatchHub.settingsChanged()
        }
        WatchHub.setReady(false)
        if (restarting) return
        if (authBlocked) {
            updateStatus("Doğrulama durdu; uygulamayı aç")
            return
        }
        if (stopping || userInitiated) {
            updateStatus("Bağlı değil")
            return
        }
        scheduleReconnect()
    }

    override fun onAuthResult(code: Int?) {
        if (code != null && code != 1 && code != -1) {
            // Saat yeniden bağlama istiyor ya da reddediyor: sıfırlanmış olabilir, ayarları belirsiz say.
            SettingsStore.sync(this).markAllUnknown()
            SettingsStore.save(this)
            WatchHub.settingsChanged()
            WatchHub.log("Saat doğrulamada $code döndü; sıfırlanmış olabilir. Ayarlar 'durum bilinmiyor' olarak işaretlendi, otomatik gönderilmeyecek.")
        }
        when (code) {
            1 -> authTimeouts = 0
            null -> {
                authTimeouts++
                if (authTimeouts >= MAX_AUTH_TIMEOUTS) {
                    blockAuth("Doğrulama $MAX_AUTH_TIMEOUTS kez yanıtsız kaldı. Haylou Fun açıksa kapat, sonra uygulamadan yeniden bağlan.")
                }
            }
            -1 -> blockAuth("Kimlik girilmemiş. Uygulamada kimliği gir ve yeniden bağlan.")
            4 -> blockAuth("Saat onay bekliyor (durum 4). Otomatik deneme durduruldu; uygulamayı aç.")
            else -> blockAuth("Saat doğrulamayı reddetti (durum $code). Otomatik deneme durduruldu; kimliği kontrol et.")
        }
    }

    private fun blockAuth(message: String) {
        authBlocked = true
        main.removeCallbacks(reconnectRunnable)
        WatchHub.log(message)
        updateStatus("Doğrulama durdu; uygulamayı aç")
        if (ble.isConnected) ble.disconnect()
    }

    // ---------- Ön plan bildirimi ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Saat bağlantısı", NotificationManager.IMPORTANCE_LOW)
        ch.description = "LS05 bağlantı durumu"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("LS05 saat")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Durdur", stop).build())
            .build()
    }

    private fun goForeground(): Boolean = try {
        val n = buildNotification(WatchHub.status)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        WatchHub.log("Ön plan servisi başlatılamadı: ${e.javaClass.simpleName}. Bluetooth iznini kontrol et.")
        stopSelf()
        false
    }

    private fun updateStatus(text: String) {
        WatchHub.setStatus(text)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    /** Saatteki kırmızı düğme (12 02). Ayar açık ve izin verilmişse telefondaki aramayı reddeder. */
    @Suppress("DEPRECATION")
    private fun rejectCallFromWatch() {
        if (!Prefs.rejectFromWatch(this)) {
            WatchHub.log("Saat aramayı reddetmek istedi (12 02). \"Saatten aramayı reddet\" kapalı; yok sayıldı.")
            return
        }
        if (android.os.Build.VERSION.SDK_INT < 28 ||
            checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            WatchHub.log("Saat aramayı reddetmek istedi, ama arama izni yok. Bildirimler sekmesinden izin ver.")
            return
        }
        val ok = try {
            getSystemService(android.telecom.TelecomManager::class.java)?.endCall() ?: false
        } catch (e: Exception) {
            WatchHub.log("Arama reddedilemedi: ${e.javaClass.simpleName}")
            false
        }
        WatchHub.log(if (ok) "Arama saatten reddedildi." else "Reddedilecek arama bulunamadı.")
    }
}
