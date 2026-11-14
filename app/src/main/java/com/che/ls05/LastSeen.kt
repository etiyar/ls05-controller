package com.che.ls05

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.LastSeenRules
import com.che.ls05.protocol.SeenFix

/**
 * Son görülen yer: bağlantı beklenmedik koptuğu anda telefonun konumu.
 *
 * - API 30+ anlık konum en çok 10 sn beklenir; gelmezse ya da API eskiyse NETWORK, PASSIVE ve GPS
 *   sağlayıcılarının son konumlarından en yenisi alınır, yalnızca son 5 dakika içindeyse.
 * - Yalnızca son kayıt Prefs'te tutulur. Konum loga yazılmaz.
 * - Arka plan konum izni istenmez; servis türüne "location" yalnızca izin varken eklenir (WatchService).
 */
object LastSeen {

    private val main = Handler(Looper.getMainLooper())

    /** Bu çalıştırmada kayıt sürüyor mu (üst üste kopmalar tek kayıt başlatır). */
    private var busy = false

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFine(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Ayar açık ve izin var. */
    fun active(c: Context): Boolean = Prefs.lastSeenEnabled(c) && hasPermission(c)

    /** Beklenmeyen kopma anında servis çağırır. Ayar kapalıysa bir şey yapmaz. */
    fun capture(c: Context) {
        if (!Prefs.lastSeenEnabled(c)) return
        if (!hasPermission(c)) {
            WatchHub.log("Konum izni yok; son görülen yer kaydedilmedi.")
            return
        }
        if (busy) return
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        busy = true
        val at = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = currentProvider(c, lm)
            if (provider != null) {
                requestCurrent(c, lm, provider, at)
                return
            }
        }
        finish(c, lm, at, null)
    }

    /** Anlık konum için sağlayıcı: birleşik (API 31+), yoksa ağ, yoksa (kesin izin varsa) GPS. */
    private fun currentProvider(c: Context, lm: LocationManager): String? {
        fun on(p: String) = try { lm.isProviderEnabled(p) } catch (_: Exception) { false }
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) && on(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER
        }
        if (on(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER
        if (hasFine(c) && on(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER
        return null
    }

    @android.annotation.TargetApi(30)
    private fun requestCurrent(c: Context, lm: LocationManager, provider: String, at: Long) {
        val cancel = CancellationSignal()
        var done = false
        val timeout = Runnable {
            if (done) return@Runnable
            done = true
            cancel.cancel()
            finish(c, lm, at, null)
        }
        try {
            lm.getCurrentLocation(provider, cancel, c.mainExecutor) { loc ->
                if (done) return@getCurrentLocation
                done = true
                main.removeCallbacks(timeout)
                finish(c, lm, at, loc)
            }
            main.postDelayed(timeout, LastSeenRules.CURRENT_WAIT_MS)
        } catch (e: Exception) {
            // SecurityException (arka planda izin kısıtı) ya da sağlayıcı hatası: son konumlara düşülür.
            done = true
            finish(c, lm, at, null)
        }
    }

    private fun lastKnown(lm: LocationManager, provider: String): Location? = try {
        lm.getLastKnownLocation(provider)
    } catch (_: Exception) {
        null
    }

    private fun Location.toFix(): SeenFix = SeenFix(latitude, longitude, if (hasAccuracy()) accuracy else 0f, time)

    private fun finish(c: Context, lm: LocationManager, at: Long, current: Location?) {
        busy = false
        val now = System.currentTimeMillis()
        val candidates = ArrayList<SeenFix?>()
        candidates += current?.toFix()
        if (current == null) {
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
                candidates += lastKnown(lm, p)?.toFix()
            }
        }
        val best = LastSeenRules.pick(candidates, now)
        // Ayar bu arada kapatıldıysa kaydetme.
        if (!Prefs.lastSeenEnabled(c)) return
        if (best == null) {
            WatchHub.log("Son görülen yer alınamadı (son 5 dakikada konum yok).")
            return
        }
        // Zaman kopma anıdır; konum en çok 5 dakika daha eski olabilir.
        Prefs.setLastSeen(c, best.copy(at = at))
        WatchHub.log("Son görülen yer kaydedildi.")
        WatchHub.settingsChanged()
    }

    /** Kopma saatini ve doğruluğu taşıyan kısa metin. */
    fun label(f: SeenFix): String {
        val t = Fmt.local(f.at)
        return LastSeenRules.label(t.hour, t.minute, f.accuracy)
    }

    fun mapIntent(f: SeenFix): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(LastSeenRules.geoUri(f)))

    /** Haritada açar. Harita uygulaması yoksa false. */
    fun openMap(c: Context, f: SeenFix): Boolean = try {
        c.startActivity(mapIntent(f))
        true
    } catch (_: Exception) {
        false
    }
}
