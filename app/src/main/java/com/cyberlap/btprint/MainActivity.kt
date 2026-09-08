package com.cyberlap.btprint

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cyberlap.btprint.bt.BtPrinter
import com.cyberlap.btprint.databinding.ActivityMainBinding
import com.cyberlap.btprint.print.JobRunnerService
import com.cyberlap.btprint.print.PrintPipeline
import com.cyberlap.btprint.text.TextRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { !it }) Toast.makeText(this, R.string.need_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        AppLog.init(this)
        AppLog.i("Main", "opened; printer=${prefs.printerMac} paper=${prefs.paperMm} raster=${prefs.rasterMode} slow=${prefs.slowMode} feed=${prefs.feedLines} cut=${prefs.autoCut}")
        ensurePermission()
        bindSettings()
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        AppLog.i("Main", "batteryOptIgnored=${pm.isIgnoringBatteryOptimizations(packageName)} alwaysReady=${prefs.alwaysReady} device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE}")
        if (prefs.alwaysReady) JobRunnerService.startReady(this)

        b.btnChoose.setOnClickListener { choosePrinter() }
        b.btnPair.setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        b.btnEnableService.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        b.btnLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        b.btnAutoStart.setOnClickListener {
            Toast.makeText(this, R.string.autostart_hint, Toast.LENGTH_LONG).show()
            val opened = AutoStart.open(this)
            if (!opened) Toast.makeText(this, R.string.autostart_fallback, Toast.LENGTH_LONG).show()
            requestBatteryExemption()
        }
        b.btnRawTest.setOnClickListener { runPrint { PrintPipeline(this).printRawTest() } }
        b.btnTest.setOnClickListener { printBitmapsAsync { listOf(TextRenderer.render(testReceipt(), prefs.dots)) } }
        b.btnPrintText.setOnClickListener {
            val t = b.quickText.text?.toString().orEmpty()
            if (t.isBlank()) { Toast.makeText(this, R.string.nothing_to_print, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            printBitmapsAsync { listOf(TextRenderer.render(t, prefs.dots)) }
        }
    }

    private fun ensurePermission() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 && !BtPrinter.hasPermission(this)) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, R.string.battery_hint, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
        } catch (e: Exception) {
            AppLog.e("Main", "battery exemption request failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.lastError?.let {
            b.status.text = getString(R.string.status_error, it)
            prefs.lastError = null
        }
    }

    private fun bindSettings() {
        refreshPrinterLabel()
        b.paperGroup.check(if (prefs.paperMm <= 58) R.id.paper58 else R.id.paper80)
        b.paperGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) prefs.paperMm = if (id == R.id.paper58) 58 else 80
        }
        b.rasterGroup.check(if (prefs.rasterMode == 1) R.id.rasterEsc else R.id.rasterGs)
        b.rasterGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) prefs.rasterMode = if (id == R.id.rasterEsc) 1 else 0
        }
        b.darkness.value = prefs.darkness.toFloat()
        b.darkness.addOnChangeListener { _, v, _ -> prefs.darkness = v.toInt() }
        b.margin.value = prefs.marginDots.toFloat()
        b.margin.addOnChangeListener { _, v, _ -> prefs.marginDots = v.toInt() }
        b.feed.value = prefs.feedLines.toFloat()
        b.feed.addOnChangeListener { _, v, _ -> prefs.feedLines = v.toInt() }
        b.swDither.isChecked = prefs.dither
        b.swDither.setOnCheckedChangeListener { _, c -> prefs.dither = c }
        b.swCut.isChecked = prefs.autoCut
        b.swCut.setOnCheckedChangeListener { _, c -> prefs.autoCut = c }
        b.swDrawer.isChecked = prefs.cashDrawer
        b.swDrawer.setOnCheckedChangeListener { _, c -> prefs.cashDrawer = c }
        b.swSlow.isChecked = prefs.slowMode
        b.swSlow.setOnCheckedChangeListener { _, c -> prefs.slowMode = c }
        b.swReady.isChecked = prefs.alwaysReady
        b.swReady.setOnCheckedChangeListener { _, c ->
            prefs.alwaysReady = c
            if (c) { JobRunnerService.startReady(this); requestBatteryExemption() } else JobRunnerService.stopReady(this)
        }
        b.swKeep.isChecked = prefs.keepConnection
        b.swKeep.setOnCheckedChangeListener { _, c -> prefs.keepConnection = c; if (!c) BtPrinter.disconnect() }
        b.swSingle.isChecked = prefs.singleRaster
        b.swSingle.setOnCheckedChangeListener { _, c -> prefs.singleRaster = c }
    }

    private fun refreshPrinterLabel() {
        val mac = prefs.printerMac
        if (mac == null) {
            b.printerName.setText(R.string.no_printer)
            b.printerMac.text = ""
        } else {
            b.printerName.text = prefs.printerName ?: mac
            b.printerMac.text = mac
        }
    }

    @SuppressLint("MissingPermission")
    private fun choosePrinter() {
        if (!BtPrinter.hasPermission(this)) { ensurePermission(); return }
        val devices = BtPrinter.bondedDevices(this)
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.no_bonded, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        val labels = devices.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_printer)
            .setItems(labels) { _, i ->
                prefs.printerMac = devices[i].address
                prefs.printerName = devices[i].name
                BtPrinter.disconnect()
                refreshPrinterLabel()
            }
            .show()
    }

    private fun printBitmapsAsync(make: () -> List<android.graphics.Bitmap>) = runPrint {
        val bmps = make()
        PrintPipeline(this).printBitmaps(bmps, prefs.dots)
        bmps.forEach { it.recycle() }
    }

    private fun runPrint(block: () -> Unit) {
        if (prefs.printerMac == null) { choosePrinter(); return }
        setBusy(true)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            setBusy(false)
            r.onSuccess { b.status.setText(R.string.status_done); AppLog.i("Main", "local print done") }
                .onFailure { b.status.text = getString(R.string.status_error, it.message); AppLog.e("Main", "local print failed", it) }
        }
    }

    private fun setBusy(busy: Boolean) {
        b.btnTest.isEnabled = !busy
        b.btnPrintText.isEnabled = !busy
        b.btnRawTest.isEnabled = !busy
        if (busy) b.status.setText(R.string.status_printing)
    }

    private fun testReceipt(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return buildString {
            appendLine("        CyberPrint — طباعة تجريبية")
            appendLine("------------------------------------")
            appendLine("التاريخ: $now")
            appendLine("الورق: ${prefs.paperMm}mm  (${prefs.dots} نقطة)")
            appendLine("------------------------------------")
            appendLine("الصنف                 الكمية   السعر")
            appendLine("مياه معدنية 1.5 لتر     2      1.50")
            appendLine("عصير برتقال             1      2.25")
            appendLine("بسكويت شوكولاتة         3      0.75")
            appendLine("------------------------------------")
            appendLine("الإجمالي:                       6.00")
            appendLine("------------------------------------")
            appendLine("شكرًا لتعاملكم معنا — Thank you!")
            appendLine("ABC abc 0123456789 ١٢٣٤٥٦٧٨٩٠")
        }
    }
}
