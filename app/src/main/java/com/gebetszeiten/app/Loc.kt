package com.gebetszeiten.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Standort ohne Google-Dienste – direkt über GPS / Netzwerk des Systems. */
object Loc {

    data class City(val name: String, val lat: Double, val lng: Double)

    val cities = listOf(
        City("Wien", 48.2082, 16.3738),
        City("Graz", 47.0707, 15.4395),
        City("Linz", 48.3069, 14.2858),
        City("Salzburg", 47.8095, 13.0550),
        City("Innsbruck", 47.2692, 11.4041),
        City("Klagenfurt", 46.6247, 14.3053),
        City("St. Pölten", 48.2047, 15.6256),
        City("Bregenz", 47.5031, 9.7471),
        City("Berlin", 52.5200, 13.4050),
        City("München", 48.1351, 11.5820),
        City("Zürich", 47.3769, 8.5417),
        City("Istanbul", 41.0082, 28.9784),
        City("Sarajevo", 43.8563, 18.4131),
        City("Mekka", 21.4225, 39.8262)
    )

    @SuppressLint("MissingPermission")
    fun fetch(ctx: Context, cb: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null) {
            cb(null); return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000L) {
            cb(last); return
        }
        if (providers.isEmpty()) {
            cb(last); return
        }
        var answered = false
        val main = Handler(Looper.getMainLooper())
        val answer: (Location?) -> Unit = { loc ->
            if (!answered) {
                answered = true
                cb(loc ?: last)
            }
        }
        main.postDelayed({ answer(null) }, 25_000L)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(providers.first(), null, ctx.mainExecutor) { answer(it) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(providers.first(), { answer(it) }, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            answer(null)
        }
    }

    /** Ortsname, falls das System einen Geocoder hat – sonst null. */
    fun placeName(ctx: Context, lat: Double, lng: Double, cb: (String?) -> Unit) {
        Thread {
            val name = try {
                if (Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val list = Geocoder(ctx, Locale.GERMAN).getFromLocation(lat, lng, 1)
                    list?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                } else null
            } catch (e: Exception) {
                null
            } ?: placeNameOsm(lat, lng)
            Handler(Looper.getMainLooper()).post { cb(name) }
        }.start()
    }

    /** Ist der Standort (GPS/Netzwerk) am Handy eingeschaltet? */
    fun isOn(ctx: android.content.Context): Boolean {
        val m = ctx.getSystemService(android.location.LocationManager::class.java) ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 28) m.isLocationEnabled
        else m.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            m.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    /** Android-Einstellung „Standort“ öffnen. */
    fun openSettings(ctx: android.content.Context) {
        try {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    /** Ortsname über OpenStreetMap, falls Android selbst keinen liefert (z. B. GrapheneOS ohne Google). */
    private fun placeNameOsm(lat: Double, lng: Double): String? = try {
        val conn = java.net.URL(
            "https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&lat=" +
                "%.5f".format(Locale.US, lat) + "&lon=" + "%.5f".format(Locale.US, lng)
        ).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Gebetszeiten-App (Android)")
        conn.setRequestProperty("Accept-Language", Prefs.lang.code + ",en;q=0.5")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val a = org.json.JSONObject(body).optJSONObject("address")
        a?.let { listOf("city", "town", "village", "municipality", "county").map { k -> it.optString(k) }.firstOrNull { n -> n.isNotBlank() } }
    } catch (e: Exception) {
        null
    }

    /**
     * Beim Öffnen der App: Standort holen. Merkt sich immer die letzte GPS-Position.
     * Ist „Automatisch (GPS)“ an und hat man sich mehr als 1 km bewegt, werden Gebetszeiten,
     * Qibla, Live-Anzeige und Widget auf den neuen Ort umgestellt.
     */
    fun autoUpdate(ctx: Context, done: () -> Unit = {}) {
        if (!isOn(ctx)) return
        fetch(ctx) { loc ->
            if (loc == null) return@fetch
            applyFix(ctx, loc.latitude, loc.longitude)
            done()
        }
    }

    /** Eine frische GPS-Position verarbeiten (auch von der Karte aus aufgerufen). */
    fun applyFix(ctx: Context, lat: Double, lng: Double) {
        Prefs.lastGpsLat = lat
        Prefs.lastGpsLng = lng
        Prefs.lastGpsAt = System.currentTimeMillis()
        if (!Prefs.autoLoc) return
        val moved = Mosques.distance(Prefs.lat, Prefs.lng, lat, lng)
        if (moved < 1000f) return
        Prefs.lat = lat
        Prefs.lng = lng
        if (moved > 10_000f) Prefs.place = t("my_location")
        Scheduler.reschedule(ctx)
        Widgets.updateAll(ctx)
        val app = ctx.applicationContext
        Thread {
            val name = try {
                if (Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    Geocoder(app, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()
                        ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                } else null
            } catch (e: Exception) {
                null
            } ?: placeNameOsm(lat, lng)
            if (name != null) Handler(Looper.getMainLooper()).post {
                Prefs.place = name
                Widgets.updateAll(app)
            }
        }.start()
    }
}
