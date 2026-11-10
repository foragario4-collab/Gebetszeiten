package com.gebetszeiten.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Echte Fahr-/Gehzeiten über Straßen (OSRM auf den OpenStreetMap-Servern von FOSSGIS, ohne Google).
 * Ein Aufruf berechnet die Zeiten zu vielen Moscheen auf einmal. Ergebnisse werden 10 Minuten gemerkt.
 */
object Routing {
    private const val UA = "Gebetszeiten-App/3.7 (Android; privat)"
    private data class Key(val mode: Int, val lat: Int, val lng: Int)
    private val cache = HashMap<Key, Pair<Long, Map<String, Int>>>()

    /** Sekunden je Moschee-ID. Leere Map, wenn der Server nicht erreichbar ist. */
    fun durations(lat: Double, lng: Double, mosques: List<Mosque>, mode: Int): Map<String, Int> {
        if (mosques.isEmpty()) return emptyMap()
        val key = Key(mode, (lat * 1000).toInt(), (lng * 1000).toInt())
        synchronized(cache) {
            cache[key]?.let { (at, map) ->
                if (System.currentTimeMillis() - at < 10 * 60_000L && mosques.all { map.containsKey(it.id) }) return map
            }
        }
        val list = mosques.take(60)
        val coords = buildString {
            append("%.5f,%.5f".format(Locale.US, lng, lat))
            list.forEach { append(";").append("%.5f,%.5f".format(Locale.US, it.lng, it.lat)) }
        }
        val profile = if (mode == 0) "routed-car/table/v1/driving" else "routed-foot/table/v1/foot"
        val url = "https://routing.openstreetmap.de/$profile/$coords?sources=0&annotations=duration"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) {
                conn.disconnect()
                return emptyMap()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val row = JSONObject(body).getJSONArray("durations").getJSONArray(0)
            val out = HashMap<String, Int>()
            list.forEachIndexed { i, m ->
                val v = row.optDouble(i + 1, Double.NaN)
                if (!v.isNaN()) out[m.id] = v.toInt()
            }
            synchronized(cache) { cache[key] = System.currentTimeMillis() to out }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Minuten für die Anzeige. Auto: Routenzeit + 15 % Stadtverkehr + 1 Min Parken (Navis rechnen mit Verkehr).
     * Ohne Route: Schätzung aus der Luftlinie.
     */
    fun minutes(seconds: Int?, distM: Float, mode: Int): Int {
        if (seconds == null) return travelMin(distM, mode)
        return if (mode == 0) Math.round(seconds * 1.15f / 60f + 1f).coerceAtLeast(1)
        else Math.round(seconds / 60f).coerceAtLeast(1)
    }
}
