package com.cyberlap.btprint

import android.content.Context
import androidx.core.content.edit

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cyberprint", Context.MODE_PRIVATE)

    var printerMac: String?
        get() = sp.getString("mac", null)
        set(v) = sp.edit { putString("mac", v) }

    var printerName: String?
        get() = sp.getString("name", null)
        set(v) = sp.edit { putString("name", v) }

    /** 58 or 80 */
    var paperMm: Int
        get() = sp.getInt("paper", 80)
        set(v) = sp.edit { putInt("paper", v) }

    val dots: Int get() = dotsFor(paperMm)

    /** 0..255 threshold; higher = darker output. Default 160. */
    var darkness: Int
        get() = sp.getInt("dark", 160)
        set(v) = sp.edit { putInt("dark", v.coerceIn(40, 230)) }

    var feedLines: Int
        get() = sp.getInt("feed", 5)
        set(v) = sp.edit { putInt("feed", v.coerceIn(0, 12)) }

    var dither: Boolean
        get() = sp.getBoolean("dither", false)
        set(v) = sp.edit { putBoolean("dither", v) }

    var autoCut: Boolean
        get() = sp.getBoolean("cut", true)
        set(v) = sp.edit { putBoolean("cut", v) }

    var cashDrawer: Boolean
        get() = sp.getBoolean("drawer", false)
        set(v) = sp.edit { putBoolean("drawer", v) }

    var slowMode: Boolean
        get() = sp.getBoolean("slow", false)
        set(v) = sp.edit { putBoolean("slow", v) }

    /** Keep the Bluetooth link open between jobs (closed after 60 s idle). */
    var keepConnection: Boolean
        get() = sp.getBoolean("keepConn", true)
        set(v) = sp.edit { putBoolean("keepConn", v) }

    /** Send the whole page as ONE GS v 0 command instead of horizontal bands. */
    var singleRaster: Boolean
        get() = sp.getBoolean("singleRaster", true)
        set(v) = sp.edit { putBoolean("singleRaster", v) }

    /** Last print-service failure, surfaced in MainActivity. */
    var lastError: String?
        get() = sp.getString("lastError", null)
        set(v) = sp.edit { putString("lastError", v) }

    /** 0 = GS v 0 raster (modern), 1 = ESC * bit-image (legacy/compatible). */
    var rasterMode: Int
        get() = sp.getInt("raster", 0)
        set(v) = sp.edit { putInt("raster", v) }

    companion object {
        const val DOTS_58 = 384
        const val DOTS_80 = 576
        fun dotsFor(mm: Int) = if (mm <= 58) DOTS_58 else DOTS_80
    }
}
