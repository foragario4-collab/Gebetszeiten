package com.gebetszeiten.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

enum class Kind(val prayer: Boolean) {
    FAJR(true),
    SUNRISE(false),
    DHUHR(true),
    ASR(true),
    MAGHRIB(true),
    ISHA(true),
    MIDNIGHT(false),
    LAST_THIRD(false);

    val label: String get() = t("k_$name")
    val short: String get() = t("s_$name")
}

/** Ein Zeitpunkt. [day] ist bei Mitternacht/letztem Drittel der Tag, an dem die Nacht beginnt. */
data class Ev(val kind: Kind, val at: Long, val day: LocalDate) {
    val isJumuah: Boolean get() = kind == Kind.DHUHR && day.dayOfWeek == DayOfWeek.FRIDAY
    val title: String get() = if (isJumuah) t("k_JUMUAH") else kind.label
    val shortTitle: String get() = if (isJumuah) t("s_JUMUAH") else kind.short
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
        return when {
            h > 0 && m == 0L -> t("dur_h", h)
            h > 0 -> t("dur_hm", h, m)
            else -> t("dur_m", m)
        }
    }

    fun countdown(ms: Long): String {
        if (ms >= 3_600_000L) return duration(ms)
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

object Hijri {
    private val de = listOf("Muharram", "Safar", "Rabi al-Awwal", "Rabi ath-Thani", "Dschumada l-ula", "Dschumada th-thaniya", "Radschab", "Schaban", "Ramadan", "Schawwal", "Dhu l-Qada", "Dhu l-Hiddscha")
    private val en = listOf("Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani", "Jumada al-Ula", "Jumada al-Akhirah", "Rajab", "Sha’ban", "Ramadan", "Shawwal", "Dhu al-Qa’dah", "Dhu al-Hijjah")
    private val tr = listOf("Muharrem", "Safer", "Rebiülevvel", "Rebiülahir", "Cemaziyelevvel", "Cemaziyelahir", "Recep", "Şaban", "Ramazan", "Şevval", "Zilkade", "Zilhicce")
    private val bs = listOf("Muharrem", "Safer", "Rebiu-l-evvel", "Rebiu-l-ahir", "Džumade-l-ula", "Džumade-l-uhra", "Redžeb", "Ša’ban", "Ramazan", "Ševval", "Zu-l-ka’de", "Zu-l-hidždže")
    private val sq = listOf("Muharrem", "Safer", "Rebiul Evvel", "Rebiul Ahir", "Xhumadel Ula", "Xhumadel Ahire", "Rexheb", "Shaban", "Ramazan", "Sheval", "Dhul Kade", "Dhul Hixhe")
    private val ru = listOf("Мухаррам", "Сафар", "Раби аль-авваль", "Раби ас-сани", "Джумада аль-уля", "Джумада ас-сания", "Раджаб", "Шаабан", "Рамадан", "Шавваль", "Зуль-када", "Зуль-хиджа")
    private val zh = listOf("穆哈兰姆月", "色法尔月", "赖比尔·敖外鲁月", "赖比尔·阿赫尔月", "主马达·敖外鲁月", "主马达·阿赫尔月", "赖哲卜月", "舍尔邦月", "赖买丹月", "闪瓦鲁月", "都尔喀尔德月", "都尔黑哲月")
    private val ar = listOf("محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة")

    private fun months(): List<String> = when (Prefs.lang) {
        Lang.DE -> de
        Lang.EN, Lang.FR -> en
        Lang.TR -> tr
        Lang.BS, Lang.SR -> bs
        Lang.SQ -> sq
        Lang.RU, Lang.CE -> ru
        Lang.ZH -> zh
        Lang.AR -> ar
    }

    /** Der islamische Tag beginnt mit Maghrib. */
    fun dateFor(now: Long): LocalDate {
        val today = Times.today(now)
        val maghrib = try {
            Times.day(today).getValue(Kind.MAGHRIB)
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
        return if (now >= maghrib) today.plusDays(1) else today
    }

    private fun parts(now: Long): Triple<Int, Int, Int>? = try {
        val h = HijrahDate.from(dateFor(now).plusDays(Prefs.hijriAdj.toLong()))
        Triple(h.get(ChronoField.DAY_OF_MONTH), h.get(ChronoField.MONTH_OF_YEAR), h.get(ChronoField.YEAR_OF_ERA))
    } catch (e: Exception) {
        null
    }

    fun text(now: Long): String = parts(now)?.let { (d, m, y) ->
        if (Prefs.lang == Lang.ZH) "伊斯兰历 $y 年 ${months()[m - 1]} $d 日"
        else "$d. ${months()[m - 1]} $y"
    } ?: ""

    fun short(now: Long): String = parts(now)?.let { (d, m, _) ->
        if (Prefs.lang == Lang.ZH) "${months()[m - 1]} $d 日" else "$d. ${months()[m - 1]}"
    } ?: ""
}

/** Datumsanzeige je nach Einstellung: 0 = Hijri, 1 = Gregorianisch, 2 = beides. */
object DateText {
    private fun loc() = Prefs.lang.locale
    private fun d(now: Long) = Times.today(now)

    private fun greg(now: Long): String =
        d(now).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(loc()))

    private fun gregNum(now: Long): String =
        d(now).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(loc()))

    fun widget(now: Long): String = when (Prefs.dateMode) {
        1 -> greg(now)
        2 -> greg(now) + "\n" + Hijri.text(now)
        else -> Hijri.text(now)
    }

    fun compact(now: Long): String = when (Prefs.dateMode) {
        1 -> greg(now)
        2 -> gregNum(now) + " · " + Hijri.short(now)
        else -> Hijri.text(now)
    }

    /** Kopfzeile in der App */
    fun headerTop(now: Long): String =
        d(now).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(loc()))

    fun headerSub(now: Long): String? = if (Prefs.dateMode == 1) null else Hijri.text(now)
}
