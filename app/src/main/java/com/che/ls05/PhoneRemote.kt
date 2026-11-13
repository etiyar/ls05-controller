package com.che.ls05

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import com.che.ls05.protocol.UteMusic

/**
 * Saatten gelen müzik ve ses kumandasını telefonda uygular. Ek izin gerekmez.
 * Haylou Fun da aynı yolu kullanıyor: AudioManager.dispatchMediaKeyEvent ve adjustStreamVolume(STREAM_MUSIC).
 */
object PhoneRemote {

    private fun audio(c: Context): AudioManager? = c.getSystemService(AudioManager::class.java)

    fun mediaKey(c: Context, keyCode: Int) {
        val am = audio(c) ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun adjustVolume(c: Context, up: Boolean) {
        val am = audio(c) ?: return
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    fun volumePercent(c: Context): Int {
        val am = audio(c) ?: return 0
        return UteMusic.percent(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    fun isMusicActive(c: Context): Boolean = audio(c)?.isMusicActive ?: false
}

/**
 * "Telefonu bul": zil sesi alarm kanalından en yüksek sesle, titreşimle birlikte çalar.
 * Kapatma eylemli bir bildirim gösterilir; 30 sn sonra kendiliğinden durur. Alarm ses düzeyi durunca eski haline döner.
 * Tüm çağrılar ana iş parçacığında yapılır.
 */
object PhoneFinder {

    const val ACTION_STOP = "com.che.ls05.STOP_FIND_PHONE"
    private const val CHANNEL_ID = "find_phone"
    private const val NOTIF_ID = 2
    private const val DURATION_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var active = false
    private var savedAlarmVolume = -1
    private var appContext: Context? = null
    private val timeout = Runnable { stop("30 sn doldu") }

    val isActive: Boolean get() = active

    fun start(c: Context) {
        val ctx = c.applicationContext
        appContext = ctx
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, DURATION_MS)
        if (active) return
        active = true

        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null) {
            try {
                savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: Exception) {
                savedAlarmVolume = -1
                WatchHub.log("Telefonu bul: alarm sesi yükseltilemedi (${e.javaClass.simpleName}).")
            }
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            val uri: Uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: Settings.System.DEFAULT_RINGTONE_URI
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(ctx, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            player?.release()
            player = null
            WatchHub.log("Telefonu bul: zil sesi çalınamadı (${e.javaClass.simpleName}); yalnızca titreşim.")
        }

        val v = ctx.getSystemService(Vibrator::class.java)
        if (v != null && v.hasVibrator()) {
            try {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0), attrs)
                vibrator = v
            } catch (e: Exception) {
                WatchHub.log("Telefonu bul: titreşim başlatılamadı (${e.javaClass.simpleName}).")
            }
        }

        showNotification(ctx)
        WatchHub.log("Telefonu bul başladı (en çok 30 sn).")
    }

    fun stop(reason: String) {
        main.removeCallbacks(timeout)
        val ctx = appContext
        if (!active) return
        active = false
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (ctx != null) {
            if (savedAlarmVolume >= 0) {
                try {
                    ctx.getSystemService(AudioManager::class.java)
                        ?.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                } catch (_: Exception) {
                }
            }
            try {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            } catch (_: Exception) {
            }
        }
        savedAlarmVolume = -1
        WatchHub.log("Telefonu bul durdu ($reason).")
    }

    private fun showNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(CHANNEL_ID, "Telefonu bul", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Saat telefonu aradığında"
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
            val stopIntent = PendingIntent.getBroadcast(
                ctx, 4, Intent(ctx, FindPhoneStopReceiver::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Saat telefonu arıyor")
                .setContentText("Durdurmak için dokun.")
                .setContentIntent(stopIntent)
                .setDeleteIntent(stopIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .addAction(Notification.Action.Builder(null as Icon?, "Durdur", stopIntent).build())
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
            WatchHub.log("Telefonu bul bildirimi gösterilemedi (${e.javaClass.simpleName}).")
        }
    }
}

/** "Telefonu bul" bildirimindeki Durdur eylemi. */
class FindPhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PhoneFinder.ACTION_STOP) PhoneFinder.stop("bildirimden durduruldu")
    }
}
