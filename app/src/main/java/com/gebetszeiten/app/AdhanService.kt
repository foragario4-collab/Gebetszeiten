package com.gebetszeiten.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder

/** Spielt den Adhan als Vordergrund-Dienst ab (mit „Adhan stoppen“-Knopf). */
class AdhanService : Service() {

    private var player: MediaPlayer? = null
    private var title: String = "Adhan"
    private var text: String = ""
    private var kindOrdinal: Int = -1

    companion object {
        const val ACTION_STOP = "com.gebetszeiten.app.STOP_ADHAN"

        fun start(ctx: Context, uri: String, ev: Ev?): Boolean = try {
            val i = Intent(ctx, AdhanService::class.java)
                .putExtra("uri", uri)
                .putExtra("title", ev?.let { Notifs.prayerTitle(it) } ?: t("n_adhan_test"))
                .putExtra("text", ev?.let { Notifs.prayerText(it) } ?: t("n_adhan_test_text"))
                .putExtra("kind", ev?.kind?.ordinal ?: -1)
            ctx.startForegroundService(i)
            true
        } catch (e: Exception) {
            false
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AdhanService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        Notifs.channels(this)
        if (intent?.action == ACTION_STOP) {
            finishPlayback()
            return START_NOT_STICKY
        }
        title = intent?.getStringExtra("title") ?: "Adhan"
        text = intent?.getStringExtra("text") ?: ""
        kindOrdinal = intent?.getIntExtra("kind", -1) ?: -1
        startForegroundCompat(buildNotif(true))

        player?.release()
        player = null
        val uri = intent?.getStringExtra("uri")
        if (uri == null) {
            finishPlayback()
            return START_NOT_STICKY
        }
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@AdhanService, Uri.parse(uri))
                setOnCompletionListener { finishPlayback() }
                setOnErrorListener { _, _, _ -> finishPlayback(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            finishPlayback()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifs.ADHAN_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifs.ADHAN_ID, n)
        }
    }

    private fun buildNotif(playing: Boolean = true): Notification {
        val stopPi = PendingIntent.getService(
            this, 7,
            Intent(this, AdhanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, Notifs.CH_QUIET)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF0B6E5F.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_ALARM)
        if (playing) b.addAction(Notification.Action.Builder(null, t("n_stop_adhan"), stopPi).build())
        return b.build()
    }

    private fun finishPlayback() {
        player?.release()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Normale (leise) Mitteilung stehen lassen
        if (kindOrdinal >= 0) {
            val kind = Kind.entries[kindOrdinal]
            val nm = getSystemService(NotificationManager::class.java)
            val id = Notifs.prayerId(kind)
            val b = Notification.Builder(this, Notifs.CH_QUIET)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF0B6E5F.toInt())
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(Notifs.doneAction(this, id))
            nm?.notify(id, b.build())
        }
        kindOrdinal = -1
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
