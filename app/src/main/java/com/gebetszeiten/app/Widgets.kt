package com.gebetszeiten.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.SizeF
import android.widget.RemoteViews

class MediumWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Prefs.init(ctx)
        val v = Widgets.medium(ctx)
        ids.forEach { mgr.updateAppWidget(it, v) }
        Scheduler.schedule(ctx)
    }
}

class SmallWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Prefs.init(ctx)
        val v = Widgets.small(ctx)
        ids.forEach { mgr.updateAppWidget(it, v) }
        Scheduler.schedule(ctx)
    }
}

object Widgets {
    private val cells = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4)
    private val names = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4)
    private val times = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)
    private val tags = intArrayOf(R.id.g0, R.id.g1, R.id.g2, R.id.g3, R.id.g4)

    fun isDark(ctx: Context): Boolean = when (Prefs.theme) {
        1 -> false
        2 -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun updateAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        val mids = m.getAppWidgetIds(ComponentName(ctx, MediumWidget::class.java))
        if (mids.isNotEmpty()) {
            val v = medium(ctx)
            mids.forEach { m.updateAppWidget(it, v) }
        }
        val sids = m.getAppWidgetIds(ComponentName(ctx, SmallWidget::class.java))
        if (sids.isNotEmpty()) {
            val v = small(ctx)
            sids.forEach { m.updateAppWidget(it, v) }
        }
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Großes Widget: ab Android 12 je nach Größe kompakt oder voll. */
    fun medium(ctx: Context): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            return RemoteViews(
                mapOf(
                    SizeF(200f, 40f) to mediumViews(ctx, true),
                    SizeF(200f, 125f) to mediumViews(ctx, false)
                )
            )
        }
        return mediumViews(ctx, false)
    }

    private fun mediumViews(ctx: Context, compact: Boolean): RemoteViews {
        val now = System.currentTimeMillis()
        val dark = isDark(ctx)
        val text = if (dark) Color.WHITE else Color.parseColor("#1C1C1E")
        val sub = if (dark) Color.parseColor("#AEAEB2") else Color.parseColor("#6C6C70")
        val v = RemoteViews(ctx.packageName, if (compact) R.layout.widget_medium_compact else R.layout.widget_medium)
        // „Automatisch“ ab Android 12: Farben aus Ressourcen, damit das Widget sofort mit hell/dunkel wechselt
        val auto = Prefs.theme == 0 && Build.VERSION.SDK_INT >= 31
        fun col(id: Int, sub: Boolean) {
            if (auto) v.setColorStateList(id, "setTextColor", if (sub) R.color.w_sub else R.color.w_text)
            else v.setTextColor(id, if (sub) Color.parseColor(if (dark) "#AEAEB2" else "#6C6C70") else (if (dark) Color.WHITE else Color.parseColor("#1C1C1E")))
        }
        v.setInt(R.id.w_root, "setBackgroundResource", if (auto) R.drawable.wbg_auto else if (dark) R.drawable.wbg_dark else R.drawable.wbg_light)

        val evs = Times.forWidget(now)
        val ni = evs.indexOfFirst { it.at > now }.let { if (it < 2) 2 else it }
        val next = evs[ni]
        val today = Times.today(now)

        if (!compact) {
            v.setTextViewText(R.id.w_place, Prefs.place)
            col(R.id.w_place, true)
        }
        v.setTextViewText(R.id.w_next, next.title)
        col(R.id.w_next, false)
        v.setTextViewText(R.id.w_hijri, if (compact) DateText.compact(now) else DateText.widget(now))
        col(R.id.w_hijri, true)
        v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() + (next.at - now), t("w_in"), true)
        v.setChronometerCountDown(R.id.w_chrono, true)
        col(R.id.w_chrono, true)

        for (i in 0 until 5) {
            val e = evs.getOrNull(ni - 2 + i)
            val bg = when {
                auto -> when (i) {
                    2 -> R.drawable.cell_next_auto
                    1 -> R.drawable.cell_cur_auto
                    else -> R.drawable.cell_auto
                }
                i == 2 -> if (dark) R.drawable.cell_next_dark else R.drawable.cell_next_light
                i == 1 -> if (dark) R.drawable.cell_cur_dark else R.drawable.cell_cur_light
                else -> if (dark) R.drawable.cell_dark else R.drawable.cell_light
            }
            val white = i == 2
            v.setInt(cells[i], "setBackgroundResource", bg)
            v.setTextViewText(names[i], e?.shortTitle ?: "")
            v.setTextViewText(times[i], e?.let { Fmt.time(it.at) } ?: "")
            if (white) {
                v.setTextColor(names[i], Color.WHITE)
                v.setTextColor(times[i], Color.WHITE)
            } else {
                col(names[i], false)
                col(times[i], false)
            }
            if (!compact) {
                val evDate = e?.let { Times.today(it.at) }
                val tag = when {
                    e == null -> ""
                    i == 1 -> t("w_now")
                    i == 2 -> t("w_next")
                    evDate != null && evDate.isAfter(today) -> t("w_tomorrow")
                    evDate != null && evDate.isBefore(today) -> t("w_yesterday")
                    else -> " "
                }
                v.setTextViewText(tags[i], tag)
                if (white) v.setTextColor(tags[i], Color.WHITE) else col(tags[i], false)
            }
        }
        v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
        return v
    }

    fun small(ctx: Context): RemoteViews {
        val now = System.currentTimeMillis()
        val dark = isDark(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_small)
        val auto = Prefs.theme == 0 && Build.VERSION.SDK_INT >= 31
        v.setInt(R.id.s_root, "setBackgroundResource", if (auto) R.drawable.wbg_accent_auto else if (dark) R.drawable.wbg_accent_dark else R.drawable.wbg_accent)
        val next = Times.around(now).first { it.at > now && it.kind.prayer }
        v.setTextViewText(R.id.s_name, next.title)
        v.setTextViewText(R.id.s_time, Fmt.time(next.at))
        v.setChronometer(R.id.s_chrono, SystemClock.elapsedRealtime() + (next.at - now), t("w_in"), true)
        v.setTextViewText(R.id.s_label, t("next_up"))
        v.setChronometerCountDown(R.id.s_chrono, true)
        v.setOnClickPendingIntent(R.id.s_root, openApp(ctx))
        return v
    }
}
