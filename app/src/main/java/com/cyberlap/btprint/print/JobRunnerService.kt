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
import com.cyberlap.btprint.AppLog
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
        private const val ACTION_KEEPALIVE = "keepalive"
        private const val ACTION_READY = "ready"
        private const val ACTION_STOP_READY = "stop_ready"
        private const val KEEPALIVE_MS = 120_000L
        @Volatile private var readyMode = false

        /**
         * Always-ready mode: some ROMs (Tecno/Infinix HiOS, MIUI...) refuse to
         * spawn a third-party print service's process for the system print
         * dialog unless the app is already running, so the printer shows as
         * "not available" until the user opens the app. A persistent, silent
         * foreground service keeps the process alive so binding is instant.
         */
        fun startReady(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, JobRunnerService::class.java).setAction(ACTION_READY))
            } catch (e: Exception) {
                AppLog.e("Runner", "ready mode refused: ${e.message}")
            }
        }

        fun stopReady(ctx: Context) {
            readyMode = false
            try { ctx.startService(Intent(ctx, JobRunnerService::class.java).setAction(ACTION_STOP_READY)) } catch (_: Exception) {}
        }

        fun enqueue(ctx: Context, work: Runnable) {
            queue.add(work)
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, JobRunnerService::class.java))
            } catch (e: Exception) {
                AppLog.e("Runner", "cannot start foreground service, running inline", e)
                Thread(work, "cyberprint-fallback").start()
            }
        }

        /** Keep the process un-frozen while the system print dialog is open. */
        fun keepAlive(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, JobRunnerService::class.java).setAction(ACTION_KEEPALIVE))
            } catch (e: Exception) {
                AppLog.e("Runner", "keepalive refused: ${e.message}")
            }
        }
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var keepUntil = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_READY) {
            readyMode = false
            handler.post { stopIfIdle() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_READY) {
            readyMode = true
            AppLog.i("Runner", "always-ready mode on (pid=${android.os.Process.myPid()})")
            handler.removeCallbacks(heartbeat)
            handler.postDelayed(heartbeat, 60_000)
        }
        startInForeground()
        if (intent?.action == ACTION_KEEPALIVE) {
            keepUntil = System.currentTimeMillis() + KEEPALIVE_MS
            AppLog.i("Runner", "keepalive for ${KEEPALIVE_MS / 1000}s")
            handler.postDelayed({ stopIfIdle() }, KEEPALIVE_MS + 500)
        }
        if (queue.isNotEmpty() && worker?.isAlive != true) {
            worker = Thread({
                while (true) {
                    val work = queue.poll() ?: break
                    try { work.run() } catch (t: Throwable) { AppLog.e("Runner", "job crashed", t) }
                }
                handler.post { stopIfIdle() }
            }, "cyberprint-runner").also { it.start() }
        }
        return if (readyMode) START_STICKY else START_NOT_STICKY
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!readyMode) return
            AppLog.i("Runner", "alive (pid=${android.os.Process.myPid()})")
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onDestroy() {
        AppLog.i("Runner", "service destroyed (readyMode=$readyMode)")
        handler.removeCallbacks(heartbeat)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("Runner", "task removed (app swiped away)")
        if (readyMode) startReady(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    private fun stopIfIdle() {
        if (readyMode) { startInForeground(); return }
        val busy = worker?.isAlive == true || queue.isNotEmpty()
        if (!busy && System.currentTimeMillis() >= keepUntil) {
            AppLog.i("Runner", "idle, stopping")
            stopSelf()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_MIN)
        )
        val busy = worker?.isAlive == true || queue.isNotEmpty()
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (busy) android.R.drawable.stat_sys_upload else android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(if (busy) R.string.status_printing else R.string.ready_notif))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }
}
