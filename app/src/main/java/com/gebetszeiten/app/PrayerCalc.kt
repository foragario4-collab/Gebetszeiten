package com.gebetszeiten.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Astronomische Berechnung der Gebetszeiten (Verfahren nach PrayTimes.org).
 * Alle Zeiten in Stunden (Ortszeit) des jeweiligen Tages.
 */
object PrayerCalc {

    data class Method(
        val id: String,
        val name: String,
        val fajr: Double,
        val isha: Double,
        val ishaMinutes: Int = 0
    ) {
        val label: String get() = t("m_$id")
    }

    val methods = listOf(
        Method("mwl", "Muslim World League", 18.0, 17.0),
        Method("diyanet", "Diyanet (Türkei)", 18.0, 17.0),
        Method("isna", "ISNA (Nordamerika)", 15.0, 15.0),
        Method("egypt", "Ägypten", 19.5, 17.5),
        Method("makkah", "Umm al-Qura (Mekka)", 18.5, 0.0, 90),
        Method("karachi", "Karachi", 18.0, 18.0),
        Method("gulf", "Golfregion", 19.5, 0.0, 90),
        Method("france", "Frankreich (12°)", 12.0, 12.0),
        Method("custom", "Eigene Winkel", 18.0, 17.0)
    )

    fun method(id: String): Method = methods.firstOrNull { it.id == id } ?: methods[0]

    class Raw(
        val fajr: Double,
        val sunrise: Double,
        val dhuhr: Double,
        val asr: Double,
        val maghrib: Double,
        val isha: Double
    )

    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x))
    private fun darccos(x: Double) = Math.toDegrees(acos(x))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1.0 / x))
    private fun fixAngle(a: Double) = a - 360.0 * floor(a / 360.0)
    private fun fixHour(a: Double) = a - 24.0 * floor(a / 24.0)

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** [Deklination, Zeitgleichung] */
    private fun sun(jd: Double): DoubleArray {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = fixHour(darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0)
        val eqt = q / 15.0 - ra
        val decl = darcsin(dsin(e) * dsin(l))
        return doubleArrayOf(decl, eqt)
    }

    fun compute(
        date: LocalDate,
        lat: Double,
        lng: Double,
        tz: Double,
        fajrAngle: Double,
        ishaAngle: Double,
        ishaMinutes: Int,
        asrFactor: Int,
        highLat: String
    ): Raw {
        val jDate = julian(date.year, date.monthValue, date.dayOfMonth) - lng / (15.0 * 24.0)

        fun midDay(t: Double): Double = fixHour(12.0 - sun(jDate + t)[1])

        fun angleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sun(jDate + t)[0]
            val noon = midDay(t)
            val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            if (x < -1.0 || x > 1.0) return Double.NaN
            val tt = darccos(x) / 15.0
            return if (ccw) noon - tt else noon + tt
        }

        fun asrTime(factor: Int, t: Double): Double {
            val decl = sun(jDate + t)[0]
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return angleTime(angle, t, false)
        }

        val rs = 0.833
        val adj = tz - lng / 15.0
        var fajr = angleTime(fajrAngle, 5.0 / 24.0, true) + adj
        val sunrise = angleTime(rs, 6.0 / 24.0, true) + adj
        val dhuhr = midDay(12.0 / 24.0) + adj
        val asr = asrTime(asrFactor, 13.0 / 24.0) + adj
        val sunset = angleTime(rs, 18.0 / 24.0, false) + adj
        var isha = angleTime(ishaAngle, 18.0 / 24.0, false) + adj

        // Anpassung für hohe Breitengrade
        val night = fixHour(sunrise - sunset)
        fun portion(angle: Double): Double = when (highLat) {
            "middle" -> 0.5
            "seventh" -> 1.0 / 7.0
            else -> angle / 60.0
        }
        if (!night.isNaN()) {
            val fp = portion(fajrAngle) * night
            if (fajr.isNaN() || fixHour(sunrise - fajr) > fp) fajr = sunrise - fp
            if (ishaMinutes > 0) {
                isha = sunset + ishaMinutes / 60.0
            } else {
                val ip = portion(ishaAngle) * night
                if (isha.isNaN() || fixHour(isha - sunset) > ip) isha = sunset + ip
            }
        }
        return Raw(fajr, sunrise, dhuhr, asr, sunset, isha)
    }
}
