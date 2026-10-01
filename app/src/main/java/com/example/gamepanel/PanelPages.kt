package com.example.gamepanel

import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import com.example.data.database.GameEntity
import com.example.manager.ShizukuExecutor
import com.example.security.AntivirusManager
import com.example.security.ScanStore
import kotlinx.coroutines.launch

/** Pembangun semua halaman panel. Setiap halaman mendaftarkan pembaruan realtime lewat [PanelWindow.pageUpdater]. */
class PanelPages(private val ctx: Context, private val host: PanelWindow) {

    private val ctrl = GamePanelController

    // ════════════════════════════════════════════════════════════════
    // 1. ALAT GAME
    // ════════════════════════════════════════════════════════════════

    private class Tool(val tile: ToolTile, val isActive: () -> Boolean)

    fun toolsPage(registerRefresher: (() -> Unit) -> Unit): View {
        val root = vbox(ctx)
        val grid = vbox(ctx)
        val detail = vbox(ctx)
        val tools = ArrayList<Tool>()

        fun refresh() { tools.forEach { it.tile.setActive(it.isActive()) } }
        registerRefresher { refresh() }

        fun showDetail(v: View?) {
            detail.removeAllViews()
            if (v != null) detail.addView(v)
        }
        fun info(msg: String): View = card(ctx).apply { addView(tv(ctx, msg, 12f, Pal.text)) }
        fun unavailable(): View = info("Fitur tidak tersedia pada perangkat ini.")
        fun permCard(p: Perm): View = card(ctx).apply {
            addView(tv(ctx, "Permission diperlukan untuk fitur ini", 13f, Pal.orange, true))
            addView(tv(ctx, "${p.title}: ${p.why}", 11.5f, Pal.muted).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(8)) })
            addView(button(ctx, "Berikan izin") {
                ToolActions.grant(ctx, p)
                ctrl.closePanel()
            })
        }
        /** true = izin kurang (kartu izin sudah ditampilkan). */
        fun missing(p: Perm): Boolean {
            if (ToolActions.has(ctx, p)) return false
            showDetail(permCard(p)); return true
        }
        fun toggleHudMetric(key: String) {
            val nv = !PanelSettings.hudMetric(ctx, key)
            PanelSettings.setHudMetric(ctx, key, nv)
            if (nv && !ctrl.hudShown()) {
                PanelSettings.setHudEnabled(ctx, true)
                ctrl.setHud(true)
            }
            ctrl.refreshHudStyle()
            showDetail(info(if (nv) "Ditampilkan di HUD" else "Disembunyikan dari HUD"))
        }
        fun hudHas(key: String) = ctrl.hudShown() && PanelSettings.hudMetric(ctx, key)

        fun add(@androidx.annotation.DrawableRes icon: Int, label: String, active: () -> Boolean = { false }, onClick: () -> Unit) {
            val t = ToolTile(ctx, icon, label) { onClick(); refresh() }
            tools.add(Tool(t, active))
        }

        // ── Layar ──
        add(R.drawable.ic_camera, "Tangkapan layar") {
            when (val r = ctrl.screenshot()) {
                null -> {}
                "NEED_A11Y" -> showDetail(permCard(Perm.ACCESSIBILITY))
                else -> showDetail(info(r))
            }
        }
        add(R.drawable.ic_record, "Rekam layar", { ScreenRecordService.isRecording }) {
            if (ScreenRecordService.isRecording) { ScreenRecordService.stop(ctx); showDetail(info("Rekaman dihentikan & disimpan")) }
            else { ToolActions.grant(ctx, Perm.PROJECTION); ctrl.closePanel() }
        }
        add(R.drawable.ic_sun, "Kecerahan") {
            if (missing(Perm.WRITE_SETTINGS)) return@add
            showDetail(card(ctx).apply {
                addView(seekRow(ctx, "Kecerahan", 1, 100, ToolActions.getBrightnessPct(ctx) ?: 50, { "$it%" }) {
                    ToolActions.setBrightnessPct(ctx, it)
                })
            })
        }
        add(R.drawable.ic_volume, "Volume") {
            showDetail(card(ctx).apply {
                addView(seekRow(ctx, "Volume media", 0, 100, ToolActions.mediaVolumePct(ctx), { "$it%" }) {
                    ToolActions.setMediaVolumePct(ctx, it)
                })
            })
        }
        add(R.drawable.ic_volume_off, "Bisukan game", { ToolActions.isGameMuted(ctx) }) { ToolActions.toggleGameMute(ctx) }
        add(R.drawable.ic_rotate_lock, "Kunci rotasi", { !ToolActions.autoRotateOn(ctx) }) {
            if (missing(Perm.WRITE_SETTINGS)) return@add
            ToolActions.setAutoRotate(ctx, !ToolActions.autoRotateOn(ctx))
        }
        add(R.drawable.ic_compass, "Orientasi: ${ctrl.orientationMode.lowercase()}", { ctrl.orientationMode != "AUTO" }) {
            val m = ctrl.cycleOrientation()
            showDetail(info("Orientasi dikunci: $m (memakai jendela sistem 1×1 px; hasil bergantung ROM)"))
        }
        add(R.drawable.ic_monitor, "Layar tetap menyala", { PanelSettings.tool(ctx, "keep_awake") }) {
            val nv = !PanelSettings.tool(ctx, "keep_awake")
            PanelSettings.setTool(ctx, "keep_awake", nv)
            ctrl.applyOrientationAwake()
        }

        // ── Notifikasi & panggilan ──
        add(R.drawable.ic_moon, "Jangan Ganggu", { PanelSettings.tool(ctx, "dnd") && ToolActions.isDndOn(ctx) }) {
            if (missing(Perm.DND)) return@add
            val nv = !(PanelSettings.tool(ctx, "dnd") && ToolActions.isDndOn(ctx))
            if (nv) ToolActions.setInterruption(ctx, NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            else ToolActions.restoreInterruption(ctx)
            PanelSettings.setTool(ctx, "dnd", nv)
            PanelSettings.setTool(ctx, "notif_block", false)
        }
        add(R.drawable.ic_bell_off, "Blokir notifikasi", { PanelSettings.tool(ctx, "notif_block") && ToolActions.isDndOn(ctx) }) {
            if (missing(Perm.DND)) return@add
            val nv = !(PanelSettings.tool(ctx, "notif_block") && ToolActions.isDndOn(ctx))
            if (nv) ToolActions.setInterruption(ctx, NotificationManager.INTERRUPTION_FILTER_ALARMS)
            else ToolActions.restoreInterruption(ctx)
            PanelSettings.setTool(ctx, "notif_block", nv)
            PanelSettings.setTool(ctx, "dnd", false)
            showDetail(info(if (nv) "Notifikasi diblokir lewat mode Jangan Ganggu resmi (alarm tetap bunyi). Dipulihkan saat game selesai." else "Notifikasi kembali normal"))
        }
        add(R.drawable.ic_phone_off, "Tolak panggilan", { PanelSettings.tool(ctx, "call_block") }) {
            if (!ToolActions.callRoleSupported()) { showDetail(unavailable()); return@add }
            if (missing(Perm.CALL_ROLE)) return@add
            val nv = !PanelSettings.tool(ctx, "call_block")
            PanelSettings.setTool(ctx, "call_block", nv)
            showDetail(info(if (nv) "Panggilan masuk akan ditolak otomatis selama game berjalan" else "Penolak panggilan dimatikan"))
        }
        add(R.drawable.ic_target, "Prioritas game", { PanelSettings.tool(ctx, "game_priority") }) {
            val nv = !PanelSettings.tool(ctx, "game_priority")
            PanelSettings.setTool(ctx, "game_priority", nv)
            if (nv) AntivirusManager.stop(ctx)
            else if (ScanStore.isRealtimeEnabled(ctx)) AntivirusManager.start(ctx)
            showDetail(info(if (nv) "Aktivitas internal Game Booster (pemindai antivirus latar belakang) dijeda selama game. Proses aplikasi lain TIDAK dimatikan." else "Aktivitas internal dilanjutkan"))
        }

        // ── Koneksi ──
        add(R.drawable.ic_wifi, "Wi-Fi", { ToolActions.wifiOn(ctx) == true }) { showDetail(info(ToolActions.toggleWifi(ctx))); if (Build.VERSION.SDK_INT >= 29) ctrl.closePanel() }
        add(R.drawable.ic_bluetooth, "Bluetooth", { ToolActions.bluetoothOn(ctx) == true }) { showDetail(info(ToolActions.openBluetooth(ctx))); ctrl.closePanel() }

        // ── Sentuhan ──
        add(R.drawable.ic_lock, "Kunci sentuh") { ctrl.showTouchLock() }
        add(R.drawable.ic_shield_touch, "Cegah salah sentuh", { PanelSettings.touchProtection(ctx) != Level.OFF || PanelSettings.edgeProtection(ctx) != Level.OFF }) {
            val on = PanelSettings.touchProtection(ctx) != Level.OFF || PanelSettings.edgeProtection(ctx) != Level.OFF
            PanelSettings.setTouchProtection(ctx, if (on) Level.OFF else Level.MEDIUM)
            PanelSettings.setEdgeProtection(ctx, if (on) Level.OFF else Level.MEDIUM)
            ctrl.applyGuards()
            showDetail(info(if (on) "Proteksi sentuhan dimatikan" else "Proteksi sentuhan & tepi: MEDIUM. Atur detail di tab Sentuhan."))
        }
        add(R.drawable.ic_pointer, "Tampilkan sentuhan", { ToolActions.showTouchesOn(ctx) }) {
            host.scope.launch {
                val target = !ToolActions.showTouchesOn(ctx)
                val ok = ToolActions.setShowTouches(ctx, target)
                showDetail(info(if (ok) "Visualisasi sentuhan ${if (target) "aktif" else "mati"}"
                else "Fitur tidak tersedia: butuh izin WRITE_SECURE_SETTINGS atau Shizuku aktif."))
                refresh()
            }
        }

        // ── Performa & monitor ──
        add(R.drawable.ic_zap, "Mode: ${PanelSettings.activeMode(ctx).label}") { host.openTab(PanelTab.PERF) }
        add(R.drawable.ic_battery, "Mode baterai", { PanelMonitor.currentMode() == PerfMode.HEMAT }) {
            ctrl.setMode(if (PanelMonitor.currentMode() == PerfMode.HEMAT) PerfMode.SEIMBANG else PerfMode.HEMAT)
        }
        add(R.drawable.ic_globe, "Monitor jaringan") { host.openTab(PanelTab.NET) }
        add(R.drawable.ic_film, "Monitor FPS", { hudHas("fps") }) { toggleHudMetric("fps") }
        add(R.drawable.ic_memory, "Monitor RAM", { hudHas("ram") }) { toggleHudMetric("ram") }
        add(R.drawable.ic_cpu, "Monitor CPU", { hudHas("cpu") }) { toggleHudMetric("cpu") }
        add(R.drawable.ic_thermo, "Monitor suhu", { hudHas("temp") }) { toggleHudMetric("temp") }
        add(R.drawable.ic_chart, "HUD", { ctrl.hudShown() }) {
            val nv = !ctrl.hudShown()
            PanelSettings.setHudEnabled(ctx, nv)
            ctrl.setHud(nv)
        }

        // ── Utilitas ──
        add(R.drawable.ic_note, "Catatan cepat") { showDetail(MiniTools.notes(ctx, host)) }
        add(R.drawable.ic_timer, "Timer") { showDetail(MiniTools.timer(ctx)) }
        add(R.drawable.ic_stopwatch, "Stopwatch") { showDetail(MiniTools.stopwatch(ctx)) }
        add(R.drawable.ic_calculator, "Kalkulator") { showDetail(MiniTools.calculator(ctx)) }
        add(R.drawable.ic_crosshair, "Crosshair", { PanelSettings.tool(ctx, "cross") }) {
            val on = ctrl.toggleCrosshair()
            showDetail(info(if (on) "Crosshair aktif di tengah layar. Catatan: sebagian game melarang overlay pembidik — pakai dengan risiko sendiri."
            else "Crosshair dimatikan"))
        }
        add(R.drawable.ic_bubble, "Bubble mengambang") { ctrl.minimize() }

        // Susun grid: 4 kolom jika lebar cukup, kalau tidak 3
        val contentDp = (realScreenSize(ctx).x * PanelSettings.panelSizePercent(ctx) / 100f) / ctx.resources.displayMetrics.density - 84f
        val cols = if (contentDp >= 300f) 4 else 3
        var row: LinearLayout? = null
        tools.forEachIndexed { i, t ->
            if (i % cols == 0) {
                row = hbox(ctx)
                grid.addView(row)
            }
            row!!.addView(t.tile.view, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // isi sisa kolom agar lebar tile seragam
        val rest = tools.size % cols
        if (rest != 0) for (k in 0 until cols - rest) row?.addView(View(ctx), lp(0, 1, 1f))

        root.addView(grid)
        root.addView(spacer(ctx, 6))
        root.addView(detail)
        refresh()
        return root
    }

    // ════════════════════════════════════════════════════════════════
    // 2. KINERJA
    // ════════════════════════════════════════════════════════════════

    fun performancePage(): View {
        val root = vbox(ctx)
        val updaters = ArrayList<(PerfSnapshot) -> Unit>()
        val accent = Pal.accent(PanelSettings.theme(ctx))

        // Gauge CPU / GPU
        val gauges = hbox(ctx).apply { gravity = Gravity.CENTER }
        val cpuG = GaugeView(ctx, "CPU")
        val gpuG = GaugeView(ctx, "GPU")
        gauges.addView(cpuG, lp(0, ctx.dp(104), 1f))
        gauges.addView(gpuG, lp(0, ctx.dp(104), 1f))
        root.addView(gauges)

        val foot = hbox(ctx)
        val durTv = tv(ctx, "Durasi game\n${host.gameDurationText()}", 11f, Pal.muted)
        val batTv = tv(ctx, "Baterai tersisa\n…", 11f, Pal.muted).apply { gravity = Gravity.END }
        foot.addView(durTv, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foot.addView(batTv, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(foot)
        root.addView(spacer(ctx, 8))

        // Mode performa
        val modes = PerfMode.values()
        val modeDesc = tv(ctx, "", 11f, Pal.muted).apply { setPadding(ctx.dp(2), ctx.dp(6), ctx.dp(2), ctx.dp(2)) }
        fun descOf(m: PerfMode): String = when (m) {
            PerfMode.HEMAT -> "Sampling monitor dilambatkan (5 dtk), prioritas thread rendah, overlay diringankan. Tidak mengubah CPU/kernel."
            PerfMode.SEIMBANG -> "Konfigurasi normal: sampling 2 dtk."
            PerfMode.BOOST -> "Sampling 1 dtk dengan prioritas thread tinggi untuk proses aplikasi ini" +
                (if (Build.VERSION.SDK_INT >= 31) (if (PanelMonitor.hintActive()) ", PerformanceHint aktif." else ", PerformanceHint tidak tersedia.") else ".") +
                " Hanya memengaruhi proses Game Booster sendiri; game & aplikasi lain tidak dimatikan."
        }
        val seg = Segmented(ctx, modes.map { it.label }, modes.indexOf(PanelSettings.activeMode(ctx)), accent) { i ->
            ctrl.setMode(modes[i])
            modeDesc.text = descOf(modes[i])
        }
        root.addView(seg.view)
        modeDesc.text = descOf(PanelSettings.activeMode(ctx))
        root.addView(modeDesc)

        // Kartu info
        fun infoCard(title: String, producer: (PerfSnapshot) -> String) {
            val c = card(ctx)
            c.addView(tv(ctx, title, 12f, Pal.muted, true))
            val body = tv(ctx, "…", 12f, Pal.text).apply { setPadding(0, ctx.dp(4), 0, 0); setLineSpacing(ctx.dpf(2f), 1f) }
            c.addView(body)
            root.addView(c)
            updaters.add { s -> body.text = producer(s) }
        }

        root.addView(sectionTitle(ctx, "Detail"))
        infoCard("CPU") { s ->
            val freqs = if (s.cpuFreqMhz.isEmpty()) "N/A" else s.cpuFreqMhz.mapIndexed { i, f -> "c$i:${f}" }.joinToString(" ") + " MHz"
            "Pemakaian: ${s.cpuPct?.let { "$it%" } ?: "N/A (Android 8+ memblokir /proc/stat; aktifkan Shizuku)"}\n" +
                "Core: ${s.cpuCores}\nFrekuensi: $freqs\nBeban rata-rata: ${s.loadAvg ?: "N/A"}\n" +
                "Suhu CPU: ${s.cpuTempC?.let { String.format(java.util.Locale.US, "%.1f°C", it) } ?: "N/A"}" +
                (s.thermalStatus?.let { "\nStatus termal sistem: $it" } ?: "")
        }
        infoCard("GPU") { s ->
            "Perangkat: ${s.gpuName ?: "N/A"}\nPemakaian: ${s.gpuPct?.let { "$it%" } ?: "N/A (sysfs GPU tidak bisa dibaca)"}\n" +
                "Frekuensi: ${s.gpuFreqMhz?.let { "$it MHz" } ?: "N/A"}"
        }
        infoCard("RAM") { s ->
            "Total: ${fmtBytes(s.ramTotal)}\nTerpakai: ${fmtBytes(s.ramUsed)} (${s.ramPct}%)\nTersedia: ${fmtBytes(s.ramAvail)}"
        }
        infoCard("Baterai") { s ->
            "Level: ${s.batteryPct?.let { "$it%" } ?: "N/A"}\nStatus: ${when (s.charging) { true -> "Mengisi daya"; false -> "Tidak mengisi"; null -> "N/A" }}\n" +
                "Suhu: ${s.batteryTempC?.let { String.format(java.util.Locale.US, "%.1f°C", it) } ?: "N/A"}\n" +
                "Tegangan: ${s.batteryMv?.let { "$it mV" } ?: "N/A"}"
        }
        infoCard("Layar") { s ->
            "Refresh rate saat ini: ${String.format(java.util.Locale.US, "%.0f", s.refreshHz)} Hz\n" +
                "Didukung: ${if (s.supportedHz.isEmpty()) "N/A" else s.supportedHz.joinToString(" / ") { String.format(java.util.Locale.US, "%.0f", it) } + " Hz"}\n" +
                "Resolusi: ${s.resolution}\nKecerahan: ${s.brightnessPct?.let { "$it%" } ?: "N/A"}"
        }
        infoCard("FPS") { s ->
            (s.fps?.let { "$it FPS\n" } ?: "N/A\n") + s.fpsNote
        }
        infoCard("Jaringan") { s ->
            "Jenis: ${s.netType}" + (s.wifiRssiDbm?.let { "\nSinyal Wi-Fi: $it dBm" } ?: "") +
                (s.wifiLinkMbps?.let { "\nKecepatan link: $it Mbps" } ?: "")
        }

        // Riwayat
        root.addView(sectionTitle(ctx, "Riwayat singkat"))
        val hist = card(ctx)
        val cpuS = SparklineView(ctx, "CPU", "%", Pal.ember)
        val ramS = SparklineView(ctx, "RAM", "%", Pal.green)
        val tmpS = SparklineView(ctx, "Suhu", "°C", Pal.orange)
        val batS = SparklineView(ctx, "Baterai", "%", Pal.yellow)
        for (sv in listOf(cpuS, ramS, tmpS, batS)) {
            hist.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(54)).also { it.bottomMargin = ctx.dp(6) })
        }
        root.addView(hist)

        host.pageUpdater = { s ->
            cpuG.set(s.cpuPct, if ((s.cpuPct ?: 0) > 85) Pal.red else accent)
            gpuG.set(s.gpuPct, if ((s.gpuPct ?: 0) > 85) Pal.red else accent)
            durTv.text = "Durasi game\n${host.gameDurationText()}"
            batTv.text = "Baterai tersisa\n${s.batteryPct?.let { "$it%" } ?: "N/A"}"
            updaters.forEach { it(s) }
            cpuS.set(PanelMonitor.cpuHistory.snapshot())
            ramS.set(PanelMonitor.ramHistory.snapshot())
            tmpS.set(PanelMonitor.tempHistory.snapshot())
            batS.set(PanelMonitor.batteryHistory.snapshot())
        }
        return root
    }

    // ════════════════════════════════════════════════════════════════
    // 3. JARINGAN
    // ════════════════════════════════════════════════════════════════

    fun networkPage(): View {
        val root = vbox(ctx)
        val liveCard = card(ctx)
        liveCard.addView(tv(ctx, "STATUS JARINGAN", 11f, Pal.emberSoft, true))
        val live = tv(ctx, "…", 12f, Pal.text).apply { setPadding(0, ctx.dp(4), 0, 0); setLineSpacing(ctx.dpf(2f), 1f) }
        liveCard.addView(live)
        root.addView(liveCard)

        val resCard = card(ctx)
        resCard.addView(tv(ctx, "HASIL TES", 11f, Pal.emberSoft, true))
        val res = tv(ctx, "", 12f, Pal.text).apply { setPadding(0, ctx.dp(4), 0, 0); setLineSpacing(ctx.dpf(2f), 1f) }
        resCard.addView(res)
        root.addView(resCard)

        fun render(r: NetResult?) {
            if (r == null) { res.text = "Belum ada tes. Tekan TEST NETWORK."; return }
            fun f(v: Float?, u: String) = if (v == null) "N/A" else String.format(java.util.Locale.US, "%.1f %s", v, u)
            res.text = "Latensi rata-rata: ${f(r.latencyAvgMs, "ms")}\n" +
                "Min / Maks: ${f(r.latencyMinMs, "ms")} / ${f(r.latencyMaxMs, "ms")}\n" +
                "Packet loss: ${f(r.lossPct, "%")}\n" +
                "Download: ${f(r.downloadMbps, "Mbps")}\nUpload: ${f(r.uploadMbps, "Mbps")}\n" +
                "Metode ping: ${r.method}" + (r.error?.let { "\nCatatan: $it" } ?: "")
        }
        render(ctrl.lastNet)

        val status = tv(ctx, "", 11f, Pal.muted).apply { setPadding(ctx.dp(2), ctx.dp(4), 0, ctx.dp(4)) }
        var busy = false
        val row = hbox(ctx)
        val testBtn = button(ctx, "TEST NETWORK") {}
        val refreshBtn = button(ctx, "REFRESH", false) {}
        fun run(full: Boolean) {
            if (busy) return
            busy = true
            status.text = if (full) "Menguji… (ping, download, upload — ±15 dtk)" else "Mengukur ping…"
            host.scope.launch {
                val r = if (full) NetTester.full() else NetTester.quickPing()
                val merged = if (!full && ctrl.lastNet != null)
                    r.copy(downloadMbps = ctrl.lastNet?.downloadMbps, uploadMbps = ctrl.lastNet?.uploadMbps) else r
                ctrl.lastNet = merged
                ctrl.lastPingMs = r.latencyAvgMs?.let { Math.round(it) }
                render(merged)
                status.text = "Selesai"
                busy = false
            }
        }
        testBtn.setOnClickListener { run(true) }
        refreshBtn.setOnClickListener { PanelMonitor.requestNow(); run(false) }
        row.addView(testBtn, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = ctx.dp(6) })
        row.addView(refreshBtn, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        root.addView(status)
        root.addView(tv(ctx, "Tes memakai data internet (±8 MB). Semua pengukuran berjalan di thread latar belakang.", 10.5f, Pal.muted))

        host.pageUpdater = { s ->
            val level = s.wifiRssiDbm?.let { when { it >= -55 -> "Sangat baik"; it >= -67 -> "Baik"; it >= -80 -> "Cukup"; else -> "Lemah" } }
            live.text = "Jenis koneksi: ${s.netType}\n" +
                "Sinyal Wi-Fi: ${s.wifiRssiDbm?.let { "$it dBm ($level)" } ?: "N/A"}\n" +
                "Kecepatan link: ${s.wifiLinkMbps?.let { "$it Mbps" } ?: "N/A"}\n" +
                "Nama jaringan: butuh izin lokasi — tidak diminta"
        }
        return root
    }

    // ════════════════════════════════════════════════════════════════
    // 4. SENTUHAN
    // ════════════════════════════════════════════════════════════════

    fun touchPage(): View {
        val root = vbox(ctx)

        val infoC = card(ctx)
        infoC.addView(tv(ctx, "INFORMASI SENTUHAN", 11f, Pal.emberSoft, true))
        val ts = touchscreenInfo()
        val hz = PanelMonitor.snapshot.value?.refreshHz
        val pointer = try { Settings.System.getInt(ctx.contentResolver, "pointer_speed") } catch (e: Exception) { null }
        infoC.addView(tv(ctx,
            "Layar sentuh: ${ts ?: "N/A"}\n" +
                "Refresh layar: ${hz?.let { String.format(java.util.Locale.US, "%.0f Hz", it) } ?: "N/A"}\n" +
                "Kecepatan pointer: ${pointer ?: "N/A"}\n" +
                "Sampling rate sentuh perangkat keras: N/A — Android tidak menyediakan akses ini untuk aplikasi biasa, jadi tidak diubah maupun diklaim.",
            12f, Pal.text).apply { setPadding(0, ctx.dp(4), 0, 0); setLineSpacing(ctx.dpf(2f), 1f) })
        root.addView(infoC)

        val levels = Level.values()
        fun levelSeg(cur: Level, onSel: (Level) -> Unit) =
            Segmented(ctx, levels.map { it.label }, levels.indexOf(cur), Pal.accent(PanelSettings.theme(ctx))) { onSel(levels[it]) }

        val prot = card(ctx)
        prot.addView(tv(ctx, "PROTEKSI SENTUHAN (gestur atas/bawah)", 11f, Pal.emberSoft, true))
        prot.addView(tv(ctx, "Menelan sentuhan di tepi atas & bawah agar swipe sistem (panel notifikasi, home) tidak terpicu tak sengaja. Lebar: LOW 12 dp · MEDIUM 24 dp · HIGH 40 dp.", 11f, Pal.muted).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(6)) })
        prot.addView(levelSeg(PanelSettings.touchProtection(ctx)) {
            PanelSettings.setTouchProtection(ctx, it); ctrl.applyGuards()
        }.view)
        root.addView(prot)

        val edge = card(ctx)
        edge.addView(tv(ctx, "PROTEKSI TEPI (kiri & kanan)", 11f, Pal.emberSoft, true))
        edge.addView(tv(ctx, "Menelan sentuhan telapak di sisi layar. Perhatian: tombol game yang persis di tepi bisa ikut terblokir — turunkan level jika terasa.", 11f, Pal.muted).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(6)) })
        edge.addView(levelSeg(PanelSettings.edgeProtection(ctx)) {
            PanelSettings.setEdgeProtection(ctx, it); ctrl.applyGuards()
        }.view)
        root.addView(edge)

        val act = card(ctx)
        act.addView(tv(ctx, "PERLINDUNGAN GESTUR", 11f, Pal.emberSoft, true))
        act.addView(tv(ctx, "Kunci sentuh menutup seluruh layar; buka dengan menahan tombol 1,5 detik.", 11f, Pal.muted).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(8)) })
        act.addView(button(ctx, "KUNCI SENTUH SEKARANG") { ctrl.showTouchLock() })
        root.addView(act)
        return root
    }

    private fun touchscreenInfo(): String? {
        return try {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                if (d.sources and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN) {
                    val x = d.getMotionRange(android.view.MotionEvent.AXIS_X)
                    val y = d.getMotionRange(android.view.MotionEvent.AXIS_Y)
                    return d.name + if (x != null && y != null) " (${x.max.toInt()}×${y.max.toInt()})" else ""
                }
            }
            null
        } catch (e: Exception) { null }
    }

    // ════════════════════════════════════════════════════════════════
    // 5. GAME (daftar, tambah, hapus, luncurkan, profil per-game)
    // ════════════════════════════════════════════════════════════════

    fun gamesPage(): View {
        val root = vbox(ctx)
        val listBox = vbox(ctx)
        val editor = vbox(ctx)
        val picker = vbox(ctx)
        root.addView(listBox); root.addView(editor); root.addView(picker)
        var games: List<GameEntity> = emptyList()

        fun renderPicker(filter: String, container: LinearLayout) {
            container.removeAllViews()
            val have = games.map { it.packageName }.toSet()
            val cands = ctrl.installedLaunchables().filter { it.first !in have && (filter.isBlank() || it.second.contains(filter, true)) }
            if (cands.isEmpty()) container.addView(tv(ctx, "Tidak ada aplikasi yang cocok.", 12f, Pal.muted))
            for ((pkg, name) in cands.take(60)) {
                val r = hbox(ctx).apply { setPadding(0, ctx.dp(5), 0, ctx.dp(5)) }
                try { r.addView(ImageView(ctx).apply { setImageDrawable(ctx.packageManager.getApplicationIcon(pkg)) }, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32))) } catch (e: Exception) { /* tanpa ikon */ }
                val col = vbox(ctx).apply { setPadding(ctx.dp(8), 0, 0, 0) }
                col.addView(tv(ctx, name, 12.5f, Pal.text, true))
                col.addView(tv(ctx, pkg, 9.5f, Pal.muted))
                r.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(button(ctx, "TAMBAH") {
                    ctrl.addGame(pkg, name) { host.openTab(PanelTab.GAMES) }
                })
                container.addView(r)
            }
        }

        fun openEditor(g: GameEntity) {
            editor.removeAllViews()
            var gp = GameProfileStore.get(ctx, g.packageName, g.displayName)
            fun save(n: GameProfile) { gp = n; ctrl.updateProfile(n) }
            val c = card(ctx)
            c.addView(tv(ctx, "PROFIL: ${g.displayName}", 11f, Pal.emberSoft, true))
            c.addView(tv(ctx, g.packageName, 9.5f, Pal.muted).apply { setPadding(0, 0, 0, ctx.dp(6)) })

            val modes = PerfMode.values()
            c.addView(tv(ctx, "Mode performa", 12f, Pal.text, true))
            c.addView(Segmented(ctx, modes.map { it.label }, modes.indexOf(gp.perfMode)) { save(gp.copy(perfMode = modes[it])) }.view)
            val levels = Level.values()
            c.addView(tv(ctx, "Proteksi sentuhan", 12f, Pal.text, true).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(2)) })
            c.addView(Segmented(ctx, levels.map { it.label }, levels.indexOf(gp.touchProtection)) { save(gp.copy(touchProtection = levels[it])) }.view)
            c.addView(tv(ctx, "Proteksi tepi", 12f, Pal.text, true).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(2)) })
            c.addView(Segmented(ctx, levels.map { it.label }, levels.indexOf(gp.edgeProtection)) { save(gp.copy(edgeProtection = levels[it])) }.view)
            val orients = listOf("AUTO", "LANDSCAPE", "PORTRAIT")
            c.addView(tv(ctx, "Orientasi", 12f, Pal.text, true).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(2)) })
            c.addView(Segmented(ctx, listOf("Otomatis", "Landscape", "Portrait"), orients.indexOf(gp.orientation).coerceAtLeast(0)) { save(gp.copy(orientation = orients[it])) }.view)
            c.addView(switchRow(ctx, "Blokir notifikasi", "Butuh izin Jangan Ganggu", gp.notifBlock) { save(gp.copy(notifBlock = it)) })
            c.addView(switchRow(ctx, "Atur kecerahan otomatis", "Butuh izin Ubah pengaturan sistem", gp.brightness > 0) {
                save(gp.copy(brightness = if (it) 70 else -1)); openEditor(g)
            })
            if (gp.brightness > 0) c.addView(seekRow(ctx, "Kecerahan game", 1, 100, gp.brightness, { "$it%" }) { save(gp.copy(brightness = it)) })
            c.addView(switchRow(ctx, "Monitor jaringan", null, gp.networkMonitor) { save(gp.copy(networkMonitor = it)) })
            c.addView(switchRow(ctx, "Monitor FPS", "FPS game butuh Shizuku", gp.fpsMonitor) { save(gp.copy(fpsMonitor = it)) })
            c.addView(switchRow(ctx, "Buka panel otomatis saat game mulai", "Bawaan MATI — panel normalnya hanya muncul lewat gestur", gp.autoOpenPanel) { save(gp.copy(autoOpenPanel = it)) })
            editor.addView(c)
        }

        fun renderList() {
            listBox.removeAllViews()
            listBox.addView(sectionTitle(ctx, "Daftar game (${games.size})"))
            if (games.isEmpty()) listBox.addView(tv(ctx, "Belum ada game. Tekan + TAMBAH GAME.", 12f, Pal.muted))
            for (g in games) {
                val c = card(ctx)
                val top = hbox(ctx)
                try { top.addView(ImageView(ctx).apply { setImageDrawable(ctx.packageManager.getApplicationIcon(g.packageName)) }, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36))) } catch (e: Exception) { /* belum terpasang */ }
                val col = vbox(ctx).apply { setPadding(ctx.dp(8), 0, 0, 0) }
                col.addView(tv(ctx, g.displayName, 13f, Pal.text, true))
                val gp = GameProfileStore.get(ctx, g.packageName, g.displayName)
                col.addView(tv(ctx, "${gp.perfMode.label} · sentuh ${gp.touchProtection.label} · tepi ${gp.edgeProtection.label}", 10f, Pal.muted))
                top.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                c.addView(top)
                c.addView(spacer(ctx, 6))
                val actions = hbox(ctx)
                actions.addView(button(ctx, "LUNCURKAN") {
                    if (ToolActions.launchGame(ctx, g.packageName)) ctrl.closePanel()
                    else ctrl.toast("Game belum terpasang")
                }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = ctx.dp(4) })
                actions.addView(button(ctx, "ATUR", false) { openEditor(g) }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = ctx.dp(4) })
                actions.addView(button(ctx, "HAPUS", false) {
                    ctrl.removeGame(g.packageName) { host.openTab(PanelTab.GAMES) }
                }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                c.addView(actions)
                listBox.addView(c)
            }
            val addBtn = button(ctx, "+ TAMBAH GAME") {
                if (picker.childCount > 0) { picker.removeAllViews(); return@button }
                picker.addView(sectionTitle(ctx, "Pilih aplikasi"))
                val search = EditText(ctx).apply {
                    hint = "Cari aplikasi…"; setHintTextColor(Pal.muted); setTextColor(Pal.text); textSize = 13f
                    setSingleLine(true)
                    background = rounded(Color.argb(30, 255, 255, 255), ctx.dpf(10f))
                    setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
                }
                host.enableTyping(search)
                val results = vbox(ctx)
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { renderPicker(s?.toString() ?: "", results) }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
                picker.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                picker.addView(results)
                renderPicker("", results)
            }
            listBox.addView(addBtn)
        }

        ctrl.loadGames { list ->
            games = list
            renderList()
        }
        return root
    }

    // ════════════════════════════════════════════════════════════════
    // 6. SETELAN
    // ════════════════════════════════════════════════════════════════

    fun settingsPage(): View {
        val root = vbox(ctx)
        val accent = Pal.accent(PanelSettings.theme(ctx))

        // Izin
        val perm = card(ctx)
        perm.addView(tv(ctx, "IZIN", 11f, Pal.emberSoft, true))
        fun permRow(p: Perm) {
            val ok = ToolActions.has(ctx, p)
            val r = hbox(ctx).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }
            r.addView(tv(ctx, p.title, 12f, if (ok) Pal.text else Pal.orange).also { it.leftIcon(if (ok) R.drawable.ic_check_circle else R.drawable.ic_alert, if (ok) Pal.green else Pal.orange, 16) }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!ok) r.addView(button(ctx, "Berikan izin", false) { ToolActions.grant(ctx, p); ctrl.closePanel() })
            perm.addView(r)
        }
        for (p in listOf(Perm.OVERLAY, Perm.WRITE_SETTINGS, Perm.DND, Perm.ACCESSIBILITY)) permRow(p)
        if (ToolActions.callRoleSupported()) permRow(Perm.CALL_ROLE)
        perm.addView(tv(ctx, "Shizuku: ${if (ShizukuExecutor.isReady()) "siap (FPS, CPU, GPU bisa dibaca)" else "belum aktif — FPS & sebagian metrik menampilkan N/A"}", 11.5f, Pal.muted).apply { setPadding(0, ctx.dp(4), 0, 0) })
        root.addView(perm)

        // Edge swipe
        val edge = card(ctx)
        edge.addView(tv(ctx, "EDGE SWIPE (kanan → kiri)", 11f, Pal.emberSoft, true))
        edge.addView(switchRow(ctx, "Aktifkan edge swipe", "Aktif hanya saat game berjalan", PanelSettings.edgeEnabled(ctx)) {
            PanelSettings.setEdgeEnabled(ctx, it); ctrl.applyGuards()
        })
        edge.addView(seekRow(ctx, "Lebar area tepi", 8, 48, PanelSettings.edgeWidthDp(ctx), { "$it dp" }) {
            PanelSettings.setEdgeWidthDp(ctx, it); ctrl.applyGuards()
        })
        edge.addView(seekRow(ctx, "Tinggi area gestur", 30, 100, PanelSettings.edgeHeightPercent(ctx), { "$it%" }) {
            PanelSettings.setEdgeHeightPercent(ctx, it); ctrl.applyGuards()
        })
        edge.addView(seekRow(ctx, "Jarak geser", 24, 160, PanelSettings.swipeDistanceDp(ctx), { "$it dp" }) { PanelSettings.setSwipeDistanceDp(ctx, it) })
        edge.addView(seekRow(ctx, "Sensitivitas", 1, 5, PanelSettings.sensitivity(ctx), { "$it/5" }) { PanelSettings.setSensitivity(ctx, it) })
        edge.addView(tv(ctx, "Tips anti-konflik gestur navigasi: pakai area tepi di bagian tengah layar (Tinggi < 70%), jauh dari zona back/home.", 10.5f, Pal.muted))
        root.addView(edge)

        // Panel
        val pan = card(ctx)
        pan.addView(tv(ctx, "TAMPILAN PANEL", 11f, Pal.emberSoft, true))
        pan.addView(seekRow(ctx, "Opasitas panel", 40, 100, PanelSettings.panelOpacity(ctx), { "$it%" }) { PanelSettings.setPanelOpacity(ctx, it) })
        pan.addView(seekRow(ctx, "Ukuran panel", 40, 90, PanelSettings.panelSizePercent(ctx), { "$it%" }) { PanelSettings.setPanelSizePercent(ctx, it) })
        pan.addView(seekRow(ctx, "Kecepatan animasi", 1, 3, PanelSettings.animSpeed(ctx), { when (it) { 1 -> "Lambat"; 3 -> "Cepat"; else -> "Normal" } }) { PanelSettings.setAnimSpeed(ctx, it) })
        val themes = listOf("cyan", "green", "orange", "purple")
        pan.addView(tv(ctx, "Tema aksen", 13f, Pal.text, true).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(4)) })
        pan.addView(Segmented(ctx, listOf("Api", "Hijau", "Emas", "Biru"), themes.indexOf(PanelSettings.theme(ctx)).coerceAtLeast(0), accent) {
            PanelSettings.setTheme(ctx, themes[it]); ctrl.refreshHudStyle()
        }.view)
        pan.addView(tv(ctx, "Opasitas & ukuran berlaku saat panel dibuka lagi.", 10.5f, Pal.muted).apply { setPadding(0, ctx.dp(4), 0, 0) })
        root.addView(pan)

        // HUD
        val hud = card(ctx)
        hud.addView(tv(ctx, "HUD PERFORMA", 11f, Pal.emberSoft, true))
        hud.addView(switchRow(ctx, "Tampilkan HUD", "Tidak menghalangi sentuhan ke game", PanelSettings.hudEnabled(ctx)) {
            PanelSettings.setHudEnabled(ctx, it); ctrl.setHud(it)
        })
        hud.addView(seekRow(ctx, "Opasitas HUD", 30, 100, PanelSettings.hudOpacity(ctx), { "$it%" }) { PanelSettings.setHudOpacity(ctx, it); ctrl.refreshHudStyle() })
        hud.addView(seekRow(ctx, "Ukuran teks HUD", 9, 18, PanelSettings.hudSizeSp(ctx), { "$it sp" }) { PanelSettings.setHudSizeSp(ctx, it); ctrl.refreshHudStyle() })
        hud.addView(tv(ctx, "Posisi HUD", 13f, Pal.text, true).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(4)) })
        val corner = PanelSettings.hudCorner(ctx).coerceIn(0, 4)
        hud.addView(Segmented(ctx, listOf("↖", "↗", "↙", "↘", "Bebas"), corner, accent) {
            PanelSettings.setHudCorner(ctx, it); ctrl.refreshHudStyle()
        }.view)
        hud.addView(spacer(ctx, 6))
        hud.addView(button(ctx, "ATUR POSISI DENGAN DRAG", false) {
            if (!ctrl.hudShown()) { PanelSettings.setHudEnabled(ctx, true); ctrl.setHud(true) }
            ctrl.hudEditMode(true)
            ctrl.closePanel()
            ctrl.toast("Seret HUD ke posisi baru lalu ketuk \"Selesai atur posisi\"")
        })
        hud.addView(tv(ctx, "Metrik ditampilkan", 13f, Pal.text, true).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(2)) })
        for ((k, label) in listOf("fps" to "FPS", "cpu" to "CPU", "gpu" to "GPU", "ram" to "RAM", "temp" to "Suhu", "bat" to "Baterai", "ping" to "Ping")) {
            hud.addView(switchRow(ctx, label, if (k == "ping") "Mengukur ping tiap ±12 dtk" else null, PanelSettings.hudMetric(ctx, k)) {
                PanelSettings.setHudMetric(ctx, k, it); ctrl.refreshHudStyle()
            })
        }
        root.addView(hud)

        // Umum
        val gen = card(ctx)
        gen.addView(tv(ctx, "UMUM", 11f, Pal.emberSoft, true))
        gen.addView(switchRow(ctx, "Layanan otomatis saat boot", "Panel siap tanpa membuka aplikasi", PanelSettings.autoStart(ctx)) { PanelSettings.setAutoStart(ctx, it) })
        val modes = PerfMode.values()
        gen.addView(tv(ctx, "Mode performa bawaan", 13f, Pal.text, true).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(4)) })
        gen.addView(Segmented(ctx, modes.map { it.label }, modes.indexOf(PanelSettings.defaultMode(ctx)), accent) { PanelSettings.setDefaultMode(ctx, modes[it]) }.view)
        gen.addView(spacer(ctx, 10))
        var armed = false
        val reset = button(ctx, "RESET SEMUA PENGATURAN", false) {}
        reset.setOnClickListener {
            if (!armed) { armed = true; reset.text = "KETUK LAGI UNTUK KONFIRMASI"; reset.setTextColor(Pal.red) }
            else {
                PanelSettings.resetAll(ctx)
                ctrl.setHud(false); ctrl.applyGuards()
                ctrl.toast("Pengaturan panel direset")
                host.openTab(PanelTab.SETTINGS)
            }
        }
        gen.addView(reset)
        root.addView(gen)
        return root
    }
}
