package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** .tbwf simgeleri: kare eşlemeleri, klasör sırası, ölçüler ve yerleşik çizimler. */
class TbwfIconsTest {

    @Test
    fun remaps() {
        assertEquals(listOf(0, 3, 5, 7, 10), TbwfIcons.remap("batteryResource", 11)!!.toList())
        assertEquals(listOf(0, 1, 2, 3, 4), TbwfIcons.remap("batteryResource", 5)!!.toList())
        assertEquals(null, TbwfIcons.remap("batteryResource", 7))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 8, 6, 7, 10, 14, 12, 11, 15, 16, 17), TbwfIcons.remap("weatherResource", 18)!!.toList())
        assertEquals(16, TbwfIcons.remap("weatherResource", 16)!!.size)
        assertEquals(null, TbwfIcons.remap("weatherResource", 17))
        assertEquals(11, TbwfIcons.remap("stepGoalProgressResource", 11)!!.size)
        assertEquals(null, TbwfIcons.remap("stepGoalProgressResource", 5))
        assertEquals(listOf(0, 1), TbwfIcons.remap("connectionResource", 2)!!.toList())
        // Çıktı kare sayıları yazıcının beyaz listesiyle aynı.
        for (n in listOf("batteryResource", "connectionResource", "weatherResource", "stepGoalProgressResource")) {
            val e = TbwfSpec.ELEMENTS[n]!!
            assertEquals(FaceWriter.FRAMES[e.type], e.frames, n)
        }
    }

    @Test
    fun folderOrder() {
        val names = (0..10).map { "percent_${it * 10}" }.shuffled(java.util.Random(3))
        assertEquals((0..10).map { "percent_${it * 10}" }, TbwfIcons.folderOrder("batteryResource", names))
        // Metin sıralaması 100'ü 10'un hemen arkasına koyardı.
        assertEquals("percent_100", TbwfIcons.numericSort(listOf("percent_100", "percent_20", "percent_0")).last())
        assertEquals(listOf("connected", "disconnected"), TbwfIcons.folderOrder("connectionResource", listOf("disconnected", "connected")))
        assertEquals(null, TbwfIcons.folderOrder("connectionResource", listOf("connected")))
        assertEquals(TbwfIcons.DAY_FILES, TbwfIcons.folderOrder("dayOfWeek", TbwfIcons.DAY_FILES.reversed()))
        assertEquals(null, TbwfIcons.folderOrder("dayOfWeek", TbwfIcons.DAY_FILES.drop(1)))
        assertEquals(listOf("w_2", "w_10"), TbwfIcons.folderOrder("weatherResource", listOf("w_10", "w_2")))
        assertEquals(null, TbwfIcons.folderOrder("weatherResource", listOf("sunny")))
    }

    @Test
    fun names() {
        assertEquals("ic_wf_anim_weather_fill", TbwfIcons.builtinName("weatherResource", "weatherResource_ic_wf_anim_weather_fill.png"))
        assertEquals("ic_wf_heart_rate_beat_2", TbwfIcons.builtinName("heartRateResource", "heartRateResource_ic_wf_heart_rate_beat_2.png"))
        assertEquals("ic_wf_hand_hour_fill", TbwfIcons.builtinName("hourHand", "/hourHand_ic_wf_hand_hour_fill.png"))
    }

    @Test
    fun geometry() {
        // 1549 pil halkası: size 0.2333 → 56 x 56.
        assertEquals(listOf(56, 56), TbwfIcons.frameSize(24f, 24f, 0.23333333).toList())
        // Yatay pil 24 x 14.4, size 0.08205127 → 20 x 12.
        assertEquals(listOf(20, 12), TbwfIcons.frameSize(24f, 14.4f, 0.08205127).toList())
        // İbre 6 x 24, size 0.3 → 18 x 72.
        assertEquals(listOf(18, 72), TbwfIcons.frameSize(6f, 24f, 0.3).toList())
        assertEquals(listOf(30, 20), TbwfIcons.cell(listOf(intArrayOf(30, 10), intArrayOf(12, 20))).toList())
        assertEquals(listOf(9, 0), TbwfIcons.offset(30, 20, 12, 20).toList())
        val placed = placeInCell(IntArray(4) { -1 }, 2, 2, 4, 4)
        assertEquals(-1, placed[1 * 4 + 1]); assertEquals(0, placed[0])
        assertEquals(19, HandGeometry.hubSize(1, 20)); assertEquals(15, HandGeometry.hubSize(2, 20)); assertEquals(11, HandGeometry.hubSize(3, 20))
        assertEquals(0xFF00FF00.toInt(), TbwfIcons.hubColor("linear_vertical", intArrayOf(-1, 0xFF00FF00.toInt())))
        assertEquals(-1, TbwfIcons.hubColor("solid", intArrayOf(-1, 5)))
    }

    @Test
    fun rasterAndTint() {
        val art = ArtBuilder(10f, 10f).apply { fill { rect(0f, 0f, 10f, 5f) }; clear { rect(0f, 0f, 5f, 5f) } }.build()
        val px = ArtRaster.render(art, 10, 10)
        assertEquals(0, px[2 * 10 + 2])
        assertEquals(-1, px[2 * 10 + 7])
        assertEquals(0, px[8 * 10 + 7])
        ArtTint.apply(px, 10, 10, "solid", intArrayOf(0xFFFF0000.toInt()), null)
        assertEquals(0xFFFF0000.toInt(), px[2 * 10 + 7])
        // Düz renk 0: boyanmaz.
        val w = ArtRaster.render(art, 10, 10)
        ArtTint.apply(w, 10, 10, "solid", intArrayOf(0), null)
        assertEquals(-1, w[2 * 10 + 7])
        // Dikey geçiş: üst ilk renk, alt son renk.
        val full = IntArray(100) { -1 }
        ArtTint.apply(full, 10, 10, "linear_vertical", intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), floatArrayOf(0f, 1f))
        assertTrue((full[5] and 0xFF) < 20)
        assertTrue((full[95] and 0xFF) > 235)
        assertEquals(0x80123456.toInt(), ArtTint.srcIn(0x80FFFFFF.toInt(), 0xFF123456.toInt()))
        assertEquals(0xFF808080.toInt(), ArtTint.colorAt(intArrayOf(0xFF000000.toInt(), -1, -1), floatArrayOf(0f, 0.5f, 1f), 0.25f))
    }

    @Test
    fun builtinCatalogue() {
        // Hello Haylou animasyonlarının kare sayıları.
        assertEquals(18, BuiltinIcons.get("ic_wf_anim_weather_fill")!!.frames.size)
        assertEquals(18, BuiltinIcons.get("ic_wf_anim_weather_outline")!!.frames.size)
        assertEquals(11, BuiltinIcons.get("ic_wf_anim_battery_circle")!!.frames.size)
        assertEquals(5, BuiltinIcons.get("ic_wf_anim_battery_horizontal")!!.frames.size)
        assertEquals(11, BuiltinIcons.get("ic_wf_anim_goal_circular")!!.frames.size)
        assertEquals(2, BuiltinIcons.get("ic_wf_anim_connection_ble")!!.frames.size)
        assertEquals(5, BuiltinIcons.get("ic_wf_anim_circle")!!.frames.size)
        assertEquals(null, BuiltinIcons.get("ic_wf_nope"))
        // Eşlemeden sonra saatin kare sayısı.
        for ((el, name) in listOf("batteryResource" to "ic_wf_anim_battery_circle", "batteryResource" to "ic_wf_anim_battery_semicircular",
            "weatherResource" to "ic_wf_anim_weather_fill", "stepGoalProgressResource" to "ic_wf_anim_goal_linear_horizontal_large",
            "connectionResource" to "ic_wf_anim_connection_signal", "batteryResource" to "ic_wf_anim_circle")) {
            val n = BuiltinIcons.get(name)!!.frames.size
            assertEquals(TbwfSpec.ELEMENTS[el]!!.frames, TbwfIcons.remap(el, n)?.size, name)
        }
        val hand = BuiltinIcons.get("ic_wf_hand_minute_fill")!!
        assertEquals(6f, hand.w); assertEquals(24f, hand.h)
        // Her ad çizilebiliyor ve boş değil.
        for (name in BuiltinIcons.NAMES) {
            val ic = BuiltinIcons.get(name)
            assertTrue(ic != null && ic.frames.isNotEmpty(), name)
            val f = ic!!.frames.last()
            val sz = TbwfIcons.frameSize(ic.w, ic.h, 0.12)
            val px = ArtRaster.render(f, sz[0], sz[1], ss = 2)
            assertTrue(px.any { it != 0 }, name)
        }
    }

    @Test
    fun handTailWithHub() {
        // Yerleşik ibre: size 0.35 → 21 x 84; akrep göbeği round(21*0.95) = 20 → H2 = 84 + 10, t = -10.
        val sz = TbwfIcons.frameSize(6f, 24f, 0.35)
        assertEquals(listOf(21, 84), sz.toList())
        val hub = HandGeometry.hubSize(1, sz[0])
        val b = HandGeometry.box(sz[0], sz[1], HandGeometry.pivot(4, 0.0), hub)
        assertEquals(listOf(94, 84, 21), b.tail.map { it.toInt() and 0xFF })
    }
}
