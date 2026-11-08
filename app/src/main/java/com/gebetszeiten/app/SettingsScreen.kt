package com.gebetszeiten.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val modeLabels = listOf("Aus", "Mitteilung (Ton)", "Adhan")

private fun displayName(ctx: Context, uri: Uri): String = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Audiodatei"
} catch (e: Exception) {
    "Audiodatei"
}

private fun keep(ctx: Context, uri: Uri) {
    try {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: Exception) {
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

@Composable
fun SettingsScreen(v: Int) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var choice by remember { mutableStateOf<Choice?>(null) }
    var coords by remember { mutableStateOf(false) }
    var locStatus by remember { mutableStateOf<String?>(null) }

    fun changed() = Scheduler.reschedule(ctx)

    fun useGps() {
        locStatus = "Standort wird bestimmt …"
        Loc.fetch(ctx) { loc ->
            if (loc == null) {
                locStatus = "Kein Standort gefunden – ist der Standort im Handy eingeschaltet?"
            } else {
                Prefs.lat = loc.latitude
                Prefs.lng = loc.longitude
                Prefs.place = "Mein Standort"
                Prefs.autoLoc = true
                locStatus = null
                changed()
                Loc.placeName(ctx, loc.latitude, loc.longitude) { n ->
                    if (n != null) {
                        Prefs.place = n
                        Widgets.updateAll(ctx)
                    }
                }
            }
        }
    }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            useGps()
        } else {
            locStatus = "Standort-Berechtigung wurde nicht erteilt."
        }
    }

    fun gps() {
        if (hasLocPermission(ctx)) useGps()
        else locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val pickAdhan = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            keep(ctx, uri)
            Prefs.adhanUri = uri.toString()
            Prefs.adhanName = displayName(ctx, uri)
        }
    }
    val pickFajr = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            keep(ctx, uri)
            Prefs.fajrAdhanUri = uri.toString()
            Prefs.fajrAdhanName = displayName(ctx, uri)
        }
    }

    ScreenColumn {
        LargeTitle("Einstellungen")

        // ---------- Standort ----------
        Section("Standort", footer = locStatus ?: "Funktioniert ohne Google-Dienste – direkt über GPS.") {
            ValueRow("Ort", Prefs.place, first = true, sub = "%.4f, %.4f".format(Prefs.lat, Prefs.lng)) {
                val names = Loc.cities.map { it.name } + "Koordinaten eingeben …"
                choice = Choice("Ort wählen", names, Loc.cities.indexOfFirst { it.name == Prefs.place }) { i ->
                    if (i < Loc.cities.size) {
                        val c = Loc.cities[i]
                        Prefs.lat = c.lat
                        Prefs.lng = c.lng
                        Prefs.place = c.name
                        Prefs.autoLoc = false
                        changed()
                    } else {
                        coords = true
                    }
                }
            }
            ToggleRow("Automatisch (GPS)", Prefs.autoLoc, sub = "Beim Öffnen der App aktualisieren") { on ->
                if (on) {
                    gps()
                } else {
                    Prefs.autoLoc = false
                }
            }
            ButtonRow("Standort jetzt bestimmen") { gps() }
        }

        // ---------- Berechnung ----------
        val m = PrayerCalc.method(Prefs.method)
        val fa = if (m.id == "custom") Prefs.customFajr.toDouble() else m.fajr
        val ia = if (m.id == "custom") Prefs.customIsha.toDouble() else m.isha
        val ishaText = if (m.ishaMinutes > 0) "Isha ${m.ishaMinutes} Min nach Maghrib" else "Isha $ia°"
        Section("Berechnung", footer = "Fajr $fa° · $ishaText. Wähle die Methode deiner Moschee.") {
            ValueRow("Methode", m.name, first = true) {
                choice = Choice(
                    "Berechnungsmethode",
                    PrayerCalc.methods.map { it.name },
                    PrayerCalc.methods.indexOfFirst { it.id == m.id }
                ) { i ->
                    Prefs.method = PrayerCalc.methods[i].id
                    changed()
                }
            }
            if (m.id == "custom") {
                StepperRow("Fajr-Winkel", "%.1f°".format(Prefs.customFajr),
                    onMinus = { Prefs.customFajr = (Prefs.customFajr - 0.5f).coerceAtLeast(10f); changed() },
                    onPlus = { Prefs.customFajr = (Prefs.customFajr + 0.5f).coerceAtMost(22f); changed() })
                StepperRow("Isha-Winkel", "%.1f°".format(Prefs.customIsha),
                    onMinus = { Prefs.customIsha = (Prefs.customIsha - 0.5f).coerceAtLeast(10f); changed() },
                    onPlus = { Prefs.customIsha = (Prefs.customIsha + 0.5f).coerceAtMost(22f); changed() })
            }
            SegRow("Asr", listOf("Standard", "Hanafi"), Prefs.asr - 1) { i ->
                Prefs.asr = i + 1
                changed()
            }
            val hl = listOf("angle" to "Winkelbasiert", "middle" to "Mitte der Nacht", "seventh" to "Ein Siebtel")
            ValueRow("Hohe Breitengrade", hl.firstOrNull { it.first == Prefs.highLat }?.second ?: "Winkelbasiert") {
                choice = Choice("Hohe Breitengrade", hl.map { it.second }, hl.indexOfFirst { it.first == Prefs.highLat }) { i ->
                    Prefs.highLat = hl[i].first
                    changed()
                }
            }
        }

        // ---------- Nacht ----------
        Section("Nacht", footer = "Mitternacht = Maghrib + ½ Nacht · Letztes Drittel = Maghrib + ⅔ der Nacht (Maghrib bis Fajr).") {
            val mm = listOf("fajr" to "Maghrib – Fajr", "sunrise" to "Maghrib – Sonnenaufgang")
            ValueRow("Mitternacht", mm.firstOrNull { it.first == Prefs.midnightMode }?.second ?: "Maghrib – Fajr", first = true) {
                choice = Choice("Mitternacht berechnen", mm.map { it.second }, mm.indexOfFirst { it.first == Prefs.midnightMode }) { i ->
                    Prefs.midnightMode = mm[i].first
                    changed()
                }
            }
            ValueRow("Letztes Drittel", modeLabels[Prefs.mode(Kind.LAST_THIRD).coerceIn(0, 1)], sub = "Mitteilung für Tahajjud") {
                choice = Choice("Letztes Drittel", modeLabels.take(2), Prefs.mode(Kind.LAST_THIRD)) { i ->
                    Prefs.setMode(Kind.LAST_THIRD, i)
                    changed()
                }
            }
        }

        // ---------- Mitteilungen ----------
        Section("Mitteilungen") {
            listOf(Kind.FAJR, Kind.SUNRISE, Kind.DHUHR, Kind.ASR, Kind.MAGHRIB, Kind.ISHA).forEachIndexed { idx, k ->
                val opts = if (k.prayer) modeLabels else modeLabels.take(2)
                ValueRow(k.label, opts[Prefs.mode(k).coerceIn(0, opts.size - 1)], first = idx == 0) {
                    choice = Choice(k.label, opts, Prefs.mode(k)) { i ->
                        Prefs.setMode(k, i)
                        changed()
                    }
                }
            }
            val rem = listOf(0, 5, 10, 15, 20, 30, 45, 60)
            ValueRow("Erinnerung vorher", if (Prefs.reminder == 0) "Aus" else "${Prefs.reminder} Min") {
                choice = Choice("Erinnerung vor dem Gebet", rem.map { if (it == 0) "Aus" else "$it Minuten" }, rem.indexOf(Prefs.reminder)) { i ->
                    Prefs.reminder = rem[i]
                    changed()
                }
            }
        }

        // ---------- Live ----------
        Section(
            "Live-Countdown",
            footer = "Laufende Mitteilung mit Countdown bis zum Gebet. Ab Android 16 zusätzlich als Live-Update in der Statusleiste und oben auf dem Sperrbildschirm."
        ) {
            ToggleRow("Live-Countdown", Prefs.live, first = true) { Prefs.live = it; changed() }
            val lm = listOf(15, 30, 45, 60, 90, 120)
            ValueRow("Startet vorher", "${Prefs.liveMin} Min") {
                choice = Choice("Countdown startet vorher", lm.map { "$it Minuten" }, lm.indexOf(Prefs.liveMin)) { i ->
                    Prefs.liveMin = lm[i]
                    changed()
                }
            }
        }

        // ---------- Adhan ----------
        Section("Adhan", footer = "Wähle eine eigene Adhan-Audiodatei (z. B. MP3) vom Handy. Ohne Datei kommt ein normaler Mitteilungston.") {
            ValueRow("Adhan-Datei", Prefs.adhanName ?: "Keine gewählt", first = true) { pickAdhan.launch(arrayOf("audio/*")) }
            ValueRow("Adhan für Fajr", Prefs.fajrAdhanName ?: "Wie oben") { pickFajr.launch(arrayOf("audio/*")) }
            if (Prefs.fajrAdhanUri != null) {
                ButtonRow("Fajr-Adhan entfernen") {
                    Prefs.fajrAdhanUri = null
                    Prefs.fajrAdhanName = null
                }
            }
            ButtonRow("Adhan probehören") {
                val u = Prefs.adhanUri
                if (u != null) AdhanService.start(ctx, u, null)
            }
            ButtonRow("Adhan stoppen", color = Color(0xFFFF3B30)) { AdhanService.stop(ctx) }
        }

        // ---------- Zeitkorrektur ----------
        Section("Zeitkorrektur", footer = "Passe die Zeiten minutengenau an den Kalender deiner Moschee an.") {
            listOf(Kind.FAJR, Kind.SUNRISE, Kind.DHUHR, Kind.ASR, Kind.MAGHRIB, Kind.ISHA).forEachIndexed { idx, k ->
                val o = Prefs.offset(k)
                StepperRow(k.label, "${signed(o)} Min", first = idx == 0,
                    onMinus = { Prefs.setOffset(k, (o - 1).coerceAtLeast(-60)); changed() },
                    onPlus = { Prefs.setOffset(k, (o + 1).coerceAtMost(60)); changed() })
            }
        }

        // ---------- Darstellung ----------
        Section("Darstellung") {
            SegRow("Design", listOf("Auto", "Hell", "Dunkel"), Prefs.theme, first = true) {
                Prefs.theme = it
                Widgets.updateAll(ctx)
            }
            StepperRow("Hijri-Datum", "${signed(Prefs.hijriAdj)} Tage",
                onMinus = { Prefs.hijriAdj = (Prefs.hijriAdj - 1).coerceAtLeast(-2); Widgets.updateAll(ctx) },
                onPlus = { Prefs.hijriAdj = (Prefs.hijriAdj + 1).coerceAtMost(2); Widgets.updateAll(ctx) })
            ToggleRow("24-Stunden-Format", Prefs.use24) { Prefs.use24 = it; Widgets.updateAll(ctx) }
        }

        // ---------- Kalender ----------
        Section("Kalender", footer = "Gilt für die App und das Widget.") {
            val dm = listOf("Hijri", "Gregorianisch", "Beides")
            ValueRow("Datum anzeigen", dm[Prefs.dateMode.coerceIn(0, 2)], first = true) {
                choice = Choice("Datum anzeigen", dm, Prefs.dateMode) { i ->
                    Prefs.dateMode = i
                    Widgets.updateAll(ctx)
                }
            }
        }

        // ---------- Moschee-Radar ----------
        Section(
            "Moschee-Radar",
            footer = "Der Radar wird im Tab „Qibla“ auf der Karte eingeschaltet. Er zeigt live die nächste Moschee und meldet sich, wenn du in der Nähe bist und das Gebet bald beginnt."
        ) {
            val rr = listOf(500, 1000, 1500, 2000, 3000, 5000)
            ValueRow("Hinweis-Umkreis", Mosques.fmtDist(Prefs.radarRadius.toFloat()), first = true) {
                choice = Choice("Hinweis-Umkreis", rr.map { Mosques.fmtDist(it.toFloat()) }, rr.indexOf(Prefs.radarRadius)) { i ->
                    Prefs.radarRadius = rr[i]
                }
            }
        }

        // ---------- Widget ----------
        Section("Widget", footer = "Das nächste Gebet steht im großen Widget immer in der Mitte und rutscht mit der Zeit nach links.") {
            ToggleRow("Sonnenaufgang", Prefs.wSunrise, first = true) { Prefs.wSunrise = it; Widgets.updateAll(ctx) }
            ToggleRow("Mitternacht", Prefs.wMidnight) { Prefs.wMidnight = it; Widgets.updateAll(ctx) }
            ToggleRow("Letztes Drittel", Prefs.wThird) { Prefs.wThird = it; Widgets.updateAll(ctx) }
        }

        // ---------- Testen ----------
        Section("Testen", footer = "Live-Updates kannst du in den Android-Mitteilungseinstellungen dieser App erlauben oder abschalten.") {
            val now = System.currentTimeMillis()
            val next = Times.around(now).first { it.at > now && it.kind.prayer }
            ButtonRow("Test: Gebets-Mitteilung", first = true) {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                nm?.notify(Notifs.prayerId(next.kind), Notifs.buildPrayer(ctx, next.copy(at = System.currentTimeMillis()), Notifs.CH_PRAYER))
            }
            ButtonRow("Test: Erinnerung") {
                Notifs.reminder(ctx, next.copy(at = System.currentTimeMillis() + 15 * 60_000L))
            }
            ButtonRow("Test: Live-Countdown (2 Min)") {
                val t = System.currentTimeMillis()
                Notifs.live(ctx, next.copy(at = t + 2 * 60_000L), prevAt = t)
            }
            ButtonRow("Test: Adhan-Mitteilung") {
                val u = Prefs.adhanUri
                if (u != null) AdhanService.start(ctx, u, next.copy(at = System.currentTimeMillis()))
                else Notifs.prayerAlert(ctx, next.copy(at = System.currentTimeMillis()))
            }
            ButtonRow("Mitteilungs-Einstellungen öffnen") {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    choice?.let { c -> ChoiceDialog(c) { choice = null } }
    if (coords) CoordDialog { coords = false }
}

@Composable
private fun CoordDialog(onClose: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    val style = TextStyle(color = p.text, fontSize = 17.sp)

    @Composable
    fun Field(label: String, value: String, number: Boolean, onChange: (String) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
            Txt(label, 13, color = p.sub)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.fill)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(p.accentText),
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(p.card)
                .padding(vertical = 14.dp)
        ) {
            Txt("Koordinaten eingeben", 17, FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            Field("Name", name, false) { name = it }
            Field("Breitengrad (z. B. 48.2082)", lat, true) { lat = it }
            Field("Längengrad (z. B. 16.3738)", lng, true) { lng = it }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Txt("Abbrechen", 16, color = p.sub, modifier = Modifier.clickable { onClose() }.padding(10.dp))
                Txt("Speichern", 16, FontWeight.SemiBold, p.accentText, modifier = Modifier.clickable {
                    val la = lat.replace(',', '.').toDoubleOrNull()
                    val lo = lng.replace(',', '.').toDoubleOrNull()
                    if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                        Prefs.lat = la
                        Prefs.lng = lo
                        Prefs.place = name.ifBlank { "Eigener Ort" }
                        Prefs.autoLoc = false
                        Scheduler.reschedule(ctx)
                        onClose()
                    }
                }.padding(10.dp))
            }
        }
    }
}
