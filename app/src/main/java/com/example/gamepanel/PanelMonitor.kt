package com.example.gamepanel

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Ring buffer sederhana untuk grafik riwayat pendek (NaN = tidak ada data). */
class History(private val cap: Int = 60) {
    private val buf = FloatArray(cap) { Float.NaN }
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(v: Float?) {
        buf[head] = v ?: Float.NaN
        head = (head + 1) % cap
        if (size < cap) size++
    }

    @Synchronized
    fun snapshot(): FloatArray {
        val out = FloatArray(size)
        val start = (head - size + cap) % cap
        for (i in 0 until size) out[i] = buf[(start + i) % cap]
        return out
    }

    @Synchronized
    fun clear() { head = 0; size = 0 }
}

/**
 * Monitor performa ringan.
 *  • Hanya berjalan saat dibutuhkan (panel terbuka / HUD tampil) — [acquire]/[release] berhitung referensi.
 *  • Interval sampling mengikuti [PerfMode] (5s / 2s / 1s), tidak pernah per-milidetik.
 *  • Sampling berjalan di satu thread khusus dengan prioritas sesuai mode.
 *  • BOOST: mendaftarkan PerformanceHint session (API 31+) untuk thread UI aplikasi ini saja.
 */
object PanelMonitor {
    private const val TAG = "PanelMonitor"

    val cpuHistory = History()
    val ramHistory = History()
    val tempHistory = History()
    val batteryHistory = History()

    private val _snapshot = MutableStateFlow<PerfSnapshot?>(null)
    val snapshot: StateFlow<PerfSnapshot?> = _snapshot.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "GamePanelSampler") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var job: Job? = null
    private var sampler: PerfSampler? = null
    private var refs = 0
    private var appCtx: Context? = null

    @Volatile private var fgPackage: String? = null
    @Volatile private var wantFps: Boolean = true
    @Volatile private var mode: PerfMode = PerfMode.SEIMBANG
    @Volatile private var forceFastMs: Long = 0L

    private var hintSession: PerformanceHintManager.Session? = null

    fun setContext(c: Context) {
        appCtx = c.applicationContext
    }

    fun setForeground(pkg: String?, fps: Boolean) {
        fgPackage = pkg
        wantFps = fps
    }

    fun setMode(m: PerfMode) {
        mode = m
        updateHint()
    }

    fun currentMode(): PerfMode = mode

    @Synchronized
    fun acquire() {
        refs++
        if (refs == 1) startLoop()
    }

    @Synchronized
    fun release() {
        if (refs > 0) refs--
        if (refs == 0) stopLoop()
    }

    /** Ambil sampel sekarang juga (tombol REFRESH). */
    fun requestNow() {
        val s = sampler ?: return
        scope.launch {
            try {
                publish(s.sample(fgPackage, wantFps))
            } catch (e: Exception) {
                Log.w(TAG, "requestNow: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun startLoop() {
        val c = appCtx ?: return
        if (job?.isActive == true) return
        val s = PerfSampler(c)
        sampler = s
        job = scope.launch {
            while (isActive) {
                try {
                    applyThreadPriority()
                    publish(s.sample(fgPackage, wantFps))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "sample gagal: ${e.message}")
                }
                delay(if (forceFastMs > 0) forceFastMs else mode.sampleMs)
            }
        }
        updateHint()
        Log.i(TAG, "Monitor mulai (mode=${mode.name})")
    }

    @Synchronized
    private fun stopLoop() {
        job?.cancel()
        job = null
        sampler = null
        closeHint()
        Log.i(TAG, "Monitor berhenti")
    }

    private fun publish(s: PerfSnapshot) {
        _snapshot.value = s
        cpuHistory.add(s.cpuPct?.toFloat())
        ramHistory.add(s.ramPct.toFloat())
        tempHistory.add(s.tempC)
        batteryHistory.add(s.batteryPct?.toFloat())
    }

    private fun applyThreadPriority() {
        try {
            val p = when (mode) {
                PerfMode.HEMAT -> Process.THREAD_PRIORITY_BACKGROUND
                PerfMode.SEIMBANG -> Process.THREAD_PRIORITY_DEFAULT
                PerfMode.BOOST -> Process.THREAD_PRIORITY_FOREGROUND
            }
            Process.setThreadPriority(p)
        } catch (e: Exception) { /* abaikan */ }
    }

    /** Hint performa hanya untuk thread milik aplikasi ini (legal, API 31+). */
    private fun updateHint() {
        if (mode != PerfMode.BOOST) { closeHint(); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val c = appCtx ?: return
        if (hintSession != null) return
        try {
            val mgr = c.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager ?: return
            hintSession = mgr.createHintSession(intArrayOf(Process.myPid()), 16_000_000L)
        } catch (e: Exception) {
            Log.w(TAG, "PerformanceHint tidak tersedia: ${e.message}")
        }
    }

    private fun closeHint() {
        try { hintSession?.close() } catch (e: Exception) { /* abaikan */ }
        hintSession = null
    }

    /** True bila hint performa aktif (untuk ditampilkan jujur di UI). */
    fun hintActive(): Boolean = hintSession != null

    fun shutdown() {
        job?.cancel()
        job = null
        closeHint()
        refs = 0
    }
}
