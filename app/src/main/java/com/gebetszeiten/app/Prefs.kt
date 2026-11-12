package com.gebetszeiten.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf

object Prefs {
    private lateinit var store: SharedPreferences

    /** Wird bei jeder Änderung hochgezählt, damit die Oberfläche neu zeichnet. */
    val version = mutableIntStateOf(0)

    /**
     * Jeder Lesezugriff liest auch [version] mit. Dadurch zeichnet sich jede Stelle,
     * die eine Einstellung anzeigt, sofort neu, sobald sich etwas ändert.
     */
    private val sp: SharedPreferences
        get() {
            version.intValue
            return store
        }

    fun init(ctx: Context) {
        if (!::store.isInitialized) {
            store = ctx.applicationContext.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)
        }
        I18n.init(ctx)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val e = store.edit()
        e.block()
        e.apply()
        version.intValue = version.intValue + 1
    }

    var lang: Lang
        get() = Lang.of(sp.getString("lang", Lang.device().code)!!)
        set(v) = edit { putString("lang", v.code) }
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = edit { putBoolean("setupDone", v) }

    var lat: Double
        get() = sp.getString("lat", "48.2082")!!.toDouble()
        set(v) = edit { putString("lat", v.toString()) }
    var lng: Double
        get() = sp.getString("lng", "16.3738")!!.toDouble()
        set(v) = edit { putString("lng", v.toString()) }
    var place: String
        get() = sp.getString("place", "Wien")!!
        set(v) = edit { putString("place", v) }
    var autoLoc: Boolean
        get() = sp.getBoolean("autoLoc", false)
        set(v) = edit { putBoolean("autoLoc", v) }

    var method: String
        get() = sp.getString("method", "isna")!!
        set(v) = edit { putString("method", v) }
    var customFajr: Float
        get() = sp.getFloat("customFajr", 18f)
        set(v) = edit { putFloat("customFajr", v) }
    var customIsha: Float
        get() = sp.getFloat("customIsha", 17f)
        set(v) = edit { putFloat("customIsha", v) }
    /** 1 = Standard (Schafii, Maliki, Hanbali), 2 = Hanafi */
    var asr: Int
        get() = sp.getInt("asr", 1)
        set(v) = edit { putInt("asr", v) }
    /** angle, middle, seventh */
    var highLat: String
        get() = sp.getString("highLat", "angle")!!
        set(v) = edit { putString("highLat", v) }
    /** fajr = Maghrib bis Fajr, sunrise = Maghrib bis Sonnenaufgang */
    var midnightMode: String
        get() = sp.getString("midnightMode", "fajr")!!
        set(v) = edit { putString("midnightMode", v) }

    fun offset(k: Kind): Int = sp.getInt("off_${k.name}", 0)
    fun setOffset(k: Kind, v: Int) = edit { putInt("off_${k.name}", v) }

    /** 0 = aus, 1 = Mitteilung mit Ton, 2 = Adhan */
    fun mode(k: Kind): Int =
        if (k == Kind.MIDNIGHT) 0 else sp.getInt("mode_${k.name}", if (k.prayer) 1 else 0)
    fun setMode(k: Kind, v: Int) = edit { putInt("mode_${k.name}", v) }

    var reminder: Int
        get() = sp.getInt("reminder", 15)
        set(v) = edit { putInt("reminder", v) }
    var live: Boolean
        get() = sp.getBoolean("live", true)
        set(v) = edit { putBoolean("live", v) }
    var liveMin: Int
        get() = sp.getInt("liveMin", 30)
        set(v) = edit { putInt("liveMin", v) }

    var adhanUri: String?
        get() = sp.getString("adhanUri", null)
        set(v) = edit { putString("adhanUri", v) }
    var adhanName: String?
        get() = sp.getString("adhanName", null)
        set(v) = edit { putString("adhanName", v) }
    var fajrAdhanUri: String?
        get() = sp.getString("fajrAdhanUri", null)
        set(v) = edit { putString("fajrAdhanUri", v) }
    var fajrAdhanName: String?
        get() = sp.getString("fajrAdhanName", null)
        set(v) = edit { putString("fajrAdhanName", v) }

    /** 0 = automatisch, 1 = hell, 2 = dunkel */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = edit { putInt("theme", v) }
    var hijriAdj: Int
        get() = sp.getInt("hijriAdj", 0)
        set(v) = edit { putInt("hijriAdj", v) }
    var use24: Boolean
        get() = sp.getBoolean("use24", true)
        set(v) = edit { putBoolean("use24", v) }

    var wSunrise: Boolean
        get() = sp.getBoolean("wSunrise", true)
        set(v) = edit { putBoolean("wSunrise", v) }
    var wMidnight: Boolean
        get() = sp.getBoolean("wMidnight", true)
        set(v) = edit { putBoolean("wMidnight", v) }
    var wThird: Boolean
        get() = sp.getBoolean("wThird", true)
        set(v) = edit { putBoolean("wThird", v) }

    /** 0 = Hijri, 1 = Gregorianisch, 2 = beides */
    var dateMode: Int
        get() = sp.getInt("dateMode", 2)
        set(v) = edit { putInt("dateMode", v) }

    var radar: Boolean
        get() = sp.getBoolean("radar", false)
        set(v) = edit { putBoolean("radar", v) }
    var radarRadius: Int
        get() = sp.getInt("radarRadius", 1500)
        set(v) = edit { putInt("radarRadius", v) }
    var radarAlertKey: String
        get() = sp.getString("radarAlertKey", "")!!
        set(v) {
            store.edit().putString("radarAlertKey", v).apply()
        }

    /** 0 = Auto, 1 = zu Fuß */
    var travelMode: Int
        get() = sp.getInt("travelMode", 0)
        set(v) = edit { putInt("travelMode", v) }
    /** 0 = nach Nähe, 1 = nach Iqamah */
    var sortMode: Int
        get() = sp.getInt("sortMode", 0)
        set(v) = edit { putInt("sortMode", v) }

    /** 0 = runder Kompass, 1 = eckige Leiste mit Karte */
    var compassStyle: Int
        get() = sp.getInt("compassStyle", 0)
        set(v) = edit { putInt("compassStyle", v) }

    /** Live-Anzeige oben dauerhaft (bis zum nächsten Gebet), unabhängig vom Radar */
    var liveAlways: Boolean
        get() = sp.getBoolean("liveAlways", true)
        set(v) = edit { putBoolean("liveAlways", v) }
    /** 0 = Restzeit (1:12), 1 = Uhrzeit (14:01) */
    var chipMode: Int
        get() = sp.getInt("chipMode", 0)
        set(v) = edit { putInt("chipMode", v) }

    /** Ausgeblendete Moscheen/Orte (IDs) */
    var hiddenIds: Set<String>
        get() = sp.getStringSet("hiddenIds", emptySet())?.toSet() ?: emptySet()
        set(v) = edit { putStringSet("hiddenIds", HashSet(v)) }

    fun hide(id: String) {
        hiddenIds = hiddenIds + id
    }

    var showMosques: Boolean
        get() = sp.getBoolean("showMosques", true)
        set(v) = edit { putBoolean("showMosques", v) }
    var showPrayer: Boolean
        get() = sp.getBoolean("showPrayer", true)
        set(v) = edit { putBoolean("showPrayer", v) }
    var showWudu: Boolean
        get() = sp.getBoolean("showWudu", true)
        set(v) = edit { putBoolean("showWudu", v) }

    /** Gewählter Ton für Gebets-Mitteilungen */
    var tone: String
        get() = sp.getString("tone", "ton_gong") ?: "ton_gong"
        set(v) = edit { putString("tone", v) }

    /** Vereine aus der Islam-Landkarte zeigen (standardmäßig aus – enthält auch Orte ohne Gebetsmöglichkeit) */
    var useLandkarte: Boolean
        get() = sp.getBoolean("useLandkarte", false)
        set(v) = edit { putBoolean("useLandkarte", v) }

    var radarExplained: Boolean
        get() = sp.getBoolean("radarExplained", false)
        set(v) = edit { putBoolean("radarExplained", v) }

    /** „Meine Moschee“ (ID) für die Startseite */
    var favId: String
        get() = sp.getString("favId", "") ?: ""
        set(v) = edit { putString("favId", v) }

    /** Letzte echte GPS-Position (auch wenn „Automatisch“ aus ist) */
    var lastGpsLat: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("lastGpsLat", java.lang.Double.doubleToRawLongBits(0.0)))
        set(v) = edit { putLong("lastGpsLat", java.lang.Double.doubleToRawLongBits(v)) }
    var lastGpsLng: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("lastGpsLng", java.lang.Double.doubleToRawLongBits(0.0)))
        set(v) = edit { putLong("lastGpsLng", java.lang.Double.doubleToRawLongBits(v)) }
    var lastGpsAt: Long
        get() = sp.getLong("lastGpsAt", 0L)
        set(v) = edit { putLong("lastGpsAt", v) }

    var lastHandled: Long
        get() = sp.getLong("lastHandled", 0L)
        set(v) {
            store.edit().putLong("lastHandled", v).apply()
        }
}
