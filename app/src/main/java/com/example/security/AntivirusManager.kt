package com.example.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pusat antivirus.
 *
 *  • Proteksi real-time: mendengarkan ACTION_PACKAGE_ADDED (aplikasi baru / update) lewat receiver
 *    dinamis, lalu memindai dan menampilkan peringatan jika risiko >= [ALERT_MIN_LEVEL].
 *  • Catch-up: saat start, aplikasi yang muncul selagi proteksi mati ikut dipindai.
 *  • Pindai semua: pemindaian manual seluruh aplikasi non-sistem.
 *
 * Receiver bersifat dinamis (Android 8+ tidak mengizinkan ACTION_PACKAGE_ADDED di manifest),
 * jadi [start] dipanggil dari MainActivity dan GameBoostService (foreground service).
 */
object AntivirusManager {

    private const val TAG = "AntivirusManager"
    const val CHANNEL_ID = "antivirus_alert_channel"

    /** Risiko minimum yang memunculkan peringatan hapus / biarkan. */
    val ALERT_MIN_LEVEL: RiskLevel = RiskLevel.SEDANG

    data class ScanProgress(
        val running: Boolean = false,
        val current: String = "",
        val done: Int = 0,
        val total: Int = 0
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val lock = Any()

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private val _results = MutableStateFlow<List<ScanResult>>(emptyList())
    val results: StateFlow<List<ScanResult>> = _results.asStateFlow()

    private val _realtime = MutableStateFlow(true)
    val realtimeEnabled: StateFlow<Boolean> = _realtime.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    // ────────────────────────────────────────────────────────────────
    // Siklus hidup
    // ────────────────────────────────────────────────────────────────

    /** Memuat hasil tersimpan ke StateFlow (aman dipanggil berulang). */
    fun load(context: Context) {
        val app = context.applicationContext
        _realtime.value = ScanStore.isRealtimeEnabled(app)
        refreshResults(app)
    }

    /** Menyalakan proteksi real-time jika diaktifkan pengguna. Aman dipanggil berulang. */
    fun start(context: Context) {
        val app = context.applicationContext
        load(app)
        if (!ScanStore.isRealtimeEnabled(app)) return
        synchronized(lock) {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    when (intent.action) {
                        Intent.ACTION_PACKAGE_ADDED -> onPackageAdded(app, pkg)
                        Intent.ACTION_PACKAGE_REMOVED -> {
                            // REPLACING = update, akan disusul PACKAGE_ADDED → jangan hapus hasil.
                            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) onPackageRemoved(app, pkg)
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            try {
                app.registerReceiver(r, filter)
                receiver = r
                Log.i(TAG, "🛡️ Proteksi real-time aktif")
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mendaftarkan receiver: ${e.message}")
            }
        }
        scope.launch { catchUp(app) }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val r = receiver ?: return
            try { app.unregisterReceiver(r) } catch (e: Exception) { Log.w(TAG, "unregister: ${e.message}") }
            receiver = null
            Log.i(TAG, "Proteksi real-time dimatikan")
        }
    }

    fun setRealtime(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        ScanStore.setRealtimeEnabled(app, enabled)
        _realtime.value = enabled
        if (enabled) start(app) else stop(app)
    }

    // ────────────────────────────────────────────────────────────────
    // Aplikasi baru / dihapus
    // ────────────────────────────────────────────────────────────────

    private fun onPackageAdded(app: Context, pkg: String) {
        if (pkg == app.packageName) return
        scope.launch {
            Log.i(TAG, "📦 Aplikasi baru terdeteksi: $pkg — memindai")
            scanAndReport(app, pkg, notify = true)
            saveSnapshot(app)
        }
    }

    private fun onPackageRemoved(app: Context, pkg: String) {
        scope.launch {
            ScanStore.removeResult(app, pkg)
            cancelAlertNotification(app, pkg)
            refreshResults(app)
            saveSnapshot(app)
        }
    }

    /** Memindai aplikasi yang terpasang saat proteksi mati / app tidak berjalan. */
    private suspend fun catchUp(app: Context) {
        val current = userPackageNames(app)
        val known = ScanStore.getKnownPackages(app)
        if (known == null) {
            // Pertama kali: hanya catat snapshot. Pengguna bisa menjalankan "Pindai semua".
            ScanStore.setKnownPackages(app, current)
            return
        }
        for (pkg in current - known) {
            scanAndReport(app, pkg, notify = true)
        }
        for (gone in known - current) {
            ScanStore.removeResult(app, gone)
            cancelAlertNotification(app, gone)
        }
        ScanStore.setKnownPackages(app, current)
        refreshResults(app)
    }

    private fun saveSnapshot(app: Context) {
        try { ScanStore.setKnownPackages(app, userPackageNames(app)) } catch (e: Exception) { Log.w(TAG, "snapshot: ${e.message}") }
    }

    // ────────────────────────────────────────────────────────────────
    // Pemindaian
    // ────────────────────────────────────────────────────────────────

    private suspend fun scanAndReport(app: Context, pkg: String, notify: Boolean): ScanResult? {
        var scanned: ScanResult? = null
        try {
            scanned = scanMutex.withLock { AppScanner(app).scan(pkg) }
        } catch (e: Exception) {
            Log.e(TAG, "Pemindaian $pkg gagal: ${e.message}")
        }
        val result: ScanResult = scanned ?: return null

        ScanStore.putResult(app, result)
        refreshResults(app)
        Log.i(TAG, "Hasil $pkg: ${result.level} (skor ${result.score}, ${result.findings.size} temuan)")

        if (notify &&
            result.level.rank >= ALERT_MIN_LEVEL.rank &&
            !ScanStore.isIgnored(app, pkg, result.versionCode)
        ) {
            raiseAlert(app, result)
        }
        return result
    }

    /** Pemindaian manual semua aplikasi non-sistem. Tidak menumpuk pop-up; hasil tampil di tab Keamanan. */
    fun scanAll(context: Context) {
        val app = context.applicationContext
        if (_progress.value.running) return
        scope.launch {
            val pkgs = userPackageNames(app).sorted()
            _progress.value = ScanProgress(running = true, current = "", done = 0, total = pkgs.size)
            try {
                for ((i, pkg) in pkgs.withIndex()) {
                    _progress.value = ScanProgress(true, pkg, i, pkgs.size)
                    scanAndReport(app, pkg, notify = false)
                }
                ScanStore.setKnownPackages(app, pkgs)
                ScanStore.setLastFullScan(app, System.currentTimeMillis())
            } finally {
                _progress.value = ScanProgress(running = false, current = "", done = pkgs.size, total = pkgs.size)
                refreshResults(app)
            }
        }
    }

    /** Pindai ulang satu aplikasi (dari layar detail). */
    fun rescan(context: Context, pkg: String) {
        val app = context.applicationContext
        scope.launch { scanAndReport(app, pkg, notify = false) }
    }

    @Suppress("DEPRECATION")
    private fun userPackageNames(app: Context): Set<String> {
        val pm = app.packageManager
        return pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != app.packageName }
            .map { it.packageName }
            .toSet()
    }

    private fun refreshResults(app: Context) {
        _results.value = ScanStore.getResults(app)
            .sortedWith(compareByDescending<ScanResult> { it.level.rank }.thenByDescending { it.score }.thenBy { it.appName.lowercase() })
    }

    /** Dipanggil setelah pengguna memilih "Biarkan" / setelah hapus, supaya daftar ikut berubah. */
    fun onUserDecision(context: Context) {
        refreshResults(context.applicationContext)
    }

    // ────────────────────────────────────────────────────────────────
    // Peringatan
    // ────────────────────────────────────────────────────────────────

    private fun raiseAlert(app: Context, r: ScanResult) {
        ScanStore.addPending(app, r.packageName)
        postAlertNotification(app, r)
        // Pop-up langsung hanya bisa dari background jika izin "tampil di atas aplikasi lain" diberikan.
        // Jika tidak, notifikasi di atas tetap menjadi jalur peringatan.
        if (Settings.canDrawOverlays(app)) {
            try {
                app.startActivity(ThreatAlertActivity.createIntent(app, r.packageName))
            } catch (e: Exception) {
                Log.w(TAG, "Gagal membuka layar peringatan langsung: ${e.message}")
            }
        }
    }

    private fun postAlertNotification(app: Context, r: ScanResult) {
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CHANNEL_ID, "Peringatan Antivirus", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Peringatan saat aplikasi baru terdeteksi berisiko"
                nm.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(
                app,
                r.packageName.hashCode(),
                ThreatAlertActivity.createIntent(app, r.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val top = r.findings.take(3).joinToString("\n") { "• ${it.title}" }
            val notif = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_boost)
                .setContentTitle("${r.appName}: risiko ${r.level.label}")
                .setContentText(r.level.headline)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${r.level.headline}\n$top"))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            nm.notify(r.packageName.hashCode(), notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notifikasi ditolak (izin?): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menampilkan notifikasi: ${e.message}")
        }
    }

    fun cancelAlertNotification(app: Context, pkg: String) {
        try {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(pkg.hashCode())
        } catch (e: Exception) {
            Log.w(TAG, "cancel notif: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        true
    }
}
