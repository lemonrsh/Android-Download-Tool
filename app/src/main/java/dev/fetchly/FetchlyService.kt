package dev.fetchly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/** Keeps the process alive while downloads run, and hosts the extension's local server. */
class FetchlyService : Service() {
    private lateinit var server: LocalServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
        server = LocalServer(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(DownloadManager.items.value.filter { it.status == Status.DOWNLOADING }),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        server.start()
        if (watcher == null) {
            watcher = scope.launch {
                DownloadManager.items.collect { list ->
                    val active = list.filter { it.status == Status.DOWNLOADING }
                    if (!Prefs.integration(this@FetchlyService) && DownloadManager.activeCount() == 0) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(active))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun notification(active: List<Item>): Notification {
        val text = if (active.isEmpty()) "Browser integration is on"
        else "${active.size} downloading · ${fmtSpeed(active.sumOf { it.speed })}"
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Fetchly")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        server.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH = "downloads"
        private const val NOTIF_ID = 1
    }
}
