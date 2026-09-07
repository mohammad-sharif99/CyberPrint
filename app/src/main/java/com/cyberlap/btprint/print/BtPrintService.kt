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
import com.cyberlap.btprint.AppLog
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
        /** Fixed virtual printer: always present, resolved to the chosen MAC at print time. */
        const val VIRTUAL_ID = "cyberprint"

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
        AppLog.init(this)
        AppLog.i(TAG, "service created")
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            AppLog.i(TAG, "discovery start (priority=${priorityList.size})")
            JobRunnerService.keepAlive(this@BtPrintService)
            // Any exception here makes the OS show "failed to add printers" with no
            // detail, so trap everything and surface it in the app instead.
            try {
                // The virtual printer needs no Bluetooth access, so it is
                // available instantly even on a cold start where the adapter
                // or bonded-device list is not ready yet.
                // A single fixed printer, built from preferences only (no
                // Bluetooth access), so it is always available - even on a cold
                // start before the adapter is ready. It resolves to the chosen
                // device at print time. This mirrors commercial ESC/POS drivers.
                val list = listOf(virtualPrinter())
                AppLog.i(TAG, "discovery: offering ${list.first().name}")
                addPrinters(list)
            } catch (e: Exception) {
                AppLog.e(TAG, "printer discovery failed", e)
                prefs.lastError = "discovery: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        override fun onStopPrinterDiscovery() { AppLog.i(TAG, "discovery stop") }
        /**
         * The print dialog remembers the last printer and shows it as
         * "not available" until the service confirms it here. Answer at once
         * for every remembered printer that is still paired.
         */
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            AppLog.i(TAG, "validate ${printerIds.joinToString { it.localId }}")
            reassert(printerIds, "validate")
        }
        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            AppLog.i(TAG, "tracking ${printerId.localId}")
            reassert(listOf(printerId), "tracking")
        }
        @SuppressLint("MissingPermission")
        private fun reassert(ids: List<PrinterId>, phase: String) {
            try {
                val bonded = try { BtPrinter.bondedDevices(this@BtPrintService) } catch (_: Exception) { emptyList() }
                val infos = ids.mapNotNull { id ->
                    if (id.localId == VIRTUAL_ID) return@mapNotNull virtualPrinter()
                    val dev = bonded.firstOrNull { it.address == id.localId } ?: return@mapNotNull null
                    val name = try { dev.name ?: dev.address } catch (_: SecurityException) { dev.address }
                    PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
                        .setDescription("Bluetooth • ${dev.address}")
                        .setCapabilities(capabilities(id))
                        .build()
                }
                if (infos.isNotEmpty()) addPrinters(infos)
                AppLog.i(TAG, "$phase: re-added ${infos.size}/${ids.size}")
            } catch (e: Exception) {
                AppLog.e(TAG, "$phase failed", e)
                prefs.lastError = "$phase: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }

    private fun virtualPrinter(): PrinterInfo {
        val id = generatePrinterId(VIRTUAL_ID)
        val name = prefs.printerName?.let { "CyberPrint - $it" } ?: getString(R.string.app_name)
        return PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
            .setDescription(prefs.printerMac?.let { "Bluetooth • $it" } ?: getString(R.string.no_printer))
            .setCapabilities(capabilities(id))
            .build()
    }

    private fun capabilities(id: PrinterId): PrinterCapabilitiesInfo {
        val b = PrinterCapabilitiesInfo.Builder(id)
        val defaultIs58 = prefs.paperMm <= 58
        val l58 = getString(R.string.media_58_label)
        val l80 = getString(R.string.media_80_label)
        for (hmm in HEIGHTS_MM) {
            val hMils = mmToMils(hmm)
            b.addMediaSize(PrintAttributes.MediaSize("$PREFIX_58$hmm", "$l58 × ${hmm}mm", W58_MILS, hMils), defaultIs58 && hmm == 500)
            b.addMediaSize(PrintAttributes.MediaSize("$PREFIX_80$hmm", "$l80 × ${hmm}mm", W80_MILS, hMils), !defaultIs58 && hmm == 500)
        }
        b.addResolution(PrintAttributes.Resolution("203", "203 dpi", DPI, DPI), true)
        b.setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
        b.setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        return b.build()
    }

    private fun mmToMils(mm: Int): Int = Math.round(mm / 25.4 * 1000).toInt()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        AppLog.i(TAG, "cancel requested for ${printJob.id}")
        running.remove(printJob.id.toString())?.interrupt()
        if (!printJob.isCancelled && !printJob.isCompleted && !printJob.isFailed) printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!printJob.isQueued) return
        printJob.start()
        val key = printJob.id.toString()
        val mac = printJob.info.printerId?.localId?.let { if (it == VIRTUAL_ID) prefs.printerMac else it }
        val media = printJob.info.attributes.mediaSize
        val dots = if (media != null && media.id.startsWith(PREFIX_58)) Prefs.DOTS_58 else Prefs.DOTS_80
        val jobLabel = printJob.info.label ?: "print job"

        // Pull the document fd now, on the main thread, before it is recycled.
        val pfd: ParcelFileDescriptor? = printJob.document.data
        AppLog.i(TAG, "job queued id=$key label=$jobLabel printer=$mac media=${media?.id} dots=$dots pages=${printJob.document.info.pageCount} fd=${pfd != null}")
        if (mac == null) {
            AppLog.e(TAG, "job $key: no printer selected in CyberPrint")
            Prefs(this).lastError = getString(R.string.no_printer)
            notifyFailure(applicationContext, jobLabel, getString(R.string.no_printer))
            printJob.fail(getString(R.string.no_printer))
            return
        }
        if (pfd == null) {
            AppLog.e(TAG, "job $key has no data")
            printJob.fail("No document data")
            return
        }

        val appCtx = applicationContext
        val work = Runnable {
            running[key] = Thread.currentThread()
            var tmp: File? = null
            try {
                val pipeline = PrintPipeline(appCtx)
                tmp = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { pipeline.copyToTemp(it, "job_$key.pdf") }
                AppLog.i(TAG, "job $key pdf=${tmp.length()} bytes")
                val pages = ArrayList<Bitmap>()
                pipeline.renderPdf(tmp, dots) { pages += it }
                AppLog.i(TAG, "job $key rendered ${pages.size} page(s)")
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val chunks = pipeline.buildJob(pages)
                pages.forEach { it.recycle() }
                pipeline.send(chunks, mac)
                AppLog.i(TAG, "job $key complete")
                main.post { runCatching { if (!printJob.isCancelled) printJob.complete() } }
            } catch (e: InterruptedException) {
                AppLog.i(TAG, "job $key cancelled")
            } catch (e: Exception) {
                AppLog.e(TAG, "job $key failed", e)
                val msg = e.message ?: e.javaClass.simpleName
                Prefs(appCtx).lastError = msg
                notifyFailure(appCtx, jobLabel, msg)
                main.post { runCatching { if (!printJob.isCancelled) printJob.fail(msg) } }
            } finally {
                running.remove(key)
                tmp?.delete()
            }
        }
        JobRunnerService.enqueue(appCtx, work)
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
