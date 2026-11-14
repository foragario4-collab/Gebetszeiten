package com.gebetszeiten.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews

/** Alle Widgets zeigen den echten Himmel (Wetter + Sonnenstand) als Hintergrundbild. */
open class SkyWidget(private val kind: Int) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Prefs.init(ctx)
        ids.forEach { Widgets.push(ctx, mgr, it, kind) }
        Scheduler.schedule(ctx)
        Widgets.refreshWeather(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        Prefs.init(ctx)
        Widgets.push(ctx, mgr, id, kind)
    }
}

class SmallWidget : SkyWidget(1)   // W1 Countdown
class RingWidget : SkyWidget(2)    // W2 Ring
class BarWidget : SkyWidget(3)     // W3 Zeile
class MediumWidget : SkyWidget(4)  // W4 Mittel
class MosqueWidget : SkyWidget(5)  // W5 Meine Moschee
class DayWidget : SkyWidget(6)     // W6 Ganzer Tag

object Widgets {
    private val classes = mapOf(
        1 to SmallWidget::class.java, 2 to RingWidget::class.java, 3 to BarWidget::class.java,
        4 to MediumWidget::class.java, 5 to MosqueWidget::class.java, 6 to DayWidget::class.java
    )
    @Volatile private var refreshing = false

    fun updateAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        for ((kind, cls) in classes) {
            m.getAppWidgetIds(ComponentName(ctx, cls)).forEach { push(ctx, m, it, kind) }
        }
    }

    /** Wetter für die Widgets aktuell halten (höchstens alle 30 Min, dann neu malen). */
    fun refreshWeather(ctx: Context) {
        if (refreshing) return
        refreshing = true
        val app = ctx.applicationContext
        Weather.refreshIfNeeded(app, Prefs.lat, Prefs.lng) { updateAll(app) }
        refreshing = false
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Widget-Größe in dp (Hochformat: minWidth × maxHeight). */
    private fun sizeDp(mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: defW
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: defH
        return w to h
    }

    fun push(ctx: Context, mgr: AppWidgetManager, id: Int, kind: Int) {
        try {
            val v = build(ctx, mgr, id, kind)
            mgr.updateAppWidget(id, v)
        } catch (e: Throwable) {
        }
    }

    private fun build(ctx: Context, mgr: AppWidgetManager, id: Int, kind: Int): RemoteViews {
        val now = System.currentTimeMillis()
        val evs = Times.around(now)
        val next = evs.first { it.at > now && (it.kind.prayer || it.kind == Kind.SUNRISE) }
        val nextPrayer = evs.first { it.at > now && it.kind.prayer }
        val prev = evs.last { it.at <= now }
        val frac = ((now - prev.at).toFloat() / (next.at - prev.at).coerceAtLeast(1)).coerceIn(0f, 1f)
        val (defW, defH) = when (kind) { 1, 2 -> 150 to 150; 3 -> 300 to 70; 6 -> 300 to 320; else -> 300 to 150 }
        val (wDp, hDp) = sizeDp(mgr, id, defW, defH)
        // Bild höchstens ~700 px breit – scharf genug und sparsam im Speicher
        val k = minOf(ctx.resources.displayMetrics.density, 700f / wDp)
        val w = (wDp * k).toInt().coerceAtLeast(1)
        val h = (hDp * k).toInt().coerceAtLeast(1)
        fun dp(x: Float) = x * k

        val glass = ArrayList<GlassRect>()
        when (kind) {
            2 -> {
                val d = minOf(w, h) - dp(24f) * 2
                val cx = w / 2f; val cy = h / 2f
                glass.add(GlassRect(RectF(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2), d / 2))
            }
            4 -> {
                val top = h - dp(14f + 52f); val bot = h - dp(14f)
                val tw = (w - dp(28f) - dp(6f) * 4) / 5f
                var x = dp(14f)
                repeat(5) { i ->
                    glass.add(GlassRect(RectF(x, top, x + tw, bot), dp(14f), 60))
                    x += tw + dp(6f)
                }
            }
            5 -> {
                val top = dp(14f + 16f + 8f)
                val tw = (w - dp(28f) - dp(8f)) / 2f
                glass.add(GlassRect(RectF(dp(14f), top, dp(14f) + tw, top + dp(52f)), dp(14f)))
                glass.add(GlassRect(RectF(dp(14f) + tw + dp(8f), top, w - dp(14f), top + dp(52f)), dp(14f), 80, 0xFF0E3C32.toInt()))
                glass.add(GlassRect(RectF(dp(14f), top + dp(60f), w - dp(14f), top + dp(90f)), dp(12f), 110, 0xFF1E7A3C.toInt()))
            }
            6 -> {
                val top = dp(16f + 40f + 112f)
                glass.add(GlassRect(RectF(dp(16f), top, w - dp(16f), h - dp(16f)), dp(18f)))
            }
        }
        val bmp = SkyImage.render(ctx, w, h, glass)
        decorate(bmp, kind, k, frac, next, evs, now)

        val layout = intArrayOf(0, R.layout.widget_w1, R.layout.widget_w2, R.layout.widget_w3, R.layout.widget_w4, R.layout.widget_w5, R.layout.widget_w6)[kind]
        val v = RemoteViews(ctx.packageName, layout)
        v.setImageViewBitmap(R.id.bg, bmp)
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        val base = SystemClock.elapsedRealtime() + (next.at - now)
        when (kind) {
            1 -> {
                v.setTextViewText(R.id.w1_label, t("next_up").uppercase())
                v.setTextViewText(R.id.w1_name, next.title)
                v.setChronometer(R.id.w1_chrono, base, null, true)
                v.setChronometerCountDown(R.id.w1_chrono, true)
                v.setTextViewText(R.id.w1_at, t("at_time", Fmt.time(next.at)))
            }
            2 -> {
                v.setTextViewText(R.id.w2_name, next.title)
                v.setChronometer(R.id.w2_chrono, base, null, true)
                v.setChronometerCountDown(R.id.w2_chrono, true)
                v.setTextViewText(R.id.w2_at, Fmt.time(next.at))
            }
            3 -> {
                v.setTextViewText(R.id.w3_name, "${next.title} · ${Fmt.time(next.at)}")
                val after = evs.firstOrNull { it.at > next.at && (it.kind.prayer || it.kind == Kind.SUNRISE) }
                v.setTextViewText(R.id.w3_after, after?.let { t("w_after", it.title, Fmt.time(it.at)) } ?: "")
                v.setChronometer(R.id.w3_chrono, base, null, true)
                v.setChronometerCountDown(R.id.w3_chrono, true)
            }
            4 -> {
                v.setTextViewText(R.id.w4_label, (Prefs.place + " · " + t("next_up")).uppercase())
                v.setTextViewText(R.id.w4_name, "${next.title}  ${Fmt.time(next.at)}")
                v.setChronometer(R.id.w4_chrono, base, null, true)
                v.setChronometerCountDown(R.id.w4_chrono, true)
                val today = Times.today(now)
                val five = evs.filter { it.kind.prayer && it.day == today }.take(5)
                val nids = intArrayOf(R.id.w4_n0, R.id.w4_n1, R.id.w4_n2, R.id.w4_n3, R.id.w4_n4)
                val tids = intArrayOf(R.id.w4_t0, R.id.w4_t1, R.id.w4_t2, R.id.w4_t3, R.id.w4_t4)
                for (i in 0 until 5) {
                    val e = five.getOrNull(i)
                    v.setTextViewText(nids[i], e?.shortTitle ?: "")
                    v.setTextViewText(tids[i], e?.let { Fmt.time(it.at) } ?: "")
                }
            }
            5 -> fillMosque(ctx, v, evs, now)
            6 -> {
                v.setTextViewText(R.id.w6_date, DateText.widget(now))
                v.setTextViewText(R.id.w6_place, Prefs.place)
                v.setTextViewText(R.id.w6_label, t("next_up").uppercase())
                v.setTextViewText(R.id.w6_name, nextPrayer.title)
                v.setChronometer(R.id.w6_chrono, SystemClock.elapsedRealtime() + (nextPrayer.at - now), null, true)
                v.setChronometerCountDown(R.id.w6_chrono, true)
                val today = Times.today(now)
                val six = evs.filter { (it.kind.prayer || it.kind == Kind.SUNRISE) && it.day == today }.take(6)
                val nids = intArrayOf(R.id.w6_n0, R.id.w6_n1, R.id.w6_n2, R.id.w6_n3, R.id.w6_n4, R.id.w6_n5)
                val tids = intArrayOf(R.id.w6_t0, R.id.w6_t1, R.id.w6_t2, R.id.w6_t3, R.id.w6_t4, R.id.w6_t5)
                for (i in 0 until 6) {
                    val e = six.getOrNull(i)
                    v.setTextViewText(nids[i], e?.title ?: "")
                    v.setTextViewText(tids[i], e?.let { Fmt.time(it.at) } ?: "")
                }
            }
        }
        return v
    }

    /** Punkte, Ring, Fortschrittsbalken und Hervorhebungen direkt ins Bild malen. */
    private fun decorate(bmp: Bitmap, kind: Int, k: Float, frac: Float, next: Ev, evs: List<Ev>, now: Long) {
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun dp(x: Float) = x * k
        when (kind) {
            1 -> { // Fortschrittsbalken unten
                val y = bmp.height - dp(16f)
                p.color = 0x48FFFFFF; p.strokeWidth = dp(5f); p.strokeCap = Paint.Cap.ROUND
                c.drawLine(dp(14f), y, bmp.width - dp(14f), y, p)
                p.color = 0xFFFFFFFF.toInt()
                c.drawLine(dp(14f), y, dp(14f) + (bmp.width - dp(28f)) * frac, y, p)
            }
            2 -> { // Ring
                val d = minOf(bmp.width, bmp.height) - dp(24f) * 2
                val r = RectF((bmp.width - d) / 2 + dp(9f), (bmp.height - d) / 2 + dp(9f), (bmp.width + d) / 2 - dp(9f), (bmp.height + d) / 2 - dp(9f))
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(8f); p.strokeCap = Paint.Cap.ROUND
                p.color = 0x3DFFFFFF
                c.drawArc(r, -90f, 360f, false, p)
                p.color = 0xFFFFFFFF.toInt()
                c.drawArc(r, -90f, 360f * frac, false, p)
            }
            3 -> { // leuchtender Punkt links
                p.color = 0x667CF0C8; c.drawCircle(dp(21f), bmp.height / 2f, dp(8f), p)
                p.color = 0xFF7CF0C8.toInt(); c.drawCircle(dp(21f), bmp.height / 2f, dp(5f), p)
            }
            4 -> { // nächste Kachel hervorheben
                val today = Times.today(now)
                val five = evs.filter { it.kind.prayer && it.day == today }.take(5)
                val ni = five.indexOfFirst { it.at > now }
                if (ni >= 0) {
                    val tw = (bmp.width - dp(28f) - dp(6f) * 4) / 5f
                    val x = dp(14f) + ni * (tw + dp(6f))
                    val r = RectF(x, bmp.height - dp(66f), x + tw, bmp.height - dp(14f))
                    p.style = Paint.Style.FILL; p.color = 0x3DFFFFFF
                    c.drawRoundRect(r, dp(14f), dp(14f), p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.4f); p.color = 0x99FFFFFF.toInt()
                    c.drawRoundRect(r, dp(14f), dp(14f), p)
                }
            }
            6 -> { // Zeile des nächsten Gebets hervorheben + Trennlinien + Punkte
                val top = dp(16f + 40f + 112f + 4f)
                val bot = bmp.height - dp(16f + 4f)
                val rowH = (bot - top) / 6f
                val today = Times.today(now)
                val six = evs.filter { (it.kind.prayer || it.kind == Kind.SUNRISE) && it.day == today }.take(6)
                val ni = six.indexOfFirst { it.at > now }
                p.style = Paint.Style.FILL
                if (ni >= 0) {
                    p.color = 0x29FFFFFF
                    c.drawRect(dp(18f), top + ni * rowH, bmp.width - dp(18f), top + (ni + 1) * rowH, p)
                }
                for (i in 0 until 6) {
                    if (i > 0) {
                        p.color = 0x1FFFFFFF
                        c.drawRect(dp(18f), top + i * rowH, bmp.width - dp(18f), top + i * rowH + dp(0.7f), p)
                    }
                    p.color = if (i == ni) 0xFF7CF0C8.toInt() else 0x66FFFFFF
                    c.drawCircle(dp(28f), top + i * rowH + rowH / 2, dp(3.5f), p)
                }
            }
        }
    }

    /** W5: Adhan und Iqamah von „Meine Moschee“ plus „Losfahren bis“. */
    private fun fillMosque(ctx: Context, v: RemoteViews, evs: List<Ev>, now: Long) {
        val favId = Prefs.favId
        val m = if (favId.isBlank()) null else try { Mosques.listAll(ctx).firstOrNull { it.id == favId } } catch (e: Exception) { null }
        if (m == null) {
            v.setTextViewText(R.id.w5_title, t("my_mosque").uppercase())
            v.setTextViewText(R.id.w5_leave, t("my_mosque_hint"))
            return
        }
        val dist = Mosques.distance(Prefs.lat, Prefs.lng, m.lat, m.lng)
        val travel = travelMin(dist, Prefs.travelMode)
        val arr = now + travel * 60_000L
        val niq = nextIqamah(m, evs, now, arr)
        val p = niq?.first ?: evs.first { it.at > now && it.kind.prayer }
        v.setTextViewText(R.id.w5_title, (t("my_mosque") + " · " + NameFmt.name(m.name)).uppercase())
        v.setTextViewText(R.id.w5_al, "${p.title} · ${t("adhan")}".uppercase())
        v.setTextViewText(R.id.w5_at, niq?.second ?: Fmt.time(p.at))
        v.setTextViewText(R.id.w5_il, t("iqamah").uppercase())
        v.setTextViewText(R.id.w5_it, niq?.let { Fmt.time(it.third) } ?: "–")
        if (niq != null) {
            val leave = niq.third - travel * 60_000L
            v.setTextViewText(R.id.w5_leave, t("leave_by", Fmt.time(leave)))
            v.setTextViewText(R.id.w5_travel, Fmt.duration(travel * 60_000L))
            val late = leave < now
            v.setTextColor(R.id.w5_leave, if (late) 0xFFFFB4AB.toInt() else 0xFFB8FFC9.toInt())
            v.setTextColor(R.id.w5_travel, if (late) 0xFFFFB4AB.toInt() else 0xFFB8FFC9.toInt())
        } else {
            v.setTextViewText(R.id.w5_leave, t("no_times"))
            v.setTextViewText(R.id.w5_travel, "")
        }
    }
}
