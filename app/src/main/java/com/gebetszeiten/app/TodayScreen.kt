package com.gebetszeiten.app

import android.Manifest
import android.content.Context
import android.os.Build
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

fun hasLocPermission(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun TodayScreen(v: Int, onSettings: () -> Unit = {}, onOpenMap: () -> Unit = {}) {
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

    // EIN Bildschirm ohne Scrollen: lebender Himmel, darauf echtes Milchglas (wie in der Probe)
    val sky = rememberSky(now)
    val reg = remember { GlassRegistry() }
    val tintA = (Math.round((0.18f + sky.listLum * 0.42f) * 20f) / 20f).coerceIn(0.2f, 0.5f)
    val tint = Color(0xFF0E1426).copy(alpha = tintA)
    val gPal = remember(Math.round(sky.listLum * 20f)) { glassPal(sky).copy(card = Color.Transparent) }
    val (fg, shadow) = rememberSkyText(sky)
    val wx = sky.wx
    val gpsFresh = Prefs.lastGpsAt > now - 6 * 3600_000L && Prefs.lastGpsLat != 0.0
    val awayShown = isToday && !Prefs.autoLoc && gpsFresh &&
        Mosques.distance(Prefs.lat, Prefs.lng, Prefs.lastGpsLat, Prefs.lastGpsLng) > 50_000f
    // Glasflächen, die gerade nicht sichtbar sind, aus dem Unschärfe-Bereich entfernen
    androidx.compose.runtime.SideEffect {
        val keep = buildSet { add("list"); if (isToday) add("fav") else add("todaypill"); if (awayShown) add("away") }
        reg.rects.keys.retainAll(keep)
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { reg.origin = it.positionInRoot() }) {
        SkyBackground(sky, Modifier.fillMaxSize())
        GlassBackdrop(sky, reg)
        androidx.compose.runtime.CompositionLocalProvider(LocalPal provides gPal) {
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 12.dp)
            ) {
                val small = maxHeight < 700.dp
                Column(Modifier.fillMaxSize()) {
                    // Kopf: Tag zurück · Datum + Ort/Wetter · Tag vor · Einstellungen
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkyIconButton(Glyph.CHEV_L, fg) { offset-- }
                        Column(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.Text(
                                DateText.headerTop(dayMs) + " · " + Hijri.text(dayMs), color = fg.copy(alpha = 0.92f), maxLines = 1,
                                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, shadow = shadow)
                            )
                            androidx.compose.material3.Text(
                                listOfNotNull(Prefs.place, wx?.temp?.let { "$it°" }, wx?.label()).joinToString(" · "),
                                color = fg, maxLines = 1,
                                style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, shadow = shadow)
                            )
                        }
                        SkyIconButton(Glyph.CHEV_R, fg) { offset++ }
                        Spacer(Modifier.width(6.dp))
                        SkyIconButton("M4 7h10M18 7h2M4 17h4M12 17h8M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM10 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", fg, onClick = onSettings)
                    }
                    if (!isToday) {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            Row(
                                Modifier.glass(reg, "todaypill", 17.dp, tint).tap { offset = 0 }.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlyphIcon(Glyph.RESET, Color.White, 14.dp)
                                Spacer(Modifier.width(6.dp))
                                Txt(t("tab_today"), 14, FontWeight.SemiBold, Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // Mitte: nächstes Gebet groß (heute) bzw. Titel des gewählten Tages
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val ts = { size: Int, w: FontWeight -> androidx.compose.ui.text.TextStyle(fontSize = size.sp, fontWeight = w, shadow = shadow) }
                        if (isToday) {
                            androidx.compose.material3.Text(t("next_up").uppercase(), color = fg.copy(alpha = 0.9f), style = ts(13, FontWeight.SemiBold).copy(letterSpacing = 1.5.sp))
                            androidx.compose.material3.Text(next.title, color = fg, style = ts(if (small) 32 else 40, FontWeight.Bold), maxLines = 1)
                            androidx.compose.material3.Text(Fmt.countdown(next.at - now), color = fg, style = ts(if (small) 48 else 60, FontWeight.ExtraLight).copy(letterSpacing = (-2).sp), maxLines = 1)
                            androidx.compose.material3.Text(t("at_time", Fmt.time(next.at)), color = fg.copy(alpha = 0.92f), style = ts(15, FontWeight.Normal))
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.width(170.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(fg.copy(alpha = 0.25f))) {
                                Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(3.dp)).background(fg))
                            }
                        } else {
                            androidx.compose.material3.Text(title, color = fg, style = ts(if (small) 30 else 36, FontWeight.Bold), maxLines = 1)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // Hinweis: weit weg vom eingestellten Ort
                    if (awayShown) {
                        Row(
                            Modifier.fillMaxWidth().glass(reg, "away", 16.dp, Color(0x55FF9500)).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Txt(t("away_title", Prefs.place), 13, FontWeight.SemiBold, Color.White, Modifier.weight(1f), maxLines = 2)
                            Box(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFFF9500)).tap {
                                    Prefs.autoLoc = true
                                    Loc.applyFix(ctx, Prefs.lastGpsLat, Prefs.lastGpsLng)
                                }.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) { Txt(t("apply"), 13, FontWeight.SemiBold, Color.White) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    // Meine Moschee (kompakt)
                    if (isToday) {
                        Box(Modifier.fillMaxWidth().glass(reg, "fav", 18.dp, tint)) { MyMosqueCard(now, v, onOpenMap) }
                        Spacer(Modifier.height(8.dp))
                    }
                    // Gebetszeiten auf Glas
                    Column(Modifier.fillMaxWidth().glass(reg, "list", 24.dp, tint)) {
                        list.forEachIndexed { i, e ->
                            GlassPrayerRow(
                                ctx, e, first = i == 0, isNext = isToday && e == next, past = isToday && e.at <= now,
                                nextIn = if (isToday && e == next) Fmt.countdown(next.at - now) else null, small = small
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkyIconButton(path: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).tap(scale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { GlyphIcon(path, color, 18.dp) }
}

@Composable
private fun PrayerRow(ctx: Context, e: Ev, first: Boolean, isNext: Boolean, past: Boolean, today: java.time.LocalDate, compact: Boolean = false, small: Boolean = false) {
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
                .heightIn(min = if (small) 34.dp else if (compact) 40.dp else 54.dp)
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
                Txt(e.title, if (compact) 16 else 17, weight, maxLines = 1)
                if (tag != null && !compact) Txt(tag, 12, color = p.sub)
            }
            if (e.kind != Kind.MIDNIGHT) ModeChip(ctx, e.kind)
            Txt(Fmt.time(e.at), if (compact) 18 else 17, if (compact) FontWeight.SemiBold else weight)
        }
    }
}

/**
 * Zeile wie in der Himmel-Probe: Punkt (beim nächsten Gebet leuchtend grün), Name,
 * beim nächsten Gebet „in 1:12“ in Grün, große Uhrzeit. Vergangene Gebete gedämpft.
 * Antippen wechselt Aus → Ton → Adhan (kurze Bestätigung unten).
 */
@Composable
private fun GlassPrayerRow(ctx: Context, e: Ev, first: Boolean, isNext: Boolean, past: Boolean, nextIn: String?, small: Boolean) {
    val green = Color(0xFF7CF0C8)
    val alpha = if (past && !isNext) 0.55f else 1f
    Column(
        Modifier.fillMaxWidth()
            .background(if (isNext) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .tap(scale = 0.99f) {
                val max = if (e.kind.prayer) 2 else 1
                if (e.kind == Kind.MIDNIGHT) return@tap
                val m = (Prefs.mode(e.kind) + 1) % (max + 1)
                Prefs.setMode(e.kind, m)
                Scheduler.reschedule(ctx)
                val label = listOf(t("mode_off"), t("mode_tone"), t("mode_adhan"))[m]
                android.widget.Toast.makeText(ctx, e.title + ": " + label, android.widget.Toast.LENGTH_SHORT).show()
            }
    ) {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (small) 36.dp else 44.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                if (isNext) drawCircle(green.copy(alpha = 0.35f), size.minDimension * 0.45f)
                drawCircle(if (isNext) green else Color.White.copy(alpha = 0.35f * alpha), size.minDimension * 0.22f)
            }
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Text(
                e.title, color = Color.White.copy(alpha = alpha), maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium)
            )
            if (nextIn != null) {
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Text(
                    t("in_time", nextIn), color = green, maxLines = 1,
                    style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                )
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Text(
                Fmt.time(e.at), color = Color.White.copy(alpha = alpha), maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 19.sp, fontWeight = if (past && !isNext) FontWeight.Medium else FontWeight.SemiBold,
                    fontFeatureSettings = "tnum", letterSpacing = 0.2.sp
                )
            )
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


private data class FavInfo(val name: String, val prayer: String, val iqMs: Long?, val travel: Int)

@Composable
private fun MyMosqueCard(now: Long, v: Int, onOpenMap: () -> Unit) {
    val p = LocalPal.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val favId = Prefs.favId
    var info by remember(favId) { mutableStateOf<FavInfo?>(null) }
    // im Hintergrund laden (Dateien lesen, Route berechnen), jede Minute auffrischen
    androidx.compose.runtime.LaunchedEffect(favId, now / 60_000L, v) {
        if (favId.isBlank()) {
            info = null
            return@LaunchedEffect
        }
        info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val m = Mosques.listAll(ctx).firstOrNull { it.id == favId } ?: return@withContext null
            val fresh = Prefs.lastGpsAt > System.currentTimeMillis() - 3 * 3600_000L && Prefs.lastGpsLat != 0.0
            val la = if (fresh) Prefs.lastGpsLat else Prefs.lat
            val lo = if (fresh) Prefs.lastGpsLng else Prefs.lng
            val dist = Mosques.distance(la, lo, m.lat, m.lng)
            val sec = Routing.durations(la, lo, listOf(m), Prefs.travelMode)[m.id]
            val travel = Routing.minutes(sec, dist, Prefs.travelMode)
            val t0 = System.currentTimeMillis()
            val niq = nextIqamah(m, Times.around(t0), t0, t0 + travel * 60_000L)
            FavInfo(NameFmt.name(m.name), niq?.first?.title ?: "", niq?.third, travel)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val i = info
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindIcon("Moschee", 26.dp)
            Spacer(Modifier.width(8.dp))
            Txt(if (i != null) t("my_mosque") + " · " + i.name else t("my_mosque"), 15, FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Txt(t("change"), 13, color = Color(0xFF007AFF), modifier = Modifier.tap(onClick = onOpenMap).padding(4.dp))
        }
        if (i == null) {
            Txt(t("my_mosque_hint"), 13, color = p.sub)
        } else if (i.iqMs == null) {
            Txt(t("no_times"), 14, color = p.sub)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(t("iqamah") + " " + i.prayer, 15, modifier = Modifier.weight(1f))
                Txt(Fmt.time(i.iqMs), 24, FontWeight.SemiBold, p.accentText)
            }
            val leave = i.iqMs - i.travel * 60_000L
            val late = leave < now
            val c = if (late) Color(0xFFD70015) else Color(0xFF248A3D)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Txt(t("leave_by", Fmt.time(leave)), 14, FontWeight.SemiBold, c, Modifier.weight(1f), maxLines = 1)
                TravelIcon(Prefs.travelMode, c, p.card, 15.dp)
                Spacer(Modifier.width(4.dp))
                Txt(Fmt.duration(i.travel * 60_000L), 14, FontWeight.Bold, c)
            }
        }
    }
}
