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

/**
 * Eine Moschee. [adhan] / [iqamah]: Schlüssel FAJR, DHUHR, ASR, MAGHRIB, ISHA, JUMUAH → "HH:mm".
 * [source]: OSM, MasjidBox, Mawaqit oder Eigene.
 */
data class Mosque(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String,
    val source: String = "OSM",
    val url: String? = null,
    val adhan: Map<String, String> = emptyMap(),
    val iqamah: Map<String, String> = emptyMap(),
    val custom: Boolean = false,
    val fetchedAt: Long = 0L,
    val category: String = "Moschee"
)

object Mosques {
    private const val UA = "Gebetszeiten-App/1.1 (Android; privat)"

    @Volatile
    private var osm: List<Mosque>? = null
    @Volatile
    private var own: MutableList<Mosque>? = null
    @Volatile
    private var overrides: MutableMap<String, Map<String, String>>? = null
    private var centerLat = 0.0
    private var centerLng = 0.0
    private var fetchedAt = 0L
    @Volatile
    var loading = false
        private set

    private fun osmFile(ctx: Context) = File(ctx.filesDir, "moscheen.json")
    private fun ownFile(ctx: Context) = File(ctx.filesDir, "eigene_moscheen.json")

    // ---------------- Laden / Speichern ----------------

    private fun mapToJson(m: Map<String, String>): JSONObject {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    private fun jsonToMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = HashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.getString(k) }
        return out
    }

    private fun loadOsm(ctx: Context) {
        try {
            val f = osmFile(ctx)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            fetchedAt = o.getLong("at")
            centerLat = o.getDouble("lat")
            centerLng = o.getDouble("lng")
            val arr = o.getJSONArray("items")
            osm = (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                Mosque(
                    m.getString("id"), m.getString("name"), m.getDouble("lat"), m.getDouble("lng"), m.optString("addr"),
                    category = m.optString("cat", "Moschee")
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun loadOwn(ctx: Context) {
        val list = ArrayList<Mosque>()
        val ov = HashMap<String, Map<String, String>>()
        try {
            val f = ownFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val arr = o.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    list.add(
                        Mosque(
                            id = m.getString("id"),
                            name = m.getString("name"),
                            lat = m.getDouble("lat"),
                            lng = m.getDouble("lng"),
                            address = m.optString("addr"),
                            source = m.optString("source", "Eigene"),
                            url = m.optString("url").ifBlank { null },
                            adhan = jsonToMap(m.optJSONObject("adhan")),
                            iqamah = jsonToMap(m.optJSONObject("iqamah")),
                            custom = true,
                            fetchedAt = m.optLong("at", 0L),
                            category = m.optString("cat", "Moschee")
                        )
                    )
                }
                val ovo = o.optJSONObject("overrides")
                ovo?.keys()?.forEach { k -> ov[k] = jsonToMap(ovo.optJSONObject(k)) }
            }
        } catch (e: Exception) {
        }
        own = list
        overrides = ov
    }

    private fun saveOwn(ctx: Context) {
        val arr = JSONArray()
        (own ?: emptyList<Mosque>()).forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id).put("name", m.name).put("lat", m.lat).put("lng", m.lng)
                    .put("addr", m.address).put("source", m.source).put("url", m.url ?: "")
                    .put("adhan", mapToJson(m.adhan)).put("iqamah", mapToJson(m.iqamah))
                    .put("at", m.fetchedAt).put("cat", m.category)
            )
        }
        val ovo = JSONObject()
        (overrides ?: emptyMap<String, Map<String, String>>()).forEach { (k, v) -> ovo.put(k, mapToJson(v)) }
        ownFile(ctx).writeText(JSONObject().put("items", arr).put("overrides", ovo).toString())
    }

    /** Alle Moscheen: eigene + OpenStreetMap (OSM-Einträge direkt neben einer eigenen werden ausgeblendet). */
    fun list(ctx: Context): List<Mosque> {
        if (osm == null) loadOsm(ctx)
        if (own == null) loadOwn(ctx)
        val mine = own ?: emptyList<Mosque>()
        val ov = overrides ?: emptyMap<String, Map<String, String>>()
        val fromOsm = (osm ?: emptyList()).filter { o ->
            mine.none { distance(it.lat, it.lng, o.lat, o.lng) < 80f }
        }.map { o -> ov[o.id]?.let { o.copy(iqamah = it) } ?: o }
        return mine + fromOsm
    }

    fun find(ctx: Context, id: String): Mosque? = list(ctx).firstOrNull { it.id == id }

    fun addOwn(ctx: Context, m: Mosque) {
        list(ctx)
        own?.removeAll { it.id == m.id }
        own?.add(0, m.copy(custom = true))
        saveOwn(ctx)
    }

    fun removeOwn(ctx: Context, id: String) {
        list(ctx)
        own?.removeAll { it.id == id }
        saveOwn(ctx)
    }

    /** Iqamah-Zeiten von Hand setzen – für eigene und OSM-Moscheen. */
    fun setIqamah(ctx: Context, id: String, iq: Map<String, String>) {
        list(ctx)
        val o = own
        val idx = o?.indexOfFirst { it.id == id } ?: -1
        if (o != null && idx >= 0) {
            o[idx] = o[idx].copy(iqamah = iq)
        } else {
            overrides?.put(id, iq)
        }
        saveOwn(ctx)
    }

    // ---------------- Hilfen ----------------

    fun distance(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, r)
        return r[0]
    }

    fun nearest(ctx: Context, lat: Double, lng: Double): Pair<Mosque, Float>? =
        list(ctx).map { it to distance(lat, lng, it.lat, it.lng) }.minByOrNull { it.second }

    fun fmtDist(m: Float): String =
        if (m < 1000f) "${(m / 10f).roundToInt() * 10} m" else "%.1f km".format(Locale.GERMAN, m / 1000f)

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "de,en;q=0.8")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return body
    }

    // ---------------- OpenStreetMap ----------------

    fun needsRefresh(ctx: Context, lat: Double, lng: Double): Boolean {
        list(ctx)
        if (osm.isNullOrEmpty()) return true
        if (System.currentTimeMillis() - fetchedAt > 7L * 24 * 3600_000L) return true
        return distance(lat, lng, centerLat, centerLng) > 15_000f
    }

    fun refresh(ctx: Context, lat: Double, lng: Double, cb: (Boolean) -> Unit) {
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = try {
                val items = fetchOsm(lat, lng)
                val arr = JSONArray()
                items.forEach {
                    arr.put(
                        JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lng", it.lng)
                            .put("addr", it.address).put("cat", it.category)
                    )
                }
                osmFile(app).writeText(
                    JSONObject().put("at", System.currentTimeMillis()).put("lat", lat).put("lng", lng).put("items", arr).toString()
                )
                fetchedAt = System.currentTimeMillis()
                centerLat = lat
                centerLng = lng
                osm = items
                true
            } catch (e: Exception) {
                false
            }
            loading = false
            main.post { cb(ok) }
        }.start()
    }

    private val nameRegex = Regex(
        "(?i)moschee|mosque|masjid|mesjid|mescid|camii|cami\\b|džamija|dzamija|džamije|musalla|mussala|musalla|gebetsraum|gebetshaus|islamisch|islamic|islamski|islamsk|İslam|islam\\b"
    )
    private val excludedAmenity = setOf(
        "grave_yard", "school", "kindergarten", "restaurant", "fast_food", "cafe", "bank", "pharmacy",
        "doctors", "dentist", "hospital", "clinic", "parking", "fuel", "university", "college", "library"
    )

    private fun fetchOsm(lat: Double, lng: Double): List<Mosque> {
        val a = "(around:25000,$lat,$lng)"
        val q = "[out:json][timeout:40];(" +
            "nwr[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]$a;" +
            "nwr[\"religion\"=\"muslim\"]$a;" +
            "nwr[\"building\"=\"mosque\"]$a;" +
            "nwr[\"name\"~\"Moschee|Mosque|Masjid|Mesjid|Mescid|Camii|Cami|Džamija|Dzamija|Musalla|Mussala|Gebetsraum|Islamisch|Islamic|Islamski\",i]$a;" +
            ");out center tags;"
        val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val els = JSONObject(body).getJSONArray("elements")
        val out = ArrayList<Mosque>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            if (tags.has("shop")) continue
            if (tags.optString("amenity") in excludedAmenity) continue
            if (tags.optString("landuse") == "cemetery") continue
            val rel = tags.optString("religion")
            if (rel.isNotBlank() && rel != "muslim") continue
            val center = e.optJSONObject("center")
            val la = if (e.has("lat")) e.getDouble("lat") else center?.getDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.getDouble("lon") else center?.getDouble("lon") ?: continue
            val rawName = tags.optString("name").ifBlank { tags.optString("name:de") }
            val worship = tags.optString("amenity") == "place_of_worship" || tags.optString("building") == "mosque"
            // Ohne religion=muslim und ohne Gebetsort: nur aufnehmen, wenn der Name eindeutig passt
            if (rel.isBlank() && !worship && !nameRegex.containsMatchIn(rawName)) continue
            val isMosque = worship && (tags.optString("place_of_worship") != "musalla") ||
                Regex("(?i)moschee|mosque|masjid|mescid|camii|džamija|dzamija").containsMatchIn(rawName)
            val cat = if (isMosque) "Moschee" else "Gebetsraum / Verein"
            val name = rawName.ifBlank { if (isMosque) "Moschee" else "Gebetsraum" }
            val addr = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber"))
                .filter { it.isNotBlank() }.joinToString(" ")
            out.add(Mosque(e.getString("type") + e.getLong("id"), name, la, lo, addr, category = cat))
        }
        // Doppelte (z. B. Gebäude + Punkt derselben Moschee) zusammenfassen
        val unique = ArrayList<Mosque>()
        out.sortedBy { if (it.category == "Moschee") 0 else 1 }.forEach { m ->
            if (unique.none { distance(it.lat, it.lng, m.lat, m.lng) < 40f }) unique.add(m)
        }
        return unique
    }

    /** Link an eine vorhandene Moschee hängen: sie wird zur eigenen Moschee mit Zeiten. */
    fun attachLink(ctx: Context, m: Mosque, d: Draft): Mosque {
        val generic = m.name.isBlank() || m.name.equals("Moschee", true) || m.name.equals("Gebetsraum", true) || m.name.equals("Mosque", true)
        val nm = if (generic) d.name else m.name
        val newM = m.copy(
            id = if (m.custom) m.id else "own-" + m.id,
            name = nm,
            address = m.address.ifBlank { d.address },
            source = d.source, url = d.url,
            adhan = d.adhan, iqamah = if (d.iqamah.isNotEmpty()) d.iqamah else m.iqamah,
            custom = true, fetchedAt = System.currentTimeMillis()
        )
        addOwn(ctx, newM)
        return newM
    }

    /** Wird alle Moscheen-Daten als geladen anzeigen (für Diagnose). */
    fun countOsm(): Int = osm?.size ?: 0
    fun countOwn(): Int = own?.size ?: 0

    // ---------------- Adresse → Position (OpenStreetMap Nominatim) ----------------

    /** Adresse → Position. Bevorzugt Treffer in deiner Umgebung (±0,6°), erst danach weltweit. */
    fun geocode(query: String, nearLat: Double = Prefs.lat, nearLng: Double = Prefs.lng): Pair<Double, Double>? {
        fun ask(extra: String): Pair<Double, Double>? = try {
            val arr = JSONArray(
                httpGet(
                    "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                        URLEncoder.encode(query, "UTF-8") + extra
                )
            )
            if (arr.length() == 0) null
            else arr.getJSONObject(0).let { it.getString("lat").toDouble() to it.getString("lon").toDouble() }
        } catch (e: Exception) {
            null
        }
        val box = "&bounded=1&viewbox=%.4f,%.4f,%.4f,%.4f".format(
            Locale.US, nearLng - 0.6, nearLat + 0.6, nearLng + 0.6, nearLat - 0.6
        )
        return ask(box) ?: ask("")
    }

    // ---------------- MasjidBox / Mawaqit ----------------

    private fun decode(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    private fun strip(html: String): String = decode(
        html.replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
    ).replace(Regex("\\s+"), " ").trim()

    private val labelMap = mapOf(
        "fadjr" to "FAJR", "fajr" to "FAJR", "fadschr" to "FAJR", "sobh" to "FAJR", "subh" to "FAJR", "sabah" to "FAJR",
        "duhr" to "DHUHR", "dhuhr" to "DHUHR", "zuhr" to "DHUHR", "dohr" to "DHUHR", "dhohr" to "DHUHR", "öğle" to "DHUHR",
        "assr" to "ASR", "asr" to "ASR", "ikindi" to "ASR",
        "maghrib" to "MAGHRIB", "maghreb" to "MAGHRIB", "akşam" to "MAGHRIB",
        "ishaa" to "ISHA", "isha" to "ISHA", "icha" to "ISHA", "yatsı" to "ISHA",
        "jumuah" to "JUMUAH", "jumua" to "JUMUAH", "jumu'ah" to "JUMUAH", "jummah" to "JUMUAH", "joumouaa" to "JUMUAH", "cuma" to "JUMUAH"
    )

    /** Liest „Label hh mm Iqâma hh mm“-Folgen aus dem Seitentext. */
    private fun parseTimes(text: String): Pair<Map<String, String>, Map<String, String>> {
        val tok = text.split(' ').filter { it.isNotBlank() }
        val adhan = LinkedHashMap<String, String>()
        val iq = LinkedHashMap<String, String>()
        fun timeAt(i: Int): Pair<String, Int>? {
            val a = tok.getOrNull(i) ?: return null
            Regex("^(\\d{1,2})[:h.](\\d{2})$").find(a)?.let {
                val h = it.groupValues[1].toInt()
                if (h < 24) return "%02d:%s".format(h, it.groupValues[2]) to i + 1
            }
            val b = tok.getOrNull(i + 1) ?: return null
            if (a.matches(Regex("\\d{1,2}")) && b.matches(Regex("\\d{2}"))) {
                val h = a.toInt()
                val m = b.toInt()
                if (h < 24 && m < 60) return "%02d:%02d".format(h, m) to i + 2
            }
            return null
        }
        var i = 0
        while (i < tok.size) {
            val key = labelMap[tok[i].lowercase().trim(':', ',', '.', '*')]
            if (key != null && key !in adhan) {
                val t = timeAt(i + 1)
                if (t != null) {
                    adhan[key] = t.first
                    var j = t.second
                    if (tok.getOrNull(j)?.lowercase()?.startsWith("iq") == true) {
                        val t2 = timeAt(j + 1)
                        if (t2 != null) {
                            iq[key] = t2.first
                            j = t2.second
                        }
                    }
                    i = j
                    continue
                }
            }
            i++
        }
        return adhan to iq
    }

    private fun jsonStr(raw: String): String = try {
        JSONObject("{\"a\":\"$raw\"}").getString("a")
    } catch (e: Exception) {
        raw
    }

    private fun addMin(t: String, min: Int): String {
        val p = t.split(":")
        val total = (p[0].toInt() * 60 + p[1].toInt() + min + 1440) % 1440
        return "%02d:%02d".format(total / 60, total % 60)
    }

    /** Name aus dem Link bilden, z. B. …/orientalisches-kulturzentrum-al-rahma → Orientalisches Kulturzentrum Al-Rahma */
    fun nameFromUrl(url: String): String {
        val slug = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (slug.isBlank()) return "Moschee"
        return slug.split('-').filter { it.isNotBlank() }.joinToString(" ") { w ->
            when {
                w.length <= 2 && w.lowercase() in setOf("al", "el", "ar", "an", "as", "at", "ad") -> w.replaceFirstChar { it.uppercase() } + "-"
                else -> w.replaceFirstChar { it.uppercase() }
            }
        }.replace("- ", "-")
    }

    data class Draft(
        val name: String,
        val address: String,
        val lat: Double?,
        val lng: Double?,
        val source: String,
        val url: String,
        val adhan: Map<String, String>,
        val iqamah: Map<String, String>
    )

    /** Lädt eine MasjidBox- oder Mawaqit-Seite und liest Name, Adresse, Position und Zeiten. */
    fun fetchDraft(url: String): Draft {
        val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val html = httpGet(u)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { strip(it) }
            ?.takeIf { it.isNotBlank() }
            ?: Regex("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"'](.*?)[\"']").find(html)?.groupValues?.get(1)?.let { decode(it) }
            ?: Regex("(?is)<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)?.let { strip(it) }
            ?: ""
        if (u.contains("mawaqit", ignoreCase = true)) {
            fun f(key: String) = Regex("\"$key\"\\s*:\\s*\"(.*?)(?<!\\\\)\"").find(html)?.groupValues?.get(1)?.let { jsonStr(it) }
            fun n(key: String) = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(html)?.groupValues?.get(1)?.toDoubleOrNull()
            val name = f("name")?.takeIf { it.isNotBlank() }
                ?: title.substringBefore(" - ").takeIf { it.isNotBlank() }
                ?: nameFromUrl(u)
            val addr = f("localisation") ?: f("address") ?: ""
            val keys = listOf("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA")
            val adhan = LinkedHashMap<String, String>()
            val iq = LinkedHashMap<String, String>()
            Regex("\"times\"\\s*:\\s*\\[(.*?)]").find(html)?.groupValues?.get(1)?.let { arr ->
                var list = Regex("\"(\\d{1,2}:\\d{2})\"").findAll(arr).map { it.groupValues[1] }.toList()
                if (list.size == 6) list = list.filterIndexed { i, _ -> i != 1 }
                list.take(5).forEachIndexed { i, t -> adhan[keys[i]] = t }
            }
            val fixed = Regex("\"fixedIqama\"\\s*:\\s*\\[(.*?)]").find(html)?.groupValues?.get(1)
                ?.let { a -> Regex("\"(.*?)\"").findAll(a).map { it.groupValues[1] }.toList() } ?: emptyList()
            val offs = Regex("\"iqama\"\\s*:\\s*\\[(.*?)]").find(html)?.groupValues?.get(1)
                ?.let { a -> Regex("\"\\+?(\\d{1,3})\"|\\b(\\d{1,3})\\b").findAll(a).map { (it.groupValues[1].ifBlank { it.groupValues[2] }).toInt() }.toList() }
                ?: emptyList()
            keys.forEachIndexed { i, k ->
                val fx = fixed.getOrNull(i)
                val a = adhan[k]
                when {
                    fx != null && fx.matches(Regex("\\d{1,2}:\\d{2}")) -> iq[k] = fx
                    a != null && offs.getOrNull(i) != null -> iq[k] = addMin(a, offs[i])
                }
            }
            Regex("\"jumua\"\\s*:\\s*\"(\\d{1,2}:\\d{2})\"").find(html)?.let { adhan["JUMUAH"] = it.groupValues[1] }
            if (adhan.isEmpty()) {
                val (a2, i2) = parseTimes(strip(html))
                adhan.putAll(a2); iq.putAll(i2)
            }
            return Draft(name, addr, n("latitude"), n("longitude"), "Mawaqit", u, adhan, iq)
        }
        // MasjidBox (und andere Seiten): Text auslesen
        val cleaned = title
            .replace(Regex("(?i)^(Gebetszeiten von|Prayer times of|Prayer times for|Horaires de prière de|Horaires des prières de)\\s+"), "")
            .replace(Regex("(?i)\\s*[-|–]\\s*Masjidbox.*$"), "")
            .trim()
        val name = cleaned.takeIf { it.isNotBlank() && !it.equals("Masjidbox", true) && !it.equals("Moschee", true) }
            ?: nameFromUrl(u)
        val addr = Regex("(?is)<h2[^>]*>(.*?)</h2>").find(html)?.groupValues?.get(1)?.let { strip(it) } ?: ""
        val (adhan, iq) = parseTimes(strip(html))
        return Draft(name, addr, null, null, if (u.contains("masjidbox", true)) "MasjidBox" else "Webseite", u, adhan, iq)
    }

    private fun slugs(name: String): List<String> {
        val base = name.trim().lowercase()
        fun clean(x: String) = x.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val de = base.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        val plain = java.text.Normalizer.normalize(base.replace("ß", "ss"), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return listOf(clean(de), clean(plain)).filter { it.isNotBlank() }.distinct()
    }

    /** Sucht eine Moschee per Name bei MasjidBox und Mawaqit (probiert die üblichen Links durch). */
    fun searchByName(name: String): Draft? {
        val urls = slugs(name).flatMap {
            listOf(
                "https://masjidbox.com/gebetszeiten/$it",
                "https://masjidbox.com/prayer-times/$it",
                "https://mawaqit.net/de/$it",
                "https://mawaqit.net/de/m/$it"
            )
        }
        for (u in urls) {
            try {
                val d = fetchDraft(u)
                if (d.adhan.isNotEmpty() || d.iqamah.isNotEmpty()) return d
            } catch (e: Exception) {
            }
        }
        return null
    }

    /** Eigene Moscheen mit Link: Zeiten höchstens alle 12 Stunden neu holen. */
    fun refreshOwnTimes(ctx: Context, cb: () -> Unit) {
        list(ctx)
        val due = (own ?: emptyList<Mosque>()).filter { it.url != null && System.currentTimeMillis() - it.fetchedAt > 12 * 3600_000L }
        if (due.isEmpty()) return
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            var changed = false
            due.forEach { m ->
                try {
                    val d = fetchDraft(m.url!!)
                    if (d.adhan.isNotEmpty()) {
                        val o = own ?: return@forEach
                        val idx = o.indexOfFirst { it.id == m.id }
                        if (idx >= 0) {
                            o[idx] = o[idx].copy(
                                adhan = d.adhan,
                                iqamah = if (d.iqamah.isNotEmpty()) d.iqamah else o[idx].iqamah,
                                fetchedAt = System.currentTimeMillis()
                            )
                            changed = true
                        }
                    }
                } catch (e: Exception) {
                }
            }
            if (changed) {
                saveOwn(app)
                main.post { cb() }
            }
        }.start()
    }
}
