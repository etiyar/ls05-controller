package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.10: son görülen yer ve saatteki kadran. Koordinatlar uydurmadır. */
class V010Test {

    private val now = 1_800_000_000_000L
    private fun fix(ageMs: Long, acc: Float = 30f) = SeenFix(41.0, 29.0, acc, now - ageMs)

    // ---------- Son görülen yer ----------

    @Test
    fun pickNewestFresh() {
        val a = fix(60_000)
        val b = fix(10_000)
        val c = fix(200_000)
        assertEquals(b, LastSeenRules.pick(listOf(a, null, c, b), now))
    }

    @Test
    fun pickRejectsStale() {
        assertNull(LastSeenRules.pick(listOf(fix(LastSeenRules.MAX_AGE_MS + 1), null), now))
        assertEquals(now - LastSeenRules.MAX_AGE_MS, LastSeenRules.pick(listOf(fix(LastSeenRules.MAX_AGE_MS)), now)?.at)
    }

    @Test
    fun pickPrefersFreshOverNewerFuture() {
        val future = fix(-(LastSeenRules.FUTURE_SKEW_MS + 1))
        val ok = fix(30_000)
        assertEquals(ok, LastSeenRules.pick(listOf(future, ok), now))
        val slight = fix(-5_000)
        assertEquals(slight, LastSeenRules.pick(listOf(slight, ok), now))
    }

    @Test
    fun pickRejectsInvalid() {
        assertNull(LastSeenRules.pick(listOf(SeenFix(0.0, 0.0, 10f, now), SeenFix(Double.NaN, 1.0, 10f, now)), now))
        assertNull(LastSeenRules.pick(emptyList(), now))
    }

    @Test
    fun labelFormat() {
        assertEquals("Son görülen: 14:32 (±40 m)", LastSeenRules.label(14, 32, 40.4f))
        assertEquals("Son görülen: 09:05", LastSeenRules.label(9, 5, 0f))
        assertEquals("Son görülen: 00:00 (±1 m)", LastSeenRules.label(0, 0, 0.3f))
        assertEquals("±1,2 km", LastSeenRules.accuracyText(1234f))
        assertEquals("±2 km", LastSeenRules.accuracyText(1990f))
        assertEquals("±999 m", LastSeenRules.accuracyText(999f))
        assertEquals("", LastSeenRules.accuracyText(Float.NaN))
    }

    @Test
    fun geoUriUsesDotDecimals() {
        val f = SeenFix(41.0123456, -8.5, 10f, now)
        assertEquals("geo:41.012346,-8.500000?q=41.012346,-8.500000(Saat)", LastSeenRules.geoUri(f))
    }

    @Test
    fun encodeRoundTrip() {
        val f = SeenFix(41.0123456, 29.9876543, 12.5f, now)
        assertEquals(f, LastSeenRules.decode(LastSeenRules.encode(f)))
        assertNull(LastSeenRules.decode(null))
        assertNull(LastSeenRules.decode(""))
        assertNull(LastSeenRules.decode("1;2;3"))
        assertNull(LastSeenRules.decode("a;2;3;4"))
        assertNull(LastSeenRules.decode("95;2;3;4"))
    }

    @Test
    fun recordBelongsToThisDisconnect() {
        val since = now
        assertTrue(LastSeenRules.belongsTo(SeenFix(41.0, 29.0, 10f, since + 5_000), since))
        assertTrue(!LastSeenRules.belongsTo(SeenFix(41.0, 29.0, 10f, since - 3_600_000), since))
    }

    // ---------- Saatteki kadran (1a 01) ----------

    private fun reply(vararg id: Int) =
        byteArrayOf(0x1a, 0x01, id[0].toByte(), id[1].toByte(), id[2].toByte(), id[3].toByte(),
            0x00, 0xf0.toByte(), 0x00, 0xf0.toByte(), 0x00, 0x00, 0x10, 0x00, 0x00)

    @Test
    fun configReplyUploadedFace() {
        val r = FaceConfig.parse(reply(0x00, 0x00, 0x13, 0x8a))!!
        assertEquals(5002L, r.id)
        assertEquals(240, r.width)
        assertEquals(240, r.height)
        assertTrue(!r.builtin)
        assertEquals(240 to 240, FaceConfig.screen(reply(0x00, 0x00, 0x13, 0x8a)))
    }

    @Test
    fun configReplyBuiltin() {
        val r = FaceConfig.parse(reply(0xff, 0xff, 0xff, 0xff))!!
        assertTrue(r.builtin)
        assertEquals(0xFFFFFFFFL, r.id)
    }

    @Test
    fun configReplyRejectsOthers() {
        assertNull(FaceConfig.parse(byteArrayOf(0x1a, 0x02, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(FaceConfig.parse(byteArrayOf(0x1a, 0x01, 0, 0)))
        assertNull(FaceConfig.parse(byteArrayOf()))
    }

    @Test
    fun fileIdIsLittleEndian() {
        assertEquals(0x138a, FaceConfig.fileId(byteArrayOf(0x8a.toByte(), 0x13, 0, 0)))
        assertEquals(0xF001, FaceConfig.fileId(byteArrayOf(0x01, 0xf0.toByte())))
        assertNull(FaceConfig.fileId(byteArrayOf(1)))
    }

    @Test
    fun matchSingleCandidate() {
        val g = mapOf("a" to 0x138a, "b" to 0xF000)
        assertEquals(FaceMatch.Result.OnWatch("b"), FaceMatch.match(0xF000L, g, ""))
        assertEquals(FaceMatch.Result.OnWatch("b"), FaceMatch.match(0xF000L, g, "a"))
    }

    @Test
    fun matchSharedIdUsesCurrent() {
        val g = mapOf("a" to 0x138a, "b" to 0x138a, "c" to 0xF000)
        assertEquals(FaceMatch.Result.OnWatch("b"), FaceMatch.match(0x138aL, g, "b"))
        assertEquals(FaceMatch.Result.NotInGallery, FaceMatch.match(0x138aL, g, "c"))
        assertEquals(FaceMatch.Result.NotInGallery, FaceMatch.match(0x138aL, g, ""))
    }

    @Test
    fun matchBuiltinAndMissing() {
        val g = mapOf("a" to 0x138a)
        assertEquals(FaceMatch.Result.Builtin, FaceMatch.match(FaceConfig.BUILTIN, g, "a"))
        assertEquals(FaceMatch.Result.NotInGallery, FaceMatch.match(0x1234L, g, "a"))
        assertEquals(FaceMatch.Result.NotInGallery, FaceMatch.match(0x138aL, emptyMap(), ""))
    }
}
