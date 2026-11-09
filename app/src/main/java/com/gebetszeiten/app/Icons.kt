package com.gebetszeiten.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Schlichte, gefüllte Symbole im Stil von Apples SF Symbols (Raster 24×24). */
object Glyph {
    const val CAR = "M6.2 5.8A2.5 2.5 0 0 1 8.6 4h6.8a2.5 2.5 0 0 1 2.4 1.8L19 10h.5A1.5 1.5 0 0 1 21 11.5V17a1 1 0 0 1-1 1h-1v1.5a1 1 0 0 1-1 1h-1a1 1 0 0 1-1-1V18H8v1.5a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V18H4a1 1 0 0 1-1-1v-5.5A1.5 1.5 0 0 1 4.5 10H5z"
    const val CAR_WINDOW = "M7.4 10l.95-3.1a1 1 0 0 1 .96-.7h5.38a1 1 0 0 1 .96.7l.95 3.1z"
    const val WALK = "M12.4 7.2c.9-.3 1.9.1 2.3 1l1.3 2.7 2.4 1.1a1.1 1.1 0 0 1-.9 2l-2.8-1.3a1.6 1.6 0 0 1-.8-.8l-.5-1-1 4 2.4 2.6c.2.2.3.5.3.8v3.5a1.1 1.1 0 0 1-2.2 0v-3.1l-2.5-2.6-1.1 4.2a1.1 1.1 0 0 1-2.1-.5l2.2-8.8-1.6.9-.9 2.6a1.1 1.1 0 0 1-2-.7l1-3a1.6 1.6 0 0 1 .7-.9z"
}

/** Auto (mode 0) oder zu Fuß (mode 1). [cut] = Farbe für Scheibe/Lichter (meist Hintergrund). */
@Composable
fun TravelIcon(mode: Int, color: Color, cut: Color, size: Dp = 18.dp) {
    val car = remember { PathParser().parsePathString(Glyph.CAR).toPath() }
    val win = remember { PathParser().parsePathString(Glyph.CAR_WINDOW).toPath() }
    val walk = remember { PathParser().parsePathString(Glyph.WALK).toPath() }
    Canvas(Modifier.size(size)) {
        val f = this.size.width / 24f
        scale(f, f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            if (mode == 0) {
                drawPath(car, color)
                drawPath(win, cut)
                drawCircle(cut, 1.3f, androidx.compose.ui.geometry.Offset(6.8f, 13.6f))
                drawCircle(cut, 1.3f, androidx.compose.ui.geometry.Offset(17.2f, 13.6f))
            } else {
                drawCircle(color, 2.2f, androidx.compose.ui.geometry.Offset(13.6f, 3.9f))
                drawPath(walk, color)
            }
        }
    }
}
