package com.che.ls05

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import com.che.ls05.protocol.FaceConfig
import com.che.ls05.protocol.FaceFile
import java.io.File

/**
 * Kadranlarım: kullanıcının eklediği kadran dosyaları. Uygulamanın özel klasöründe kopya olarak durur;
 * kaynak dosya silinse de kaybolmaz. Yedeklenmez (manifestte yedek kapalı).
 */
object FaceLibrary {

    class Entry(val id: String, val name: String, val file: File) {
        val sizeKb: Int get() = (file.length() / 1024).toInt()
    }

    private const val MAX_NAME = 24
    private val previews = HashMap<String, Bitmap?>()

    private fun dir(c: Context): File = File(c.filesDir, "faces").apply { mkdirs() }

    /** Eklenme sırasına göre (eski → yeni). */
    fun list(c: Context): List<Entry> =
        dir(c).listFiles { f -> f.name.endsWith(".face") }.orEmpty()
            .sortedBy { it.name }
            .map { f ->
                val id = f.name.removeSuffix(".face")
                Entry(id, File(dir(c), "$id.name").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { "Kadran" }, f)
            }

    fun get(c: Context, id: String): Entry? = list(c).firstOrNull { it.id == id }

    /** Ekleme sonucu: galeri girdisi ve (tbwf ise) çizilemeyen öğe uyarıları. */
    class Added(val entry: Entry, val warnings: List<String>, val compiled: Boolean)

    /**
     * Doğrular ve kopyalar. .tbwf tasarım dosyası ise önce kadrana derlenir; derlenen dosya <id>.face,
     * özgün dosya <id>.tbwf olarak saklanır. İş arka planda yapılır, sonuç ana iş parçacığında [done]'a gelir.
     */
    fun add(c: Context, bytes: ByteArray, name: String, done: (Result<Added>) -> Unit) {
        val app = c.applicationContext
        val main = Handler(app.mainLooper)
        Thread({
            val r = try {
                if (TbwfCompiler.isTbwf(bytes)) addTbwf(app, bytes, name) else addBin(app, bytes, name)
            } catch (e: Throwable) {
                Result.failure(Exception("Kadran eklenemedi (${e.javaClass.simpleName})."))
            }
            main.post { done(r) }
        }, "kadran-ekle").start()
    }

    private fun cleanName(name: String): String =
        name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).ifEmpty { "Kadran" }

    private fun addBin(c: Context, bytes: ByteArray, name: String): Result<Added> {
        FaceFile.parse(bytes).exceptionOrNull()?.let { return Result.failure(it) }
        list(c).firstOrNull { it.file.length() == bytes.size.toLong() && it.file.readBytes().contentEquals(bytes) }
            ?.let { return Result.success(Added(it, emptyList(), false)) }
        return Result.success(Added(store(c, bytes, null, cleanName(name)), emptyList(), false))
    }

    private fun addTbwf(c: Context, zip: ByteArray, name: String): Result<Added> {
        list(c).firstOrNull { e ->
            val t = File(dir(c), "${e.id}.tbwf")
            t.exists() && t.length() == zip.size.toLong() && t.readBytes().contentEquals(zip)
        }?.let { return Result.success(Added(it, emptyList(), true)) }
        val out = TbwfCompiler(c).compile(zip, freeFaceId(c), name).getOrElse { return Result.failure(it) }
        return Result.success(Added(store(c, out.bytes, zip, cleanName(out.name)), out.warnings, true))
    }

    private fun store(c: Context, face: ByteArray, tbwf: ByteArray?, name: String): Entry {
        var id = System.currentTimeMillis()
        while (File(dir(c), "$id.face").exists()) id++
        File(dir(c), "$id.face").writeBytes(face)
        if (tbwf != null) File(dir(c), "$id.tbwf").writeBytes(tbwf)
        File(dir(c), "$id.name").writeText(name)
        return Entry(id.toString(), name, File(dir(c), "$id.face"))
    }

    /** Saatin 1a 01 yanıtında bildirdiği kimlik: dosyanın ilk 2 baytı (u16 LE). Okunamazsa null. */
    fun headerId(e: Entry): Int? = try {
        e.file.inputStream().use { s ->
            val b = ByteArray(2)
            if (s.read(b) == 2) FaceConfig.fileId(b) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Galeri kaydı → dosya kimliği (kadran eşleme için). */
    fun headerIds(c: Context): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (e in list(c)) headerId(e)?.let { out[e.id] = it }
        return out
    }

    /** Derlenen kadranlar için galeride kullanılmayan kimlik (0xF000..0xFFFE). */
    private fun freeFaceId(c: Context): Int {
        val used = HashSet<Int>(headerIds(c).values)
        for (id in 0xF000..0xFFFE) if (id !in used) return id
        return 0xF000
    }

    fun delete(c: Context, id: String) {
        File(dir(c), "$id.face").delete()
        File(dir(c), "$id.name").delete()
        File(dir(c), "$id.tbwf").delete()
        previews.remove(id)
        if (Prefs.currentFace(c) == id) Prefs.setCurrentFace(c, "")
    }

    fun load(e: Entry): FaceFile? = FaceFile.parse(e.file.readBytes()).getOrNull()

    /** Dosyadaki önizleme resmi (240x240). Bir kez çözülür, sonra bellekten. */
    fun preview(e: Entry): Bitmap? = previews.getOrPut(e.id) {
        val f = load(e) ?: return@getOrPut null
        val px = f.previewPixels() ?: return@getOrPut null
        Bitmap.createBitmap(px, f.width, f.height, Bitmap.Config.ARGB_8888)
    }
}
