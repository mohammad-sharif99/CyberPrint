package com.cyberlap.btprint

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent, user-readable event log. Every print-path step writes here so
 * failures on devices without adb can still be diagnosed from the app itself.
 */
object AppLog {
    private const val MAX_BYTES = 400 * 1024L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "cyberprint.log")
    }

    fun file(ctx: Context): File { init(ctx); return file!! }

    @Synchronized
    fun i(tag: String, msg: String) = write("I", tag, msg, null)

    @Synchronized
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        if (level == "E") Log.e(tag, msg, t) else Log.i(tag, msg)
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "cyberprint.log.1")
                old.delete(); f.renameTo(old)
            }
            val sb = StringBuilder()
            sb.append(fmt.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
            if (t != null) {
                val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
                sb.append(sw.toString().lineSequence().take(12).joinToString("\n")).append('\n')
            }
            f.appendText(sb.toString())
        } catch (_: Exception) {}
    }

    fun read(ctx: Context, maxChars: Int = 120_000): String {
        val f = file(ctx)
        val old = File(f.parentFile, "cyberprint.log.1")
        val text = (if (old.exists()) old.readText() else "") + (if (f.exists()) f.readText() else "")
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    fun clear(ctx: Context) {
        val f = file(ctx)
        File(f.parentFile, "cyberprint.log.1").delete()
        f.delete()
    }
}
