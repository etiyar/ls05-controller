package com.che.ls05

import android.content.Context
import android.content.SharedPreferences
import com.che.ls05.protocol.AlarmSetting
import com.che.ls05.protocol.DndSetting
import com.che.ls05.protocol.SettingSync
import com.che.ls05.protocol.SyncEntry
import com.che.ls05.protocol.UteAlarm

/**
 * Saate giden ayarların kalıcı durumu. SettingSync, "ls05" SharedPreferences dosyasında "sync" anahtarında saklanır.
 * Tüm çağrılar ana iş parçacığında yapılır.
 */
object SettingsStore {

    private const val PREFS = "ls05"
    private const val KEY = "sync"

    private var cached: SettingSync? = null

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Servis ve ekranlar aynı nesneyi kullanır. İlk çağrıda yüklenir, gerekirse eski kayıttan göç edilir. */
    fun sync(c: Context): SettingSync {
        cached?.let { return it }
        val p = prefs(c)
        val text = p.getString(KEY, null)
        val s = if (text != null) {
            SettingSync.deserialize(text)
        } else {
            val migrated = migrate(p)
            p.edit().putString(KEY, migrated.serialize()).apply()
            migrated
        }
        cached = s
        return s
    }

    fun save(c: Context) {
        prefs(c).edit().putString(KEY, sync(c).serialize()).apply()
    }

    fun entry(c: Context, key: String): SyncEntry = sync(c).entry(key)

    /** Kullanıcı yeni bir değer seçti: kaydedilir, saat hazırsa hemen gönderilir. */
    fun set(c: Context, key: String, value: String) {
        sync(c).setDesired(key, value)
        save(c)
        WatchHub.service?.pushSettings()
        WatchHub.settingsChanged()
    }

    /** Kullanıcı "Yeniden gönder" dedi. */
    fun retry(c: Context, key: String) {
        sync(c).retry(key)
        save(c)
        WatchHub.service?.pushSettings()
        WatchHub.settingsChanged()
    }

    /**
     * v0.3 kaydı: "alarm1".."alarm3" ve "dnd" metinleri (biçim aynı). Saat bunları zaten almıştı;
     * bu yüzden hem istenen hem onaylanan değer sayılır (onay zamanı bilinmiyor: 0).
     */
    private fun migrate(p: SharedPreferences): SettingSync {
        val lines = ArrayList<String>()
        for (slot in 1..UteAlarm.SLOTS) {
            val key = AlarmSetting.key(slot)
            val v = AlarmSetting.decode(p.getString(key, null))?.encode() ?: continue
            lines += "$key\t$v\t$v\t0\t0\t0"
        }
        val d = DndSetting.decode(p.getString(DndSetting.KEY, null))?.encode()
        if (d != null) lines += "${DndSetting.KEY}\t$d\t$d\t0\t0\t0"
        if (lines.isNotEmpty()) WatchHub.log("Eski alarm ve rahatsız etme kayıtları yeni ayar deposuna taşındı (${lines.size} ayar).")
        return SettingSync.deserialize(lines.joinToString("\n"))
    }
}
