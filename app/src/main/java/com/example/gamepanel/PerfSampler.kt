package com.example.gamepanel

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.example.manager.ShizukuExecutor
import java.io.File

/**
 * Satu potret metrik. SEMUA field nullable = "tidak tersedia di perangkat ini".
 * Tidak ada angka estimasi/palsu: jika tidak bisa dibaca, nilainya null dan UI menulis "N/A".
 */
data class PerfSnapshot(
    val time: Long,
    // CPU
    val cpuPct: Int?,
    val cpuCores: Int,
    val cpuFreqMhz: List<Int>,
    val loadAvg: String?,
    val cpuTempC: Float?,
    val thermalStatus: String?,
    // GPU
    val gpuPct: Int?,
    val gpuFreqMhz: Int?,
    val gpuName: String?,
    // RAM (byte)
    val ramTotal: Long,
    val ramAvail: Long,
    // Baterai
    val batteryPct: Int?,
    val charging: Boolean?,
    val batteryTempC: Float?,
    val batteryMv: Int?,
    // Layar
    val refreshHz: Float,
    val supportedHz: List<Float>,
    val resolution: String,
    val brightnessPct: Int?,
    // Jaringan
    val netType: String,
    val wifiRssiDbm: Int?,
    val wifiLinkMbps: Int?,
    // FPS (game di latar depan) — null bila tidak bisa diukur
    val fps: Int?,
    val fpsNote: String
) {
    val ramUsed: Long get() = (ramTotal - ramAvail).coerceAtLeast(0)
    val ramPct: Int get() = if (ramTotal <= 0) 0 else (ramUsed * 100 / ramTotal).toInt()
    /** Suhu terbaik yang tersedia: CPU jika terbaca, kalau tidak suhu baterai. */
    val tempC: Float? get() = cpuTempC ?: batteryTempC
}

class PerfSampler(context: Context) {

    companion object {
        private const val TAG = "PerfSampler"
        private val GPU_BUSY_PCT_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load"
        )
        private const val KGSL_GPUBUSY = "/sys/class/kgsl/kgsl-3d0/gpubusy"
        private val GPU_FREQ_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/misc/mali0/device/cur_freq",
            "/sys/kernel/gpu/gpu_clock"
        )
        private val MALI_UTIL_PATHS = listOf(
            "/sys/class/misc/mali0/device/utilization",
            "/sys/kernel/gpu/gpu_busy"
        )
        private val TEMP_PATHS = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )
    }

    private val app = context.applicationContext
    private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager

    // Delta /proc/stat
    private var prevTotal = 0L
    private var prevIdle = 0L
    // Delta gpubusy (kgsl)
    private var gpuName: String? = null
    private var gpuNameTried = false

    // Cache "jalur langsung gagal" agar tidak membuang waktu di tiap sampling
    private var procStatDirectFailed = false
    private var sysDirectFailed = false

    // Cache layer FPS
    private var fpsLayerPkg: String? = null
    private var fpsLayer: String? = null
    private var fpsLayerTime = 0L

    suspend fun sample(fgPackage: String?, wantFps: Boolean): PerfSnapshot {
        val now = System.currentTimeMillis()
        val cores = Runtime.getRuntime().availableProcessors()

        // RAM
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        // Baterai (sticky broadcast, murah)
        val b: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenth = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val mv = b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = if (status < 0) null else
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        // Layar
        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display: Display? = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val hz = display?.refreshRate ?: 0f
        val supported: List<Float> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display?.supportedModes?.map { it.refreshRate }?.distinctBy { Math.round(it) }?.sorted() ?: emptyList()
            } else display?.supportedRefreshRates?.toList() ?: emptyList()
        } catch (e: Exception) { emptyList() }
        val res = app.resources.displayMetrics
        val brightness = try {
            (Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255)
        } catch (e: Exception) { null }

        // Jaringan
        val (netType, rssi, link) = networkInfo()

        // CPU / GPU / suhu / frekuensi (I/O)
        val cpu = readCpuPct()
        val freqs = readCpuFreqsMhz(cores)
        val load = readSys("/proc/loadavg")?.split(" ")?.take(3)?.joinToString(" ")
        val cpuTemp = readCpuTemp()
        val gpu = readGpu()
        val gname = gpuName ?: resolveGpuName()

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Ringan"
                PowerManager.THERMAL_STATUS_MODERATE -> "Sedang"
                PowerManager.THERMAL_STATUS_SEVERE -> "Berat"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Kritis"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Darurat"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> null
            }
        } else null

        // FPS
        var fps: Int? = null
        var fpsNote = "Pantau FPS tidak diaktifkan"
        if (wantFps) {
            if (fgPackage.isNullOrBlank()) {
                fpsNote = "Belum ada game di latar depan"
            } else if (!ShizukuExecutor.isReady()) {
                fpsNote = "FPS game butuh Shizuku (Android tidak menyediakan FPS lintas-aplikasi untuk app biasa)"
            } else {
                val r = measureFps(fgPackage)
                fps = r.first
                fpsNote = r.second
            }
        }

        return PerfSnapshot(
            time = now,
            cpuPct = cpu, cpuCores = cores, cpuFreqMhz = freqs, loadAvg = load,
            cpuTempC = cpuTemp, thermalStatus = thermal,
            gpuPct = gpu.first, gpuFreqMhz = gpu.second, gpuName = gname,
            ramTotal = mi.totalMem, ramAvail = mi.availMem,
            batteryPct = batteryPct, charging = charging,
            batteryTempC = if (tempTenth == Int.MIN_VALUE) null else tempTenth / 10f,
            batteryMv = if (mv > 0) mv else null,
            refreshHz = hz, supportedHz = supported,
            resolution = "${res.widthPixels}×${res.heightPixels} @${res.densityDpi}dpi",
            brightnessPct = brightness,
            netType = netType, wifiRssiDbm = rssi, wifiLinkMbps = link,
            fps = fps, fpsNote = fpsNote
        )
    }

    // ────────────────────────────────────────────────────────────────
    // Jaringan
    // ────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun networkInfo(): Triple<String, Int?, Int?> {
        return try {
            val net = cm.activeNetwork ?: return Triple("Tidak terhubung", null, null)
            val caps = cm.getNetworkCapabilities(net) ?: return Triple("Tidak terhubung", null, null)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data seluler"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Lainnya"
            }
            var rssi: Int? = null
            var link: Int? = null
            if (type == "Wi-Fi") {
                val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wm?.connectionInfo
                if (info != null && info.rssi > -127) {
                    rssi = info.rssi
                    link = if (info.linkSpeed > 0) info.linkSpeed else null
                }
            }
            Triple(type, rssi, link)
        } catch (e: SecurityException) {
            Triple("Tidak diketahui (izin)", null, null)
        } catch (e: Exception) {
            Triple("Tidak diketahui", null, null)
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Pembacaan sysfs/procfs: langsung, lalu via Shizuku bila perlu
    // ────────────────────────────────────────────────────────────────

    private fun readDirect(path: String): String? = try {
        File(path).readText().trim().ifEmpty { null }
    } catch (e: Exception) { null }

    /** Baca file; jika akses langsung ditolak dan Shizuku siap, baca via shell. */
    private suspend fun readSys(path: String): String? {
        val d = readDirect(path)
        if (d != null) return d
        if (!ShizukuExecutor.isReady()) return null
        return ShizukuExecutor.runCommand("cat $path 2>/dev/null").getOrNull()?.trim()?.ifEmpty { null }
    }

    private suspend fun readCpuPct(): Int? {
        val text: String? = if (!procStatDirectFailed) {
            val t = readDirect("/proc/stat")
            if (t == null) procStatDirectFailed = true
            t
        } else null
        val stat: String = (text ?: if (ShizukuExecutor.isReady())
            ShizukuExecutor.runCommand("head -n 1 /proc/stat").getOrNull() else null) ?: return null
        val line = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val idle = parts[3] + (parts.getOrNull(4) ?: 0L) // idle + iowait
        val total = parts.take(8).sum()
        val pt = prevTotal
        val pi = prevIdle
        prevTotal = total
        prevIdle = idle
        if (pt == 0L) return null
        val dt = total - pt
        val di = idle - pi
        if (dt <= 0) return null
        return ((dt - di) * 100 / dt).toInt().coerceIn(0, 100)
    }

    private suspend fun readCpuFreqsMhz(cores: Int): List<Int> {
        val out = ArrayList<Int>()
        var directOk = false
        for (i in 0 until cores) {
            val v = readDirect("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toLongOrNull()
            if (v != null) { out.add((v / 1000).toInt()); directOk = true } else out.add(-1)
        }
        if (!directOk && ShizukuExecutor.isReady()) {
            val r = ShizukuExecutor.runCommand(
                "for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do cat \$f 2>/dev/null; done"
            ).getOrNull()
            val vals = r?.lines()?.mapNotNull { it.trim().toLongOrNull() }?.map { (it / 1000).toInt() }
            if (vals != null && vals.isNotEmpty()) return vals
        }
        return if (directOk) out.filter { it >= 0 } else emptyList()
    }

    private suspend fun readCpuTemp(): Float? {
        for (p in TEMP_PATHS) {
            val v = readDirect(p)?.toFloatOrNull() ?: continue
            val c = if (v > 1000f) v / 1000f else v
            if (c in 1f..150f) return c
        }
        if (!sysDirectFailed && ShizukuExecutor.isReady()) {
            val r = ShizukuExecutor.runCommand("cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null").getOrNull()
            val v = r?.trim()?.toFloatOrNull()
            if (v != null) {
                val c = if (v > 1000f) v / 1000f else v
                if (c in 1f..150f) return c
            }
        }
        return null
    }

    /** @return Pair(persen GPU?, frekuensi MHz?) */
    private suspend fun readGpu(): Pair<Int?, Int?> {
        var pct: Int? = null
        var freq: Int? = null

        for (p in GPU_BUSY_PCT_PATHS) {
            val v = readSys(p)?.filter { it.isDigit() }?.toIntOrNull()
            if (v != null) { pct = v.coerceIn(0, 100); break }
        }
        if (pct == null) {
            // gpubusy: "<busy> <total>" — hanya bermakna jika total > 0
            val g = readSys(KGSL_GPUBUSY)?.split(Regex("\\s+"))
            val busy = g?.getOrNull(0)?.toLongOrNull()
            val total = g?.getOrNull(1)?.toLongOrNull()
            if (busy != null && total != null && total > 0) pct = (busy * 100 / total).toInt().coerceIn(0, 100)
        }
        if (pct == null) {
            for (p in MALI_UTIL_PATHS) {
                val v = readSys(p)?.filter { it.isDigit() }?.toIntOrNull()
                if (v != null) { pct = if (v > 100) (v * 100 / 255).coerceIn(0, 100) else v; break }
            }
        }
        for (p in GPU_FREQ_PATHS) {
            val v = readSys(p)?.trim()?.toLongOrNull() ?: continue
            freq = when {
                v > 10_000_000L -> (v / 1_000_000L).toInt()  // Hz -> MHz
                v > 10_000L -> (v / 1000L).toInt()           // kHz -> MHz
                else -> v.toInt()                            // sudah MHz
            }
            break
        }
        return Pair(pct, freq)
    }

    /** Nama GPU dari GL_RENDERER lewat EGL pbuffer sekali saja. */
    private fun resolveGpuName(): String? {
        if (gpuNameTried) return gpuName
        gpuNameTried = true
        var display = EGL14.EGL_NO_DISPLAY
        var ctx = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null
            val cfgAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return null
            ctx = EGL14.eglCreateContext(
                display, cfgs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            surf = EGL14.eglCreatePbufferSurface(
                display, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return null
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            gpuName = renderer
        } catch (e: Exception) {
            Log.w(TAG, "GPU name: ${e.message}")
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                    EGL14.eglTerminate(display)
                }
            } catch (e: Exception) { /* abaikan */ }
        }
        return gpuName
    }

    // ────────────────────────────────────────────────────────────────
    // FPS game (butuh shell via Shizuku): SurfaceFlinger --latency
    // ────────────────────────────────────────────────────────────────

    private suspend fun measureFps(pkg: String): Pair<Int?, String> {
        val now = System.currentTimeMillis()
        if (fpsLayerPkg != pkg || fpsLayer == null || now - fpsLayerTime > 15_000L) {
            fpsLayer = findLayer(pkg)
            fpsLayerPkg = pkg
            fpsLayerTime = now
        }
        val layer = fpsLayer ?: return Pair(null, "Layer game tidak ditemukan di SurfaceFlinger")
        if (layer.contains('\'')) return Pair(null, "Nama layer tidak valid")

        val out = ShizukuExecutor.runCommand("dumpsys SurfaceFlinger --latency '$layer'").getOrNull()
            ?: return Pair(null, "Gagal membaca SurfaceFlinger")
        val lines = out.lines().drop(1) // baris pertama = refresh period
        val times = ArrayList<Long>()
        for (l in lines) {
            val parts = l.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val t = parts[1].toLongOrNull() ?: continue // actualPresentTime
            if (t <= 0L || t == Long.MAX_VALUE) continue
            times.add(t)
        }
        if (times.size < 3) {
            fpsLayer = null // paksa cari ulang layer
            return Pair(null, "Data frame belum cukup")
        }
        val last = times.max()
        val window = times.filter { it >= last - 1_000_000_000L }.sorted()
        if (window.size < 2) return Pair(null, "Data frame belum cukup")
        val spanNs = window.last() - window.first()
        if (spanNs <= 0L) return Pair(null, "Data frame belum cukup")
        val fps = Math.round((window.size - 1) * 1_000_000_000.0 / spanNs).toInt()
        return Pair(fps.coerceIn(0, 240), "Diukur dari SurfaceFlinger")
    }

    private suspend fun findLayer(pkg: String): String? {
        val list = ShizukuExecutor.runCommand("dumpsys SurfaceFlinger --list").getOrNull() ?: return null
        val cands = list.lines().map { it.trim() }.filter { it.contains(pkg) }
        return cands.firstOrNull { it.contains("SurfaceView", ignoreCase = true) && it.contains("BLAST").not() }
            ?: cands.firstOrNull { it.contains("SurfaceView", ignoreCase = true) }
            ?: cands.firstOrNull { it.contains("BLAST") }
            ?: cands.firstOrNull()
    }
}
