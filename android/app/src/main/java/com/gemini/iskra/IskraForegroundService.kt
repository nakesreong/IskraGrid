package com.gemini.iskra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class IskraForegroundService : Service() {
    private var scheduler: ScheduledExecutorService? = null
    private lateinit var engine: IskraEngine

    companion object {
        const val CHANNEL_ID = "iskra_telemetry_channel"
        const val NOTIF_ID = 2101
    }

    override fun onCreate() {
        super.onCreate()
        engine = IskraEngine.getInstance(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(engine.buildStateSnapshot()))

        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay({
            try {
                val snap = engine.pollAllSync()
                updateNotification(snap)
            } catch (_: Exception) {}
        }, 1, 6, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Искра • Мониторинг Энергосистемы",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Живой статус Oukitel P1000E Plus, генератора Rato и сети ДТЕК"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun formatDur(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}ч ${m}м" else "${m}м"
    }

    private fun buildNotification(snap: JSONObject): Notification {
        val ouk = snap.optJSONObject("oukitel")
        val rato = snap.optJSONObject("rato")
        val sl = snap.optJSONObject("smartLife")

        val batt = ouk?.optInt("batteryPct", 0) ?: 0
        val inW = ouk?.optInt("totalInputW", 0) ?: 0
        val outW = ouk?.optInt("totalOutputW", 0) ?: 0
        val ratoRunning = rato?.optBoolean("running", false) ?: false
        val gridOnline = sl?.optBoolean("gridOnline", false) ?: false
        val darkSec = snap.optLong("darkDurationSec", 0L)
        val genSec = rato?.optInt("uptimeSec", 0)?.toLong() ?: 0L

        val title = when {
            ratoRunning -> "⛽ Rato: ${formatDur(genSec)} • 🔋 Oukitel $batt%"
            gridOnline -> "⚡ Свет ДТЕК есть • 🔋 Oukitel $batt%"
            else -> "🕯️ Тьма: ${formatDur(darkSec)} • 🔋 Oukitel $batt%"
        }

        val text = "Вход: ${inW}Вт | Выход: ${outW}Вт | CO: ${sl?.optJSONObject("co")?.optInt("ppm", 0) ?: 0} ppm"

        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_iskra_logo)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(snap: JSONObject) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(snap))
    }
}
