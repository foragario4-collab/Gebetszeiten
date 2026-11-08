package com.gebetszeiten.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Notifs.channels(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Scheduler.schedule(this)
        Widgets.updateAll(this)
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        Scheduler.schedule(this)
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalPal provides pal) {
        Column(Modifier.fillMaxSize().background(pal.bg)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                when (tab) {
                    0 -> TodayScreen(v)
                    1 -> QiblaScreen(v)
                    else -> SettingsScreen(v)
                }
            }
            TabBar(tab) { tab = it }
        }
    }
}

@Composable
private fun TabBar(tab: Int, onTab: (Int) -> Unit) {
    val p = LocalPal.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (p.dark) Color(0xF01C1C1E) else Color(0xF5F9F9F9))
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.line))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Heute", "Qibla", "Einstellungen").forEachIndexed { i, label ->
                val c = if (i == tab) p.accentText else p.sub
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onTab(i) }
                        .padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Canvas(Modifier.size(24.dp)) {
                        val s = size.minDimension
                        val st = Stroke(width = s * 0.085f, cap = StrokeCap.Round)
                        when (i) {
                            0 -> {
                                drawCircle(c, s * 0.42f, center, style = st)
                                drawLine(c, center, Offset(center.x, s * 0.25f), s * 0.085f, StrokeCap.Round)
                                drawLine(c, center, Offset(s * 0.68f, s * 0.62f), s * 0.085f, StrokeCap.Round)
                            }
                            1 -> {
                                drawCircle(c, s * 0.42f, center, style = st)
                                drawLine(c, Offset(s * 0.62f, s * 0.38f), Offset(s * 0.38f, s * 0.62f), s * 0.13f, StrokeCap.Round)
                                drawCircle(c, s * 0.07f, Offset(s * 0.65f, s * 0.35f))
                            }
                            else -> {
                                drawCircle(c, s * 0.16f, center, style = st)
                                for (k in 0 until 8) {
                                    val a = Math.toRadians(k * 45.0)
                                    val r1 = s * 0.3f
                                    val r2 = s * 0.44f
                                    drawLine(
                                        c,
                                        Offset(center.x + (r1 * Math.cos(a)).toFloat(), center.y + (r1 * Math.sin(a)).toFloat()),
                                        Offset(center.x + (r2 * Math.cos(a)).toFloat(), center.y + (r2 * Math.sin(a)).toFloat()),
                                        s * 0.12f, StrokeCap.Round
                                    )
                                }
                            }
                        }
                    }
                    Txt(label, 10, if (i == tab) FontWeight.SemiBold else FontWeight.Medium, c)
                }
            }
        }
    }
}
