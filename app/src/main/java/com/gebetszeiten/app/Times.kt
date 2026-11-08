package com.gebetszeiten.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

enum class Kind(val label: String, val short: String, val prayer: Boolean) {
    FAJR("Fajr", "Fajr", true),
    SUNRISE("Sonnenaufgang", "Shuruq", false),
    DHUHR("Dhuhr", "Dhuhr", true),
    ASR("Asr", "Asr", true),
    MAGHRIB("Maghrib", "Maghrib", true),
    ISHA("Isha", "Isha", true),
    MIDNIGHT("Mitternacht", "Mitte", false),
    LAST_THIRD("Letztes Drittel", "Drittel", false)
}

/** Ein Zeitpunkt. [day] ist bei Mitternacht/letztem Drittel der Tag, an dem die Nacht beginnt. */
data class Ev(val kind: Kind, val at: Long, val day: LocalDate) {
    private val friday get() = kind == Kind.DHUHR && day.dayOfWeek == DayOfWeek.FRIDAY
    val title: String get() = if (friday) "Jumuah" else kind.label
    val shortTitle: String get() = if (friday) "Jumuah" else kind.short
}

object Times {
    private val base = listOf(Kind.FAJR, Kind.SUNRISE, Kind.DHUHR, Kind.ASR, Kind.MAGHRIB, Kind.ISHA)

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun today(now: Long = System.currentTimeMillis()): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()

    fun day(date: LocalDate): Map<Kind, Long> {
        val z = zone()
        val tz = z.rules.getOffset(date.atTime(12, 0)).totalSeconds / 3600.0
        val m = PrayerCalc.method(Prefs.method)
        val fa = if (m.id == "custom") Prefs.customFajr.toDouble() else m.fajr
        val ia = if (m.id == "custom") Prefs.customIsha.toDouble() else m.isha
        val r = PrayerCalc.compute(
            date, Prefs.lat, Prefs.lng, tz, fa, ia, m.ishaMinutes, Prefs.asr, Prefs.highLat
        )
        val hours = mapOf(
            Kind.FAJR to r.fajr,
            Kind.SUNRISE to r.sunrise,
            Kind.DHUHR to r.dhuhr,
            Kind.ASR to r.asr,
            Kind.MAGHRIB to r.maghrib,
            Kind.ISHA to r.isha
        )
        return hours.mapValues { (k, h) ->
            val safe = if (h.isNaN()) 0.0 else h
            val minutes = floor(safe * 60.0 + 0.5).toLong() + Prefs.offset(k)
            date.atStartOfDay().plusMinutes(minutes).atZone(z).toInstant().toEpochMilli()
        }
    }

    fun events(from: LocalDate, days: Int): List<Ev> {
        val maps = (0..days).map { day(from.plusDays(it.toLong())) }
        val out = ArrayList<Ev>()
        for (i in 0 until days) {
            val d = from.plusDays(i.toLong())
            val t = maps[i]
            val n = maps[i + 1]
            base.forEach { out.add(Ev(it, t.getValue(it), d)) }
            val maghrib = t.getValue(Kind.MAGHRIB)
            val fajrNext = n.getValue(Kind.FAJR)
            val end = if (Prefs.midnightMode == "sunrise") n.getValue(Kind.SUNRISE) else fajrNext
            out.add(Ev(Kind.MIDNIGHT, roundMin(maghrib + (end - maghrib) / 2), d))
            out.add(Ev(Kind.LAST_THIRD, roundMin(maghrib + (fajrNext - maghrib) * 2 / 3), d))
        }
        return out.sortedBy { it.at }
    }

    private fun roundMin(x: Long) = (x + 30_000L) / 60_000L * 60_000L

    /** Gestern bis übermorgen. */
    fun around(now: Long): List<Ev> = events(today(now).minusDays(1), 4)

    /** Liste für das Widget, je nach Einstellung mit Sonnenaufgang/Mitternacht/Drittel. */
    fun forWidget(now: Long): List<Ev> = around(now).filter {
        when (it.kind) {
            Kind.SUNRISE -> Prefs.wSunrise
            Kind.MIDNIGHT -> Prefs.wMidnight
            Kind.LAST_THIRD -> Prefs.wThird
            else -> true
        }
    }
}

object Fmt {
    fun time(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(Times.zone())
        return if (Prefs.use24) z.format(DateTimeFormatter.ofPattern("HH:mm"))
        else z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }

    fun duration(ms: Long): String {
        val total = ceil(ms / 60_000.0).toLong().coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h Std $m Min" else "$m Min"
    }

    fun countdown(ms: Long): String {
        if (ms >= 3_600_000L) return duration(ms)
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun dateLong(d: LocalDate): String =
        d.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN))
}

object Hijri {
    private val months = listOf(
        "Muharram", "Safar", "Rabi al-Awwal", "Rabi ath-Thani",
        "Dschumada l-ula", "Dschumada th-thaniya", "Radschab", "Schaban",
        "Ramadan", "Schawwal", "Dhu l-Qada", "Dhu l-Hiddscha"
    )

    fun text(d: LocalDate): String = try {
        val h = HijrahDate.from(d.plusDays(Prefs.hijriAdj.toLong()))
        "${h.get(ChronoField.DAY_OF_MONTH)}. ${months[h.get(ChronoField.MONTH_OF_YEAR) - 1]} ${h.get(ChronoField.YEAR_OF_ERA)}"
    } catch (e: Exception) {
        ""
    }

    fun short(d: LocalDate): String = try {
        val h = HijrahDate.from(d.plusDays(Prefs.hijriAdj.toLong()))
        "${h.get(ChronoField.DAY_OF_MONTH)}. ${months[h.get(ChronoField.MONTH_OF_YEAR) - 1]}"
    } catch (e: Exception) {
        ""
    }
}
