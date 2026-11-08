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
import kotlinx.coroutines.launch
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
    onUpdated: (Mosque) -> Unit
) {
    val p = LocalPal.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var parentPx by remember { mutableFloatStateOf(0f) }
    val peekPx = with(density) { 84.dp.toPx() }
    val maxPx = maxOf(peekPx + 1f, if (parentPx > 0f) parentPx * 0.86f else with(density) { 420.dp.toPx() })
    val h = remember { Animatable(peekPx) }

    // Beim Antippen einer Moschee: Blatt ganz hoch
    LaunchedEffect(selected?.id) {
        if (selected != null) h.animateTo(maxPx, spring(dampingRatio = 0.85f))
    }

    val sorted = remember(mosques, lat, lng) {
        mosques.map { it to Mosques.distance(lat, lng, it.lat, it.lng) }.sortedBy { it.second }.take(80)
    }
    val evs = remember(now / 60_000L) { Times.around(now) }
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
                .clickable { if (selected == null) toggle() }
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
                            status ?: if (sorted.isEmpty()) "Keine Moscheen geladen" else "${sorted.size} Gebetsorte in der Nähe",
                            18, FontWeight.Bold, maxLines = 1
                        )
                        Txt(
                            if (expanded) "$mCount Moscheen · $oCount Gebetsräume/Vereine"
                            else "Nach oben ziehen für die Liste",
                            13, color = p.sub, maxLines = 1
                        )
                    } else {
                        Txt(selected.name, 18, FontWeight.Bold, maxLines = 2)
                        val d = Mosques.distance(lat, lng, selected.lat, selected.lng)
                        Txt(
                            listOf(selected.category, selected.address, Mosques.fmtDist(d)).filter { it.isNotBlank() }.joinToString(" · "),
                            13, color = p.sub, maxLines = 2
                        )
                    }
                }
                if (selected != null) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(p.fill).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) { Txt("✕", 14, FontWeight.Bold, p.sub) }
                } else {
                    Txt(if (expanded) "Runter" else "Hoch", 13, FontWeight.SemiBold, Color(0xFF007AFF))
                }
            }
        }

        if (selected == null) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sorted, key = { it.first.id }) { (m, d) ->
                    val nx = if (hasTimes(m)) nextFor(m, evs, now) else null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(p.bg)
                            .clickable { onPick(m) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(38.dp).clip(CircleShape)
                                .background(if (m.category == "Moschee") Color(0xFF13917C) else Color(0xFF5E9C8F)),
                            contentAlignment = Alignment.Center
                        ) { Txt(if (m.category == "Moschee") "☪" else "◆", 16, color = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Txt(m.name, 15, FontWeight.SemiBold, maxLines = 1)
                            if (nx != null) {
                                Txt("${nx.first} ${nx.second} · Iqamah ${nx.third ?: "–"}", 13, FontWeight.SemiBold, p.accentText, maxLines = 1)
                            } else {
                                Txt("Keine Zeiten verfügbar", 13, FontWeight.Medium, p.sub, maxLines = 1)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Txt(Mosques.fmtDist(d), 13, color = p.sub)
                            Txt(if (m.category == "Moschee") "Moschee" else "Verein", 11, FontWeight.SemiBold, p.sub)
                        }
                    }
                }
            }
        } else {
            MosqueDetail(selected, evs, now, onEdit, onRemove, onUpdated)
        }
    }
}

@Composable
private fun MosqueDetail(
    m: Mosque, evs: List<Ev>, now: Long,
    onEdit: (Mosque) -> Unit, onRemove: (Mosque) -> Unit, onUpdated: (Mosque) -> Unit
) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val today = Times.today(now)
    val next = evs.firstOrNull { it.at > now && it.kind.prayer }
    var link by remember(m.id) { mutableStateOf("") }
    var busy by remember(m.id) { mutableStateOf(false) }
    var info by remember(m.id) { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

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
                    res == null -> info = "Seite konnte nicht geladen werden – Link und Internet prüfen."
                    res.adhan.isEmpty() && res.iqamah.isEmpty() ->
                        info = "Auf der Seite wurden keine Zeiten gefunden. Bitte Iqamah von Hand eintragen."
                    else -> onUpdated(Mosques.attachLink(ctx, m, res))
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
        if (hasTimes(m)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Txt("Gebet", 12, FontWeight.SemiBold, p.sub, Modifier.weight(1f))
                Txt("Adhan", 12, FontWeight.SemiBold, p.sub, Modifier.width(70.dp), align = TextAlign.End)
                Txt("Iqamah", 12, FontWeight.SemiBold, p.sub, Modifier.width(70.dp), align = TextAlign.End)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)) {
                val prayers = listOf(Kind.FAJR, Kind.DHUHR, Kind.ASR, Kind.MAGHRIB, Kind.ISHA)
                val rows: List<Triple<String, Ev?, String>> =
                    prayers.map { k -> Triple(k.label, evs.firstOrNull { it.kind == k && it.day == today }, k.name) } +
                        (if (m.adhan.containsKey("JUMUAH") || m.iqamah.containsKey("JUMUAH")) listOf(Triple("Jumuah", null, "JUMUAH")) else emptyList())
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
                if (m.url != null && m.fetchedAt > 0) "Zeiten von ${m.source} · aktualisiert ${Fmt.time(m.fetchedAt)}"
                else "Iqamah von Hand eingetragen",
                12, color = p.sub
            )
        } else {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bg).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Txt("🕓", 26)
                Txt("Keine Zeiten verfügbar", 16, FontWeight.SemiBold)
                Txt(
                    "Füge den Link der Moschee von MasjidBox oder Mawaqit hinzu – die App liest Adhan und Iqamah dann automatisch aus.",
                    13, color = p.sub, align = TextAlign.Center
                )
            }
            Txt("LINK DER MOSCHEE", 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    if (link.isEmpty()) Txt("z. B. masjidbox.com/gebetszeiten/…", 14, color = p.sub, maxLines = 1)
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
                    Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF007AFF)).clickable { loadLink() }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) { Txt(if (busy) "Lädt …" else "Link hinzufügen", 14, FontWeight.SemiBold, Color.White) }
            }
        }
        info?.let { Txt(it, 13, FontWeight.SemiBold, Color(0xFFFF3B30)) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF007AFF))
                    .clickable {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("geo:${m.lat},${m.lng}?q=${m.lat},${m.lng}(${Uri.encode(m.name)})"))
                            )
                        } catch (e: Exception) {
                            info = "Keine Navigations-App gefunden (z. B. Vela Maps)"
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Txt("Route", 15, FontWeight.SemiBold, Color.White) }
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(p.fill)
                    .clickable { onEdit(m) },
                contentAlignment = Alignment.Center
            ) { Txt(if (hasTimes(m)) "Iqamah bearbeiten" else "Von Hand eintragen", 15, FontWeight.SemiBold, Color(0xFF007AFF)) }
        }
        if (m.custom) {
            Txt(
                "Entfernen", 15, color = Color(0xFFFF3B30),
                modifier = Modifier.clickable { onRemove(m) }.padding(vertical = 8.dp)
            )
        }
    }
}
