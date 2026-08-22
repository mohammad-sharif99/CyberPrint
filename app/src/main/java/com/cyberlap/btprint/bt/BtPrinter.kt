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
    fun print(mac: String, chunks: List<ByteArray>, interChunkDelayMs: Long = 0) {
        if (!hasPermission(ctx)) throw IOException("Bluetooth permission not granted")
        val adapter = adapter(ctx) ?: throw IOException("Bluetooth not available")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        try { adapter.cancelDiscovery() } catch (_: Exception) {}

        val device = adapter.getRemoteDevice(mac)
        val socket = connect(device)
        try {
            val out = socket.outputStream
            for (c in chunks) {
                var off = 0
                while (off < c.size) {
                    val n = minOf(WRITE_CHUNK, c.size - off)
                    out.write(c, off, n)
                    off += n
                    if (interChunkDelayMs > 0) Thread.sleep(interChunkDelayMs)
                }
                out.flush()
            }
            out.flush()
            // Give the BT stack time to drain before we tear the link down.
            Thread.sleep(if (interChunkDelayMs > 0) 1500 else 700)
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
                return s
            } catch (e: Exception) {
                try { s?.close() } catch (_: Exception) {}
                lastErr = e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }
        throw lastErr ?: IOException("Unable to connect")
    }
}
