package com.cyberlap.btprint

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cyberlap.btprint.print.PrintPipeline
import com.cyberlap.btprint.text.TextRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Deep-link entry point so any web page can print with one tap, bypassing the
 * system print dialog entirely (which some ROMs block for third-party services).
 *
 *   cyberprint://print?html=<base64 or percent-encoded HTML>   render HTML and print
 *   cyberprint://print?url=<page url>                          load page and print
 *   cyberprint://print?pdf=<pdf url>                           download PDF and print
 *   cyberprint://print?text=<text>                             print plain text
 *
 * Optional: &width=58|80  &copies=N  &b64=1 (html/text are base64)
 *
 * From Chrome use an intent: URL so the fallback can point at the download page:
 *   intent://print?html=...#Intent;scheme=cyberprint;package=com.cyberlap.btprint;end
 */
class LinkPrintActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        val uri = intent?.data
        AppLog.i("Link", "received ${uri?.scheme}://${uri?.host}${uri?.path} params=${uri?.queryParameterNames}")
        if (uri == null) { finish(); return }

        val prefs = Prefs(this)
        if (prefs.printerMac == null) {
            Toast.makeText(this, R.string.no_printer, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }

        val b64 = uri.getQueryParameter("b64") == "1"
        val widthMm = uri.getQueryParameter("width")?.toIntOrNull() ?: prefs.paperMm
        val copies = uri.getQueryParameter("copies")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
        val html = uri.getQueryParameter("html")?.let { if (b64 || looksBase64(it)) decode(it) else it }
        val url = uri.getQueryParameter("url")
        val pdf = uri.getQueryParameter("pdf")
        val text = uri.getQueryParameter("text")?.let { if (b64 || looksBase64(it)) decode(it) else it }

        when {
            !html.isNullOrBlank() || !url.isNullOrBlank() -> {
                startActivity(
                    Intent(this, WebPrintActivity::class.java)
                        .putExtra(WebPrintActivity.EXTRA_HTML, html)
                        .putExtra(WebPrintActivity.EXTRA_URL, url)
                        .putExtra(WebPrintActivity.EXTRA_AUTO, true)
                        .putExtra(WebPrintActivity.EXTRA_WIDTH_MM, widthMm)
                        .putExtra(WebPrintActivity.EXTRA_COPIES, copies)
                )
                finish()
            }
            !pdf.isNullOrBlank() -> printPdf(pdf, widthMm, copies)
            !text.isNullOrBlank() -> printText(text, widthMm, copies)
            else -> { Toast.makeText(this, R.string.nothing_to_print, Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    private fun printPdf(pdfUrl: String, widthMm: Int, copies: Int) {
        Toast.makeText(this, R.string.status_printing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val pipeline = PrintPipeline(this@LinkPrintActivity)
                    val conn = URL(pdfUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                    val tmp = conn.inputStream.use { pipeline.copyToTemp(it, "link.pdf") }
                    val dots = Prefs.dotsFor(widthMm)
                    val pages = ArrayList<android.graphics.Bitmap>()
                    pipeline.renderPdf(tmp, dots) { pages += it }
                    tmp.delete()
                    repeat(copies) { pipeline.printBitmaps(pages, dots) }
                    pages.forEach { it.recycle() }
                }
            }
            report(r)
        }
    }

    private fun printText(text: String, widthMm: Int, copies: Int) {
        Toast.makeText(this, R.string.status_printing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dots = Prefs.dotsFor(widthMm)
                    val bmp = TextRenderer.render(text, dots)
                    repeat(copies) { PrintPipeline(this@LinkPrintActivity).printBitmaps(listOf(bmp), dots) }
                    bmp.recycle()
                }
            }
            report(r)
        }
    }

    private fun report(r: Result<Unit>) {
        r.onSuccess { Toast.makeText(this, R.string.status_done, Toast.LENGTH_SHORT).show() }
            .onFailure {
                AppLog.e("Link", "print failed", it)
                Toast.makeText(this, getString(R.string.status_error, it.message), Toast.LENGTH_LONG).show()
            }
        finish()
    }

    private fun looksBase64(s: String) =
        s.length > 16 && !s.contains('<') && !s.contains(' ') && s.matches(Regex("^[A-Za-z0-9+/=_-]+$"))

    private fun decode(s: String): String = try {
        String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    } catch (_: Exception) {
        try { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { s }
    }
}
