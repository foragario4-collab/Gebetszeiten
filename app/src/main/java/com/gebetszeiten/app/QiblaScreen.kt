package com.gebetszeiten.app

import android.graphics.Paint
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val GREEN = Color(0xFF34C759)
private val ORANGE = Color(0xFFFF9F0A)

/** Qibla-Tab: runder Kompass im Apple-Stil oder eckige Leiste mit Karte. */
@Composable
fun QiblaScreen(v: Int) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val lat = Prefs.lat
    val lng = Prefs.lng
    val bearing = Qibla.bearing(lat, lng)
    val dist = Qibla.distanceKm(lat, lng)
    var az by remember { mutableFloatStateOf(0f) }
    var hasSensor by remember { mutableStateOf(true) }
    var accuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_HIGH) }

    DisposableEffect(lat, lng) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val rot = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val mag = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sm == null || rot == null) {
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
                    if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                        accuracy = e.accuracy
                        return
                    }
                    SensorManager.getRotationMatrixFromVector(rm, e.values)
                    SensorManager.getOrientation(rm, o)
                    val a = Math.toRadians(Math.toDegrees(o[0].toDouble()) + decl)
                    sx = sx * 0.85 + cos(a) * 0.15
                    sy = sy * 0.85 + sin(a) * 0.15
                    az = ((Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0).toFloat()
                }

                override fun onAccuracyChanged(s: Sensor?, acc: Int) {
                    if (s?.type == Sensor.TYPE_MAGNETIC_FIELD) accuracy = acc
                }
            }
            sm.registerListener(l, rot, SensorManager.SENSOR_DELAY_UI)
            if (mag != null) sm.registerListener(l, mag, SensorManager.SENSOR_DELAY_NORMAL)
            onDispose { sm.unregisterListener(l) }
        }
    }

    val diff = ((bearing - az + 540.0) % 360.0) - 180.0
    val aligned = hasSensor && abs(diff) < 4.0
    LaunchedEffect(aligned) {
        if (aligned) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
    val lowAcc = hasSensor && accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    val style = Prefs.compassStyle
    val bgColor = if (p.dark) Color.Black else p.bg

    Column(Modifier.fillMaxSize().background(bgColor).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(t("tab_qibla"), 34, FontWeight.Bold)
                Txt(t("mecca_km", "%,d".format(dist.roundToInt()).replace(',', ' ')), 13, color = p.sub)
            }
            Segmented(listOf(t("style_round"), t("style_square")), style) { Prefs.compassStyle = it }
        }

        AnimatedVisibility(visible = lowAcc) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ORANGE.copy(alpha = 0.16f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Canvas(Modifier.size(40.dp, 28.dp)) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.5f, h * 0.5f)
                        cubicTo(w * 0.35f, h * 0.1f, w * 0.08f, h * 0.1f, w * 0.08f, h * 0.5f)
                        cubicTo(w * 0.08f, h * 0.9f, w * 0.35f, h * 0.9f, w * 0.5f, h * 0.5f)
                        cubicTo(w * 0.65f, h * 0.1f, w * 0.92f, h * 0.1f, w * 0.92f, h * 0.5f)
                        cubicTo(w * 0.92f, h * 0.9f, w * 0.65f, h * 0.9f, w * 0.5f, h * 0.5f)
                    }
                    drawPath(path, ORANGE, style = Stroke(width = h * 0.1f, cap = StrokeCap.Round))
                }
                Column(Modifier.weight(1f)) {
                    Txt(t("calib_title"), 14, FontWeight.Bold, ORANGE)
                    Txt(t("calib_text"), 13, color = p.text)
                }
            }
        }

        if (style == 0) {
            RoundCompass(az, bearing, diff, aligned, hasSensor, Modifier.weight(1f))
        } else {
            Spacer(Modifier.height(12.dp))
            BarCompass(az, bearing, diff, aligned, hasSensor)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
                MapScreen(v)
            }
        }
    }
}

private fun statusText(hasSensor: Boolean, aligned: Boolean, diff: Double): String = when {
    !hasSensor -> t("no_sensor")
    aligned -> t("aligned")
    diff > 0 -> t("turn_right", abs(diff).roundToInt())
    else -> t("turn_left", abs(diff).roundToInt())
}

@Composable
private fun RoundCompass(az: Float, bearing: Double, diff: Double, aligned: Boolean, hasSensor: Boolean, modifier: Modifier) {
    val p = LocalPal.current
    val density = LocalDensity.current
    val fg = p.text
    val sub = p.sub
    val letters = Qibla.letters()
    val numPx = with(density) { 12.dp.toPx() }
    val cardPx = with(density) { 22.dp.toPx() }
    val qColor by animateColorAsState(if (!hasSensor) sub else if (aligned) GREEN else ORANGE, label = "q")
    val topColor by animateColorAsState(if (aligned) GREEN else fg, label = "top")

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth(0.92f).aspectRatio(1f)) {
                val r = size.minDimension / 2f
                val c = center
                val ringR = r * 0.88f
                // Bogen von Blickrichtung bis Qibla
                if (hasSensor && !aligned) {
                    val arcR = r * 0.93f
                    drawArc(
                        ORANGE.copy(alpha = 0.85f), startAngle = -90f, sweepAngle = diff.toFloat(), useCenter = false,
                        topLeft = Offset(c.x - arcR, c.y - arcR), size = Size(arcR * 2, arcR * 2),
                        style = Stroke(width = r * 0.028f, cap = StrokeCap.Round)
                    )
                }
                val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = numPx
                    color = sub.toArgb()
                }
                val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = cardPx
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
                    // Qibla: ein langer Strich bis an den Rand
                    rotate(bearing.toFloat(), c) {
                        drawLine(qColor, Offset(c.x, c.y - r * 0.24f), Offset(c.x, c.y - ringR), strokeWidth = r * 0.024f, cap = StrokeCap.Round)
                    }
                }
                // Blickrichtung oben – trifft er den Qibla-Strich, ergibt das eine grüne Linie
                drawLine(topColor, Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.8f), strokeWidth = r * 0.024f, cap = StrokeCap.Round)
                for (k in 0 until 12) {
                    val a = Math.toRadians((k * 30 - az).toDouble())
                    val rr = r * 0.68f
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(
                            (k * 30).toString(),
                            c.x + (rr * sin(a)).toFloat(), c.y - (rr * cos(a)).toFloat() + numPx * 0.35f, numPaint
                        )
                    }
                }
                for (k in 0 until 4) {
                    val a = Math.toRadians((k * 90 - az).toDouble())
                    val rr = r * 0.5f
                    cardPaint.color = if (k == 0) 0xFFFF3B30.toInt() else fg.toArgb()
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(
                            letters[k],
                            c.x + (rr * sin(a)).toFloat(), c.y - (rr * cos(a)).toFloat() + cardPx * 0.36f, cardPaint
                        )
                    }
                }
                drawLine(sub, Offset(c.x - r * 0.12f, c.y), Offset(c.x + r * 0.12f, c.y), strokeWidth = r * 0.008f)
                drawLine(sub, Offset(c.x, c.y - r * 0.12f), Offset(c.x, c.y + r * 0.12f), strokeWidth = r * 0.008f)
            }
        }
        Txt(if (hasSensor) "${az.roundToInt() % 360}°" else "–", 64, FontWeight.Light, align = TextAlign.Center)
        Txt(Qibla.direction(az.toDouble()), 17, FontWeight.Medium, p.sub, align = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (aligned) GREEN.copy(alpha = 0.18f) else p.card)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Txt(statusText(hasSensor, aligned, diff), 15, FontWeight.SemiBold, if (aligned) GREEN else p.text) }
        Spacer(Modifier.height(4.dp))
        Txt("${t("tab_qibla")} ${bearing.roundToInt()}° · ${Qibla.direction(bearing)}", 13, color = p.sub, align = TextAlign.Center)
    }
}

/** Eckige Leiste wie früher: Gradband läuft unter der Nadel durch. */
@Composable
private fun BarCompass(az: Float, bearing: Double, diff: Double, aligned: Boolean, hasSensor: Boolean) {
    val p = LocalPal.current
    val density = LocalDensity.current
    val letters = Qibla.letters()
    val qColor by animateColorAsState(if (!hasSensor) p.sub else if (aligned) GREEN else ORANGE, label = "q")
    val topColor by animateColorAsState(if (aligned) GREEN else p.text, label = "top")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.card)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Txt(t("heading"), 12, FontWeight.SemiBold, p.sub)
                Txt(if (hasSensor) "${az.roundToInt() % 360}°" else "–", 40, FontWeight.Light)
            }
            Column(horizontalAlignment = Alignment.End) {
                Txt(t("qibla_up"), 12, FontWeight.SemiBold, p.sub)
                Txt("${bearing.roundToInt()}° ${Qibla.direction(bearing)}", 17, FontWeight.SemiBold)
            }
        }
        val tick = p.text
        val minor = p.line
        val label = p.sub
        val textPx = with(density) { 12.dp.toPx() }
        Canvas(Modifier.fillMaxWidth().height(68.dp)) {
            val w = size.width
            val hgt = size.height
            val ppd = w / 128f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = textPx
                isFakeBoldText = true
            }
            val base = hgt - textPx * 1.9f
            var d = (ceil((az - 64f) / 5f) * 5f).toInt()
            while (d <= az + 64f) {
                val n = ((d % 360) + 360) % 360
                val x = w / 2f + (d - az) * ppd
                val major = n % 30 == 0
                val len = if (major) textPx * 1.8f else textPx * 0.9f
                drawLine(
                    if (n == 0) Color(0xFFFF3B30) else if (major) tick else minor,
                    Offset(x, base - len), Offset(x, base),
                    strokeWidth = if (major) 4f else 3f, cap = StrokeCap.Round
                )
                if (major) {
                    val lb = when (n) { 0 -> letters[0]; 90 -> letters[1]; 180 -> letters[2]; 270 -> letters[3]; else -> n.toString() }
                    paint.color = if (n == 0) 0xFFFF3B30.toInt() else label.toArgb()
                    drawIntoCanvas { it.nativeCanvas.drawText(lb, x, hgt - textPx * 0.4f, paint) }
                }
                d += 5
            }
            val rel = diff.toFloat().coerceIn(-62f, 62f)
            drawLine(qColor, Offset(w / 2f + rel * ppd, 0f), Offset(w / 2f + rel * ppd, base + 2f), strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(topColor, Offset(w / 2f, 0f), Offset(w / 2f, base + 2f), strokeWidth = 8f, cap = StrokeCap.Round)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.line))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (aligned) GREEN else qColor))
            Txt(statusText(hasSensor, aligned, diff), 15, FontWeight.SemiBold, if (aligned) GREEN else p.text)
        }
    }
}
