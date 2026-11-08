package com.gebetszeiten.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

/** Moschee-Symbol für die Karte (grüner Kreis mit Kuppel). */
private fun mosqueIcon(ctx: Context, selected: Boolean): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val size = ((if (selected) 44 else 34) * d).toInt()
    val h = (size * 1.25f).toInt()
    val bmp = Bitmap.createBitmap(size, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    val green = if (selected) 0xFF0B6E5F.toInt() else 0xFF13917C.toInt()
    // Spitze
    val tip = android.graphics.Path().apply {
        moveTo(r - size * 0.18f, size * 0.85f)
        lineTo(r + size * 0.18f, size * 0.85f)
        lineTo(r, h.toFloat() - 1f)
        close()
    }
    p.color = green
    c.drawPath(tip, p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(r, r, r - 1f, p)
    p.color = green
    c.drawCircle(r, r, r - 2.5f * d, p)
    // Kuppel
    p.color = 0xFFFFFFFF.toInt()
    val s = size * 0.5f
    val left = r - s / 2f
    val top = r - s / 2f
    c.drawArc(left + s * 0.12f, top + s * 0.1f, left + s * 0.88f, top + s * 0.86f, 180f, 180f, true, p)
    c.drawRect(left + s * 0.12f, top + s * 0.47f, left + s * 0.88f, top + s * 0.86f, p)
    c.drawRect(left, top + s * 0.86f, left + s, top + s * 0.98f, p)
    c.drawRect(r - s * 0.04f, top - s * 0.06f, r + s * 0.04f, top + s * 0.14f, p)
    return bmp
}

private fun hidePois(style: Style) {
    style.layers.forEach { layer ->
        val id = layer.id.lowercase()
        val src = (layer as? SymbolLayer)?.sourceLayer?.lowercase() ?: ""
        if (src == "poi" || id.contains("poi") || id.contains("aerodrome")) {
            layer.setProperties(PropertyFactory.visibility(Property.NONE))
        }
    }
}

private fun features(list: List<Mosque>): FeatureCollection =
    FeatureCollection.fromFeatures(list.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.lng, m.lat)).also { it.addStringProperty("id", m.id) }
    })

@SuppressLint("MissingPermission")
private fun setupStyle(ctx: Context, map: MapLibreMap, style: Style, mosques: List<Mosque>) {
    hidePois(style)
    style.addImage("mosque", mosqueIcon(ctx, false))
    style.addImage("mosque_sel", mosqueIcon(ctx, true))
    style.addSource(GeoJsonSource("mosques", features(mosques)))
    style.addSource(GeoJsonSource("mosque_sel", FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(
        SymbolLayer("mosques-layer", "mosques").withProperties(
            PropertyFactory.iconImage("mosque"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
    style.addLayer(
        SymbolLayer("mosque-sel-layer", "mosque_sel").withProperties(
            PropertyFactory.iconImage("mosque_sel"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
    if (hasLocPermission(ctx)) {
        try {
            val lc = map.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(ctx, style).useDefaultLocationEngine(true).build()
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.COMPASS
        } catch (e: Exception) {
        }
    }
}

@Composable
fun QiblaScreen(v: Int) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val density = LocalDensity.current

    // ---------- Kompass ----------
    var myLat by remember { mutableDoubleStateOf(Prefs.lat) }
    var myLng by remember { mutableDoubleStateOf(Prefs.lng) }
    val bearing = Qibla.bearing(myLat, myLng)
    val dist = Qibla.distanceKm(myLat, myLng)
    var az by remember { mutableFloatStateOf(0f) }
    var hasSensor by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) {
            hasSensor = false
            onDispose { }
        } else {
            val decl = GeomagneticField(Prefs.lat.toFloat(), Prefs.lng.toFloat(), 0f, System.currentTimeMillis()).declination
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

    // ---------- Moscheen ----------
    var mosques by remember { mutableStateOf(Mosques.list(ctx)) }
    var selected by remember { mutableStateOf<Mosque?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var viewRef by remember { mutableStateOf<MapView?>(null) }
    var permOk by remember { mutableStateOf(hasLocPermission(ctx)) }

    fun load(force: Boolean) {
        if (force || Mosques.needsRefresh(ctx, myLat, myLng)) {
            status = "Moscheen werden geladen …"
            Mosques.refresh(ctx, myLat, myLng) { ok ->
                status = if (ok) null else "Laden fehlgeschlagen – ist Internet an?"
                mosques = Mosques.list(ctx)
            }
        }
    }

    fun locate(animate: Boolean) {
        Loc.fetch(ctx) { loc ->
            if (loc != null) {
                myLat = loc.latitude
                myLng = loc.longitude
                if (animate) mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLat, myLng), 13.5))
            }
            load(false)
        }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permOk = res.values.any { it }
        if (permOk) locate(true) else load(false)
    }

    LaunchedEffect(Unit) {
        if (permOk) locate(true)
        else perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Marker aktualisieren
    LaunchedEffect(mosques, styleRef, selected) {
        val st = styleRef ?: return@LaunchedEffect
        try {
            st.getSourceAs<GeoJsonSource>("mosques")?.setGeoJson(features(mosques.filter { it.id != selected?.id }))
            st.getSourceAs<GeoJsonSource>("mosque_sel")?.setGeoJson(features(listOfNotNull(selected)))
        } catch (e: Exception) {
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewRef?.let {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }
        }
    }

    val dark = p.dark
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Txt("Qibla", 34, FontWeight.Bold, modifier = Modifier.weight(1f))
            Txt("Mekka · ${"%,d".format(dist.roundToInt()).replace(',', ' ')} km", 13, color = p.sub)
        }
        Box(Modifier.height(10.dp))

        // ---------- Rechteckiger Kompass ----------
        val needle = if (!hasSensor) p.sub else if (aligned) Color(0xFF34C759) else Color(0xFFFF9500)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.card)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("BLICKRICHTUNG", 12, FontWeight.SemiBold, p.sub)
                    Txt(if (hasSensor) "${az.roundToInt()}°" else "–", 40, FontWeight.Light)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Txt("QIBLA", 12, FontWeight.SemiBold, p.sub)
                    Txt("${bearing.roundToInt()}° ${Qibla.direction(bearing)}", 17, FontWeight.SemiBold)
                }
            }
            val tickColor = p.text
            val minorColor = p.line
            val labelColor = p.sub
            val textPx = with(density) { 12.dp.toPx() }
            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                val w = size.width
                val hgt = size.height
                val ppd = w / 128f
                val heading = az
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = textPx
                    isFakeBoldText = true
                }
                val base = hgt - textPx * 1.9f
                var d = (ceil((heading - 64f) / 5f) * 5f).toInt()
                while (d <= heading + 64f) {
                    val n = ((d % 360) + 360) % 360
                    val x = w / 2f + (d - heading) * ppd
                    val major = n % 30 == 0
                    val isN = n == 0
                    val len = if (major) textPx * 1.8f else textPx * 0.9f
                    drawLine(
                        if (isN) Color(0xFFFF3B30) else if (major) tickColor else minorColor,
                        Offset(x, base - len), Offset(x, base),
                        strokeWidth = if (major) 4f else 3f, cap = StrokeCap.Round
                    )
                    if (major) {
                        val label = when (n) { 0 -> "N"; 90 -> "O"; 180 -> "S"; 270 -> "W"; else -> n.toString() }
                        paint.color = if (isN) 0xFFFF3B30.toInt() else labelColor.toArgb()
                        drawIntoCanvas { it.nativeCanvas.drawText(label, x, hgt - textPx * 0.4f, paint) }
                    }
                    d += 5
                }
                // Kaaba-Markierung
                var rel = ((bearing - heading + 540.0) % 360.0 - 180.0).toFloat()
                rel = rel.coerceIn(-62f, 62f)
                val kx = w / 2f + rel * ppd
                val ks = textPx * 1.3f
                drawRoundRect(Color(0xFF1C1C1E), Offset(kx - ks / 2, 4f), Size(ks, ks), CornerRadius(ks * 0.18f))
                drawRect(Color(0xFFD4AF37), Offset(kx - ks / 2, 4f + ks * 0.28f), Size(ks, ks * 0.12f))
                // Nadel
                drawLine(needle, Offset(w / 2f, 0f), Offset(w / 2f, base + 2f), strokeWidth = 6f, cap = StrokeCap.Round)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.line))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(needle))
                val st = when {
                    !hasSensor -> "Kein Kompass-Sensor gefunden"
                    aligned -> "Ausgerichtet – Richtung Kaaba"
                    diff > 0 -> "Nach rechts drehen · ${abs(diff).roundToInt()}°"
                    else -> "Nach links drehen · ${abs(diff).roundToInt()}°"
                }
                Txt(st, 15, FontWeight.SemiBold, if (aligned) Color(0xFF34C759) else p.text)
            }
        }
        Box(Modifier.height(12.dp))

        // ---------- Karte ----------
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(p.card)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    MapLibre.getInstance(c)
                    MapView(c).apply {
                        onCreate(null)
                        onStart()
                        onResume()
                        viewRef = this
                        getMapAsync { map ->
                            mapRef = map
                            map.uiSettings.isCompassEnabled = false
                            map.uiSettings.isTiltGesturesEnabled = false
                            map.uiSettings.isLogoEnabled = false
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(myLat, myLng)).zoom(13.0).build()
                            val onLoaded = Style.OnStyleLoaded { st ->
                                setupStyle(c, map, st, mosques)
                                styleRef = st
                            }
                            addOnDidFailLoadingMapListener {
                                if (dark) map.setStyle(Style.Builder().fromUri(STYLE_LIGHT), onLoaded)
                            }
                            map.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT), onLoaded)
                            map.addOnMapClickListener { point ->
                                val px = map.projection.toScreenLocation(point)
                                val hit = map.queryRenderedFeatures(px, "mosques-layer", "mosque-sel-layer")
                                val id = hit.firstOrNull()?.getStringProperty("id")
                                selected = if (id != null) mosques.firstOrNull { it.id == id } ?: Mosques.list(c).firstOrNull { it.id == id } else null
                                selected?.let { m ->
                                    map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(m.lat, m.lng)))
                                }
                                true
                            }
                        }
                    }
                }
            )

            // Oben links: Radar
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.card.copy(alpha = 0.96f))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Txt("Radar", 14, FontWeight.SemiBold)
                IosSwitch(Prefs.radar) { on ->
                    if (on) {
                        if (permOk) {
                            Prefs.radar = true
                            if (!RadarService.start(ctx)) Prefs.radar = false
                        } else {
                            perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    } else {
                        Prefs.radar = false
                        RadarService.stop(ctx)
                    }
                }
            }

            // Oben rechts: Standort + Neu laden
            Column(
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapButton("➤") { locate(true) }
                MapButton("↻") { load(true) }
            }

            val sel = selected
            if (sel == null) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp)
                        .shadow(6.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(p.card.copy(alpha = 0.97f))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Txt(status ?: "${mosques.size} Moscheen in der Nähe", 15, FontWeight.SemiBold)
                    Txt("Tippe auf eine Moschee für Gebetszeiten & Iqamah", 13, color = p.sub)
                }
            } else {
                MosqueSheet(ctx, sel, Mosques.distance(myLat, myLng, sel.lat, sel.lng), now) { selected = null }
            }
        }
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    val p = LocalPal.current
    Box(
        Modifier
            .size(44.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(p.card.copy(alpha = 0.96f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Txt(label, 18, FontWeight.Bold, Color(0xFF007AFF)) }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MosqueSheet(
    ctx: Context, m: Mosque, dist: Float, now: Long, onClose: () -> Unit
) {
    val p = LocalPal.current
    val today = Times.today(now)
    val evs = Times.around(now)
    val prayers = listOf(Kind.FAJR, Kind.DHUHR, Kind.ASR, Kind.MAGHRIB, Kind.ISHA)
    val todayEvs = prayers.mapNotNull { k -> evs.firstOrNull { it.kind == k && it.day == today } }
    val next = evs.firstOrNull { it.at > now && it.kind.prayer }
    val driveMin = ((dist / 1000f) / 30f * 60f).roundToInt().coerceAtLeast(1)
    var info by remember(m.id) { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(p.card)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(36.dp, 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(p.line)
        )
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Txt(m.name, 19, FontWeight.Bold, maxLines = 2)
                Txt(
                    listOf(m.address, Mosques.fmtDist(dist)).filter { it.isNotBlank() }.joinToString(" · "),
                    13, color = p.sub
                )
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(p.fill).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) { Txt("✕", 14, FontWeight.Bold, p.sub) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Txt("Gebet", 12, FontWeight.SemiBold, p.sub, Modifier.weight(1f))
            Txt("Adhan", 12, FontWeight.SemiBold, p.sub, Modifier.width(70.dp), align = TextAlign.End)
            Txt("Iqamah", 12, FontWeight.SemiBold, p.sub, Modifier.width(70.dp), align = TextAlign.End)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)) {
            todayEvs.forEach { e ->
                val isNext = next != null && e.kind == next.kind && e.day == next.day
                val c = if (isNext) Color.White else p.text
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isNext) p.accent else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Txt(e.title, 15, if (isNext) FontWeight.Bold else FontWeight.Medium, c, Modifier.weight(1f))
                    Txt(Fmt.time(e.at), 15, color = c, modifier = Modifier.width(70.dp), align = TextAlign.End)
                    Txt(m.iqamah[e.kind.name] ?: "–", 15, FontWeight.Bold, c, Modifier.width(70.dp), align = TextAlign.End)
                }
            }
        }
        if (m.iqamah.isEmpty()) {
            Txt("Iqamah-Zeiten folgen, sobald die Quelle eingebunden ist.", 12, color = p.sub)
        }
        info?.let { Txt(it, 13, FontWeight.SemiBold, p.accentText) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF007AFF))
                    .clickable {
                        try {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("geo:${m.lat},${m.lng}?q=${m.lat},${m.lng}(${Uri.encode(m.name)})")
                                )
                            )
                        } catch (e: Exception) {
                            info = "Keine Navigations-App gefunden (z. B. Vela Maps)"
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Txt("Route · $driveMin Min", 15, FontWeight.SemiBold, Color.White) }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.fill)
                    .clickable {
                        if (next != null) {
                            val at = maxOf(System.currentTimeMillis() + 5_000L, next.at - 15 * 60_000L)
                            val iq = m.iqamah[next.kind.name]
                            val intent = Intent(ctx, AlarmReceiver::class.java)
                                .setAction(Notifs.ACTION_MOSQUE)
                                .putExtra("title", "${m.name}: ${next.title} um ${Fmt.time(next.at)}")
                                .putExtra("text", if (iq != null) "Iqamah um $iq" else "Gebet beginnt bald")
                            val pi = PendingIntent.getBroadcast(
                                ctx, 500 + (m.id.hashCode() and 0xFFFF), intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            val am = ctx.getSystemService(AlarmManager::class.java)
                            try {
                                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                                } else {
                                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                                }
                                info = "Erinnerung um ${Fmt.time(at)} gesetzt"
                            } catch (e: Exception) {
                                info = "Erinnerung konnte nicht gesetzt werden"
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Txt("Erinnern", 15, FontWeight.SemiBold, Color(0xFF007AFF)) }
        }
    }
}
