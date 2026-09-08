package com.cyberlap.btprint.escpos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

object ImageUtils {

    /** Scale to exactly [dots] wide, keep aspect ratio, flatten alpha on white. */
    fun fitToWidth(src: Bitmap, dots: Int): Bitmap {
        val h = maxOf(1, (src.height.toLong() * dots / src.width).toInt())
        val out = Bitmap.createBitmap(dots, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val scaled = if (src.width == dots && src.height == h) src
        else Bitmap.createScaledBitmap(src, dots, h, true)
        c.drawBitmap(scaled, 0f, 0f, null)
        if (scaled !== src) scaled.recycle()
        return out
    }

    /** Shrink the content by [margin] dots on each side and centre it on a white page of the same width. */
    fun inset(src: Bitmap, margin: Int): Bitmap {
        if (margin <= 0 || src.width - 2 * margin < 8) return src
        val innerW = src.width - 2 * margin
        val innerH = maxOf(1, (src.height.toLong() * innerW / src.width).toInt())
        val out = Bitmap.createBitmap(src.width, innerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val scaled = Bitmap.createScaledBitmap(src, innerW, innerH, true)
        c.drawBitmap(scaled, margin.toFloat(), 0f, null)
        scaled.recycle()
        return out
    }

    /**
     * Convert to 1-bit. [threshold] 0..255: pixels darker than it become black.
     * [dither] uses Floyd–Steinberg for photos/greyscale; off gives crisp text.
     */
    fun toMono(bmp: Bitmap, threshold: Int, dither: Boolean): MonoBitmap {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Greyscale with alpha composited on white.
        val grey = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) / 255f
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            grey[i] = lum * a + 255f * (1f - a)
        }

        val bpr = (w + 7) / 8
        val data = ByteArray(bpr * h)
        val thr = threshold.toFloat()

        if (dither) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val old = grey[row + x]
                    val black = old < thr
                    val new = if (black) 0f else 255f
                    val err = old - new
                    if (black) data[y * bpr + (x shr 3)] = (data[y * bpr + (x shr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                    if (x + 1 < w) grey[row + x + 1] += err * 7f / 16f
                    if (y + 1 < h) {
                        if (x > 0) grey[row + w + x - 1] += err * 3f / 16f
                        grey[row + w + x] += err * 5f / 16f
                        if (x + 1 < w) grey[row + w + x + 1] += err * 1f / 16f
                    }
                }
            }
        } else {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (grey[row + x] < thr) {
                        val idx = y * bpr + (x shr 3)
                        data[idx] = (data[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
        }
        return MonoBitmap(w, h, data)
    }
}
