package com.cyberlap.btprint

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.cyberlap.btprint.databinding.ActivityLogBinding

class LogActivity : AppCompatActivity() {

    private lateinit var b: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = getString(R.string.log_title)
        refresh()
        b.btnShare.setOnClickListener { share() }
        b.btnClear.setOnClickListener { AppLog.clear(this); refresh() }
        b.btnRefresh.setOnClickListener { refresh() }
        b.btnCapture.setOnClickListener { shareCapture() }
    }

    private fun refresh() {
        val text = AppLog.read(this)
        b.logText.text = text.ifBlank { getString(R.string.log_empty) }
        b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun shareCapture() {
        val f = java.io.File(filesDir, "last_capture.png")
        if (!f.exists()) { android.widget.Toast.makeText(this, R.string.log_empty, android.widget.Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.log_capture)))
    }

    private fun share() {
        val f = AppLog.file(this)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "CyberPrint log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, getString(R.string.log_share)))
    }
}
