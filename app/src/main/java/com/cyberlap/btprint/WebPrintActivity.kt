package com.cyberlap.btprint

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cyberlap.btprint.databinding.ActivityWebPrintBinding
import com.cyberlap.btprint.print.PrintPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders a web page (URL or inline HTML) in a WebView and prints it as an
 * image. This path never touches the system print framework, so it works on
 * ROMs that block third-party print services. In [EXTRA_AUTO] mode it prints
 * as soon as the page has loaded and closes itself - used by cyberprint:// links.
 */
class WebPrintActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_HTML = "html"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_WIDTH_MM = "width"
        const val EXTRA_COPIES = "copies"
        private const val MAX_CAPTURE_HEIGHT_PX = 20_000
        /** CSS px per mm at the 96 dpi the web assumes. */
        private const val CSS_PX_PER_MM = 96f / 25.4f
    }

    private lateinit var b: ActivityWebPrintBinding
    private lateinit var prefs: Prefs
    private var auto = false
    private var dots = 576
    private var copies = 1
    private var printed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before any WebView is created for full-page capture.
        WebView.enableSlowWholeDocumentDraw()
        super.onCreate(savedInstanceState)
        b = ActivityWebPrintBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        AppLog.init(this)

        val url = intent.getStringExtra(EXTRA_URL)
        val html = intent.getStringExtra(EXTRA_HTML)
        auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        copies = intent.getIntExtra(EXTRA_COPIES, 1).coerceIn(1, 10)
        val paperMm = intent.getIntExtra(EXTRA_WIDTH_MM, prefs.paperMm)
        dots = Prefs.dotsFor(paperMm)
        if (url.isNullOrBlank() && html.isNullOrBlank()) { finish(); return }
        AppLog.i("WebPrint", "auto=$auto paper=${paperMm}mm copies=$copies url=${url?.take(80)} html=${html?.length ?: 0}B")

        // Lay the page out at the paper's physical width (58 mm -> 219 css px,
        // 80 mm -> 302 css px) so receipt CSS written in mm/px prints 1:1.
        // The WebView spans the full screen width; the zoom is chosen so the CSS
        // viewport equals the paper width exactly (never wider than the screen,
        // which previously left part of the page unrendered on narrow phones).
        val printableMm = if (paperMm <= 58) 48f else 72f
        val cssWidth = (printableMm * CSS_PX_PER_MM).toInt()
        val screenW = resources.displayMetrics.widthPixels
        b.web.setInitialScale(screenW * 100 / cssWidth)
        // Scrollbars would be drawn into the capture (a line at the left in RTL, or at the bottom).
        b.web.isVerticalScrollBarEnabled = false
        b.web.isHorizontalScrollBarEnabled = false
        b.web.overScrollMode = android.view.View.OVER_SCROLL_NEVER

        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            textZoom = 100
        }
        AppLog.i("WebPrint", "viewport css=${cssWidth}px screen=${screenW}px scale=${screenW * 100 / cssWidth}%")
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            override fun onPageFinished(view: WebView?, url: String?) {
                b.progress.hide()
                b.btnPrintPage.isEnabled = true
                if (auto && !printed) {
                    // Give images/fonts a moment to paint before capturing.
                    b.web.postDelayed({ if (!isFinishing) printPage() }, 700)
                }
            }
        }
        b.btnPrintPage.isEnabled = false
        b.btnPrintPage.setOnClickListener { printPage() }

        if (!html.isNullOrBlank()) {
            b.web.loadDataWithBaseURL(url ?: "https://cyberprint.local/", html, "text/html", "utf-8", null)
        } else {
            b.web.loadUrl(url!!)
        }
    }

    private fun printPage() {
        val web = b.web
        val srcW = web.width
        if (srcW <= 0 || web.contentHeight <= 0) {
            Toast.makeText(this, R.string.nothing_to_print, Toast.LENGTH_SHORT).show()
            if (auto) finish()
            return
        }
        printed = true
        b.btnPrintPage.isEnabled = false
        b.progress.show()

        @Suppress("DEPRECATION")
        val srcH = (web.contentHeight * web.scale).toInt()
            .coerceAtMost(MAX_CAPTURE_HEIGHT_PX)
            .coerceAtLeast(1)
        // Draw directly at printer width to keep the bitmap small.
        val scale = dots.toFloat() / srcW
        val bmp = Bitmap.createBitmap(dots, (srcH * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        web.draw(canvas)
        AppLog.i("WebPrint", "captured ${bmp.width}x${bmp.height} from ${srcW}x$srcH")

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val pipeline = PrintPipeline(this@WebPrintActivity)
                    repeat(copies) { pipeline.printBitmaps(listOf(bmp), dots) }
                }
            }
            bmp.recycle()
            b.progress.hide()
            b.btnPrintPage.isEnabled = true
            r.onSuccess {
                Toast.makeText(this@WebPrintActivity, R.string.status_done, Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure {
                AppLog.e("WebPrint", "print failed", it)
                Toast.makeText(this@WebPrintActivity, getString(R.string.status_error, it.message), Toast.LENGTH_LONG).show()
                if (auto) finish()
            }
        }
    }

    override fun onDestroy() {
        (b.web.parent as? ViewGroup)?.removeView(b.web)
        b.web.destroy()
        super.onDestroy()
    }
}
