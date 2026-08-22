package com.cyberlap.btprint.print

import android.annotation.SuppressLint
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
import com.cyberlap.btprint.Prefs
import com.cyberlap.btprint.R
import com.cyberlap.btprint.bt.BtPrinter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = ConcurrentHashMap<String, Future<*>>()
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            val list = buildPrinters()
            if (list.isNotEmpty()) addPrinters(list)
        }
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) {}
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
        running.remove(printJob.id.toString())?.cancel(true)
        if (!printJob.isCancelled && !printJob.isCompleted && !printJob.isFailed) printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!printJob.isQueued) return
        printJob.start()
        val key = printJob.id.toString()
        val mac = printJob.info.printerId?.localId
        val media = printJob.info.attributes.mediaSize
        val dots = if (media != null && media.id.startsWith(PREFIX_58)) Prefs.DOTS_58 else Prefs.DOTS_80

        // Pull the document fd now, on the main thread, before it is recycled.
        val pfd: ParcelFileDescriptor? = printJob.document.data
        if (pfd == null || mac == null) {
            printJob.fail("No document data")
            return
        }

        val future = executor.submit {
            var tmp: File? = null
            try {
                val pipeline = PrintPipeline(this)
                tmp = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { pipeline.copyToTemp(it, "job_$key.pdf") }
                val pages = ArrayList<Bitmap>()
                pipeline.renderPdf(tmp, dots) { pages += it }
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val chunks = pipeline.buildJob(pages)
                pages.forEach { it.recycle() }
                pipeline.send(chunks, mac)
                main.post { if (!printJob.isCancelled) printJob.complete() }
            } catch (e: InterruptedException) {
                Log.i(TAG, "job cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "print failed", e)
                main.post { if (!printJob.isCancelled) printJob.fail(e.message ?: e.javaClass.simpleName) }
            } finally {
                running.remove(key)
                tmp?.delete()
            }
        }
        running[key] = future
    }
}
