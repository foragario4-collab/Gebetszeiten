package com.gebetszeiten.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Aktuelles Wetter als Bausteine für den Himmel (frei kombinierbar, wie das echte Wetter). */
data class Wx(
    val cloud: Float,      // 0..1 Bewölkung
    val rain: Float,       // 0..1 Regenstärke
    val snow: Boolean,     // Schnee statt Regen
    val thunder: Boolean,  // Gewitter (auch trocken)
    val fog: Float,        // 0..1 Nebel
    val wind: Float,       // 0..1 Wind
    val temp: Int?,        // °C
    val at: Long
) {
    fun label(): String = when {
        thunder -> t("wx_thunder")
        snow && rain > 0.03f -> t("wx_snow")
        rain > 0.03f -> t("wx_rain")
        fog > 0.5f -> t("wx_fog")
        cloud > 0.5f -> t("wx_cloudy")
        else -> t("wx_clear")
    }
}

/**
 * Wetter von MET Norway (api.met.no): kostenlos, auch kommerziell, ohne Schlüssel.
 * Bedingungen: eindeutige App-Kennung, Antworten zwischenspeichern, Quelle nennen.
 */
object Weather {
    private const val UA = "Hadaf-Gebetszeiten/5.0 (github.com/foragario4-collab/Gebetszeiten)"
    @Volatile private var mem: Wx? = null
    @Volatile private var memLat = 0.0
    @Volatile private var memLng = 0.0
    @Volatile private var loading = false

    fun current(ctx: Context): Wx? {
        if (mem == null) load(ctx)
        return mem
    }

    private fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences("wetter", Context.MODE_PRIVATE)
        val raw = sp.getString("wx", null) ?: return
        try {
            val o = JSONObject(raw)
            mem = Wx(
                o.getDouble("c").toFloat(), o.getDouble("r").toFloat(), o.getBoolean("s"), o.getBoolean("t"),
                o.getDouble("f").toFloat(), o.getDouble("w").toFloat(), if (o.has("temp")) o.getInt("temp") else null, o.getLong("at")
            )
            memLat = o.optDouble("lat", 0.0)
            memLng = o.optDouble("lng", 0.0)
        } catch (e: Exception) {
        }
    }

    /** Neu holen, wenn älter als 30 Min oder mehr als 5 km entfernt. Läuft im Hintergrund. */
    fun refreshIfNeeded(ctx: Context, lat: Double, lng: Double, done: () -> Unit) {
        current(ctx)
        val m = mem
        val fresh = m != null && System.currentTimeMillis() - m.at < 30 * 60_000L &&
            Mosques.distance(lat, lng, memLat, memLng) < 5_000f
        if (fresh || loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                val w = fetch(lat, lng)
                if (w != null) {
                    mem = w
                    memLat = lat
                    memLng = lng
                    val o = JSONObject().put("c", w.cloud.toDouble()).put("r", w.rain.toDouble()).put("s", w.snow)
                        .put("t", w.thunder).put("f", w.fog.toDouble()).put("w", w.wind.toDouble()).put("at", w.at)
                        .put("lat", lat).put("lng", lng)
                    if (w.temp != null) o.put("temp", w.temp)
                    app.getSharedPreferences("wetter", Context.MODE_PRIVATE).edit().putString("wx", o.toString()).apply()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { done() }
                }
            } catch (e: Exception) {
            }
            loading = false
        }.start()
    }

    private fun fetch(lat: Double, lng: Double): Wx? {
        // MET verlangt höchstens 4 Nachkommastellen
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=" +
            "%.4f".format(Locale.US, lat) + "&lon=" + "%.4f".format(Locale.US, lng)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val ts = JSONObject(body).getJSONObject("properties").getJSONArray("timeseries")
        if (ts.length() == 0) return null
        // ersten Eintrag nehmen, der nicht in der Vergangenheit liegt
        val now = System.currentTimeMillis()
        var pick = ts.getJSONObject(0)
        for (i in 0 until minOf(3, ts.length())) {
            val o = ts.getJSONObject(i)
            val tms = try { java.time.Instant.parse(o.getString("time")).toEpochMilli() } catch (e: Exception) { 0L }
            if (tms >= now - 30 * 60_000L) { pick = o; break }
        }
        val data = pick.getJSONObject("data")
        val d = data.getJSONObject("instant").getJSONObject("details")
        val n1 = data.optJSONObject("next_1_hours")
        val symbol = n1?.optJSONObject("summary")?.optString("symbol_code").orEmpty()
        val precip = n1?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0
        val cloud = (d.optDouble("cloud_area_fraction", 20.0) / 100.0).toFloat()
        val fog = (d.optDouble("fog_area_fraction", 0.0) / 100.0).toFloat()
        val wind = (d.optDouble("wind_speed", 3.0) / 15.0).toFloat().coerceIn(0f, 1f)
        val temp = d.optDouble("air_temperature", Double.NaN)
        val thunder = symbol.contains("thunder")
        val snow = symbol.contains("snow") || symbol.contains("sleet")
        var rain = (precip / 4.0).toFloat().coerceIn(0f, 1f)
        if (rain < 0.05f && (symbol.contains("rain") || symbol.contains("sleet") || symbol.contains("snow"))) rain = 0.15f
        return Wx(
            cloud.coerceIn(0f, 1f), rain, snow, thunder,
            if (symbol.contains("fog")) maxOf(fog, 0.75f) else fog.coerceIn(0f, 1f),
            wind, if (temp.isNaN()) null else Math.round(temp).toInt(), now
        )
    }
}
