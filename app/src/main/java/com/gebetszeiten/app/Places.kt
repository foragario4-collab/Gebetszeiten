package com.gebetszeiten.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Gebetsplatz oder Wudu-Platz, von Nutzern mit Fotos eingetragen (vorerst nur auf dem Handy). */
data class Place(
    val id: String,
    val kind: String,          // "prayer" oder "wudu"
    val name: String,
    val lat: Double,
    val lng: Double,
    val hours: String,
    val tags: List<String>,
    val photos: List<String>,  // Dateipfade
    val createdAt: Long
)

object Places {
    const val CAT_PRAYER = "Gebetsplatz"
    const val CAT_WUDU = "Wudu"

    @Volatile
    private var items: MutableList<Place>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "orte.json")
    fun photoDir(ctx: Context) = File(ctx.filesDir, "fotos").also { it.mkdirs() }
    fun entranceFile(ctx: Context, mosqueId: String) =
        File(File(ctx.filesDir, "eingang").also { it.mkdirs() }, mosqueId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpg")

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /** Alle Fotos einer Moschee (inkl. früherem einzelnen Eingangsfoto), älteste zuerst. */
    fun mosquePhotos(ctx: Context, mosqueId: String): List<File> {
        val out = ArrayList<File>()
        entranceFile(ctx, mosqueId).takeIf { it.exists() }?.let { out.add(it) }
        val dir = File(File(ctx.filesDir, "eingang"), safe(mosqueId))
        dir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name }?.let { out.addAll(it) }
        return out
    }

    fun newMosquePhoto(ctx: Context, mosqueId: String): File {
        val dir = File(File(ctx.filesDir, "eingang"), safe(mosqueId)).also { it.mkdirs() }
        return File(dir, "foto_${System.currentTimeMillis()}.jpg")
    }

    fun list(ctx: Context): List<Place> {
        if (items == null) load(ctx)
        return items ?: emptyList()
    }

    fun get(ctx: Context, id: String): Place? = list(ctx).firstOrNull { it.id == id }

    private fun load(ctx: Context) {
        val out = ArrayList<Place>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val tags = o.optJSONArray("tags") ?: JSONArray()
                    val ph = o.optJSONArray("photos") ?: JSONArray()
                    out.add(
                        Place(
                            o.getString("id"), o.getString("kind"), o.getString("name"),
                            o.getDouble("lat"), o.getDouble("lng"), o.optString("hours"),
                            (0 until tags.length()).map { tags.getString(it) },
                            (0 until ph.length()).map { ph.getString(it) }.filter { File(it).exists() },
                            o.optLong("at")
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        items = out
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        (items ?: emptyList<Place>()).forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("kind", p.kind).put("name", p.name).put("lat", p.lat).put("lng", p.lng)
                    .put("hours", p.hours).put("tags", JSONArray(p.tags)).put("photos", JSONArray(p.photos)).put("at", p.createdAt)
            )
        }
        file(ctx).writeText(arr.toString())
    }

    fun add(ctx: Context, p: Place) {
        list(ctx)
        items?.removeAll { it.id == p.id }
        items?.add(p)
        save(ctx)
    }

    fun delete(ctx: Context, id: String) {
        list(ctx)
        items?.firstOrNull { it.id == id }?.photos?.forEach { File(it).delete() }
        items?.removeAll { it.id == id }
        save(ctx)
    }

    /** Als „Mosque“ für Karte und Liste (Kategorie Gebetsplatz/Wudu). */
    fun asMosques(ctx: Context): List<Mosque> = list(ctx).map { p ->
        Mosque(
            id = p.id, name = p.name, lat = p.lat, lng = p.lng, address = p.hours,
            source = "Eigene", custom = true, category = if (p.kind == "wudu") CAT_WUDU else CAT_PRAYER
        )
    }

    fun isPlace(m: Mosque) = m.category == CAT_PRAYER || m.category == CAT_WUDU

    /**
     * Kamerafoto verkleinern (max. 1600 px), richtig drehen und neu speichern.
     * Durch das Neuspeichern fallen alle versteckten Gerätedaten (EXIF, GPS) weg.
     */
    fun storePhoto(src: File, dest: File): Boolean = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, opts)
        var sample = 1
        while (opts.outWidth / sample > 3200 || opts.outHeight / sample > 3200) sample *= 2
        val raw = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
        val rot = when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, 1600f / maxOf(raw.width, raw.height))
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rot)
        }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        src.delete()
        true
    } catch (e: Exception) {
        false
    }

    /** Kleines Vorschaubild laden. */
    fun thumb(path: String, size: Int = 360): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var s = 1
        while (opts.outWidth / s > size * 2) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) {
        null
    }
}
