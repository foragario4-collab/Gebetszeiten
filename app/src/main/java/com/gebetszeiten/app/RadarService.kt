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
            handler.postDelayed(this, 60_000L)
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

    private fun build(near: Pair<Mosque, Float>?): Notification {
        val next = nextPrayer()
        val gpsOff = !Loc.isOn(this)
        val title = when {
            gpsOff -> t("gps_off_title")
            near != null -> "${NameFmt.name(near.first.name)} · ${Mosques.fmtDist(near.second)}"
            else -> t("n_radar_active")
        }
        val iq = near?.first?.iqamah?.get(next.kind.name)
        val text = if (gpsOff) t("gps_off_text")
        else t("n_at", next.title, Fmt.time(next.at)) + (if (iq != null) " · " + t("n_iq", iq) else "")
        val stopPi = PendingIntent.getService(
            this, 9,
            Intent(this, RadarService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val extras = Bundle().apply { putBoolean("android.requestPromotedOngoing", true) }
        val now = System.currentTimeMillis()
        val prev = Times.around(now).lastOrNull { it.at <= now && it.kind.prayer }?.at ?: (next.at - 3_600_000L)
        val pct = ((now - prev) * 100 / (next.at - prev).coerceAtLeast(1)).toInt()
        val minsLeft = ((next.at - now + 59_999L) / 60_000L).coerceAtLeast(0)
        val b = Notification.Builder(this, Notifs.CH_LIVE)
            .setSmallIcon(R.drawable.ic_live_dot)
            .setColor(0xFF0B6E5F.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(t("n_nearest"))
            .also { nb ->
                if (gpsOff) nb.setContentIntent(
                    PendingIntent.getActivity(
                        this, 11,
                        Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            .setContentIntent(Notifs.openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(next.at)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .addAction(Notification.Action.Builder(null, t("n_radar_off"), stopPi).build())
            .addExtras(extras)
        val chip = Notifs.chipText(next, next.at - now)
        Notifs.applyLiveStyle(b, pct, chip)
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
        super.onDestroy()
    }
}
