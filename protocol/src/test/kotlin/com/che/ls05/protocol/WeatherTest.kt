package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherTest {
    @Test
    fun wmoMapping() {
        assertEquals(UteWeather.SUNNY, WeatherCodes.fromWmo(0))
        assertEquals(UteWeather.PARTLY_CLOUDY, WeatherCodes.fromWmo(2))
        assertEquals(UteWeather.HAZE, WeatherCodes.fromWmo(45))
        assertEquals(UteWeather.SHOWERS, WeatherCodes.fromWmo(80))
        assertEquals(UteWeather.HEAVY_RAIN, WeatherCodes.fromWmo(65))
        assertEquals(UteWeather.SNOW, WeatherCodes.fromWmo(73))
        assertEquals(UteWeather.THUNDER, WeatherCodes.fromWmo(95))
        assertEquals(UteWeather.STORM, WeatherCodes.fromWmo(3, 70.0))
        assertEquals(UteWeather.THUNDER, WeatherCodes.fromWmo(95, 80.0))
    }

    @Test
    fun packetsLayout() {
        val days = List(7) { UteWeather.Day(WeatherCodes.fromWmo(it), 10 + it, -2 - it) }
        val p = UteWeather.build(-3, days, WeatherCodes.shortCity("İstanbul"))
        assertEquals(3, p.size)
        assertEquals(0x11, p[0][0].toInt()); assertEquals(1, p[0][1].toInt())
        assertEquals(UteWeather.SUNNY, p[0][2].toInt())
        assertEquals(-3, UteWeather.decodeTemp(p[0][4]))
        assertEquals(10, UteWeather.decodeTemp(p[0][5]))
        assertEquals(-2, UteWeather.decodeTemp(p[0][6]))
        assertEquals(2 + 4 * 4, p[1].size); assertEquals(2 + 2 * 4, p[2].size)
    }
}
