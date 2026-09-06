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
import androidx.core.content.ContextCompat
import com.cyberlap.btprint.AppLog
import java.io.IOException
import java.util.UUID

/** Raw SPP (RFCOMM) transport to a Bluetooth ESC/POS printer. */
class BtPrinter(private val ctx: Context) {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val WRITE_CHUNK = 512

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
    }

    /**
     * Connects, streams every chunk in order, then closes. Blocking — call off the main thread.
     * [interChunkDelayMs] throttles for printers with tiny buffers.
     */
    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun print(mac: String, chunks: List<ByteArray>, interChunkDelayMs: Long = 0, pace: Boolean = true) {
        if (!hasPermission(ctx)) throw IOException("Bluetooth permission not granted")
        val adapter = adapter(ctx) ?: throw IOException("Bluetooth not available")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        try { adapter.cancelDiscovery() } catch (_: Exception) {}

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
        try {
            val out = socket.outputStream
            val totalBytes = chunks.sumOf { it.size }
            // Pace sending to the printer's PRINT speed, not its receive speed.
            // Cheap printers accept data far faster than they print; when their
            // buffer fills and the host disconnects, they stall mid-buffer and
            // dump the remainder at the start of the next job. Feeding roughly
            // as fast as the mechanism prints keeps the buffer near-empty.
            val paceBytesPerMs = if (interChunkDelayMs > 0) 4 else 10
            // Swallow anything the printer may have sent earlier so the
            // handshake below reads only its own reply.
            try { while (socket.inputStream.available() > 0) socket.inputStream.read() } catch (_: Exception) {}
            for (c in chunks) {
                var off = 0
                while (off < c.size) {
                    val n = minOf(WRITE_CHUNK, c.size - off)
                    out.write(c, off, n)
                    off += n
                }
                out.flush()
                if (pace) Thread.sleep((c.size / paceBytesPerMs).coerceAtLeast(3).toLong())
            }
            // End-of-job handshake: GS r 1 is a NON-real-time status request,
            // so the printer answers only after everything queued before it has
            // been processed. A reply therefore proves the receipt is fully
            // printed and the link can be closed. Printers that never answer
            // fall back to a size-proportional timed drain.
            // Tail padding. Some printers' Bluetooth modules forward data to the
            // print engine in fixed-size blocks and hold the last partial block
            // until more data arrives - so the final feed/cut only printed at the
            // start of the NEXT job, regardless of how long we waited. Push it
            // through with 2 KB of no-op commands (ESC a 0 = align left, no output).
            val pad = ByteArray(2049) { i -> if (i % 3 == 0) 0x1B else if (i % 3 == 1) 'a'.code.toByte() else 0 }
            out.write(pad)
            out.flush()
            val tSent = System.currentTimeMillis()
            AppLog.i("BT", "all $totalBytes bytes + ${pad.size} pad written in ${tSent - t0}ms; waiting for GS r reply")
            out.write(byteArrayOf(0x1D, 'r'.code.toByte(), 1))
            out.flush()
            // Printers that never answer (log shows this one does not) fall
            // back to a timed drain sized on a ~8 KB/s print speed.
            val maxWaitMs = (2_000L + totalBytes / 8).coerceAtMost(60_000L)
            val started = System.currentTimeMillis()
            var acked = false
            val input = socket.inputStream
            while (System.currentTimeMillis() - started < maxWaitMs) {
                if (input.available() > 0) {
                    val b = input.read()
                    // GS r replies have bit 4 = 0; real-time DLE EOT replies
                    // (possibly triggered earlier) have bit 4 = 1 - ignore those.
                    AppLog.i("BT", "printer byte 0x${Integer.toHexString(b)} after ${System.currentTimeMillis() - tSent}ms")
                    if (b >= 0 && (b and 0x10) == 0) { acked = true; break }
                    continue
                }
                Thread.sleep(50)
            }
            // Short settle after ack; without ack the loop already waited the full budget.
            AppLog.i("BT", if (acked) "handshake OK after ${System.currentTimeMillis() - tSent}ms" else "no reply in ${maxWaitMs}ms (timed drain used)")
            if (acked) Thread.sleep(400)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
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
        for (make in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                AppLog.i("BT", "socket method #${attempts.indexOf(make) + 1} ok")
                return s
            } catch (e: Exception) {
                AppLog.e("BT", "socket method #${attempts.indexOf(make) + 1} failed: ${e.message}")
                try { s?.close() } catch (_: Exception) {}
                lastErr = e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }
        throw lastErr ?: IOException("Unable to connect")
    }
}
