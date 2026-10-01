package com.example.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penyimpanan hasil pemindaian + pengaturan antivirus (SharedPreferences, tanpa Room
 * supaya tidak perlu migrasi database).
 */
object ScanStore {
    private const val PREFS = "antivirus_store"
    private const val KEY_RESULTS = "results"
    private const val KEY_KNOWN = "known_packages"
    private const val KEY_IGNORED = "ignored"
    private const val KEY_PENDING = "pending"
    private const val KEY_REALTIME = "realtime_enabled"
    private const val KEY_LAST_FULL = "last_full_scan"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Hasil pemindaian ─────────────────────────────────────────────

    @Synchronized
    fun getResults(ctx: Context): List<ScanResult> {
        val root = readObject(ctx, KEY_RESULTS)
        val out = ArrayList<ScanResult>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = root.optJSONObject(k) ?: continue
            try { out.add(ScanResult.fromJson(o)) } catch (ignored: Exception) { /* entri rusak dilewati */ }
        }
        return out
    }

    @Synchronized
    fun getResult(ctx: Context, pkg: String): ScanResult? {
        val o = readObject(ctx, KEY_RESULTS).optJSONObject(pkg) ?: return null
        return try { ScanResult.fromJson(o) } catch (ignored: Exception) { null }
    }

    @Synchronized
    fun putResult(ctx: Context, r: ScanResult) {
        val root = readObject(ctx, KEY_RESULTS)
        root.put(r.packageName, r.toJson())
        prefs(ctx).edit().putString(KEY_RESULTS, root.toString()).apply()
    }

    @Synchronized
    fun removeResult(ctx: Context, pkg: String) {
        val root = readObject(ctx, KEY_RESULTS)
        root.remove(pkg)
        prefs(ctx).edit().putString(KEY_RESULTS, root.toString()).apply()
        removePending(ctx, pkg)
        val ign = readObject(ctx, KEY_IGNORED)
        ign.remove(pkg)
        prefs(ctx).edit().putString(KEY_IGNORED, ign.toString()).apply()
    }

    // ── Snapshot aplikasi terpasang (untuk mendeteksi aplikasi baru) ─

    /** null = belum pernah ada snapshot (pertama kali dijalankan). */
    @Synchronized
    fun getKnownPackages(ctx: Context): Set<String>? {
        val s = prefs(ctx).getString(KEY_KNOWN, null) ?: return null
        return try {
            val arr = JSONArray(s)
            val set = HashSet<String>()
            for (i in 0 until arr.length()) set.add(arr.optString(i))
            set
        } catch (ignored: Exception) { null }
    }

    @Synchronized
    fun setKnownPackages(ctx: Context, pkgs: Collection<String>) {
        val arr = JSONArray()
        pkgs.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY_KNOWN, arr.toString()).apply()
    }

    // ── "Biarkan" (abaikan) — per paket + versionCode ────────────────

    @Synchronized
    fun ignore(ctx: Context, pkg: String, versionCode: Long) {
        val o = readObject(ctx, KEY_IGNORED)
        o.put(pkg, versionCode)
        prefs(ctx).edit().putString(KEY_IGNORED, o.toString()).apply()
        removePending(ctx, pkg)
    }

    @Synchronized
    fun isIgnored(ctx: Context, pkg: String, versionCode: Long): Boolean {
        val o = readObject(ctx, KEY_IGNORED)
        return o.has(pkg) && o.optLong(pkg, -1L) == versionCode
    }

    // ── Antrean peringatan yang belum ditangani ──────────────────────

    @Synchronized
    fun addPending(ctx: Context, pkg: String) {
        val list = pendingList(ctx).toMutableList()
        if (!list.contains(pkg)) list.add(pkg)
        writePending(ctx, list)
    }

    @Synchronized
    fun removePending(ctx: Context, pkg: String) {
        writePending(ctx, pendingList(ctx).filter { it != pkg })
    }

    @Synchronized
    fun pendingList(ctx: Context): List<String> {
        val s = prefs(ctx).getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (ignored: Exception) { emptyList() }
    }

    private fun writePending(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    // ── Pengaturan ───────────────────────────────────────────────────

    fun isRealtimeEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_REALTIME, true)

    fun setRealtimeEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_REALTIME, enabled).apply()
    }

    fun getLastFullScan(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_FULL, 0L)

    fun setLastFullScan(ctx: Context, time: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_FULL, time).apply()
    }

    private fun readObject(ctx: Context, key: String): JSONObject {
        val s = prefs(ctx).getString(key, null) ?: return JSONObject()
        return try { JSONObject(s) } catch (ignored: Exception) { JSONObject() }
    }
}
