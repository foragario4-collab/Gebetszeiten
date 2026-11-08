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
import kotlinx.coroutines.delay

fun hasLocPermission(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun TodayScreen(v: Int) {
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
                        Prefs.place = "Mein Standort"
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
    val list = evs.filter { it.day == today }

    ScreenColumn {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Txt(Fmt.dateLong(today).uppercase(), 13, FontWeight.SemiBold, p.sub)
                Txt("Heute", 34, FontWeight.Bold)
                Txt(Hijri.text(today), 15, color = p.sub)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(p.card)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Txt(Prefs.place, 15, FontWeight.SemiBold, p.accentText, maxLines = 1)
            }
        }
        Spacer(Modifier.height(18.dp))

        // Nächstes Gebet
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(p.accent)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Txt("NÄCHSTES", 13, FontWeight.SemiBold, Color.White.copy(alpha = 0.85f))
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
                Txt("noch ${Fmt.countdown(next.at - now)}", 14, FontWeight.Medium, Color.White)
            }
        }
        Spacer(Modifier.height(18.dp))

        // Liste
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card)) {
            list.forEachIndexed { i, e ->
                PrayerRow(ctx, e, first = i == 0, isNext = e == next, past = e.at <= now, today = today)
            }
        }
        Spacer(Modifier.height(18.dp))

        // Nacht-Info
        val maghrib = list.firstOrNull { it.kind == Kind.MAGHRIB }
        val fajrNext = maghrib?.let { m -> evs.firstOrNull { it.kind == Kind.FAJR && it.at > m.at } }
        if (maghrib != null && fajrNext != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.card)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Txt("Die Nacht", 16, FontWeight.SemiBold)
                Txt(
                    "Maghrib ${Fmt.time(maghrib.at)} → Fajr ${Fmt.time(fajrNext.at)} = ${Fmt.duration(fajrNext.at - maghrib.at)}",
                    13, color = p.sub
                )
                val mm = if (Prefs.midnightMode == "sunrise") "Maghrib bis Sonnenaufgang" else "Maghrib bis Fajr"
                Txt("Mitternacht = Hälfte ($mm)", 13, color = p.sub)
                Txt("Letztes Drittel = Maghrib + ⅔ der Nacht", 13, color = p.sub)
            }
            Spacer(Modifier.height(12.dp))
        }
        val m = PrayerCalc.method(Prefs.method)
        Txt(
            "${m.name} · Asr ${if (Prefs.asr == 2) "Hanafi" else "Standard"}",
            12, color = p.sub, modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
private fun PrayerRow(ctx: Context, e: Ev, first: Boolean, isNext: Boolean, past: Boolean, today: java.time.LocalDate) {
    val p = LocalPal.current
    val weight = if (isNext) FontWeight.Bold else FontWeight.Medium
    val tag = when (e.kind) {
        Kind.SUNRISE -> "Ende Fajr"
        Kind.MIDNIGHT -> "Ende Isha"
        Kind.LAST_THIRD -> if (Times.today(e.at).isAfter(today)) "Tahajjud · morgen früh" else "Tahajjud"
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
    val labels = listOf("Aus", "Ton", "Adhan")
    val max = if (k.prayer) 2 else 1
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (mode > 0) p.hl else p.fill)
            .clickable {
                Prefs.setMode(k, (mode + 1) % (max + 1))
                Scheduler.reschedule(ctx)
            }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Txt(labels[mode.coerceIn(0, 2)], 12, FontWeight.SemiBold, if (mode > 0) p.accentText else p.sub)
    }
}
