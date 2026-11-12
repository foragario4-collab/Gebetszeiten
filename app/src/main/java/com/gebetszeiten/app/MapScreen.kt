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
import androidx.compose.foundation.horizontalScroll
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
/** Karten-Pin: 0 = Moschee (grün, Kuppel), 1 = Gebetsplatz (lila, Teppich), 2 = Wudu (blau, Tropfen). */
private fun mosqueIcon(ctx: Context, selected: Boolean, kind: Int = 0): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val size = ((if (selected) 44 else 34) * d).toInt()
    val h = (size * 1.25f).toInt()
    val bmp = Bitmap.createBitmap(size, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    val col = when (kind) {
        1 -> if (selected) 0xFF4B49C9.toInt() else 0xFF5E5CE6.toInt()
        2 -> if (selected) 0xFF0066CC.toInt() else 0xFF0A84FF.toInt()
        else -> if (selected) 0xFF0B6E5F.toInt() else 0xFF13917C.toInt()
    }
    val tip = android.graphics.Path().apply {
        moveTo(r - size * 0.18f, size * 0.85f)
        lineTo(r + size * 0.18f, size * 0.85f)
        lineTo(r, h.toFloat() - 1f)
        close()
    }
    p.color = col
    c.drawPath(tip, p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(r, r, r - 1f, p)
    p.color = col
    c.drawCircle(r, r, r - 2.5f * d, p)
    // Symbol (24er Raster) mittig, weiß: Moschee M10, Gebetsraum G4, Wudu W2
    p.color = 0xFFFFFFFF.toInt()
    val g = size * 0.56f
    c.save()
    c.translate(r - g / 2f, r - g / 2f)
    c.scale(g / 24f, g / 24f)
    when (kind) {
        1 -> {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.2f
            c.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(Glyph.PRAYER_LINE), p)
            p.style = Paint.Style.FILL
            c.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(Glyph.PRAYER_FILL), p)
        }
        2 -> c.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(Glyph.WUDU), p)
        else -> c.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(Glyph.MOSQUE), p)
    }
    c.restore()
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
    style.addImage("prayer", mosqueIcon(ctx, false, 1))
    style.addImage("wudu", mosqueIcon(ctx, false, 2))
    style.addImage("prayer_sel", mosqueIcon(ctx, true, 1))
    style.addImage("wudu_sel", mosqueIcon(ctx, true, 2))
    style.addSource(GeoJsonSource("prayer", FeatureCollection.fromFeatures(emptyList())))
    style.addSource(GeoJsonSource("wudu", FeatureCollection.fromFeatures(emptyList())))
    listOf("prayer", "wudu").forEach { k ->
        style.addLayer(
            SymbolLayer("$k-layer", k).withProperties(
                PropertyFactory.iconImage(k),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
            )
        )
    }
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
fun MapScreen(v: Int) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val density = LocalDensity.current

    // Startpunkt: letzte echte GPS-Position (höchstens 6 Stunden alt), sonst der eingestellte Ort
    val recentGps = Prefs.lastGpsAt > System.currentTimeMillis() - 6 * 3600_000L && Prefs.lastGpsLat != 0.0
    var myLat by remember { mutableDoubleStateOf(if (recentGps) Prefs.lastGpsLat else Prefs.lat) }
    var myLng by remember { mutableDoubleStateOf(if (recentGps) Prefs.lastGpsLng else Prefs.lng) }

    // ---------- Moscheen ----------
    var mosques by remember { mutableStateOf<List<Mosque>>(emptyList()) }
    LaunchedEffect(Unit) {
        // Dateien im Hintergrund lesen, damit die Karte sofort erscheint
        mosques = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { allPlaces(ctx) }
    }
    val visible = remember(mosques, v) {
        mosques.filter {
            when (it.category) {
                "Moschee" -> Prefs.showMosques
                Places.CAT_WUDU -> Prefs.showWudu
                else -> Prefs.showPrayer
            }
        }
    }
    var showAddPlace by remember { mutableStateOf<String?>(null) }
    var showRadarInfo by remember { mutableStateOf(false) }
    var chooseAdd by remember { mutableStateOf(false) }
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
    var moving by remember { mutableStateOf<Mosque?>(null) }
    var reportFor by remember { mutableStateOf<Mosque?>(null) }

    fun reloadList() {
        mosques = allPlaces(ctx)
        selected = selected?.let { s -> mosques.firstOrNull { it.id == s.id } }
    }

    fun load(force: Boolean) {
        if (force || Mosques.needsRefresh(ctx, myLat, myLng)) {
            status = t("loading_mosques")
            Mosques.refresh(ctx, myLat, myLng) { ok ->
                status = when {
                    ok -> null
                    Mosques.lastError == "offline" -> t("err_offline")
                    else -> t("err_busy")
                }
                mosques = allPlaces(ctx)
            }
        }
        // Islam-Landkarte (ganz Österreich), einmal im Monat
        if (force || Mosques.landkarteNeedsRefresh(ctx)) {
            Mosques.refreshLandkarte(ctx) { mosques = allPlaces(ctx) }
        }
        // Mawaqit: Moscheen mit tagesaktuellen Zeiten (Adhan, Iqamah, Jumuah)
        if (force || Mosques.mawaqitNeedsRefresh(ctx, myLat, myLng)) {
            Mosques.refreshMawaqit(ctx, myLat, myLng) {
                mosques = allPlaces(ctx)
                selected = selected?.let { s -> mosques.firstOrNull { it.id == s.id } } ?: selected
            }
        }
    }

    var locating by remember { mutableStateOf(false) }
    var gpsOn by remember { mutableStateOf(Loc.isOn(ctx)) }
    DisposableEffect(Unit) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                gpsOn = Loc.isOn(ctx)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, r, android.content.IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        onDispose {
            try {
                ctx.unregisterReceiver(r)
            } catch (e: Exception) {
            }
        }
    }

    fun locate(animate: Boolean) {
        gpsOn = Loc.isOn(ctx)
        if (!gpsOn) {
            locating = false
            load(false)
            return
        }
        locating = true
        Loc.fetch(ctx) { loc ->
            locating = false
            if (loc != null) {
                myLat = loc.latitude
                myLng = loc.longitude
                Loc.applyFix(ctx, loc.latitude, loc.longitude)
                if (animate) mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLat, myLng), 13.5))
            }
            load(false)
        }
    }

    // GPS wurde gerade eingeschaltet → sofort Standort holen
    var gpsSeen by remember { mutableStateOf(gpsOn) }
    LaunchedEffect(gpsOn) {
        if (gpsOn && !gpsSeen) locate(false)
        gpsSeen = gpsOn
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permOk = res.values.any { it }
        if (permOk) locate(true) else load(false)
    }

    LaunchedEffect(Unit) {
        Mosques.importSeeds(ctx) { reloadList() }
        Mosques.refreshOwnTimes(ctx) { reloadList() }
        if (permOk) locate(true)
        else perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Marker aktualisieren
    LaunchedEffect(visible, styleRef, selected) {
        val st = styleRef ?: return@LaunchedEffect
        try {
            val vis = visible.filter { it.id != selected?.id }
            st.getSourceAs<GeoJsonSource>("mosques")?.setGeoJson(features(vis.filter { it.category == "Moschee" }))
            st.getSourceAs<GeoJsonSource>("prayer")?.setGeoJson(features(vis.filter { it.category != "Moschee" && it.category != Places.CAT_WUDU }))
            st.getSourceAs<GeoJsonSource>("wudu")?.setGeoJson(features(vis.filter { it.category == Places.CAT_WUDU }))
            val sel = selected
            val selImg = when (sel?.category) {
                "Moschee" -> "mosque_sel"
                Places.CAT_WUDU -> "wudu_sel"
                else -> "prayer_sel"
            }
            st.getLayer("mosque-sel-layer")?.setProperties(PropertyFactory.iconImage(selImg))
            st.getSourceAs<GeoJsonSource>("mosque_sel")?.setGeoJson(features(listOfNotNull(sel)))
        } catch (e: Exception) {
        }
    }

    // Karte mit dem App-Lebenszyklus pausieren (spart Akku im Hintergrund)
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> viewRef?.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewRef?.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewRef?.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> viewRef?.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
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

    Box(Modifier.fillMaxSize()) {
        // ---------- Karte ----------
        Box(
            Modifier
                .fillMaxSize()
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
                                    val mv = moving
                                    if (mv != null) {
                                        moving = null
                                        val nm = Mosques.move(c, mv, point.latitude, point.longitude)
                                        reloadList()
                                        selected = mosques.firstOrNull { it.id == nm.id }
                                    } else {
                                        manual = point.latitude to point.longitude
                                        showManual = true
                                    }
                                    return@addOnMapClickListener true
                                }
                                val px = map.projection.toScreenLocation(point)
                                val hit = map.queryRenderedFeatures(px, "mosques-layer", "mosque-sel-layer", "prayer-layer", "wudu-layer")
                                val id = hit.firstOrNull()?.getStringProperty("id")
                                selected = if (id != null) mosques.firstOrNull { it.id == id } ?: allPlaces(c).firstOrNull { it.id == id } else null
                                selected?.let { m ->
                                    map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(m.lat, m.lng)))
                                }
                                true
                            }
                        }
                    }
                }
            )

            // Lade-Hinweis, damit man sieht, dass die App arbeitet und nicht hängt
            val busyText = when {
                !gpsOn -> t("gps_off_pill")
                locating -> t("gps_searching")
                else -> null
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = busyText != null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 104.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
                exit = androidx.compose.animation.fadeOut()
            ) {
                Row(
                    Modifier
                        .shadow(6.dp, RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (!gpsOn) Color(0xF2FF9500) else Color(0xF21C1C1E))
                        .tap(enabled = !gpsOn) { Loc.openSettings(ctx) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (gpsOn) androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp,
                        trackColor = Color.White.copy(alpha = 0.25f)
                    ) else GlyphIcon(Glyph.ROUTE, Color.White, 16.dp)
                    Box(Modifier.size(8.dp))
                    Txt(busyText ?: "", 13, FontWeight.SemiBold, Color.White, maxLines = 1)
                }
            }

            // Oben: Filter Moscheen / Gebetsplätze / Wudu
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 10.dp, end = 64.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(t("f_mosques"), Color(0xFF13917C), Prefs.showMosques) { Prefs.showMosques = it }
                FilterChip(t("f_prayer"), Color(0xFF5E5CE6), Prefs.showPrayer) { Prefs.showPrayer = it }
                FilterChip(t("f_wudu"), Color(0xFF0A84FF), Prefs.showWudu) { Prefs.showWudu = it }
            }

            // Radar
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 56.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.card.copy(alpha = 0.96f))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Txt(t("radar"), 14, FontWeight.SemiBold)
                IosSwitch(Prefs.radar) { on ->
                    if (on && !Prefs.radarExplained) {
                        showRadarInfo = true
                    } else if (on) {
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
                MapButton("+") { chooseAdd = true }
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
                        Txt(if (moving != null) t("move_hint") else t("pick_on_map"), 15, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Txt(t("cancel"), 15, color = Color(0xFF007AFF), modifier = Modifier.tap { pickMode = false }.padding(8.dp))
                    }
                }
            } else {
                MosqueBottomSheet(
                    mosques = visible, selected = sel, lat = myLat, lng = myLng, now = now, status = status,
                    onPick = { m ->
                        selected = m
                        mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(m.lat, m.lng), 14.5))
                    },
                    onClose = { selected = null },
                    onEdit = { m -> editIq = m },
                    onRemove = { m ->
                        Mosques.removeOwn(ctx, m.id)
                        selected = null
                        reloadList()
                    },
                    onUpdated = { m ->
                        reloadList()
                        selected = mosques.firstOrNull { it.id == m.id } ?: m
                    },
                    onHide = { m ->
                        Prefs.hide(m.id)
                        selected = null
                        reloadList()
                    },
                    onReport = { m -> reportFor = m },
                    onReload = { reloadList() }
                )
            }
        }
    }

    if (showRadarInfo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRadarInfo = false },
            title = { Txt(t("sec_radar"), 18, FontWeight.Bold) },
            text = { Txt(t("radar_explain"), 15) },
            confirmButton = {
                Txt(t("radar_start"), 16, FontWeight.SemiBold, Color(0xFF007AFF), Modifier.tap {
                    showRadarInfo = false
                    Prefs.radarExplained = true
                    if (permOk) {
                        Prefs.radar = true
                        if (!RadarService.start(ctx)) Prefs.radar = false
                    } else {
                        perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }.padding(8.dp))
            },
            dismissButton = {
                Txt(t("cancel"), 16, color = Color(0xFF007AFF), modifier = Modifier.tap { showRadarInfo = false }.padding(8.dp))
            }
        )
    }
    if (chooseAdd) {
        ChoiceDialog(Choice(t("add_what"), listOf(t("add_mosque"), t("kind_prayer"), t("kind_wudu")), -1) { i ->
            when (i) {
                0 -> showAdd = true
                1 -> showAddPlace = "prayer"
                else -> showAddPlace = "wudu"
            }
        }) { chooseAdd = false }
    }
    showAddPlace?.let { k ->
        AddPlaceDialog(k, myLat, myLng, onClose = { showAddPlace = null }) { pl ->
            showAddPlace = null
            if (pl.kind == "wudu") Prefs.showWudu = true else Prefs.showPrayer = true
            reloadList()
            selected = mosques.firstOrNull { it.id == pl.id }
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
    reportFor?.let { m ->
        ChoiceDialog(
            Choice(t("report"), listOf(t("rep_position"), t("rep_gone"), t("rep_times")), -1) { i ->
                when (i) {
                    0 -> {
                        moving = m
                        selected = null
                        pickMode = true
                    }
                    1 -> {
                        Prefs.hide(m.id)
                        selected = null
                        reloadList()
                    }
                    else -> editIq = m
                }
            }
        ) { reportFor = null }
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
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Txt(label, 18, FontWeight.Bold, Color(0xFF007AFF)) }
}

// ------------------------------------------------------------------
// Dialoge
// ------------------------------------------------------------------

@Composable
internal fun FullSheet(title: String, saveEnabled: Boolean, onClose: () -> Unit, onSave: () -> Unit, content: @Composable () -> Unit) {
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
                Txt(t("cancel"), 16, color = Color(0xFF007AFF), modifier = Modifier.tap(onClick = onClose).padding(8.dp))
                Txt(title, 16, FontWeight.SemiBold, align = TextAlign.Center, modifier = Modifier.weight(1f))
                Txt(
                    t("save"), 16, FontWeight.SemiBold,
                    if (saveEnabled) Color(0xFF007AFF) else p.sub.copy(alpha = 0.5f),
                    Modifier.tap(enabled = saveEnabled, onClick = onSave).padding(8.dp)
                )
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
internal fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
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

    var query by remember { mutableStateOf("") }

    fun applyResult(d: Mosques.Draft?, err: String) {
        if (d == null) {
            busy = false
            error = err
            return
        }
        Thread {
            val ps = if (d.lat != null && d.lng != null) d.lat to d.lng
            else if (d.address.isNotBlank()) Mosques.geocode(d.address) ?: Mosques.geocode(d.name)
            else Mosques.geocode(d.name)
            main.post {
                busy = false
                draft = d
                pos = ps
                link = d.url
                if (ps == null) {
                    error = t("pos_not_found")
                } else {
                    val km = Mosques.distance(Prefs.lat, Prefs.lng, ps.first, ps.second) / 1000f
                    if (km > 50f) error = t("far_warning", km.roundToInt())
                }
            }
        }.start()
    }

    fun search() {
        if (query.isBlank() || busy) return
        busy = true
        error = null
        draft = null
        pos = null
        Thread {
            val d = Mosques.searchByName(query)
            main.post { applyResult(d, t("nothing_found") + Mosques.friendlyInfo()) }
        }.start()
    }

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
                Triple(null, null, t("page_failed") + Mosques.friendlyInfo())
            }
            main.post {
                busy = false
                draft = res.first
                pos = res.second
                val far = res.second?.let { Mosques.distance(Prefs.lat, Prefs.lng, it.first, it.second) / 1000f }
                error = res.third ?: when {
                    res.first != null && res.second == null -> t("pos_not_found")
                    far != null && far > 50f -> t("far_warning", far.roundToInt())
                    res.first != null && res.first!!.adhan.isEmpty() -> null
                    else -> null
                }
            }
        }.start()
    }

    val canSave = draft != null && pos != null
    FullSheet(t("add_mosque"), canSave, onClose, onSave = {
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
        Txt(t("search_name"), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Txt(t("search_hint"), 15, color = p.sub, maxLines = 1)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color(0xFF007AFF)),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF007AFF)).tap { search() }.padding(horizontal = 14.dp, vertical = 9.dp)
            ) { Txt(if (busy) t("searching") else t("search"), 14, FontWeight.SemiBold, Color.White) }
        }
        Txt(t("name_note"), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp))

        Txt(t("or_link"), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
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
                Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF007AFF)).tap { fetch() }.padding(horizontal = 14.dp, vertical = 9.dp)
            ) { Txt(if (busy) t("loading") else t("load"), 14, FontWeight.SemiBold, Color.White) }
        }
        error?.let { Txt(it, 13, FontWeight.SemiBold, Color(0xFFFF3B30), modifier = Modifier.padding(start = 4.dp)) }
        draft?.let { d ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GreenCheck()
                    Txt(t("found"), 14, FontWeight.SemiBold, Color(0xFF34C759))
                }
                Txt(d.name, 16, FontWeight.Bold)
                if (d.address.isNotBlank()) Txt(d.address, 13, color = p.sub)
                Txt(
                    if (pos != null) t("position", "%.5f, %.5f".format(pos!!.first, pos!!.second)) else t("pos_missing"),
                    12, color = p.sub
                )
                val withIq = d.iqamah.size
                Txt(
                    if (d.adhan.isEmpty()) t("no_times_page")
                    else t("times_taken", d.adhan.size, withIq),
                    12, color = p.sub
                )
            }
        }
        Txt(t("or"), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card)) {
            RowBase(true, onPickMap) { LabelCol(t("set_on_map")); Txt("›", 20, color = p.sub) }
            RowBase(false, onManual) { LabelCol(t("by_hand")); Txt("›", 20, color = p.sub) }
        }
        Txt(
            t("tip_links"),
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

    FullSheet(t("own_mosque"), name.isNotBlank() && !busy && (pos != null || addr.isNotBlank()), onClose, onSave = {
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
                    if (r != null) save(r.first, r.second) else error = t("addr_not_found")
                }
            }.start()
        }
    }) {
        Field(t("name"), name) { name = it }
        Field(t("address"), addr) { addr = it }
        Txt(
            if (pos != null) t("pos_from_map", "%.5f, %.5f".format(pos.first, pos.second))
            else t("pos_by_addr"),
            12, color = p.sub, modifier = Modifier.padding(start = 4.dp)
        )
        if (busy) Txt(t("searching_addr"), 13, color = p.sub, modifier = Modifier.padding(start = 4.dp))
        error?.let { Txt(it, 13, FontWeight.SemiBold, Color(0xFFFF3B30), modifier = Modifier.padding(start = 4.dp)) }
    }
}

@Composable
private fun IqamahDialog(m: Mosque, onClose: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val p = LocalPal.current
    val keys = listOf("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA", "JUMUAH").map { it to t("k_$it") }
    // intern nur Ziffern („0600“), angezeigt als „06:00“
    val values = remember(m.id) { keys.map { (k, _) -> mutableStateOf((m.iqamah[k] ?: "").filter { it.isDigit() }) } }
    val valid = values.all { v -> v.value.isEmpty() || (v.value.length == 4 && v.value.substring(2).toInt() < 60 && v.value.substring(0, 2).toInt() < 24) }
    FullSheet(t("iq_times"), valid, onClose, onSave = {
        val out = LinkedHashMap<String, String>()
        keys.forEachIndexed { i, (k, _) ->
            val d = values[i].value
            if (d.length == 4) out[k] = d.substring(0, 2) + ":" + d.substring(2)
        }
        onSave(out)
    }) {
        Txt(m.name, 16, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        Txt(t("time_hint"), 13, color = p.sub, modifier = Modifier.padding(start = 4.dp))
        keys.forEachIndexed { i, (_, label) ->
            TimeField(label, values[i].value) { values[i].value = it }
        }
        Txt(t("empty_dash"), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * Uhrzeit-Eingabe nur mit Ziffern: Doppelpunkt kommt von selbst.
 * Erste Ziffer 3–9 → wird zu „0X:“, danach direkt die Minuten.
 */
@Composable
private fun TimeField(label: String, digits: String, onChange: (String) -> Unit) {
    val p = LocalPal.current
    fun clean(input: String): String {
        val raw = input.filter { it.isDigit() }
        var d = ""
        for (ch in raw) {
            val n = ch - '0'
            d = when {
                d.isEmpty() && n >= 3 -> "0$n"
                d.length == 1 && (d + ch).toInt() > 23 -> "0$d$ch"
                d.length == 2 && n > 5 -> d
                d.length >= 4 -> d
                else -> d + ch
            }
        }
        return d.take(4)
    }
    val shown = when {
        digits.length <= 1 -> digits
        digits.length == 2 -> "$digits:"
        else -> digits.substring(0, 2) + ":" + digits.substring(2)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Txt(label, 16, modifier = Modifier.weight(1f))
        Box(contentAlignment = Alignment.CenterEnd) {
            if (digits.isEmpty()) Txt("––:––", 22, FontWeight.SemiBold, p.sub)
            BasicTextField(
                value = androidx.compose.ui.text.input.TextFieldValue(shown, androidx.compose.ui.text.TextRange(shown.length)),
                onValueChange = { tv ->
                    // Löschen: wurde nur der Doppelpunkt entfernt, auch die Ziffer davor löschen
                    val newDigits = tv.text.filter { it.isDigit() }
                    val next = if (tv.text.length < shown.length && newDigits == digits) digits.dropLast(1) else clean(newDigits)
                    onChange(next)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                ),
                cursorBrush = SolidColor(Color(0xFF007AFF)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.width(110.dp)
            )
        }
    }
}


/** Moscheen + selbst eingetragene Gebets- und Wudu-Plätze. */
internal fun allPlaces(ctx: Context): List<Mosque> = Mosques.list(ctx) + Places.asMosques(ctx).filter { it.id !in Prefs.hiddenIds }

@Composable
private fun FilterChip(label: String, color: Color, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier
            .shadow(4.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) p.card else p.card.copy(alpha = 0.7f))
            .tap(scale = 0.92f) { onChange(!on) }
            .padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) color else p.line), contentAlignment = Alignment.Center) {
            if (on) KindIcon(
                when (color) {
                    Color(0xFF0A84FF) -> Places.CAT_WUDU
                    Color(0xFF5E5CE6) -> Places.CAT_PRAYER
                    else -> "Moschee"
                }, 22.dp
            )
        }
        Box(Modifier.size(6.dp))
        Txt(label, 14, FontWeight.SemiBold, if (on) p.text else p.sub, maxLines = 1)
    }
}
