package com.example.manager

import com.example.data.repository.GameBoostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * PowerOptimizer — Optimizaciones de energía y procesos tomadas de Neon Core.
 *
 * ## Funciones
 * - Force Doze — Fuerza el estado de suspensión profunda (ahorra batería entre partidas)
 * - Force Stop Background Apps — Cierra apps en segundo plano (libera RAM + CPU)
 * - Dex Optimize — Compila la app del juego a código máquina (carga más rápida)
 * - Boot Optimizer — Ejecuta optimización de dex en segundo plano (bg-dexopt-job)
 *
 * ## Comandos
 * - `dumpsys deviceidle force-idle` — Force Doze
 * - `am force-stop <pkg>` — Cierra apps en background ✅ (verificado)
 * - `cmd package compile -f -m speed <pkg>` — Dex compile
 * - `cmd package bg-dexopt-job` — Boot optimizer
 *
 * ## Nota
 * `cmd activity idle-systems` fue descartado porque NO EXISTE en AOSP
 * (verificado en ZTE Neo 2 5G Android 14). Se reemplazó por `am force-stop`.
 */
class PowerOptimizer(
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Flags ────────────────────────────────────────────────────

    @Volatile
    private var bootOptimizerRan = false

    private val lastDexOptimizeByPkg = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val DEX_OPTIMIZE_COOLDOWN = 7 * 24 * 60 * 60 * 1000L // 7 días entre compilaciones

    // ── API Pública ──────────────────────────────────────────────

    /**
     * Fuerza el estado Doze (suspensión profunda) en el dispositivo.
     * Útil cuando la pantalla está apagada durante una partida (ej: carga larga, entre rondas).
     * Ahorra batería significativamente.
     *
     * Se integra con ResourceGovernor (screen off handler).
     */
    fun forceDoze() {
        repository.logAsync("DEBUG", "PowerOpt", "💤 Memaksa mode Doze...")
        scope.launch {
            val result = ShizukuExecutor.runCommand("dumpsys deviceidle force-idle")
            if (result.isSuccess) {
                repository.logAsync("INFO", "PowerOpt", "💤 Mode Doze aktif")
            } else {
                repository.logAsync("WARN", "PowerOpt", "❌ Force Doze gagal: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Menutup proses latar belakang dengan `am kill-all`: hanya proses cached yang aman dibunuh sistem.
     *
     * Sebelumnya memakai `am force-stop` pada daftar proses hasil dumpsys — itu bisa ikut mematikan
     * keyboard (IME), Shizuku, aplikasi mapper (FF Mouse), atau aplikasi telepon di tengah game.
     * `kill-all` tidak menyentuh aplikasi foreground/layanan aktif/IME, jadi aman.
     */
    fun suspendCachedApps(@Suppress("UNUSED_PARAMETER") packageName: String? = null) {
        repository.logAsync("DEBUG", "PowerOpt", "💤 Menutup proses cache latar belakang (am kill-all)...")
        scope.launch {
            val result = ShizukuExecutor.runCommand("am kill-all")
            if (result.isSuccess) {
                repository.logAsync("INFO", "PowerOpt", "💤 Proses cache latar belakang ditutup")
            } else {
                repository.logAsync("WARN", "PowerOpt", "❌ am kill-all gagal: ${result.exceptionOrNull()?.message?.take(80)}")
            }
        }
    }

    /**
     * Compila el paquete del juego a código máquina (modo speed).
     * Esto mejora los tiempos de carga y reduce el lag durante el juego.
     *
     * Solo se ejecuta una vez cada 7 días por paquete para no desgastar
     * el almacenamiento (la compilación genera archivos .odex grandes).
     *
     * @param packageName Package del juego a compilar
     */
    fun dexOptimize(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - (lastDexOptimizeByPkg[packageName] ?: 0L) < DEX_OPTIMIZE_COOLDOWN) {
            repository.logAsync("DEBUG", "PowerOpt", "⏭️ Dex optimize dalam cooldown (7 hari). Dilewati.")
            return
        }

        repository.logAsync("INFO", "PowerOpt", "⚙️ Mengompilasi $packageName ke speed (dex2oat)...")
        scope.launch {
            val result = ShizukuExecutor.runCommand("cmd package compile -f -m speed $packageName")
            if (result.isSuccess) {
                lastDexOptimizeByPkg[packageName] = now
                repository.logAsync("INFO", "PowerOpt", "✅ $packageName terkompilasi ke speed")
            } else {
                repository.logAsync("WARN", "PowerOpt", "❌ Dex compile gagal: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Ejecuta optimización de dex en segundo plano (bg-dexopt-job).
     * Android ya ejecuta esto periódicamente, pero forzarlo al arrancar
     * la app asegura que las apps del sistema estén optimizadas.
     *
     * Solo se ejecuta UNA vez por sesión de la app (no por cada game boost).
     */
    fun bootOptimizer() {
        if (bootOptimizerRan) {
            repository.logAsync("DEBUG", "PowerOpt", "⏭️ Boot optimizer sudah dijalankan pada sesi ini.")
            return
        }

        repository.logAsync("INFO", "PowerOpt", "🚀 Menjalankan boot optimizer (bg-dexopt-job)...")
        scope.launch {
            val result = ShizukuExecutor.runCommand("cmd package bg-dexopt-job")
            if (result.isSuccess) {
                bootOptimizerRan = true
                repository.logAsync("INFO", "PowerOpt", "✅ Boot optimizer selesai")
            } else {
                repository.logAsync("WARN", "PowerOpt", "❌ Boot optimizer gagal: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Resetea el flag de boot optimizer (útil para testing).
     */
    fun resetBootOptimizerFlag() {
        bootOptimizerRan = false
    }

    /**
     * Diagnóstico del estado del PowerOptimizer.
     */
    suspend fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ Power Optimizer Diagnosis ═══")

        // Verificar estado de deviceidle
        val idleState = ShizukuExecutor.runCommand("dumpsys deviceidle get deep")
        sb.appendLine("Device Idle: ${idleState.getOrNull()?.trim() ?: "Tidak tersedia"}")

        sb.appendLine("Boot Optimizer: ${if (bootOptimizerRan) "Sudah dijalankan" else "Tertunda"}")
        sb.appendLine("Dex dikompilasi (per paket): ${lastDexOptimizeByPkg.size}")

        sb.appendLine("══════════════════════════════════")
        return sb.toString()
    }
}
