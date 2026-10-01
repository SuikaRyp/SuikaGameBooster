package com.example.manager

import android.content.Context
import com.example.data.PreferenceManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PerformanceTweaks — tweak performa yang BENAR-BENAR punya efek (tanpa root, via Shizuku/shell),
 * masing-masing diverifikasi dengan baca-ulang dan dipulihkan persis ke kondisi semula.
 *
 *  1. Data Saver (`cmd netpolicy`): data latar belakang aplikasi lain diblokir selama boost, sehingga
 *     tidak ada sync/update/upload yang berebut CPU, radio, dan bandwidth dengan game.
 *     Game + aplikasi ini di-whitelist supaya koneksi game & ping tidak terganggu.
 *  2. Game Mode (`cmd game mode performance <pkg>`, Android 12+): meminta sistem memakai mode
 *     performa untuk game (intervensi/pengaturan yang didefinisikan OEM/game).
 *  3. Tutup proses cache (`am kill-all`): hanya membunuh proses cached/latar belakang yang aman
 *     dibunuh sistem; tidak menyentuh aplikasi foreground, layanan, IME, atau Shizuku.
 *
 * Backup disimpan di SharedPreferences (bukan RAM) sehingga pemulihan tetap benar walau proses
 * app mati di tengah sesi (lihat [restoreIfDirty]).
 */
class PerformanceTweaks(
    private val context: Context,
    private val log: (level: String, tag: String, msg: String) -> Unit
) {
    companion object {
        private const val TAG = "PerfTweaks"
        private const val PREFS = "perf_tweaks_backup"

        /** Preferensi pengguna (default ON): Data Saver selama boost. */
        const val PREF_DATA_SAVER_ON_BOOST = "perf_data_saver_on_boost"

        private const val K_DS_DIRTY = "ds_dirty"
        private const val K_DS_ORIG_ENABLED = "ds_orig_enabled"
        private const val K_DS_WL_ADDED = "ds_wl_added"
        private const val K_GM_PKGS = "gm_pkgs"

        /**
         * Output `cmd netpolicy get restrict-background`:
         * "Restrict background status: enabled|disabled". null = tidak bisa dibaca.
         */
        fun parseRestrictBackground(out: String?): Boolean? {
            val t = out?.lowercase() ?: return null
            return when {
                "enabled" in t && "disabled" !in t -> true
                "disabled" in t -> false
                else -> null
            }
        }

        /** Output `cmd netpolicy list restrict-background-whitelist`: ambil semua UID angka. */
        fun parseUidList(out: String?): Set<Int> {
            val t = out ?: return emptySet()
            val tail = t.substringAfter(":", t)
            return Regex("\\d{4,}").findAll(tail).mapNotNull { it.value.toIntOrNull() }.toSet()
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private fun dataSaverWanted() =
        PreferenceManager.getPrefBoolean(context, PREF_DATA_SAVER_ON_BOOST, true)

    // ── API ───────────────────────────────────────────────────────

    /** Dipanggil saat boost ON. [gamePkg] boleh null (boost manual tanpa game). */
    suspend fun applyBoost(gamePkg: String?) {
        mutex.withLock {
            if (dataSaverWanted()) {
                applyDataSaver(gamePkg)
            } else {
                log("INFO", TAG, "Data Saver dimatikan oleh pengguna — dilewati")
            }
            killBackground()
        }
    }

    /** Dipanggil saat boost OFF / keluar game. Mengembalikan semua yang kita ubah. */
    suspend fun restore() {
        mutex.withLock {
            restoreDataSaver()
            restoreGameMode()
        }
    }

    /** Dipanggil saat service start: kalau sesi sebelumnya mati sebelum restore, bereskan sekarang. */
    suspend fun restoreIfDirty() {
        val dirty = prefs.getBoolean(K_DS_DIRTY, false) ||
            !prefs.getStringSet(K_GM_PKGS, emptySet()).isNullOrEmpty()
        if (!dirty) return
        mutex.withLock {
            log("WARN", TAG, "Sisa perubahan dari sesi sebelumnya ditemukan — memulihkan")
            restoreDataSaver()
            restoreGameMode()
        }
    }

    suspend fun setGameMode(pkg: String, on: Boolean) {
        mutex.withLock {
            if (on) enableGameMode(pkg) else resetGameMode(pkg)
        }
    }

    // ── Data Saver ────────────────────────────────────────────────

    private suspend fun readRestrictBackground(): Boolean? =
        parseRestrictBackground(ShizukuExecutor.runCommand("cmd netpolicy get restrict-background").getOrNull())

    private suspend fun applyDataSaver(gamePkg: String?) {
        val current = readRestrictBackground()
        if (current == null) {
            log("WARN", TAG, "Data Saver: perangkat tidak mendukung `cmd netpolicy` — dilewati")
            return
        }

        // Simpan kondisi asli HANYA sekali per sesi (apply ulang tidak boleh menimpa aslinya).
        if (!prefs.getBoolean(K_DS_DIRTY, false)) {
            prefs.edit()
                .putBoolean(K_DS_DIRTY, true)
                .putBoolean(K_DS_ORIG_ENABLED, current)
                .putStringSet(K_DS_WL_ADDED, emptySet())
                .apply()
        }

        // Whitelist dulu, baru nyalakan Data Saver — jangan sampai game/app ini sempat terblokir.
        val uids = mutableSetOf(context.applicationInfo.uid)
        gamePkg?.let { pkg ->
            try { uids += context.packageManager.getApplicationInfo(pkg, 0).uid } catch (_: Exception) {}
        }
        val already = parseUidList(
            ShizukuExecutor.runCommand("cmd netpolicy list restrict-background-whitelist").getOrNull()
        )
        val added = prefs.getStringSet(K_DS_WL_ADDED, emptySet()).orEmpty().toMutableSet()
        for (uid in uids) {
            if (uid in already) continue
            if (ShizukuExecutor.runCommand("cmd netpolicy add restrict-background-whitelist $uid").isSuccess) {
                added += uid.toString()
            }
        }
        prefs.edit().putStringSet(K_DS_WL_ADDED, added).apply()

        if (current != true) ShizukuExecutor.runCommand("cmd netpolicy set restrict-background true")

        val verified = readRestrictBackground()
        if (verified == true) {
            log("INFO", TAG, "✅ Data Saver aktif (terverifikasi). Whitelist: ${uids.joinToString()}")
        } else {
            log("WARN", TAG, "⚠️ Data Saver gagal diaktifkan (status=$verified)")
        }
    }

    private suspend fun restoreDataSaver() {
        if (!prefs.getBoolean(K_DS_DIRTY, false)) return
        var ok = true

        // Hapus hanya whitelist yang kita tambahkan sendiri.
        for (uid in prefs.getStringSet(K_DS_WL_ADDED, emptySet()).orEmpty()) {
            val r = ShizukuExecutor.runCommand("cmd netpolicy remove restrict-background-whitelist $uid")
            if (r.isFailure) ok = false
        }

        val original = prefs.getBoolean(K_DS_ORIG_ENABLED, false)
        ShizukuExecutor.runCommand("cmd netpolicy set restrict-background $original")
        if (readRestrictBackground() != original) ok = false

        if (ok) {
            prefs.edit().remove(K_DS_DIRTY).remove(K_DS_ORIG_ENABLED).remove(K_DS_WL_ADDED).apply()
            log("INFO", TAG, "Data Saver dipulihkan ke semula (${if (original) "aktif" else "nonaktif"})")
        } else {
            // Biarkan dirty=true supaya dicoba lagi (restoreIfDirty / restore berikutnya).
            log("ERROR", TAG, "Pemulihan Data Saver belum tuntas — akan dicoba lagi")
        }
    }

    // ── Game Mode ─────────────────────────────────────────────────

    private suspend fun enableGameMode(pkg: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val r = ShizukuExecutor.runCommand("cmd game mode performance $pkg").let {
            if (it.isSuccess) it else ShizukuExecutor.runCommand("cmd game set --mode performance $pkg")
        }
        if (r.isSuccess) {
            val set = prefs.getStringSet(K_GM_PKGS, emptySet()).orEmpty().toMutableSet()
            set += pkg
            prefs.edit().putStringSet(K_GM_PKGS, set).apply()
            log("INFO", TAG, "✅ Game Mode PERFORMANCE untuk $pkg")
        } else {
            log("WARN", TAG, "Game Mode tidak tersedia untuk $pkg: ${r.exceptionOrNull()?.message?.take(80)}")
        }
    }

    private suspend fun resetGameMode(pkg: String) {
        val r = ShizukuExecutor.runCommand("cmd game reset $pkg").let {
            if (it.isSuccess) it else ShizukuExecutor.runCommand("cmd game mode standard $pkg")
        }
        if (r.isSuccess) {
            val set = prefs.getStringSet(K_GM_PKGS, emptySet()).orEmpty().toMutableSet()
            set -= pkg
            prefs.edit().putStringSet(K_GM_PKGS, set).apply()
        }
    }

    private suspend fun restoreGameMode() {
        for (pkg in prefs.getStringSet(K_GM_PKGS, emptySet()).orEmpty().toList()) resetGameMode(pkg)
    }

    // ── Proses latar ──────────────────────────────────────────────

    /** `am kill-all`: hanya proses cached/latar yang aman dibunuh sistem. */
    suspend fun killBackground() {
        val r = ShizukuExecutor.runCommand("am kill-all")
        if (r.isSuccess) log("INFO", TAG, "🧹 Proses cache latar belakang ditutup (am kill-all)")
        else log("WARN", TAG, "am kill-all gagal: ${r.exceptionOrNull()?.message?.take(80)}")
    }
}
