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
 * Renders a shared web page in a WebView and prints it as an image — a
 * print-service-free path for ROMs that block third-party print services.
 * The user sees the page and taps Print when it looks ready.
 */
class WebPrintActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val MAX_CAPTURE_HEIGHT_PX = 20_000
    }

    private lateinit var b: ActivityWebPrintBinding
    private lateinit var prefs: Prefs

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before any WebView is created for full-page capture.
        WebView.enableSlowWholeDocumentDraw()
        super.onCreate(savedInstanceState)
        b = ActivityWebPrintBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }

        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
        }
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            override fun onPageFinished(view: WebView?, url: String?) {
                b.progress.hide()
                b.btnPrintPage.isEnabled = true
            }
        }
        b.btnPrintPage.isEnabled = false
        b.web.loadUrl(url)

        b.btnPrintPage.setOnClickListener { printPage() }
    }

    private fun printPage() {
        val web = b.web
        val srcW = web.width
        if (srcW <= 0 || web.contentHeight <= 0) {
            Toast.makeText(this, R.string.nothing_to_print, Toast.LENGTH_SHORT).show()
            return
        }
        b.btnPrintPage.isEnabled = false
        b.progress.show()

        val dots = prefs.dots
        @Suppress("DEPRECATION")
        val srcH = (web.contentHeight * web.scale).toInt()
            .coerceAtMost(MAX_CAPTURE_HEIGHT_PX)
            .coerceAtLeast(web.height)
        // Draw directly at printer width to keep the bitmap small.
        val scale = dots.toFloat() / srcW
        val bmp = Bitmap.createBitmap(dots, (srcH * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        web.draw(canvas)

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { PrintPipeline(this@WebPrintActivity).printBitmaps(listOf(bmp), dots) }
            }
            bmp.recycle()
            b.progress.hide()
            b.btnPrintPage.isEnabled = true
            r.onSuccess {
                Toast.makeText(this@WebPrintActivity, R.string.status_done, Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure {
                Toast.makeText(this@WebPrintActivity, getString(R.string.status_error, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        (b.web.parent as? ViewGroup)?.removeView(b.web)
        b.web.destroy()
        super.onDestroy()
    }
}
