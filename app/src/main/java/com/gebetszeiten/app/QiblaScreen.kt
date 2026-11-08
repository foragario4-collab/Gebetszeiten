package com.gebetszeiten.app

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun QiblaScreen(v: Int) {
    val p = LocalPal.current
    val ctx = LocalContext.current
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
    val aligned = hasSensor && abs(diff) < 5.0

    ScreenColumn {
        LargeTitle("Qibla")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(p.card)
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val accent = if (aligned) Color(0xFF34C759) else p.accentText
            val ring = p.line
            val tick = p.sub
            val dot = p.text
            Canvas(Modifier.size(280.dp)) {
                val r = size.minDimension / 2f
                val c = this.center
                // Fester Zeiger oben = Blickrichtung des Handys
                val tri = Path().apply {
                    moveTo(c.x, 2f)
                    lineTo(c.x - 16f, 30f)
                    lineTo(c.x + 16f, 30f)
                    close()
                }
                drawPath(tri, accent)
                drawCircle(ring, r - 40f, c, style = Stroke(5f))
                rotate(-az, c) {
                    for (i in 0 until 72) {
                        val a = Math.toRadians(i * 5.0)
                        val len = if (i % 18 == 0) 28f else if (i % 6 == 0) 18f else 9f
                        val ro = r - 44f
                        val outer = Offset(c.x + (ro * sin(a)).toFloat(), c.y - (ro * cos(a)).toFloat())
                        val inner = Offset(c.x + ((ro - len) * sin(a)).toFloat(), c.y - ((ro - len) * cos(a)).toFloat())
                        drawLine(
                            if (i == 0) Color(0xFFFF3B30) else tick, inner, outer,
                            strokeWidth = if (i % 18 == 0) 6f else 2.5f, cap = StrokeCap.Round
                        )
                    }
                    rotate(bearing.toFloat(), c) {
                        drawLine(accent, c, Offset(c.x, c.y - r + 110f), strokeWidth = 12f, cap = StrokeCap.Round)
                        drawRect(
                            Color(0xFF1C1C1E),
                            topLeft = Offset(c.x - 22f, c.y - r + 70f),
                            size = androidx.compose.ui.geometry.Size(44f, 44f)
                        )
                        drawRect(
                            Color(0xFFD4AF37),
                            topLeft = Offset(c.x - 22f, c.y - r + 80f),
                            size = androidx.compose.ui.geometry.Size(44f, 6f)
                        )
                    }
                }
                drawCircle(dot, 10f, c)
            }
            Spacer(Modifier.height(10.dp))
            Txt("${bearing.roundToInt()}° ${Qibla.direction(bearing)}", 28, FontWeight.Bold)
            Txt("Richtung Mekka · ${"%,d".format(dist.roundToInt()).replace(',', ' ')} km", 15, color = p.sub)
            Spacer(Modifier.height(6.dp))
            val status = when {
                !hasSensor -> "Kein Kompass-Sensor gefunden"
                aligned -> "Ausgerichtet ✓"
                diff > 0 -> "Nach rechts drehen (${abs(diff).roundToInt()}°)"
                else -> "Nach links drehen (${abs(diff).roundToInt()}°)"
            }
            Txt(status, 17, FontWeight.SemiBold, accent)
        }
        Spacer(Modifier.height(12.dp))
        Txt(
            "Handy flach halten und von Metall fernhalten. Bei ungenauer Anzeige das Handy in Form einer 8 bewegen.",
            13, color = p.sub, align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
    }
}
