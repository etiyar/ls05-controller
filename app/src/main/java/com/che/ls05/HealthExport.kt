package com.che.ls05

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.che.ls05.protocol.HealthCsv
import java.time.LocalDate

/**
 * Sağlık verisini dışa aktarma (v0.10): adim.csv, nabiz.csv, uyku.csv tek bir zip içinde.
 * Yer kullanıcıya ACTION_CREATE_DOCUMENT ile seçtirilir. Dosyalar arka planda okunur ve yazılır.
 * Sağlık değerleri loga yazılmaz.
 */
object HealthExport {

    const val REQ = 61

    /** Aralık seçenekleri: etiket ve gün sayısı. "Tümü" saklama süresi kadardır. */
    val RANGES = listOf("Son 7 gün" to 7, "Son 30 gün" to 30, "Tümü (90 gün)" to 90)

    private const val ALL_DAYS = 90
    private val main = Handler(Looper.getMainLooper())

    /** Dosya seçici açıkken seçilen aralık. Süreç yeniden kurulursa "Tümü" kullanılır. */
    private var pendingDays = 0
    private var busy = false

    fun start(act: Activity, days: Int) {
        if (busy) {
            Toast.makeText(act, "Dışa aktarma sürüyor.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingDays = days
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, HealthCsv.fileName(LocalDate.now()))
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(i, REQ)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(act, "Dosya kaydetme ekranı açılamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    /** MainActivity.onActivityResult buraya yönlendirir. */
    fun onResult(act: Activity, resultCode: Int, data: Intent?) {
        val days = pendingDays.takeIf { it > 0 } ?: ALL_DAYS
        pendingDays = 0
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        if (busy) return
        busy = true
        val app = act.applicationContext
        val read = HealthStore.reader(app, days.toLong())
        val to = LocalDate.now()
        val from = to.minusDays(days - 1L)
        Thread({
            val err = try {
                val files = HealthCsv.files(read(), from, to)
                val out = app.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("akış yok")
                out.use { HealthCsv.writeZip(it, files, System.currentTimeMillis()) }
                null
            } catch (e: Throwable) {
                e.javaClass.simpleName
            }
            main.post {
                busy = false
                if (err == null) {
                    Toast.makeText(app, "Dışa aktarıldı: 3 dosya", Toast.LENGTH_SHORT).show()
                } else {
                    WatchHub.log("Sağlık verisi dışa aktarılamadı: $err")
                    Toast.makeText(app, "Dışa aktarılamadı.", Toast.LENGTH_SHORT).show()
                }
            }
        }, "saglik-disa-aktar").start()
    }
}
