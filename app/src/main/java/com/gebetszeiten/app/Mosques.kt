package com.gebetszeiten.app

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** [iqamah]: Schlüssel = Kind.name (FAJR, DHUHR …), Wert = "HH:mm". Wird später aus Mawaqit/MasjidBox befüllt. */
data class Mosque(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String,
    val iqamah: Map<String, String> = emptyMap()
)

/** Moscheen aus OpenStreetMap (ohne Google), zwischengespeichert auf dem Handy. */
object Mosques {
    @Volatile
    private var cache: List<Mosque>? = null
    private var centerLat = 0.0
    private var centerLng = 0.0
    private var fetchedAt = 0L
    @Volatile
    var loading = false
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "moscheen.json")

    fun list(ctx: Context): List<Mosque> {
        cache?.let { return it }
        load(ctx)
        return cache ?: emptyList()
    }

    private fun load(ctx: Context) {
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            fetchedAt = o.getLong("at")
            centerLat = o.getDouble("lat")
            centerLng = o.getDouble("lng")
            val arr = o.getJSONArray("items")
            cache = (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                Mosque(m.getString("id"), m.getString("name"), m.getDouble("lat"), m.getDouble("lng"), m.optString("addr"))
            }
        } catch (e: Exception) {
        }
    }

    private fun save(ctx: Context, items: List<Mosque>, lat: Double, lng: Double) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name)
                    .put("lat", it.lat).put("lng", it.lng).put("addr", it.address)
            )
        }
        val o = JSONObject().put("at", System.currentTimeMillis()).put("lat", lat).put("lng", lng).put("items", arr)
        file(ctx).writeText(o.toString())
        fetchedAt = System.currentTimeMillis()
        centerLat = lat
        centerLng = lng
        cache = items
    }

    fun distance(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, r)
        return r[0]
    }

    fun needsRefresh(ctx: Context, lat: Double, lng: Double): Boolean {
        val l = list(ctx)
        if (l.isEmpty()) return true
        if (System.currentTimeMillis() - fetchedAt > 7L * 24 * 3600_000L) return true
        return distance(lat, lng, centerLat, centerLng) > 15_000f
    }

    fun refresh(ctx: Context, lat: Double, lng: Double, cb: (Boolean) -> Unit) {
        if (loading) return
        loading = true
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = try {
                val items = fetch(lat, lng)
                save(ctx.applicationContext, items, lat, lng)
                true
            } catch (e: Exception) {
                false
            }
            loading = false
            main.post { cb(ok) }
        }.start()
    }

    private fun fetch(lat: Double, lng: Double): List<Mosque> {
        val q = "[out:json][timeout:25];" +
            "(nwr[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"](around:25000,$lat,$lng););" +
            "out center tags;"
        val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 45_000
        conn.setRequestProperty("User-Agent", "Gebetszeiten-App (Android)")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val els = JSONObject(body).getJSONArray("elements")
        val out = ArrayList<Mosque>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val center = e.optJSONObject("center")
            val la = if (e.has("lat")) e.getDouble("lat") else center?.getDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.getDouble("lon") else center?.getDouble("lon") ?: continue
            val name = tags.optString("name").ifBlank { tags.optString("name:de").ifBlank { "Moschee" } }
            val addr = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber"))
                .filter { it.isNotBlank() }.joinToString(" ")
            out.add(Mosque(e.getString("type") + e.getLong("id"), name, la, lo, addr))
        }
        return out
    }

    fun nearest(ctx: Context, lat: Double, lng: Double): Pair<Mosque, Float>? =
        list(ctx).map { it to distance(lat, lng, it.lat, it.lng) }.minByOrNull { it.second }

    fun fmtDist(m: Float): String =
        if (m < 1000f) "${(m / 10f).roundToInt() * 10} m" else "%.1f km".format(Locale.GERMAN, m / 1000f)
}
