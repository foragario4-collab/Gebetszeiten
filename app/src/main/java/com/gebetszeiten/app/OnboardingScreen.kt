package com.gebetszeiten.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val STEPS = 6

/** Einrichtung beim ersten Start: Sprache → Standort → Berechnung → Mitteilungen → fertig. */
@Composable
fun OnboardingScreen(v: Int) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var gpsState by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = step > 0) { step-- }

    fun useGps() {
        gpsState = t("locating")
        Loc.fetch(ctx) { loc ->
            if (loc == null) {
                gpsState = t("no_loc")
            } else {
                Prefs.lat = loc.latitude
                Prefs.lng = loc.longitude
                Prefs.place = t("my_location")
                Prefs.autoLoc = true
                gpsState = t("ob_found", Prefs.place)
                Loc.placeName(ctx, loc.latitude, loc.longitude) { n ->
                    if (n != null) {
                        Prefs.place = n
                        gpsState = t("ob_found", n)
                    }
                }
            }
        }
    }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            useGps()
        } else {
            gpsState = t("no_perm")
        }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step = 5 }

    fun finish() {
        Prefs.setupDone = true
        Notifs.channels(ctx)
        Scheduler.reschedule(ctx)
    }

    fun next() {
        when (step) {
            4 -> {
                if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                else step = 5
            }
            5 -> finish()
            else -> step++
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp)
    ) {
        // Fortschritt
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(STEPS) { i ->
                val w by animateDpAsState(if (i == step) 22.dp else 6.dp, label = "dot")
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(w, 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i <= step) p.accent else p.line)
                )
            }
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "step"
        ) { s ->
            when (s) {
                0 -> Centered {
                    Box(
                        Modifier.size(96.dp).shadow(14.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B6E5F)),
                        contentAlignment = Alignment.Center
                    ) { Moon() }
                    Spacer(Modifier.height(18.dp))
                    Txt(t("ob_welcome"), 32, FontWeight.Bold, align = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Txt(t("ob_welcome_sub"), 16, color = p.sub, align = TextAlign.Center)
                }
                1 -> Page(t("sec_language"), t("ob_lang_sub")) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)) {
                        Lang.entries.forEachIndexed { i, l ->
                            CheckRow(l.native, Prefs.lang == l, first = i == 0) {
                                Prefs.lang = l
                                Notifs.channels(ctx)
                            }
                        }
                    }
                }
                2 -> Page(t("sec_location"), t("ob_loc_sub")) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)) {
                        RowBase(true, {
                            if (hasLocPermission(ctx)) useGps()
                            else locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color(0x1F007AFF)),
                                contentAlignment = Alignment.Center
                            ) { Txt("➤", 18, color = Color(0xFF007AFF)) }
                            LabelCol(t("ob_use_gps"), gpsState ?: t("ob_gps_sub"))
                            GreenCheck(Prefs.autoLoc)
                        }
                    }
                    Txt(t("ob_or_city"), 12, color = p.sub, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)) {
                        Loc.cities.forEachIndexed { i, c ->
                            CheckRow(c.name, !Prefs.autoLoc && Prefs.place == c.name, first = i == 0) {
                                Prefs.lat = c.lat
                                Prefs.lng = c.lng
                                Prefs.place = c.name
                                Prefs.autoLoc = false
                                gpsState = null
                            }
                        }
                    }
                }
                3 -> Page(t("sec_calc"), t("ob_calc_sub")) {
                    val order = listOf("isna", "mwl", "diyanet", "egypt", "makkah", "karachi", "gulf", "france")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)) {
                        order.forEachIndexed { i, id ->
                            val m = PrayerCalc.method(id)
                            CheckRow(m.label, Prefs.method == id, first = i == 0) { Prefs.method = id }
                        }
                    }
                    Txt(Kind.ASR.label.uppercase(), 12, color = p.sub, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)) {
                        CheckRow(t("asr_std"), Prefs.asr == 1, first = true, sub = t("asr_std_sub")) { Prefs.asr = 1 }
                        CheckRow(t("asr_hanafi"), Prefs.asr == 2, sub = t("asr_han_sub")) { Prefs.asr = 2 }
                    }
                }
                4 -> Centered {
                    Box(Modifier.size(84.dp).clip(CircleShape).background(Color(0x1FFF3B30)), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.BELL, Color(0xFFFF3B30), 40.dp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Txt(t("sec_notif"), 30, FontWeight.Bold, align = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Txt(t("ob_notif_sub"), 15, color = p.sub, align = TextAlign.Center)
                }
                else -> Centered {
                    Box(Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(44.dp)) {
                            val w = size.width
                            val path = Path().apply {
                                moveTo(w * 0.17f, w * 0.52f)
                                lineTo(w * 0.38f, w * 0.73f)
                                lineTo(w * 0.83f, w * 0.27f)
                            }
                            drawPath(
                                path, Color.White,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = w * 0.12f,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Txt(t("ob_done"), 30, FontWeight.Bold, align = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Txt(t("ob_done_sub"), 15, color = p.sub, align = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Txt(t("ob_more"), 14, color = p.sub, align = TextAlign.Center)
                }
            }
        }

        // Knöpfe
        val label = when (step) {
            0 -> t("ob_start")
            4 -> t("ob_allow")
            5 -> t("ob_open")
            else -> t("next")
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.accent)
                .tap { next() },
            contentAlignment = Alignment.Center
        ) { Txt(label, 17, FontWeight.SemiBold, Color.White) }
        Box(
            Modifier.fillMaxWidth().height(48.dp).padding(bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (step in 1..4) {
                Txt(t("back"), 16, color = Color(0xFF007AFF), modifier = Modifier.tap { step-- }.padding(10.dp))
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) { content() }
}

@Composable
private fun Page(title: String, sub: String, content: @Composable () -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Txt(title, 30, FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Txt(sub, 15, color = p.sub)
        Spacer(Modifier.height(16.dp))
        content()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Moon() {
    Canvas(Modifier.size(56.dp)) {
        val s = size.width
        val path = Path().apply {
            moveTo(s * 0.6f, s * 0.12f)
            cubicTo(s * 0.15f, s * 0.12f, s * 0.05f, s * 0.65f, s * 0.32f, s * 0.84f)
            cubicTo(s * 0.55f, s * 1.0f, s * 0.86f, s * 0.86f, s * 0.92f, s * 0.6f)
            cubicTo(s * 0.62f, s * 0.72f, s * 0.36f, s * 0.42f, s * 0.6f, s * 0.12f)
            close()
        }
        drawPath(path, Color.White)
        drawCircle(Color(0xFFF5D98B), s * 0.07f, androidx.compose.ui.geometry.Offset(s * 0.72f, s * 0.32f))
    }
}
