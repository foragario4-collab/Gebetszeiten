package com.gebetszeiten.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

fun hasLocPermission(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun TodayScreen(v: Int, onSettings: () -> Unit = {}) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    // Automatischer Standort: beim Öffnen prüfen, ob man sich bewegt hat
    LaunchedEffect(Unit) {
        if (Prefs.autoLoc && hasLocPermission(ctx)) {
            Loc.fetch(ctx) { loc ->
                if (loc != null) {
                    val d = FloatArray(1)
                    Location.distanceBetween(Prefs.lat, Prefs.lng, loc.latitude, loc.longitude, d)
                    if (d[0] > 3000f) {
                        Prefs.lat = loc.latitude
                        Prefs.lng = loc.longitude
                        Prefs.place = t("my_location")
                        Scheduler.reschedule(ctx)
                        Loc.placeName(ctx, loc.latitude, loc.longitude) { n ->
                            if (n != null) {
                                Prefs.place = n
                                Widgets.updateAll(ctx)
                            }
                        }
                    }
                }
            }
        }
    }

    val minute = now / 60_000L
    val evs = remember(minute, v) { Times.around(now) }
    val today = Times.today(now)
    val ni = evs.indexOfFirst { it.at > now }.coerceAtLeast(1)
    val next = evs[ni]
    val prev = evs[ni - 1]
    val frac = ((now - prev.at).toFloat() / (next.at - prev.at).coerceAtLeast(1)).coerceIn(0f, 1f)
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val isToday = offset == 0
    val day = today.plusDays(offset.toLong())
    val dayMs = day.atTime(12, 0).atZone(Times.zone()).toInstant().toEpochMilli()
    val list = if (isToday) evs.filter { it.day == today } else remember(day, v) { Times.events(day, 1) }
    val title = when (offset) {
        0 -> t("today_title")
        1 -> t("tomorrow")
        -1 -> t("yesterday")
        else -> t("prayer_times")
    }

    ScreenColumn {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, 34, FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(p.card)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Txt(Prefs.place, 14, FontWeight.SemiBold, p.accentText, maxLines = 1, modifier = Modifier.widthIn(max = 130.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(p.card)
                    .tap(scale = 0.9f, onClick = onSettings),
                contentAlignment = Alignment.Center
            ) { SlidersIcon(p.accentText) }
        }
        Spacer(Modifier.height(12.dp))

        // Tag wechseln
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DayArrow(left = true) { offset-- }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(DateText.headerTop(dayMs), 15, FontWeight.SemiBold, maxLines = 1, align = TextAlign.Center)
                Txt(Hijri.text(dayMs), 12, color = p.sub, maxLines = 1, align = TextAlign.Center)
            }
            DayArrow(left = false) { offset++ }
        }
        androidx.compose.animation.AnimatedVisibility(visible = !isToday) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(17.dp))
                        .background(p.accent)
                        .tap { offset = 0 }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { Txt("↺ " + t("tab_today"), 14, FontWeight.SemiBold, Color.White) }
            }
        }
        Spacer(Modifier.height(14.dp))

        // Nächstes Gebet (nur heute)
        if (isToday) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(p.accent)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Txt(t("next_up"), 13, FontWeight.SemiBold, Color.White.copy(alpha = 0.85f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Txt(next.title, 36, FontWeight.Bold, Color.White, Modifier.weight(1f), maxLines = 1)
                Txt(Fmt.time(next.at), 36, FontWeight.Light, Color.White)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Txt("${prev.title} ${Fmt.time(prev.at)}", 14, FontWeight.Medium, Color.White, Modifier.weight(1f))
                Txt(t("still", Fmt.countdown(next.at - now)), 14, FontWeight.Medium, Color.White)
            }
        }
        Spacer(Modifier.height(18.dp))
        }

        // Liste
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card)) {
            list.forEachIndexed { i, e ->
                PrayerRow(ctx, e, first = i == 0, isNext = isToday && e == next, past = isToday && e.at <= now, today = day)
            }
        }
        Spacer(Modifier.height(18.dp))

        // Nacht-Info
        val maghrib = list.firstOrNull { it.kind == Kind.MAGHRIB }
        val fajrNext = maghrib?.let { m -> (if (isToday) evs else Times.events(day, 2)).firstOrNull { it.kind == Kind.FAJR && it.at > m.at } }
        if (maghrib != null && fajrNext != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.card)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Txt(t("the_night"), 16, FontWeight.SemiBold)
                Txt(
                    t("night_line", Fmt.time(maghrib.at), Fmt.time(fajrNext.at), Fmt.duration(fajrNext.at - maghrib.at)),
                    13, color = p.sub
                )
                val mm = if (Prefs.midnightMode == "sunrise") t("mm_sunrise") else t("mm_fajr")
                Txt(t("midnight_def", mm), 13, color = p.sub)
                Txt(t("third_def"), 13, color = p.sub)
            }
            Spacer(Modifier.height(12.dp))
        }
        val m = PrayerCalc.method(Prefs.method)
        Txt(
            "${m.label} · ${Kind.ASR.label} ${if (Prefs.asr == 2) t("asr_hanafi") else t("asr_std")}",
            12, color = p.sub, modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
private fun PrayerRow(ctx: Context, e: Ev, first: Boolean, isNext: Boolean, past: Boolean, today: java.time.LocalDate) {
    val p = LocalPal.current
    val weight = if (isNext) FontWeight.Bold else FontWeight.Medium
    val tag = when (e.kind) {
        Kind.SUNRISE -> t("end_fajr")
        Kind.MIDNIGHT -> t("end_isha")
        Kind.LAST_THIRD -> if (Times.today(e.at).isAfter(today)) t("tahajjud_tom") else t("tahajjud")
        else -> null
    }
    Column(Modifier.fillMaxWidth().background(if (isNext) p.hl else Color.Transparent)) {
        if (!first) Box(Modifier.padding(start = 36.dp).fillMaxWidth().height(0.5.dp).background(p.line))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isNext) p.accentText else if (past) p.line else p.sub)
            )
            Column(Modifier.weight(1f)) {
                Txt(e.title, 17, weight)
                if (tag != null) Txt(tag, 12, color = p.sub)
            }
            if (e.kind != Kind.MIDNIGHT) ModeChip(ctx, e.kind)
            Txt(Fmt.time(e.at), 17, weight)
        }
    }
}

@Composable
private fun ModeChip(ctx: Context, k: Kind) {
    val p = LocalPal.current
    val mode = Prefs.mode(k)
    val labels = listOf(t("mode_off"), t("mode_tone"), t("mode_adhan"))
    val max = if (k.prayer) 2 else 1
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (mode > 0) p.hl else p.fill)
            .tap {
                Prefs.setMode(k, (mode + 1) % (max + 1))
                Scheduler.reschedule(ctx)
            }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Txt(labels[mode.coerceIn(0, 2)], 12, FontWeight.SemiBold, if (mode > 0) p.accentText else p.sub)
    }
}


@Composable
private fun DayArrow(left: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    val rtl = Prefs.lang.rtl
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.bg).tap(scale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val c = p.accentText
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val pointLeft = left != rtl
            val path = androidx.compose.ui.graphics.Path().apply {
                if (pointLeft) {
                    moveTo(w * 0.62f, w * 0.2f); lineTo(w * 0.32f, w * 0.5f); lineTo(w * 0.62f, w * 0.8f)
                } else {
                    moveTo(w * 0.38f, w * 0.2f); lineTo(w * 0.68f, w * 0.5f); lineTo(w * 0.38f, w * 0.8f)
                }
            }
            drawPath(
                path, c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w * 0.14f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
        }
    }
}

@Composable
private fun SlidersIcon(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val s = size.width
        val sw = s * 0.085f
        drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.1f, s * 0.3f), androidx.compose.ui.geometry.Offset(s * 0.9f, s * 0.3f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.1f, s * 0.7f), androidx.compose.ui.geometry.Offset(s * 0.9f, s * 0.7f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(c, s * 0.13f, androidx.compose.ui.geometry.Offset(s * 0.66f, s * 0.3f))
        drawCircle(c, s * 0.13f, androidx.compose.ui.geometry.Offset(s * 0.34f, s * 0.7f))
    }
}
