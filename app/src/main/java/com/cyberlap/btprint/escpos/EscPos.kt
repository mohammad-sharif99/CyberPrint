package com.cyberlap.btprint.escpos

/** 1-bit-per-pixel image, MSB first, 1 = black. */
class MonoBitmap(val width: Int, val height: Int, val data: ByteArray) {
    val bytesPerRow: Int get() = (width + 7) / 8

    fun rowHasInk(y: Int): Boolean {
        val start = y * bytesPerRow
        for (i in start until start + bytesPerRow) if (data[i].toInt() != 0) return true
        return false
    }

    /** Drop blank rows from the bottom (saves paper) while keeping at least [minRows]. */
    fun trimBottom(minRows: Int = 8): MonoBitmap {
        var last = height - 1
        while (last > minRows && !rowHasInk(last)) last--
        val newH = last + 1
        if (newH == height) return this
        return MonoBitmap(width, newH, data.copyOf(newH * bytesPerRow))
    }
}

/** ESC/POS command builder. All output is raw bytes ready for the printer. */
object EscPos {
    private const val ESC: Byte = 0x1B
    private const val GS: Byte = 0x1D

    val init = byteArrayOf(ESC, '@'.code.toByte())
    fun alignLeft() = byteArrayOf(ESC, 'a'.code.toByte(), 0)
    fun alignCenter() = byteArrayOf(ESC, 'a'.code.toByte(), 1)
    fun feed(lines: Int) = byteArrayOf(ESC, 'd'.code.toByte(), lines.coerceIn(0, 255).toByte())
    fun lineSpacingDefault() = byteArrayOf(ESC, '2'.code.toByte())
    fun lineSpacing(n: Int) = byteArrayOf(ESC, '3'.code.toByte(), n.toByte())
    fun text(s: String) = s.replace("\n", "\r\n").toByteArray(Charsets.US_ASCII)

    /**
     * ESC * m=33 (24-dot double density) bit-image - the legacy command that virtually
     * every ESC/POS printer understands, for firmwares that ignore GS v 0.
     * One chunk per 24-row stripe, each terminated by LF.
     */
    fun bitImage(img: MonoBitmap): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out += lineSpacing(24)
        val w = img.width
        val bpr = img.bytesPerRow
        var y = 0
        while (y < img.height) {
            val chunk = ByteArray(5 + w * 3 + 1)
            chunk[0] = ESC; chunk[1] = '*'.code.toByte(); chunk[2] = 33
            chunk[3] = (w and 0xFF).toByte(); chunk[4] = ((w shr 8) and 0xFF).toByte()
            var o = 5
            for (x in 0 until w) {
                for (k in 0 until 3) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val yy = y + k * 8 + bit
                        if (yy < img.height) {
                            val src = img.data[yy * bpr + (x shr 3)].toInt()
                            if (src and (0x80 ushr (x and 7)) != 0) b = b or (0x80 ushr bit)
                        }
                    }
                    chunk[o++] = b.toByte()
                }
            }
            scrubRealtime(chunk, 5)
            chunk[o] = 0x0A
            out += chunk
            y += 24
        }
        out += lineSpacingDefault()
        return out
    }

    /**
     * Image bytes are arbitrary, so they sometimes contain DLE EOT (10 04),
     * DLE ENQ (10 05) or DLE DC4 (10 14). Many firmwares execute those as
     * real-time commands wherever they appear - DLE ENQ 2 even clears the
     * buffer - which makes prints fail depending on content. Clearing the
     * single pixel that forms the DLE byte is invisible and removes the risk.
     */
    fun scrubRealtime(buf: ByteArray, from: Int) {
        var i = from
        while (i < buf.size - 1) {
            if (buf[i] == 0x10.toByte()) {
                val n = buf[i + 1].toInt()
                if (n == 0x04 || n == 0x05 || n == 0x14) buf[i] = 0
            }
            i++
        }
        if (buf.isNotEmpty() && buf[buf.size - 1] == 0x10.toByte()) buf[buf.size - 1] = 0
    }

    /** GS V 66 n — partial cut with feed (most common). */
    fun cut() = byteArrayOf(GS, 'V'.code.toByte(), 66, 0)

    /** ESC p m t1 t2 — open cash drawer pin 2. */
    fun openDrawer() = byteArrayOf(ESC, 'p'.code.toByte(), 0, 60, 120.toByte())

    /**
     * GS v 0 raster bit image, split into horizontal bands so printers with
     * small buffers do not choke. Each band is a standalone command.
     */
    fun raster(img: MonoBitmap, bandRows: Int = 128): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val bpr = img.bytesPerRow
        var y = 0
        while (y < img.height) {
            val rows = minOf(bandRows, img.height - y)
            val header = byteArrayOf(
                GS, 'v'.code.toByte(), '0'.code.toByte(), 0,
                (bpr and 0xFF).toByte(), ((bpr shr 8) and 0xFF).toByte(),
                (rows and 0xFF).toByte(), ((rows shr 8) and 0xFF).toByte()
            )
            val chunk = ByteArray(header.size + rows * bpr)
            System.arraycopy(header, 0, chunk, 0, header.size)
            System.arraycopy(img.data, y * bpr, chunk, header.size, rows * bpr)
            scrubRealtime(chunk, header.size)
            out.add(chunk)
            y += rows
        }
        return out
    }
}
