package com.cyberlap.btprint.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.cyberlap.btprint.AppLog
import com.cyberlap.btprint.Prefs
import com.cyberlap.btprint.bt.BtPrinter
import com.cyberlap.btprint.escpos.EscPos
import com.cyberlap.btprint.escpos.ImageUtils
import com.cyberlap.btprint.escpos.MonoBitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Turns bitmaps / PDFs into ESC/POS and ships them to the configured printer. */
class PrintPipeline(private val ctx: Context) {
    private val prefs = Prefs(ctx)

    /** Render every page of a PDF to a bitmap exactly [dots] wide. */
    fun renderPdf(file: File, dots: Int, onPage: (Bitmap) -> Unit) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val h = maxOf(1, (page.height.toLong() * dots / page.width).toInt())
                        val bmp = Bitmap.createBitmap(dots, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        onPage(bmp)
                    }
                }
            }
        }
    }

    fun copyToTemp(input: InputStream, name: String = "job.pdf"): File {
        val f = File(ctx.cacheDir, name)
        FileOutputStream(f).use { out -> input.copyTo(out) }
        return f
    }

    /** Build a complete ESC/POS job from already-sized bitmaps (each exactly [dots] wide). */
    fun buildJob(bitmaps: List<Bitmap>, trimBlank: Boolean = true): List<ByteArray> {
        val chunks = ArrayList<ByteArray>()
        chunks += EscPos.init
        chunks += EscPos.lineSpacingDefault()
        chunks += EscPos.alignLeft()
        for ((idx, bmp) in bitmaps.withIndex()) {
            var mono: MonoBitmap = ImageUtils.toMono(bmp, prefs.darkness, prefs.dither)
            // Trim trailing whitespace on the last page only; inner pages keep layout.
            if (trimBlank && idx == bitmaps.lastIndex) mono = mono.trimBottom()
            chunks += if (prefs.rasterMode == 1) EscPos.bitImage(mono)
                      else EscPos.raster(mono, bandRows = if (prefs.slowMode) 32 else 64)
        }
        if (prefs.feedLines > 0) chunks += EscPos.feed(prefs.feedLines)
        if (prefs.autoCut) chunks += EscPos.cut()
        if (prefs.cashDrawer) chunks += EscPos.openDrawer()
        AppLog.i("Pipeline", "job built: pages=${bitmaps.size} sizes=${bitmaps.joinToString { "${it.width}x${it.height}" }} raster=${if (prefs.rasterMode == 1) "ESC*" else "GSv0"} dark=${prefs.darkness} dither=${prefs.dither} feed=${prefs.feedLines} cut=${prefs.autoCut} bytes=${chunks.sumOf { it.size }}")
        return chunks
    }

    @Throws(IOException::class)
    fun send(chunks: List<ByteArray>, macOverride: String? = null) {
        val mac = macOverride ?: prefs.printerMac ?: throw IOException("No printer selected")
        val pace = prefs.rasterMode == 0 || prefs.slowMode
        AppLog.i("Pipeline", "send -> $mac chunks=${chunks.size} bytes=${chunks.sumOf { it.size }} pace=$pace slow=${prefs.slowMode}")
        BtPrinter(ctx).print(mac, chunks, if (prefs.slowMode) 25 else 0, pace)
        AppLog.i("Pipeline", "send complete")
    }

    /** Plain-ASCII diagnostic: proves whether the printer speaks ESC/POS at all. */
    @Throws(IOException::class)
    fun printRawTest() {
        val chunks = ArrayList<ByteArray>()
        chunks += EscPos.init
        chunks += EscPos.alignCenter()
        chunks += EscPos.text("CyberPrint RAW TEST\n")
        chunks += EscPos.alignLeft()
        chunks += EscPos.text("ESC/POS OK - 0123456789\nIf you can read this,\nthe printer accepts ESC/POS.\n")
        chunks += EscPos.feed(4)
        if (prefs.autoCut) chunks += EscPos.cut()
        send(chunks)
    }

    /** Convenience: fit arbitrary bitmaps to paper width, build and send. */
    @Throws(IOException::class)
    fun printBitmaps(src: List<Bitmap>, dots: Int = prefs.dots, macOverride: String? = null) {
        if (src.isEmpty()) throw IOException("Nothing to print")
        val sized = src.map { ImageUtils.fitToWidth(it, dots) }
        try {
            send(buildJob(sized), macOverride)
        } finally {
            sized.forEach { if (!src.contains(it)) it.recycle() }
        }
    }
}
