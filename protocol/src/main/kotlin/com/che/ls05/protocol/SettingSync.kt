package com.che.ls05.protocol

/*
 * Saate giden ayarların ortak durum modeli (v0.4.0).
 *
 * Saat ayarlarını geri okutmuyor. Bu yüzden üç şey ayrı tutulur:
 *   istenen   : kullanıcının son seçtiği değer
 *   onaylanan : saatin onay yanıtı verdiği son değer ve zamanı
 *   belirsiz  : onay gelmedi ya da saat sıfırlanmış olabilir
 *
 * Kurallar:
 *  - Bir anahtar için aynı anda en çok bir komut yolda olur.
 *  - Onay yalnızca aynı bağlantı oturumunda yolda olan komuta sayılır.
 *  - Onayı gelmeyen komut otomatik yeniden gönderilmez; kullanıcı "Yeniden gönder" der.
 *  - Hiç gönderilmemiş istek (bağlantı yokken yapılan değişiklik) bağlanınca gönderilir.
 *
 * Değerler bu sınıf için opak metindir. Yeni ayar eklemek = yeni anahtar + komut üretici + onay eşleyici.
 */

enum class SyncPhase {
    /** Hiç ayarlanmadı. */
    IDLE,

    /** İstenen değer saate hiç gönderilmedi; bağlanınca gönderilecek. */
    PENDING,

    /** Komut yolda, onay bekleniyor. */
    SENDING,

    /** Saat istenen değeri onayladı. */
    CONFIRMED,

    /** Komut gönderildi ama onay gelmedi. Saatteki durum bilinmiyor. */
    NO_ACK,

    /** Saat sıfırlanmış olabilir. Saatteki durum bilinmiyor. */
    UNKNOWN,
}

data class SyncEntry(
    val desired: String? = null,
    val confirmed: String? = null,
    val confirmedAt: Long = 0,
    val inFlight: String? = null,
    val inFlightSession: Int = 0,
    val sentAt: Long = 0,
    val noAck: Boolean = false,
    val unknown: Boolean = false,
) {
    val phase: SyncPhase
        get() = when {
            inFlight != null -> SyncPhase.SENDING
            desired == null -> SyncPhase.IDLE
            noAck -> SyncPhase.NO_ACK
            unknown -> SyncPhase.UNKNOWN
            desired == confirmed -> SyncPhase.CONFIRMED
            else -> SyncPhase.PENDING
        }
}

class SettingSync {

    enum class Ack { MATCHED, UNMATCHED }

    private val entries = LinkedHashMap<String, SyncEntry>()

    fun entry(key: String): SyncEntry = entries[key] ?: SyncEntry()
    fun keys(): Set<String> = entries.keys.toSet()

    /** Kullanıcı yeni bir değer seçti. Açık bir niyet olduğu için belirsizlik bayrakları kalkar. */
    fun setDesired(key: String, value: String) {
        require('\t' !in value && '\n' !in value) { "değer sekme ya da satır sonu içeremez" }
        val e = entry(key)
        entries[key] = e.copy(desired = value, noAck = false, unknown = false)
    }

    /** Kullanıcı "Yeniden gönder" dedi. */
    fun retry(key: String) {
        val e = entry(key)
        if (e.desired == null) return
        // Onaylanan değer silinir ki istenen değer aynı olsa bile yeniden gönderilsin.
        entries[key] = e.copy(noAck = false, unknown = false, confirmed = null, confirmedAt = 0)
    }

    /** Şu an gönderilmesi gereken (anahtar, değer) çiftleri. */
    fun nextToSend(): List<Pair<String, String>> =
        entries.filter { it.value.phase == SyncPhase.PENDING }.map { it.key to it.value.desired!! }

    fun markSent(key: String, value: String, session: Int, now: Long) {
        entries[key] = entry(key).copy(inFlight = value, inFlightSession = session, sentAt = now)
    }

    /**
     * Saatten onay geldi. ackValue: saat değeri yankılıyorsa (ör. rahatsız etme) o değer; yoksa null.
     * Yolda komut yoksa, oturum farklıysa ya da yankı tutmuyorsa onay hiçbir şeye sayılmaz.
     */
    fun onAck(key: String, session: Int, now: Long, ackValue: String? = null): Ack {
        val e = entries[key] ?: return Ack.UNMATCHED
        val sent = e.inFlight ?: return Ack.UNMATCHED
        if (e.inFlightSession != session) return Ack.UNMATCHED
        if (ackValue != null && ackValue != sent) return Ack.UNMATCHED
        entries[key] = e.copy(
            confirmed = sent, confirmedAt = now, inFlight = null, noAck = false, unknown = false,
        )
        return Ack.MATCHED
    }

    /** Süresi dolan yoldaki komutları "onay gelmedi" yapar. Değişen anahtarları döndürür. */
    fun expire(now: Long, timeoutMs: Long): List<String> {
        val changed = ArrayList<String>()
        for ((k, e) in entries.toMap()) {
            if (e.inFlight != null && now - e.sentAt >= timeoutMs) {
                entries[k] = e.copy(inFlight = null, noAck = true)
                changed += k
            }
        }
        return changed
    }

    /** Bağlantı koptu: yoldaki komutların akıbeti bilinmiyor. */
    fun onDisconnect(): List<String> {
        val changed = ArrayList<String>()
        for ((k, e) in entries.toMap()) {
            if (e.inFlight != null) {
                entries[k] = e.copy(inFlight = null, noAck = true)
                changed += k
            }
        }
        return changed
    }

    /** Saat sıfırlanmış olabilir: ayarlanmış her şey belirsiz olur, otomatik gönderilmez. */
    fun markAllUnknown() {
        for ((k, e) in entries.toMap()) {
            if (e.desired != null) {
                entries[k] = e.copy(inFlight = null, noAck = false, unknown = true, confirmed = null, confirmedAt = 0)
            }
        }
    }

    // ---------- Kalıcılık ----------

    /** Satır: anahtar, istenen, onaylanan, onay zamanı, onay gelmedi, belirsiz. Yoldaki komut "onay gelmedi" olarak yazılır. */
    fun serialize(): String = entries.entries.joinToString("\n") { (k, e) ->
        listOf(
            k, e.desired ?: NULL, e.confirmed ?: NULL, e.confirmedAt.toString(),
            if (e.noAck || e.inFlight != null) "1" else "0", if (e.unknown) "1" else "0",
        ).joinToString("\t")
    }

    companion object {
        private const val NULL = "-"

        /** Bozuk satırlar atlanır. */
        fun deserialize(text: String): SettingSync {
            val s = SettingSync()
            for (line in text.split('\n')) {
                val p = line.split('\t')
                if (p.size != 6 || p[0].isEmpty()) continue
                val at = p[3].toLongOrNull() ?: continue
                s.entries[p[0]] = SyncEntry(
                    desired = p[1].takeIf { it != NULL },
                    confirmed = p[2].takeIf { it != NULL },
                    confirmedAt = at,
                    noAck = p[4] == "1",
                    unknown = p[5] == "1",
                )
            }
            return s
        }
    }
}

/** Alarm ayarı. Metin biçimi v0.3 kaydıyla aynı: açık|saat|dakika|gün maskesi. */
data class AlarmSetting(val enabled: Boolean, val hour: Int, val minute: Int, val mask: Int) {
    fun encode() = "${if (enabled) 1 else 0}|$hour|$minute|$mask"
    fun command(slot: Int): ByteArray = UteAlarm.build(slot, hour, minute, mask, enabled)

    companion object {
        val DEFAULT = AlarmSetting(false, 7, 0, UteAlarm.WEEKDAYS)
        fun key(slot: Int) = "alarm$slot"

        fun decode(text: String?): AlarmSetting? {
            val p = text?.split('|')?.map { it.toIntOrNull() ?: return null } ?: return null
            if (p.size != 4 || p[1] !in 0..23 || p[2] !in 0..59 || p[3] !in 1..0x7F) return null
            return AlarmSetting(p[0] == 1, p[1], p[2], p[3])
        }
    }
}

/** Rahatsız etme ayarı. Metin biçimi: açık|baş saat|baş dakika|bitiş saat|bitiş dakika. */
data class DndSetting(val enabled: Boolean, val startHour: Int, val startMinute: Int, val endHour: Int, val endMinute: Int) {
    fun encode() = "${if (enabled) 1 else 0}|$startHour|$startMinute|$endHour|$endMinute"
    fun command(): ByteArray = UteDnd.build(startHour, startMinute, endHour, endMinute, enabled)

    companion object {
        const val KEY = "dnd"
        val DEFAULT = DndSetting(false, 22, 0, 7, 0)

        fun decode(text: String?): DndSetting? {
            val p = text?.split('|')?.map { it.toIntOrNull() ?: return null } ?: return null
            if (p.size != 5 || p[1] !in 0..23 || p[2] !in 0..59 || p[3] !in 0..23 || p[4] !in 0..59) return null
            return DndSetting(p[0] == 1, p[1], p[2], p[3], p[4])
        }

        /** Saatin yankısından (15 00 sa dk sa dk açık) ayar. */
        fun fromEcho(b: ByteArray): DndSetting? {
            if (b.size < 7 || b[0] != 0x15.toByte()) return null
            return DndSetting(b[6].toInt() == 1, b[2].toInt() and 0xFF, b[3].toInt() and 0xFF, b[4].toInt() and 0xFF, b[5].toInt() and 0xFF)
        }
    }
}

/**
 * Profil ve ekran ayarları (komut 05). Boy, kilo, yaş ve cinsiyet kullanıcı girene kadar null; eksik profil
 * için komut üretilmez (uydurma değer gönderilmez). Metin biçimi: boy|kilo|yaş|cinsiyet|ekran sn|bilek|hedef,
 * eksik alan boş. Kişisel veridir: yalnızca telefonda saklanır, loga ve depoya girmez.
 */
data class ProfileSetting(
    val heightCm: Int?,
    val weightKg: Int?,
    val age: Int?,
    val sex: Int?,
    val screenSec: Int,
    val raiseWrist: Boolean,
    val stepGoal: Int,
) {
    val personalComplete: Boolean
        get() = heightCm != null && weightKg != null && age != null && sex != null

    fun encode(): String = listOf(
        heightCm?.toString() ?: "", weightKg?.toString() ?: "", age?.toString() ?: "", sex?.toString() ?: "",
        screenSec.toString(), if (raiseWrist) "1" else "0", stepGoal.toString(),
    ).joinToString("|")

    /** Eksik ya da aralık dışı profil için null. */
    fun command(): ByteArray? {
        val h = heightCm ?: return null
        val w = weightKg ?: return null
        val a = age ?: return null
        val x = sex ?: return null
        return try {
            UteProfile.build(h, w, screenSec, stepGoal, raiseWrist, a, x)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val KEY = "profile"

        /** Ekran 5 sn ve bileği kaldırma açık: Haylou Fun'ın varsayılanı ve kayıttaki değer. Hedef 8000: Haylou Fun varsayılanı. */
        val DEFAULT = ProfileSetting(null, null, null, null, 5, true, 8000)

        fun decode(text: String?): ProfileSetting? {
            val p = text?.split('|') ?: return null
            if (p.size != 7) return null
            fun opt(i: Int, range: IntRange): Int? {
                if (p[i].isEmpty()) return null
                return p[i].toIntOrNull()?.takeIf { it in range }
            }
            val screen = p[4].toIntOrNull()?.takeIf { it in UteProfile.SCREEN_SECONDS } ?: return null
            val wrist = when (p[5]) { "1" -> true; "0" -> false; else -> return null }
            val goal = p[6].toIntOrNull()?.takeIf { it in UteProfile.GOAL_RANGE } ?: return null
            return ProfileSetting(
                heightCm = opt(0, UteProfile.HEIGHT_RANGE),
                weightKg = opt(1, UteProfile.WEIGHT_RANGE),
                age = opt(2, UteProfile.AGE_RANGE),
                sex = opt(3, UteProfile.SEX_MALE..UteProfile.SEX_FEMALE),
                screenSec = screen,
                raiseWrist = wrist,
                stepGoal = goal,
            )
        }
    }
}

/** Bir ayar anahtarı ve değerinden saate gidecek komut. Bilinmeyen anahtar ya da bozuk değer için null. */
object SettingCommands {
    fun build(key: String, value: String): ByteArray? = when {
        key == DndSetting.KEY -> DndSetting.decode(value)?.command()
        key == ProfileSetting.KEY -> ProfileSetting.decode(value)?.command()
        key.startsWith("alarm") -> {
            val slot = key.removePrefix("alarm").toIntOrNull()
            val a = AlarmSetting.decode(value)
            if (slot != null && slot in 1..UteAlarm.SLOTS && a != null) a.command(slot) else null
        }
        else -> null
    }
}
