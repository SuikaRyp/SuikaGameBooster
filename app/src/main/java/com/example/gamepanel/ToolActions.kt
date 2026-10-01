package com.example.gamepanel

import android.app.NotificationManager
import android.app.role.RoleManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.manager.ShizukuExecutor
import com.example.service.UnifiedAccessibilityService

/** Izin yang mungkin dibutuhkan alat. */
enum class Perm(val title: String, val why: String) {
    OVERLAY("Tampil di atas aplikasi lain", "Diperlukan agar panel, HUD, dan gestur tepi bisa muncul di atas game."),
    WRITE_SETTINGS("Ubah pengaturan sistem", "Diperlukan untuk mengubah kecerahan dan kunci rotasi."),
    DND("Akses Jangan Ganggu", "Diperlukan untuk memblokir notifikasi dan mengaktifkan mode Jangan Ganggu secara resmi."),
    ACCESSIBILITY("Layanan Aksesibilitas", "Diperlukan untuk mengambil tangkapan layar lewat fungsi sistem."),
    CALL_ROLE("Penyaring panggilan", "Diperlukan agar aplikasi bisa menolak panggilan masuk selama game (peran Call Screening)."),
    PROJECTION("Rekam layar", "Android meminta izin resmi tiap kali merekam layar (MediaProjection).")
}

object ToolActions {
    private const val TAG = "ToolActions"

    // ────────────────────────────────────────────────────────────────
    // Izin
    // ────────────────────────────────────────────────────────────────

    fun has(c: Context, p: Perm): Boolean = when (p) {
        Perm.OVERLAY -> Settings.canDrawOverlays(c)
        Perm.WRITE_SETTINGS -> Settings.System.canWrite(c)
        Perm.DND -> (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        Perm.ACCESSIBILITY -> UnifiedAccessibilityService.isServiceRunning
        Perm.CALL_ROLE -> hasCallRole(c)
        Perm.PROJECTION -> true // diminta saat mulai merekam
    }

    fun hasCallRole(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val rm = c.getSystemService(Context.ROLE_SERVICE) as RoleManager
            rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        } catch (e: Exception) { false }
    }

    fun callRoleSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Buka layar pengaturan yang sesuai untuk memberikan izin. */
    fun grant(c: Context, p: Perm) {
        try {
            when (p) {
                Perm.OVERLAY -> start(c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
                Perm.WRITE_SETTINGS -> start(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${c.packageName}")))
                Perm.DND -> start(c, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                Perm.ACCESSIBILITY -> start(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Perm.CALL_ROLE -> start(c, Intent(c, PanelHelperActivity::class.java).setAction(PanelHelperActivity.ACTION_CALL_ROLE))
                Perm.PROJECTION -> start(c, Intent(c, PanelHelperActivity::class.java).setAction(PanelHelperActivity.ACTION_PROJECTION))
            }
        } catch (e: Exception) {
            Log.w(TAG, "grant($p): ${e.message}")
        }
    }

    private fun start(c: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }

    // ────────────────────────────────────────────────────────────────
    // Jangan Ganggu / blokir notifikasi (API resmi NotificationManager)
    // ────────────────────────────────────────────────────────────────

    private const val PREF = "game_panel_state"

    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun currentFilter(c: Context): Int = nm(c).currentInterruptionFilter

    /**
     * Terapkan filter gangguan. Filter sebelumnya disimpan sekali agar bisa dipulihkan persis.
     * @return false jika izin belum diberikan
     */
    fun setInterruption(c: Context, filter: Int): Boolean {
        val n = nm(c)
        if (!n.isNotificationPolicyAccessGranted) return false
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!sp.contains("prev_filter") && filter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            sp.edit().putInt("prev_filter", n.currentInterruptionFilter).apply()
        }
        n.setInterruptionFilter(filter)
        return true
    }

    fun restoreInterruption(c: Context) {
        val n = nm(c)
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!sp.contains("prev_filter")) return
        val prev = sp.getInt("prev_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
        sp.edit().remove("prev_filter").apply()
        if (n.isNotificationPolicyAccessGranted) {
            try { n.setInterruptionFilter(if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else prev) }
            catch (e: Exception) { Log.w(TAG, "restore DND: ${e.message}") }
        }
    }

    fun isDndOn(c: Context): Boolean =
        currentFilter(c).let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }

    // ────────────────────────────────────────────────────────────────
    // Kecerahan (WRITE_SETTINGS)
    // ────────────────────────────────────────────────────────────────

    fun getBrightnessPct(c: Context): Int? = try {
        Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
    } catch (e: Exception) { null }

    fun setBrightnessPct(c: Context, pct: Int): Boolean {
        if (!Settings.System.canWrite(c)) return false
        return try {
            val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (!sp.contains("prev_brightness")) {
                val cur = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                val mode = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                sp.edit().putInt("prev_brightness", cur).putInt("prev_bmode", mode).apply()
            }
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct.coerceIn(1, 100) * 255 / 100))
            true
        } catch (e: Exception) { Log.w(TAG, "brightness: ${e.message}"); false }
    }

    fun restoreBrightness(c: Context) {
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!sp.contains("prev_brightness") || !Settings.System.canWrite(c)) return
        try {
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, sp.getInt("prev_brightness", 128))
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, sp.getInt("prev_bmode", 0))
        } catch (e: Exception) { Log.w(TAG, "restore brightness: ${e.message}") }
        sp.edit().remove("prev_brightness").remove("prev_bmode").apply()
    }

    // ────────────────────────────────────────────────────────────────
    // Volume / mute game
    // ────────────────────────────────────────────────────────────────

    private fun am(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun mediaVolumePct(c: Context): Int {
        val a = am(c)
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setMediaVolumePct(c: Context, pct: Int) {
        val a = am(c)
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try { a.setStreamVolume(AudioManager.STREAM_MUSIC, (pct.coerceIn(0, 100) * max / 100), 0) }
        catch (e: SecurityException) { Log.w(TAG, "volume: ${e.message}") }
    }

    fun isGameMuted(c: Context): Boolean = am(c).getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    fun toggleGameMute(c: Context) {
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val a = am(c)
        try {
            if (a.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                sp.edit().putInt("prev_music_vol", a.getStreamVolume(AudioManager.STREAM_MUSIC)).apply()
                a.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } else {
                val prev = sp.getInt("prev_music_vol", a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2)
                a.setStreamVolume(AudioManager.STREAM_MUSIC, prev.coerceAtLeast(1), 0)
            }
        } catch (e: SecurityException) { Log.w(TAG, "mute: ${e.message}") }
    }

    // ────────────────────────────────────────────────────────────────
    // Wi-Fi / Bluetooth (Android modern tidak mengizinkan app biasa mengubahnya langsung)
    // ────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun wifiOn(c: Context): Boolean? = try {
        (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (e: Exception) { null }

    /** @return teks hasil untuk ditampilkan ke pengguna */
    @Suppress("DEPRECATION")
    fun toggleWifi(c: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                start(c, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                "Panel koneksi dibuka (Android 10+ tidak mengizinkan app mengubah Wi-Fi langsung)"
            } else {
                val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.isWifiEnabled = !wm.isWifiEnabled
                if (wm.isWifiEnabled) "Wi-Fi dinyalakan" else "Wi-Fi dimatikan"
            }
        } catch (e: Exception) { "Fitur tidak tersedia pada perangkat ini." }
    }

    fun bluetoothOn(c: Context): Boolean? = try {
        (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled
    } catch (e: SecurityException) { null } catch (e: Exception) { null }

    fun openBluetooth(c: Context): String = try {
        start(c, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        "Pengaturan Bluetooth dibuka (Android tidak mengizinkan app mengubahnya langsung)"
    } catch (e: Exception) { "Fitur tidak tersedia pada perangkat ini." }

    // ────────────────────────────────────────────────────────────────
    // Rotasi & tampilan sentuhan
    // ────────────────────────────────────────────────────────────────

    fun autoRotateOn(c: Context): Boolean =
        Settings.System.getInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    fun setAutoRotate(c: Context, on: Boolean): Boolean {
        if (!Settings.System.canWrite(c)) return false
        return try {
            Settings.System.putInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            true
        } catch (e: Exception) { false }
    }

    fun showTouchesOn(c: Context): Boolean =
        Settings.System.getInt(c.contentResolver, "show_touches", 0) == 1

    /**
     * "Tampilkan sentuhan" (opsi developer). Butuh WRITE_SECURE_SETTINGS (diberikan lewat Shizuku/ADB).
     * @return true jika berhasil dan terverifikasi
     */
    suspend fun setShowTouches(c: Context, on: Boolean): Boolean {
        val v = if (on) 1 else 0
        try {
            Settings.System.putInt(c.contentResolver, "show_touches", v)
            if (showTouchesOn(c) == on) return true
        } catch (e: Exception) { /* lanjut ke Shizuku */ }
        if (ShizukuExecutor.isReady()) {
            ShizukuExecutor.runCommand("settings put system show_touches $v")
            return showTouchesOn(c) == on
        }
        return false
    }

    fun launchGame(c: Context, pkg: String): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { c.startActivity(i); true } catch (e: Exception) { false }
    }
}
