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
    val category: String = "Moschee",
    /** Eigene Website laut OpenStreetMap (dort steht oft der MasjidBox-/Mawaqit-Link) */
    val website: String? = null
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
    private fun mwFile(ctx: Context) = File(ctx.filesDir, "mawaqit.json")
    private fun lkFile(ctx: Context) = File(ctx.filesDir, "landkarte.json")
    private fun addrFile(ctx: Context) = File(ctx.filesDir, "adressen.json")

    @Volatile
    private var landkarte: List<Mosque>? = null
    private var lkAt = 0L
    @Volatile
    var lkLoading = false
        private set
    @Volatile
    private var addrCache: MutableMap<String, String>? = null

    @Volatile
    private var mawaqit: List<Mosque>? = null
    private var mwAt = 0L
    private var mwLat = 0.0
    private var mwLng = 0.0
    @Volatile
    var mwLoading = false
        private set
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
                    category = m.optString("cat", "Moschee"), website = m.optString("web").ifBlank { null }
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
        val unique = list.distinctBy { it.id }.distinctBy { it.url ?: it.id }
        own = java.util.concurrent.CopyOnWriteArrayList(unique)
        overrides = ov
        if (unique.size != list.size) saveOwn(ctx)
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

    /**
     * Alle Moscheen: eigene + Mawaqit + OpenStreetMap.
     * Doppelte (näher als 80 m an einem besseren Eintrag) werden ausgeblendet.
     */
    /** Alle sichtbaren Orte (ausgeblendete werden weggelassen). */
    fun list(ctx: Context): List<Mosque> {
        val hidden = Prefs.hiddenIds
        return listAll(ctx).filter { it.id !in hidden }
    }

    /** Alle Orte inklusive ausgeblendeter (für die Einstellungen). */
    fun listAll(ctx: Context): List<Mosque> {
        if (osm == null) loadOsm(ctx)
        if (own == null) loadOwn(ctx)
        if (mawaqit == null) loadMw(ctx)
        if (landkarte == null) loadLk(ctx)
        if (addrCache == null) loadAddr(ctx)
        val mine = own ?: emptyList<Mosque>()
        val ov = overrides ?: emptyMap<String, Map<String, String>>()
        val mw = (mawaqit ?: emptyList()).filter { m ->
            mine.none { distance(it.lat, it.lng, m.lat, m.lng) < 80f || (it.url != null && it.url == m.url) }
        }.map { m -> ov[m.id]?.let { m.copy(iqamah = it) } ?: m }
        val good = mine + mw
        // Islam-Landkarte: genaue Namen, aber ohne Zeiten – nach eigenen und Mawaqit
        val lkAll = landkarte ?: emptyList()
        val lkUse = if (Prefs.useLandkarte) lkAll else lkAll.filter { lkMosque.containsMatchIn(it.name) || lkPrayerOrg.containsMatchIn(it.name) }
        val lk = lkUse.filter { l ->
            good.none { distance(it.lat, it.lng, l.lat, l.lng) < 80f }
        }.map { l -> ov[l.id]?.let { l.copy(iqamah = it) } ?: l }
        val better = good + lk
        val fromOsm = (osm ?: emptyList()).filter { o ->
            better.none { distance(it.lat, it.lng, o.lat, o.lng) < 80f }
        }.map { o -> ov[o.id]?.let { o.copy(iqamah = it) } ?: o }
        val ac = addrCache ?: emptyMap<String, String>()
        return (better + fromOsm).distinctBy { it.id }
            .map { m -> if (m.address.isBlank() && ac[m.id] != null) m.copy(address = ac[m.id]!!) else m }
    }

    // ---------------- Islam-Landkarte (Universität Wien) ----------------

    private val lkExclude = Regex(
        "(?i)jugend|frauen|hilf|institut|stiftung|forum|pfadfind|lehrer|bestattung|publi|gmbh|kindergarten|schule|" +
            "akademik|student|seelsorg|hochsch|forschung|kaiciid|dialog|religionsgemeinde|dachverband|dachorganisation|" +
            "union |^union|rat |solidar|initiative|beratung|bildung ist|maarif|isma verein|hasene|human relief|tekaaful|" +
            "takaful|wefa|rahma austria|hania|emus|amade|möh|imö|mlv|mjö|mpö|juwa|juwel|fiuk|cirde|leopold|ifime|" +
            "verband|föderation in wien - frauen|egitim|edukation|club|sport- und kulturverein für"
    )
    private val lkMosque = Regex("(?i)moschee|masjid|mesjid|mescid|camii|cami|dzemat|džemat|xhamia|musalla|gebetsraum|gebetshaus")
    /** Vereine, die erfahrungsgemäß einen Gebetsraum haben (IGGÖ-Moscheegemeinden, Verbände) */
    private val lkPrayerOrg = Regex("(?i)islamisches zentrum|islamzentrum|kulturzentrum|atib|islamische föderation|islamska zajednica|bosniak|džemat|dzemat|zentrum der|moscheegemeinde|kultusgemeinde|tekke|imam")

    private fun loadLk(ctx: Context) {
        try {
            val f = lkFile(ctx)
            if (!f.exists()) {
                landkarte = emptyList()
                return
            }
            val o = JSONObject(f.readText())
            lkAt = o.getLong("at")
            val arr = o.getJSONArray("items")
            landkarte = (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                Mosque(
                    id = m.getString("id"), name = m.getString("name"), lat = m.getDouble("lat"), lng = m.getDouble("lng"),
                    address = "", source = "Islam-Landkarte", category = m.optString("cat", "Gebetsraum / Verein")
                )
            }
        } catch (e: Exception) {
            landkarte = emptyList()
        }
    }

    /** Einmal im Monat neu laden. */
    fun landkarteNeedsRefresh(ctx: Context): Boolean {
        listAll(ctx)
        return System.currentTimeMillis() - lkAt > 30L * 24 * 3600_000L || landkarte.isNullOrEmpty()
    }

    fun refreshLandkarte(ctx: Context, cb: (Boolean) -> Unit) {
        if (lkLoading) return
        lkLoading = true
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val out = ArrayList<Mosque>()
            var anyOk = false
            val urls = try {
                app.assets.open("landkarte_urls.txt").bufferedReader().readLines().filter { it.startsWith("http") }
            } catch (e: Exception) {
                emptyList()
            }
            for (u in urls) {
                try {
                    val html = httpGet(u, browser = true)
                    val json = Regex("(?s)var\\s+locations\\s*=\\s*(\\[.*?]);").find(html)?.groupValues?.get(1) ?: continue
                    val arr = JSONArray(json)
                    anyOk = true
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val la = o.optDouble("lat", 0.0)
                        val lo = o.optDouble("lng", 0.0)
                        if (la < 46.0 || la > 49.2 || lo < 9.3 || lo > 17.3) continue // außerhalb Österreichs / ungültig
                        if (la == 48.0 && lo == 16.0) continue
                        val name = o.optString("title").trim()
                        if (name.isBlank() || lkExclude.containsMatchIn(name)) continue
                        val cat = if (lkMosque.containsMatchIn(name) && !name.contains("Gebetsraum", true)) "Moschee" else "Gebetsraum / Verein"
                        out.add(Mosque("lk-" + o.optInt("id"), name, la, lo, "", source = "Islam-Landkarte", category = cat))
                    }
                } catch (e: Exception) {
                }
            }
            if (anyOk) {
                val arr = JSONArray()
                out.forEach {
                    arr.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lng", it.lng).put("cat", it.category))
                }
                try {
                    lkFile(app).writeText(JSONObject().put("at", System.currentTimeMillis()).put("items", arr).toString())
                } catch (e: Exception) {
                }
                lkAt = System.currentTimeMillis()
                landkarte = out
            }
            lkLoading = false
            main.post { cb(anyOk) }
        }.start()
    }

    // ---------------- Adressen nachschlagen (OpenStreetMap, gespeichert) ----------------

    private fun loadAddr(ctx: Context) {
        val m = HashMap<String, String>()
        try {
            val f = addrFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.keys().forEach { k -> m[k] = o.getString(k) }
            }
        } catch (e: Exception) {
        }
        addrCache = java.util.concurrent.ConcurrentHashMap(m)
    }

    /** Adresse zu einer Position (Straße Hausnummer, PLZ Ort) – wird gespeichert. */
    fun lookupAddress(ctx: Context, m: Mosque): String? {
        listAll(ctx)
        addrCache?.get(m.id)?.let { return it }
        return try {
            val o = JSONObject(
                httpGet(
                    "https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&addressdetails=1&lat=" +
                        "%.6f".format(Locale.US, m.lat) + "&lon=" + "%.6f".format(Locale.US, m.lng)
                )
            )
            val a = o.optJSONObject("address") ?: return null
            val street = listOf(a.optString("road"), a.optString("house_number")).filter { it.isNotBlank() }.joinToString(" ")
            val city = listOf(a.optString("postcode"), a.optString("city").ifBlank { a.optString("town").ifBlank { a.optString("village") } })
                .filter { it.isNotBlank() }.joinToString(" ")
            val res = listOf(street, city).filter { it.isNotBlank() }.joinToString(", ")
            if (res.isBlank()) return null
            addrCache?.put(m.id, res)
            val json = JSONObject()
            addrCache?.forEach { (k, v) -> json.put(k, v) }
            addrFile(ctx).writeText(json.toString())
            res
        } catch (e: Exception) {
            null
        }
    }

    /** Position einer Moschee korrigieren (für fremde Einträge wird eine eigene Kopie angelegt). */
    fun move(ctx: Context, m: Mosque, lat: Double, lng: Double): Mosque {
        list(ctx)
        val moved = if (m.custom) m.copy(lat = lat, lng = lng)
        else m.copy(id = "own-" + m.id, lat = lat, lng = lng, custom = true)
        if (!m.custom) Prefs.hide(m.id)
        addOwn(ctx, moved)
        return moved
    }

    // ---------------- Mawaqit (öffentliche Suche, liefert die heutigen Zeiten mit) ----------------

    private fun loadMw(ctx: Context) {
        try {
            val f = mwFile(ctx)
            if (!f.exists()) {
                mawaqit = emptyList()
                return
            }
            val o = JSONObject(f.readText())
            mwAt = o.getLong("at")
            mwLat = o.getDouble("lat")
            mwLng = o.getDouble("lng")
            val arr = o.getJSONArray("items")
            mawaqit = (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                Mosque(
                    id = m.getString("id"), name = m.getString("name"),
                    lat = m.getDouble("lat"), lng = m.getDouble("lng"), address = m.optString("addr"),
                    source = "Mawaqit", url = m.optString("url").ifBlank { null },
                    adhan = jsonToMap(m.optJSONObject("adhan")), iqamah = jsonToMap(m.optJSONObject("iqamah")),
                    fetchedAt = mwAt
                )
            }
        } catch (e: Exception) {
            mawaqit = emptyList()
        }
    }

    /** Neu laden, wenn älter als 12 Stunden (Zeiten ändern sich täglich) oder weit weg. */
    fun mawaqitNeedsRefresh(ctx: Context, lat: Double, lng: Double): Boolean {
        list(ctx)
        if (System.currentTimeMillis() - mwAt > 12 * 3600_000L) return true
        return distance(lat, lng, mwLat, mwLng) > 10_000f
    }

    fun refreshMawaqit(ctx: Context, lat: Double, lng: Double, cb: (Boolean) -> Unit) {
        if (mwLoading) return
        mwLoading = true
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = try {
                val items = fetchMawaqit(lat, lng)
                val arr = JSONArray()
                items.forEach {
                    arr.put(
                        JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lng", it.lng)
                            .put("addr", it.address).put("url", it.url ?: "")
                            .put("adhan", mapToJson(it.adhan)).put("iqamah", mapToJson(it.iqamah))
                    )
                }
                mwFile(app).writeText(
                    JSONObject().put("at", System.currentTimeMillis()).put("lat", lat).put("lng", lng).put("items", arr).toString()
                )
                mwAt = System.currentTimeMillis()
                mwLat = lat
                mwLng = lng
                mawaqit = items
                true
            } catch (e: Exception) {
                false
            }
            mwLoading = false
            main.post { cb(ok) }
        }.start()
    }

    private val TIME = Regex("\\d{1,2}:\\d{2}")

    /** Ein Mawaqit-Suchergebnis in eine Moschee umwandeln. */
    private fun parseMw(o: JSONObject): Mosque? {
        val la = o.optDouble("latitude", Double.NaN)
        val lo = o.optDouble("longitude", Double.NaN)
        if (la.isNaN() || lo.isNaN()) return null
        val name = o.optString("name").ifBlank { o.optString("label") }.ifBlank { "Moschee" }
        val slug = o.optString("slug")
        val uuid = o.optString("uuid").ifBlank { slug }.ifBlank { "%.5f,%.5f".format(Locale.US, la, lo) }
        val keys = listOf("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA")
        val adhan = LinkedHashMap<String, String>()
        o.optJSONArray("times")?.let { t ->
            var list = (0 until t.length()).map { t.optString(it) }.filter { it.matches(TIME) }
            if (list.size == 6) list = list.filterIndexed { idx, _ -> idx != 1 }
            list.take(5).forEachIndexed { idx, v -> adhan[keys[idx]] = v }
        }
        val iq = LinkedHashMap(mwIqamaToday(o, adhan))
        val jumua = o.optString("jumua").ifBlank { o.optString("jumua1") }
        if (jumua.matches(TIME)) adhan["JUMUAH"] = jumua
        val addr = listOf(o.optString("localisation"), o.optString("address"), o.optString("city"))
            .firstOrNull { it.isNotBlank() } ?: ""
        return Mosque(
            id = "mw-$uuid", name = name, lat = la, lng = lo, address = addr,
            source = "Mawaqit", url = if (slug.isNotBlank()) "https://mawaqit.net/de/$slug" else null,
            adhan = adhan, iqamah = iq, fetchedAt = System.currentTimeMillis()
        )
    }

    /** Den versteckten Datenblock „confData = {…}“ einer Mawaqit-Seite als JSON lesen. */
    private fun mwConf(html: String): JSONObject? {
        val start = Regex("confData\\s*=\\s*\\{").find(html)?.let { it.range.last } ?: return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until html.length) {
            val ch = html[i]
            if (inStr) {
                if (esc) esc = false
                else if (ch == '\\') esc = true
                else if (ch == '"') inStr = false
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return try {
                        JSONObject(html.substring(start, i + 1))
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        return null
    }

    /**
     * Iqamah von heute aus Mawaqit-Daten:
     * „iqamaCalendar“ = 12 Monate, je Tag 5 Werte („+15“ = 15 Min nach Adhan oder feste Uhrzeit),
     * „fixedIqama“ = feste Uhrzeiten, die Vorrang haben.
     */
    private fun mwIqamaToday(o: JSONObject, adhan: Map<String, String>): Map<String, String> {
        val keys = listOf("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA")
        val out = LinkedHashMap<String, String>()
        val today = java.time.LocalDate.now()
        val raw = ArrayList<String>()
        o.optJSONArray("iqamaCalendar")?.let { cal ->
            val month = cal.opt(today.monthValue - 1)
            val day: Any? = when (month) {
                is JSONObject -> month.opt(today.dayOfMonth.toString())
                is JSONArray -> month.opt(today.dayOfMonth - 1) ?: month.opt(today.dayOfMonth)
                else -> null
            }
            when (day) {
                is JSONArray -> for (i in 0 until day.length()) raw.add(day.optString(i))
                is String -> raw.addAll(day.split(","))
            }
        }
        if (raw.isEmpty()) o.optJSONArray("iqama")?.let { a -> for (i in 0 until a.length()) raw.add(a.optString(i)) }
        keys.forEachIndexed { i, k ->
            val v = raw.getOrNull(i)?.trim().orEmpty()
            val a = adhan[k]
            when {
                v.matches(TIME) -> out[k] = v.padStart(5, '0')
                a != null && v.trimStart('+').toIntOrNull() != null -> out[k] = addMin(a, v.trimStart('+').toInt())
            }
        }
        o.optJSONArray("fixedIqama")?.let { fx ->
            for (i in 0 until minOf(5, fx.length())) {
                val v = fx.optString(i).trim()
                if (v.matches(TIME)) out[keys[i]] = v.padStart(5, '0')
            }
        }
        return out
    }

    /** Mawaqit-Suche mit beliebigen Parametern (word=… oder lat=…&lon=…). */
    private fun mawaqitQuery(params: String): List<Mosque> {
        val body = httpGet("https://mawaqit.net/api/2.0/mosque/search?$params", browser = true).trim()
        val arr: JSONArray = when {
            body.startsWith("[") -> JSONArray(body)
            body.startsWith("{") -> JSONObject(body).let { o ->
                o.optJSONArray("data") ?: o.optJSONArray("mosques") ?: o.optJSONArray("results") ?: JSONArray()
            }
            else -> JSONArray()
        }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseMw(it) } }
    }

    private fun fetchMawaqit(lat: Double, lng: Double): List<Mosque> {
        val out = LinkedHashMap<String, Mosque>()
        for (page in 1..3) {
            val list = mawaqitQuery(
                "lat=" + "%.5f".format(Locale.US, lat) + "&lon=" + "%.5f".format(Locale.US, lng) + "&page=" + page
            )
            if (list.isEmpty()) break
            val near = list.filter { distance(lat, lng, it.lat, it.lng) <= 25_000f }
            near.forEach { out[it.id] = it }
            if (near.isEmpty()) break
        }
        return out.values.toList()
    }

    /**
     * Zeiten für eine Moschee ohne Zeiten im Hintergrund suchen:
     * 1. Mawaqit nach Name, 2. Mawaqit nach Position, 3. MasjidBox/Mawaqit-Links aus dem Namen.
     */
    /** Website der Moschee nach einem MasjidBox- oder Mawaqit-Link durchsuchen. */
    private fun timesLinkFromWebsite(web: String): String? {
        if (web.contains("masjidbox.com", true) || web.contains("mawaqit.net", true)) return web
        return try {
            val html = httpGet(web, browser = true)
            Regex("(?i)https?://(?:www\\.)?(?:masjidbox\\.com/(?:gebetszeiten|prayer-times)/[a-z0-9%_\\-]+|mawaqit\\.net/[a-z]{2}/(?:m/)?[a-z0-9%_\\-]+)")
                .find(html)?.value
        } catch (e: Exception) {
            null
        }
    }

    fun findTimes(m: Mosque): Draft? {
        // 0) Eigene Website der Moschee (aus OpenStreetMap) → verlinkte MasjidBox-/Mawaqit-Seite
        m.website?.let { w ->
            val link = timesLinkFromWebsite(w)
            if (link != null) {
                val d = try { fetchDraft(link) } catch (e: Exception) { null }
                if (d != null && (d.adhan.isNotEmpty() || d.iqamah.isNotEmpty())) return d
            }
        }
        val cands = ArrayList<Mosque>()
        try {
            cands += mawaqitQuery("word=" + URLEncoder.encode(m.name, "UTF-8"))
        } catch (e: Exception) {
        }
        try {
            cands += mawaqitQuery("lat=" + "%.5f".format(Locale.US, m.lat) + "&lon=" + "%.5f".format(Locale.US, m.lng))
        } catch (e: Exception) {
        }
        val best = cands
            .filter { it.adhan.isNotEmpty() || it.iqamah.isNotEmpty() }
            .map { it to distance(m.lat, m.lng, it.lat, it.lng) }
            .filter { it.second < 400f }
            .minByOrNull { it.second }?.first
        if (best != null) {
            return Draft(best.name, best.address, best.lat, best.lng, "Mawaqit", best.url ?: "https://mawaqit.net", best.adhan, best.iqamah)
        }
        if (m.name.isNotBlank() && !m.name.equals("Moschee", true) && !m.name.equals("Gebetsraum", true)) {
            return searchByName(m.name)
        }
        return null
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

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Was beim letzten Laden einer Moschee-Seite passiert ist (für verständliche Fehlermeldungen). */
    @Volatile
    var lastFetchInfo: String = ""

    /**
     * [browser] = true: wie ein normaler Browser auftreten (für MasjidBox/Mawaqit-Seiten,
     * die App-Anfragen sonst blocken). Für OpenStreetMap-Dienste die eigene Kennung (deren Regeln).
     */
    private fun httpGet(url: String, browser: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", if (browser) BROWSER_UA else UA)
        conn.setRequestProperty("Accept-Language", "de-AT,de;q=0.9,en;q=0.8")
        if (browser) conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            lastFetchInfo = "HTTP $code"
            throw java.io.IOException("HTTP $code")
        }
        // höchstens 4 MB lesen (Schutz vor riesigen oder bösartigen Antworten)
        val body = conn.inputStream.use { ins ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                total += n
                if (total > 4 * 1024 * 1024) break
                buf.write(chunk, 0, n)
            }
            buf.toString("UTF-8")
        }
        conn.disconnect()
        return body
    }

    /** Nur echte Web-Links zulassen (kein file:, content: usw.). */
    fun isWebLink(u: String): Boolean {
        val l = u.trim().lowercase()
        return (l.startsWith("https://") || l.startsWith("http://")) && !l.contains(" ") && l.length < 600
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
                            .put("addr", it.address).put("cat", it.category).put("web", it.website ?: "")
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
        val body = overpass(q)
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
            val web = listOf("website", "contact:website", "url", "contact:facebook").map { tags.optString(it) }
                .firstOrNull { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://$it" }
            out.add(Mosque(e.getString("type") + e.getLong("id"), name, la, lo, addr, category = cat, website = web))
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
    fun countMawaqit(): Int = mawaqit?.size ?: 0

    /** Verständlicher Grund statt Technik („HTTP 404“ → „Seite nicht gefunden“). */
    fun friendlyInfo(): String {
        val i = lastFetchInfo
        val txt = when {
            i.contains("403") || i.contains("401") || i.contains("429") -> t("fi_blocked")
            i.contains("404") || i.contains("410") -> t("fi_notfound")
            i.contains("keine Zeiten") -> t("fi_notimes")
            i.startsWith("OK") -> ""
            i.isBlank() -> ""
            else -> t("fi_unreachable")
        }
        return if (txt.isBlank()) "" else " – $txt"
    }

    /** Automatische Zeitsuche höchstens einmal am Tag pro Moschee. */
    fun shouldAutoFind(ctx: Context, id: String): Boolean {
        val sp = ctx.getSharedPreferences("autosuche", Context.MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()
        if (sp.getString(id, "") == today) return false
        sp.edit().putString(id, today).apply()
        return true
    }
    fun countLandkarte(): Int = landkarte?.size ?: 0

    /** Fehlerart des letzten Ladeversuchs: "offline" oder "busy". */
    @Volatile
    var lastError: String? = null

    private val overpassServers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    /** Fragt nacheinander mehrere Overpass-Server, bis einer antwortet. */
    private fun overpass(q: String): String {
        var last: Exception? = null
        var anyReached = false
        for (server in overpassServers) {
            try {
                val conn = URL(server).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 12_000
                conn.readTimeout = 45_000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                val code = conn.responseCode
                anyReached = true
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    if (body.trimStart().startsWith("{")) {
                        lastError = null
                        return body
                    }
                } else {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        lastError = if (anyReached) "busy" else "offline"
        throw last ?: Exception("Overpass nicht erreichbar")
    }

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
                // „Jumuah 1 13 30“ → die „1“ ist die Nummer des Freitagsgebets, nicht die Stunde
                val skip = if (tok.getOrNull(i + 1)?.matches(Regex("\\d")) == true && timeAt(i + 2) != null &&
                    tok.getOrNull(i + 2)?.matches(Regex("\\d{1,2}(:\\d{2})?")) == true &&
                    (timeAt(i + 1)?.first?.startsWith("0") == true)
                ) 1 else 0
                val t = timeAt(i + 1 + skip)
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

    /**
     * Zeiten aus eingebetteten Skript-Daten lesen, z. B. "fajr":"05:22", "fajrIqamah":"06:07",
     * "iqamah":{"isha":"19:00"} – egal in welcher Struktur, solange Gebetsname und Uhrzeit nah beieinander stehen.
     */
    private fun jsonTimes(html: String): Pair<Map<String, String>, Map<String, String>> {
        val adhan = LinkedHashMap<String, String>()
        val iq = LinkedHashMap<String, String>()
        val keyRe = Regex("(?i)\"([a-z_]*?(fajr|fadjr|subh|dhuhr|duhr|zuhr|dohr|asr|assr|maghrib|isha|ishaa|jumu?a+h?|jummah)[a-z_]*)\"\\s*:\\s*\"(\\d{1,2}:\\d{2})")
        for (m in keyRe.findAll(html)) {
            val key = m.groupValues[1].lowercase()
            val name = m.groupValues[2].lowercase()
            val time = m.groupValues[3].padStart(5, '0')
            val k = labelMap[name] ?: when {
                name.startsWith("fa") || name == "subh" -> "FAJR"
                name.startsWith("d") || name.startsWith("z") -> "DHUHR"
                name.startsWith("a") -> "ASR"
                name.startsWith("m") -> "MAGHRIB"
                name.startsWith("i") -> "ISHA"
                else -> "JUMUAH"
            }
            // Iqamah steht entweder im Schlüssel (fajrIqamah) oder kurz davor ("iqamah":{ ... "fajr":"06:07")
            val before = html.substring(maxOf(0, m.range.first - 120), m.range.first).lowercase()
            val isIq = key.contains("iqam") || key.contains("jamaa") || key.contains("jamat") ||
                (before.lastIndexOf("iqam") > before.lastIndexOf("adhan").coerceAtLeast(before.lastIndexOf("athan")) && before.contains("iqam"))
            if (isIq) iq.putIfAbsent(k, time) else adhan.putIfAbsent(k, time)
        }
        return adhan to iq
    }

    /** MasjidBox-Termine: "name":"🕌 Fadjr Gebet → 05:07","startDate":"2026-10-28T05:07…" – nur heute. */
    private fun eventTimes(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val today = java.time.LocalDate.now().toString()
        val re = Regex("\"name\"\\s*:\\s*\"[^\"]*?([A-Za-zÀ-ÿ']+)\\s+(?:Gebet|Prayer|Prière|Namaz|Namazi)[^\"]*\"[^{}]*?\"startDate\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})")
        for (m in re.findAll(html)) {
            if (m.groupValues[2] != today) continue
            val k = labelMap[m.groupValues[1].lowercase()] ?: continue
            out.putIfAbsent(k, m.groupValues[3])
        }
        return out
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
        if (!isWebLink(url.trim().let { if (it.startsWith("http")) it else "https://$it" })) {
            lastFetchInfo = "kein gültiger Link"
            throw IllegalArgumentException("kein gültiger Link")
        }
        val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val html = httpGet(u, browser = true)
        lastFetchInfo = "OK, ${html.length / 1024} kB"
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { strip(it) }
            ?.takeIf { it.isNotBlank() }
            ?: Regex("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"'](.*?)[\"']").find(html)?.groupValues?.get(1)?.let { decode(it) }
            ?: Regex("(?is)<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)?.let { strip(it) }
            ?: ""
        if (u.contains("mawaqit", ignoreCase = true)) {
            mwConf(html)?.let { conf ->
                val keys0 = listOf("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA")
                val adhan0 = LinkedHashMap<String, String>()
                conf.optJSONArray("times")?.let { t ->
                    var list = (0 until t.length()).map { t.optString(it) }.filter { it.matches(TIME) }
                    if (list.size == 6) list = list.filterIndexed { i, _ -> i != 1 }
                    list.take(5).forEachIndexed { i, v -> adhan0[keys0[i]] = v.padStart(5, '0') }
                }
                if (adhan0.isNotEmpty()) {
                    val iq0 = LinkedHashMap(mwIqamaToday(conf, adhan0))
                    val jum = conf.optString("jumua").ifBlank { conf.optString("jumua1") }
                    if (jum.matches(TIME)) adhan0["JUMUAH"] = jum.padStart(5, '0')
                    val nm = conf.optString("name").ifBlank { title.substringBefore(" | ").substringBefore(" - ") }.ifBlank { nameFromUrl(u) }
                    val ad = conf.optString("localisation").ifBlank { conf.optString("address") }
                    val la = conf.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() }
                    val lo = conf.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() }
                    lastFetchInfo = "OK, Mawaqit-Daten, Iqamah: ${iq0.size}"
                    return Draft(nm, ad, la, lo, "Mawaqit", u, adhan0, iq0)
                }
            }
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
        val (a1, i1) = parseTimes(strip(html))
        val (a2, i2) = jsonTimes(html)
        val a3 = eventTimes(html)
        val adhan = LinkedHashMap(a3).apply { putAll(a2); putAll(a1) }
        val iq = LinkedHashMap(i2).apply { putAll(i1) }
        if (adhan.isEmpty() && iq.isEmpty()) lastFetchInfo += ", keine Zeiten erkannt"
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

    /**
     * Vorinstallierte Wiener Moscheen (assets/seed_mosques.json) einmalig übernehmen:
     * Seite laden (Position/Zeiten), sonst Adresse suchen. Läuft im Hintergrund.
     */
    @Volatile
    private var seeding = false

    fun importSeeds(ctx: Context, cb: () -> Unit) {
        synchronized(this) {
            if (seeding) return
            seeding = true
        }
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)
        val json = try {
            JSONObject(app.assets.open("seed_mosques.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            seeding = false
            return
        }
        val ver = json.optInt("version", 1)
        if (sp.getInt("seedVersion", 0) >= ver) {
            seeding = false
            return
        }
        list(app)
        val main = Handler(Looper.getMainLooper())
        Thread {
            val arr = json.getJSONArray("items")
            var added = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.getString("url")
                val seedId = "seed-" + url.substringAfterLast('/')
                val existing = (own ?: emptyList<Mosque>()).indexOfFirst { it.url == url || it.id == seedId }
                if (existing >= 0) {
                    // Vorhandene Moschee: Name/Adresse aus der neuen Liste übernehmen
                    val o2 = own ?: continue
                    val cur = o2[existing]
                    val nn = o.optString("name").ifBlank { cur.name }
                    val na = o.optString("address").ifBlank { cur.address }
                    if (nn != cur.name || na != cur.address) {
                        o2[existing] = cur.copy(name = nn, address = na)
                        added++
                    }
                    continue
                }
                val name = o.optString("name")
                val addr = o.optString("address")
                val timesSource = url.contains("masjidbox", true) || url.contains("mawaqit", true)
                val d = if (!timesSource) null else try {
                    fetchDraft(url)
                } catch (e: Exception) {
                    null
                }
                val pos = if (d?.lat != null && d.lng != null) d.lat to d.lng
                else geocode(addr.ifBlank { d?.address ?: "" }.ifBlank { "$name Wien" })
                if (pos == null) continue
                own?.add(
                    Mosque(
                        id = "seed-" + url.substringAfterLast('/'),
                        name = name.ifBlank { d?.name ?: "Moschee" },
                        lat = pos.first, lng = pos.second,
                        address = addr.ifBlank { d?.address ?: "" },
                        source = if (url.contains("mawaqit")) "Mawaqit" else if (url.contains("masjidbox")) "MasjidBox" else "Liste",
                        url = if (timesSource) url else null,
                        website = if (timesSource) null else url,
                        adhan = d?.adhan ?: emptyMap(), iqamah = d?.iqamah ?: emptyMap(),
                        custom = true, fetchedAt = if (d != null) System.currentTimeMillis() else 0L
                    )
                )
                added++
                Thread.sleep(1100) // Adresssuche nicht überlasten
            }
            saveOwn(app)
            sp.edit().putInt("seedVersion", ver).apply()
            seeding = false
            if (added > 0) main.post { cb() }
        }.start()
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
