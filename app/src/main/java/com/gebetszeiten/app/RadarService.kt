package com.gebetszeiten.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper

/**
 * Moschee-Radar: läuft im Vordergrund, solange er eingeschaltet ist.
 * Zeigt live die nächste Moschee + nächstes Gebet (auch auf dem Sperrbildschirm)
 * und meldet sich, wenn man nah an einer Moschee ist und bald gebetet wird.
 */
class RadarService : Service(), LocationListener {

    private var lm: LocationManager? = null
    private var lastLoc: Location? = null
    private val handler = android.os.Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            lastLoc?.let { update(it) }
                ?: getSystemService(NotificationManager::class.java)?.notify(ID, build(null))
            handler.postDelayed(this, 30_000L)
        }
    }

    companion object {
        const val ACTION_STOP = "com.gebetszeiten.app.STOP_RADAR"
        const val ID = 70
        const val ALERT_ID = 80

        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, RadarService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RadarService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        Notifs.channels(this)
        if (intent?.action != ACTION_STOP) {
            try { stopService(Intent(this, LiveService::class.java)) } catch (e: Exception) { }
        }
        if (intent?.action == ACTION_STOP) {
            Prefs.radar = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLocPermission(this)) {
            Prefs.radar = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, build(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID, build(null))
            }
        } catch (e: Exception) {
            Prefs.radar = false
            stopSelf()
            return START_NOT_STICKY
        }
        startUpdates()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 60_000L)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (lm != null) return
        val m = getSystemService(LocationManager::class.java) ?: return
        lm = m
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            try {
                // auch ausgeschaltete Quellen anmelden – sie liefern, sobald GPS wieder an ist
                m.requestLocationUpdates(p, 30_000L, 50f, this, Looper.getMainLooper())
            } catch (e: Exception) {
            }
        }
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) onLocationChanged(last)
    }

    override fun onProviderEnabled(provider: String) {
        getSystemService(NotificationManager::class.java)?.notify(ID, build(lastLoc?.let { Mosques.nearest(this, it.latitude, it.longitude) }))
    }

    override fun onProviderDisabled(provider: String) {
        getSystemService(NotificationManager::class.java)?.notify(ID, build(null))
    }

    override fun onLocationChanged(loc: Location) {
        lastLoc = loc
        if (Mosques.needsRefresh(this, loc.latitude, loc.longitude) && !Mosques.loading) {
            Mosques.refresh(this, loc.latitude, loc.longitude) { lastLoc?.let { update(it) } }
        }
        if (Mosques.mawaqitNeedsRefresh(this, loc.latitude, loc.longitude) && !Mosques.mwLoading) {
            Mosques.refreshMawaqit(this, loc.latitude, loc.longitude) { lastLoc?.let { update(it) } }
        }
        update(loc)
    }

    private fun update(loc: Location) {
        val near = Mosques.nearest(this, loc.latitude, loc.longitude)
        getSystemService(NotificationManager::class.java)?.notify(ID, build(near))
        maybeAlert(near)
    }

    private fun nextPrayer(): Ev {
        val now = System.currentTimeMillis()
        return Times.around(now).first { it.at > now && it.kind.prayer }
    }

    /**
     * Eine Mitteilung für alles: laufendes Zeitfenster mit Balken (Fajr bis Shuruq, Dhuhr bis Asr …),
     * nächstes Gebet der nächsten Moschee mit Adhan, Iqamah, Fahrzeit und „ca. vor/nach Iqamah“.
     * Hauptknopf „Route“; ausgeschaltet wird der Radar in der App.
     */
    private fun build(near: Pair<Mosque, Float>?): Notification {
        val now = System.currentTimeMillis()
        val evs = Times.around(now)
        val chain = evs.filter { it.kind.prayer || it.kind == Kind.SUNRISE }
        val next = chain.firstOrNull { it.at > now } ?: nextPrayer()
        val prev = chain.lastOrNull { it.at <= now }
        val prevAt = prev?.at ?: (next.at - 3_600_000L)
        val pct = ((now - prevAt) * 100 / (next.at - prevAt).coerceAtLeast(1)).toInt().coerceIn(0, 100)
        val gpsOff = !Loc.isOn(this)
        val period = t("period_running", prev?.title ?: next.title, next.title, Fmt.time(next.at))

        var title = t("n_radar_active")
        var line1 = period
        val big = StringBuilder()
        var routeUri: android.net.Uri? = null
        if (gpsOff) {
            title = t("gps_off_title")
            line1 = t("gps_off_text")
            big.append(line1)
        } else if (near != null) {
            val (m, d) = near
            val travel = travelMin(d, Prefs.travelMode)
            title = "${NameFmt.name(m.name)} · ${Mosques.fmtDist(d)} · ${Fmt.duration(travel * 60_000L)}"
            val arr = now + travel * 60_000L
            val niq = nextIqamah(m, evs, now, arr)
            val p = niq?.first ?: evs.firstOrNull { it.at > now && it.kind.prayer } ?: next
            val adhanTxt = niq?.second ?: Fmt.time(p.at)
            val adhanMs = try {
                val parts = adhanTxt.split(":")
                p.day.atTime(parts[0].toInt(), parts[1].toInt()).atZone(Times.zone()).toInstant().toEpochMilli()
            } catch (e: Exception) {
                p.at
            }
            line1 = if (niq != null) "${p.title} $adhanTxt · ${t("iqamah")} ${Fmt.time(niq.third)}"
            else "${p.title} $adhanTxt"
            big.append(period).append('\n')
            if (niq != null) {
                big.append(
                    t(
                        "radar_next_line", p.title, adhanTxt, Fmt.duration((adhanMs - now).coerceAtLeast(0)),
                        Fmt.time(niq.third), Fmt.duration((niq.third - now).coerceAtLeast(0))
                    )
                ).append('\n')
                val diff = (niq.third - arr) / 60_000L
                big.append(t("arrival", Fmt.time(arr))).append(" · ")
                    .append(if (diff >= 0) t("diff_before", Fmt.duration(diff * 60_000L)) else t("diff_after", Fmt.duration(-diff * 60_000L)))
            } else {
                big.append("${p.title} $adhanTxt · ${t("in_time", Fmt.duration((adhanMs - now).coerceAtLeast(0)))}").append('\n')
                    .append(t("arrival", Fmt.time(arr)))
            }
            routeUri = android.net.Uri.parse("geo:${m.lat},${m.lng}?q=${m.lat},${m.lng}(${android.net.Uri.encode(NameFmt.name(m.name))})")
        } else {
            big.append(period)
        }

        val extras = Bundle().apply { putBoolean("android.requestPromotedOngoing", true) }
        val b = Notification.Builder(this, Notifs.CH_LIVE)
            .setSmallIcon(R.drawable.ic_live_dot)
            .setColor(0xFF0B6E5F.toInt())
            .setContentTitle(title)
            .setContentText(line1)
            .setStyle(Notification.BigTextStyle().bigText(big.toString()))
            .setContentIntent(
                if (gpsOff) PendingIntent.getActivity(
                    this, 11, Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ) else Notifs.openApp(this)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(next.at)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .addExtras(extras)
        routeUri?.let { uri ->
            val routePi = PendingIntent.getActivity(
                this, 12, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(null, t("route"), routePi).build())
        }
        val mapPi = PendingIntent.getActivity(
            this, 13,
            Intent(this, MainActivity::class.java).putExtra("openTab", 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        b.addAction(Notification.Action.Builder(null, t("other_mosque"), mapPi).build())
        Notifs.applyLiveStyle(b, pct, Notifs.chipText(next, next.at - now))
        return b.build()
    }

    private fun maybeAlert(near: Pair<Mosque, Float>?) {
        if (near == null) return
        val (m, d) = near
        if (d > Prefs.radarRadius) return
        val now = System.currentTimeMillis()
        val next = nextPrayer()
        val mins = (next.at - now) / 60_000L
        if (mins < 0 || mins > 30) return
        val key = "${m.id}@${next.at}"
        if (Prefs.radarAlertKey == key) return
        Prefs.radarAlertKey = key
        val iq = m.iqamah[next.kind.name]
        val n = Notification.Builder(this, Notifs.CH_REMIND)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF0B6E5F.toInt())
            .setContentTitle(t("n_away", m.name, Mosques.fmtDist(d)))
            .setContentText(
                t("n_in_min_at", next.title, mins, Fmt.time(next.at)) + (if (iq != null) " · " + t("n_iq", iq) else "")
            )
            .setContentIntent(Notifs.openApp(this))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(ALERT_ID, n)
    }

    @Deprecated("Für alte Android-Versionen")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            lm?.removeUpdates(this)
        } catch (e: Exception) {
        }
        lm = null
        getSystemService(NotificationManager::class.java)?.cancel(ID)
        // Radar aus → normale Live-Anzeige übernimmt wieder
        try { Scheduler.showCurrentLive(this) } catch (e: Exception) { }
        super.onDestroy()
    }
}
