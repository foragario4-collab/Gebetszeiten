package com.gebetszeiten.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.roundToInt

/** Nächstes Gebet einer Moschee: (Titel, Adhan, Iqamah?) – nutzt die Zeiten der Moschee, falls vorhanden. */
internal fun nextFor(m: Mosque, evs: List<Ev>, now: Long): Triple<String, String, String?>? {
    val n = evs.firstOrNull { it.at > now && it.kind.prayer } ?: return null
    val friday = n.kind == Kind.DHUHR && n.day.dayOfWeek == java.time.DayOfWeek.FRIDAY
    val key = if (friday && (m.adhan.containsKey("JUMUAH") || m.iqamah.containsKey("JUMUAH"))) "JUMUAH" else n.kind.name
    val adhan = m.adhan[key] ?: Fmt.time(n.at)
    return Triple(n.title, adhan, m.iqamah[key])
}

private fun hasTimes(m: Mosque) = m.adhan.isNotEmpty() || m.iqamah.isNotEmpty()

/** Geschätzte Fahr-/Gehzeit in Minuten (Stadtverkehr ~28 km/h + 2 Min Parken, zu Fuß 4,8 km/h). */
internal fun travelMin(distM: Float, mode: Int): Int =
    if (mode == 0) ((distM / 1000f * 1.35f) / 28f * 60f + 2f).roundToInt()
    else ((distM / 1000f * 1.25f) / 4.8f * 60f).roundToInt().coerceAtLeast(1)

/** Nächste noch kommende Iqamah einer Moschee: (Gebet, Adhan-Text, Iqamah-Zeitpunkt). */
/**
 * Relevante Iqamah: die erste, die bei Ankunft höchstens 20 Min vorbei ist.
 * Wer später ankommt, verpasst das Gebet wahrscheinlich – dann zählt das nächste.
 */
internal fun nextIqamah(m: Mosque, evs: List<Ev>, now: Long, arrival: Long = now): Triple<Ev, String, Long>? {
    if (m.iqamah.isEmpty()) return null
    for (e in evs) {
        if (!e.kind.prayer || e.at < now - 3 * 3600_000L) continue
        val key = if (e.isJumuah && m.iqamah.containsKey("JUMUAH")) "JUMUAH" else e.kind.name
        val iq = m.iqamah[key] ?: continue
        val parts = iq.split(":")
        val ms = try {
            e.day.atTime(parts[0].toInt(), parts[1].toInt()).atZone(Times.zone()).toInstant().toEpochMilli()
        } catch (ex: Exception) {
            continue
        }
        if (ms >= arrival - 20 * 60_000L && ms > now - 30 * 60_000L) return Triple(e, m.adhan[key] ?: Fmt.time(e.at), ms)
    }
    return null
}

/** Ankunfts-Hinweis: Text + Farbe. */
internal fun arrivalChip(iqMs: Long?, now: Long, travel: Int): Pair<String, Color> {
    val arr = now + travel * 60_000L
    if (iqMs == null) return t("arr_none", Fmt.time(arr)) to Color(0xFF8E8E93)
    val diff = ((iqMs - arr) / 60_000L).toInt()
    return when {
        diff >= 3 -> t("arr_ok", Fmt.time(arr), diff) to Color(0xFF248A3D)
        diff >= 0 -> t("arr_tight", Fmt.time(arr), diff) to Color(0xFFB25000)
        diff > -6 -> t("arr_late", Fmt.time(arr), -diff) to Color(0xFFC93400)
        else -> t("arr_missed") to Color(0xFFD70015)
    }
}

@Composable
internal fun ArrivalChip(text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Txt(text, 13, FontWeight.SemiBold, color, maxLines = 1)
    }
}


/**
 * Blatt zum Hoch- und Runterziehen (wie Apple Karten):
 * unten nur die Anzahl, hochgezogen die Liste, beim Antippen die Moschee mit Zeiten.
 */
@Composable
fun BoxScope.MosqueBottomSheet(
    mosques: List<Mosque>,
    selected: Mosque?,
    lat: Double,
    lng: Double,
    now: Long,
    status: String?,
    onPick: (Mosque) -> Unit,
    onClose: () -> Unit,
    onEdit: (Mosque) -> Unit,
    onRemove: (Mosque) -> Unit,
    onUpdated: (Mosque) -> Unit,
    onHide: (Mosque) -> Unit = {},
    onReport: (Mosque) -> Unit = {},
    onReload: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var parentPx by remember { mutableFloatStateOf(0f) }
    val peekDp = 72.dp
    val peekPx = with(density) { peekDp.toPx() }
    val maxPx = maxOf(peekPx + 1f, if (parentPx > 0f) parentPx * 0.86f else with(density) { 420.dp.toPx() })
    val h = remember { Animatable(peekPx) }

    // Beim Antippen einer Moschee: Blatt ganz hoch
    LaunchedEffect(selected?.id) {
        if (selected != null) h.animateTo(maxPx, spring(dampingRatio = 0.85f))
    }

    val evs = remember(now / 60_000L) { Times.around(now) }
    val sortMode = Prefs.sortMode
    val mode = Prefs.travelMode
    // Echte Routenzeiten (OpenStreetMap) für die nächsten Moscheen holen
    var routeSec by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(mosques.size, (lat * 500).toInt(), (lng * 500).toInt(), mode) {
        val near = mosques.sortedBy { Mosques.distance(lat, lng, it.lat, it.lng) }.take(40)
        routeSec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Routing.durations(lat, lng, near, mode)
        }
    }
    // Adressen (und fehlende Zeiten) für die nächsten Orte im Hintergrund nachladen
    val addrs = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    LaunchedEffect(mosques.size, (lat * 200).toInt(), (lng * 200).toInt()) {
        val near = mosques.filter { !Places.isPlace(it) }
            .sortedBy { Mosques.distance(lat, lng, it.lat, it.lng) }.take(15)
        for (m in near) {
            if (m.address.isNotBlank() || addrs.containsKey(m.id)) continue
            val a = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Mosques.lookupAddress(ctx, m) }
            if (!a.isNullOrBlank()) addrs[m.id] = a
            kotlinx.coroutines.delay(1100) // Regeln der Adresssuche: höchstens 1 Anfrage pro Sekunde
        }
        var found = false
        for (m in near.take(8)) {
            if (hasTimes(m) || !Mosques.shouldAutoFind(ctx, m.id)) continue
            val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { Mosques.findTimes(m) } catch (e: Exception) { null }
            }
            if (d != null && (d.adhan.isNotEmpty() || d.iqamah.isNotEmpty())) {
                Mosques.attachLink(ctx, m, d)
                found = true
            }
        }
        if (found) onReload()
    }
    val sorted = remember(mosques, lat, lng, sortMode, now / 60_000L) {
        val byDist = mosques.distinctBy { it.id }
            .map { it to Mosques.distance(lat, lng, it.lat, it.lng) }.sortedBy { it.second }.take(80)
        if (sortMode == 1) byDist.sortedWith(compareBy({ nextIqamah(it.first, evs, now)?.third ?: Long.MAX_VALUE }, { it.second }))
        else byDist
    }
    val expanded = h.value > (peekPx + maxPx) / 2f

    fun toggle() {
        scope.launch { h.animateTo(if (expanded) peekPx else maxPx, spring(dampingRatio = 0.85f)) }
    }

    // Unsichtbarer Messer für die verfügbare Höhe
    Box(Modifier.matchParentSize().onSizeChanged { parentPx = it.height.toFloat() })

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(with(density) { h.value.coerceIn(peekPx, maxPx).toDp() })
            .shadow(10.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(p.card)
    ) {
        // Griff + Kopf: ziehen oder antippen
        Column(
            Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch { h.snapTo((h.value - delta).coerceIn(peekPx, maxPx)) }
                    },
                    onDragStopped = { velocity ->
                        val target = when {
                            velocity < -600f -> maxPx
                            velocity > 600f -> peekPx
                            h.value > (peekPx + maxPx) / 2f -> maxPx
                            else -> peekPx
                        }
                        h.animateTo(target, spring(dampingRatio = 0.85f))
                    }
                )
                .tap { if (selected == null) toggle() }
                .then(if (selected == null) Modifier.height(peekDp) else Modifier)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(36.dp, 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(p.line)
            )
            Box(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (selected == null) {
                        val mCount = sorted.count { it.first.category == "Moschee" }
                        val oCount = sorted.size - mCount
                        Txt(
                            status ?: if (sorted.isEmpty()) t("none_loaded") else t("n_places", sorted.size),
                            18, FontWeight.Bold, maxLines = 1
                        )
                        if (expanded) Txt(t("count_split", mCount, oCount), 13, color = p.sub, maxLines = 1)
                    } else {
                        Txt(NameFmt.name(selected.name), 18, FontWeight.Bold, maxLines = 2)
                        val d = Mosques.distance(lat, lng, selected.lat, selected.lng)
                        Txt(
                            listOf(
                                when (selected.category) {
                                    "Moschee" -> NameFmt.type(selected.name).ifBlank { t("cat_mosque") }
                                    Places.CAT_PRAYER -> t("kind_prayer")
                                    Places.CAT_WUDU -> t("kind_wudu")
                                    else -> NameFmt.type(selected.name).ifBlank { t("cat_room") }
                                },
                                if (Places.isPlace(selected)) "" else Bezirk.label(selected.address), Mosques.fmtDist(d)
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            13, color = p.sub, maxLines = 2
                        )
                    }
                }
                if (selected != null) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(p.fill).tap(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) { GlyphIcon(Glyph.CLOSE, p.sub, 14.dp) }
                } else {
                    Chevron(expanded)
                }
            }
        }

        if (selected == null && h.value > peekPx + 4f) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    Segmented(listOf(t("sort_near"), t("sort_iq")), sortMode) { Prefs.sortMode = it }
                }
                ModeSegmented(mode) { Prefs.travelMode = it }
            }
            if (sorted.isEmpty() && status == null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(p.bg).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlyphIcon(Glyph.SEARCH, p.sub, 40.dp)
                    Txt(t("empty_title"), 16, FontWeight.SemiBold)
                    Txt(t("empty_text"), 13, color = p.sub, align = TextAlign.Center)
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sorted, key = { it.first.id }) { (m, d) ->
                    val nx = if (hasTimes(m)) nextFor(m, evs, now) else null
                    val travel = Routing.minutes(routeSec[m.id], d, mode)
                    val arr = now + travel * 60_000L
                    val niq = nextIqamah(m, evs, now, arr)
                    val adr = if (Places.isPlace(m)) "" else (m.address.ifBlank { addrs[m.id].orEmpty() })
                    MosqueCard(m, d, adr, travel, mode, arr, niq, nx, now) { onPick(m) }
                }
            }
        } else if (selected != null) {
            MosqueDetail(selected, evs, now, lat, lng, routeSec[selected.id], onEdit, onRemove, onUpdated, onHide, onReport)
        }
    }
}

@Composable
private fun MosqueDetail(
    m: Mosque, evs: List<Ev>, now: Long, myLat: Double, myLng: Double, listSec: Int?,
    onEdit: (Mosque) -> Unit, onRemove: (Mosque) -> Unit, onUpdated: (Mosque) -> Unit,
    onHide: (Mosque) -> Unit, onReport: (Mosque) -> Unit
) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val today = Times.today(now)
    val next = evs.firstOrNull { it.at > now && it.kind.prayer }
    var link by remember(m.id) { mutableStateOf("") }
    var busy by remember(m.id) { mutableStateOf(false) }
    var info by remember(m.id) { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    var finding by remember(m.id) { mutableStateOf(false) }
    var showLink by remember(m.id) { mutableStateOf(false) }
    var addr by remember(m.id) { mutableStateOf(m.address) }
    LaunchedEffect(m.id) {
        if (addr.isBlank()) {
            addr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Mosques.lookupAddress(ctx, m) } ?: ""
            if (addr.isNotBlank()) onUpdated(m.copy(address = addr))
        }
        // Mawaqit-Suche liefert oft keine Iqamah – dann die Moschee-Seite selbst lesen
        if (m.source == "Mawaqit" && m.iqamah.isEmpty() && m.url != null) {
            val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { Mosques.fetchDraft(m.url) } catch (e: Exception) { null }
            }
            if (d != null && d.iqamah.isNotEmpty()) onUpdated(Mosques.attachLink(ctx, m, d))
        }
    }
    var preview by remember(m.id) { mutableStateOf<Mosques.Draft?>(null) }

    fun findTimes() {
        if (finding) return
        finding = true
        info = null
        Thread {
            val d = try {
                Mosques.findTimes(m)
            } catch (e: Exception) {
                null
            }
            main.post {
                finding = false
                if (d != null && (d.adhan.isNotEmpty() || d.iqamah.isNotEmpty())) {
                    onUpdated(Mosques.attachLink(ctx, m, d))
                } else {
                    info = t("not_found_times")
                }
            }
        }.start()
    }

    fun loadLink() {
        if (link.isBlank() || busy) return
        busy = true
        info = null
        Thread {
            val res = try {
                Mosques.fetchDraft(link)
            } catch (e: Exception) {
                null
            }
            main.post {
                busy = false
                when {
                    res == null -> info = t("page_failed") + Mosques.friendlyInfo()
                    res.adhan.isEmpty() && res.iqamah.isEmpty() ->
                        info = t("no_times_found") + Mosques.friendlyInfo()
                    else -> preview = res
                }
            }
        }.start()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (NameFmt.changed(m.name)) Txt(m.name, 12, color = p.sub)
        // Teilen · Adresse kopieren · Suchen · Route
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val addrText = listOf(m.name, addr).filter { it.isNotBlank() }.joinToString(", ")
            ActionTile(Glyph.SHARE, t("share")) {
                val text = addrText + "\nhttps://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=18/%.5f/%.5f".format(
                    java.util.Locale.US, m.lat, m.lng, m.lat, m.lng
                ) + (m.url?.let { "\n$it" } ?: "")
                try {
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), m.name
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
            }
            ActionTile(Glyph.COPY, t("copy_address")) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText(m.name, addrText))
                info = null
                android.widget.Toast.makeText(ctx, t("copied"), android.widget.Toast.LENGTH_SHORT).show()
            }
            ActionTile(Glyph.SEARCH, t("search_web")) {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(addrText)))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
            }
            ActionTile(Glyph.ROUTE, t("route")) {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${m.lat},${m.lng}?q=${m.lat},${m.lng}(${Uri.encode(m.name)})"))
                    )
                } catch (e: Exception) {
                    info = t("no_nav")
                }
            }
        }
        // Gebets-/Wudu-Platz: Fotos, Uhrzeiten, Angaben statt Gebetszeiten
        val place = Places.get(ctx, m.id)
        if (place != null) {
            PlaceDetailContent(ctx, place) {
                Places.delete(ctx, m.id)
                onHide(m)
            }
        } else {
        EntrancePhoto(m.id)
        val isFav = Prefs.favId == m.id
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (isFav) p.accent.copy(alpha = 0.14f) else p.fill)
                .tap { Prefs.favId = if (isFav) "" else m.id },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(if (isFav) t("is_my_mosque") else t("set_my_mosque"), 15, FontWeight.SemiBold, if (isFav) p.accentText else Color(0xFF007AFF), maxLines = 1)
        }
        if (hasTimes(m)) {
            // dieselbe (aktuelle) Position wie in der Liste – sonst passen Entfernung und Zeit nicht zusammen
            val dist = Mosques.distance(myLat, myLng, m.lat, m.lng)
            var sec by remember(m.id, Prefs.travelMode) { mutableStateOf(listSec) }
            LaunchedEffect(m.id, Prefs.travelMode) {
                if (sec == null) {
                    sec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        Routing.durations(myLat, myLng, listOf(m), Prefs.travelMode)[m.id]
                    }
                }
            }
            val tr = Routing.minutes(sec, dist, Prefs.travelMode)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TravelIcon(Prefs.travelMode, p.text, p.bg, 18.dp)
                Txt(Fmt.duration(tr * 60_000L) + " · " + Mosques.fmtDist(dist), 14, FontWeight.SemiBold)
            }
            ArrivalBar(now + tr * 60_000L, nextIqamah(m, evs, now, now + tr * 60_000L)?.third)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Txt(t("prayer"), 12, FontWeight.SemiBold, p.sub, Modifier.weight(1f))
                Txt(t("adhan"), 12, FontWeight.SemiBold, p.sub, Modifier.width(70.dp), align = TextAlign.End)
                Txt(t("iqamah"), 12, FontWeight.SemiBold, p.sub, Modifier.width(70.dp), align = TextAlign.End)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)) {
                val prayers = listOf(Kind.FAJR, Kind.DHUHR, Kind.ASR, Kind.MAGHRIB, Kind.ISHA)
                val rows: List<Triple<String, Ev?, String>> =
                    prayers.map { k -> Triple(k.label, evs.firstOrNull { it.kind == k && it.day == today }, k.name) } +
                        (if (m.adhan.containsKey("JUMUAH") || m.iqamah.containsKey("JUMUAH")) listOf(Triple(t("k_JUMUAH"), null, "JUMUAH")) else emptyList())
                rows.forEach { (label, e, key) ->
                    val isNext = e != null && next != null && e.kind == next.kind && e.day == next.day
                    val c = if (isNext) Color.White else p.text
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isNext) p.accent else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Txt(label, 15, if (isNext) FontWeight.Bold else FontWeight.Medium, c, Modifier.weight(1f))
                        Txt(m.adhan[key] ?: e?.let { Fmt.time(it.at) } ?: "–", 15, color = c, modifier = Modifier.width(70.dp), align = TextAlign.End)
                        Txt(m.iqamah[key] ?: "–", 15, FontWeight.Bold, c, Modifier.width(70.dp), align = TextAlign.End)
                    }
                }
            }
            Txt(
                if (m.url != null && m.fetchedAt > 0 && m.iqamah.isEmpty()) t("no_iq_hint", m.source)
                else if (m.url != null && m.fetchedAt > 0) t("times_from", m.source, Fmt.time(m.fetchedAt))
                else t("iq_manual"),
                12, color = p.sub
            )
        } else {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bg).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ClockIcon(p.sub)
                Txt(t("no_times"), 16, FontWeight.SemiBold)
                Txt(
                    t("no_times_sub"),
                    13, color = p.sub, align = TextAlign.Center
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.accent)
                    .tap { findTimes() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!finding) {
                    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                        val w = size.width
                        drawCircle(
                            Color.White, w * 0.32f, androidx.compose.ui.geometry.Offset(w * 0.44f, w * 0.44f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.12f)
                        )
                        drawLine(
                            Color.White, androidx.compose.ui.geometry.Offset(w * 0.68f, w * 0.68f),
                            androidx.compose.ui.geometry.Offset(w * 0.9f, w * 0.9f),
                            strokeWidth = w * 0.13f, cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Txt(if (finding) t("finding") else t("find_times"), 15, FontWeight.SemiBold, Color.White, maxLines = 1)
            }
            Txt(t("find_times_sub"), 12, color = p.sub, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        // Link-Feld: bei Moscheen ohne Zeiten immer, sonst über den Knopf „Link hinzufügen oder ändern“
        if (hasTimes(m) && !showLink) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(p.fill)
                    .tap { showLink = true },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinkIcon(Color(0xFF007AFF))
                Spacer(Modifier.width(8.dp))
                Txt(t("change_link"), 15, FontWeight.SemiBold, Color(0xFF007AFF), maxLines = 1)
            }
        }
        if (!hasTimes(m) || showLink) {
            Txt((if (hasTimes(m)) "" else t("or") + " · ") + t("link_label"), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    if (link.isEmpty()) Txt(t("link_hint"), 14, color = p.sub, maxLines = 1)
                    BasicTextField(
                        value = link,
                        onValueChange = { link = it },
                        singleLine = true,
                        textStyle = TextStyle(color = p.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(Color(0xFF007AFF)),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Box(
                    Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF007AFF)).tap { loadLink() }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) { Txt(if (busy) t("loading") else t("add_link"), 14, FontWeight.SemiBold, Color.White) }
            }
        }
        if (!hasTimes(m) || showLink) {
            // Kopierten Link mit einem Tipp übernehmen
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(p.fill)
                    .tap {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        val txt = cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
                        if (txt.isNotBlank() && txt.length < 600) {
                            link = txt
                            loadLink()
                        }
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) { Txt(t("paste") + " & " + t("load"), 14, FontWeight.SemiBold, Color(0xFF007AFF)) }
        }
        // Vorschau vor dem Speichern
        preview?.let { d ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GreenCheck()
                    Txt(t("preview_found", d.name, d.adhan.size, d.iqamah.size), 14, FontWeight.SemiBold)
                }
                if (d.iqamah.isNotEmpty() || d.adhan.isNotEmpty()) {
                    Txt(
                        listOf("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA", "JUMUAH").mapNotNull { k ->
                            val a = d.adhan[k]
                            val i = d.iqamah[k]
                            if (a == null && i == null) null else t("k_$k") + " " + (a ?: "–") + (if (i != null) " / $i" else "")
                        }.joinToString(" · "),
                        12, color = p.sub
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(p.accent)
                            .tap {
                                onUpdated(Mosques.attachLink(ctx, m, d))
                                preview = null
                                showLink = false
                            },
                        contentAlignment = Alignment.Center
                    ) { Txt(t("apply"), 15, FontWeight.SemiBold, Color.White) }
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(p.fill)
                            .tap { preview = null },
                        contentAlignment = Alignment.Center
                    ) { Txt(t("discard"), 15, FontWeight.SemiBold, p.text) }
                }
            }
        }
        info?.let { Txt(it, 13, FontWeight.SemiBold, Color(0xFFFF3B30)) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF007AFF))
                    .tap {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("geo:${m.lat},${m.lng}?q=${m.lat},${m.lng}(${Uri.encode(m.name)})"))
                            )
                        } catch (e: Exception) {
                            info = t("no_nav")
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Txt(t("route"), 15, FontWeight.SemiBold, Color.White) }
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(p.fill)
                    .tap { onEdit(m) },
                contentAlignment = Alignment.Center
            ) { Txt(if (hasTimes(m)) t("edit_iq") else t("enter_manual"), 15, FontWeight.SemiBold, Color(0xFF007AFF)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(p.fill).tap { onHide(m) },
                contentAlignment = Alignment.Center
            ) { Txt(t("hide"), 15, FontWeight.SemiBold, p.text) }
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1AFF3B30)).tap { onReport(m) },
                contentAlignment = Alignment.Center
            ) { Txt(t("report"), 15, FontWeight.SemiBold, Color(0xFFFF3B30)) }
        }
        }
    }
}


/** Pfeil nach oben/unten wie bei Apple. */
@Composable
private fun Chevron(expanded: Boolean) {
    val p = LocalPal.current
    val rot by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, label = "chev")
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        rotate(rot) {
            val w = size.width
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.25f, w * 0.62f)
                lineTo(w * 0.5f, w * 0.37f)
                lineTo(w * 0.75f, w * 0.62f)
            }
            drawPath(
                path, p.sub,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w * 0.11f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
        }
    }
}


/** Umschalter Auto / zu Fuß mit gefüllten Symbolen. */
@Composable
private fun ModeSegmented(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPal.current
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(p.fill).padding(2.dp)) {
        for (i in 0..1) {
            Box(
                Modifier
                    .size(48.dp, 32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (i == selected) p.seg else Color.Transparent)
                    .tap { onSelect(i) },
                contentAlignment = Alignment.Center
            ) { TravelIcon(i, p.text, if (i == selected) p.seg else p.fill, 20.dp) }
        }
    }
}


/** Ankunft grün (vor/pünktlich zur Iqamah) oder rot (danach) mit der Differenz rechts. */
@Composable
internal fun ArrivalBar(arrMs: Long, iqMs: Long?) {
    val p = LocalPal.current
    val arrMin = Math.ceil(arrMs / 60_000.0).toLong()
    val green = Color(0xFF248A3D)
    val red = Color(0xFFD70015)
    val (c, diffText) = if (iqMs == null) {
        p.sub to ""
    } else {
        val diff = iqMs / 60_000L - arrMin
        when {
            diff > 0 -> green to t("diff_before", Fmt.duration(diff * 60_000L))
            diff == 0L -> green to t("diff_ontime")
            else -> red to t("diff_after", Fmt.duration(-diff * 60_000L))
        }
    }
    val bg = when (c) {
        green -> Color(0x2234C759)
        red -> Color(0x1AFF3B30)
        else -> p.fill
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(8.dp))
        Txt(t("arrival", Fmt.time(arrMin * 60_000L)), 14, FontWeight.SemiBold, c, Modifier.weight(1f), maxLines = 1)
        if (diffText.isNotEmpty()) Txt(diffText, 14, FontWeight.Bold, c, maxLines = 1)
    }
}


@Composable
private fun LinkIcon(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        rotate(-45f) {
            drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * 0.08f, w * 0.34f), androidx.compose.ui.geometry.Size(w * 0.5f, w * 0.32f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f), style = st)
            drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.34f), androidx.compose.ui.geometry.Size(w * 0.5f, w * 0.32f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f), style = st)
        }
    }
}

@Composable
private fun ClockIcon(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
        val w = size.width
        drawCircle(c, w * 0.42f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.07f))
        drawLine(c, center, androidx.compose.ui.geometry.Offset(center.x, w * 0.24f), strokeWidth = w * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(c, center, androidx.compose.ui.geometry.Offset(w * 0.68f, w * 0.6f), strokeWidth = w * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}


@Composable
private fun RowScope.ActionTile(glyph: String, label: String, onClick: () -> Unit) {
    val p = LocalPal.current
    Column(
        Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(12.dp)).background(p.bg).tap(scale = 0.92f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GlyphIcon(glyph, Color(0xFF007AFF), 20.dp)
        Spacer(Modifier.height(4.dp))
        Txt(label, 11, FontWeight.SemiBold, Color(0xFF007AFF), maxLines = 1, align = TextAlign.Center)
    }
}


/** „Fajr 05:22 · in 2 Std 10 Min“ – Uhrzeit plus Restzeit (oder „hat begonnen“). */
@Composable
private fun CountLine(label: String, time: String, day: java.time.LocalDate?, now: Long, color: Color, absMs: Long? = null) {
    val ms = absMs ?: try {
        val p = time.split(":")
        (day ?: Times.today(now)).atTime(p[0].toInt(), p[1].toInt()).atZone(Times.zone()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
    val rest = when {
        ms == null -> ""
        ms > now -> " · " + t("in_time", Fmt.duration(ms - now))
        else -> " · " + t("started")
    }
    Txt("$label $time$rest", 13, FontWeight.SemiBold, color, maxLines = 1)
}


/** Aufgeräumte Karte in der Liste: Name + Adresse, Adhan und Iqamah nebeneinander, ca. Ankunft. */
@Composable
private fun MosqueCard(
    m: Mosque, d: Float, adr: String, travel: Int, mode: Int, arr: Long,
    niq: Triple<Ev, String, Long>?, nx: Triple<String, String, String?>?, now: Long, onClick: () -> Unit
) {
    val p = LocalPal.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.bg).tap(scale = 0.98f, onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KindIcon(m.category, 38.dp)
            Column(Modifier.weight(1f)) {
                Txt(NameFmt.name(m.name), 16, FontWeight.SemiBold, maxLines = 1)
                val sub = when {
                    Places.isPlace(m) -> (if (m.category == Places.CAT_WUDU) t("kind_wudu") else t("kind_prayer")) +
                        (if (m.address.isNotBlank()) " · " + m.address else "")
                    adr.isNotBlank() -> Bezirk.short(adr)
                    else -> NameFmt.type(m.name)
                }
                if (sub.isNotBlank()) Txt(sub, 12, color = p.sub, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TravelIcon(mode, p.text, p.bg, 16.dp)
                    Txt(Fmt.duration(travel * 60_000L), 14, FontWeight.Bold)
                }
                Txt(Mosques.fmtDist(d), 12, color = p.sub)
            }
        }
        if (!Places.isPlace(m)) {
            if (niq != null || nx != null) {
                val title = niq?.first?.title ?: nx!!.first
                val adhan = niq?.second ?: nx!!.second
                val adhanMs = try {
                    val day = niq?.first?.day ?: Times.today(now)
                    val parts = adhan.split(":")
                    day.atTime(parts[0].toInt(), parts[1].toInt()).atZone(Times.zone()).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    null
                }
                val iqText = niq?.let { Fmt.time(it.third) } ?: nx?.third
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeTile("$title · ${t("adhan")}".uppercase(), adhan, adhanMs, now, p.text, p.sub, Modifier.weight(1f))
                    TimeTile(t("iqamah").uppercase(), iqText ?: "–", niq?.third, now, p.accentText, p.accentText, Modifier.weight(1f))
                }
            } else {
                Txt(t("no_times"), 13, FontWeight.Medium, p.sub, maxLines = 1)
            }
            ArrivalBar(arr, niq?.third)
        }
    }
}

@Composable
private fun TimeTile(label: String, time: String, ms: Long?, now: Long, color: Color, labelColor: Color, modifier: Modifier) {
    val p = LocalPal.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(p.card).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Txt(label, 11, FontWeight.SemiBold, labelColor, maxLines = 1)
        Txt(time, 22, FontWeight.SemiBold, color, maxLines = 1)
        val rest = when {
            ms == null -> ""
            ms > now -> t("in_time", Fmt.duration(ms - now))
            else -> t("started")
        }
        if (rest.isNotBlank()) Txt(rest, 12, color = p.sub, maxLines = 1)
    }
}
