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
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.UteFrame
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

        fun start(context: Context, action: String = ACTION_START, address: String? = null) {
            val i = Intent(context, WatchService::class.java).setAction(action)
            if (address != null) i.putExtra(EXTRA_ADDRESS, address)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                WatchHub.log("Servis başlatılamadı: ${e.javaClass.simpleName}. Uygulamayı açıp tekrar dene.")
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

    private val reconnectRunnable = Runnable { connectSaved(autoConnect = true) }

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
        WatchHub.service = this
        createChannel()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onDestroy() {
        main.removeCallbacks(reconnectRunnable)
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
        val sent = ble.sendNotification(note.title, note.body, Prefs.vibrate(this), note.type)
        if (sent > 0) {
            WatchHub.log("İletildi: ${note.appLabel} (tip ${note.type}, ${note.title.length + note.body.length} karakter).")
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

    override fun onFrame(frame: UteFrame) = WatchHub.frame(frame)

    override fun onReadyChanged(ready: Boolean) {
        WatchHub.setReady(ready)
        if (!ready) return
        reconnectAttempt = 0
        authTimeouts = 0
        if (Prefs.autoTime(this)) sendTime("otomatik saat eşitleme (04)")
        flushPending()
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
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
}
