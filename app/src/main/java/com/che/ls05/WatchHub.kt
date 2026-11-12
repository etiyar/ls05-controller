package com.che.ls05

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.HealthSummary
import com.che.ls05.protocol.StepHour
import com.che.ls05.protocol.UteDate
import com.che.ls05.protocol.UteFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servis, ekran ve bildirim dinleyici arasında ortak durum. Tüm çağrılar ana iş parçacığında işlenir.
 * Log yalnızca bellekte tutulur; bildirim içerikleri ve kimlik loga yazılmaz.
 */
object WatchHub {

    interface Observer {
        fun onLog(line: String) {}
        fun onStatus(status: String) {}
        fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) {}
        fun onFrame(frame: UteFrame) {}
        fun onReadyChanged(ready: Boolean) {}
    }

    /** İletilecek bildirim. İçerik yalnızca bellekte ve kısa süre tutulur. */
    data class Note(
        val pkg: String,
        val appLabel: String,
        val title: String,
        val body: String,
        val type: Int,
        val at: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val observers = LinkedHashSet<Observer>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val logLines = ArrayDeque<String>()
    val pending = ArrayDeque<Note>()

    var status: String = "Bağlı değil"
        private set
    var ready: Boolean = false
        private set

    /** Çalışan servis; yoksa null. */
    var service: WatchService? = null

    private const val MAX_LOG = 2_000
    private const val MAX_PENDING = 20
    const val PENDING_TTL_MS = 10 * 60 * 1000L

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun addObserver(o: Observer) = onMain { observers += o }
    fun removeObserver(o: Observer) = onMain { observers -= o }

    fun log(line: String) = onMain {
        val stamped = "${fmt.format(Date())}  $line"
        logLines.addLast(stamped)
        while (logLines.size > MAX_LOG) logLines.removeFirst()
        observers.toList().forEach { it.onLog(stamped) }
    }

    fun setStatus(s: String) = onMain {
        status = s
        observers.toList().forEach { it.onStatus(s) }
    }

    fun setReady(r: Boolean) = onMain {
        ready = r
        observers.toList().forEach { it.onReadyChanged(r) }
    }

    fun deviceFound(device: BluetoothDevice, name: String, rssi: Int) = onMain {
        observers.toList().forEach { it.onDeviceFound(device, name, rssi) }
    }

    /** Sağlık verisi yalnızca bellekte tutulur (v0.3a'da kalıcı kayıt gelecek). */
    val steps = LinkedHashMap<String, StepHour>()
    var lastHeart: UteFrame.HeartLive? = null
    var heartSummary: UteFrame.HeartSummary? = null

    fun stepsToday(): Int? {
        val c = java.util.Calendar.getInstance()
        val today = UteDate(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        if (steps.values.none { it.date == today }) return null
        return HealthSummary.stepsForDay(today, steps.values)
    }

    fun frame(f: UteFrame) = onMain {
        when (f) {
            is UteFrame.Steps -> {
                val h = f.hour
                steps["${h.date.year}-${h.date.month}-${h.date.day}-${h.hour}"] = h
                while (steps.size > 24 * 8) steps.remove(steps.keys.first())
            }
            is UteFrame.HeartLive -> lastHeart = f
            is UteFrame.HeartSummary -> heartSummary = f
            else -> Unit
        }
        observers.toList().forEach { it.onFrame(f) }
    }

    /** Bildirim dinleyiciden gelir. Saat hazır değilse kısa kuyruğa alınır. */
    fun forward(context: Context, note: Note) = onMain {
        val s = service
        if (s != null && s.isWatchReady()) {
            s.forward(note)
            return@onMain
        }
        pending.addLast(note)
        while (pending.size > MAX_PENDING) pending.removeFirst()
        log("Saat hazır değil; bildirim kuyruğa alındı (${note.appLabel}, kuyruk ${pending.size}).")
        if (s == null && Prefs.serviceEnabled(context) && Prefs.address(context) != null) {
            WatchService.start(context)
        }
    }

    /** Kuyrukta bekleyen ve 10 dakikadan eski olmayan bildirimleri sırayla verir. */
    fun drainPending(now: Long = System.currentTimeMillis()): List<Note> {
        val fresh = pending.filter { now - it.at <= PENDING_TTL_MS }
        val dropped = pending.size - fresh.size
        pending.clear()
        if (dropped > 0) log("Kuyruktaki $dropped eski bildirim atıldı (10 dakikadan eski).")
        return fresh
    }
}
