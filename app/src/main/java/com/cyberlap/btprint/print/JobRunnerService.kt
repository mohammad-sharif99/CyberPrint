package com.cyberlap.btprint.print

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cyberlap.btprint.R
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Foreground service that executes queued print transfers. Aggressive OEM ROMs
 * freeze background processes mid-Bluetooth-transfer, which delayed jobs until
 * the app was opened and truncated long receipts; a foreground service with a
 * visible notification is exempt from that freezing.
 */
class JobRunnerService : Service() {

    companion object {
        private const val CHANNEL = "print_progress"
        private const val NOTIF_ID = 10
        private val queue = ConcurrentLinkedQueue<Runnable>()

        fun enqueue(ctx: Context, work: Runnable) {
            queue.add(work)
            ContextCompat.startForegroundService(ctx, Intent(ctx, JobRunnerService::class.java))
        }
    }

    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (worker?.isAlive != true) {
            worker = Thread({
                while (true) {
                    val work = queue.poll() ?: break
                    try { work.run() } catch (_: Throwable) {}
                }
                stopSelf()
            }, "cyberprint-runner").also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW)
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.status_printing))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }
}
