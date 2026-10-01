package com.example.gamepanel

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.example.data.database.AppDatabase
import com.example.data.database.GameEntity
import com.example.data.repository.GameBoostRepository
import com.example.security.AntivirusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Otak Game Booster Panel. Semua overlay dikelola dari sini; tidak ada jendela yang ada
 * selama tidak ada game aktif (nol beban untuk aplikasi biasa).
 *
 * Alur:
 *   GameBoostService → [onGameChanged] → terapkan profil game → EdgeGuards (swipe kanan→kiri)
 *   → [openPanel] → PanelWindow (slide-in) → [minimize] → BubbleOverlay
 */
object GamePanelController {
    private const val TAG = "GamePanelController"

    @Volatile var gameActive = false
        private set
    @Volatile var gamePkg: String? = null
        private set
    var gameName: String = "Game"
        private set
    var gameStartElapsed: Long = 0L
        private set

    /** Hasil tes jaringan terakhir (untuk halaman Jaringan). */
    @Volatile var lastNet: NetResult? = null
    @Volatile var lastPingMs: Int? = null

    private var ctx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    private var edge: EdgeGuards? = null
    private var panel: PanelWindow? = null
    private var hud: HudOverlay? = null
    private var bubble: BubbleOverlay? = null
    private var lock: TouchLockOverlay? = null
    private var util: UtilityOverlay? = null
    private var cross: CrosshairOverlay? = null

    private var panelHoldsMonitor = false
    private var hudHoldsMonitor = false
    private var pingJob: Job? = null

    private var profile: GameProfile? = null
    var orientationMode: String = "AUTO"
        private set

    val appContext: Context? get() = ctx

    fun init(context: Context) {
        if (ctx != null) return
        val c = context.applicationContext
        ctx = c
        PanelMonitor.setContext(c)
        PanelMonitor.setMode(PanelSettings.activeMode(c))
        edge = EdgeGuards(c, { effectiveLevels() }) { main.post { openPanel() } }
        hud = HudOverlay(c)
        bubble = BubbleOverlay(c) { main.post { openPanel() } }
        lock = TouchLockOverlay(c) { toast("Kunci sentuh dibuka") }
        util = UtilityOverlay(c)
        cross = CrosshairOverlay(c)

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch {
            PanelMonitor.snapshot.collect { s ->
                panel?.onSnapshot(s)
                if (hud?.isShown == true) hud?.update(s, lastPingMs)
            }
        }
        ScreenRecordService.onStateChanged = { main.post { panel?.refreshTools() } }
        Log.i(TAG, "Controller siap")
    }

    // ────────────────────────────────────────────────────────────────
    // Siklus game
    // ────────────────────────────────────────────────────────────────

    /** Game terakhir yang dilaporkan service (dipakai saat saklar Panel Manager dinyalakan lagi). */
    @Volatile private var lastReportedPkg: String? = null

    /** Dipanggil dari service setiap kali game aktif berubah (null = tidak ada game). */
    fun onGameChanged(pkg: String?) {
        lastReportedPkg = pkg
        main.post {
            val c = ctx
            if (pkg == null || (c != null && !PanelSettings.panelEnabled(c))) endGame() else startGame(pkg)
        }
    }

    /**
     * Saklar utama Panel Manager. Berlaku langsung: OFF → semua overlay dilepas,
     * ON → kalau game sedang berjalan, panel langsung dipasang lagi.
     */
    fun setPanelEnabled(context: Context, enabled: Boolean) {
        PanelSettings.setPanelEnabled(context, enabled)
        main.post {
            if (ctx == null) return@post           // service belum jalan; nilai sudah tersimpan
            if (!enabled) endGame()
            else lastReportedPkg?.let { startGame(it) }
        }
    }

    private fun startGame(pkg: String) {
        val c = ctx ?: return
        if (gameActive && gamePkg == pkg) return
        if (gameActive) endGame()

        val label = try {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
        gamePkg = pkg
        gameName = label
        gameStartElapsed = SystemClock.elapsedRealtime()
        gameActive = true

        val gp = GameProfileStore.get(c, pkg, label)
        profile = gp
        orientationMode = gp.orientation

        // Mode performa dari profil
        PanelSettings.setActiveMode(c, gp.perfMode)
        PanelMonitor.setMode(gp.perfMode)
        PanelMonitor.setForeground(pkg, gp.fpsMonitor)

        // Notifikasi
        if (gp.notifBlock) {
            if (ToolActions.setInterruption(c, NotificationManager.INTERRUPTION_FILTER_ALARMS)) {
                PanelSettings.setTool(c, "notif_block", true)
            } else {
                toast("Blokir notifikasi butuh izin Jangan Ganggu (buka panel → Alat Game)")
            }
        }
        // Kecerahan
        if (gp.brightness in 1..100 && !ToolActions.setBrightnessPct(c, gp.brightness)) {
            toast("Kecerahan profil butuh izin Ubah pengaturan sistem")
        }
        // Prioritas game → hentikan aktivitas internal app sendiri yang tidak perlu
        if (PanelSettings.tool(c, "game_priority")) AntivirusManager.stop(c)

        applyOrientationAwake()
        applyGuards()
        if (PanelSettings.hudEnabled(c)) setHud(true)
        if (gp.autoOpenPanel) openPanel()
        Log.i(TAG, "Game mulai: $pkg (mode=${gp.perfMode})")
    }

    private fun endGame() {
        val c = ctx ?: return
        if (!gameActive) return
        gameActive = false
        closePanelNow()
        bubble?.hide()
        lock?.hide()
        cross?.hide()
        setHud(false)
        edge?.remove()
        util?.remove()
        pingJob?.cancel(); pingJob = null

        // Pulihkan hanya yang kita ubah
        ToolActions.restoreInterruption(c)
        ToolActions.restoreBrightness(c)
        for (k in listOf("notif_block", "dnd", "call_block", "cross", "touch_lock")) PanelSettings.setTool(c, k, false)
        if (PanelSettings.tool(c, "game_priority") && com.example.security.ScanStore.isRealtimeEnabled(c)) {
            AntivirusManager.start(c)
        }
        PanelSettings.setActiveMode(c, PanelSettings.defaultMode(c))
        PanelMonitor.setMode(PanelSettings.defaultMode(c))
        gamePkg = null
        profile = null
        Log.i(TAG, "Game selesai — semua overlay dilepas")
    }

    /** Hentikan total (service dihancurkan). Aman dipanggil dari thread mana pun. */
    fun shutdown() {
        if (Looper.myLooper() == Looper.getMainLooper()) doShutdown() else main.post { doShutdown() }
    }

    private fun doShutdown() {
        if (ctx == null) return
        endGame()
        hudHoldsMonitor = false
        panelHoldsMonitor = false
        PanelMonitor.shutdown()
        scope?.let { (it.coroutineContext[Job])?.cancel() }
        scope = null
        ScreenRecordService.onStateChanged = null
        ctx = null
        edge = null; hud = null; bubble = null; lock = null; util = null; cross = null; panel = null
        Log.i(TAG, "Controller dihentikan")
    }

    // ────────────────────────────────────────────────────────────────
    // Panel
    // ────────────────────────────────────────────────────────────────

    fun openPanel() {
        val c = ctx ?: return
        if (!gameActive || panel != null) return
        if (!Settings.canDrawOverlays(c)) {
            toast("Izin \"Tampil di atas aplikasi lain\" belum diberikan")
            return
        }
        bubble?.hide()
        val p = PanelWindow(c)
        if (!p.show()) return
        panel = p
        if (!panelHoldsMonitor) { PanelMonitor.acquire(); panelHoldsMonitor = true }
        PanelMonitor.requestNow()
    }

    fun closePanel() {
        val p = panel ?: return
        p.dismiss { releasePanelMonitor() }
        panel = null
    }

    private fun closePanelNow() {
        panel?.dismissNow()
        panel = null
        releasePanelMonitor()
    }

    private fun releasePanelMonitor() {
        if (panelHoldsMonitor) { PanelMonitor.release(); panelHoldsMonitor = false }
    }

    /** Ciutkan panel menjadi bubble yang bisa digeser. */
    fun minimize() {
        closePanel()
        main.postDelayed({ if (gameActive) bubble?.show() }, PanelSettings.animDurationMs(ctx ?: return) + 30L)
    }

    fun isPanelOpen(): Boolean = panel != null

    // ────────────────────────────────────────────────────────────────
    // Penerapan pengaturan
    // ────────────────────────────────────────────────────────────────

    private fun effectiveLevels(): Pair<Level, Level> {
        val c = ctx ?: return Pair(Level.OFF, Level.OFF)
        val gp = profile
        val t = if (gp != null && gp.touchProtection != Level.OFF) gp.touchProtection else PanelSettings.touchProtection(c)
        val e = if (gp != null && gp.edgeProtection != Level.OFF) gp.edgeProtection else PanelSettings.edgeProtection(c)
        return Pair(t, e)
    }

    /** Bangun ulang strip tepi (swipe + proteksi). Aman dipanggil kapan saja. */
    fun applyGuards() {
        main.post {
            if (!gameActive) { edge?.remove(); return@post }
            edge?.apply()
        }
    }

    fun applyOrientationAwake() {
        val c = ctx ?: return
        val o = when (orientationMode) {
            "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (gameActive) util?.apply(o, PanelSettings.tool(c, "keep_awake"))
    }

    fun cycleOrientation(): String {
        orientationMode = when (orientationMode) { "AUTO" -> "LANDSCAPE"; "LANDSCAPE" -> "PORTRAIT"; else -> "AUTO" }
        applyOrientationAwake()
        return orientationMode
    }

    fun setMode(m: PerfMode) {
        val c = ctx ?: return
        PanelSettings.setActiveMode(c, m)
        PanelMonitor.setMode(m)
        // simpan ke profil game aktif supaya dipakai lagi lain waktu
        val gp = profile
        if (gp != null) {
            val updated = gp.copy(perfMode = m)
            profile = updated
            GameProfileStore.put(c, updated)
        }
    }

    fun setHud(on: Boolean) {
        val c = ctx ?: return
        val h = hud ?: return
        if (on && gameActive) {
            h.show()
            if (!hudHoldsMonitor) { PanelMonitor.acquire(); hudHoldsMonitor = true }
            startPingLoop()
            h.update(PanelMonitor.snapshot.value, lastPingMs)
        } else {
            h.hide()
            if (hudHoldsMonitor) { PanelMonitor.release(); hudHoldsMonitor = false }
            pingLoopStop()
        }
    }

    fun hudShown(): Boolean = hud?.isShown == true
    fun refreshHudStyle() { hud?.refreshStyle(); hud?.update(PanelMonitor.snapshot.value, lastPingMs) }
    fun hudEditMode(on: Boolean) { if (on) hud?.setEditMode(true) }

    private fun startPingLoop() {
        if (pingJob?.isActive == true) return
        val sc = scope ?: return
        pingJob = sc.launch {
            while (isActive) {
                val c = ctx ?: break
                if (hud?.isShown == true && PanelSettings.hudMetric(c, "ping", false)) {
                    val r = NetTester.quickPing()
                    lastPingMs = r.latencyAvgMs?.let { Math.round(it) }
                    hud?.update(PanelMonitor.snapshot.value, lastPingMs)
                }
                delay(if (PanelMonitor.currentMode() == PerfMode.HEMAT) 30_000L else 12_000L)
            }
        }
    }

    private fun pingLoopStop() { pingJob?.cancel(); pingJob = null }

    fun toggleCrosshair(): Boolean {
        val c = ctx ?: return false
        val x = cross ?: return false
        return if (x.isOn) { x.hide(); PanelSettings.setTool(c, "cross", false); false }
        else { x.show(); PanelSettings.setTool(c, "cross", x.isOn); x.isOn }
    }

    fun showTouchLock() {
        val c = ctx ?: return
        closePanel()
        main.postDelayed({
            lock?.show()
            PanelSettings.setTool(c, "touch_lock", lock?.isOn == true)
        }, PanelSettings.animDurationMs(c) + 30L)
    }

    /** Ambil screenshot lewat fungsi sistem (Aksesibilitas, Android 9+). */
    fun screenshot(): String? {
        val c = ctx ?: return "Fitur tidak tersedia pada perangkat ini."
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return "Fitur tidak tersedia pada perangkat ini (butuh Android 9+)."
        val svc = com.example.service.UnifiedAccessibilityService.instance ?: return "NEED_A11Y"
        closePanel()
        main.postDelayed({
            val ok = try {
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            } catch (e: Exception) { false }
            if (!ok) toast("Tangkapan layar gagal")
        }, PanelSettings.animDurationMs(c) + 250L)
        return null
    }

    // ────────────────────────────────────────────────────────────────
    // Data game
    // ────────────────────────────────────────────────────────────────

    fun loadGames(onResult: (List<GameEntity>) -> Unit) {
        val c = ctx ?: return
        val sc = scope ?: return
        sc.launch {
            val list = withContext(Dispatchers.IO) {
                try { AppDatabase.getDatabase(c).gameDao().getAllGames() } catch (e: Exception) { emptyList() }
            }
            onResult(list)
        }
    }

    fun addGame(pkg: String, name: String, onDone: () -> Unit) {
        val c = ctx ?: return
        val sc = scope ?: return
        sc.launch {
            withContext(Dispatchers.IO) {
                try { GameBoostRepository.getInstance(c).addGame(pkg, name) } catch (e: Exception) { Log.w(TAG, "addGame: ${e.message}") }
            }
            onDone()
        }
    }

    fun removeGame(pkg: String, onDone: () -> Unit) {
        val c = ctx ?: return
        val sc = scope ?: return
        sc.launch {
            withContext(Dispatchers.IO) {
                try {
                    GameBoostRepository.getInstance(c).removeGame(pkg)
                    GameProfileStore.remove(c, pkg)
                } catch (e: Exception) { Log.w(TAG, "removeGame: ${e.message}") }
            }
            onDone()
        }
    }

    /** Aplikasi peluncur yang terpasang (kandidat game untuk ditambahkan). */
    fun installedLaunchables(): List<Pair<String, String>> {
        val c = ctx ?: return emptyList()
        val pm = c.packageManager
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(i, 0)
        return list.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != c.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    fun currentProfile(): GameProfile? = profile

    fun updateProfile(gp: GameProfile) {
        val c = ctx ?: return
        GameProfileStore.put(c, gp)
        if (gp.packageName == gamePkg) {
            profile = gp
            PanelMonitor.setForeground(gp.packageName, gp.fpsMonitor)
            applyGuards()
        }
    }

    fun toast(msg: String) {
        val c = ctx ?: return
        main.post { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
    }

    fun postDelayed(ms: Long, r: Runnable) { main.postDelayed(r, ms) }
}
