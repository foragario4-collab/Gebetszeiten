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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Txt(t("tab_qibla"), 34, FontWeight.Bold, modifier = Modifier.weight(1f))
            Txt(t("mecca_km", "%,d".format(dist.roundToInt()).replace(',', ' ')), 13, color = p.sub)
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(p.card)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val ring = p.line
            val sub = p.sub
            val text = p.text
            Canvas(Modifier.fillMaxWidth(0.88f).aspectRatio(1f)) {
                val r = size.minDimension / 2f
                val c = center
                // fester Zeiger oben = Blickrichtung
                val tri = Path().apply {
                    moveTo(c.x, 0f)
                    lineTo(c.x - r * 0.06f, r * 0.11f)
                    lineTo(c.x + r * 0.06f, r * 0.11f)
                    close()
                }
                drawPath(tri, needle)
                drawCircle(ring, r * 0.86f, c, style = Stroke(r * 0.025f))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = textPx
                    isFakeBoldText = true
                }
                rotate(-az, c) {
                    for (i in 0 until 72) {
                        val a = Math.toRadians(i * 5.0)
                        val major = i % 18 == 0
                        val mid = i % 6 == 0
                        val ro = r * 0.83f
                        val len = if (major) r * 0.1f else if (mid) r * 0.065f else r * 0.035f
                        drawLine(
                            if (i == 0) Color(0xFFFF3B30) else if (major || mid) text else sub,
                            Offset(c.x + ((ro - len) * sin(a)).toFloat(), c.y - ((ro - len) * cos(a)).toFloat()),
                            Offset(c.x + (ro * sin(a)).toFloat(), c.y - (ro * cos(a)).toFloat()),
                            strokeWidth = if (major) r * 0.02f else r * 0.01f, cap = StrokeCap.Round
                        )
                    }
                    for (k in 0 until 4) {
                        val a = Math.toRadians(k * 90.0)
                        val rr = r * 0.62f
                        paint.color = if (k == 0) 0xFFFF3B30.toInt() else sub.toArgb()
                        drawIntoCanvas {
                            it.nativeCanvas.drawText(
                                letters[k],
                                c.x + (rr * sin(a)).toFloat(),
                                c.y - (rr * cos(a)).toFloat() + textPx * 0.35f,
                                paint
                            )
                        }
                    }
                    rotate(bearing.toFloat(), c) {
                        drawLine(needle, c, Offset(c.x, c.y - r * 0.56f), strokeWidth = r * 0.045f, cap = StrokeCap.Round)
                        val ks = r * 0.2f
                        val top = c.y - r * 0.8f
                        drawRoundRect(Color(0xFF1C1C1E), Offset(c.x - ks / 2, top), Size(ks, ks), CornerRadius(ks * 0.16f))
                        drawRect(Color(0xFFD4AF37), Offset(c.x - ks / 2, top + ks * 0.27f), Size(ks, ks * 0.13f))
                    }
                }
                drawCircle(text, r * 0.045f, c)
            }
            Spacer(Modifier.height(18.dp))
            Txt("${bearing.roundToInt()}° ${Qibla.direction(bearing)}", 30, FontWeight.Bold, align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            val st = when {
                !hasSensor -> t("no_sensor")
                aligned -> t("aligned") + " ✓"
                diff > 0 -> t("turn_right", abs(diff).roundToInt())
                else -> t("turn_left", abs(diff).roundToInt())
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (aligned) Color(0x2634C759) else p.bg)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Txt(st, 15, FontWeight.SemiBold, if (aligned) Color(0xFF248A3D) else p.text) }
        }
    }
}
