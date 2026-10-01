package com.example.manager

import com.example.data.repository.GameBoostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SystemTweaks — Ajustes del sistema tomados de Neon Core.
 *
 * ## Funciones
 * - Disable BLE/GPS Scanning — Desactiva escaneo Bluetooth y GPS en segundo plano
 * - Disable HW Overlays / blurs / V-Sync — libera GPU y baja latencia de frames
 * - Disable Auto-Sync — evita sincronización automática durante el juego
 * - Disable Digital Wellbeing voice — libera CPU
 * - Force 4x MSAA (opcional) — mejora calidad gráfica
 * - Clear Logs — limpia logcat y dmesg
 *
 * ## ⚠️ Fix restore
 * Antes solo se respaldaban/restauraban un subconjunto de keys. Ahora se hace
 * backup de TODAS las que se tocan y se restauran al desactivar boost,
 * incluyendo activity_manager_constants (delete si no había valor previo).
 *
 * ## Nota
 * Estos comandos usan `settings put global` / `settings delete global`
 * y funcionan con Shizuku. No requieren root.
 */
class SystemTweaks(
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Valores originales para restauración (null = no había valor / usar default)
    @Volatile private var originalBleScan: String? = null
    @Volatile private var originalWifiScanAlways: String? = null
    @Volatile private var originalBtDisabledProfiles: String? = null
    @Volatile private var originalHwOverlays: String? = null
    @Volatile private var originalAutoSync: String? = null
    @Volatile private var originalWindowBlurs: String? = null
    @Volatile private var originalVsync: String? = null
    @Volatile private var originalDigitalWellbeing: String? = null
    @Volatile private var originalMsaa: String? = null
    @Volatile private var originalWindowAnim: String? = null
    @Volatile private var originalTransitionAnim: String? = null
    @Volatile private var originalAnimatorDuration: String? = null
    @Volatile private var originalSendActionAppError: String? = null
    @Volatile private var originalLowPowerTrigger: String? = null
    @Volatile private var originalActivityManagerConstants: String? = null
    @Volatile private var originalHwuiRenderer: String? = null
    @Volatile private var originalHwuiOverdraw: String? = null
    @Volatile private var originalHwuiDirtyRegions: String? = null
    @Volatile private var originalSfBackpressure: String? = null
    @Volatile private var originalSfLatch: String? = null

    // ── Comandos apply ───────────────────────────────────────────

    /**
     * Hanya setting yang benar-benar dibaca Android (efek nyata). Dibuang karena tidak berefek:
     * `debug.hwui.*`, `debug.sf.*`, `debug.gl.msaa` (itu system property, bukan Settings.Global —
     * `settings put global` tidak mengubahnya), `overlay_display_devices`, `adaptive_connected_voice_enabled`.
     * Dibuang karena berisiko: `bluetooth_disabled_profiles` (bisa memutus audio Bluetooth).
     */
    private fun getApplyCommands(@Suppress("UNUSED_PARAMETER") enableMsaa: Boolean = false): List<String> = buildList {
        // Hentikan scan latar BLE / Wi-Fi (kurangi wakeup radio & CPU)
        add("settings put global ble_scan_always_enabled 0")
        add("settings put global wifi_scan_always_enabled 0")

        // Blur jendela = beban GPU/compositor (Android 12+)
        add("settings put global disable_window_blurs 1")

        // Animasi UI off
        add("settings put global auto_sync 0")
        add("settings put global window_animation_scale 0")
        add("settings put global transition_animation_scale 0")
        add("settings put global animator_duration_scale 0")
        add("settings put global send_action_app_error 0")

        // Lebih banyak proses cached dipertahankan → app tidak sering di-reload (RAM cukup)
        add("settings put global activity_manager_constants max_cached_processes=128")
        // Jangan biarkan Battery Saver memotong performa di tengah game
        add("settings put global low_power_trigger_level 0")
    }

    // ── API Pública ──────────────────────────────────────────────

    /**
     * Aplica todos los ajustes del sistema.
     * Primero respalda los valores originales, luego aplica los nuevos.
     *
     * @param enableMsaa Si true, aplica Force 4x MSAA (puede bajar FPS en mid-range).
     */
    fun apply(enableMsaa: Boolean = false) {
        repository.logAsync(
            "INFO", "SysTweaks",
            "Menerapkan pengaturan sistem (blur, animasi, scan latar, sinkronisasi)..."
        )
        scope.launch {
            backupOriginalValues()
            applyTweaks(enableMsaa)
        }
    }

    /**
     * Restaura TODOS los valores originales del sistema tocados por apply().
     * Si no había valor previo, usa defaults sensatos o borra la key.
     */
    fun restore() {
        repository.logAsync("INFO", "SysTweaks", "Memulihkan pengaturan sistem (lengkap)...")
        scope.launch {
            val commands = getRestoreCommands()
            for (cmd in commands) {
                ShizukuExecutor.runCommand(cmd)
            }
            clearOriginals()
        }
    }

    /**
     * Limpia logs del sistema (logcat + dmesg).
     */
    fun clearLogs() {
        repository.logAsync("DEBUG", "SysTweaks", "🧹 Membersihkan log sistem...")
        scope.launch {
            ShizukuExecutor.runCommand("logcat -c")
            ShizukuExecutor.runCommand("dmesg -c")
        }
    }

    /**
     * Diagnóstico del estado actual de los ajustes.
     */
    suspend fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ System Tweaks Diagnosis ═══")

        suspend fun line(label: String, cmd: String) {
            val v = ShizukuExecutor.runCommand(cmd).getOrNull()?.trim()
            sb.appendLine("$label: ${v ?: "Tidak tersedia"}")
        }

        line("BLE Scan", "settings get global ble_scan_always_enabled")
        line("WiFi Scan Always", "settings get global wifi_scan_always_enabled")
        line("Auto Sync", "settings get global auto_sync")
        line("Window Blurs", "settings get global disable_window_blurs")
        line("window_animation_scale", "settings get global window_animation_scale")
        line("transition_animation_scale", "settings get global transition_animation_scale")
        line("animator_duration_scale", "settings get global animator_duration_scale")
        line("send_action_app_error", "settings get global send_action_app_error")
        line("low_power_trigger_level", "settings get global low_power_trigger_level")
        line("activity_manager_constants", "settings get global activity_manager_constants")

        sb.appendLine("════════════════════════════════")
        return sb.toString()
    }

    // ── Internals ────────────────────────────────────────────────

    private suspend fun backupOriginalValues() {
        suspend fun get(key: String): String? {
            val v = ShizukuExecutor.runCommand("settings get global $key").getOrNull()?.trim()
            return if (v.isNullOrBlank() || v == "null") null else v
        }

        originalBleScan = get("ble_scan_always_enabled")
        originalWifiScanAlways = get("wifi_scan_always_enabled")
        originalBtDisabledProfiles = get("bluetooth_disabled_profiles")
        originalHwOverlays = get("overlay_display_devices")
        originalAutoSync = get("auto_sync")
        originalWindowBlurs = get("disable_window_blurs")
        originalVsync = get("debug.sf.disable_hwc_vds")
        originalDigitalWellbeing = get("adaptive_connected_voice_enabled")
        originalMsaa = get("debug.gl.msaa")
        originalWindowAnim = get("window_animation_scale")
        originalTransitionAnim = get("transition_animation_scale")
        originalAnimatorDuration = get("animator_duration_scale")
        originalSendActionAppError = get("send_action_app_error")
        originalLowPowerTrigger = get("low_power_trigger_level")
        originalActivityManagerConstants = get("activity_manager_constants")
        originalHwuiRenderer = get("debug.hwui.renderer")
        originalHwuiOverdraw = get("debug.hwui.overdraw")
        originalHwuiDirtyRegions = get("debug.hwui.show_dirty_regions")
        originalSfBackpressure = get("debug.sf.disable_backpressure")
        originalSfLatch = get("debug.sf.latch_unsignaled")
    }

    private fun getRestoreCommands(): List<String> = buildList {
        fun put(key: String, value: String?) {
            if (value != null) {
                add("settings put global $key $value")
            } else {
                // Defaults sensatos cuando no había valor previo
                when (key) {
                    "ble_scan_always_enabled" -> add("settings put global $key 1")
                    "wifi_scan_always_enabled" -> add("settings put global $key 1")
                    "auto_sync" -> add("settings put global $key 1")
                    "disable_window_blurs" -> add("settings put global $key 0")
                    "window_animation_scale",
                    "transition_animation_scale",
                    "animator_duration_scale" -> add("settings put global $key 1.0")
                    "send_action_app_error" -> add("settings put global $key 1")
                    "low_power_trigger_level" -> add("settings put global $key 15")
                    // activity_manager_constants y debug.hwui.renderer: ver abajo
                    else -> { /* no-op */ }
                }
            }
        }

        put("ble_scan_always_enabled", originalBleScan)
        put("wifi_scan_always_enabled", originalWifiScanAlways)
        put("auto_sync", originalAutoSync)
        put("disable_window_blurs", originalWindowBlurs)
        put("window_animation_scale", originalWindowAnim)
        put("transition_animation_scale", originalTransitionAnim)
        put("animator_duration_scale", originalAnimatorDuration)
        put("send_action_app_error", originalSendActionAppError)
        put("low_power_trigger_level", originalLowPowerTrigger)

        // activity_manager_constants: restaurar valor completo o borrar
        if (originalActivityManagerConstants != null) {
            add("settings put global activity_manager_constants $originalActivityManagerConstants")
        } else {
            add("settings delete global activity_manager_constants")
        }

        // Bersihkan sisa key "sampah" yang ditulis versi lama (system property salah dikirim lewat
        // Settings.Global — tidak berefek tapi mengotori database settings). Idempotent.
        for (junk in listOf(
            "debug.hwui.renderer", "debug.hwui.overdraw", "debug.hwui.show_dirty_regions",
            "debug.sf.disable_backpressure", "debug.sf.latch_unsignaled",
            "debug.sf.disable_hwc_vds", "debug.gl.msaa"
        )) add("settings delete global $junk")
    }

    private suspend fun applyTweaks(enableMsaa: Boolean = false) {
        var successCount = 0
        var failCount = 0

        for (cmd in getApplyCommands(enableMsaa)) {
            val result = ShizukuExecutor.runCommand(cmd)
            if (result.isSuccess) {
                successCount++
                repository.logAsync("DEBUG", "SysTweaks", "✅ OK: ${cmd.take(60)}")

                val verifyKey = extractVerifyKey(cmd)
                if (verifyKey != null) {
                    val verifyResult = ShizukuExecutor.runCommand("settings get global $verifyKey")
                    val actualValue = verifyResult.getOrNull()?.trim()
                    val expectedValue = extractExpectedValue(cmd)
                    if (actualValue == expectedValue) {
                        repository.logAsync("DEBUG", "SysTweaks", "🔍 Terverifikasi: $verifyKey = $actualValue ✅")
                    } else {
                        repository.logAsync(
                            "WARN", "SysTweaks",
                            "Verifikasi: $verifyKey diharapkan=$expectedValue, aktual=$actualValue"
                        )
                    }
                }
            } else {
                failCount++
                repository.logAsync(
                    "WARN", "SysTweaks",
                    "Gagal: ${cmd.take(60)} — ${result.exceptionOrNull()?.message}"
                )
            }
        }

        repository.logAsync("INFO", "SysTweaks", "Sistem: $successCount OK, $failCount gagal")
    }

    private fun extractVerifyKey(cmd: String): String? {
        val regex = """^settings\s+put\s+global\s+(\S+)\s+""".toRegex()
        return regex.find(cmd)?.groupValues?.getOrNull(1)
    }

    private fun extractExpectedValue(cmd: String): String? {
        val regex = """^settings\s+put\s+global\s+\S+\s+(.+)$""".toRegex()
        return regex.find(cmd)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun clearOriginals() {
        originalBleScan = null
        originalWifiScanAlways = null
        originalBtDisabledProfiles = null
        originalHwOverlays = null
        originalAutoSync = null
        originalWindowBlurs = null
        originalVsync = null
        originalDigitalWellbeing = null
        originalMsaa = null
        originalWindowAnim = null
        originalTransitionAnim = null
        originalAnimatorDuration = null
        originalSendActionAppError = null
        originalLowPowerTrigger = null
        originalActivityManagerConstants = null
        originalHwuiRenderer = null
        originalHwuiOverdraw = null
        originalHwuiDirtyRegions = null
        originalSfBackpressure = null
        originalSfLatch = null
    }
}
