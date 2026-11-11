package com.gebetszeiten.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalTime

/**
 * Plant immer nur den nächsten Wecker. Wenn er auslöst, werden alle fälligen
 * Aktionen erledigt und der nächste Wecker gesetzt.
 */
object Scheduler {
    enum class Act { WIDGET, PRAYER, REMINDER, LIVE }

    data class Action(val at: Long, val act: Act, val ev: Ev?, val start: Long = 0L)

    const val ACTION_FIRE = "com.gebetszeiten.app.FIRE"

    fun actions(now: Long): List<Action> {
        val out = ArrayList<Action>()
        val evs = Times.around(now)
        for ((idx, ev) in evs.withIndex()) {
            out.add(Action(ev.at, Act.WIDGET, ev))
            val mode = Prefs.mode(ev.kind)
            if (mode > 0) {
                out.add(Action(ev.at, Act.PRAYER, ev))
                if (ev.kind.prayer && Prefs.reminder > 0) {
                    out.add(Action(ev.at - Prefs.reminder * 60_000L, Act.REMINDER, ev))
                }
            }
            // Live-Countdown: normal X Min vorher. Nach Isha bis Mitternacht, nach Mitternacht bis Fajr.
            val prev = evs.getOrNull(idx - 1)
            val start: Long? = when {
                !Prefs.live -> null
                ev.kind == Kind.MIDNIGHT ->
                    evs.subList(0, idx).lastOrNull { it.kind == Kind.ISHA }?.at
                // Nach Fajr: Countdown bis Sonnenaufgang (Ende der Fajr-Zeit)
                ev.kind == Kind.SUNRISE ->
                    evs.subList(0, idx).lastOrNull { it.kind == Kind.FAJR }?.at
                ev.kind == Kind.FAJR ->
                    evs.subList(0, idx).lastOrNull { it.kind == Kind.MIDNIGHT }?.at
                        ?: (ev.at - Prefs.liveMin * 60_000L)
                // „Immer anzeigen“: ab dem vorherigen Gebet durchgehend
                ev.kind.prayer && Prefs.liveAlways ->
                    evs.subList(0, idx).lastOrNull { it.kind.prayer || it.kind == Kind.SUNRISE }?.at
                ev.kind.prayer -> ev.at - Prefs.liveMin * 60_000L
                else -> null
            }
            if (start != null && prev != null) {
                var t = maxOf(start, prev.at)
                while (t < ev.at) {
                    out.add(Action(t, Act.LIVE, ev, start))
                    t += if (ev.at - t > 3_600_000L) 900_000L else if (ev.at - t > 1_800_000L) 300_000L else 60_000L
                }
            }
        }
        // Tageswechsel: Widget um Mitternacht (Uhr) aktualisieren
        val today = Times.today(now)
        for (i in 0..2) {
            val d = today.plusDays(i.toLong())
            val ms = d.atTime(LocalTime.of(0, 0, 30)).atZone(Times.zone()).toInstant().toEpochMilli()
            out.add(Action(ms, Act.WIDGET, null))
        }
        return out.sortedBy { it.at }
    }

    /** Nach Einstellungsänderungen: nichts Vergangenes nachholen, neu planen. */
    fun reschedule(ctx: Context) {
        Prefs.lastHandled = System.currentTimeMillis()
        if (!Prefs.live) ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(Notifs.LIVE_ID)
        showCurrentLive(ctx)
        schedule(ctx)
        Widgets.updateAll(ctx)
    }

    /** Die gerade aktive Live-Aktion (welches Gebet, ab wann), oder null. */
    fun currentLive(now: Long): Action? {
        if (!Prefs.live) return null
        return actions(now).lastOrNull { it.act == Act.LIVE && it.at <= now && (it.ev?.at ?: 0L) > now }
    }

    /** Live-Anzeige sofort zeigen (z. B. beim Öffnen der App), nicht erst beim nächsten Wecker. */
    fun showCurrentLive(ctx: Context) {
        val a = currentLive(System.currentTimeMillis()) ?: return
        Notifs.live(ctx, a.ev!!, a.start)
    }

    fun schedule(ctx: Context) {
        val now = System.currentTimeMillis()
        val next = actions(now).firstOrNull { it.at > now + 1000 } ?: return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
        }
    }

    fun handle(ctx: Context) {
        val now = System.currentTimeMillis()
        val from = maxOf(Prefs.lastHandled, now - 5 * 60_000L)
        val until = now + 30_000L
        val due = actions(now).filter { it.at > from && it.at <= until }
        Prefs.lastHandled = until

        // Pro Gebet nur die "stärkste" Aktion: Gebet > Erinnerung > Live
        val prayers = due.filter { it.act == Act.PRAYER }.mapNotNull { it.ev }
        prayers.forEach { Notifs.prayerAlert(ctx, it) }
        due.filter { it.act == Act.REMINDER && it.ev !in prayers }
            .mapNotNull { it.ev }
            .forEach { Notifs.reminder(ctx, it) }
        due.filter { it.act == Act.LIVE && it.ev != null }
            .lastOrNull()
            ?.let { a -> val e = a.ev!!; if (e.at > now) Notifs.live(ctx, e, a.start) }

        Widgets.updateAll(ctx)
        schedule(ctx)
    }
}
