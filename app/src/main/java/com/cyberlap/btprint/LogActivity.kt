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
    }

    private fun refresh() {
        val text = AppLog.read(this)
        b.logText.text = text.ifBlank { getString(R.string.log_empty) }
        b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
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
