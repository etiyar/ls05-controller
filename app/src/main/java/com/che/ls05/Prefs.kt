package com.che.ls05

import android.content.Context
import android.content.SharedPreferences
import com.che.ls05.protocol.AppTypeMap
import com.che.ls05.protocol.UteCommand

/** Telefonda saklanan ayarlar. Kimlik yalnızca burada tutulur; depoya ve loga girmez. */
object Prefs {
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("ls05", Context.MODE_PRIVATE)

    fun userIdText(c: Context): String = p(c).getString("userId", "") ?: ""
    fun setUserIdText(c: Context, value: String) = p(c).edit().putString("userId", value.trim()).apply()
    fun userIdBytes(c: Context): ByteArray? = UteCommand.parseUserId(userIdText(c))

    fun address(c: Context): String? = p(c).getString("address", null)
    fun setAddress(c: Context, value: String) = p(c).edit().putString("address", value).apply()

    /** Kullanıcı "Durdur" demediyse servis açılışta ve güncellemeden sonra yeniden başlar. */
    fun serviceEnabled(c: Context): Boolean = p(c).getBoolean("serviceEnabled", false)
    fun setServiceEnabled(c: Context, value: Boolean) = p(c).edit().putBoolean("serviceEnabled", value).apply()

    fun forwardingEnabled(c: Context): Boolean = p(c).getBoolean("forwarding", true)
    fun setForwardingEnabled(c: Context, value: Boolean) = p(c).edit().putBoolean("forwarding", value).apply()

    fun vibrate(c: Context): Boolean = p(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, value: Boolean) = p(c).edit().putBoolean("vibrate", value).apply()

    /** Varsayılan kapalı: önce elle "Saati eşitle" ile doğrulanır (kural 3). */
    fun autoTime(c: Context): Boolean = p(c).getBoolean("autoTime", false)
    fun setAutoTime(c: Context, value: Boolean) = p(c).edit().putBoolean("autoTime", value).apply()

    fun appEnabled(c: Context, pkg: String): Boolean = p(c).getBoolean("app:$pkg", pkg in AppTypeMap.defaultOn)
    fun setAppEnabled(c: Context, pkg: String, value: Boolean) = p(c).edit().putBoolean("app:$pkg", value).apply()

    fun seenApps(c: Context): Set<String> = p(c).getStringSet("seenApps", emptySet())?.toSet() ?: emptySet()
    fun addSeenApp(c: Context, pkg: String) {
        val current = seenApps(c)
        if (pkg !in current) p(c).edit().putStringSet("seenApps", current + pkg).apply()
    }

    /** Uygulamadan son bildirimin geldiği zaman (ms); 0 = hiç. */
    fun lastNotified(c: Context, pkg: String): Long = p(c).getLong("last:$pkg", 0L)
    fun setLastNotified(c: Context, pkg: String, at: Long) = p(c).edit().putLong("last:$pkg", at).apply()

    /** Günlük adım hedefi. */
    fun stepGoal(c: Context): Int = p(c).getInt("stepGoal", 8000)

    /** Son başarılı adım geçmişi okuması (ms); 0 = hiç. */
    fun lastSync(c: Context): Long = p(c).getLong("lastSync", 0L)
    fun setLastSync(c: Context, at: Long) = p(c).edit().putLong("lastSync", at).apply()

    // Alarm ve rahatsız etme ayarları SettingsStore'a taşındı. Eski "alarm1".."alarm3" ve "dnd"
    // metinleri aynı dosyada kalır; SettingsStore ilk açılışta bunları okur.
}
