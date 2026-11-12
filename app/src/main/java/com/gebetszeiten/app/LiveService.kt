package com.gebetszeiten.app

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Hält die Live-Anzeige (Countdown + Ladebalken) aktuell:
 * alle 30 Sekunden und sofort beim Einschalten des Bildschirms.
 * Bei ausgeschaltetem Bildschirm schläft er mit dem Handy mit – kaum Akkuverbrauch.
 */
class LiveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val tick = object : Runnable {
        override fun run() {
            if (!refresh()) return
            handler.postDelayed(this, 30_000L)
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handler.removeCallbacks(tick)
            tick.run()
        }
    }

    companion object {
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, LiveService::class.java))
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        Notifs.channels(this)
        val a = Scheduler.currentLive(System.currentTimeMillis())
        // Läuft der Live-Radar, zeigt er schon alles in seiner Mitteilung
        if (a == null || Prefs.radar) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = Notifs.buildLive(this, a.ev!!, a.start)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifs.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifs.LIVE_ID, n)
            }
        } catch (e: Exception) {
            // Falls Android den Dienst nicht erlaubt: wenigstens einmal anzeigen
            getSystemService(NotificationManager::class.java)?.notify(Notifs.LIVE_ID, n)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            try {
                registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
            } catch (e: Exception) {
            }
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 30_000L)
        return START_STICKY
    }

    /** Neu zeichnen; false = nichts mehr anzuzeigen, Dienst beendet sich. */
    private fun refresh(): Boolean {
        val a = Scheduler.currentLive(System.currentTimeMillis())
        if (a == null || !Prefs.live || Prefs.radar) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }
        getSystemService(NotificationManager::class.java)?.notify(Notifs.LIVE_ID, Notifs.buildLive(this, a.ev!!, a.start))
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (started) {
            try {
                unregisterReceiver(screenOn)
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }
}
