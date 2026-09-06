package com.cyberlap.btprint.print

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cyberlap.btprint.MainActivity
import com.cyberlap.btprint.Prefs
import com.cyberlap.btprint.R
import com.cyberlap.btprint.bt.BtPrinter
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * System print service: every paired Bluetooth device shows up as a printer in
 * Chrome / any app's print dialog. Pages are rendered by the OS to PDF, we
 * rasterise them at 203 dpi and stream ESC/POS over RFCOMM.
 */
class BtPrintService : PrintService() {

    companion object {
        private const val TAG = "BtPrintService"
        private const val DPI = 203
        // Printable width in mils (1/1000 in) so the OS lays pages out at the exact dot width.
        private const val W58_MILS = 1890   // 384 dots / 203 dpi
        private const val W80_MILS = 2835   // 576 dots / 203 dpi
        private val HEIGHTS_MM = intArrayOf(100, 150, 200, 300, 500)
        private const val PREFIX_58 = "CP58_"
        private const val PREFIX_80 = "CP80_"
        private const val CHANNEL_ERRORS = "print_errors"

        // Job threads are deliberately NOT tied to the service lifecycle: some OEMs
        // unbind and destroy the print service the moment the print dialog closes,
        // which would otherwise kill the Bluetooth transfer mid-job.
        private val running = ConcurrentHashMap<String, Thread>()
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            // Any exception here makes the OS show "failed to add printers" with no
            // detail, so trap everything and surface it in the app instead.
            try {
                if (!BtPrinter.hasPermission(this@BtPrintService)) {
                    prefs.lastError = "Bluetooth permission missing for print service"
                    return
                }
                val list = buildPrinters()
                if (list.isEmpty()) {
                    prefs.lastError = "No paired Bluetooth printers visible to print service"
                    return
                }
                addPrinters(list)
            } catch (e: Exception) {
                Log.e(TAG, "printer discovery failed", e)
                prefs.lastError = "discovery: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            // Re-assert capabilities for ROMs that only accept them during tracking.
            try {
                val dev = BtPrinter.bondedDevices(this@BtPrintService).firstOrNull { it.address == printerId.localId } ?: return
                val name = try { dev.name ?: dev.address } catch (_: SecurityException) { dev.address }
                addPrinters(listOf(
                    PrinterInfo.Builder(printerId, name, PrinterInfo.STATUS_IDLE)
                        .setDescription("Bluetooth • ${dev.address}")
                        .setCapabilities(capabilities(printerId))
                        .build()
                ))
            } catch (e: Exception) {
                Log.e(TAG, "state tracking failed", e)
                prefs.lastError = "tracking: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }

    @SuppressLint("MissingPermission")
    private fun buildPrinters(): List<PrinterInfo> {
        val out = ArrayList<PrinterInfo>()
        val devices = BtPrinter.bondedDevices(this)
        val preferredMac = prefs.printerMac
        // Preferred printer first so it is the default pick.
        val sorted = devices.sortedBy { if (it.address == preferredMac) 0 else 1 }
        for (d in sorted) {
            val id = generatePrinterId(d.address)
            val name = try { d.name ?: d.address } catch (_: SecurityException) { d.address }
            out += PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
                .setDescription("Bluetooth • ${d.address}")
                .setCapabilities(capabilities(id))
                .build()
        }
        return out
    }

    private fun capabilities(id: PrinterId): PrinterCapabilitiesInfo {
        val b = PrinterCapabilitiesInfo.Builder(id)
        val defaultIs58 = prefs.paperMm <= 58
        val l58 = getString(R.string.media_58_label)
        val l80 = getString(R.string.media_80_label)
        for (hmm in HEIGHTS_MM) {
            val hMils = mmToMils(hmm)
            b.addMediaSize(PrintAttributes.MediaSize("$PREFIX_58$hmm", "$l58 × ${hmm}mm", W58_MILS, hMils), defaultIs58 && hmm == 200)
            b.addMediaSize(PrintAttributes.MediaSize("$PREFIX_80$hmm", "$l80 × ${hmm}mm", W80_MILS, hMils), !defaultIs58 && hmm == 200)
        }
        b.addResolution(PrintAttributes.Resolution("203", "203 dpi", DPI, DPI), true)
        b.setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
        b.setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        return b.build()
    }

    private fun mmToMils(mm: Int): Int = Math.round(mm / 25.4 * 1000).toInt()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        running.remove(printJob.id.toString())?.interrupt()
        if (!printJob.isCancelled && !printJob.isCompleted && !printJob.isFailed) printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!printJob.isQueued) return
        printJob.start()
        val key = printJob.id.toString()
        val mac = printJob.info.printerId?.localId
        val media = printJob.info.attributes.mediaSize
        val dots = if (media != null && media.id.startsWith(PREFIX_58)) Prefs.DOTS_58 else Prefs.DOTS_80
        val jobLabel = printJob.info.label ?: "print job"

        // Pull the document fd now, on the main thread, before it is recycled.
        val pfd: ParcelFileDescriptor? = printJob.document.data
        if (pfd == null || mac == null) {
            printJob.fail("No document data")
            return
        }

        val appCtx = applicationContext
        val thread = Thread({
            var tmp: File? = null
            try {
                val pipeline = PrintPipeline(appCtx)
                tmp = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { pipeline.copyToTemp(it, "job_$key.pdf") }
                val pages = ArrayList<Bitmap>()
                pipeline.renderPdf(tmp, dots) { pages += it }
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val chunks = pipeline.buildJob(pages)
                pages.forEach { it.recycle() }
                pipeline.send(chunks, mac)
                main.post { runCatching { if (!printJob.isCancelled) printJob.complete() } }
            } catch (e: InterruptedException) {
                Log.i(TAG, "job cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "print failed", e)
                val msg = e.message ?: e.javaClass.simpleName
                Prefs(appCtx).lastError = msg
                notifyFailure(appCtx, jobLabel, msg)
                main.post { runCatching { if (!printJob.isCancelled) printJob.fail(msg) } }
            } finally {
                running.remove(key)
                tmp?.delete()
            }
        }, "cyberprint-job-$key")
        running[key] = thread
        thread.start()
    }

    private fun notifyFailure(ctx: Context, jobLabel: CharSequence, msg: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ERRORS, ctx.getString(R.string.notif_channel_errors), NotificationManager.IMPORTANCE_HIGH)
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ERRORS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(ctx.getString(R.string.notif_fail_title))
                .setContentText("$jobLabel: $msg")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$jobLabel: $msg"))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(msg.hashCode(), n)
        } catch (e: Exception) {
            Log.w(TAG, "cannot post notification", e)
        }
    }
}
