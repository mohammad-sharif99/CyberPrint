package com.cyberlap.btprint

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cyberlap.btprint.print.PrintPipeline
import com.cyberlap.btprint.text.TextRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives text / images / PDFs from the share sheet and prints them. */
class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        if (prefs.printerMac == null) {
            Toast.makeText(this, R.string.no_printer, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }
        Toast.makeText(this, R.string.status_printing, Toast.LENGTH_SHORT).show()
        val incoming = intent
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { handle(incoming, prefs.dots) }
            }
            result.onSuccess { printed ->
                if (printed) Toast.makeText(this@ShareActivity, R.string.status_done, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@ShareActivity, getString(R.string.status_error, it.message), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    /** @return true when something was printed here (false = routed elsewhere). */
    private fun handle(intent: Intent, dots: Int): Boolean {
        val pipeline = PrintPipeline(this)
        val bitmaps = ArrayList<Bitmap>()
        val uris = ArrayList<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
                if (uris.isEmpty()) {
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    val url = text?.let { extractUrl(it) }
                    if (url != null) {
                        // Chrome shares the page URL; render and print the page itself.
                        startActivity(
                            Intent(this, WebPrintActivity::class.java)
                                .putExtra(WebPrintActivity.EXTRA_URL, url)
                        )
                        return false
                    }
                    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    val full = listOfNotNull(subject, text).joinToString("\n\n")
                    if (full.isNotBlank()) bitmaps += TextRenderer.render(full, dots)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            }
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
        }

        for (uri in uris) {
            val type = contentResolver.getType(uri) ?: guessType(uri)
            if (type == "application/pdf") {
                val tmp = contentResolver.openInputStream(uri)?.use { pipeline.copyToTemp(it, "share.pdf") }
                    ?: throw IllegalStateException("Cannot open PDF")
                pipeline.renderPdf(tmp, dots) { bitmaps += it }
                tmp.delete()
            } else if (type.startsWith("image/")) {
                bitmaps += decodeImage(uri)
            } else if (type.startsWith("text/")) {
                val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (txt.isNotBlank()) bitmaps += TextRenderer.render(txt, dots)
            }
        }

        if (bitmaps.isEmpty()) throw IllegalStateException(getString(R.string.nothing_to_print))
        pipeline.printBitmaps(bitmaps, dots)
        bitmaps.forEach { it.recycle() }
        return true
    }

    /** Returns the URL when the shared text is (or contains only) a web link. */
    private fun extractUrl(text: String): String? {
        val t = text.trim()
        val m = Regex("https?://\\S+").find(t) ?: return null
        // Treat as a page share only when the text is essentially just the link
        // (Chrome sends "title\nurl" or the bare url).
        val rest = t.removeRange(m.range).trim()
        return if (rest.length <= 120 && !rest.contains(Regex("https?://"))) m.value else null
    }

    private fun guessType(uri: Uri): String {
        val s = uri.toString().lowercase()
        return when {
            s.endsWith(".pdf") -> "application/pdf"
            s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".webp") || s.endsWith(".bmp") -> "image/*"
            else -> "text/plain"
        }
    }

    private fun decodeImage(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        }
    }
}
