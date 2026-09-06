package com.cyberlap.btprint.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.cyberlap.btprint.AppLog
import java.io.IOException
import java.util.UUID

/** Raw SPP (RFCOMM) transport to a Bluetooth ESC/POS printer. */
class BtPrinter(private val ctx: Context) {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val WRITE_CHUNK = 512
        private const val IDLE_CLOSE_MS = 60_000L

        fun hasPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun adapter(ctx: Context): BluetoothAdapter? =
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        @SuppressLint("MissingPermission")
        fun bondedDevices(ctx: Context): List<BluetoothDevice> {
            if (!hasPermission(ctx)) return emptyList()
            val a = adapter(ctx) ?: return emptyList()
            return try { a.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }
        }

        // ---- persistent connection (one printer at a time) ----
        private val lock = Any()
        private var held: Held? = null
        private val main = Handler(Looper.getMainLooper())
        private val idleCloser = Runnable { synchronized(lock) { closeHeld("idle") } }

        private class Held(val mac: String, val socket: BluetoothSocket) {
            @Volatile var alive = true
            // Mirrors what the printer sends back and, more importantly, notices
            // a remote hang-up (read returns -1 / throws) before the next job
            // silently writes into a dead socket.
            val reader = Thread({
                val buf = ByteArray(64)
                try {
                    val input = socket.inputStream
                    while (alive) {
                        val n = input.read(buf)
                        if (n < 0) break
                    }
                } catch (_: Exception) {}
                if (alive) AppLog.i("BT", "printer closed the link")
                alive = false
            }, "cyberprint-bt-reader").apply { isDaemon = true; start() }
        }

        private fun closeHeld(why: String) {
            val h = held ?: return
            held = null
            h.alive = false
            try { h.socket.close() } catch (_: Exception) {}
            AppLog.i("BT", "connection closed ($why)")
        }

        /** Drop any cached connection, e.g. when the user picks another printer. */
        fun disconnect() = synchronized(lock) { main.removeCallbacks(idleCloser); closeHeld("manual") }
    }

    /**
     * Streams every chunk in order. Blocking — call off the main thread.
     * [interChunkDelayMs] > 0 selects slow mode (throttled writes, long drain).
     * [pace] throttles writes to roughly print speed.
     * [keepOpen] leaves the link up for the next job (closed after 60 s idle).
     */
    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun print(mac: String, chunks: List<ByteArray>, interChunkDelayMs: Long = 0, pace: Boolean = false, keepOpen: Boolean = true) {
        if (!hasPermission(ctx)) throw IOException("Bluetooth permission not granted")
        val adapter = adapter(ctx) ?: throw IOException("Bluetooth not available")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        try { adapter.cancelDiscovery() } catch (_: Exception) {}

        synchronized(lock) {
            main.removeCallbacks(idleCloser)
            var h = acquire(adapter, mac)
            try {
                send(h.socket, chunks, interChunkDelayMs, pace)
            } catch (e: IOException) {
                // Stale cached link (printer rebooted, went out of range...): reconnect once and resend.
                AppLog.e("BT", "write failed on ${if (h === held) "cached" else "fresh"} link (${e.message}); reconnecting")
                closeHeld("write error")
                h = acquire(adapter, mac)
                send(h.socket, chunks, interChunkDelayMs, pace)
            }
            if (keepOpen) {
                Thread.sleep(300)
                main.postDelayed(idleCloser, IDLE_CLOSE_MS)
            } else {
                drain(h.socket, chunks.sumOf { it.size }, interChunkDelayMs)
                closeHeld("job done")
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    private fun acquire(adapter: BluetoothAdapter, mac: String): Held {
        held?.let { h ->
            if (h.mac == mac && h.alive && h.socket.isConnected) {
                AppLog.i("BT", "reusing open connection to $mac")
                return h
            }
            closeHeld(if (h.mac != mac) "different printer" else "dead")
        }
        val device = adapter.getRemoteDevice(mac)
        AppLog.i("BT", "connecting to $mac")
        val t0 = System.currentTimeMillis()
        val socket = try {
            connect(device)
        } catch (first: IOException) {
            AppLog.e("BT", "connect failed (${first.message}), retrying in 1.5s")
            Thread.sleep(1500)
            try { connect(device) } catch (_: IOException) { throw first }
        }
        AppLog.i("BT", "connected in ${System.currentTimeMillis() - t0}ms")
        return Held(mac, socket).also { held = it }
    }

    @Throws(IOException::class)
    private fun send(socket: BluetoothSocket, chunks: List<ByteArray>, interChunkDelayMs: Long, pace: Boolean) {
        val out = socket.outputStream
        val totalBytes = chunks.sumOf { it.size }
        val paceBytesPerMs = if (interChunkDelayMs > 0) 4 else 10
        val t0 = System.currentTimeMillis()
        for (c in chunks) {
            var off = 0
            while (off < c.size) {
                val n = minOf(WRITE_CHUNK, c.size - off)
                out.write(c, off, n)
                off += n
                if (interChunkDelayMs > 0) Thread.sleep(interChunkDelayMs)
            }
            out.flush()
            if (pace) Thread.sleep((c.size / paceBytesPerMs).coerceAtLeast(3).toLong())
        }
        // Tail padding: some printers' Bluetooth modules forward data to the
        // print engine in fixed-size blocks and hold the last partial block
        // until more data arrives, so the final feed/cut only printed with the
        // NEXT job. 2 KB of no-op ESC a 0 pushes it through without output.
        val pad = ByteArray(2049) { i -> if (i % 3 == 0) 0x1B else if (i % 3 == 1) 'a'.code.toByte() else 0 }
        out.write(pad)
        out.flush()
        AppLog.i("BT", "wrote $totalBytes bytes + ${pad.size} pad in ${System.currentTimeMillis() - t0}ms")
    }

    /** Before closing a link, give the phone-side buffer time to empty. */
    private fun drain(socket: BluetoothSocket, totalBytes: Int, interChunkDelayMs: Long) {
        val waitMs = if (interChunkDelayMs > 0) (1_500L + totalBytes / 10).coerceAtMost(45_000L)
                     else (600L + totalBytes / 40).coerceAtMost(15_000L)
        AppLog.i("BT", "drain ${waitMs}ms before close")
        Thread.sleep(waitMs)
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        var lastErr: IOException? = null
        // Attempt 1: secure SPP. Attempt 2: insecure. Attempt 3: reflection channel 1 (old clones).
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            }
        )
        for ((i, make) in attempts.withIndex()) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                AppLog.i("BT", "socket method #${i + 1} ok")
                return s
            } catch (e: Exception) {
                AppLog.e("BT", "socket method #${i + 1} failed: ${e.message}")
                try { s?.close() } catch (_: Exception) {}
                lastErr = e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }
        throw lastErr ?: IOException("Unable to connect")
    }
}
