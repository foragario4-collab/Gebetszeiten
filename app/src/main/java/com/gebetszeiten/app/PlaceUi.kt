package com.gebetszeiten.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File

/** Kamera öffnen; das fertige (verkleinerte, datenfreie) Foto landet in [dest()]. */
@Composable
fun rememberCamera(dest: () -> File, onSaved: (File) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var tmp by remember { mutableStateOf<File?>(null) }
    var target by remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val src = tmp
        val out = target
        if (ok && src != null && out != null && Places.storePhoto(src, out)) onSaved(out)
    }
    return {
        val dir = File(ctx.cacheDir, "camera").also { it.mkdirs() }
        val f = File(dir, "foto_${System.currentTimeMillis()}.jpg")
        tmp = f
        target = dest()
        val uri: Uri = FileProvider.getUriForFile(ctx, "com.gebetszeiten.app.files", f)
        try {
            launcher.launch(uri)
        } catch (e: Exception) {
        }
    }
}

/** Vorschaubild aus einer Datei. */
@Composable
fun PhotoThumb(path: String, w: Dp, h: Dp, onClick: (() -> Unit)? = null) {
    val bmp = remember(path) { Places.thumb(path) }
    val m = Modifier.size(w, h).clip(RoundedCornerShape(12.dp)).background(LocalPal.current.fill)
    Box(if (onClick != null) m.tap(scale = 0.96f, onClick = onClick) else m) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Großansicht eines Fotos. */
@Composable
fun PhotoViewer(path: String, onClose: () -> Unit) {
    val bmp = remember(path) { Places.thumb(path, 1400) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).tap(scale = 1f, onClick = onClose), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
        }
    }
}

private fun tagKeys(kind: String) =
    if (kind == "wudu") listOf("t_sink", "t_foot", "t_wc", "t_free", "t_clean", "t_women", "t_access")
    else listOf("t_in", "t_out", "t_quiet", "t_clean", "t_free", "t_women", "t_access")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(keys: List<String>, selected: List<String>, onToggle: ((String) -> Unit)?) {
    val p = LocalPal.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { k ->
            val on = k in selected
            val m = Modifier.clip(RoundedCornerShape(17.dp)).background(if (on) p.accent else p.card)
            Box(
                (if (onToggle != null) m.tap(scale = 0.94f) { onToggle(k) } else m).padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Txt(t(k), 13, FontWeight.SemiBold, if (on) Color.White else p.text) }
        }
    }
}

/** Gebets- oder Wudu-Platz eintragen: 2–5 Fotos, Name, Uhrzeiten, Angaben, Position per GPS. */
@Composable
fun AddPlaceDialog(kind0: String, lat: Double, lng: Double, onClose: () -> Unit, onSaved: (Place) -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf(kind0) }
    var name by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    val tags = remember { mutableStateListOf<String>() }
    val photos = remember { mutableStateListOf<String>() }
    var viewer by remember { mutableStateOf<String?>(null) }
    val id = remember { "pl-" + System.currentTimeMillis() }
    val camera = rememberCamera(
        dest = { File(Places.photoDir(ctx), "${id}_${System.currentTimeMillis()}.jpg") },
        onSaved = { f -> if (photos.size < 10) photos.add(f.path) }
    )
    val canSave = photos.size >= 2 && name.isNotBlank()

    FullSheet(t("place_new"), canSave, onClose, onSave = {
        val pl = Place(id, kind, name.trim(), lat, lng, hours.trim(), tags.toList(), photos.toList(), System.currentTimeMillis())
        Places.add(ctx, pl)
        onSaved(pl)
    }) {
        Segmented(listOf(t("kind_prayer"), t("kind_wudu")), if (kind == "wudu") 1 else 0) {
            kind = if (it == 1) "wudu" else "prayer"
            tags.clear()
        }
        Txt(t("photos_n", photos.size), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { ph -> PhotoThumb(ph, 72.dp, 72.dp) { viewer = ph } }
            if (photos.size < 10) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                        .border(2.dp, p.line, RoundedCornerShape(12.dp)).tap { camera() },
                    contentAlignment = Alignment.Center
                ) { Txt("+", 26, FontWeight.Medium, Color(0xFF007AFF)) }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp))
                .background(if (photos.size < 10) Color(0xFF007AFF) else p.fill).tap(enabled = photos.size < 10) { camera() },
            contentAlignment = Alignment.Center
        ) { Txt(t("take_photo"), 15, FontWeight.SemiBold, if (photos.size < 10) Color.White else p.sub) }
        Txt(t("photo_hint"), 12, color = p.sub, modifier = Modifier.padding(horizontal = 4.dp))

        Field(t("name"), name) { name = it }
        Column {
            Txt(t("hours").uppercase(), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(12.dp)) {
                if (hours.isEmpty()) Txt(t("hours_hint"), 16, color = p.sub)
                BasicTextField(
                    value = hours, onValueChange = { hours = it }, singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color(0xFF007AFF)), modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(t("sec_location"), 16, modifier = Modifier.weight(1f))
            Txt(t("position_gps"), 14, FontWeight.SemiBold, Color(0xFF248A3D))
        }
        Txt(t("details"), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        TagChips(tagKeys(kind), tags) { k -> if (k in tags) tags.remove(k) else tags.add(k) }
    }
    viewer?.let { PhotoViewer(it) { viewer = null } }
}

/** Inhalt der Detailansicht für einen Gebets-/Wudu-Platz. */
@Composable
fun PlaceDetailContent(ctx: Context, pl: Place, onDelete: () -> Unit) {
    val p = LocalPal.current
    var viewer by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pl.photos.forEach { ph -> PhotoThumb(ph, 140.dp, 100.dp) { viewer = ph } }
    }
    if (pl.hours.isNotBlank()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(t("hours"), 15, modifier = Modifier.weight(1f))
            Txt(pl.hours, 15, FontWeight.SemiBold)
        }
    }
    if (pl.tags.isNotEmpty()) TagChips(pl.tags, pl.tags, null)
    Txt(t("delete"), 15, color = Color(0xFFFF3B30), modifier = Modifier.tap(onClick = onDelete).padding(vertical = 8.dp))
    viewer?.let { PhotoViewer(it) { viewer = null } }
}

/** Fotos einer Moschee: vom Eingang bis zum Gebetsraum, bis zu 10 (anzeigen und aufnehmen). */
@Composable
fun EntrancePhoto(mosqueId: String) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    var version by remember(mosqueId) { mutableStateOf(0) }
    var viewer by remember { mutableStateOf<String?>(null) }
    val photos = remember(mosqueId, version) { Places.mosquePhotos(ctx, mosqueId) }
    val camera = rememberCamera(dest = { Places.newMosquePhoto(ctx, mosqueId) }, onSaved = { version++ })
    Txt(t("mosque_photos", photos.size), 12, color = p.sub, modifier = Modifier.padding(start = 4.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEachIndexed { i, f ->
            Box {
                androidx.compose.runtime.key(f.path, version) { PhotoThumb(f.path, 120.dp, 80.dp) { viewer = f.path } }
                if (i == 0) Txt(t("entrance"), 11, FontWeight.SemiBold, Color.White, Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
        }
        if (photos.size < 10) {
            Column(
                Modifier.size(120.dp, 80.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, p.line, RoundedCornerShape(12.dp))
                    .tap { camera() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Txt("+", 20, color = Color(0xFF007AFF))
                Txt(if (photos.isEmpty()) t("entrance_add") else t("photo_more"), 12, FontWeight.SemiBold, Color(0xFF007AFF))
            }
        }
    }
    viewer?.let { PhotoViewer(it) { viewer = null } }
}
