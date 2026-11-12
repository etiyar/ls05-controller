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
import com.che.ls05.protocol.MSG_TYPE_GENERIC
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

        /** Bağlantı kapandı. userInitiated: kullanıcı kesti. status: GATT durum kodu. */
        fun onDisconnected(userInitiated: Boolean, status: Int) {}

        /** Doğrulama sonucu: 1 kabul, 4 saatte onay bekleniyor, diğer kodlar ret; null zaman aşımı; -1 kimlik yok. */
        fun onAuthResult(code: Int?) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var gatt: BluetoothGatt? = null
    private var batteryChar: BluetoothGattCharacteristic? = null

    /** Aday komut kanalı: yazma karakteristiği ve adı. */
    private class Channel(val name: String, val write: BluetoothGattCharacteristic)

    /**
     * Gate testi 2 kararı (build-5 logu): A (55FF/33F1) pil sorgusuna yanıt vermedi.
     * 33F1 yalnızca WRITE destekliyor, oysa kayıttaki komutlar Write Command (0x52) idi;
     * handle 0x22/0x24 ve saatin 6002'den kendiliğinden veri göndermesi B'yi gösteriyor.
     * Bu testte YALNIZCA B (60FF/6001 -> 6002) denenir. Otomatik kanal değiştirme yok.
     */
    private val probeOnly = "B "

    /**
     * Kullanıcının Haylou kimliği (4 bayt). Kişisel veri: koda yazılmaz, uygulamada girilir.
     * Doğrulama (20 02 + kimlik) olmadan saat komutlara cevap vermez ve ~30 sn sonra bağlantıyı keser.
     */
    var userId: ByteArray? = null

    private var candidates: List<Channel> = emptyList()
    private var active: Channel? = null
    private var probeIndex = -1
    private var pendingCccd = 0
    private var bindChannel: Channel? = null
    private var bindSerial = 0
    private var configReads: List<BluetoothGattCharacteristic> = emptyList()

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
    private var userDisconnect = false
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

    fun connect(device: BluetoothDevice, autoConnect: Boolean = false) {
        if (gatt != null) {
            log("Zaten bir cihaza bağlı. Önce bağlantıyı kes. (Tek cihaz kuralı)")
            return
        }
        stopScan()
        log("Bağlanılıyor: ${maskMac(device.address)}, bond=${bondText(device.bondState)}")
        status("Bağlanılıyor…")
        userDisconnect = false
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val g = gatt ?: return
        log("Bağlantı kullanıcı tarafından kesiliyor.")
        userDisconnect = true
        g.disconnect()
        main.postDelayed({
            if (gatt === g) {
                closeGatt("kullanıcı kesti")
                listener.onDisconnected(true, 0)
            }
        }, 1_000)
    }

    /** Bluetooth kapandığında vb. sessizce kapatır; yeniden bağlanma kararı servise aittir. */
    fun forceClose(reason: String) {
        if (gatt == null) return
        closeGatt(reason)
        listener.onDisconnected(false, -1)
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
        bindChannel = null
        bindSerial++
        configReads = emptyList()
        queue.clear()
        current = null
        listener.onReadyChanged(false)
        status("Bağlı değil")
        userDisconnect = false
        log("Bağlantı kapandı ($reason).")
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
                        val user = userDisconnect
                        if (gatt != null) {
                            closeGatt("koptu")
                            listener.onDisconnected(user, statusCode)
                        }
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

        configReads = listOfNotNull(
            ch(UteUuids.CMD_A_SERVICE, UteUuids.CMD_A_WRITE),
            ch(UteUuids.TRANSFER_SERVICE, UteUuids.TRANSFER_WRITE),
        ).filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }

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

    /**
     * Haylou Fun'ın bağlanma sırası (haylou_notification_test.log, kayıt 23-32):
     * 33F1 ve 34F1 yapılandırma okumaları -> 6001'e 20 02 <kimlik> -> 6002'den 20 04 01 -> komutlar.
     */
    private fun startProbe() {
        if (active != null || bindChannel != null) return
        val c = candidates.firstOrNull { it.name.startsWith(probeOnly) }
        if (c == null) {
            status("Kanal ${probeOnly.trim()} yok")
            log("Kanal bulunamadı; hiçbir komut yazılmadı. GATT haritasını gönder.")
            return
        }
        val id = userId
        if (id == null) {
            status("Kimlik girilmedi")
            log("Doğrulama için Haylou kimliği gerekli. Üstteki alana 8 haneli kimliği yaz, bağlantıyı kesip yeniden bağlan. Hiçbir komut yazılmadı.")
            listener.onAuthResult(-1)
            return
        }
        bindChannel = c
        configReads.forEach { enqueue(Op.Read(it, "yapılandırma oku ${shortUuid(it.uuid)}")) }
        status("Doğrulanıyor…")
        log("Doğrulama: ${c.name} üzerinden 20 02 + kimlik gönderiliyor (Haylou Fun ile aynı sıra).")
        enqueue(Op.Write(c.write, UteCommand.bindRequest(id), "doğrulama (20 02)"))
        waitForBind(5_000, "5 sn")
    }

    private fun waitForBind(ms: Long, label: String) {
        val serial = ++bindSerial
        main.postDelayed({
            if (active == null && bindChannel != null && bindSerial == serial && gatt != null) {
                status("Doğrulama yanıtı yok")
                log("SONUÇ: $label içinde 20 04 yanıtı gelmedi. Eşleştirmeyi SİLME. Haylou Fun açıksa kapat.")
                listener.onAuthResult(null)
            }
        }, ms)
    }

    private fun handleAuth(code: Int) {
        val c = bindChannel ?: return
        listener.onAuthResult(code)
        when (code) {
            1 -> becameReady(c)
            4 -> {
                status("Saatte onay bekleniyor")
                log("Saat 4 döndü: saat ekranında onay istiyor olabilir (Haylou Fun 35 sn bekler). Saatin ekranına bak; bilmediğin bir onayı verme, önce bana sor.")
                waitForBind(35_000, "35 sn")
            }
            else -> {
                bindSerial++
                status("Doğrulama reddedildi ($code)")
                log("SONUÇ: saat doğrulamayı reddetti (durum $code). Kimliği kontrol et. Eşleştirmeyi SİLME. Logu gönder.")
            }
        }
    }

    private fun becameReady(c: Channel) {
        bindSerial++
        active = c
        status("Hazır (${c.name}, doğrulandı)")
        listener.onReadyChanged(true)
        log("KANIT: saat doğrulamayı kabul etti (20 04 01). Bağımsız bağlantı çalışıyor. Firmware ve pil okunuyor.")
        sendCommand(UteCommand.firmwareRequest(), "firmware sorgu")
        sendCommand(UteCommand.batteryRequest(), "pil sorgu")
    }

    private fun handleRead(ch: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
        if (ch.uuid == batteryChar?.uuid && value.isNotEmpty()) {
            log("Standart pil (2A19): %${value[0].toInt() and 0xFF} (status=$statusCode)")
        } else {
            log("Okuma ${shortUuid(ch.uuid)}: ${Hex.of(value)} (status=$statusCode)")
        }
        if (current is Op.Read) opDone()
    }

    private var lastLiveLog = 0L

    private fun handleNotify(ch: BluetoothGattCharacteristic, value: ByteArray) {
        val frame = UteParser.parse(value)
        // Yürürken saat her saniye canlı adım yollar; logu boğmasın diye dakikada bir yazılır.
        if (frame is UteFrame.Steps && frame.hour.live) {
            val now = System.currentTimeMillis()
            if (now - lastLiveLog >= 60_000) {
                lastLiveLog = now
                log("<- canlı adım: bu saat ${frame.hour.total} (dakikada bir loglanır)")
            }
            listener.onFrame(frame)
            return
        }
        log("<- ${shortUuid(ch.uuid)}: ${Hex.redacted(value)}")
        if (frame is UteFrame.AuthStatus && active == null) handleAuth(frame.code)
        when (frame) {
            is UteFrame.Battery -> log("   = Pil %${frame.percent}")
            is UteFrame.Firmware -> log("   = Firmware ${frame.version}")
            is UteFrame.NotificationSegmentAck -> log("   = Bildirim segment ${frame.segment} onayı")
            is UteFrame.NotificationTransferDone -> log("   = Bildirim aktarımı tamam (tip ${frame.type}, uzunluk ${frame.length}). Ekranda görünüp görünmediğini sen onayla.")
            is UteFrame.VibrateAck -> log("   = Titreşim onayı")
            is UteFrame.TimeAck -> log("   = Saat/tarih saat tarafından onaylandı")
            is UteFrame.AuthStatus -> log("   = Saat yetki/bağlama durumu bildirdi: ${frame.code} (anlamı doğrulanmadı)")
            is UteFrame.AlarmAck -> log("   = Alarm yuvası ${frame.slot} saat tarafından alındı")
            is UteFrame.DndAck -> log("   = Rahatsız etme ayarı alındı (${if (frame.enabled) "açık" else "kapalı"})")
            is UteFrame.WeatherAck -> log("   = Hava paketi ${frame.part} alındı")
            is UteFrame.DeviceStatus -> log("   = Saat durumu: rahatsız etme ${if (frame.dndOn) "açık" else "kapalı"} (bayrak 0x%02X, yorum orta güvenli)".format(frame.flags))
            is UteFrame.Steps -> log("   = Adım ${frame.hour.date.day}.${frame.hour.date.month} saat ${frame.hour.hour}: ${frame.hour.total}" + if (frame.hour.consistent) "" else " (toplam tutmuyor!)")
            is UteFrame.StepHistoryEnd -> log("   = Adım geçmişi bitti")
            is UteFrame.HeartLive -> log("   = Nabız %02d:%02d → ${frame.bpm}".format(frame.hour, frame.minute))
            is UteFrame.HeartSummary -> log("   = Nabız özeti: en yüksek ${frame.max}, en düşük ${frame.min}, ortalama ${frame.avg}")
            is UteFrame.HeartBlockFrame -> log("   = Nabız bloğu ${frame.block.date.day}.${frame.block.date.month} saat ${frame.block.endHour}: ${frame.block.values.count { it != null }} ölçüm")
            is UteFrame.HeartHistoryEnd -> log("   = Nabız geçmişi bitti")
            is UteFrame.Echo -> Unit
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
    fun sendNotification(title: String, body: String, vibrateAfter: Boolean, type: Int = MSG_TYPE_GENERIC): Int {
        val c = active ?: run { log("Hazır değil, bildirim gönderilmedi."); return 0 }
        val packets = NotificationPacketizer.build(title, body, type)
        log("Bildirim: ${packets.size} paket (${packets.size - 1} veri + bitiş)${if (vibrateAfter) " + titreşim" else ", titreşimsiz"}. Metin loga yazılmaz.")
        packets.forEachIndexed { i, p ->
            enqueue(Op.Write(c.write, p, "bildirim paket ${i + 1}/${packets.size}", gapAfterMs = 50))
        }
        // Kayıttaki sıra: 0F FD'den hemen sonra mesaj titreşimi. İsteğe bağlı.
        if (vibrateAfter) enqueue(Op.Write(c.write, UteCommand.VIBRATE_TEXT, "mesaj titreşimi (06)", gapAfterMs = 300))
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
