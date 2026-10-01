package com.example.gamepanel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Random

data class NetResult(
    val latencyAvgMs: Float?,
    val latencyMinMs: Float?,
    val latencyMaxMs: Float?,
    val lossPct: Float?,
    val downloadMbps: Float?,
    val uploadMbps: Float?,
    val method: String,
    val error: String?
)

/** Tes jaringan sungguhan (bukan dummy), semuanya di background. Dijalankan hanya saat pengguna menekan TEST NETWORK. */
object NetTester {
    private const val TAG = "NetTester"
    private const val PING_HOST = "8.8.8.8"
    private const val DOWN_BYTES = 6_000_000
    private const val UP_BYTES = 2_000_000

    /** Ping ringan saja (untuk REFRESH). */
    suspend fun quickPing(): NetResult = withContext(Dispatchers.IO) {
        val p = ping()
        NetResult(p.avg, p.min, p.max, p.loss, null, null, p.method, p.error)
    }

    suspend fun full(): NetResult = withContext(Dispatchers.IO) {
        val p = ping()
        var down: Float? = null
        var up: Float? = null
        var err = p.error
        try { down = download() } catch (e: Exception) {
            Log.w(TAG, "download: ${e.message}")
            err = err ?: "Download gagal: ${e.message}"
        }
        try { up = upload() } catch (e: Exception) {
            Log.w(TAG, "upload: ${e.message}")
            err = err ?: "Upload gagal: ${e.message}"
        }
        NetResult(p.avg, p.min, p.max, p.loss, down, up, p.method, err)
    }

    private class PingR(val avg: Float?, val min: Float?, val max: Float?, val loss: Float?, val method: String, val error: String?)

    private fun ping(): PingR {
        // 1) Biner ping bawaan Android (tanpa root)
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("ping", "-c", "6", "-i", "0.3", "-W", "1", PING_HOST))
            val text = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            try { proc.waitFor() } catch (e: Exception) { /* abaikan */ }
            val loss = Regex("(\\d+(?:\\.\\d+)?)% packet loss").find(text)?.groupValues?.get(1)?.toFloatOrNull()
            val rtt = Regex("=\\s*([\\d.]+)/([\\d.]+)/([\\d.]+)").find(text)
            if (loss != null) {
                return PingR(
                    avg = rtt?.groupValues?.get(2)?.toFloatOrNull(),
                    min = rtt?.groupValues?.get(1)?.toFloatOrNull(),
                    max = rtt?.groupValues?.get(3)?.toFloatOrNull(),
                    loss = loss, method = "ICMP ping ke $PING_HOST", error = null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ping biner: ${e.message}")
        }
        // 2) Fallback: waktu koneksi TCP ke 1.1.1.1:443
        val times = ArrayList<Float>()
        var fail = 0
        val n = 6
        for (i in 0 until n) {
            val t0 = System.nanoTime()
            try {
                Socket().use { s -> s.connect(InetSocketAddress("1.1.1.1", 443), 1500) }
                times.add((System.nanoTime() - t0) / 1_000_000f)
            } catch (e: Exception) { fail++ }
            try { Thread.sleep(150) } catch (e: InterruptedException) { break }
        }
        if (times.isEmpty()) return PingR(null, null, null, 100f, "TCP 1.1.1.1:443", "Tidak ada respons")
        return PingR(times.average().toFloat(), times.min(), times.max(), fail * 100f / n, "TCP connect 1.1.1.1:443 (ICMP tidak tersedia)", null)
    }

    private fun download(): Float? {
        val conn = URL("https://speed.cloudflare.com/__down?bytes=$DOWN_BYTES").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        try {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            val t0 = System.nanoTime()
            conn.inputStream.use { ins ->
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    total += r
                    if (System.nanoTime() - t0 > 8_000_000_000L) break
                }
            }
            val sec = (System.nanoTime() - t0) / 1e9
            if (sec <= 0.0 || total <= 0L) return null
            return (total * 8 / sec / 1e6).toFloat()
        } finally {
            conn.disconnect()
        }
    }

    private fun upload(): Float? {
        val conn = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 4000
        conn.readTimeout = 8000
        conn.setFixedLengthStreamingMode(UP_BYTES)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        try {
            val chunk = ByteArray(32 * 1024).also { Random().nextBytes(it) }
            var sent = 0
            val t0 = System.nanoTime()
            conn.outputStream.use { os ->
                while (sent < UP_BYTES) {
                    val n = minOf(chunk.size, UP_BYTES - sent)
                    os.write(chunk, 0, n)
                    sent += n
                }
                os.flush()
            }
            conn.responseCode // pastikan server menerima
            val sec = (System.nanoTime() - t0) / 1e9
            if (sec <= 0.0) return null
            return (sent * 8 / sec / 1e6).toFloat()
        } finally {
            conn.disconnect()
        }
    }
}
