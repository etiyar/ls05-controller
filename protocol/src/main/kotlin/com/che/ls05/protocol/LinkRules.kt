package com.che.ls05.protocol

/**
 * "Saat uzaklaştı" uyarısının karar kuralları. Android'e bağımlı değildir; birim testiyle doğrulanır.
 *
 * Uyarı yalnızca beklenmeyen kopmada kurulur (kullanıcı kesmedi, servis durmuyor, Bluetooth kapanmadı)
 * ve bekleme süresi dolunca bu kurallarla son kez denetlenir.
 */
object LostLinkRules {
    /** Kopmadan sonra uyarıdan önce beklenen süre. Servis bu sürede kendisi yeniden bağlanmayı dener. */
    const val GRACE_MS = 60_000L

    /** Bildirimdeki "1 saat sustur" süresi. */
    const val SNOOZE_MS = 60 * 60 * 1000L

    enum class Decision {
        /** Uyarı gösterilir. */
        ALERT,

        /** Ayar kapalı. */
        OFF,

        /** Saat bu arada yeniden bağlandı. */
        RECONNECTED,

        /**
         * Telefonun Bluetooth'u kapalı. Karar: hiçbir uyarı gösterilmez. Bluetooth'u kullanıcı kapattıysa
         * "saat uzaklaştı" yanlış olur; ön plan bildirimi zaten "Bluetooth kapalı" yazıyor.
         */
        BLUETOOTH_OFF,

        /** Kullanıcı bildirimden 1 saat susturdu. */
        SNOOZED,

        /** Sessiz saatler içinde. */
        QUIET,
    }

    fun decide(
        enabled: Boolean,
        ready: Boolean,
        bluetoothOn: Boolean,
        snoozedUntil: Long,
        now: Long,
        quiet: Boolean,
    ): Decision = when {
        !enabled -> Decision.OFF
        ready -> Decision.RECONNECTED
        !bluetoothOn -> Decision.BLUETOOTH_OFF
        now < snoozedUntil -> Decision.SNOOZED
        quiet -> Decision.QUIET
        else -> Decision.ALERT
    }

    /**
     * Gün içi dakika (0..1439) [start, end) aralığında mı. start > end ise gece yarısını aşar (22:00-07:00).
     * start == end boş aralık sayılır (saatin rahatsız etme ayarında anlamsız bir değer).
     */
    fun inWindow(startMin: Int, endMin: Int, nowMin: Int): Boolean = when {
        startMin == endMin -> false
        startMin < endMin -> nowMin in startMin until endMin
        else -> nowMin >= startMin || nowMin < endMin
    }

    /** Sessiz saatler saatin rahatsız etme penceresinden alınır; o ayar kapalıysa sessiz saat yoktur. */
    fun quiet(dnd: DndSetting?, hour: Int, minute: Int): Boolean {
        if (dnd == null || !dnd.enabled) return false
        return inWindow(dnd.startHour * 60 + dnd.startMinute, dnd.endHour * 60 + dnd.endMinute, hour * 60 + minute)
    }
}

/**
 * Geçmiş okuma kapısı: okuma sürerken ya da bitişinden 30 sn geçmeden gelen yeni istek yok sayılır.
 * Zaman tekdüze saatle (uptime) verilir. Okuma bitiş paketleri gelince ya da zaman aşımında biter.
 */
class HistoryGate(private val cooldownMs: Long = COOLDOWN_MS) {
    companion object {
        const val COOLDOWN_MS = 30_000L

        /** Bitiş paketi gelmese bile okuma bu sürede bitmiş sayılır. */
        const val TIMEOUT_MS = 25_000L
    }

    enum class Part { STEPS, HEART, SLEEP }

    private val waiting = mutableSetOf<Part>()

    var running = false
        private set
    private var startedAt = 0L
    private var finishedAt = 0L
    private var everFinished = false

    sealed class Check {
        object Start : Check()
        object Running : Check()
        data class Recent(val secondsAgo: Long) : Check()
    }

    fun check(now: Long): Check = when {
        running && now - startedAt < TIMEOUT_MS -> Check.Running
        running -> Check.Start
        everFinished && now - finishedAt < cooldownMs -> Check.Recent((now - finishedAt) / 1000)
        else -> Check.Start
    }

    fun start(now: Long, parts: Set<Part>) {
        running = true
        startedAt = now
        waiting.clear()
        waiting += parts
    }

    /** Bir parçanın bitişi geldi. Okuma bu çağrıyla bittiyse true. */
    fun partDone(part: Part, now: Long): Boolean {
        if (!running || !waiting.remove(part)) return false
        if (waiting.isNotEmpty()) return false
        finish(now)
        return true
    }

    /** Zaman aşımı: okuma hâlâ sürüyorsa bitirir. Bittiyse true. */
    fun timeout(now: Long): Boolean {
        if (!running) return false
        finish(now)
        return true
    }

    /** Bağlantı koptu: yarım okuma sayılmaz, yeniden bağlanınca hemen okunabilir. */
    fun reset() {
        running = false
        waiting.clear()
        everFinished = false
    }

    private fun finish(now: Long) {
        running = false
        waiting.clear()
        finishedAt = now
        everFinished = true
    }
}
