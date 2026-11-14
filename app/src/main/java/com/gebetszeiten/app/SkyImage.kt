package com.gebetszeiten.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.compose.ui.graphics.asAndroidPath

/** Eine Glasfläche im Widget-Bild: Position in Pixeln, Eckenradius. */
internal class GlassRect(val r: RectF, val radius: Float, val tintAlpha: Int = 70, val tint: Int = 0xFF0E1426.toInt())

/**
 * Malt den lebenden Himmel (gleicher Shader wie „Heute“) als Bild für Widgets:
 * echter Sonnenstand, echtes Wetter, Mond mit Phase, Silhouette – und brennt das
 * Milchglas (unscharfer Himmel + Tönung + Lichtkante) direkt ins Bild ein.
 */
internal object SkyImage {

    fun render(ctx: Context, w: Int, h: Int, glass: List<GlassRect>): Bitmap {
        val now = System.currentTimeMillis()
        val st = skyState(now, Weather.current(ctx))
        val base = shaderBitmap(w, h, st, now) ?: gradientBitmap(w, h, st)
        val c = Canvas(base)
        // Widgets zeigen nur den Himmel mit Wetter – ohne Stadt-Silhouette (wie in der Vorschau)
        // Milchglas: Ausschnitt stark verkleinern und weich zurückzeichnen = echte Unschärfe
        if (glass.isNotEmpty()) {
            val small = Bitmap.createScaledBitmap(base, (w / 7).coerceAtLeast(1), (h / 7).coerceAtLeast(1), true)
            val blurred = Bitmap.createScaledBitmap(small, w, h, true)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            for (g in glass) {
                val path = Path().apply { addRoundRect(g.r, g.radius, g.radius, Path.Direction.CW) }
                c.save()
                c.clipPath(path)
                c.drawBitmap(blurred, 0f, 0f, null)
                c.drawColor(Color.argb(g.tintAlpha, Color.red(g.tint), Color.green(g.tint), Color.blue(g.tint)))
                // Lichtkante oben
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(0f, g.r.top, 0f, g.r.top + g.r.height() * 0.18f,
                    Color.argb(26, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawRect(g.r, p)
                p.shader = null
                c.restore()
                // feiner heller Rand
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                p.color = Color.argb(70, 255, 255, 255)
                c.drawRoundRect(g.r, g.radius, g.radius, p)
                p.style = Paint.Style.FILL
            }
            small.recycle()
            blurred.recycle()
        }
        return base
    }

    /** Shader auf dem Grafikchip in ein Bild zeichnen (ab Android 13). */
    private fun shaderBitmap(w: Int, h: Int, st: SkyState, now: Long): Bitmap? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val shader = SkyShader.create() ?: return null
            // halbe Auflösung reicht – Wolken sind weich; danach hochskalieren
            val rw = (w / 2).coerceAtLeast(1)
            val rh = (h / 2).coerceAtLeast(1)
            shader.setFloatUniform("uRes", rw.toFloat(), rh.toFloat())
            shader.setFloatUniform("uTime", ((now / 60_000L) % 100_000L) * 0.8f)
            shader.setFloatUniform("uTop", st.top[0], st.top[1], st.top[2])
            shader.setFloatUniform("uMid", st.mid[0], st.mid[1], st.mid[2])
            shader.setFloatUniform("uHor", st.hor[0], st.hor[1], st.hor[2])
            shader.setFloatUniform("uSun", st.sunX, st.sunY)
            shader.setFloatUniform("uSunVis", st.sunVis)
            shader.setFloatUniform("uSunCol", st.sunCol[0], st.sunCol[1], st.sunCol[2])
            shader.setFloatUniform("uSunLow", st.sunLow)
            shader.setFloatUniform("uMoon", st.moonX, st.moonY)
            shader.setFloatUniform("uMoonVis", st.moonVis)
            shader.setFloatUniform("uPhase", st.phase)
            shader.setFloatUniform("uStars", st.stars)
            shader.setFloatUniform("uCloud", st.cloud)
            shader.setFloatUniform("uRain", st.rain)
            shader.setFloatUniform("uSnow", st.snow)
            shader.setFloatUniform("uFog", st.fog)
            shader.setFloatUniform("uFlash", if (st.thunder) 0.45f else 0f)
            shader.setFloatUniform("uHorizon", 0.12f)
            shader.setFloatUniform("uFlashPos", 0.62f, 0.66f)
            shader.setFloatUniform("uWind", st.wind)
            shader.setFloatUniform("uCloudLit", st.lit[0], st.lit[1], st.lit[2])
            shader.setFloatUniform("uCloudShade", st.shade[0], st.shade[1], st.shade[2])
            shader.setFloatUniform("uSteps", 16f)
            shader.setFloatUniform("uLight", 1f)
            val paint = Paint()
            paint.shader = shader
            val reader = ImageReader.newInstance(
                rw, rh, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
            val hr = android.graphics.HardwareRenderer()
            hr.setSurface(reader.surface)
            val node = RenderNode("hadafSky")
            node.setPosition(0, 0, rw, rh)
            val rc = node.beginRecording()
            rc.drawRect(0f, 0f, rw.toFloat(), rh.toFloat(), paint)
            node.endRecording()
            hr.setContentRoot(node)
            hr.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val img = reader.acquireLatestImage() ?: return null
            val hb = img.hardwareBuffer ?: return null
            val hwBmp = Bitmap.wrapHardwareBuffer(hb, null)
            val out = hwBmp?.copy(Bitmap.Config.ARGB_8888, true)
            img.close(); hb.close(); hr.destroy(); reader.close()
            out?.let { Bitmap.createScaledBitmap(it, w, h, true).also { s -> if (s != it) it.recycle() } }
        } catch (e: Throwable) {
            null
        }
    }

    /** Rückfall ohne Shader: weicher Verlauf in den Himmelsfarben. */
    private fun gradientBitmap(w: Int, h: Int, st: SkyState): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        fun col(a: FloatArray) = Color.rgb((a[0] * 255).toInt(), (a[1] * 255).toInt(), (a[2] * 255).toInt())
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(col(st.top), col(st.mid), col(st.hor)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }

    /** Silhouette mit Kuppeln und Minaretten, nachts mit warmen Fenstern. */
    private fun drawSkyline(c: Canvas, w: Int, h: Int, st: SkyState) {
        val sc = w / 390f
        val hts = 120f * sc * 0.6f  // in Widgets etwas flacher
        val path = androidx.compose.ui.graphics.vector.PathParser().parsePathString(SKYLINE).toPath().asAndroidPath()
        val m = android.graphics.Matrix()
        m.setScale(sc, sc * 0.6f)
        m.postTranslate(0f, h - hts)
        path.transform(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (st.night) 0xFF0A0F22.toInt() else if (st.dusk) 0xFF2A2340.toInt() else 0xFF24324F.toInt()
        c.drawPath(path, p)
        if (st.night || st.dusk) {
            p.color = 0xFFFFD27A.toInt()
            for ((x, y) in listOf(168f to 83f, 176f to 83f, 184f to 83f, 55f to 87f, 61f to 87f, 279f to 88f, 285f to 88f)) {
                c.drawCircle(x * sc, h - hts + y * sc * 0.6f, 1.6f * sc, p)
            }
        }
    }
}
