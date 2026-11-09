package com.gebetszeiten.app

import android.graphics.Paint
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Qibla-Tab: großer runder Kompass im Apple-Stil. */
@Composable
fun QiblaScreen(v: Int) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val lat = Prefs.lat
    val lng = Prefs.lng
    val bearing = Qibla.bearing(lat, lng)
    val dist = Qibla.distanceKm(lat, lng)
    var az by remember { mutableFloatStateOf(0f) }
    var hasSensor by remember { mutableStateOf(true) }

    DisposableEffect(lat, lng) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) {
            hasSensor = false
            onDispose { }
        } else {
            val decl = GeomagneticField(lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()).declination
            val rm = FloatArray(9)
            val o = FloatArray(3)
            var sx = 1.0
            var sy = 0.0
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rm, e.values)
                    SensorManager.getOrientation(rm, o)
                    val a = Math.toRadians(Math.toDegrees(o[0].toDouble()) + decl)
                    sx = sx * 0.85 + cos(a) * 0.15
                    sy = sy * 0.85 + sin(a) * 0.15
                    az = ((Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0).toFloat()
                }

                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(l) }
        }
    }

    val diff = ((bearing - az + 540.0) % 360.0) - 180.0
    val aligned = hasSensor && abs(diff) < 4.0
    // Kurzes Antippen-Gefühl, sobald man ausgerichtet ist
    LaunchedEffect(aligned) {
        if (aligned) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
    val needle by animateColorAsState(
        if (!hasSensor) p.sub else if (aligned) Color(0xFF34C759) else Color(0xFFFF9500), label = "needle"
    )
    val letters = Qibla.letters()
    val textPx = with(density) { 15.dp.toPx() }

    val bgColor = if (p.dark) Color.Black else p.bg
    Column(
        Modifier.fillMaxSize().background(bgColor).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Txt(t("tab_qibla"), 34, FontWeight.Bold, modifier = Modifier.weight(1f))
            Txt(t("mecca_km", "%,d".format(dist.roundToInt()).replace(',', ' ')), 13, color = p.sub)
        }
        val fg = p.text
        val sub = p.sub
        val numPx = with(density) { 12.dp.toPx() }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth(0.92f).aspectRatio(1f)) {
                val r = size.minDimension / 2f
                val c = center
                val ringR = r * 0.88f
                // feste Blickrichtung oben
                drawLine(fg, Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.8f), strokeWidth = r * 0.022f, cap = StrokeCap.Round)
                // Bogen von Blickrichtung zur Qibla
                if (hasSensor && !aligned) {
                    val arcR = r * 0.93f
                    drawArc(
                        needle, startAngle = -90f, sweepAngle = diff.toFloat(), useCenter = false,
                        topLeft = Offset(c.x - arcR, c.y - arcR), size = Size(arcR * 2, arcR * 2),
                        style = Stroke(width = r * 0.03f, cap = StrokeCap.Round)
                    )
                }
                val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = numPx
                    color = sub.toArgb()
                }
                val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = textPx * 1.45f
                    isFakeBoldText = true
                }
                rotate(-az, c) {
                    for (i in 0 until 180) {
                        val deg = i * 2
                        val a = Math.toRadians(deg.toDouble())
                        val major = deg % 30 == 0
                        val mid = deg % 10 == 0
                        val len = if (major) r * 0.1f else if (mid) r * 0.075f else r * 0.045f
                        drawLine(
                            if (deg == 0) Color(0xFFFF3B30) else if (major || mid) fg else sub.copy(alpha = 0.7f),
                            Offset(c.x + ((ringR - len) * sin(a)).toFloat(), c.y - ((ringR - len) * cos(a)).toFloat()),
                            Offset(c.x + (ringR * sin(a)).toFloat(), c.y - (ringR * cos(a)).toFloat()),
                            strokeWidth = if (major) r * 0.014f else if (mid) r * 0.009f else r * 0.006f
                        )
                    }
                    // Qibla-Markierung: Strich + Punkt (ohne Kaaba)
                    rotate(bearing.toFloat(), c) {
                        drawLine(needle, Offset(c.x, c.y - r * 0.36f), Offset(c.x, c.y - ringR), strokeWidth = r * 0.016f, cap = StrokeCap.Round)
                        drawCircle(needle, r * 0.035f, Offset(c.x, c.y - r * 0.97f))
                    }
                }
                // Zahlen und Himmelsrichtungen aufrecht, aber mitgedreht positioniert
                for (k in 0 until 12) {
                    val deg = k * 30 - az
                    val a = Math.toRadians(deg.toDouble())
                    val rr = r * 0.68f
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(
                            (k * 30).toString(),
                            c.x + (rr * sin(a)).toFloat(), c.y - (rr * cos(a)).toFloat() + numPx * 0.35f, numPaint
                        )
                    }
                }
                for (k in 0 until 4) {
                    val deg = k * 90 - az
                    val a = Math.toRadians(deg.toDouble())
                    val rr = r * 0.5f
                    cardPaint.color = if (k == 0) 0xFFFF3B30.toInt() else fg.toArgb()
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(
                            letters[k],
                            c.x + (rr * sin(a)).toFloat(), c.y - (rr * cos(a)).toFloat() + cardPaint.textSize * 0.36f, cardPaint
                        )
                    }
                }
                // Fadenkreuz
                drawLine(sub, Offset(c.x - r * 0.12f, c.y), Offset(c.x + r * 0.12f, c.y), strokeWidth = r * 0.008f)
                drawLine(sub, Offset(c.x, c.y - r * 0.12f), Offset(c.x, c.y + r * 0.12f), strokeWidth = r * 0.008f)
            }
        }
        Txt(if (hasSensor) "${az.roundToInt() % 360}°" else "–", 64, FontWeight.Light, align = TextAlign.Center)
        Txt(Qibla.direction(az.toDouble()), 17, FontWeight.Medium, p.sub, align = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        val st = when {
            !hasSensor -> t("no_sensor")
            aligned -> t("aligned")
            diff > 0 -> t("turn_right", abs(diff).roundToInt())
            else -> t("turn_left", abs(diff).roundToInt())
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (aligned) Color(0x2E34C759) else p.card)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Txt(st, 15, FontWeight.SemiBold, if (aligned) Color(0xFF34C759) else p.text) }
        Spacer(Modifier.height(4.dp))
        Txt("${t("tab_qibla")} ${bearing.roundToInt()}° · ${Qibla.direction(bearing)}", 13, color = p.sub, align = TextAlign.Center)
    }
}
