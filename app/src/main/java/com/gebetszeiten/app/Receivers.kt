package com.gebetszeiten.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        Notifs.channels(ctx)
        when (intent.action) {
            Scheduler.ACTION_FIRE -> Scheduler.handle(ctx)
            Notifs.ACTION_MOSQUE -> Notifs.mosqueReminder(
                ctx,
                intent.getStringExtra("title") ?: t("prayer"),
                intent.getStringExtra("text") ?: ""
            )
            Notifs.ACTION_DONE -> {
                val id = intent.getIntExtra("id", 0)
                ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        Notifs.channels(ctx)
        Scheduler.reschedule(ctx)
        // Moschee-Radar nach Neustart wieder aufnehmen (falls Android es erlaubt)
        if (Prefs.radar && hasLocPermission(ctx)) {
            if (!RadarService.start(ctx)) Prefs.radar = false
        }
    }
}
