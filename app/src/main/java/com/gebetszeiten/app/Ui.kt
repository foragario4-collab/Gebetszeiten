package com.gebetszeiten.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Apple-artiger Tipp-Effekt: kein eckiger grauer Kasten, sondern kurz kleiner und blasser.
 */
fun Modifier.tap(enabled: Boolean = true, scale: Float = 0.96f, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) scale else 1f, label = "tapScale")
    val al by animateFloatAsState(if (pressed) 0.6f else 1f, label = "tapAlpha")
    this
        .graphicsLayer {
            scaleX = sc
            scaleY = sc
            alpha = al
        }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

data class Pal(
    val bg: Color,
    val card: Color,
    val text: Color,
    val sub: Color,
    val line: Color,
    val accent: Color,
    val accentText: Color,
    val hl: Color,
    val fill: Color,
    val seg: Color,
    val dark: Boolean
)

val LightPal = Pal(
    bg = Color(0xFFF2F2F7), card = Color.White, text = Color(0xFF1C1C1E), sub = Color(0xFF6C6C70),
    line = Color(0xFFE5E5EA), accent = Color(0xFF0B6E5F), accentText = Color(0xFF0B6E5F),
    hl = Color(0x170B6E5F), fill = Color(0xFFE9E9EB), seg = Color.White, dark = false
)
val DarkPal = Pal(
    bg = Color.Black, card = Color(0xFF1C1C1E), text = Color.White, sub = Color(0xFFAEAEB2),
    line = Color(0xFF38383A), accent = Color(0xFF0B7A68), accentText = Color(0xFF4FD6BC),
    hl = Color(0x244FD6BC), fill = Color(0xFF39393D), seg = Color(0xFF636366), dark = true
)
val LocalPal = staticCompositionLocalOf { LightPal }

@Composable
fun Txt(
    text: String,
    size: Int = 16,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalPal.current.text,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        textAlign = align,
        maxLines = maxLines,
        lineHeight = (size * 1.25).sp
    )
}

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        content = content
    )
}

@Composable
fun LargeTitle(text: String) {
    Txt(text, 34, FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp, start = 4.dp))
}

@Composable
fun Section(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Txt(title.uppercase(), 13, color = p.sub, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card),
            content = content
        )
        if (footer != null) {
            Txt(footer, 12, color = p.sub, modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp))
        }
    }
}

@Composable
fun RowBase(first: Boolean, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(p.line))
        val m = Modifier.fillMaxWidth()
        Row(
            (if (onClick != null) m.tap(scale = 0.985f, onClick = onClick) else m)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun RowScope.LabelCol(label: String, sub: String? = null) {
    Column(Modifier.weight(1f)) {
        Txt(label, 16)
        if (!sub.isNullOrEmpty()) Txt(sub, 12, color = LocalPal.current.sub)
    }
}

@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPal.current
    val off by animateDpAsState(if (checked) 22.dp else 2.dp, label = "knopf")
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (checked) Color(0xFF34C759) else p.fill)
            .tap(scale = 0.94f) { onChange(!checked) }
    ) {
        Box(
            Modifier
                .padding(start = off, top = 2.dp)
                .size(27.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, first: Boolean = false, sub: String? = null, onChange: (Boolean) -> Unit) {
    RowBase(first) {
        LabelCol(label, sub)
        IosSwitch(checked, onChange)
    }
}

@Composable
fun ValueRow(label: String, value: String, first: Boolean = false, sub: String? = null, onClick: () -> Unit) {
    val p = LocalPal.current
    RowBase(first, onClick) {
        LabelCol(label, sub)
        Txt(value, 16, color = p.sub, maxLines = 1, modifier = Modifier.widthIn(max = 190.dp))
        Txt("›", 22, color = p.line)
    }
}

@Composable
fun ButtonRow(label: String, first: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    RowBase(first, onClick) {
        Txt(label, 16, color = color ?: LocalPal.current.accentText)
    }
}

@Composable
fun StepperRow(label: String, value: String, first: Boolean = false, onMinus: () -> Unit, onPlus: () -> Unit) {
    val p = LocalPal.current
    RowBase(first) {
        LabelCol(label)
        Txt(value, 15, color = p.sub, align = TextAlign.End, modifier = Modifier.width(64.dp))
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(p.fill)) {
            Box(Modifier.size(46.dp, 32.dp).tap(onClick = onMinus), contentAlignment = Alignment.Center) {
                Txt("−", 20)
            }
            Box(Modifier.width(1.dp).height(32.dp).padding(vertical = 7.dp).background(p.sub.copy(alpha = 0.3f)))
            Box(Modifier.size(46.dp, 32.dp).tap(onClick = onPlus), contentAlignment = Alignment.Center) {
                Txt("+", 20)
            }
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPal.current
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(p.fill).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (i == selected) p.seg else Color.Transparent)
                    .tap { onSelect(i) }
                    .heightIn(min = 34.dp)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Txt(o, 13, if (i == selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun SegRow(label: String, options: List<String>, selected: Int, first: Boolean = false, onSelect: (Int) -> Unit) {
    RowBase(first) {
        LabelCol(label)
        Segmented(options, selected, onSelect)
    }
}

/** Grüner Haken wie bei Apple. */
@Composable
fun GreenCheck(visible: Boolean = true) {
    Canvas(Modifier.size(20.dp)) {
        if (!visible) return@Canvas
        val w = size.width
        val path = Path().apply {
            moveTo(w * 0.17f, w * 0.52f)
            lineTo(w * 0.38f, w * 0.73f)
            lineTo(w * 0.83f, w * 0.27f)
        }
        drawPath(path, Color(0xFF34C759), style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, first: Boolean = false, sub: String? = null, onClick: () -> Unit) {
    RowBase(first, onClick) {
        LabelCol(label, sub)
        GreenCheck(checked)
    }
}

data class Choice(val title: String, val options: List<String>, val selected: Int, val onPick: (Int) -> Unit)

@Composable
fun ChoiceDialog(c: Choice, onDismiss: () -> Unit) {
    val p = LocalPal.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(p.card)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            Txt(c.title, 17, FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            c.options.forEachIndexed { i, o ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tap(scale = 0.985f) { c.onPick(i); onDismiss() }
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Txt(o, 16, modifier = Modifier.weight(1f))
                    GreenCheck(i == c.selected)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
