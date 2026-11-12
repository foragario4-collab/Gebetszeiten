package com.gebetszeiten.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        NavRequest.tab = intent?.getIntExtra("openTab", -1) ?: -1
        Notifs.channels(this)
        enableEdgeToEdge()
        if (Prefs.setupDone && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Scheduler.schedule(this)
        Widgets.updateAll(this)
        Mosques.importSeeds(this) { }
        setContent { App() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NavRequest.tab = intent.getIntExtra("openTab", -1)
    }

    override fun onResume() {
        super.onResume()
        Scheduler.showCurrentLive(this)
        Scheduler.schedule(this)
        // „Automatisch (GPS)“: Ort beim Öffnen aktualisieren
        if (hasLocPermission(this)) Loc.autoUpdate(this)
        Widgets.updateAll(this)
    }
}

@Composable
fun App() {
    val v = Prefs.version.intValue
    val sysDark = isSystemInDarkTheme()
    val dark = when (Prefs.theme) {
        1 -> false
        2 -> true
        else -> sysDark
    }
    val pal = if (dark) DarkPal else LightPal
    val dir = if (Prefs.lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Sprung aus einer Mitteilung (z. B. „Andere Moschee“ → Karte)
    androidx.compose.runtime.LaunchedEffect(NavRequest.tab) {
        if (NavRequest.tab in 0..2) {
            tab = NavRequest.tab
            NavRequest.tab = -1
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    CompositionLocalProvider(LocalPal provides pal, LocalLayoutDirection provides dir) {
        if (!Prefs.setupDone) {
            OnboardingScreen(v)
            return@CompositionLocalProvider
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(pal.bg)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    when (tab) {
                        0 -> TodayScreen(v, onSettings = { showSettings = true }, onOpenMap = { tab = 2 })
                        1 -> QiblaScreen(v)
                        else -> MapScreen(v)
                    }
                }
                TabBar(tab) { tab = it }
            }
            // Einstellungen als eigene Seite über allem (Knopf oben rechts in „Heute“)
            androidx.compose.animation.AnimatedVisibility(
                visible = showSettings,
                enter = androidx.compose.animation.slideInHorizontally { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutHorizontally { it } + androidx.compose.animation.fadeOut()
            ) {
                androidx.activity.compose.BackHandler { showSettings = false }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(pal.bg)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    SettingsScreen(v) { showSettings = false }
                }
        }
        }
    }
}

@Composable
private fun TabBar(tab: Int, onTab: (Int) -> Unit) {
    val p = LocalPal.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (p.dark) Color(0xF21C1C1E) else Color(0xF5F9F9F9))
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.line))
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf(t("tab_today"), t("tab_qibla"), t("tab_map")).forEachIndexed { i, label ->
                val active = i == tab
                val c by animateColorAsState(if (active) p.accentText else p.sub, label = "tabColor")
                val pill by animateColorAsState(if (active) p.accentText.copy(alpha = 0.13f) else Color.Transparent, label = "tabPill")
                Column(
                    Modifier
                        .weight(1f)
                        .tap(scale = 0.9f) { onTab(i) }
                        .padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(56.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(pill),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(24.dp)) {
                            val s = size.minDimension
                            val sw = s * 0.075f
                            val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            when (i) {
                                0 -> {
                                    // Mond (Heute)
                                    val path = Path().apply {
                                        moveTo(s * 0.62f, s * 0.12f)
                                        cubicTo(s * 0.18f, s * 0.12f, s * 0.08f, s * 0.62f, s * 0.32f, s * 0.82f)
                                        cubicTo(s * 0.55f, s * 1.0f, s * 0.86f, s * 0.86f, s * 0.9f, s * 0.6f)
                                        cubicTo(s * 0.62f, s * 0.72f, s * 0.38f, s * 0.42f, s * 0.62f, s * 0.12f)
                                        close()
                                    }
                                    if (active) drawPath(path, c, style = Fill) else drawPath(path, c, style = st)
                                }
                                1 -> {
                                    // Kompass (Qibla)
                                    drawCircle(c, s * 0.42f, center, style = st)
                                    val needle = Path().apply {
                                        moveTo(s * 0.66f, s * 0.34f)
                                        lineTo(s * 0.56f, s * 0.56f)
                                        lineTo(s * 0.34f, s * 0.66f)
                                        lineTo(s * 0.44f, s * 0.44f)
                                        close()
                                    }
                                    drawPath(needle, c, style = if (active) Fill else st)
                                }
                                else -> {
                                    // Faltkarte (Karte)
                                    val map = Path().apply {
                                        moveTo(s * 0.1f, s * 0.24f)
                                        lineTo(s * 0.37f, s * 0.14f)
                                        lineTo(s * 0.63f, s * 0.24f)
                                        lineTo(s * 0.9f, s * 0.14f)
                                        lineTo(s * 0.9f, s * 0.76f)
                                        lineTo(s * 0.63f, s * 0.86f)
                                        lineTo(s * 0.37f, s * 0.76f)
                                        lineTo(s * 0.1f, s * 0.86f)
                                        close()
                                    }
                                    if (active) drawPath(map, c.copy(alpha = 0.25f), style = Fill)
                                    drawPath(map, c, style = st)
                                    drawLine(c, Offset(s * 0.37f, s * 0.14f), Offset(s * 0.37f, s * 0.76f), sw)
                                    drawLine(c, Offset(s * 0.63f, s * 0.24f), Offset(s * 0.63f, s * 0.86f), sw)
                                }
                            }
                        }
                    }
                    Txt(label, 10, if (active) FontWeight.SemiBold else FontWeight.Medium, c, maxLines = 1)
                }
            }
        }
    }
}


/** Navigationswunsch von außen (Mitteilung), beobachtbar für die Oberfläche. */
object NavRequest {
    var tab by androidx.compose.runtime.mutableIntStateOf(-1)
}
