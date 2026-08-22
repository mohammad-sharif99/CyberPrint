package com.cyberlap.btprint.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

/**
 * Renders plain text (Arabic / RTL / mixed) to a bitmap using Android's own text
 * shaping, so ligatures, harakat and bidi are always correct regardless of printer firmware.
 */
object TextRenderer {
    fun render(text: String, dots: Int, textSizePx: Float = 26f, bold: Boolean = false, padding: Int = 8): Bitmap {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textSizePx
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = dots - padding * 2
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setIncludePad(true)
            .setLineSpacing(2f, 1.05f)
            .build()
        val bmp = Bitmap.createBitmap(dots, layout.height + padding * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.translate(padding.toFloat(), padding.toFloat())
        layout.draw(c)
        return bmp
    }
}
