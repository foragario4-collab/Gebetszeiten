package com.gebetszeiten.app

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Qibla {
    private const val KAABA_LAT = 21.4225
    private const val KAABA_LNG = 39.8262

    fun bearing(lat: Double, lng: Double): Double {
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(KAABA_LAT)
        val dl = Math.toRadians(KAABA_LNG - lng)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun distanceKm(lat: Double, lng: Double): Double {
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(KAABA_LAT)
        val dp = p2 - p1
        val dl = Math.toRadians(KAABA_LNG - lng)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 6371.0 * 2 * asin(sqrt(a))
    }

    /** Himmelsrichtungen N/O/S/W in der gewählten Sprache. */
    fun letters(): List<String> = when (Prefs.lang) {
        Lang.DE -> listOf("N", "O", "S", "W")
        Lang.FR -> listOf("N", "E", "S", "O")
        Lang.TR -> listOf("K", "D", "G", "B")
        Lang.BS, Lang.SR -> listOf("S", "I", "J", "Z")
        Lang.SQ -> listOf("V", "L", "J", "P")
        Lang.RU, Lang.CE -> listOf("С", "В", "Ю", "З")
        Lang.ZH -> listOf("北", "东", "南", "西")
        Lang.AR -> listOf("ش", "ق", "ج", "غ")
        else -> listOf("N", "E", "S", "W")
    }

    fun direction(b: Double): String {
        val names = t("dirs").split('|')
        return names[(((b + 22.5) % 360) / 45).toInt()]
    }
}
