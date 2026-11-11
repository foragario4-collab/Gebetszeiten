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

private fun modeLabels() = listOf(t("mode_off"), t("mode_tone_long"), t("mode_adhan"))

private fun displayName(ctx: Context, uri: Uri): String = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Audio"
} catch (e: Exception) {
    "Audio"
}

private fun keep(ctx: Context, uri: Uri) {
    try {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: Exception) {
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

@Composable
fun SettingsScreen(v: Int, onBack: () -> Unit = {}) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var choice by remember { mutableStateOf<Choice?>(null) }
    var coords by remember { mutableStateOf(false) }
    var locStatus by remember { mutableStateOf<String?>(null) }

    fun changed() = Scheduler.reschedule(ctx)

    fun useGps() {
        locStatus = t("locating")
        Loc.fetch(ctx) { loc ->
            if (loc == null) {
                locStatus = t("no_loc")
            } else {
                Prefs.lat = loc.latitude
                Prefs.lng = loc.longitude
                Prefs.place = t("my_location")
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
            locStatus = t("no_perm")
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
        Row(
            Modifier.padding(bottom = 4.dp).tap(onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Txt(if (Prefs.lang.rtl) "›" else "‹", 26, FontWeight.Medium, Color(0xFF007AFF))
            Txt(" " + t("tab_today"), 17, color = Color(0xFF007AFF))
        }
        LargeTitle(t("tab_settings"))

        // ---------- Sprache ----------
        Section(t("sec_language")) {
            ValueRow(t("sec_language"), Prefs.lang.native, first = true) {
                choice = Choice(t("sec_language"), Lang.entries.map { it.native }, Prefs.lang.ordinal) { i ->
                    Prefs.lang = Lang.entries[i]
                    Notifs.channels(ctx)
                    Widgets.updateAll(ctx)
                }
            }
        }

        // ---------- Standort ----------
        Section(t("sec_location"), footer = locStatus ?: t("loc_footer")) {
            ValueRow(t("place"), Prefs.place, first = true, sub = "%.4f, %.4f".format(Prefs.lat, Prefs.lng)) {
                val names = Loc.cities.map { it.name } + t("enter_coords")
                choice = Choice(t("choose_place"), names, Loc.cities.indexOfFirst { it.name == Prefs.place }) { i ->
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
            ToggleRow(t("auto_gps"), Prefs.autoLoc, sub = t("auto_gps_sub")) { on ->
                if (on) {
                    gps()
                } else {
                    Prefs.autoLoc = false
                }
            }
            ButtonRow(t("locate_now")) { gps() }
        }

        // ---------- Asr ----------
        Section(Kind.ASR.label) {
            CheckRow(t("asr_std"), Prefs.asr == 1, first = true, sub = t("asr_std_sub")) {
                Prefs.asr = 1
                changed()
            }
            CheckRow(t("asr_hanafi"), Prefs.asr == 2, sub = t("asr_han_sub")) {
                Prefs.asr = 2
                changed()
            }
        }

        // ---------- Berechnung ----------
        val m = PrayerCalc.method(Prefs.method)
        val fa = if (m.id == "custom") Prefs.customFajr.toDouble() else m.fajr
        val ia = if (m.id == "custom") Prefs.customIsha.toDouble() else m.isha
        val ishaText = if (m.ishaMinutes > 0) t("isha_min", m.ishaMinutes) else t("isha_deg", ia)
        Section(t("sec_calc"), footer = t("calc_footer", fa, ishaText)) {
            PrayerCalc.methods.forEachIndexed { i, me ->
                CheckRow(me.label, me.id == m.id, first = i == 0) {
                    Prefs.method = me.id
                    changed()
                }
            }
            if (m.id == "custom") {
                StepperRow(t("fajr_angle"), "%.1f°".format(Prefs.customFajr),
                    onMinus = { Prefs.customFajr = (Prefs.customFajr - 0.5f).coerceAtLeast(10f); changed() },
                    onPlus = { Prefs.customFajr = (Prefs.customFajr + 0.5f).coerceAtMost(22f); changed() })
                StepperRow(t("isha_angle"), "%.1f°".format(Prefs.customIsha),
                    onMinus = { Prefs.customIsha = (Prefs.customIsha - 0.5f).coerceAtLeast(10f); changed() },
                    onPlus = { Prefs.customIsha = (Prefs.customIsha + 0.5f).coerceAtMost(22f); changed() })
            }
            val hl = listOf("angle" to t("hl_angle"), "middle" to t("hl_middle"), "seventh" to t("hl_seventh"))
            ValueRow(t("high_lat"), hl.firstOrNull { it.first == Prefs.highLat }?.second ?: hl[0].second) {
                choice = Choice(t("high_lat"), hl.map { it.second }, hl.indexOfFirst { it.first == Prefs.highLat }) { i ->
                    Prefs.highLat = hl[i].first
                    changed()
                }
            }
        }

        // ---------- Nacht ----------
        Section(t("sec_night"), footer = t("night_footer")) {
            val mm = listOf("fajr" to t("mm_fajr"), "sunrise" to t("mm_sunrise"))
            ValueRow(Kind.MIDNIGHT.label, mm.firstOrNull { it.first == Prefs.midnightMode }?.second ?: mm[0].second, first = true) {
                choice = Choice(t("midnight_calc"), mm.map { it.second }, mm.indexOfFirst { it.first == Prefs.midnightMode }) { i ->
                    Prefs.midnightMode = mm[i].first
                    changed()
                }
            }
            val ml = modeLabels()
            ValueRow(Kind.LAST_THIRD.label, ml[Prefs.mode(Kind.LAST_THIRD).coerceIn(0, 1)], sub = t("third_sub")) {
                choice = Choice(Kind.LAST_THIRD.label, ml.take(2), Prefs.mode(Kind.LAST_THIRD)) { i ->
                    Prefs.setMode(Kind.LAST_THIRD, i)
                    changed()
                }
            }
        }

        // ---------- Mitteilungen ----------
        Section(t("sec_notif")) {
            listOf(Kind.FAJR, Kind.SUNRISE, Kind.DHUHR, Kind.ASR, Kind.MAGHRIB, Kind.ISHA).forEachIndexed { idx, k ->
                val opts = if (k.prayer) modeLabels() else modeLabels().take(2)
                ValueRow(k.label, opts[Prefs.mode(k).coerceIn(0, opts.size - 1)], first = idx == 0) {
                    choice = Choice(k.label, opts, Prefs.mode(k)) { i ->
                        Prefs.setMode(k, i)
                        changed()
                    }
                }
            }
            val rem = listOf(0, 5, 10, 15, 20, 30, 45, 60)
            ValueRow(t("reminder_before"), if (Prefs.reminder == 0) t("mode_off") else t("min_short", Prefs.reminder)) {
                choice = Choice(t("reminder_title"), rem.map { if (it == 0) t("mode_off") else t("minutes", it) }, rem.indexOf(Prefs.reminder)) { i ->
                    Prefs.reminder = rem[i]
                    changed()
                }
            }
        }

        // ---------- Live ----------
        Section(t("sec_live"), footer = t("live_footer")) {
            ToggleRow(t("sec_live"), Prefs.live, first = true) { Prefs.live = it; changed() }
            ToggleRow(t("live_always"), Prefs.liveAlways, sub = t("live_always_sub")) { Prefs.liveAlways = it; changed() }
            val cm = listOf(t("chip_remaining"), t("chip_clock"))
            cm.forEachIndexed { i, label ->
                CheckRow(label, Prefs.chipMode == i, sub = if (i == 0) t("chip_mode") else null) {
                    Prefs.chipMode = i
                    changed()
                }
            }
            val lm = listOf(15, 30, 45, 60, 90, 120)
            if (!Prefs.liveAlways) ValueRow(t("live_start"), t("min_short", Prefs.liveMin)) {
                choice = Choice(t("live_start"), lm.map { t("minutes", it) }, lm.indexOf(Prefs.liveMin)) { i ->
                    Prefs.liveMin = lm[i]
                    changed()
                }
            }
        }

        // ---------- Töne ----------
        var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player?.release() } }
        Section(t("sec_tone"), footer = t("tone_footer")) {
            Notifs.TONES.forEachIndexed { i, id ->
                CheckRow(t("tone_$id"), Prefs.tone == id, first = i == 0) {
                    player?.release()
                    player = try {
                        android.media.MediaPlayer.create(ctx, Notifs.toneUri(ctx, id))?.apply { start() }
                    } catch (e: Exception) {
                        null
                    }
                    if (Prefs.tone != id) {
                        Prefs.tone = id
                        Notifs.channels(ctx)
                        changed()
                    }
                }
            }
        }

        // ---------- Adhan ----------
        Section(t("sec_adhan"), footer = t("adhan_footer")) {
            ValueRow(t("adhan_file"), Prefs.adhanName ?: t("none_chosen"), first = true) { pickAdhan.launch(arrayOf("audio/*")) }
            ValueRow(t("fajr_adhan"), Prefs.fajrAdhanName ?: t("same_as_above")) { pickFajr.launch(arrayOf("audio/*")) }
            if (Prefs.fajrAdhanUri != null) {
                ButtonRow(t("remove_fajr_adhan")) {
                    Prefs.fajrAdhanUri = null
                    Prefs.fajrAdhanName = null
                }
            }
            ButtonRow(t("adhan_preview")) {
                val u = Prefs.adhanUri
                if (u != null) AdhanService.start(ctx, u, null)
            }
            ButtonRow(t("adhan_stop"), color = Color(0xFFFF3B30)) { AdhanService.stop(ctx) }
        }

        // ---------- Zeitkorrektur ----------
        Section(t("sec_offsets"), footer = t("offsets_footer")) {
            listOf(Kind.FAJR, Kind.SUNRISE, Kind.DHUHR, Kind.ASR, Kind.MAGHRIB, Kind.ISHA).forEachIndexed { idx, k ->
                val o = Prefs.offset(k)
                StepperRow(k.label, t("min_short", signed(o)), first = idx == 0,
                    onMinus = { Prefs.setOffset(k, (o - 1).coerceAtLeast(-60)); changed() },
                    onPlus = { Prefs.setOffset(k, (o + 1).coerceAtMost(60)); changed() })
            }
        }

        // ---------- Darstellung ----------
        Section(t("sec_display")) {
            SegRow(t("design"), listOf(t("d_auto"), t("d_light"), t("d_dark")), Prefs.theme, first = true) {
                Prefs.theme = it
                Widgets.updateAll(ctx)
            }
            StepperRow(t("hijri_date"), t("days", signed(Prefs.hijriAdj)),
                onMinus = { Prefs.hijriAdj = (Prefs.hijriAdj - 1).coerceAtLeast(-2); Widgets.updateAll(ctx) },
                onPlus = { Prefs.hijriAdj = (Prefs.hijriAdj + 1).coerceAtMost(2); Widgets.updateAll(ctx) })
            ToggleRow(t("h24"), Prefs.use24) { Prefs.use24 = it; Widgets.updateAll(ctx) }
        }

        // ---------- Kalender ----------
        Section(t("sec_calendar"), footer = t("cal_footer")) {
            val dm = listOf(t("cal_hijri"), t("cal_greg"), t("cal_both"))
            dm.forEachIndexed { i, label ->
                CheckRow(label, Prefs.dateMode == i, first = i == 0) {
                    Prefs.dateMode = i
                    Widgets.updateAll(ctx)
                }
            }
        }

        // ---------- Moschee-Radar ----------
        Section(t("sec_radar"), footer = t("radar_footer")) {
            val rr = listOf(500, 1000, 1500, 2000, 3000, 5000)
            ValueRow(t("radar_radius"), Mosques.fmtDist(Prefs.radarRadius.toFloat()), first = true) {
                choice = Choice(t("radar_radius"), rr.map { Mosques.fmtDist(it.toFloat()) }, rr.indexOf(Prefs.radarRadius)) { i ->
                    Prefs.radarRadius = rr[i]
                }
            }
        }

        // ---------- Widget ----------
        Section(t("sec_widget"), footer = t("widget_footer")) {
            ToggleRow(Kind.SUNRISE.label, Prefs.wSunrise, first = true) { Prefs.wSunrise = it; Widgets.updateAll(ctx) }
            ToggleRow(Kind.MIDNIGHT.label, Prefs.wMidnight) { Prefs.wMidnight = it; Widgets.updateAll(ctx) }
            ToggleRow(Kind.LAST_THIRD.label, Prefs.wThird) { Prefs.wThird = it; Widgets.updateAll(ctx) }
        }

        // ---------- Ausgeblendete Orte ----------
        val hidden = Prefs.hiddenIds
        if (hidden.isNotEmpty()) {
            val all = Mosques.listAll(ctx).filter { it.id in hidden }
            Section(t("hidden_places"), footer = t("hidden_footer")) {
                all.forEachIndexed { i, m ->
                    RowBase(i == 0) {
                        LabelCol(m.name, m.address.ifBlank { null })
                        Txt(t("unhide"), 15, FontWeight.SemiBold, Color(0xFF007AFF),
                            modifier = Modifier.tap { Prefs.hiddenIds = Prefs.hiddenIds - m.id }.padding(6.dp))
                    }
                }
                ButtonRow(t("unhide_all")) { Prefs.hiddenIds = emptySet() }
            }
        }

        // ---------- Diagnose ----------
        Section(t("sec_diag"), footer = t("diag_footer")) {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val net = try {
                cm?.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } catch (e: Exception) {
                false
            }
            val exact = android.os.Build.VERSION.SDK_INT < 31 || (am?.canScheduleExactAlarms() == true)
            var promotedOk = true
            val promoted: String = if (android.os.Build.VERSION.SDK_INT >= 36) {
                try {
                    val r = android.app.NotificationManager::class.java.getMethod("canPostPromotedNotifications").invoke(nm)
                    promotedOk = r == true
                    if (r == true) t("allowed") else t("off")
                } catch (e: Throwable) {
                    t("unknown")
                }
            } else t("from16")
            val version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
            val now = System.currentTimeMillis()
            val nextAlarm = Scheduler.actions(now).firstOrNull { it.at > now + 1000 }
            Mosques.list(ctx)
            fun ok(b: Boolean) = if (b) t("ok") else t("missing")
            val notifOk = nm?.areNotificationsEnabled() == true
            DiagRow(t("app_version"), version, true, first = true)
            DiagRow(t("sec_location"), ok(hasLocPermission(ctx)), hasLocPermission(ctx))
            DiagRow(t("sec_notif"), ok(notifOk), notifOk)
            DiagRow(t("d_exact"), ok(exact), exact)
            DiagRow(t("d_live"), promoted, promotedOk)
            DiagRow(t("d_internet"), if (net) t("online") else t("offline"), net)
            DiagRow(t("d_osm"), "${Mosques.countOsm()}", Mosques.countOsm() > 0)
            DiagRow("Mawaqit", "${Mosques.countMawaqit()}", Mosques.countMawaqit() > 0)
            ToggleRow(t("use_lk"), Prefs.useLandkarte, sub = t("use_lk_sub")) { Prefs.useLandkarte = it; changed() }
            DiagRow(t("d_own"), "${Mosques.countOwn()}", true)
            DiagRow(t("d_next"), nextAlarm?.let { "${Fmt.time(it.at)} · ${it.ev?.title ?: t("sec_widget")}" } ?: t("none"), nextAlarm != null)
            ButtonRow(t("reload_mosques")) {
                Mosques.refresh(ctx, Prefs.lat, Prefs.lng) { okLoad ->
                    locStatus = if (okLoad) t("reloaded", Mosques.countOsm())
                    else if (Mosques.lastError == "offline") t("err_offline") else t("err_busy")
                }
                Mosques.refreshMawaqit(ctx, Prefs.lat, Prefs.lng) { }
            }
        }

        // ---------- Testen ----------
        Section(t("sec_test"), footer = t("test_footer")) {
            val now = System.currentTimeMillis()
            val next = Times.around(now).first { it.at > now && it.kind.prayer }
            ButtonRow(t("t_prayer"), first = true) {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                nm?.notify(Notifs.prayerId(next.kind), Notifs.buildPrayer(ctx, next.copy(at = System.currentTimeMillis()), Notifs.prayerChannel()))
            }
            ButtonRow(t("t_remind")) {
                Notifs.reminder(ctx, next.copy(at = System.currentTimeMillis() + 15 * 60_000L))
            }
            ButtonRow(t("t_live")) {
                val tm = System.currentTimeMillis()
                Notifs.live(ctx, next.copy(at = tm + 2 * 60_000L), prevAt = tm)
            }
            ButtonRow(t("t_adhan")) {
                val u = Prefs.adhanUri
                if (u != null) AdhanService.start(ctx, u, next.copy(at = System.currentTimeMillis()))
                else Notifs.prayerAlert(ctx, next.copy(at = System.currentTimeMillis()))
            }
            ButtonRow(t("open_notif_settings")) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            ButtonRow(t("rerun_setup")) { Prefs.setupDone = false }
        }
    }

    choice?.let { c -> ChoiceDialog(c) { choice = null } }
    if (coords) CoordDialog { coords = false }
}

@Composable
private fun DiagRow(label: String, value: String, good: Boolean, first: Boolean = false) {
    RowBase(first) {
        LabelCol(label)
        Txt(value, 15, FontWeight.SemiBold, if (good) Color(0xFF34C759) else Color(0xFFFF3B30), maxLines = 1)
    }
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
    fun CField(label: String, value: String, number: Boolean, onChange: (String) -> Unit) {
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
            Txt(t("coords_title"), 17, FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            CField(t("name"), name, false) { name = it }
            CField(t("lat_hint"), lat, true) { lat = it }
            CField(t("lng_hint"), lng, true) { lng = it }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Txt(t("cancel"), 16, color = p.sub, modifier = Modifier.tap { onClose() }.padding(10.dp))
                Txt(t("save"), 16, FontWeight.SemiBold, p.accentText, modifier = Modifier.tap {
                    val la = lat.replace(',', '.').toDoubleOrNull()
                    val lo = lng.replace(',', '.').toDoubleOrNull()
                    if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                        Prefs.lat = la
                        Prefs.lng = lo
                        Prefs.place = name.ifBlank { t("own_place") }
                        Prefs.autoLoc = false
                        Scheduler.reschedule(ctx)
                        onClose()
                    }
                }.padding(10.dp))
            }
        }
    }
}
