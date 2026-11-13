package com.che.ls05

import android.content.Context
import android.graphics.Bitmap
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

    /** Doğrular ve kopyalar. Aynı içerik zaten varsa onu döndürür. */
    fun add(c: Context, bytes: ByteArray, name: String): Result<Entry> {
        FaceFile.parse(bytes).exceptionOrNull()?.let { return Result.failure(it) }
        list(c).firstOrNull { it.file.length() == bytes.size.toLong() && it.file.readBytes().contentEquals(bytes) }
            ?.let { return Result.success(it) }
        val id = System.currentTimeMillis().toString()
        File(dir(c), "$id.face").writeBytes(bytes)
        val clean = name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).ifEmpty { "Kadran" }
        File(dir(c), "$id.name").writeText(clean)
        return Result.success(Entry(id, clean, File(dir(c), "$id.face")))
    }

    fun delete(c: Context, id: String) {
        File(dir(c), "$id.face").delete()
        File(dir(c), "$id.name").delete()
        previews.remove(id)
        if (Prefs.currentFace(c) == id) Prefs.setCurrentFace(c, "")
    }

    fun load(e: Entry): FaceFile? = FaceFile.parse(e.file.readBytes()).getOrNull()

    /** Dosyadaki önizleme resmi (240x240). Bir kez çözülür, sonra bellekten. */
    fun preview(e: Entry): Bitmap? = previews.getOrPut(e.id) {
        val f = load(e) ?: return@getOrPut null
        val el = f.previewElement() ?: return@getOrPut null
        Bitmap.createBitmap(f.pixels(el), el.width, el.height, Bitmap.Config.ARGB_8888)
    }
}
