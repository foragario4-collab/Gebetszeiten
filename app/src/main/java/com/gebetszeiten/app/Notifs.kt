package com.gebetszeiten.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle

object Notifs {
    const val CH_PRAYER = "gebet_ton"
    const val CH_QUIET = "gebet_leise"
    const val CH_REMIND = "erinnerung"
    const val CH_LIVE = "live"
    const val ACTION_DONE = "com.gebetszeiten.app.DONE"
    const val LIVE_ID = 50
    const val ADHAN_ID = 60

    private const val ACCENT = 0xFF0B6E5F.toInt()

    fun prayerId(k: Kind) = 100 + k.ordinal
    fun remindId(k: Kind) = 200 + k.ordinal

    /** Eingebaute Töne (res/raw). „system“ = Standard-Ton des Handys. */
    val TONES = listOf("system", "ton_gong", "ton_glocke", "ton_harfe", "ton_tropfen", "ton_morgen", "ton_kristall")

    fun toneUri(ctx: Context, id: String): android.net.Uri =
        if (id == "system") RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        else android.net.Uri.parse("android.resource://${ctx.packageName}/raw/$id")

    /** Kanal je Ton (Android erlaubt keinen Tonwechsel an einem bestehenden Kanal). */
    fun prayerChannel(): String = if (Prefs.tone == "system") CH_PRAYER else CH_PRAYER + "_" + Prefs.tone

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // alte Ton-Kanäle eines anderen Tons entfernen
        nm.notificationChannels.filter { it.id.startsWith(CH_PRAYER + "_") && it.id != prayerChannel() }
            .forEach { nm.deleteNotificationChannel(it.id) }
        val prayer = NotificationChannel(prayerChannel(), t("ch_prayer"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                toneUri(ctx, Prefs.tone),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            )
            enableLights(true)
            lightColor = Color.GREEN
        }
        val quiet = NotificationChannel(CH_QUIET, t("ch_quiet"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
        }
        val remind = NotificationChannel(CH_REMIND, t("ch_remind"), NotificationManager.IMPORTANCE_DEFAULT).apply {
        }
        val live = NotificationChannel(CH_LIVE, t("ch_live"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(prayer, quiet, remind, live))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun doneAction(ctx: Context, id: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            ctx, 1000 + id,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DONE).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, t("n_prayed"), pi).build()
    }

    fun prayerTitle(ev: Ev) = "${ev.title} · ${Fmt.time(ev.at)}"

    fun prayerText(ev: Ev): String = when (ev.kind) {
        Kind.SUNRISE -> t("n_sunrise")
        Kind.LAST_THIRD -> t("n_third")
        Kind.MIDNIGHT -> t("n_midnight")
        else -> t("n_time_for", ev.title)
    }

    fun buildPrayer(ctx: Context, ev: Ev, channel: String): Notification {
        val id = prayerId(ev.kind)
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(ACCENT)
            .setContentTitle(prayerTitle(ev))
            .setContentText(prayerText(ev))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(ev.at)
            .setShowWhen(true)
        if (ev.kind.prayer) b.addAction(doneAction(ctx, id))
        return b.build()
    }

    /** Zur Gebetszeit: Adhan (wenn gewählt und Datei vorhanden) oder Mitteilung mit Ton. */
    fun prayerAlert(ctx: Context, ev: Ev) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(remindId(ev.kind))
        val mode = Prefs.mode(ev.kind)
        val uri = if (ev.kind == Kind.FAJR) (Prefs.fajrAdhanUri ?: Prefs.adhanUri) else Prefs.adhanUri
        if (mode == 2 && uri != null && ev.kind.prayer) {
            if (AdhanService.start(ctx, uri, ev)) return
        }
        nm.notify(prayerId(ev.kind), buildPrayer(ctx, ev, prayerChannel()))
    }

    const val ACTION_MOSQUE = "com.gebetszeiten.app.MOSQUE_REMIND"

    fun mosqueReminder(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        nm.notify(90, n)
    }

    fun reminder(ctx: Context, ev: Ev) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val min = ((ev.at - System.currentTimeMillis() + 30_000L) / 60_000L).coerceAtLeast(1)
        val n = Notification.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(ACCENT)
            .setContentTitle(t("n_in_min", ev.title, min))
            .setContentText(t("n_begins", ev.title, Fmt.time(ev.at)))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setTimeoutAfter((ev.at - System.currentTimeMillis()).coerceAtLeast(60_000L))
            .build()
        nm.notify(remindId(ev.kind), n)
    }

    /**
     * Laufender Countdown. Auf Android 16+ wird zusätzlich die Hervorhebung als
     * „Live-Update“ angefragt (Statusleisten-Chip, oben auf dem Sperrbildschirm).
     */
    /**
     * Ab Android 16: ProgressStyle (Balken auch eingeklappt / auf dem Sperrbildschirm)
     * + kurzer Text für den Statusleisten-Chip. Ältere Versionen: normaler Balken.
     */
    fun applyLiveStyle(b: Notification.Builder, pct: Int, chip: String, color: Int = 0xFF4FD6BC.toInt()) {
        val p = pct.coerceIn(0, 100)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val psCls = Class.forName("android.app.Notification\$ProgressStyle")
                val ps = psCls.getConstructor().newInstance()
                try {
                    val segCls = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
                    val seg = segCls.getConstructor(Int::class.javaPrimitiveType).newInstance(100)
                    try {
                        segCls.getMethod("setColor", Int::class.javaPrimitiveType).invoke(seg, color)
                    } catch (e: Throwable) {
                    }
                    psCls.getMethod("setProgressSegments", java.util.List::class.java).invoke(ps, listOf(seg))
                } catch (e: Throwable) {
                }
                psCls.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(ps, p)
                b.setStyle(ps as Notification.Style)
                try {
                    Notification.Builder::class.java.methods
                        .firstOrNull { it.name == "setShortCriticalText" }
                        ?.invoke(b, chip)
                } catch (e: Throwable) {
                }
                return
            } catch (e: Throwable) {
            }
        }
        b.setProgress(100, p, false)
    }

    /** Kurzer Text für den Chip in der Statusleiste, z. B. „Mit. 2:32“ oder „Asr 18m“. */
    fun chipText(ev: Ev, msLeft: Long): String {
        val name = when (ev.kind) {
            Kind.MIDNIGHT, Kind.LAST_THIRD, Kind.MAGHRIB, Kind.SUNRISE -> t("c_${ev.kind.name}")
            Kind.DHUHR -> if (ev.isJumuah) t("c_JUMUAH") else t("c_DHUHR")
            else -> ev.kind.short
        }
        if (Prefs.chipMode == 1) return "$name ${Fmt.time(ev.at)}"
        val mins = ((msLeft + 59_999L) / 60_000L).coerceAtLeast(0)
        val t = if (mins >= 60) "${mins / 60}:${"%02d".format(mins % 60)}" else "${mins}m"
        return "$name $t"
    }

    /** Baut die Live-Mitteilung (Countdown + Ladebalken). */
    fun buildLive(ctx: Context, ev: Ev, prevAt: Long? = null): Notification {
        val now = System.currentTimeMillis()
        val start = prevAt ?: (ev.at - Prefs.liveMin * 60_000L)
        val total = (ev.at - start).coerceAtLeast(1)
        val done = ((now - start) * 100 / total).toInt().coerceIn(0, 100)
        val extras = Bundle().apply { putBoolean("android.requestPromotedOngoing", true) }
        val minsLeft = ((ev.at - now + 59_999L) / 60_000L).coerceAtLeast(0)
        val isMid = ev.kind == Kind.MIDNIGHT
        val isSun = ev.kind == Kind.SUNRISE
        val col = if (isMid) 0xFF5E5CE6.toInt() else ACCENT
        val b = Notification.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_live_dot)
            .setColor(col)
            .setContentTitle(t("n_at", ev.title, Fmt.time(ev.at)))
            .setContentText(if (isMid) t("n_end_isha") else if (isSun) t("end_fajr") else t("n_countdown"))
            .setSubText(if (isMid) t("n_end_isha") else if (isSun) t("end_fajr") else t("n_next"))
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(ev.at)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setTimeoutAfter((ev.at - now + 60_000L).coerceAtLeast(60_000L))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addExtras(extras)
        val chip = chipText(ev, ev.at - now)
        applyLiveStyle(b, done, chip, if (isMid) 0xFFA5A5FF.toInt() else if (isSun) 0xFFFFC94D.toInt() else 0xFF4FD6BC.toInt())
        return b.build()
    }

    /** Live-Anzeige zeigen: bevorzugt über den Dienst (aktualisiert den Balken laufend). */
    fun live(ctx: Context, ev: Ev, prevAt: Long? = null) {
        if (Prefs.radar) return  // der Live-Radar zeigt alles in einer Mitteilung
        if (!LiveService.start(ctx)) {
            ctx.getSystemService(NotificationManager::class.java)?.notify(LIVE_ID, buildLive(ctx, ev, prevAt))
        }
    }
}
