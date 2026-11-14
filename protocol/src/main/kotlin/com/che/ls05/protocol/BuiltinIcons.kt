package com.che.ls05.protocol

import kotlin.math.cos
import kotlin.math.sin

/*
 * Hello Haylou'nun yerleşik simge adlarına (ic_wf_*) karşılık gelen kendi basit çizimlerimiz.
 * Yalnızca adlar ve en/boy oranları (yerleşim hesabı için) Hello Haylou ile aynıdır; çizimler bizim.
 * Tüm şekiller beyaz çizilir; renk sonradan verilir (ArtTint).
 */

object BuiltinIcons {

    /** w x h: yerleşik simgenin en/boy ölçüsü (dp; yalnızca oran kullanılır). Kareler sırasıyla. */
    class Icon(val w: Float, val h: Float, val frames: List<Art>)

    private const val SW = 1.6f

    private val WEATHER_ORDER = listOf(
        "unknown", "clear_sky_day", "scattered_clouds_day", "cloudy_day", "rain_with_sun_day", "thunderstorm",
        "light_rain", "medium_rain", "rain_and_snow", "light_snow", "medium_snow", "wind", "mist", "haze",
        "sandstorm", "clear_sky_night", "scattered_clouds_night", "rain_with_sun_night",
    )
    private val WEATHER_OUTLINE_ORDER = WEATHER_ORDER.map { if (it == "medium_snow") "heavy_snow" else it }

    private val TEN = (0..10).map { it * 10 }
    private val BATTERY_LEVELS = listOf(0, 20, 50, 80, 100)

    private val STATIC_SIZES = mapOf(
        "ic_wf_calorie_burn" to (16.378f to 24f), "ic_wf_calorie_burn_2" to (17.264f to 24f),
        "ic_wf_calorie_burn_3" to (16.052f to 24f), "ic_wf_calorie_burn_4" to (18.649f to 24f),
        "ic_wf_calorie_burn_5" to (18.723f to 24f), "ic_wf_calorie_cake" to (20.095f to 24f),
        "ic_wf_calorie_cake_2" to (24f to 21.506f), "ic_wf_calorie_cutlery" to (21.6f to 24f),
        "ic_wf_calorie_cutlery_2" to (24f to 22.79f), "ic_wf_calorie_food" to (24f to 24f),
        "ic_wf_calorie_food_2" to (17.315f to 24f), "ic_wf_calorie_food_3" to (24f to 21.6f),
        "ic_wf_calorie_ice_cream" to (13.679f to 24f), "ic_wf_calorie_ice_cream_2" to (13.129f to 24f),
        "ic_wf_calorie_ice_cream_3" to (15.943f to 24f), "ic_wf_calorie_ice_cream_4" to (10.536f to 24f),
        "ic_wf_distance_direction" to (20.4f to 24f), "ic_wf_distance_map_marker" to (16.8f to 24f),
        "ic_wf_distance_map_marker_2" to (24f to 24f), "ic_wf_distance_map_marker_2_radius" to (18.286f to 24f),
        "ic_wf_distance_map_marker_radius" to (18.286f to 24f), "ic_wf_distance_navigation" to (24f to 24f),
        "ic_wf_heart_rate_beat" to (24f to 20.898f), "ic_wf_heart_rate_beat_2" to (24f to 18.587f),
        "ic_wf_heart_rate_favorite" to (24f to 22.02f), "ic_wf_heart_rate_favorite_2" to (24f to 22.02f),
        "ic_wf_step_boots" to (24f to 23.534f), "ic_wf_step_filp_flops" to (24f to 24f),
        "ic_wf_step_foot_print" to (14.348f to 24f), "ic_wf_step_shoe_formal" to (24f to 9.6f),
        "ic_wf_step_shoe_print" to (24f to 24f), "ic_wf_step_shoe_training" to (24f to 10.429f),
        "ic_wf_step_slippers" to (24f to 24f), "ic_wf_step_sneakers" to (24f to 12.924f),
        "ic_wf_step_sneakers_2" to (24f to 16.108f), "ic_wf_step_women_boots" to (24f to 18.914f),
        "ic_wf_step_women_boots_2" to (24f to 24f), "ic_wf_step_women_boots_3" to (21.291f to 24f),
        "ic_wf_step_women_high_heels" to (24f to 18.99f),
    )

    /** Tanınan bütün adlar. */
    val NAMES: Set<String> by lazy {
        val s = LinkedHashSet<String>()
        for (k in listOf("hour", "minute", "second")) for (st in listOf("fill", "fill_2", "fill_3", "outline", "round")) s += "ic_wf_hand_${k}_$st"
        s += "ic_wf_hand_axis_fill"
        for (o in listOf("horizontal", "vertical")) {
            s += "ic_wf_anim_battery_$o"
            for (l in BATTERY_LEVELS) s += "ic_wf_battery_${o}_$l"
        }
        for (p in listOf("circular", "semicircular", "linear_horizontal", "linear_horizontal_small", "linear_horizontal_large")) {
            for (v in TEN) s += "ic_wf_progress_${p}_$v"
            s += (if (p == "circular") "ic_wf_anim_battery_circle" else "ic_wf_anim_battery_$p")
            s += "ic_wf_anim_goal_$p"
        }
        s += "ic_wf_anim_circle"; s += "ic_wf_circle"
        for (c in listOf("ble", "link", "signal")) { s += "ic_wf_anim_connection_$c"; s += "ic_wf_connection_$c"; s += "ic_wf_connection_${c}_off" }
        s += "ic_wf_anim_weather_fill"; s += "ic_wf_anim_weather_outline"
        for (w in WEATHER_ORDER) s += "ic_wf_weather_fill_$w"
        for (w in WEATHER_OUTLINE_ORDER) s += "ic_wf_weather_outline_$w"
        s += STATIC_SIZES.keys
        s
    }

    fun get(name: String): Icon? {
        if (name !in NAMES) return null
        STATIC_SIZES[name]?.let { (w, h) -> return Icon(w, h, listOf(static(name))) }
        val hand = Regex("ic_wf_hand_(hour|minute|second)_(.+)").matchEntire(name)
        if (hand != null) return Icon(6f, 24f, listOf(hand(hand.groupValues[1], hand.groupValues[2])))
        return when {
            name == "ic_wf_hand_axis_fill" -> Icon(4f, 4f, listOf(axis()))
            name == "ic_wf_anim_battery_horizontal" -> Icon(24f, 14.4f, BATTERY_LEVELS.map { battery(false, it / 100f) })
            name == "ic_wf_anim_battery_vertical" -> Icon(14.4f, 24f, BATTERY_LEVELS.map { battery(true, it / 100f) })
            name.startsWith("ic_wf_battery_") -> {
                val v = name.contains("vertical")
                val l = TbwfIcons.trailingNumber(name) ?: 0
                if (v) Icon(14.4f, 24f, listOf(battery(true, l / 100f))) else Icon(24f, 14.4f, listOf(battery(false, l / 100f)))
            }
            name == "ic_wf_anim_circle" -> Icon(24f, 24f, (0..4).map { pulseRing(it / 4f) })
            name == "ic_wf_circle" -> Icon(24f, 24f, listOf(pulseRing(1f)))
            name.startsWith("ic_wf_anim_battery_") || name.startsWith("ic_wf_anim_goal_") -> {
                val kind = name.removePrefix("ic_wf_anim_battery_").removePrefix("ic_wf_anim_goal_").let { if (it == "circle") "circular" else it }
                progressIcon(kind, TEN.map { it / 100f })
            }
            name.startsWith("ic_wf_progress_") -> {
                val kind = name.removePrefix("ic_wf_progress_").substringBeforeLast('_')
                progressIcon(kind, listOf((TbwfIcons.trailingNumber(name) ?: 0) / 100f))
            }
            name.startsWith("ic_wf_anim_connection_") -> {
                val c = name.removePrefix("ic_wf_anim_connection_")
                val (w, h) = connectionSize(c)
                Icon(w, h, listOf(connection(c, true), connection(c, false)))
            }
            name.startsWith("ic_wf_connection_") -> {
                val off = name.endsWith("_off")
                val c = name.removePrefix("ic_wf_connection_").removeSuffix("_off")
                val (w, h) = connectionSize(c)
                Icon(w, h, listOf(connection(c, !off)))
            }
            name == "ic_wf_anim_weather_fill" -> Icon(24f, 24f, WEATHER_ORDER.map { weather(it, false) })
            name == "ic_wf_anim_weather_outline" -> Icon(24f, 24f, WEATHER_OUTLINE_ORDER.map { weather(it, true) })
            name.startsWith("ic_wf_weather_fill_") -> Icon(24f, 24f, listOf(weather(name.removePrefix("ic_wf_weather_fill_"), false)))
            name.startsWith("ic_wf_weather_outline_") -> Icon(24f, 24f, listOf(weather(name.removePrefix("ic_wf_weather_outline_"), true)))
            else -> null
        }
    }

    // ---------- İbreler ----------

    /** 6 x 24: uç üstte, alt kenar dönme merkezi. */
    private fun hand(kind: String, style: String): Art {
        val b = ArtBuilder(6f, 24f)
        val bw = when (kind) { "hour" -> 2.6f; "minute" -> 2.2f; else -> 1.0f }
        val c = 3f
        when (style) {
            "fill" -> b.fill { poly(c - bw, 24f, c - bw * 0.45f, 2.6f, c, 0f, c + bw * 0.45f, 2.6f, c + bw, 24f) }
            "fill_2" -> b.fill { poly(c - bw * 0.4f, 24f, c - bw, 8.5f, c, 0f, c + bw, 8.5f, c + bw * 0.4f, 24f) }
            "fill_3" -> if (kind == "second") {
                b.fill { rect(c - 0.5f, 6f, 1f, 18f) }
                b.fill { circle(c, 3.6f, 2.4f) }
                b.clear { circle(c, 3.6f, 1.2f) }
            } else {
                b.fill { rrect(c - bw * 0.85f, 0.4f, bw * 1.7f, 23.6f, bw * 0.85f) }
                b.clear { rrect(c - bw * 0.35f, 2.6f, bw * 0.7f, 12f, bw * 0.35f) }
            }
            "outline" -> if (kind == "second") {
                b.fill { rect(c - 0.45f, 0.5f, 0.9f, 23.5f) }
            } else {
                val w = bw - 0.4f
                b.stroke(0.8f) { poly(c - w, 23.6f, c - w * 0.45f, 3f, c, 0.6f, c + w * 0.45f, 3f, c + w, 23.6f) }
            }
            else -> b.fill { rrect(c - bw * 0.7f, 0f, bw * 1.4f, 24f, bw * 0.7f) }
        }
        return b.build()
    }

    private fun axis(): Art = ArtBuilder(4f, 4f).apply { fill { circle(2f, 2f, 2f) } }.build()

    // ---------- Pil ve ilerleme ----------

    private fun battery(vertical: Boolean, level: Float): Art {
        if (!vertical) {
            val b = ArtBuilder(24f, 14.4f)
            b.stroke(1.5f) { rrect(0.75f, 1.2f, 20.5f, 12f, 2.4f) }
            b.fill { rrect(21.6f, 4.6f, 2f, 5.2f, 0.9f) }
            if (level > 0f) b.fill { rrect(2.9f, 3.35f, 16.2f * level, 7.7f, 1f) }
            return b.build()
        }
        val b = ArtBuilder(14.4f, 24f)
        b.stroke(1.5f) { rrect(1.2f, 2.75f, 12f, 20.5f, 2.4f) }
        b.fill { rrect(4.6f, 0.4f, 5.2f, 2f, 0.9f) }
        if (level > 0f) b.fill { rrect(3.35f, 21.1f - 16.2f * level, 7.7f, 16.2f * level, 1f) }
        return b.build()
    }

    private fun progressIcon(kind: String, levels: List<Float>): Icon? = when (kind) {
        "circular" -> Icon(24f, 24f, levels.map { ring(it) })
        "semicircular" -> Icon(24f, 12f, levels.map { semi(it) })
        "linear_horizontal" -> Icon(24f, 2f, levels.map { bar(it, 2f) })
        "linear_horizontal_small" -> Icon(24f, 1f, levels.map { bar(it, 1f) })
        "linear_horizontal_large" -> Icon(24f, 3f, levels.map { bar(it, 3f) })
        else -> null
    }

    private fun ring(f: Float): Art {
        val b = ArtBuilder(24f, 24f)
        b.stroke(2f, 0.4f) { circle(12f, 12f, 10f) }
        if (f > 0f) b.stroke(3f) { arc(12f, 12f, 10f, -90f, 360f * f, move = true) }
        return b.build()
    }

    private fun semi(f: Float): Art {
        val b = ArtBuilder(24f, 12f)
        b.stroke(2f, 0.4f) { arc(12f, 10.6f, 9.4f, 180f, 180f, move = true) }
        if (f > 0f) b.stroke(2.6f) { arc(12f, 10.6f, 9.4f, 180f, 180f * f, move = true) }
        return b.build()
    }

    private fun bar(f: Float, h: Float): Art {
        val b = ArtBuilder(24f, h)
        b.fill(0.4f) { rrect(0f, 0f, 24f, h, h / 2) }
        if (f > 0f) b.fill { rrect(0f, 0f, 24f * f, h, h / 2) }
        return b.build()
    }

    private fun pulseRing(f: Float): Art {
        val b = ArtBuilder(24f, 24f)
        b.stroke(1.2f, 0.45f) { circle(12f, 12f, 10.6f) }
        if (f > 0f) b.stroke(2.4f) { arc(12f, 12f, 7.8f, -90f, 360f * f, move = true) }
        return b.build()
    }

    // ---------- Bağlantı ----------

    private fun connectionSize(c: String): Pair<Float, Float> = when (c) {
        "ble" -> 22.026f to 24f
        "link" -> 24f to 15.2f
        else -> 24f to 24f
    }

    private fun connection(c: String, on: Boolean): Art {
        val (w, h) = connectionSize(c)
        val b = ArtBuilder(w, h)
        val a = if (on) 1f else 0.35f
        when (c) {
            "ble" -> b.stroke(2f, a) { m(5f, 7.5f); l(16f, 17f); l(11f, 21.5f); l(11f, 2.5f); l(16f, 7f); l(5f, 16.5f) }
            "link" -> {
                b.stroke(2f, a) { rrect(1f, 2.6f, 12.5f, 10f, 5f) }
                b.stroke(2f, a) { rrect(10.5f, 2.6f, 12.5f, 10f, 5f) }
            }
            else -> for (i in 0..3) {
                val bh = 6f + i * 4.5f
                b.fill(a) { rrect(2.5f + i * 5.2f, 22f - bh, 3.4f, bh, 0.8f) }
            }
        }
        if (!on) b.stroke(2f) { line(w * 0.12f, h * 0.1f, w * 0.88f, h * 0.9f) }
        return b.build()
    }

    // ---------- Hava ----------

    private class C(val x: Float, val y: Float, val r: Float)

    /** Bulut yolu: soldan sağa çemberler, alttan düz taban (ilk ve son çember tabana değer). */
    private fun ArtBuilder.Path.cloud(cs: List<C>) {
        val base = cs.first().y + cs.first().r
        val first = cs.first()
        var start = 90f
        for (i in cs.indices) {
            val c = cs[i]
            val end: Float = if (i == cs.size - 1) 90f else {
                val n = cs[i + 1]
                val p = circleIntersections(c.x, c.y, c.r, n.x, n.y, n.r)!!
                val (px, py) = if (p[1] < p[3]) p[0] to p[1] else p[2] to p[3]
                val a = angleOf(c.x, c.y, px, py)
                val sweep = norm(a - start)
                arc(c.x, c.y, c.r, start, sweep, move = i == 0)
                start = angleOf(n.x, n.y, px, py)
                continue
            }
            arc(c.x, c.y, c.r, start, norm(end - start).let { if (it == 0f) 360f else it }, move = i == 0)
        }
        l(first.x, base)
        z()
    }

    private fun norm(a: Float): Float = ((a % 360f) + 360f) % 360f

    private fun cloudAt(x: Float, y: Float, s: Float): List<C> =
        listOf(C(-5f, -4.5f, 4.5f), C(0.3f, -8.5f, 6f), C(5.8f, -4.5f, 4.5f)).map { C(x + it.x * s, y + it.y * s, it.r * s) }

    /** Büyük bulut (taban 20), yağışlı bulut (taban 15.5), küçük ön bulut. */
    private val bigCloud = cloudAt(12.2f, 20f, 1f)
    private val rainCloud = cloudAt(12.2f, 15.6f, 0.86f)
    private val frontCloud = cloudAt(10.2f, 20.5f, 0.84f)
    private val backCloud = cloudAt(16.2f, 12.5f, 0.62f)

    private fun ArtBuilder.drawCloud(cs: List<C>, outline: Boolean, gap: Boolean) {
        if (gap) {
            clear { cloud(cs) }
            clearStroke(3.2f) { cloud(cs) }
        }
        if (outline) {
            clear { cloud(cs) }
            stroke(SW) { cloud(cs) }
        } else fill { cloud(cs) }
    }

    private fun ArtBuilder.sun(x: Float, y: Float, r: Float, outline: Boolean, rays: Float = 3f, from: Int = 0, to: Int = 7) {
        if (outline) stroke(SW) { circle(x, y, if (outline) r - SW / 2 else r) } else fill { circle(x, y, r) }
        for (i in from..to) {
            val a = Math.toRadians(i * 45.0)
            val cx = cos(a).toFloat(); val sy = sin(a).toFloat()
            stroke(SW) { line(x + cx * (r + 1.5f), y + sy * (r + 1.5f), x + cx * (r + 1.5f + rays), y + sy * (r + 1.5f + rays)) }
        }
    }

    /** Hilal: dış çember eksi kesen çember; tek kapalı yol. */
    private fun ArtBuilder.moon(x: Float, y: Float, r: Float, outline: Boolean) {
        val bx = x + 0.55f * r; val by = y - 0.45f * r; val br = 0.8f * r
        val p = circleIntersections(x, y, r, bx, by, br) ?: return
        val theta = angleOf(x, y, bx, by)
        var s = 0; var e = 2
        var a1 = angleOf(x, y, p[0], p[1]); var a2 = angleOf(x, y, p[2], p[3])
        var sweep = norm(a2 - a1)
        if (norm(theta - a1) < sweep) { val t = a1; a1 = a2; a2 = t; s = 2; e = 0; sweep = norm(a2 - a1) }
        val b1 = angleOf(bx, by, p[e], p[e + 1]); val b2 = angleOf(bx, by, p[s], p[s + 1])
        val phi = angleOf(bx, by, x, y)
        val s1 = norm(b2 - b1)
        val inner = if (norm(phi - b1) < s1) s1 else -(360f - s1)
        val path: ArtBuilder.Path.() -> Unit = { arc(x, y, r, a1, sweep, move = true); arc(bx, by, br, b1, inner); z() }
        if (outline) stroke(SW, 1f, path) else fill(1f, path)
    }

    private fun ArtBuilder.drops(vararg p: Float, len: Float = 3f) {
        var i = 0
        while (i + 1 < p.size) {
            val x = p[i]; val y = p[i + 1]
            stroke(SW) { line(x, y, x - len * 0.35f, y + len) }
            i += 2
        }
    }

    private fun ArtBuilder.flake(x: Float, y: Float, r: Float, w: Float = 1.3f, ticks: Boolean = false) {
        for (k in 0..2) {
            val a = Math.toRadians(90.0 + k * 60)
            val dx = cos(a).toFloat() * r; val dy = sin(a).toFloat() * r
            stroke(w) { line(x - dx, y - dy, x + dx, y + dy) }
        }
        if (ticks) for (k in 0..5) {
            val a = Math.toRadians(90.0 + k * 60)
            val mx = x + cos(a).toFloat() * r * 0.6f; val my = y + sin(a).toFloat() * r * 0.6f
            for (sgn in listOf(-1, 1)) {
                val b = a + Math.toRadians(sgn * 40.0)
                stroke(w) { line(mx, my, mx + cos(b).toFloat() * r * 0.35f, my + sin(b).toFloat() * r * 0.35f) }
            }
        }
    }

    private fun weather(n: String, o: Boolean): Art {
        val b = ArtBuilder(24f, 24f)
        when (n) {
            "unknown" -> {
                b.drawCloud(bigCloud, o, false)
                val q: ArtBuilder.Path.() -> Unit = { arc(12.2f, 11f, 2.4f, 190f, 230f, move = true); l(12.2f, 14.4f) }
                if (o) { b.stroke(1.4f, 1f, q); b.fill { circle(12.2f, 16.8f, 0.9f) } } else { b.clearStroke(1.6f, q); b.clear { circle(12.2f, 16.9f, 1f) } }
            }
            "clear_sky_day" -> b.sun(12f, 12f, 5f, o, rays = 3.5f)
            "scattered_clouds_day" -> { b.sun(15.5f, 8.5f, 3.6f, o, rays = 2.2f); b.drawCloud(frontCloud, o, true) }
            "cloudy_day" -> { b.drawCloud(backCloud, o, false); b.drawCloud(frontCloud, o, true) }
            "rain_with_sun_day" -> { b.sun(17f, 6.5f, 3.2f, o, rays = 2f); b.drawCloud(rainCloud, o, true); b.drops(8f, 18.5f, 12.5f, 18.5f, 17f, 18.5f) }
            "thunderstorm" -> {
                b.drawCloud(rainCloud, o, false)
                val bolt: ArtBuilder.Path.() -> Unit = { poly(13.2f, 15.2f, 9f, 19.8f, 11.8f, 19.8f, 10.4f, 23.6f, 15.6f, 18f, 12.8f, 18f, 14.6f, 15.2f) }
                if (o) b.stroke(1.1f, 1f, bolt) else b.fill(1f, bolt)
            }
            "light_rain" -> { b.drawCloud(rainCloud, o, false); b.drops(9.5f, 18.5f, 15.5f, 18.5f) }
            "medium_rain" -> { b.drawCloud(rainCloud, o, false); b.drops(7f, 18f, 11f, 19.8f, 15f, 18f, 19f, 19.8f, len = 3.6f) }
            "rain_and_snow" -> { b.drawCloud(rainCloud, o, false); b.drops(9f, 18.2f, len = 3.6f); b.flake(15.5f, 20.2f, 2.6f) }
            "light_snow" -> { b.drawCloud(rainCloud, o, false); b.flake(8.8f, 20f, 2.4f); b.flake(15.8f, 20f, 2.4f) }
            "medium_snow", "heavy_snow" -> b.flake(12f, 12f, 10f, w = if (o) 1.4f else 1.9f, ticks = true)
            "wind" -> {
                b.stroke(SW) { m(2f, 8.5f); l(15f, 8.5f); arc(15f, 5.5f, 3f, 90f, -270f) }
                b.stroke(SW) { m(2f, 13f); l(19f, 13f); arc(19f, 10f, 3f, 90f, -270f) }
                b.stroke(SW) { m(2f, 17.5f); l(11f, 17.5f); arc(11f, 20.5f, 3f, -90f, 270f) }
            }
            "mist" -> for ((i, y) in listOf(6f, 10f, 14f, 18f).withIndex()) {
                val x0 = if (i % 2 == 0) 3f else 5.5f
                b.stroke(SW) { line(x0, y, x0 + 15.5f, y) }
            }
            "haze" -> {
                b.sun(12f, 12f, 4.6f, o, rays = 2.6f, from = 4, to = 8)
                b.clear { rect(0f, 13.2f, 24f, 11f) }
                for ((i, y) in listOf(15.5f, 19f, 22.5f).withIndex()) {
                    val x0 = if (i % 2 == 0) 3f else 6f
                    b.stroke(SW) { line(x0, y, x0 + 15f, y) }
                }
            }
            "sandstorm" -> {
                b.stroke(SW) { line(3f, 7.5f, 16f, 7.5f) }
                b.stroke(SW) { line(6f, 12f, 21f, 12f) }
                b.stroke(SW) { line(3f, 16.5f, 14f, 16.5f) }
                b.fill { circle(19.6f, 7.5f, 1.1f) }
                b.fill { circle(3f, 12f, 1.1f) }
                b.fill { circle(17.6f, 16.5f, 1.1f) }
                b.fill { circle(10f, 20.5f, 1.1f) }
            }
            "clear_sky_night" -> b.moon(11f, 12.5f, 8.5f, o)
            "scattered_clouds_night" -> { b.moon(14.5f, 8.5f, 5.2f, o); b.drawCloud(frontCloud, o, true) }
            "rain_with_sun_night" -> { b.moon(16.5f, 6.8f, 4.2f, o); b.drawCloud(rainCloud, o, true); b.drops(8f, 18.5f, 12.5f, 18.5f, 17f, 18.5f) }
        }
        return b.build()
    }

    // ---------- Sabit simgeler ----------

    private fun static(name: String): Art = when {
        name.startsWith("ic_wf_calorie_burn") -> flame()
        name.startsWith("ic_wf_calorie_cake") -> cake()
        name.startsWith("ic_wf_calorie_ice_cream") -> iceCream()
        name.startsWith("ic_wf_calorie_") -> cutlery()
        name == "ic_wf_distance_direction" -> ArtBuilder(20.4f, 24f).apply { fill { poly(10.2f, 0.5f, 20f, 23.5f, 10.2f, 18f, 0.4f, 23.5f) } }.build()
        name == "ic_wf_distance_navigation" -> ArtBuilder(24f, 24f).apply { fill { poly(22.5f, 1.5f, 13.5f, 22.5f, 11f, 13f, 1.5f, 10.5f) } }.build()
        name.startsWith("ic_wf_distance_") -> marker(name.endsWith("_radius"))
        name == "ic_wf_heart_rate_favorite" -> heart(false, false)
        name == "ic_wf_heart_rate_favorite_2" -> heart(true, false)
        name == "ic_wf_heart_rate_beat" -> heart(false, true)
        name.startsWith("ic_wf_heart_rate_") -> heart(true, true)
        name == "ic_wf_step_foot_print" -> ArtBuilder(14f, 24f).apply { foot(0f, 0f, 1f) }.build()
        name == "ic_wf_step_shoe_print" -> ArtBuilder(24f, 24f).apply { foot(1f, 4.5f, 0.8f); foot(12.5f, 0f, 0.8f) }.build()
        name == "ic_wf_step_filp_flops" || name == "ic_wf_step_slippers" -> soles()
        name.contains("boots") -> boot()
        name == "ic_wf_step_women_high_heels" -> heel()
        else -> sneaker()
    }

    private fun flame(): Art = ArtBuilder(16f, 24f).apply {
        fill { m(8f, 23.5f); c(3.6f, 23.5f, 1f, 20.4f, 1f, 16.6f); c(1f, 11.6f, 5.6f, 8.6f, 6.2f, 1f); c(10.6f, 3.6f, 15f, 8.6f, 15f, 15.8f); c(15f, 20.4f, 12.4f, 23.5f, 8f, 23.5f); z() }
        clear { m(8f, 21.8f); c(5.9f, 21.8f, 4.6f, 20.3f, 4.6f, 18.5f); c(4.6f, 16.2f, 6.8f, 14.8f, 7.6f, 11.6f); c(9.9f, 13f, 11.4f, 15.6f, 11.4f, 18.3f); c(11.4f, 20.3f, 10.1f, 21.8f, 8f, 21.8f); z() }
    }.build()

    private fun cutlery(): Art = ArtBuilder(21.6f, 24f).apply {
        stroke(1.8f) { line(5f, 9f, 5f, 23f) }
        for (x in listOf(2f, 5f, 8f)) stroke(1.3f) { line(x, 1f, x, 7f) }
        stroke(1.6f) { m(2f, 7f); q(2f, 10.5f, 5f, 10.8f); q(8f, 10.5f, 8f, 7f) }
        fill { m(15.2f, 23f); l(15.2f, 1f); c(19.4f, 3f, 20f, 8.5f, 19.6f, 13.5f); l(17.2f, 13.5f); l(17.2f, 23f); z() }
    }.build()

    private fun cake(): Art = ArtBuilder(20f, 24f).apply {
        fill { rrect(1f, 11.5f, 18f, 11.5f, 1.5f) }
        clearStroke(1.2f) { m(1f, 15.5f); q(3.25f, 18f, 5.5f, 15.5f); q(7.75f, 13f, 10f, 15.5f); q(12.25f, 18f, 14.5f, 15.5f); q(16.75f, 13f, 19f, 15.5f) }
        fill { rect(9f, 5.5f, 2f, 5f) }
        fill { m(10f, 0.5f); q(12.6f, 3.4f, 10f, 4.8f); q(7.4f, 3.4f, 10f, 0.5f); z() }
    }.build()

    private fun iceCream(): Art = ArtBuilder(13.6f, 24f).apply {
        fill { circle(6.8f, 6.8f, 5.8f) }
        fill { poly(1.4f, 11.8f, 12.2f, 11.8f, 6.8f, 23.5f) }
        clearStroke(1f) { line(0f, 11.6f, 13.6f, 11.6f) }
    }.build()

    private fun marker(radius: Boolean): Art {
        val b = ArtBuilder(if (radius) 18.3f else 16.8f, 24f)
        val cx = if (radius) 9.15f else 8.4f
        val r = if (radius) 6.2f else 7.4f
        val cy = if (radius) 7.2f else 8.4f
        val tip = if (radius) 19.5f else 23.5f
        if (radius) b.stroke(1.3f) { ellipse(cx, 20.8f, 8f, 2.4f) }
        if (radius) b.clearStroke(2.4f) { arc(cx, cy, r, 150f, 240f, move = true); l(cx, tip); z() }
        b.fill { arc(cx, cy, r, 150f, 240f, move = true); l(cx, tip); z() }
        b.clear { circle(cx, cy, r * 0.4f) }
        return b.build()
    }

    private val heartPath: ArtBuilder.Path.() -> Unit = {
        m(12f, 21f); c(5f, 15.8f, 1.2f, 12.3f, 1.2f, 7.2f); c(1.2f, 3.8f, 3.7f, 1.2f, 7f, 1.2f); c(9.1f, 1.2f, 10.9f, 2.4f, 12f, 4.3f)
        c(13.1f, 2.4f, 14.9f, 1.2f, 17f, 1.2f); c(20.3f, 1.2f, 22.8f, 3.8f, 22.8f, 7.2f); c(22.8f, 12.3f, 19f, 15.8f, 12f, 21f); z()
    }
    private val pulse: ArtBuilder.Path.() -> Unit = { m(1f, 11f); l(7f, 11f); l(9f, 7f); l(12f, 15f); l(14.5f, 8.5f); l(16.2f, 11f); l(23f, 11f) }

    private fun heart(outline: Boolean, beat: Boolean): Art {
        val b = ArtBuilder(24f, 22f)
        if (outline) b.stroke(2f, 1f, heartPath) else b.fill(1f, heartPath)
        if (beat) {
            if (outline) { b.clearStroke(3.6f, pulse); b.stroke(1.6f, 1f, pulse) } else b.clearStroke(1.8f, pulse)
        }
        return b.build()
    }

    private fun ArtBuilder.foot(x: Float, y: Float, s: Float) {
        fun X(v: Float) = x + v * s
        fun Y(v: Float) = y + v * s
        fill { ellipse(X(7.2f), Y(11.5f), 4.6f * s, 5.6f * s) }
        fill { ellipse(X(7.8f), Y(20.2f), 3.3f * s, 3.4f * s) }
        for ((cx, cy, r) in listOf(Triple(3.4f, 3.4f, 1.4f), Triple(6f, 2.3f, 1.5f), Triple(8.8f, 2.5f, 1.35f), Triple(11f, 3.7f, 1.15f), Triple(12.6f, 5.6f, 0.95f))) {
            fill { circle(X(cx), Y(cy), r * s) }
        }
    }

    private fun soles(): Art = ArtBuilder(24f, 24f).apply {
        fill { ellipse(7f, 12.5f, 4.6f, 10.5f) }
        fill { ellipse(17f, 12.5f, 4.6f, 10.5f) }
        for (cx in listOf(7f, 17f)) clearStroke(1.3f) { m(cx - 3.6f, 10f); l(cx, 6f); l(cx + 3.6f, 10f) }
    }.build()

    private fun sneaker(): Art = ArtBuilder(24f, 13f).apply {
        fill { m(0.6f, 12.4f); l(0.6f, 6.6f); q(0.6f, 4.2f, 3.2f, 4.2f); l(7f, 4.2f); l(9.2f, 1.4f); q(10.2f, 0.4f, 11.4f, 1.2f); l(14.4f, 4.6f); q(18.6f, 5.2f, 21.8f, 7f); q(23.5f, 8.2f, 23.5f, 10.2f); l(23.5f, 12.4f); z() }
        clearStroke(0.9f) { line(0f, 10.2f, 24f, 10.2f) }
        for (x in listOf(10.5f, 12.6f)) clearStroke(0.8f) { line(x, 3.2f, x + 1.4f, 5.4f) }
    }.build()

    private fun boot(): Art = ArtBuilder(24f, 24f).apply {
        fill { m(4.5f, 0.8f); l(13f, 0.8f); l(13f, 12.5f); q(20f, 13.5f, 22.4f, 16.6f); q(23.5f, 18f, 23.5f, 20.4f); l(23.5f, 23.2f); l(3f, 23.2f); l(3f, 12f); q(3f, 6f, 4.5f, 0.8f); z() }
        clearStroke(0.9f) { line(0f, 20.6f, 24f, 20.6f) }
    }.build()

    private fun heel(): Art = ArtBuilder(24f, 19f).apply {
        fill { m(23.2f, 3.6f); l(23.2f, 18.5f); l(20.8f, 18.5f); l(20.8f, 10.8f); q(14.6f, 12.6f, 9.4f, 17.2f); l(2.2f, 18.5f); q(0.4f, 18.6f, 1f, 16.6f); q(3f, 11.2f, 8.2f, 9.8f); q(13.6f, 8.4f, 18.2f, 3.6f); q(21f, 1.2f, 23.2f, 3.6f); z() }
    }.build()
}
