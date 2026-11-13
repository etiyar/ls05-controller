package com.che.ls05

import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.FaceConfig
import com.che.ls05.protocol.FaceFile
import com.che.ls05.protocol.FaceTransfer

/**
 * Kadran yükleme (kapı 2). Akış Haylou Fun SendPlateTask ile aynı:
 * MTU 247 → 1a 01 (ekran 240x240 mı) → 1a 02 → 4 KB'lık bloklar, her blokta 26 03 04 → 1a 03 00.
 *
 * Kurallar: kullanıcı başlatır, otomatik yeniden deneme yok. Her bekleme 10 sn ile sınırlı.
 * Aktarım sürerken bildirim, ayar ve geçmiş isteği saate gitmez; bildirimler kuyrukta bekler.
 */
class FaceUpload(private val ble: Ls05Ble, private val onEnd: () -> Unit) {

    enum class Phase { IDLE, MTU, CONFIG, START, SENDING, FINISHING, DONE, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var transfer: FaceTransfer? = null
    private var file: FaceFile? = null
    private var serial = 0
    private var startedAt = 0L

    var phase = Phase.IDLE
        private set
    val active: Boolean get() = phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.FAILED

    companion object {
        const val MTU = 247
        const val MIN_BATTERY = 40
        const val WAIT_MS = 10_000L
        /** Son blok onayı gelmezse Haylou Fun 1 sn sonra bitişi gönderiyor. */
        const val FINAL_GRACE_MS = 3_000L
    }

    /** Başlatılamazsa nedeni döner. */
    fun start(face: FaceFile): String? {
        if (active) return "Yükleme zaten sürüyor."
        if (!ble.isReady) return "Saat bağlı değil."
        if (!ble.canSendFace) return "Saatte kadran kanalı bulunamadı."
        val bat = WatchHub.battery
        if (bat == null || bat < MIN_BATTERY) return "Saat pili en az %$MIN_BATTERY olmalı (şu an ${bat?.let { "%$it" } ?: "bilinmiyor"})."
        file = face
        transfer = null
        startedAt = System.currentTimeMillis()
        ble.quietFace = true
        report(Phase.MTU, 0, "Bağlantı hazırlanıyor…")
        WatchHub.log("Kadran yükleme başladı: ${face.size / 1024} KB, ${face.elements.size} öğe.")
        ble.requestMtu(MTU)
        // MTU geri çağrısı (ya da 3 sn zaman aşımı) kuyruğu boşaltınca ekran sorgusu.
        val s = ++serial
        var waited = 0
        main.postDelayed(object : Runnable {
            override fun run() {
                if (serial != s || phase != Phase.MTU) return
                waited += 250
                if (ble.pendingOps > 0 && waited < 5_000) main.postDelayed(this, 250) else askConfig()
            }
        }, 250)
        return null
    }

    private fun askConfig() {
        // Kayıttaki gibi 244 baytlık paket (saat 0x28 okumasında 00 f4 = 244 bildiriyor). İlk sürümde başka boy denenmez.
        if (ble.mtu < MTU) {
            fail("Bağlantı 247 bayta yükseltilemedi (${ble.mtu}). Yükleme başlamadı, saate veri gitmedi.")
            return
        }
        val packet = MTU - 3
        transfer = FaceTransfer(file!!.bytes, packet)
        report(Phase.CONFIG, 0, "Saat soruluyor…")
        ble.sendCommand(FaceTransfer.QUERY, "kadran yapılandırma (1a 01)")
        waitFor(Phase.CONFIG)
    }

    /** Saatten gelen her ham paket buraya da verilir. */
    fun onRaw(v: ByteArray) {
        if (!active || v.size < 2) return
        val t = transfer ?: return
        when (phase) {
            Phase.CONFIG -> {
                val screen = FaceConfig.screen(v) ?: return
                val f = file!!
                if (screen != f.width to f.height) {
                    fail("Saat ekranı ${screen.first}x${screen.second}, kadran ${f.width}x${f.height}. Saate veri gitmedi.")
                    return
                }
                report(Phase.START, 0, "Yükleme başlatılıyor…")
                ble.sendCommand(FaceTransfer.START, "kadran başlat (1a 02)")
                waitFor(Phase.START)
            }
            Phase.START -> {
                if (v[0] != 0x1a.toByte() || v[1] != 0x02.toByte()) return
                val s = t.begin()
                report(Phase.SENDING, 0, "Yükleniyor %0")
                send(s.from, s.to)
            }
            Phase.SENDING, Phase.FINISHING -> handle(t.onReply(v))
            else -> Unit
        }
    }

    private fun handle(step: FaceTransfer.Step) {
        val t = transfer ?: return
        when (step) {
            is FaceTransfer.Step.Send -> {
                report(Phase.SENDING, t.percent, "Yükleniyor %${t.percent}")
                send(step.from, step.to)
            }
            FaceTransfer.Step.Finish -> {
                report(Phase.FINISHING, 100, "Saat kadranı kaydediyor…")
                main.postDelayed({
                    if (phase == Phase.FINISHING) {
                        ble.sendCommand(FaceTransfer.END, "kadran bitiş (1a 03 00)")
                        waitFor(Phase.FINISHING)
                    }
                }, 1_000)
            }
            FaceTransfer.Step.Done -> {
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                report(Phase.DONE, 100, "Kadran yüklendi ($sec sn). Saatte görünüyor mu kontrol et.")
                WatchHub.log("Kadran yükleme TAMAM ($sec sn, ${t.total} paket).")
                end()
            }
            is FaceTransfer.Step.Fail -> fail("${step.reason} Yükleme durdu; otomatik yeniden denenmez.")
            FaceTransfer.Step.Ignore -> Unit
        }
    }

    private fun send(from: Int, to: Int) {
        val t = transfer ?: return
        if (!ble.sendFacePackets(t.packets, from, to)) {
            fail("Kadran kanalı kayboldu.")
            return
        }
        waitFor(phase, last = to >= t.total)
    }

    /** Yanıt beklenir. Son blokta onay gelmezse Haylou Fun gibi bitişe geçilir. */
    private fun waitFor(p: Phase, last: Boolean = false) {
        val s = ++serial
        main.postDelayed(object : Runnable {
            override fun run() {
                if (serial != s || phase != p) return
                // Paketler hâlâ kuyruktaysa süre işlemez.
                if (p == Phase.SENDING && ble.pendingOps > 0) {
                    main.postDelayed(this, 1_000)
                    return
                }
                if (p == Phase.SENDING && last) {
                    WatchHub.log("Son blok onayı gelmedi; bitiş gönderiliyor (Haylou Fun da böyle yapıyor).")
                    handle(transfer!!.forceFinish())
                    return
                }
                fail("Saat ${WAIT_MS / 1000} sn yanıt vermedi (${p.name}). Yükleme durdu.")
            }
        }, if (last) FINAL_GRACE_MS else WAIT_MS)
    }

    /** Bağlantı koparsa. */
    fun onDisconnected() {
        if (active) fail("Bağlantı koptu. Saat eski kadranda kalmış olmalı; kontrol et.")
    }

    private fun fail(reason: String) {
        report(Phase.FAILED, transfer?.percent ?: 0, reason)
        WatchHub.log("Kadran yükleme HATA: $reason")
        end()
    }

    private fun end() {
        serial++
        ble.quietFace = false
        transfer = null
        file = null
        onEnd()
    }

    private fun report(p: Phase, percent: Int, text: String) {
        phase = p
        WatchHub.setFace(p, percent, text)
    }
}
