package com.che.ls05

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.Hex
import com.che.ls05.protocol.NotificationPacketizer
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.UteFrame
import com.che.ls05.protocol.UteParser
import com.che.ls05.protocol.UteUuids
import java.util.UUID

/**
 * v0.1 BLE katmanı.
 *
 * Kurallar:
 * - Aynı anda tek cihaz. Bağlıyken yeni bağlantı reddedilir.
 * - Otomatik yeniden bağlanma YOK (aşama 3). Bağlantı koparsa kapanır ve kullanıcı yeniden bağlanır.
 * - Android GATT aynı anda tek işlem kabul eder; tüm yazma/okuma işlemleri sıralı kuyruktan geçer.
 * - Eşleştirme (bond) bu uygulama tarafından asla silinmez veya değiştirilmez.
 */
@SuppressLint("MissingPermission")
class Ls05Ble(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(line: String)
        fun onStatus(status: String)
        fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int)
        fun onFrame(frame: UteFrame)
        fun onReadyChanged(ready: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var gatt: BluetoothGatt? = null
    private var batteryChar: BluetoothGattCharacteristic? = null

    /** Aday komut kanalı: yazma karakteristiği ve adı. */
    private class Channel(val name: String, val write: BluetoothGattCharacteristic)

    /**
     * Gate testi kararı: yalnızca A (55FF/33F1) denenir. B'ye (60FF/6001) otomatik geçiş KAPALI.
     * A yanıt vermezse bağlantı, CCCD ve yetkilendirme incelenir; B ancak ayrı bir kararla açılır.
     */
    private val autoFallbackToB = false

    private var candidates: List<Channel> = emptyList()
    private var active: Channel? = null
    private var probeIndex = -1
    private var pendingCccd = 0

    private sealed class Op {
        abstract val label: String
        class Write(
            val target: BluetoothGattCharacteristic,
            val data: ByteArray,
            override val label: String,
            val gapAfterMs: Long = 0,
        ) : Op()
        class EnableNotify(val ch: BluetoothGattCharacteristic, override val label: String) : Op()
        class Read(val ch: BluetoothGattCharacteristic, override val label: String) : Op()
    }

    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private var opSerial = 0
    private var scanning = false
    private val seen = mutableSetOf<String>()

    val isConnected: Boolean get() = gatt != null
    val isReady: Boolean get() = active != null

    // ---------- Tarama ----------

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            log("Bluetooth kapalı veya yok. Bluetooth'u açıp tekrar dene.")
            return
        }
        if (scanning) return
        seen.clear()
        val scanner = a.bluetoothLeScanner ?: run { log("Tarayıcı alınamadı."); return }
        scanning = true
        status("Taranıyor (10 sn)…")
        log("Tarama başladı. Adında HAYLOU geçen cihazlar listelenecek.")
        scanner.startScan(scanCallback)
        main.postDelayed({ stopScan() }, 10_000)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        log("Tarama bitti.")
        if (!isConnected) status("Bağlı değil")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.contains("HAYLOU", ignoreCase = true)) return
            if (!seen.add(result.device.address)) return
            main.post {
                log("Bulundu: $name, ${maskMac(result.device.address)}, RSSI ${result.rssi} dBm, bond=${bondText(result.device.bondState)}")
                listener.onDeviceFound(result.device, name, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { scanning = false; log("Tarama hatası: $errorCode") }
        }
    }

    // ---------- Bağlantı ----------

    fun connect(device: BluetoothDevice) {
        if (gatt != null) {
            log("Zaten bir cihaza bağlı. Önce bağlantıyı kes. (Tek cihaz kuralı)")
            return
        }
        stopScan()
        log("Bağlanılıyor: ${maskMac(device.address)}, bond=${bondText(device.bondState)}")
        status("Bağlanılıyor…")
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        val g = gatt ?: return
        log("Bağlantı kullanıcı tarafından kesiliyor.")
        g.disconnect()
        main.postDelayed({ closeGatt("kullanıcı kesti") }, 1_000)
    }

    private fun closeGatt(reason: String) {
        val g = gatt ?: return
        g.close()
        gatt = null
        batteryChar = null
        candidates = emptyList()
        active = null
        probeIndex = -1
        pendingCccd = 0
        queue.clear()
        current = null
        listener.onReadyChanged(false)
        status("Bağlı değil")
        log("Bağlantı kapandı ($reason). Otomatik yeniden bağlanma yok.")
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            main.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("Bağlandı (status=$statusCode). Servisler keşfediliyor…")
                        status("Bağlandı, servis keşfi…")
                        main.postDelayed({ gatt?.discoverServices() }, 600)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val hint = if (statusCode == 133) " (133: genel GATT hatası. Haylou uygulaması saati tutuyor olabilir; onu durdurup tekrar dene.)" else ""
                        log("Bağlantı koptu, status=$statusCode$hint")
                        closeGatt("koptu")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            main.post { handleServices(g, statusCode) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) {
            main.post {
                val op = current
                if (op is Op.EnableNotify && op.ch.uuid == d.characteristic.uuid) {
                    log("Dinleme açıldı: ${shortUuid(d.characteristic.uuid)} status=$statusCode")
                    opDone()
                    cccdFinished()
                } else {
                    log("Geç gelen CCCD geri çağrısı yok sayıldı: ${shortUuid(d.characteristic.uuid)}")
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, statusCode: Int) {
            main.post {
                val op = current
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    log("   yazma tamam (${shortUuid(ch.uuid)}, status=0)${if (op is Op.Write) " ${op.label}" else ""}")
                } else {
                    log("   YAZMA HATASI (${shortUuid(ch.uuid)}, status=$statusCode)")
                }
                if (op is Op.Write) opDone()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            main.post { handleRead(ch, value, statusCode) }
        }

        @Deprecated("API 33 öncesi")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, statusCode: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = ch.value ?: ByteArray(0)
            main.post { handleRead(ch, v, statusCode) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { handleNotify(ch, value) }
        }

        @Deprecated("API 33 öncesi")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = ch.value?.copyOf() ?: ByteArray(0)
            main.post { handleNotify(ch, v) }
        }
    }

    private fun handleServices(g: BluetoothGatt, statusCode: Int) {
        if (statusCode != BluetoothGatt.GATT_SUCCESS) {
            log("Servis keşfi başarısız: $statusCode")
            return
        }
        log("--- GATT haritası ---")
        for (s in g.services) {
            log("Servis ${s.uuid}")
            for (c in s.characteristics) {
                log("   char ${c.uuid}  [${propsText(c.properties)}]")
            }
        }
        log("---------------------")

        batteryChar = g.getService(UUID.fromString(UteUuids.BATTERY_SERVICE))
            ?.getCharacteristic(UUID.fromString(UteUuids.BATTERY_LEVEL))

        fun ch(service: String, char: String): BluetoothGattCharacteristic? =
            g.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(char))

        candidates = listOfNotNull(
            ch(UteUuids.CMD_A_SERVICE, UteUuids.CMD_A_WRITE)?.let { Channel("A 55FF/33F1", it) },
            ch(UteUuids.CMD_B_SERVICE, UteUuids.CMD_B_WRITE)?.let { Channel("B 60FF/6001", it) },
        )
        val listeners = listOfNotNull(
            ch(UteUuids.CMD_A_SERVICE, UteUuids.CMD_A_NOTIFY),
            ch(UteUuids.CMD_B_SERVICE, UteUuids.CMD_B_NOTIFY),
            ch(UteUuids.ALT_NOTIFY_SERVICE, UteUuids.ALT_NOTIFY_1),
            ch(UteUuids.ALT_NOTIFY_SERVICE, UteUuids.ALT_NOTIFY_2),
        ).filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }

        log("Aday komut kanalları: ${candidates.joinToString { "${it.name} [${propsText(it.write.properties)}]" }.ifEmpty { "YOK" }}")
        log("Dinlenecek kanallar: ${listeners.joinToString { shortUuid(it.uuid) }.ifEmpty { "YOK" }}, standart pil ${if (batteryChar != null) "VAR" else "YOK"}")

        if (candidates.isEmpty() || listeners.isEmpty()) {
            status("Beklenen UUID'ler yok")
            log("Komut veya dinleme kanalı bulunamadı. GATT haritasını kopyalayıp gönder; hiçbir komut yazılmayacak.")
            return
        }
        pendingCccd = listeners.size
        listeners.forEach { c ->
            g.setCharacteristicNotification(c, true)
            enqueue(Op.EnableNotify(c, "dinlemeyi aç ${shortUuid(c.uuid)}"))
        }
    }

    /** Her dinleme açma işlemi (başarılı, başarısız veya zaman aşımı) bitince çağrılır. */
    private fun cccdFinished() {
        if (pendingCccd <= 0) return
        pendingCccd--
        if (pendingCccd == 0) startProbe()
    }

    /** Komut kanalını zararsız pil sorgusuyla doğrular. Varsayılan: yalnızca A. */
    private fun startProbe() {
        if (active != null) return
        if (probeIndex < 0) {
            val all = candidates
            candidates = if (autoFallbackToB) all else all.filter { it.name.startsWith("A ") }
            log("Yoklama planı: ${candidates.joinToString { it.name }.ifEmpty { "YOK" }} (B'ye otomatik geçiş ${if (autoFallbackToB) "AÇIK" else "KAPALI"})")
            if (candidates.isEmpty()) {
                status("A kanalı (55FF/33F1) yok")
                log("A kanalı bulunamadı; hiçbir komut yazılmadı. GATT haritasını gönder.")
                return
            }
        }
        probeIndex++
        if (probeIndex >= candidates.size) {
            status("A kanalı yanıt vermedi")
            log("SONUÇ: A (55FF/33F1) pil sorgusuna yanıt vermedi. B denenmedi (kapalı). Eşleştirmeyi SİLME. Logu gönder: sıradaki adım bağlantı, CCCD ve yetkilendirme incelemesi.")
            return
        }
        val c = candidates[probeIndex]
        status("Kanal deneniyor: ${c.name}")
        log("Kanal yoklaması ${probeIndex + 1}/${candidates.size}: ${c.name} üzerinden pil sorgusu (03).")
        enqueue(Op.Write(c.write, UteCommand.batteryRequest(), "yoklama pil sorgu (${c.name})"))
        val myIndex = probeIndex
        main.postDelayed({
            if (active == null && probeIndex == myIndex && gatt != null) {
                log("${c.name} 2,5 sn içinde yanıt vermedi.")
                startProbe()
            }
        }, 2_500)
    }

    private fun becameReady(c: Channel) {
        active = c
        status("Hazır (${c.name})")
        listener.onReadyChanged(true)
        log("KANIT: ${c.name} komut kanalı olarak yanıt verdi. Bağımsız okuma çalışıyor.")
        sendCommand(UteCommand.firmwareRequest(), "firmware sorgu")
    }

    private fun handleRead(ch: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
        if (ch.uuid == batteryChar?.uuid && value.isNotEmpty()) {
            log("Standart pil (2A19): %${value[0].toInt() and 0xFF} (status=$statusCode)")
        } else {
            log("Okuma ${shortUuid(ch.uuid)}: ${Hex.of(value)} (status=$statusCode)")
        }
        if (current is Op.Read) opDone()
    }

    private fun handleNotify(ch: BluetoothGattCharacteristic, value: ByteArray) {
        log("<- ${shortUuid(ch.uuid)}: ${Hex.redacted(value)}")
        val frame = UteParser.parse(value)
        if (active == null && frame is UteFrame.Battery && probeIndex in candidates.indices) {
            becameReady(candidates[probeIndex])
        }
        when (frame) {
            is UteFrame.Battery -> log("   = Pil %${frame.percent}")
            is UteFrame.Firmware -> log("   = Firmware ${frame.version}")
            is UteFrame.NotificationSegmentAck -> log("   = Bildirim segment ${frame.segment} onayı")
            is UteFrame.NotificationTransferDone -> log("   = Bildirim aktarımı tamam (tip ${frame.type}, uzunluk ${frame.length}). Ekranda görünüp görünmediğini sen onayla.")
            is UteFrame.VibrateAck -> log("   = Titreşim onayı")
            is UteFrame.AuthStatus -> log("   = Saat yetki/bağlama durumu bildirdi: ${frame.code} (anlamı doğrulanmadı)")
            is UteFrame.Unknown -> log("   = Bilinmeyen çerçeve, yorumlanmadı")
        }
        listener.onFrame(frame)
    }

    // ---------- Komutlar ----------

    fun sendCommand(data: ByteArray, label: String) {
        val c = active ?: run { log("Hazır değil, gönderilmedi: $label"); return }
        enqueue(Op.Write(c.write, data, label))
    }

    fun readStandardBattery() {
        val c = batteryChar ?: run { log("Standart pil karakteristiği yok."); return }
        enqueue(Op.Read(c, "standart pil oku (2A19)"))
    }

    /** Yalnızca kanıtlanmış 0x0F akışı + kayıttaki titreşim. Paketler arası 50 ms boşluk. */
    fun sendNotification(title: String, body: String, vibrateAfter: Boolean): Int {
        val c = active ?: run { log("Hazır değil, bildirim gönderilmedi."); return 0 }
        val packets = NotificationPacketizer.build(title, body)
        log("Bildirim: ${packets.size} paket (${packets.size - 1} veri + bitiş)${if (vibrateAfter) " + titreşim" else ", titreşimsiz"}. Metin loga yazılmaz.")
        packets.forEachIndexed { i, p ->
            enqueue(Op.Write(c.write, p, "bildirim paket ${i + 1}/${packets.size}", gapAfterMs = 50))
        }
        // Kayıttaki sıra: 0F FD'den hemen sonra mesaj titreşimi. İsteğe bağlı.
        if (vibrateAfter) enqueue(Op.Write(c.write, UteCommand.VIBRATE_TEXT, "mesaj titreşimi (06)"))
        return packets.size
    }

    /** Bildirimden bağımsız titreşim. Yalnızca kayıtta görülen mesaj titreşimi kalıbı. */
    fun vibrateText() {
        sendCommand(UteCommand.VIBRATE_TEXT, "mesaj titreşimi (06), tek başına")
    }

    // ---------- Sıralı GATT kuyruğu ----------

    private fun enqueue(op: Op) {
        queue.addLast(op)
        if (current == null) next()
    }

    private fun next() {
        val g = gatt ?: return
        val op = queue.removeFirstOrNull() ?: run { current = null; return }
        current = op
        val serial = ++opSerial
        val ok = when (op) {
            is Op.Write -> {
                log("-> ${shortUuid(op.target.uuid)}: ${Hex.redacted(op.data)}  (${op.label})")
                write(g, op.target, op.data)
            }
            is Op.EnableNotify -> {
                val d = op.ch.getDescriptor(UUID.fromString(UteUuids.CCCD))
                val value = if (op.ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (d == null) { log("CCCD yok: ${op.label}"); false }
                else writeDescriptor(g, d, value)
            }
            is Op.Read -> g.readCharacteristic(op.ch)
        }
        if (!ok) {
            log("İşlem başlatılamadı: ${op.label}")
            current = null
            main.postDelayed({ next() }, 100)
            if (op is Op.EnableNotify) cccdFinished()
            return
        }
        // Geri çağrı hiç gelmezse kuyruk kilitlenmesin.
        main.postDelayed({
            if (current === op && opSerial == serial) {
                log("Zaman aşımı (3 sn): ${op.label}. Sıradakine geçiliyor.")
                opDone()
                if (op is Op.EnableNotify) cccdFinished()
            }
        }, 3_000)
    }

    private fun opDone() {
        val op = current ?: return
        current = null
        val gap = (op as? Op.Write)?.gapAfterMs ?: 0
        if (gap > 0) main.postDelayed({ if (current == null) next() }, gap) else next()
    }

    /** Karakteristik destekliyorsa yanıtsız yazar (kayıttaki 0x52), yoksa normal yazma. */
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = type
                c.value = data
                g.writeCharacteristic(c)
            }
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }

    // ---------- Yardımcılar ----------

    private fun log(line: String) = listener.onLog(line)
    private fun status(s: String) = listener.onStatus(s)

    private fun shortUuid(u: UUID): String = u.toString().substring(4, 8)

    private fun maskMac(mac: String): String = "XX:XX:XX:XX:" + mac.takeLast(5)

    private fun bondText(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "BONDED"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        else -> "NONE"
    }

    private fun propsText(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESP")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }.joinToString(",")
}
