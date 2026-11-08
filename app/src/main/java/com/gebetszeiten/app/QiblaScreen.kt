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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.os.Handler
import android.os.Looper
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
    var showAdd by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var editIq by remember { mutableStateOf<Mosque?>(null) }

    fun reloadList() {
        mosques = Mosques.list(ctx)
        selected = selected?.let { s -> mosques.firstOrNull { it.id == s.id } }
    }

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
        Mosques.refreshOwnTimes(ctx) { reloadList() }
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
                                if (pickMode) {
                                    pickMode = false
                                    manual = point.latitude to point.longitude
                                    showManual = true
                                    return@addOnMapClickListener true
                                }
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
                MapButton("+") { showAdd = true }
                MapButton("➤") { locate(true) }
                MapButton("↻") { load(true) }
            }

            val sel = selected
            if (pickMode) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp)
                        .shadow(6.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(p.card)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("Tippe auf die Karte, wo die Moschee ist", 15, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Txt("Abbrechen", 15, color = Color(0xFF007AFF), modifier = Modifier.clickable { pickMode = false }.padding(8.dp))
                    }
                }
            } else if (sel == null) {
                MosqueList(mosques, myLat, myLng, now, status) { m ->
                    selected = m
                    mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(m.lat, m.lng), 14.5))
                }
            } else {
                MosqueSheet(
                    ctx, sel, Mosques.distance(myLat, myLng, sel.lat, sel.lng), now,
                    onClose = { selected = null },
                    onEdit = { editIq = sel },
                    onRemove = {
                        Mosques.removeOwn(ctx, sel.id)
                        selected = null
                        reloadList()
                    }
                )
            }
        }
    }

    if (showAdd) {
        AddMosqueDialog(
            onClose = { showAdd = false },
            onSaved = { m ->
                showAdd = false
                reloadList()
                selected = mosques.firstOrNull { it.id == m.id }
                mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(m.lat, m.lng), 15.0))
            },
            onPickMap = {
                showAdd = false
                pickMode = true
            },
            onManual = {
                showAdd = false
                manual = null
                showManual = true
            }
        )
    }
    if (showManual) {
        ManualMosqueDialog(manual, onClose = { showManual = false }) { m ->
            showManual = false
            reloadList()
            selected = mosques.firstOrNull { it.id == m.id }
            mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(m.lat, m.lng), 15.0))
        }
    }
    editIq?.let { m ->
        IqamahDialog(m, onClose = { editIq = null }) { iq ->
            Mosques.setIqamah(ctx, m.id, iq)
            editIq = null
            reloadList()
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

/** Nächstes Gebet einer Moschee: (Titel, Adhan, Iqamah?) – nutzt die Zeiten der Moschee, falls vorhanden. */
private fun nextFor(m: Mosque, evs: List<Ev>, now: Long): Triple<String, String, String?>? {
    val n = evs.firstOrNull { it.at > now && it.kind.prayer } ?: return null
    val friday = n.kind == Kind.DHUHR && n.day.dayOfWeek == java.time.DayOfWeek.FRIDAY
    val key = if (friday && (m.adhan.containsKey("JUMUAH") || m.iqamah.containsKey("JUMUAH"))) "JUMUAH" else n.kind.name
    val adhan = m.adhan[key] ?: Fmt.time(n.at)
    return Triple(n.title, adhan, m.iqamah[key])
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MosqueList(
    mosques: List<Mosque>, lat: Double, lng: Double, now: Long, status: String?, onPick: (Mosque) -> Unit
) {
    val p = LocalPal.current
    val evs = remember(now / 60_000L) { Times.around(now) }
    val sorted = remember(mosques, lat, lng) {
        mosques.map { it to Mosques.distance(lat, lng, it.lat, it.lng) }.sortedBy { it.second }.take(60)
    }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.52f)
            .shadow(10.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(p.card)
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp, bottom = 6.dp)
                .size(36.dp, 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(p.line)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Txt("Moscheen in der Nähe", 20, FontWeight.Bold, modifier = Modifier.weight(1f))
            Txt(status ?: "nach Entfernung", 13, color = p.sub)
        }
        if (sorted.isEmpty()) {
            Txt(
                status ?: "Noch keine Moscheen geladen – tippe auf ↻ oder füge mit + eine hinzu.",
                14, color = p.sub, modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sorted, key = { it.first.id }) { (m, d) ->
                val nx = nextFor(m, evs, now)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.bg)
                        .clickable { onPick(m) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF13917C)), contentAlignment = Alignment.Center) {
                        Txt("☪", 17, color = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Txt(m.name, 15, FontWeight.SemiBold, maxLines = 1)
                        if (nx != null) {
                            Txt(
                                "${nx.first} ${nx.second} · Iqamah ${nx.third ?: "–"}",
                                13, FontWeight.SemiBold, p.accentText, maxLines = 1
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Txt(Mosques.fmtDist(d), 13, color = p.sub)
                        val srcColor = when (m.source) {
                            "MasjidBox" -> Color(0xFF007AFF)
                            "Mawaqit" -> p.accentText
                            "OSM" -> p.sub
                            else -> Color(0xFFFF9500)
                        }
                        Txt(m.source, 11, FontWeight.SemiBold, srcColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MosqueSheet(
    ctx: Context, m: Mosque, dist: Float, now: Long,
    onClose: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit
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
            .verticalScroll(rememberScrollState())
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
            val rows: List<Triple<String, Ev?, String>> = todayEvs.map { Triple(it.kind.label, it as Ev?, it.kind.name) } +
                (if (m.adhan.containsKey("JUMUAH") || m.iqamah.containsKey("JUMUAH")) listOf(Triple("Jumuah", null, "JUMUAH")) else emptyList())
            rows.forEach { (label, e, key) ->
                val isNext = e != null && next != null && e.kind == next.kind && e.day == next.day
                val c = if (isNext) Color.White else p.text
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isNext) p.accent else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Txt(label, 15, if (isNext) FontWeight.Bold else FontWeight.Medium, c, Modifier.weight(1f))
                    Txt(m.adhan[key] ?: e?.let { Fmt.time(it.at) } ?: "–", 15, color = c, modifier = Modifier.width(70.dp), align = TextAlign.End)
                    Txt(m.iqamah[key] ?: "–", 15, FontWeight.Bold, c, Modifier.width(70.dp), align = TextAlign.End)
                }
            }
        }
        val srcLine = when {
            m.url != null && m.fetchedAt > 0 -> "Zeiten von ${m.source} · aktualisiert ${Fmt.time(m.fetchedAt)}"
            m.iqamah.isNotEmpty() -> "Iqamah von Hand eingetragen"
            else -> "Iqamah unbekannt – tippe auf „Iqamah bearbeiten“"
        }
        Txt(srcLine, 12, color = p.sub)
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
                            val key = next.kind.name
                            val at = maxOf(System.currentTimeMillis() + 5_000L, next.at - 15 * 60_000L)
                            val iq = m.iqamah[key]
                            val intent = Intent(ctx, AlarmReceiver::class.java)
                                .setAction(Notifs.ACTION_MOSQUE)
                                .putExtra("title", "${m.name}: ${next.title} um ${m.adhan[key] ?: Fmt.time(next.at)}")
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
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 2.dp)) {
            Txt("Iqamah bearbeiten", 15, color = Color(0xFF007AFF), modifier = Modifier.clickable(onClick = onEdit).padding(vertical = 8.dp))
            if (m.custom) {
                Txt("Entfernen", 15, color = Color(0xFFFF3B30), modifier = Modifier.clickable(onClick = onRemove).padding(vertical = 8.dp))
            }
        }
    }
}

// ------------------------------------------------------------------
// Dialoge
// ------------------------------------------------------------------

@Composable
private fun FullSheet(title: String, saveEnabled: Boolean, onClose: () -> Unit, onSave: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPal.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(p.bg)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Abbrechen", 16, color = Color(0xFF007AFF), modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
                Txt(title, 16, FontWeight.SemiBold, align = TextAlign.Center, modifier = Modifier.weight(1f))
                Txt(
                    "Sichern", 16, FontWeight.SemiBold,
                    if (saveEnabled) Color(0xFF007AFF) else p.sub.copy(alpha = 0.5f),
                    Modifier.clickable(enabled = saveEnabled, onClick = onSave).padding(8.dp)
                )
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val p = LocalPal.current
    Column {
        Txt(label.uppercase(), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(horizontal = 12.dp, vertical = 12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                cursorBrush = SolidColor(Color(0xFF007AFF)),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AddMosqueDialog(onClose: () -> Unit, onSaved: (Mosque) -> Unit, onPickMap: () -> Unit, onManual: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Mosques.Draft?>(null) }
    var pos by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun fetch() {
        if (link.isBlank() || busy) return
        busy = true
        error = null
        draft = null
        pos = null
        Thread {
            val res = try {
                val d = Mosques.fetchDraft(link)
                val ps = if (d.lat != null && d.lng != null) d.lat to d.lng
                else if (d.address.isNotBlank()) Mosques.geocode(d.address) ?: Mosques.geocode(d.name)
                else Mosques.geocode(d.name)
                Triple(d, ps, null as String?)
            } catch (e: Exception) {
                Triple(null, null, "Seite konnte nicht geladen werden – Link und Internet prüfen.")
            }
            main.post {
                busy = false
                draft = res.first
                pos = res.second
                error = res.third ?: when {
                    res.first != null && res.second == null -> "Position nicht gefunden – bitte „Punkt auf der Karte setzen“ nutzen."
                    res.first != null && res.first!!.adhan.isEmpty() -> null
                    else -> null
                }
            }
        }.start()
    }

    val canSave = draft != null && pos != null
    FullSheet("Moschee hinzufügen", canSave, onClose, onSave = {
        val d = draft ?: return@FullSheet
        val ps = pos ?: return@FullSheet
        val m = Mosque(
            id = "own-" + System.currentTimeMillis(),
            name = d.name, lat = ps.first, lng = ps.second, address = d.address,
            source = d.source, url = d.url, adhan = d.adhan, iqamah = d.iqamah,
            custom = true, fetchedAt = System.currentTimeMillis()
        )
        Mosques.addOwn(ctx, m)
        onSaved(m)
    }) {
        Txt("MASJIDBOX- ODER MAWAQIT-LINK", 12, color = p.sub, modifier = Modifier.padding(start = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                value = link,
                onValueChange = { link = it },
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 15.sp),
                cursorBrush = SolidColor(Color(0xFF007AFF)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF007AFF)).clickable { fetch() }.padding(horizontal = 14.dp, vertical = 9.dp)
            ) { Txt(if (busy) "Lädt …" else "Laden", 14, FontWeight.SemiBold, Color.White) }
        }
        error?.let { Txt(it, 13, FontWeight.SemiBold, Color(0xFFFF3B30), modifier = Modifier.padding(start = 4.dp)) }
        draft?.let { d ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GreenCheck()
                    Txt("Gefunden", 14, FontWeight.SemiBold, Color(0xFF34C759))
                }
                Txt(d.name, 16, FontWeight.Bold)
                if (d.address.isNotBlank()) Txt(d.address, 13, color = p.sub)
                Txt(
                    if (pos != null) "Position: %.5f, %.5f".format(pos!!.first, pos!!.second) else "Position fehlt",
                    12, color = p.sub
                )
                val withIq = d.iqamah.size
                Txt(
                    if (d.adhan.isEmpty()) "Keine Zeiten gefunden – du kannst die Iqamah danach von Hand eintragen."
                    else "${d.adhan.size} Gebetszeiten übernommen, davon $withIq mit Iqamah",
                    12, color = p.sub
                )
            }
        }
        Txt("ODER", 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card)) {
            RowBase(true, onPickMap) { LabelCol("Punkt auf der Karte setzen"); Txt("›", 20, color = p.sub) }
            RowBase(false, onManual) { LabelCol("Name & Adresse von Hand"); Txt("›", 20, color = p.sub) }
        }
        Txt(
            "Tipp: Den Link findest du auf masjidbox.com oder mawaqit.net bei der jeweiligen Moschee. Die Zeiten werden zweimal am Tag aktualisiert.",
            12, color = p.sub, modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun ManualMosqueDialog(pos: Pair<Double, Double>?, onClose: () -> Unit, onSaved: (Mosque) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    var name by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    FullSheet("Eigene Moschee", name.isNotBlank() && !busy && (pos != null || addr.isNotBlank()), onClose, onSave = {
        fun save(lat: Double, lng: Double) {
            val m = Mosque(
                id = "own-" + System.currentTimeMillis(), name = name.trim(), lat = lat, lng = lng,
                address = addr.trim(), source = "Eigene", custom = true
            )
            Mosques.addOwn(ctx, m)
            onSaved(m)
        }
        if (pos != null) {
            save(pos.first, pos.second)
        } else {
            busy = true
            error = null
            Thread {
                val r = Mosques.geocode(addr)
                main.post {
                    busy = false
                    if (r != null) save(r.first, r.second) else error = "Adresse nicht gefunden – bitte genauer eingeben."
                }
            }.start()
        }
    }) {
        Field("Name", name) { name = it }
        Field("Adresse", addr) { addr = it }
        Txt(
            if (pos != null) "Position von der Karte: %.5f, %.5f".format(pos.first, pos.second)
            else "Die Position wird über die Adresse gesucht.",
            12, color = p.sub, modifier = Modifier.padding(start = 4.dp)
        )
        if (busy) Txt("Adresse wird gesucht …", 13, color = p.sub, modifier = Modifier.padding(start = 4.dp))
        error?.let { Txt(it, 13, FontWeight.SemiBold, Color(0xFFFF3B30), modifier = Modifier.padding(start = 4.dp)) }
    }
}

@Composable
private fun IqamahDialog(m: Mosque, onClose: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val p = LocalPal.current
    val keys = listOf("FAJR" to "Fajr", "DHUHR" to "Dhuhr", "ASR" to "Asr", "MAGHRIB" to "Maghrib", "ISHA" to "Isha", "JUMUAH" to "Jumuah")
    val values = remember(m.id) { keys.map { (k, _) -> mutableStateOf(m.iqamah[k] ?: "") } }
    val valid = values.all { it.value.isBlank() || it.value.trim().matches(Regex("\\d{1,2}[:.]\\d{2}")) }
    FullSheet("Iqamah-Zeiten", valid, onClose, onSave = {
        val out = LinkedHashMap<String, String>()
        keys.forEachIndexed { i, (k, _) ->
            val v = values[i].value.trim().replace('.', ':')
            if (v.isNotBlank()) {
                val parts = v.split(":")
                out[k] = "%02d:%s".format(parts[0].toInt(), parts[1])
            }
        }
        onSave(out)
    }) {
        Txt(m.name, 16, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        keys.forEachIndexed { i, (_, label) ->
            Field("$label (z. B. 19:00)", values[i].value, KeyboardType.Number) { values[i].value = it }
        }
        Txt("Leere Felder werden als „–“ angezeigt.", 12, color = p.sub, modifier = Modifier.padding(start = 4.dp))
    }
}
