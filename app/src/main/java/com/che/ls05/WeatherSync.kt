package com.che.ls05

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.UteWeather
import com.che.ls05.protocol.WeatherCodes
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Hava durumu (Open-Meteo, ücretsiz, anahtarsız). Yalnızca kullanıcı açarsa internete çıkar.
 * Şehir elle seçilir; konum izni istenmez. Saatte bir yenilenir, saat bağlanınca son veri gönderilir.
 * Şehir adı ve hava değerleri loga yazılmaz.
 */
object WeatherSync {

    const val REFRESH_MS = 60 * 60 * 1000L
    /** Bu süreden eski veri saate gönderilmez. */
    const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000

    class City(val name: String, val detail: String, val lat: Double, val lon: Double)

    class Forecast(val current: Int, val days: List<UteWeather.Day>, val fetchedAt: Long) {
        val todayCode: Int get() = days.firstOrNull()?.code ?: 0
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var fetching = false

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Şehir arama (arka planda). */
    fun search(query: String, done: (List<City>?, String?) -> Unit) {
        Thread({
            val result = try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=tr&format=json"))
                val arr = json.optJSONArray("results")
                val list = ArrayList<City>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val detail = listOf(o.optString("admin1"), o.optString("country"))
                        .filter { it.isNotBlank() && it != "null" }.joinToString(", ")
                    list += City(o.getString("name"), detail, o.getDouble("latitude"), o.getDouble("longitude"))
                }
                list to null
            } catch (e: Exception) {
                null to "Arama yapılamadı. İnternet bağlantısını kontrol et."
            }
            main.post { done(result.first, result.second) }
        }, "weather-search").start()
    }

    fun parse(text: String, fetchedAt: Long): Forecast {
        val o = JSONObject(text)
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        val codes = daily.getJSONArray("weather_code")
        val max = daily.getJSONArray("temperature_2m_max")
        val min = daily.getJSONArray("temperature_2m_min")
        val wind = daily.optJSONArray("wind_speed_10m_max")
        val days = (0 until 7).map { i ->
            val idx = minOf(i, codes.length() - 1)
            UteWeather.Day(
                WeatherCodes.fromWmo(codes.getInt(idx), wind?.optDouble(idx, 0.0) ?: 0.0),
                Math.round(max.getDouble(idx)).toInt(),
                Math.round(min.getDouble(idx)).toInt(),
            )
        }
        // Bugünün simgesi şimdiki duruma göre.
        val nowCode = WeatherCodes.fromWmo(cur.getInt("weather_code"), cur.optDouble("wind_speed_10m", 0.0))
        val fixed = listOf(days[0].copy(code = nowCode)) + days.drop(1)
        return Forecast(Math.round(cur.getDouble("temperature_2m")).toInt(), fixed, fetchedAt)
    }

    fun cached(c: Context): Forecast? {
        val text = Prefs.weatherCache(c) ?: return null
        return try { parse(text, Prefs.weatherFetchedAt(c)) } catch (e: Exception) { null }
    }

    /** İnternetten yeniler; bitince saate gönderir. force=false ise son yenilemeden 1 saat geçmediyse atlar. */
    fun refresh(c: Context, force: Boolean, done: ((String?) -> Unit)? = null) {
        val app = c.applicationContext
        if (!Prefs.weatherEnabled(app) || Prefs.weatherLat(app) == null) { done?.invoke("Hava durumu kapalı ya da şehir seçilmedi."); return }
        if (!force && System.currentTimeMillis() - Prefs.weatherFetchedAt(app) < REFRESH_MS) {
            sendIfFresh(app)
            done?.invoke(null)
            return
        }
        if (fetching) { done?.invoke(null); return }
        fetching = true
        val lat = Prefs.weatherLat(app)!!
        val lon = Prefs.weatherLon(app)!!
        Thread({
            val err = try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max" +
                    "&timezone=auto&forecast_days=7"
                val text = get(url)
                val now = System.currentTimeMillis()
                parse(text, now) // geçerli mi
                Prefs.setWeatherCache(app, text, now)
                null
            } catch (e: Exception) {
                "Hava durumu alınamadı. İnternet bağlantısını kontrol et."
            }
            main.post {
                fetching = false
                if (err == null) {
                    WatchHub.log("Hava durumu yenilendi.")
                    sendIfFresh(app)
                } else {
                    WatchHub.log("Hava durumu alınamadı.")
                }
                WatchHub.settingsChanged()
                done?.invoke(err)
            }
        }, "weather-fetch").start()
    }

    /** Elde güncel veri varsa ve saat hazırsa gönderir. */
    fun sendIfFresh(c: Context) {
        if (!Prefs.weatherEnabled(c)) return
        val f = cached(c) ?: return
        if (System.currentTimeMillis() - f.fetchedAt > MAX_AGE_MS) return
        val s = WatchHub.service ?: return
        if (!s.isWatchReady()) return
        val packets = UteWeather.build(f.current, f.days, WeatherCodes.shortCity(Prefs.weatherCityName(c)))
        packets.forEachIndexed { i, p -> s.command(p, "hava durumu ${i + 1}/3 (11)") }
        Prefs.setWeatherSentAt(c, System.currentTimeMillis())
    }

    /** Ayarlar ekranındaki özet satırı. */
    fun summary(c: Context): String {
        if (!Prefs.weatherEnabled(c)) return "Kapalı"
        if (Prefs.weatherLat(c) == null) return "Şehir seçilmedi"
        val f = cached(c) ?: return "${Prefs.weatherCityName(c)} · henüz alınmadı"
        return "${Prefs.weatherCityName(c)} · ${f.current}° ${WeatherCodes.label(f.todayCode)} · ${Fmt.dayMs(f.fetchedAt)}"
    }
}
